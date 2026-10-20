"""Authenticated, single-controller sessions over an async transport."""

import asyncio
import json
import logging
import secrets
import time
from collections import deque
from typing import Protocol

from .mouse import InputAdapter, MouseSession
from .protocol import VERSION, ProtocolError, Text, _decode, _fields, parse_command, parse_pair
from .trust import TrustStore, identifier, public_key

log = logging.getLogger(__name__)
AUTH_TIMEOUT = 5.0
IDLE_TIMEOUT = 5.0
MAX_PENDING_PAIRS = 16
MAX_FAILED_PAIRS = 5
PAIR_FAILURE_WINDOW = 60.0


class Connection(Protocol):
    """Transport contract, implemented by WebSockets or an in-memory test connection."""

    async def recv(self) -> str | bytes | None:
        """Wait for a message, returning None when the connection has ended."""
        ...

    async def send(self, message: str) -> None:
        """Send a text message to the client."""
        ...

    async def close(self, code: int, reason: str) -> None:
        """End the connection with a machine-readable code and a short explanation."""
        ...


class RateLimit:
    """Token bucket: 240 messages/second with a 60-message burst."""

    def __init__(self) -> None:
        """Start with a full burst allowance and a monotonic refill timestamp."""
        self.tokens = 60.0
        self.updated = time.monotonic()

    def allow(self) -> bool:
        """Refill by elapsed time, then spend one token if a message is permitted."""
        # The monotonic clock measures elapsed time independently of clock changes.
        now = time.monotonic()
        self.tokens = min(60.0, self.tokens + (now - self.updated) * 240)
        self.updated = now
        if self.tokens < 1:
            return False
        self.tokens -= 1
        return True


class Receiver:
    """Authenticate clients and give one connection exclusive control of the mouse."""

    def __init__(self, adapter: InputAdapter, pin: str, trust: TrustStore | None = None) -> None:
        """Create shared mouse state and save this receiver run's five-digit PIN."""
        self.mouse = MouseSession(adapter)
        self.pin = pin
        self.trust = trust if trust is not None else TrustStore()
        self.failed_pairs: deque[float] = deque()
        self.active: Connection | None = None
        self.pending_pairs = 0
        self.stopping = False

    def stop(self) -> None:
        """Prevent new input and immediately attempt to release held buttons."""
        self.stopping = True
        self.mouse.release_all()

    def pairing_blocked(self) -> bool:
        """Share a bounded failure budget across connections so reconnecting cannot bypass it."""
        cutoff = time.monotonic() - PAIR_FAILURE_WINDOW
        while self.failed_pairs and self.failed_pairs[0] <= cutoff:
            self.failed_pairs.popleft()
        return len(self.failed_pairs) >= MAX_FAILED_PAIRS

    async def handle(self, connection: Connection) -> None:
        """Pair one client, process its input, and clean up whenever its session ends."""
        if self.stopping:
            await connection.close(1001, "Receiver stopped")
            return
        if self.pairing_blocked():
            await connection.close(4003, "Too many PIN attempts; wait one minute")
            return
        if self.pending_pairs >= MAX_PENDING_PAIRS:
            await connection.close(4009, "Too many pending connections")
            return
        self.pending_pairs += 1
        # Pairing has a separate deadline and capacity limit. Clients that have
        # not authenticated never own the mouse or trigger mouse cleanup.
        try:
            try:
                enrollment = None
                remembered = False
                # The complete challenge exchange shares one authentication deadline.
                async with asyncio.timeout(AUTH_TIMEOUT):
                    raw = await connection.recv()
                    if raw is None:
                        return
                    if self.pairing_blocked():
                        await connection.close(4003, "Too many PIN attempts; wait one minute")
                        return
                    request = _decode(raw)
                    if request.get("type") == "resume":
                        if type(request.get("version")) is not int or request["version"] != VERSION:
                            raise ProtocolError("Unsupported protocol version")
                        remembered = await self.trust.resume(connection, request)
                    else:
                        if "device_id" in request or "public_key" in request:
                            _fields(request, "version", "pin", "device_id", "public_key")
                            device = identifier(request.pop("device_id"))
                            encoded = request.pop("public_key")
                            public_key(encoded)
                            enrollment = (device, encoded)
                        supplied = parse_pair(json.dumps(request))
                        if not secrets.compare_digest(supplied, self.pin):
                            raise ProtocolError("Invalid PIN")
            except (ProtocolError, TimeoutError):
                if not self.pairing_blocked():
                    self.failed_pairs.append(time.monotonic())
                await connection.close(4001, "Pairing failed")
                return
        finally:
            # Always free the pairing slot, even after a timeout or cancellation.
            self.pending_pairs -= 1

        # No await between checking and claiming ownership on this event loop.
        if self.stopping:
            await connection.close(1001, "Receiver stopped")
            return
        if self.active is not None:
            await connection.close(4009, "Another controller is connected")
            return
        self.active = connection
        close_code, close_reason = 1000, "Session ended"
        try:
            response = {"type": "paired", "version": VERSION}
            if enrollment is not None:
                self.trust.enroll(*enrollment)
                response["receiver_id"] = self.trust.receiver_id
                response["expires_at"] = int(self.trust.devices[enrollment[0]]["expires"])
            elif remembered:
                response["receiver_id"] = self.trust.receiver_id
                response["expires_at"] = int(self.trust.devices[remembered]["expires"])
            await connection.send(json.dumps(response))
            log.info("Controller paired")
            rate = RateLimit()
            while not self.stopping:
                # await lets other connections and shutdown signals run while
                # this client is idle. Each new message gets a fresh deadline.
                async with asyncio.timeout(IDLE_TIMEOUT):
                    raw = await connection.recv()
                if raw is None:
                    break
                if self.stopping:
                    break
                if not rate.allow():
                    close_code, close_reason = 4008, "Message rate exceeded"
                    break
                # Validation completes before any command can reach the backend.
                command = parse_command(raw)
                if isinstance(command, Text):
                    try:
                        self.mouse.apply(command)
                    except Exception:
                        log.exception("Text input failed; controller remains connected")
                        self.mouse.release_all()
                        await connection.send(json.dumps({"type": "input_error",
                            "message": "Text could not be typed. Check the receiver terminal.",
                        }))
                else:
                    self.mouse.apply(command)
        except ProtocolError:
            close_code, close_reason = 4002, "Invalid input message"
        except TimeoutError:
            close_code, close_reason = 4000, "Heartbeat timeout"
        except Exception:
            log.exception("Controller session failed")
            close_code, close_reason = 1011, "Receiver error"
        finally:
            # Release before the closing handshake, which may wait on the peer.
            try:
                self.mouse.release_all()
            except Exception:
                # Do not transfer control while an injected button may still be down.
                self.stopping = True
                log.exception("Mouse cleanup failed; restart the receiver")
                close_code, close_reason = 1011, "Mouse cleanup failed"
            finally:
                self.active = None
                await connection.close(close_code, close_reason)
                log.info("Controller disconnected; held buttons released")

"""Remember public device keys; private signing keys never leave the phone."""

import base64
import json
import os
import re
import secrets
import tempfile
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .protocol import ProtocolError, _decode, _fields

TRUST_SECONDS = 90 * 24 * 60 * 60


def identifier(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{32}", value):
        raise ProtocolError("Invalid device or receiver identifier")
    return value


def public_key(encoded: str) -> ec.EllipticCurvePublicKey:
    try:
        key = serialization.load_der_public_key(base64.b64decode(encoded, validate=True))
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            raise ValueError("Expected P-256")
        return key
    except (ValueError, TypeError) as exc:
        raise ProtocolError("Invalid public key") from exc


def proof_payload(receiver: str, device: str, nonce: str) -> bytes:
    return f"lanpad-resume-v4\n{receiver}\n{device}\n{nonce}".encode("ascii")


class TrustStore:
    """An integrity-sensitive public-key registry; tests can use an in-memory store."""

    def __init__(self, path: Path | None = None):
        self.path = path
        self.receiver_id = secrets.token_hex(16)
        self.devices: dict[str, dict] = {}
        if path is not None and path.exists():
            data = json.loads(path.read_text())
            self.receiver_id = identifier(data["receiver_id"])
            self.devices = data["devices"]
            if not isinstance(self.devices, dict) or len(self.devices) > 32:
                raise ValueError("Invalid trusted-device registry")
            for device, entry in self.devices.items():
                identifier(device)
                public_key(entry["public_key"])
                if type(entry["expires"]) not in (int, float):
                    raise ValueError("Invalid device expiry")

    def save(self):
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd, temporary = tempfile.mkstemp(dir=self.path.parent, prefix=".devices-")
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump({"receiver_id": self.receiver_id, "devices": self.devices}, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def enroll(self, device: str, encoded: str):
        identifier(device)
        public_key(encoded)
        self.devices = {key: value for key, value in self.devices.items()
                        if value["expires"] > time.time()}
        if device not in self.devices and len(self.devices) >= 32:
            raise ProtocolError("Trusted-device limit reached; revoke an old device")
        self.devices[device] = {"public_key": encoded, "expires": time.time() + TRUST_SECONDS}
        self.save()

    async def resume(self, connection, request: dict):
        _fields(request, "version", "device_id", "receiver_id")
        device = identifier(request["device_id"])
        if request["receiver_id"] != self.receiver_id:
            raise ProtocolError("Receiver identity changed")
        entry = self.devices.get(device)
        if entry is None or entry["expires"] <= time.time():
            raise ProtocolError("Unknown or expired device")
        nonce = secrets.token_hex(32)
        await connection.send(json.dumps({"type": "challenge", "receiver_id": self.receiver_id,
                                          "nonce": nonce}))
        proof = _decode(await connection.recv())
        _fields(proof, "signature")
        if proof["type"] != "proof" or not isinstance(proof["signature"], str):
            raise ProtocolError("Invalid device proof")
        try:
            signature = base64.b64decode(proof["signature"], validate=True)
            public_key(entry["public_key"]).verify(
                signature, proof_payload(self.receiver_id, device, nonce),
                ec.ECDSA(hashes.SHA256()),
            )
        except (ValueError, InvalidSignature) as exc:
            raise ProtocolError("Invalid device proof") from exc
        return device

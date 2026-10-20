import asyncio
import base64
import json
import stat
import time

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from test_receiver import Connection

from lanpad.protocol import VERSION
from lanpad.receiver import Receiver
from lanpad.trust import TrustStore, proof_payload

DEVICE = "a" * 32
PIN = "01234"


@pytest.fixture
def identity():
    key = ec.generate_private_key(ec.SECP256R1())
    public = base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    )).decode()
    return key, public


def test_enrollment_is_pin_gated_persistent_and_private(adapter, identity, tmp_path):
    async def scenario():
        _, public = identity
        path = tmp_path / "config" / "devices.json"
        store = TrustStore(path)
        request = {"type": "pair", "version": VERSION, "pin": "99999",
                   "device_id": DEVICE, "public_key": public}
        rejected = Connection(json.dumps(request))
        await Receiver(adapter, PIN, store).handle(rejected)
        assert rejected.closed[0] == 4001
        assert not path.exists() and not store.devices
        request["pin"] = PIN
        accepted = Connection(json.dumps(request), None)
        await Receiver(adapter, PIN, store).handle(accepted)
        response = await accepted.outgoing.get()
        assert response == {"type": "paired", "version": VERSION,
                            "receiver_id": store.receiver_id,
                            "expires_at": int(store.devices[DEVICE]["expires"])}
        loaded = TrustStore(path)
        assert loaded.devices == store.devices
        assert loaded.receiver_id == store.receiver_id
        if __import__("os").name != "nt":
            assert stat.S_IMODE(path.stat().st_mode) == 0o600
        assert "PRIVATE KEY" not in path.read_text()
    asyncio.run(scenario())


def test_fresh_challenges_reject_replay_wrong_keys_expiry_and_revocation(adapter, identity):
    async def scenario():
        private, public = identity
        store = TrustStore()
        store.enroll(DEVICE, public)
        receiver = Receiver(adapter, PIN, store)
        request = json.dumps({"type": "resume", "version": VERSION,
                              "receiver_id": store.receiver_id, "device_id": DEVICE})

        async def attempt(signature=None, signing_key=private):
            connection = Connection(request)
            task = asyncio.create_task(receiver.handle(connection))
            challenge = await asyncio.wait_for(connection.outgoing.get(), 1)
            assert challenge["type"] == "challenge"
            if signature is None:
                signature = base64.b64encode(signing_key.sign(
                    proof_payload(store.receiver_id, DEVICE, challenge["nonce"]),
                    ec.ECDSA(hashes.SHA256()),
                )).decode()
            connection.input({"type": "proof", "signature": signature})
            connection.input(None)
            await task
            return connection, signature, challenge["nonce"]

        accepted, signature, nonce = await attempt()
        assert accepted.closed[0] == 1000
        assert (await accepted.outgoing.get())["receiver_id"] == store.receiver_id
        replayed, _, next_nonce = await attempt(signature)
        assert next_nonce != nonce and replayed.closed[0] == 4001
        rejected, _, _ = await attempt(signing_key=ec.generate_private_key(ec.SECP256R1()))
        assert rejected.closed[0] == 4001
        store.devices[DEVICE]["expires"] = time.time() - 1
        expired = Connection(request)
        await receiver.handle(expired)
        assert expired.closed[0] == 4001
        store.devices.clear()
        revoked = Connection(request)
        await receiver.handle(revoked)
        assert revoked.closed[0] == 4001
        assert adapter.events == []
    asyncio.run(scenario())


def test_text_backend_failure_reports_error_and_accepts_later_keys(adapter):
    async def scenario():
        def fail(text):
            raise RuntimeError("Text backend unavailable")
        adapter.text = fail
        connection = Connection(json.dumps({"type": "pair", "version": VERSION, "pin": PIN}))
        connection.input({"type": "text", "text": "hello"})
        connection.input({"type": "key", "key": "enter", "modifiers": []})
        connection.input(None)
        await Receiver(adapter, PIN).handle(connection)
        assert connection.closed[0] == 1000
        assert (await connection.outgoing.get())["type"] == "paired"
        assert (await connection.outgoing.get())["type"] == "input_error"
        assert adapter.events == [("key", "enter", True), ("key", "enter", False)]
    asyncio.run(scenario())

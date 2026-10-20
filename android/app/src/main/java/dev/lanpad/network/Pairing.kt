package dev.lanpad.network

import org.json.JSONObject

interface DeviceIdentity {
    val deviceId: String
    val publicKey: String
    fun sign(payload: ByteArray): String
}

/** Device authentication uses fresh, receiver-bound ECDSA challenges; PINs aren't stored. */
object Pairing {
    const val VERSION = 4
    fun validPin(pin: String): Boolean = pin.matches(Regex("[0-9]{5}"))
    fun validId(id: String): Boolean = id.matches(Regex("[0-9a-f]{32}"))

    fun message(pin: String, identity: DeviceIdentity? = null): String {
        require(validPin(pin)) { "Enter the five-digit PIN shown on your computer." }
        return JSONObject().put("type", "pair").put("version", VERSION).put("pin", pin).apply {
            if (identity != null) {
                put("device_id", identity.deviceId)
                put("public_key", identity.publicKey)
            }
        }.toString()
    }

    fun resume(receiver: String, identity: DeviceIdentity): String = JSONObject()
        .put("type", "resume").put("version", VERSION).put("receiver_id", receiver)
        .put("device_id", identity.deviceId).toString()

    fun proof(receiver: String, nonce: String, identity: DeviceIdentity): String {
        require(validId(receiver) && nonce.matches(Regex("[0-9a-f]{64}")))
        val payload = "lanpad-resume-v4\n$receiver\n${identity.deviceId}\n$nonce".toByteArray(Charsets.US_ASCII)
        return JSONObject().put("type", "proof").put("signature", identity.sign(payload)).toString()
    }
}

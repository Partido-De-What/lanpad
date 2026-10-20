package dev.lanpad.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.lanpad.network.DeviceIdentity
import dev.lanpad.network.Endpoint
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Only public receiver IDs are preferences. The private key stays in Android Keystore. */
class DeviceTrust(context: Context) : DeviceIdentity {
    private val preferences = context.getSharedPreferences("trusted_receivers", Context.MODE_PRIVATE)
    private val alias = "lanpad-device-v4"
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val identity by lazy {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }
    override val publicKey: String get() = Base64.encodeToString(identity.certificate.publicKey.encoded, Base64.NO_WRAP)
    override val deviceId: String get() = MessageDigest.getInstance("SHA-256")
        .digest(identity.certificate.publicKey.encoded).take(16).joinToString("") { "%02x".format(it) }
    override fun sign(payload: ByteArray): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(identity.privateKey)
        update(payload)
        Base64.encodeToString(sign(), Base64.NO_WRAP)
    }

    private fun key(name: String, endpoint: Endpoint) =
        if (name.isNotBlank()) "name:$name" else "address:${endpoint.host}:${endpoint.port}"
    fun receiver(name: String, endpoint: Endpoint): String? = preferences.getString(key(name, endpoint), null)
    fun expiresAt(name: String, endpoint: Endpoint): Long = preferences.getLong("expiry:${key(name, endpoint)}", 0)
    fun remember(name: String, endpoint: Endpoint, receiver: String, expiresAt: Long) {
        check(preferences.edit().putString(key(name, endpoint), receiver)
            .putLong("expiry:${key(name, endpoint)}", expiresAt).commit()) { "Could not save trusted computer" }
    }
    fun forgetAll() { preferences.edit().clear().apply() }
}

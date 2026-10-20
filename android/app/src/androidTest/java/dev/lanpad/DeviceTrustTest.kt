package dev.lanpad

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.lanpad.data.DeviceTrust
import dev.lanpad.network.Pairing
import dev.lanpad.network.PadClient
import dev.lanpad.network.Endpoint
import dev.lanpad.input.Command
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class DeviceTrustTest {
    @Test fun keystoreSignatureMatchesTheReceiverProtocolAndSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = DeviceTrust(context)
        val receiver = "a".repeat(32)
        val nonce = "b".repeat(64)
        val proof = JSONObject(Pairing.proof(receiver, nonce, identity))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(
            Base64.decode(identity.publicKey, Base64.NO_WRAP)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update("lanpad-resume-v4\n$receiver\n${identity.deviceId}\n$nonce".toByteArray(Charsets.US_ASCII))
        assertTrue(verifier.verify(Base64.decode(proof.getString("signature"), Base64.NO_WRAP)))
        assertEquals(identity.publicKey, DeviceTrust(context).publicKey)
        assertTrue(Pairing.validId(identity.deviceId))
    }

    @Test fun realReceiverAcceptsPinEnrollmentThenPinlessKeystoreReconnect() = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("receiverPort")?.toIntOrNull()
        assumeTrue("Requires the opt-in loopback receiver fixture", port != null)
        val identity = DeviceTrust(InstrumentationRegistry.getInstrumentation().targetContext)
        val client = PadClient(this)
        var remembered: String? = null
        try {
            val endpoint = Endpoint("127.0.0.1", requireNotNull(port))
            client.connect(endpoint, "01234", identity, onRemembered = { receiver, expiry ->
                remembered = receiver
                assertTrue(expiry > System.currentTimeMillis() / 1000)
            })
            withTimeout(7000) { client.state.first { it.ready } }
            assertTrue(Pairing.validId(requireNotNull(remembered)))
            assertTrue(requireNotNull(client.state.value.trustExpiresAt) > System.currentTimeMillis() / 1000)
            client.disconnect()
            delay(500) // Complete the previous controller's WebSocket closing handshake.
            client.connect(endpoint, "", identity, requireNotNull(remembered))
            withTimeout(7000) { client.state.first { it.ready } }
            client.offer(Command.Text("Keystore reconnect Caffè 😀"))
            client.offer(Command.Key("enter", listOf("ctrl")))
            delay(100)
            assertTrue(client.state.value.ready)
        } finally {
            client.close()
        }
    }
}

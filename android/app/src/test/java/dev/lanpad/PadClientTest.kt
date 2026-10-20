package dev.lanpad

import dev.lanpad.input.Command
import dev.lanpad.network.Endpoint
import dev.lanpad.network.PadClient
import dev.lanpad.network.Phase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PadClientTest {
    @Test fun pairingPrecedesInputAndDisconnectReleasesHeldButtons() = runBlocking {
        val messages = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.offer(text)
                if (JSONObject(text).getString("type") == "pair") {
                    webSocket.send("{\"type\":\"paired\",\"version\":4}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Acknowledge the client's close frame so both peers release the socket.
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.countDown()
            }
        }))
        server.start()
        val client = PadClient(this)
        try {
            client.offer(Command.Move(999f, 0f)) // Input before pairing must disappear.
            client.connect(Endpoint("127.0.0.1", server.port), "01234")
            withTimeout(5_000) { client.state.first { it.ready } }
            val pairing = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            assertEquals("pair", pairing.getString("type"))
            assertEquals("01234", pairing.getString("pin"))
            client.offer(Command.Button("left", true))
            client.offer(Command.Move(12f, -3f))
            val press = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            val movement = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            assertEquals("button", press.getString("type"))
            assertTrue(press.getBoolean("down"))
            assertEquals("move", movement.getString("type"))
            assertEquals(12.0, movement.getDouble("dx"), 0.001)
            client.offer(Command.Text("Caffè 😀"))
            client.offer(Command.Key("enter", listOf("super")))
            val text = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            val key = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            assertEquals("Caffè 😀", text.getString("text"))
            assertEquals("key", key.getString("type"))
            assertEquals("super", key.getJSONArray("modifiers").getString(0))
            val heartbeat = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            assertEquals("heartbeat", heartbeat.getString("type"))
            client.disconnect()
            val release = JSONObject(requireNotNull(messages.poll(3, TimeUnit.SECONDS)))
            assertEquals("release_all", release.getString("type"))
            assertEquals(Phase.DISCONNECTED, client.state.value.phase)
            assertTrue("WebSocket close handshake did not finish", closed.await(3, TimeUnit.SECONDS))
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun rejectedPinStopsWithoutRetrying() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(4001, "Pairing failed")
            }
        }))
        server.start()
        val client = PadClient(this)
        try {
            client.connect(Endpoint("127.0.0.1", server.port), "99999")
            val failed = withTimeout(5_000) { client.state.first { it.phase == Phase.ERROR } }
            assertTrue(failed.message.contains("PIN rejected"))
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }
}

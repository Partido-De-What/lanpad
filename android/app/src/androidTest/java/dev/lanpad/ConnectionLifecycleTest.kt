package dev.lanpad

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.lanpad.input.Command
import dev.lanpad.network.Endpoint
import dev.lanpad.network.Phase
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A phone-local fake receiver verifies the real Activity, foreground service and socket together. */
class ConnectionLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as LanPadApplication
    private val messages = CopyOnWriteArrayList<String>()
    private val closed = CountDownLatch(1)
    private lateinit var server: MockWebServer
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var receiver: WebSocket

    @Before fun connectFromTheVisibleActivity() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            application.packageName, Manifest.permission.POST_NOTIFICATIONS)
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { receiver = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val kind = JSONObject(text).getString("type")
                messages += kind
                if (kind == "pair") webSocket.send("{\"type\":\"paired\",\"version\":4}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
        }))
        server.start()
        activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { screen ->
            // Prevent the real saved computer from auto-connecting during this isolated test.
            ViewModelProvider(screen)[PadViewModel::class.java].disconnect()
            application.session.connect { client -> client.connect(Endpoint("127.0.0.1", server.port), "01234") }
        }
        eventually { application.session.client.state.value.ready }
    }

    @After fun cleanUp() {
        if (::activity.isInitialized) activity.close()
        instrumentation.runOnMainSync { application.session.stop() }
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun switchingAppsAndRecreatingTheScreenPreserveTheSameConnectionAndHeartbeats() {
        instrumentation.runOnMainSync { application.session.client.offer(Command.Button("left", true)) }
        eventually { "button" in messages }
        activity.moveToState(Lifecycle.State.CREATED)
        val backgroundHeartbeats = messages.count { it == "heartbeat" }
        Thread.sleep(6_500) // Receiver idle timeout is five seconds: heartbeats must continue.
        assertTrue(application.session.client.state.value.ready)
        assertTrue(messages.count { it == "heartbeat" } >= backgroundHeartbeats + 4)
        assertTrue("release_all" in messages)
        activity.moveToState(Lifecycle.State.RESUMED)
        activity.recreate()
        assertTrue(application.session.client.state.value.ready)
        assertEquals(1, server.requestCount)
        assertEquals(1, messages.count { it == "pair" })
        activity.close()
        eventually { !application.session.client.state.value.ready }
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        eventually { notifications().isEmpty() }
    }

    @Test fun notificationStopEndsTheSessionAndReturningDoesNotReconnect() {
        eventually { notifications().isNotEmpty() }
        val notification = notifications().single()
        val stopRequests = application.session.stopRequests.value
        notification.notification.actions.single { it.title == "Stop" }.actionIntent.send()
        eventually { application.session.client.state.value.phase == Phase.DISCONNECTED }
        assertTrue(application.session.stopRequests.value > stopRequests)
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        activity.moveToState(Lifecycle.State.CREATED)
        activity.moveToState(Lifecycle.State.RESUMED)
        Thread.sleep(900)
        assertEquals(1, server.requestCount)
        assertEquals(Phase.DISCONNECTED, application.session.client.state.value.phase)
        eventually { notifications().isEmpty() }
    }

    @Test fun stoppingTheReceiverEndsTheForegroundService() {
        receiver.close(1001, "Receiver stopped")
        eventually { application.session.client.state.value.phase == Phase.ERROR }
        eventually { notifications().isEmpty() }
        assertEquals(1, server.requestCount)
    }

    private fun notifications() = application.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.id == 1 }

    private fun eventually(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("Timed out waiting for connection lifecycle change; " +
            "state=${application.session.client.state.value}; messages=$messages; requests=${server.requestCount}", check())
    }
}

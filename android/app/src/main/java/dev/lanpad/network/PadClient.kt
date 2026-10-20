package dev.lanpad.network

import dev.lanpad.input.Command
import dev.lanpad.input.InputBuffer
import dev.lanpad.input.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

enum class Phase { DISCONNECTED, CONNECTING, PAIRING, CONNECTED, RETRYING, ERROR }

data class LinkState(val phase: Phase = Phase.DISCONNECTED, val message: String = "", val trustExpiresAt: Long? = null) {
    val ready: Boolean get() = phase == Phase.CONNECTED
    val busy: Boolean get() = phase in setOf(Phase.CONNECTING, Phase.PAIRING, Phase.RETRYING)
}

private class LinkFailure(message: String, val retryable: Boolean) : Exception(message)

/** Own one paired session. UI calls enqueue input; IO coroutines handle the socket. */
class PadClient(
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .build(),
) {
    private val lock = Any()
    private val input = InputBuffer()
    private val mutableState = MutableStateFlow(LinkState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var socket: WebSocket? = null
    private var job: Job? = null

    /** Replace the current session; retry only transient failures, never a rejected PIN. */
    fun connect(endpoint: Endpoint, pin: String, identity: DeviceIdentity? = null,
                receiverId: String? = null, onRemembered: (String, Long) -> Unit = { _, _ -> }) {
        require(Pairing.validPin(pin) || (identity != null && receiverId != null)) { "PIN must contain exactly five digits" }
        val remembered = AtomicReference(receiverId)
        val id: Long
        val previous: Job?
        synchronized(lock) {
            previous = job
            generation += 1
            id = generation
            retireSocket()
            input.clear()
            mutableState.value = LinkState(Phase.CONNECTING)
            job = scope.launch(Dispatchers.IO) {
                previous?.cancelAndJoin()
                for (retry in 0..3) {
                    if (retry > 0) {
                        publish(id, LinkState(Phase.RETRYING, "Reconnecting · $retry of 3"))
                        delay(1_000L shl (retry - 1))
                    }
                    try {
                        attempt(id, endpoint, pin, identity, remembered.get()) { receiver, expiry ->
                            onRemembered(receiver, expiry)
                            remembered.set(receiver)
                        }
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: LinkFailure) {
                        input.clear()
                        if (!error.retryable || retry == 3) {
                            publish(id, LinkState(Phase.ERROR, error.message.orEmpty()))
                            return@launch
                        }
                    } catch (_: Exception) {
                        publish(id, LinkState(Phase.ERROR, "Couldn't connect. Check the receiver and Wi-Fi."))
                        return@launch
                    }
                }
            }
        }
    }

    /** Stop retries and input immediately; ask the receiver to release before closing. */
    fun disconnect(message: String = "") = synchronized(lock) {
        generation += 1
        job?.cancel()
        job = null
        input.clear()
        retireSocket()
        mutableState.value = LinkState(Phase.DISCONNECTED, message)
    }

    /** Drop pending motion and release held buttons, for settings or gesture cancellation. */
    fun releaseAll() = synchronized(lock) {
        input.clear()
        if (mutableState.value.ready && socket?.send(Command.ReleaseAll.toJson()) == false) {
            disconnect("Connection ended. Connect again to continue.")
        }
    }

    /** Reject input until pairing succeeds and bound the queue instead of building lag. */
    fun offer(command: Command) = synchronized(lock) {
        if (mutableState.value.ready && mutableState.value.message.isNotBlank()) {
            mutableState.value = mutableState.value.copy(message = "")
        }
        if (mutableState.value.ready && !input.offer(command)) {
            disconnect("Input fell behind. Connect again to continue.")
        }
    }

    /** Close the app-owned HTTP resources when the ViewModel is destroyed. */
    fun close() {
        disconnect()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /** Publish only for the current session; late callbacks cannot restore an old state. */
    private fun publish(id: Long, value: LinkState) = synchronized(lock) {
        if (generation == id) mutableState.value = value
    }

    private fun retireSocket() {
        socket?.let {
            it.send(Command.ReleaseAll.toJson())
            it.close(1000, "Controller stopped")
        }
        socket = null
    }

    /** Pair once, then run a paced sender and heartbeats until the connection ends. */
    private suspend fun attempt(id: Long, endpoint: Endpoint, pin: String,
                                identity: DeviceIdentity?, receiverId: String?,
                                onRemembered: (String, Long) -> Unit) {
        val paired = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<LinkFailure>()
        val enrollment = if (receiverId == null) Pairing.message(pin, identity) else
            Pairing.resume(receiverId, requireNotNull(identity))
        val trustExpiry = AtomicLong(0)
        var challenged = false
        val listener = object : WebSocketListener() {
            private fun fail(error: LinkFailure) {
                paired.completeExceptionally(error)
                ended.complete(error)
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (generation != id || socket !== webSocket) {
                        webSocket.cancel()
                        return
                    }
                    mutableState.value = LinkState(Phase.PAIRING)
                    if (!webSocket.send(enrollment)) {
                        fail(LinkFailure("The receiver closed before pairing.", true))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(lock) {
                    if (generation != id || socket !== webSocket) return
                    val valid = runCatching {
                        require(text.length <= 1024)
                        val message = JSONObject(text)
                        when (message.getString("type")) {
                            "challenge" -> {
                                require(!paired.isCompleted && !challenged && receiverId != null && identity != null)
                                require(message.length() == 3 && message.getString("receiver_id") == receiverId)
                                challenged = true
                                require(webSocket.send(Pairing.proof(receiverId, message.getString("nonce"), identity)))
                            }
                            "paired" -> {
                                require(!paired.isCompleted && message.getInt("version") == Pairing.VERSION)
                                if (identity != null) {
                                    require(message.length() == 4)
                                    val receiver = message.getString("receiver_id")
                                    require(Pairing.validId(receiver))
                                    if (receiverId != null) require(challenged && receiver == receiverId)
                                    val expiry = message.getLong("expires_at")
                                    require(expiry > 0)
                                    trustExpiry.set(expiry)
                                    onRemembered(receiver, expiry)
                                } else require(message.length() == 2)
                                require(paired.complete(Unit))
                            }
                            "input_error" -> {
                                require(paired.isCompleted && message.length() == 2)
                                val detail = message.getString("message").take(200)
                                publish(id, mutableState.value.copy(message = detail))
                            }
                            else -> error("Unexpected response")
                        }
                    }.isSuccess
                    if (!valid) {
                        fail(LinkFailure("Pairing or device verification failed. Enter the PIN to pair again.", false))
                        webSocket.close(1002, "Invalid response")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                fail(LinkFailure("The receiver sent an unexpected response.", false))
                webSocket.close(1002, "Text messages required")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                fail(closeFailure(code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(closeFailure(code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(LinkFailure("Connection lost. Check that both devices are on the same network.", true))
            }
        }

        val connection = synchronized(lock) {
            if (generation != id) throw CancellationException()
            input.clear()
            mutableState.value = LinkState(Phase.CONNECTING)
            http.newWebSocket(Request.Builder().url(endpoint.url).build(), listener).also { socket = it }
        }
        try {
            try {
                withTimeout(7_000) { paired.await() }
            } catch (_: TimeoutCancellationException) {
                throw LinkFailure("Pairing timed out. Check the receiver and try again.", true)
            }
            publish(id, LinkState(Phase.CONNECTED, trustExpiresAt = trustExpiry.get().takeIf { it > 0 }))
            coroutineScope {
                val sender = launch {
                    while (isActive) {
                        // Two messages per 17 ms limits input to fewer than 120/s.
                        // Adjacent motion is merged; button order is retained.
                        repeat(2) {
                            synchronized(lock) { input.poll()?.let { send(id, connection, it) } }
                        }
                        delay(17)
                    }
                }
                val heartbeat = launch {
                    while (isActive) {
                        delay(1_000)
                        send(id, connection, Command.Heartbeat)
                    }
                }
                try {
                    throw ended.await()
                } finally {
                    sender.cancel()
                    heartbeat.cancel()
                }
            }
        } finally {
            synchronized(lock) {
                if (socket === connection) {
                    retireSocket()
                    input.clear()
                } else {
                    connection.close(1000, "Session replaced")
                }
            }
        }
    }

    /** Serialize sends with disconnect so no input can be sent after its final release. */
    private fun send(id: Long, connection: WebSocket, command: Command) = synchronized(lock) {
        if (generation != id || socket !== connection) throw CancellationException()
        if (connection.queueSize() > 8192 || !connection.send(command.toJson())) {
            throw LinkFailure("Connection fell behind. Reconnecting…", true)
        }
    }

    private fun closeFailure(code: Int): LinkFailure = when (code) {
        1011 -> LinkFailure("Receiver input failed. Check the receiver terminal for details.", false)
        4001 -> LinkFailure("PIN rejected or remembered device expired/revoked. Enter the current PIN to pair again.", false)
        4003 -> LinkFailure("Too many PIN attempts. Wait one minute, then try again.", false)
        4009 -> LinkFailure("Another controller is connected. Disconnect it first.", false)
        4002, 4008, 1009 -> LinkFailure("The receiver rejected the input. Reconnect to try again.", false)
        1000, 1001 -> LinkFailure("The receiver stopped. Start it again, then connect.", false)
        else -> LinkFailure("Connection lost. Check the receiver and Wi-Fi.", true)
    }
}

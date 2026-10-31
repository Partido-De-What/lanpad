package dev.lanpad

import android.app.Application
import android.content.Intent
import android.os.Build
import dev.lanpad.network.PadClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A connected session belongs to the application/service, rather than its screen. */
class LanPadApplication : Application() {
    val session by lazy { PadSession(this) }
}

class PadSession(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val client = PadClient(scope)
    private val mutableStopRequests = MutableStateFlow(0L)
    val stopRequests = mutableStopRequests.asStateFlow()
    private var pending: (() -> Unit)? = null

    /** The service starts the socket only after entering foreground state. Main-thread only. */
    fun connect(action: (PadClient) -> Unit) {
        pending = { action(client) }
        try {
            val intent = Intent(application, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) application.startForegroundService(intent)
            else application.startService(intent)
        } catch (error: RuntimeException) {
            pending = null
            throw error
        }
    }

    fun startPending() {
        val action = pending
        pending = null
        action?.invoke()
    }

    fun stop(message: String = "") {
        mutableStopRequests.value += 1
        pending = null
        client.disconnect(message)
    }
}

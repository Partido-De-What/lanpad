package dev.lanpad.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Browse mDNS while visible. Main-thread serialization and generations discard stale callbacks. */
class ReceiverDiscovery(context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val mutableState = MutableStateFlow(DiscoveryState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var listener: NsdManager.DiscoveryListener? = null
    private val known = mutableSetOf<String>()
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val pending = linkedMapOf<String, NsdServiceInfo>()
    private var resolving = false

    /** Start a fresh browse; the OS handles multicast instead of probing every subnet address. */
    fun start() {
        if (listener != null) return
        val id = ++generation
        mutableState.value = DiscoveryState(searching = true)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = dispatch(id) {
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return@dispatch
                if (known.size >= 16 || !known.add(serviceInfo.serviceName)) return@dispatch
                if (Build.VERSION.SDK_INT >= 34) watch(serviceInfo, id) else {
                    pending[serviceInfo.serviceName] = serviceInfo
                    resolveNext()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = dispatch(id) {
                val name = serviceInfo.serviceName
                known.remove(name)
                pending.remove(name)
                if (Build.VERSION.SDK_INT >= 34) callbacks.remove(name)?.let {
                    runCatching { manager.unregisterServiceInfoCallback(it) }
                }
                remove(name)
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = dispatch(id) {
                stop()
                mutableState.value = DiscoveryState(message = "Discovery unavailable. Try again or enter an address.")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: RuntimeException) {
            stop()
            mutableState.value = DiscoveryState(message = "Discovery unavailable. Try again or enter an address.")
        }
    }

    /** Stop browsing and watching services when the app leaves the foreground. */
    fun stop() {
        generation += 1
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.values.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
        }
        callbacks.clear()
        pending.clear()
        known.clear()
        mutableState.value = DiscoveryState()
    }

    private fun dispatch(id: Long, action: () -> Unit) {
        handler.post { if (generation == id && listener != null) action() }
    }

    /** Modern Android continuously supplies refreshed addresses and service-loss notifications. */
    @androidx.annotation.RequiresApi(34)
    private fun watch(service: NsdServiceInfo, id: Long) {
        val name = service.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = dispatch(id) { remove(name) }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = dispatch(id) { update(serviceInfo) }
            override fun onServiceLost() = dispatch(id) { remove(name) }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks[name] = callback
        try {
            manager.registerServiceInfoCallback(service, executor, callback)
        } catch (_: RuntimeException) {
            callbacks.remove(name)
            remove(name)
        }
    }

    /** Older phones resolve one service at a time; parallel resolution fails on older NSD stacks. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null || pending.isEmpty()) return
        val service = pending.values.first()
        pending.remove(service.serviceName)
        val id = generation
        resolving = true
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finish(null)
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = finish(serviceInfo)
            private fun finish(info: NsdServiceInfo?) {
                handler.post {
                    resolving = false
                    if (generation == id && info != null && info.serviceName in known) update(info)
                    resolveNext()
                }
            }
        }
        try {
            manager.resolveService(service, resolver)
        } catch (_: RuntimeException) {
            resolving = false
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun update(info: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val receiver = discoveredReceiver(info.serviceName, addresses.mapNotNull { it.hostAddress },
            info.port, info.attributes["version"]?.toString(Charsets.UTF_8))
        val previous = mutableState.value.receivers.filterNot { it.name == info.serviceName }
        mutableState.value = mutableState.value.copy(
            receivers = (previous + listOfNotNull(receiver)).sortedBy { it.name },
        )
    }

    private fun remove(name: String) {
        mutableState.value = mutableState.value.copy(receivers = mutableState.value.receivers.filterNot { it.name == name })
    }

    companion object { private const val SERVICE_TYPE = "_lanpad._tcp." }
}

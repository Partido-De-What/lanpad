package dev.lanpad

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lanpad.data.PadSettings
import dev.lanpad.data.SettingsStore
import dev.lanpad.input.Command
import dev.lanpad.network.DiscoveryState
import dev.lanpad.network.Endpoint
import dev.lanpad.network.LinkState
import dev.lanpad.network.PadClient
import dev.lanpad.network.Pairing
import dev.lanpad.network.ReceiverDiscovery
import dev.lanpad.network.chooseReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PadState(
    val settings: PadSettings = PadSettings(),
    val pin: String = "",
    val link: LinkState = LinkState(),
    val formError: String? = null,
    val settingsReady: Boolean = false,
    val discovery: DiscoveryState = DiscoveryState(),
    val selectedName: String? = null,
    val manualAddress: Boolean = false,
) {
    val selectedReceiver get() = if (manualAddress) null
        else chooseReceiver(discovery.receivers, selectedName ?: settings.receiverName)
}

/** State survives rotation. The screen emits actions; this class owns their effects. */
class PadViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val client = PadClient(viewModelScope)
    private val discovery = ReceiverDiscovery(application)
    private val form = MutableStateFlow(PadState())
    private val mutableState = MutableStateFlow(PadState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settings.values.first()
            form.update { it.copy(settings = saved, settingsReady = true) }
        }
        viewModelScope.launch {
            combine(form, client.state, discovery.state) { fields, link, found ->
                fields.copy(link = link, discovery = found)
            }
                .collect { mutableState.value = it }
        }
    }

    fun editHost(host: String) = edit { copy(settings = settings.copy(host = host)) }
    fun editPort(port: String) = edit { copy(settings = settings.copy(port = port)) }
    fun editPin(value: String) = edit { copy(pin = value.filter { it in '0'..'9' }.take(5)) }
    fun selectReceiver(name: String) = edit { copy(selectedName = name, manualAddress = false) }
    fun manualAddress(value: Boolean) = edit { copy(manualAddress = value) }
    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()
    fun refreshDiscovery() { discovery.stop(); discovery.start() }

    private fun edit(change: PadState.() -> PadState) {
        form.update { it.change().copy(formError = null) }
    }

    /** Use the discovered endpoint or manual form, remember the choice, and start pairing on IO. */
    fun connect(): Boolean {
        val current = form.value.copy(discovery = discovery.state.value)
        val endpoint = try {
            require(current.settingsReady) { "Settings are still loading. Try again in a moment." }
            require(Pairing.validPin(current.pin)) {
                "Enter the five-digit PIN shown on your computer."
            }
            if (current.manualAddress) Endpoint.parse(current.settings.host, current.settings.port)
            else requireNotNull(current.selectedReceiver) { "Start the receiver, then choose your computer." }.endpoint
        } catch (error: IllegalArgumentException) {
            form.update { it.copy(formError = error.message) }
            return false
        }
        val saved = current.settings.copy(host = endpoint.host, port = endpoint.port.toString(),
            receiverName = if (current.manualAddress) "" else current.selectedReceiver?.name.orEmpty())
        form.update { it.copy(settings = saved, formError = null) }
        persist(saved)
        client.connect(endpoint, current.pin)
        return true
    }

    fun disconnect(message: String = "") = client.disconnect(message)
    fun releaseAll() = client.releaseAll()

    /** Route typed UI gestures through sensitivity handling before enqueueing them. */
    fun input(command: Command) {
        when (command) {
            is Command.Move -> move(command.dx, command.dy)
            is Command.Scroll -> scroll(command.dx, command.dy)
            else -> client.offer(command)
        }
    }

    /** Sensitivity scales pointer pixels only; scrolling uses a fixed dp-per-step scale. */
    private fun move(dx: Float, dy: Float) {
        val sensitivity = form.value.settings.sensitivity
        if (dx.isFinite() && dy.isFinite()) {
            client.offer(Command.Move(
                (dx * sensitivity).coerceIn(-2048f, 2048f),
                (dy * sensitivity).coerceIn(-2048f, 2048f),
            ))
        }
    }

    private fun scroll(dx: Float, dy: Float) {
        if (dx.isFinite() && dy.isFinite()) {
            client.offer(Command.Scroll(dx.coerceIn(-10f, 10f), dy.coerceIn(-10f, 10f)))
        }
    }

    fun sensitivity(value: Float) {
        if (value.isFinite()) edit { copy(settings = settings.copy(sensitivity = value.coerceIn(0.5f, 2.5f))) }
    }

    /** Save on slider release instead of writing preferences for every drag sample. */
    fun saveSensitivity() = persist(form.value.settings)

    private fun persist(value: PadSettings) {
        viewModelScope.launch {
            try {
                settings.save(value)
            } catch (error: CancellationException) {
                throw error // Preserve lifecycle cancellation when the ViewModel closes.
            } catch (_: Exception) {
                form.update { state -> state.copy(formError = "Couldn't save settings. You can still connect.") }
            }
        }
    }

    override fun onCleared() {
        discovery.stop()
        client.close()
        super.onCleared()
    }
}

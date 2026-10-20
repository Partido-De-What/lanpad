package dev.lanpad.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lanpad.PadState
import dev.lanpad.data.PadSettings
import dev.lanpad.input.Command
import dev.lanpad.network.DiscoveredReceiver
import dev.lanpad.network.DiscoveryState
import dev.lanpad.network.Endpoint
import dev.lanpad.network.LinkState
import dev.lanpad.network.Pairing
import dev.lanpad.network.Phase
import java.util.Locale

/** Keep presentation independent of the ViewModel so previews and UI tests use the same screen. */
data class PadActions(
    val connect: () -> Boolean = { false },
    val disconnect: () -> Unit = {},
    val releaseAll: () -> Unit = {},
    val input: (Command) -> Unit = {},
    val host: (String) -> Unit = {},
    val port: (String) -> Unit = {},
    val pin: (String) -> Unit = {},
    val selectReceiver: (String) -> Unit = {},
    val manualAddress: (Boolean) -> Unit = {},
    val refreshDiscovery: () -> Unit = {},
    val sensitivity: (Float) -> Unit = {},
    val saveSensitivity: () -> Unit = {},
    val rememberDevice: (Boolean) -> Unit = {},
    val forgetDevices: () -> Unit = {},
)

/** The main surface stays focused on control; connection details live in a sheet. */
@Composable
fun LanPadScreen(state: PadState, actions: PadActions) {
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var keyboardMode by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val release by rememberUpdatedState(actions.releaseAll)
    val enabled = state.link.ready && !showConnection
    DisposableEffect(enabled) {
        // Rotation, opening settings, and removing the screen all end held input.
        onDispose { release() }
    }
    val openSettings = {
        actions.releaseAll()
        showConnection = true
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            val wide = maxWidth >= 600.dp
            val compact = !wide && maxHeight < 560.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Header(state, actions.disconnect, openSettings, keyboardMode) {
                    actions.releaseAll()
                    keyboard?.hide()
                    keyboardMode = !keyboardMode
                }
                // Keep Stop fixed at the top; small screens can scroll the controls below it.
                Column(Modifier.weight(1f).fillMaxWidth().then(
                    if (compact && !keyboardMode) Modifier.verticalScroll(rememberScrollState()) else Modifier
                ), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ConnectionCard(state, openSettings)
                    if (state.link.ready && state.link.message.isNotBlank()) {
                        Text(state.link.message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (keyboardMode) {
                        KeyboardPanel(enabled, actions.input, Modifier.weight(1f).fillMaxWidth())
                    } else if (wide) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Touchpad(enabled, actions.input, Modifier.weight(1f).fillMaxSize())
                            Column(Modifier.width(240.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                MouseButtons(enabled, actions.input)
                                SpeedControl(state, actions)
                            }
                        }
                    } else {
                        val padSize = if (compact) Modifier.height(220.dp) else Modifier.weight(1f)
                        Touchpad(enabled, actions.input, padSize.fillMaxWidth())
                        MouseButtons(enabled, actions.input)
                        SpeedControl(state, actions)
                    }
                }
            }
        }
    }
    if (showConnection) {
        ConnectionSheet(state, actions, onDismiss = { showConnection = false })
    }
}

/** Brand, explicit connection status, and a stop action that stays within reach. */
@Composable
private fun Header(state: PadState, disconnect: () -> Unit, openSettings: () -> Unit,
                   keyboardMode: Boolean, toggleMode: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(colors.primaryContainer, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            PadIcon(Symbol.PAD, color = colors.primary)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("LanPad", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(
                    if (state.link.ready) colors.primary else colors.onSurfaceVariant, CircleShape))
                Text(when (state.link.phase) {
                    Phase.CONNECTED -> "Connected"
                    Phase.CONNECTING -> "Connecting"
                    Phase.PAIRING -> "Pairing"
                    Phase.RETRYING -> "Reconnecting"
                    else -> "Not connected"
                }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        IconButton(onClick = toggleMode, modifier = Modifier.semantics {
            contentDescription = if (keyboardMode) "Switch to mouse" else "Switch to keyboard"
        }) { PadIcon(if (keyboardMode) Symbol.PAD else Symbol.KEYBOARD, color = colors.primary) }
        if (state.link.ready || state.link.busy) {
            TextButton(onClick = disconnect) {
                PadIcon(Symbol.STOP, Modifier.size(16.dp), colors.primary)
                Text("Stop", Modifier.padding(start = 6.dp))
            }
        }
        IconButton(onClick = openSettings, modifier = Modifier.semantics { contentDescription = "Connection settings" }) {
            PadIcon(Symbol.SETTINGS, color = colors.onSurfaceVariant)
        }
    }
}

/** A compact connection summary doubles as the entry point to pairing. */
@Composable
private fun ConnectionCard(state: PadState, openSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = openSettings, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when {
                        state.link.ready -> "Your computer, connected."
                        state.link.busy -> "Making the connection…"
                        else -> "Connect your computer"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        state.link.message.isNotBlank() -> state.link.message
                        state.link.ready -> state.settings.host
                        state.link.phase == Phase.PAIRING -> if (state.canResume) "Verifying remembered phone" else "Checking your pairing PIN"
                        state.link.busy -> "Keep the receiver running"
                        state.canResume -> "${state.selectedReceiver?.name ?: state.settings.host} · ${state.trustDaysRemaining} days of auto-connect"
                        state.selectedReceiver != null -> "${state.selectedReceiver?.name} · ready to pair"
                        state.discovery.receivers.size > 1 -> "Choose a computer to pair"
                        state.discovery.searching -> "Looking for your computer…"
                        else -> "Start LanPad on your computer"
                    }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                        color = if (state.link.phase == Phase.ERROR) colors.error else colors.onSurfaceVariant)
                }
                PadIcon(Symbol.ARROW, Modifier.padding(start = 12.dp), colors.primary)
            }
            if (state.link.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MouseButtons(enabled: Boolean, input: (Command) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MouseButton("left", enabled, input, Modifier.weight(1f).fillMaxSize())
        MouseButton("right", enabled, input, Modifier.weight(1f).fillMaxSize())
    }
}

/** A single persistent slider tunes movement without adding another settings screen. */
@Composable
private fun SpeedControl(state: PadState, actions: PadActions) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pointer speed", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(String.format(Locale.getDefault(), "%.1f×", state.settings.sensitivity),
                style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
        Slider(value = state.settings.sensitivity, onValueChange = actions.sensitivity,
            onValueChangeFinished = actions.saveSensitivity, valueRange = 0.5f..2.5f,
            enabled = state.settingsReady,
            modifier = Modifier.semantics { contentDescription = "Pointer speed" })
        GestureHint()
    }
}

@Composable
private fun GestureHint() {
    Text("Hold Left + move to drag", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Use ordinary form controls with large targets, hidden PINs, and inline errors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSheet(state: PadState, actions: PadActions, onDismiss: () -> Unit) {
    var showPin by rememberSaveable { mutableStateOf(false) }
    var pairAgain by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val connect = {
        if (actions.connect()) {
            keyboard?.hide()
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.link.ready) {
                ConnectedDetails(state, actions, onDismiss)
            } else if (state.link.busy) {
                Text("Connecting to your computer", style = MaterialTheme.typography.headlineLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.canResume) "Verifying your remembered phone…" else "Checking your pairing PIN…")
                FilledTonalButton(onClick = actions.disconnect, modifier = Modifier.fillMaxWidth()) { Text("Cancel connection") }
            } else {
            Column {
                Text("A connection away.", style = MaterialTheme.typography.headlineLarge)
                Text(if (state.canResume) "Choose your remembered computer to reconnect." else "Choose your computer and enter its five-digit PIN.", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.manualAddress) ReceiverChoices(state, actions)
            TextButton(onClick = { actions.manualAddress(!state.manualAddress) }) {
                Text(if (state.manualAddress) "Find computers automatically" else "Enter address manually")
            }
            if (state.manualAddress) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.settings.host, onValueChange = actions.host,
                    label = { Text("Computer IP") }, placeholder = { Text("192.168.1.20") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(16.dp))
                OutlinedTextField(value = state.settings.port, onValueChange = actions.port,
                    label = { Text("Port") }, modifier = Modifier.width(104.dp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(16.dp))
            }
            val remembered = state.canResume && state.rememberDevice && !pairAgain
            if (remembered) {
                TrustStatus(state.trustDaysRemaining)
                TextButton(onClick = { pairAgain = true }) { Text("Pair again with a PIN") }
            } else {
            OutlinedTextField(value = state.pin, onValueChange = actions.pin,
                label = { Text("Pairing PIN") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("First connection: enter the PIN. Remembered computer: leave blank.") },
                visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPin = !showPin }) { Text(if (showPin) "Hide" else "Show") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!state.link.busy) connect() }),
                shape = RoundedCornerShape(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.rememberDevice, onCheckedChange = actions.rememberDevice)
                Column {
                    Text("Remember this phone", style = MaterialTheme.typography.labelLarge)
                    Text("Trusted LAN only · expires after 90 days", style = MaterialTheme.typography.bodySmall)
                }
            }
            }
            TextButton(onClick = { pairAgain = false; actions.forgetDevices() }) { Text("Forget saved computers on this phone") }
            state.formError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = connect, enabled = state.settingsReady && !state.link.busy &&
                (Pairing.validPin(state.pin) || (remembered && state.pin.isEmpty())) && (state.manualAddress || state.selectedReceiver != null),
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (remembered) "Reconnect" else "Connect")
                Spacer(Modifier.width(8.dp))
                PadIcon(Symbol.ARROW, color = MaterialTheme.colorScheme.onPrimary)
            }
            if (state.link.ready || state.link.busy) {
                FilledTonalButton(onClick = { actions.disconnect(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Disconnect") }
            }
            Text("Your PIN is never saved. Remembered connections use a device key.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrustStatus(days: Long) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (days > 0) "Auto-connect valid for $days ${if (days == 1L) "day" else "days"}"
                else "Auto-connect has expired", style = MaterialTheme.typography.titleMedium)
            Text("Validity is checked with your computer each time you connect.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectedDetails(state: PadState, actions: PadActions, onDismiss: () -> Unit) {
    Text("You're connected", style = MaterialTheme.typography.headlineLarge)
    Text(state.settings.receiverName.ifBlank { state.settings.host.ifBlank { "Your computer" } },
        style = MaterialTheme.typography.titleLarge)
    if (state.settings.host.isNotBlank()) Text("${state.settings.host}:${state.settings.port}",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.link.trustExpiresAt != null || state.trustExpiresAt > 0) {
        TrustStatus(state.trustDaysRemaining)
    } else {
        Text("This connection uses a PIN. Remember this phone when you next pair to enable auto-connect.")
    }
    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continue controlling") }
    FilledTonalButton(onClick = actions.disconnect, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Disconnect") }
    TextButton(onClick = actions.forgetDevices) { Text("Disconnect & forget saved computers") }
}

/** One receiver is selected automatically; more results get an explicit, accessible choice. */
@Composable
private fun ReceiverChoices(state: PadState, actions: PadActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your computer", style = MaterialTheme.typography.titleMedium)
        if (state.discovery.receivers.isEmpty()) {
            Text(state.discovery.message.ifBlank { "Looking for receivers. Keep both devices on the same network." },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.discovery.receivers.forEach { receiver ->
            val selected = state.selectedReceiver?.name == receiver.name
            FilledTonalButton(onClick = { actions.selectReceiver(receiver.name) }, modifier = Modifier.fillMaxWidth()) {
                Text(receiver.name, Modifier.weight(1f))
                if (selected) Text("Selected")
            }
        }
        TextButton(onClick = actions.refreshDiscovery) { Text("Search again") }
    }
}

@Preview(name = "Quiet light", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun LightPreview() {
    LanPadTheme(dark = false) { LanPadScreen(PadState(settingsReady = true), PadActions()) }
}

@Preview(name = "Connected dark", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DarkPreview() {
    LanPadTheme(dark = true) {
        LanPadScreen(PadState(settings = PadSettings(host = "192.168.1.20"), settingsReady = true,
            link = LinkState(Phase.CONNECTED)), PadActions())
    }
}

@Preview(name = "Landscape", showBackground = true, widthDp = 852, heightDp = 393)
@Composable
private fun LandscapePreview() {
    LanPadTheme(dark = false) {
        LanPadScreen(PadState(settingsReady = true, link = LinkState(Phase.CONNECTED)), PadActions())
    }
}

/** Render the actual pairing sheet with a discovered receiver and a complete example PIN. */
@Preview(name = "PIN pairing · light", showBackground = true, widthDp = 393, heightDp = 852)
@Preview(name = "PIN pairing · dark", showBackground = true, widthDp = 393, heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PinPairingPreview() {
    val state = PadState(settingsReady = true, pin = "01234", discovery = DiscoveryState(
        receivers = listOf(DiscoveredReceiver("LanPad workstation", Endpoint("192.168.1.20", 8765))),
        searching = true,
    ))
    LanPadTheme {
        LanPadScreen(state, PadActions())
        ConnectionSheet(state, PadActions(), onDismiss = {})
    }
}

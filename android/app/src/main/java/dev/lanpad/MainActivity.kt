package dev.lanpad

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanpad.ui.LanPadScreen
import dev.lanpad.ui.LanPadTheme
import dev.lanpad.ui.PadActions

/** Host Compose and tie mouse control to the app's visible lifetime. */
class MainActivity : ComponentActivity() {
    private val model: PadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            // Avoid screen sleep during control; allow normal sleep after disconnecting.
            DisposableEffect(state.link.ready) {
                if (state.link.ready) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            val actions = remember(model) {
                PadActions(
                    connect = model::connect,
                    disconnect = { model.disconnect() },
                    releaseAll = model::releaseAll,
                    input = model::input,
                    host = model::editHost,
                    port = model::editPort,
                    pin = model::editPin,
                    selectReceiver = model::selectReceiver,
                    manualAddress = model::manualAddress,
                    refreshDiscovery = model::refreshDiscovery,
                    sensitivity = model::sensitivity,
                    saveSensitivity = model::saveSensitivity,
                    rememberDevice = model::rememberDevice,
                    forgetDevices = model::forgetDevices,
                )
            }
            LanPadTheme { LanPadScreen(state, actions) }
        }
    }

    override fun onStart() {
        super.onStart()
        model.startDiscovery()
    }

    override fun onStop() {
        model.stopDiscovery()
        // Rotation keeps the session; leaving the app stops input and retries.
        if (!isChangingConfigurations) model.disconnect("Paused when LanPad left the foreground.")
        super.onStop()
    }
}

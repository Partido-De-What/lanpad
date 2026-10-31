package dev.lanpad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanpad.ui.LanPadScreen
import dev.lanpad.ui.LanPadTheme
import dev.lanpad.ui.PadActions

/** Host controls; the foreground service owns the connection across app switching. */
class MainActivity : ComponentActivity() {
    private val model: PadViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
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
                    dismissConnectionPrompt = model::dismissConnectionPrompt,
                )
            }
            LanPadTheme { LanPadScreen(state, actions) }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prompts = getPreferences(MODE_PRIVATE)
        if (!prompts.getBoolean("notification_requested", false)) {
            prompts.edit { putBoolean("notification_requested", true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        model.startDiscovery()
    }

    override fun onStop() {
        model.stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) model.close()
        super.onDestroy()
    }
}

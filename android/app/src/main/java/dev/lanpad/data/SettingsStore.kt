package dev.lanpad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settings by preferencesDataStore("lanpad_settings")

data class PadSettings(
    val host: String = "",
    val port: String = "8765",
    val sensitivity: Float = 1f,
    val receiverName: String = "",
)

/** Persist convenience settings only; the pairing PIN stays in memory. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settings
    private val hostKey = stringPreferencesKey("host")
    private val portKey = stringPreferencesKey("port")
    private val sensitivityKey = floatPreferencesKey("sensitivity")
    private val receiverKey = stringPreferencesKey("receiver_name")

    val values = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values ->
        PadSettings(
            values[hostKey].orEmpty(),
            values[portKey] ?: "8765",
            (values[sensitivityKey] ?: 1f).let { if (it.isFinite()) it.coerceIn(0.5f, 2.5f) else 1f },
            values[receiverKey].orEmpty(),
        )
    }

    suspend fun save(settings: PadSettings) {
        store.edit {
            it[hostKey] = settings.host
            it[portKey] = settings.port
            it[sensitivityKey] = settings.sensitivity
            it[receiverKey] = settings.receiverName
        }
    }
}

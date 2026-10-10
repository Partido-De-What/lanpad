package dev.lanpad.network

import org.json.JSONObject

/** Both endpoints use version 2: a five-digit PIN replaces the old long token. */
object Pairing {
    const val VERSION = 2
    fun validPin(pin: String): Boolean = pin.matches(Regex("[0-9]{5}"))

    fun message(pin: String): String {
        require(validPin(pin)) { "Enter the five-digit PIN shown on your computer." }
        return JSONObject().put("type", "pair").put("version", VERSION).put("pin", pin).toString()
    }
}

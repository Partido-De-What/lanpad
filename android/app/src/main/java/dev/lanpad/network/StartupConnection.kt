package dev.lanpad.network

/** One launch gets one automatic action. Explicit Stop/dismiss never causes an auto-retry loop. */
class StartupConnection {
    enum class Action { NONE, CONNECT, PROMPT }
    private var handled = false

    fun decide(visible: Boolean, settingsReady: Boolean, phase: Phase,
               receiverAvailable: Boolean, remembered: Boolean): Action {
        if (handled || !visible || !settingsReady) return Action.NONE
        if (phase != Phase.DISCONNECTED && phase != Phase.ERROR) {
            handled = true
            return Action.NONE
        }
        if (!receiverAvailable) return Action.NONE
        handled = true
        return if (remembered) Action.CONNECT else Action.PROMPT
    }

    fun handled() { handled = true }
}

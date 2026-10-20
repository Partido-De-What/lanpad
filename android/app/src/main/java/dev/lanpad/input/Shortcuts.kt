package dev.lanpad.input

/** Snapshot of ~/.config/i3/config: Mod4 is Super; labels describe the bound action. */
data class Shortcut(val label: String, val key: String, val modifiers: List<String> = listOf("super")) {
    val command get() = Command.Key(key, modifiers)
    val hint get() = (modifiers.map { it.replaceFirstChar(Char::uppercase) } +
        when (key) { "enter" -> "Enter"; "tab" -> "Tab"; else -> key.uppercase() }).joinToString(" + ")
}

val i3Shortcuts: Map<String, List<Shortcut>> = linkedMapOf(
    "Apps" to listOf(
        Shortcut("Terminal", "enter"), Shortcut("Launcher", "d"),
        Shortcut("Files", "f", listOf("super", "shift")), Shortcut("Network", "n"),
        Shortcut("Mixer", "a", listOf("super", "shift")),
        Shortcut("Power menu", "s", listOf("super", "shift")),
    ),
    "Windows" to listOf(
        Shortcut("Focus ←", "h"), Shortcut("Focus ↓", "j"),
        Shortcut("Focus ↑", "k"), Shortcut("Focus →", "l"),
        Shortcut("Move ←", "h", listOf("super", "shift")),
        Shortcut("Move ↓", "j", listOf("super", "shift")),
        Shortcut("Move ↑", "k", listOf("super", "shift")),
        Shortcut("Move →", "l", listOf("super", "shift")),
        Shortcut("Fullscreen", "f"), Shortcut("Float / tile", "t", listOf("super", "shift")),
        Shortcut("Focus floating", "t"), Shortcut("Focus parent", "a"),
        Shortcut("Close window", "q", listOf("super", "shift")),
    ),
    "Layout" to listOf(
        Shortcut("Split horizontal", "o"), Shortcut("Split vertical", "v"),
        Shortcut("Stacked", "s"), Shortcut("Tabbed", "w"), Shortcut("Toggle split", "e"),
        Shortcut("Resize mode", "r"),
    ),
    "Media" to listOf(
        Shortcut("Volume −", "volume_down", emptyList()),
        Shortcut("Volume +", "volume_up", emptyList()),
        Shortcut("Mute audio", "volume_mute", emptyList()),
        Shortcut("Mute mic", "mic_mute", emptyList()),
        Shortcut("Brightness −", "brightness_down", emptyList()),
        Shortcut("Brightness +", "brightness_up", emptyList()),
        Shortcut("Warm screen", "n", listOf("super", "shift")),
        Shortcut("Reset warmth", "m", listOf("super", "shift")),
    ),
    "Session" to listOf(
        Shortcut("Reload config", "c", listOf("super", "shift")),
        Shortcut("Restart i3", "r", listOf("super", "shift")),
    ),
)

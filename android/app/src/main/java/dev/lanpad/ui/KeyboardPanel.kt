package dev.lanpad.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.lanpad.input.Command
import dev.lanpad.input.Shortcut
import dev.lanpad.input.i3Shortcuts

@Composable
fun KeyboardPanel(enabled: Boolean, input: (Command) -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf("Type & keys") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Type & keys", "i3 shortcuts").forEach { label ->
                FilterChip(selected = page == label, onClick = { page = label }, label = { Text(label) })
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (page == "Type & keys") TypingControls(enabled, input)
            else I3Controls(enabled, input)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TypingControls(enabled: Boolean, input: (Command) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var modifiers by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showLetters by rememberSaveable { mutableStateOf(false) }
    // Selected modifiers persist locally; every remote chord still releases all keys.
    LaunchedEffect(enabled) { if (!enabled) modifiers = emptyList() }
    val key: (String) -> Unit = { name ->
        input(Command.Key(name, modifiers))
    }
    Text("Write on your computer", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(value = draft, onValueChange = { value ->
        draft = value.filter { it == '\n' || it == '\t' || (it.code >= 32 && it.code != 127) }
            .take(128).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        sent = false
    }, label = { Text("Text to send") }, placeholder = { Text("Write a message, command, or search…") },
        supportingText = { Text("${draft.length}/128 · No Enter added. Newlines are sent.") },
        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        shape = RoundedCornerShape(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { input(Command.Text(draft)); sent = true },
            enabled = enabled && draft.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(if (sent) "Send again" else "Send text")
        }
        FilledTonalButton(onClick = { key("enter") }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Enter ↵") }
    }
    Text("Keys & combinations", style = MaterialTheme.typography.titleMedium)
    Text("Modifiers stay selected. Tap again or Clear modifiers to release your selection.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ctrl", "alt", "shift", "super").forEach { name ->
            FilterChip(selected = name in modifiers, enabled = enabled, onClick = {
                modifiers = if (name in modifiers) modifiers - name else modifiers + name
            }, label = { Text(name.replaceFirstChar(Char::uppercase)) })
        }
    }
    if (modifiers.isNotEmpty()) TextButton(onClick = { modifiers = emptyList() }) { Text("Clear modifiers") }
    KeyGrid(listOf("Esc" to "escape", "Tab" to "tab", "⌫ Backspace" to "backspace",
        "Space" to "space", "Delete" to "delete", "Insert" to "insert",
        "Home" to "home", "↑" to "up", "End" to "end",
        "←" to "left", "↓" to "down", "→" to "right",
        "Page up" to "page_up", "Page down" to "page_down"), enabled, key)
    Text("Editing", style = MaterialTheme.typography.titleMedium)
    KeyGrid(listOf("Copy" to "c", "Paste" to "v", "Cut" to "x", "Undo" to "z",
        "Select all" to "a", "Find" to "f"), enabled, { input(Command.Key(it, listOf("ctrl"))) })
    Text("Function keys", style = MaterialTheme.typography.titleMedium)
    KeyGrid((1..12).map { "F$it" to "f$it" }, enabled, key, columns = 4)
    TextButton(onClick = { showLetters = !showLetters }) {
        Text(if (showLetters) "Hide letters & numbers" else "Show letters & numbers")
    }
    if (showLetters) KeyGrid((('a'..'z') + ('0'..'9')).map { it.uppercase() to it.toString() },
        enabled, key, columns = 6)
}

@Composable
private fun KeyGrid(keys: List<Pair<String, String>>, enabled: Boolean, onKey: (String) -> Unit,
                    columns: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, name) ->
                    FilledTonalButton(onClick = { onKey(name) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp), shape = RoundedCornerShape(12.dp)) {
                        Text(label)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun I3Controls(enabled: Boolean, input: (Command) -> Unit) {
    var group by rememberSaveable { mutableStateOf("Apps") }
    var moveWindow by rememberSaveable { mutableStateOf(false) }
    Text("Your i3 controls", style = MaterialTheme.typography.titleLarge)
    Text("Super = Mod4 · Your configured bindings", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf("Workspaces") + i3Shortcuts.keys).forEach { name ->
            FilterChip(selected = group == name, onClick = { group = name }, label = { Text(name) })
        }
    }
    if (group == "Workspaces") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !moveWindow, onClick = { moveWindow = false }, label = { Text("Switch to") })
            FilterChip(selected = moveWindow, onClick = { moveWindow = true }, label = { Text("Move window to") })
        }
        KeyGrid((1..10).map { "$it" to if (it == 10) "0" else "$it" }, enabled, {
            input(Command.Key(it, if (moveWindow) listOf("super", "shift") else listOf("super")))
        }, columns = 5)
        ShortcutButton(Shortcut(if (moveWindow) "Move & follow previous" else "Previous workspace", "tab",
            if (moveWindow) listOf("super", "shift") else listOf("super")), enabled, input)
    } else {
        i3Shortcuts.getValue(group).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ShortcutButton(it, enabled, input, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (group == "Layout") {
        Text("In resize mode", style = MaterialTheme.typography.titleMedium)
        Text("Tap Resize mode, adjust with arrows, then Done to return to normal controls.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyGrid(listOf("Narrower" to "left", "Shorter" to "up", "Wider" to "right",
            "Taller" to "down", "Done ↵" to "enter", "Escape" to "escape"), enabled,
            { input(Command.Key(it)) })
    }
}

@Composable
private fun ShortcutButton(shortcut: Shortcut, enabled: Boolean, input: (Command) -> Unit,
                           modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = { input(shortcut.command) }, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 76.dp),
        contentPadding = PaddingValues(12.dp), shape = RoundedCornerShape(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shortcut.label, style = MaterialTheme.typography.labelLarge)
            Text(shortcut.hint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Keyboard · light", showBackground = true,
    widthDp = 393, heightDp = 700)
@androidx.compose.ui.tooling.preview.Preview(name = "Keyboard · dark", showBackground = true,
    widthDp = 393, heightDp = 700, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun KeyboardPreview() {
    LanPadTheme {
        Surface {
            KeyboardPanel(true, {}, Modifier.fillMaxSize().padding(24.dp))
        }
    }
}

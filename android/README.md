# Android touchpad

A Kotlin / Jetpack Compose application with a quiet Material 3 interface, a
muted teal accent, and system light/dark themes. It supports Android 6.0 and
newer (`minSdk 23`), and targets Android 16. Physical-device
verification remains required; configuration alone does not prove behavior on
every phone.

Tap the keyboard icon in the header to switch between touchpad and keyboard
modes. Keyboard mode includes a 128-unit text composer with a separate Enter
button, persistent modifier selections, navigation/editing/function keys, and grouped i3
bindings from the owner's configuration. The i3 controls are a static snapshot
in `input/Shortcuts.kt`; they use Super/Mod4 and include the custom Super+O
horizontal split and Super+Shift+T floating toggle. Workspaces support switching
and moving the focused window. Linux Unicode typing uses XTEST directly. Remembered phones enroll a public key
after PIN authentication and reconnect using a private Android Keystore signing
key. Remembered computers show a PIN-free reconnect view; connected computers
show their status and remaining trust duration. The receiver refreshes the
reported expiry each connection; trust lasts 90 days from PIN enrollment. Both endpoints must use protocol v4.

## Build and install

On Linux x86_64, with Java 21, `curl`, `unzip`, and `sha256sum` installed, you can
install the SDK requirements without Android Studio:

```bash
# From the repository root:
./android/install-sdk.sh
```

The installer downloads Google's pinned command-line tools, verifies the
[published checksum](https://developer.android.com/studio#command-tools), and
uses [sdkmanager](https://developer.android.com/tools/sdkmanager) to install
Platform 36, Build-Tools 36.0.0, and Platform-Tools. Answer Google's license
prompts. It uses the ignored project-local `.android-sdk/` directory by default
and creates `android/local.properties` after successful installation. It preserves
an existing `local.properties`; ensure its `sdk.dir` matches the installed SDK.
Set `ANDROID_HOME` before invoking the script if you want another SDK location.
Internet access is required for initial setup. The SDK and `local.properties`
are ignored by Git; each checkout needs its own SDK configuration.

After successful setup, build with:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Alternatively, use Android Studio's SDK Manager:

Install Android Studio, JDK 17 or newer (Java 21 is supported), SDK Platform 36,
Build-Tools 36.0.0, and SDK Platform-Tools. No system Gradle is needed.

From the project root, using the default SDK location:

```bash
cd android
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the SDK is elsewhere, set `ANDROID_HOME` to that path or configure `sdk.dir`
in an untracked `local.properties`. Enable USB debugging, connect the phone,
and accept its authorization prompt before installing with `adb`.

The first `gradlew` invocation extracts the official wrapper JAR from the Gradle
8.13 release distribution, verifying both published SHA-256 checksums. The
wrapper then manages its own cached Gradle installation (one additional initial
distribution download). The wrapper JAR is bootstrapped locally rather than
committed. Internet access and `curl`/`unzip`/`sha256sum` are needed initially.
Following invocations use cached files.

For Android Studio, run `./gradlew --version` once, then open `android/`, let
Gradle sync, and use Run. Light, dark, landscape, and PIN pairing previews are
included in `LanPadScreen.kt` and use the same composables as the app. Select
**PIN pairing · light** or **PIN pairing · dark** to render the current discovery
and pairing sheet with a sample receiver and five-digit PIN.

## Connect to the computer

Update receiver dependencies, then start it from the project root:

```bash
receiver/.venv/bin/python -m pip install -e './receiver[dev]'
receiver/.venv/bin/lanpad
```

Use `--dry-run` first to log input without moving the mouse. The receiver chooses
an active LAN address and advertises its name, address, and port automatically.
Keep both devices on a network where they can reach each other.

In LanPad, tap **Connect your computer**. The app searches for compatible receivers
using Android's [Network Service Discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd).
It selects the sole receiver automatically or remembers your previous choice.
If several appear, choose your computer. Enter its five-digit **Pairing PIN** and
tap **Connect**. Input is enabled only after pairing succeeds. Leading zeros count.

No IP or port entry is needed, including when the receiver uses a custom port.
**Search again** refreshes discovery. If multicast is blocked, use **Enter address
manually** with the receiver's printed address and port. On a multi-network PC,
`--interface wlan0` selects a different LAN. Firewalls must permit local UDP 5353
for discovery and TCP 8765 (or the chosen port) for control. Guest Wi-Fi isolation
can block both. The PIN changes when the receiver restarts and is never advertised.
After five failed PIN attempts within 60 seconds, wait one minute to retry.
Both endpoints must use protocol v4; rebuild and reinstall the app after updating.

- Move one finger to move the cursor; a short stationary tap left-clicks.
- Move two fingers to scroll horizontally or vertically with natural scrolling.
- Tap **Left** or **Right** for mouse clicks.
- Hold **Left** while moving another finger on the pad to drag.
- Adjust **Pointer speed**; the app remembers it along with the receiver name and address.
- Tap **Stop** to disconnect and release held buttons immediately.

Opening settings releases held buttons. Leaving the app disconnects and cancels
retries; reconnect when you return. Rotation retains the connection but releases
the previous gesture's buttons. The phone stays awake while paired.

## Interface

See the [interface design reference](design/preview.svg) for the light, dark, and
connection-sheet layouts (including automatic discovery and PIN entry). This is a vector
design reference, not a screenshot of the running Android app.

The touchpad is the largest element, with a faint dot texture to identify its
surface. Mouse buttons sit directly below it; connection details live in a
bottom sheet. Status uses both text and color. Stop stays at the top, controls
have large touch targets, and mouse buttons have accessibility click actions.
Compact screens scroll below the fixed header; wide screens move controls beside
the pad.

The accent is teal (`#0D766E` light, `#8CD5C5` dark). Neutral surfaces, consistent
spacing, rounded controls, and subtle press feedback follow
[Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3).
The stable Compose BOM keeps UI library versions compatible.

## Components

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Hosts Compose and stops control when the app leaves the foreground |
| `PadViewModel` | Owns screen state, validates settings, scales movement, and starts effects |
| `SettingsStore` | Stores receiver name, address, port, and sensitivity with DataStore; never stores the PIN |
| `ReceiverDiscovery` | Browses DNS-SD while visible and resolves receiver addresses and ports |
| `Pairing` / `DiscoveredReceiver` | Validate PINs, protocol versions, and discovered private endpoints |
| `PadClient` | Pairs, sends input on IO, maintains heartbeats, and handles connection failures |
| `InputBuffer` | Merges adjacent motion and preserves button ordering in a bounded queue |
| `GestureTracker` | Recognizes taps and finger transitions independently of Android |
| `LanPadScreen` / `Touchpad` | Render state and translate gestures into typed commands |

The ViewModel exposes `StateFlow`, collected by the screen while active. Screen
actions flow back through `PadActions`. Network work uses IO coroutines and
OkHttp callbacks, keeping socket waits off the UI thread.

On Android 14+, discovery watches service updates continuously. Older phones
resolve services one at a time for compatibility with older NSD implementations.
Callbacks are serialized on the main thread and tagged by generation so results
from a stopped search cannot populate a new search. Browsing stops in the background.

The sender transmits at most two input messages every 17 ms (under 120/s), plus
a heartbeat each second. A 64-event queue and 8 KiB socket backlog limit prevent
delayed cursor movement. Adjacent motion is coalesced and bounded to the
protocol's limits; excessive motion in one interval is clipped.

Transient failures retry after 1, 2, and 4 seconds. Bad PINs, the attempt limit, another
connected controller, and receiver shutdown require manual reconnection. Each
new connection pairs again with an empty queue, so old gestures are never replayed.

## Verification

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

JVM tests cover endpoints, discovered receiver selection, PINs, gesture
transitions, bounded queues, wire encoding,
pairing before input, heartbeats, and disconnect release with a MockWebServer
WebSocket. Compose tests cover setup, disabled controls, accessible clicks, and
Stop, and automatic receiver selection. Instrumented tests require a phone or
emulator.

On the phone, verify movement, clicks, both scroll axes, hold-and-drag, rotation
during a drag, Stop, leaving the app, and interrupted Wi-Fi. Repeat in light/dark
themes, landscape, and with larger fonts. Test an older Android emulator before
claiming the full compatibility range has been verified.

Also verify discovery with one receiver, selection with several, restart/address
changes, a custom port, manual fallback, an incorrect PIN, and the attempt limit.

All Android Kotlin sources, including Compose and device test sources, compile
against the Android SDK API with Kotlin 2.2.21 and its Compose plugin. The 16 JVM
tests that do not require sockets pass. The restricted development environment
blocks sockets, including Gradle's local networking, so the full Gradle build,
the two WebSocket tests, lint, APK installation, device previews, and physical
control still require verification from a normal terminal or Android Studio.

## Development transport

The current receiver uses unencrypted `ws://`. The app permits cleartext for
development, restricts entered addresses to private IPv4 ranges, ignores HTTP
proxies, and does not follow redirects. Use a trusted private network. PINs are
kept only for the current app process and excluded from preferences and backups.

Verified encryption remains required before regular use. When target SDK rises
from 36 to 37, implement Android's runtime local-network permission before
connecting; target SDK 36 currently uses the Internet permission. See
[Android local-network guidance](https://developer.android.com/privacy-and-security/local-network-permission)
and the [receiver protocol](../docs/PROTOCOL.md).

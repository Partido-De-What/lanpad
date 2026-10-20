# Pairing security and its limits

LanPad v4 remains a **trusted private LAN** tool. Its `ws://` transport does not
provide confidentiality or integrity for PIN enrollment, desktop input, or
receiver responses. Private addressing and rejection of browser origins are not
substitutes for encryption. Do not expose the receiver to the internet or use
it on an untrusted shared network.

## Remembered phones

On the first connection, the user enters the receiver's five-digit PIN. With
**Remember this phone** checked, the Android app registers its P-256 public key.
The private signing key is generated and retained by Android Keystore; the app
never sends or persists an exportable private key or the pairing PIN. Hardware
protection depends on the phone's Keystore implementation.

The receiver assigns a persistent random ID and saves public device keys with a
fixed 90-day expiry. Each successful connection reports the authoritative expiry
to Android so the displayed days remaining stay current. Reconnecting does not
extend that expiry; pairing again with a PIN renews it. A reconnect sends the device and receiver IDs, receives a
fresh random 256-bit nonce, and signs the following exact ASCII bytes using
SHA256withECDSA (DER-encoded signature):

```text
lanpad-resume-v4\n<receiver_id>\n<device_id>\n<nonce>
```

There is no trailing newline. Each challenge is valid only for that connection
and the authentication deadline is five seconds for the complete exchange.
Capturing a previous proof does not enable replay on a new connection. Public
keys and device IDs are not bearer credentials. The receiver retains PIN rate
limiting and exclusive control by one connection.

This improves resistance to credential theft and passive replay during later
reconnects. It **does not authenticate/encrypt the input stream**, prevent a live
relay, or establish a verified receiver identity. An active network attacker can
relay authentication and inject input. A captured initial PIN can authorize an
attacker's own key until the PIN changes, and a malicious peer can tamper with
initial enrollment. A compromised unlocked phone may be able to invoke its
Keystore key even though the key is not exportable. Enter the first PIN only on
a trusted LAN. TLS with pinned receiver identity remains required before using
LanPad beyond that trust boundary; challenge-response does not replace TLS.

## Storage and revocation

The CLI stores trust in `$XDG_CONFIG_HOME/lanpad/devices.json`, defaulting to
`~/.config/lanpad/devices.json`. It uses atomic writes and a mode-0600 file on
POSIX. The registry contains public keys, IDs and expiry times; its integrity
matters because writing a new key grants desktop control. On Windows, protect
the per-user configuration directory with the user's normal private ACLs.
`--trust-file PATH` overrides the location. Public registry corruption fails
startup rather than silently resetting identity or disabling authentication.
At most 32 unexpired devices are retained. Tests and dry-run fixtures can use
an isolated `--trust-file`.

To revoke every phone, stop the receiver, run `lanpad --forget-devices`, and
restart it. Existing receiver processes load trust at startup, so restarting is
required. The next connection requires a fresh PIN. To forget local computer
mappings on Android, use **Forget saved computers on this phone** in connection
settings. This disconnects and removes local remembered computer IDs; use
receiver-side revocation for a lost or compromised phone. Uninstalling the app
removes its Keystore identity. A new install must pair again.

## Input failures

Key chords always release their modifiers, including error cleanup. Modifier
chips persist as local UI selections and do not leave remote keys held between
commands. Disconnecting clears their selections. X11 text entry uses the active
keyboard map and temporarily assigns unused keycodes for Unicode, restoring
those mappings and modifier state afterward. Unicode support still depends on
the target X11 application. Text injection failures produce an `input_error`
response and preserve the connection and the phone's draft for retry.

## Sources

- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html): transport protection, credential lifetime and invalidation.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): non-exportable signing keys and device-dependent hardware protection.

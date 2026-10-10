# LanPad protocol v2

A persistent WebSocket carries one JSON object per text message. The protocol
is independent of Android version and screen dimensions. WebSocket ordering
preserves button-down, movement, and button-up sequence without sequence IDs.

## Pairing

Send this first, within five seconds of connecting:

```json
{"type":"pair","version":2,"pin":"01234"}
```

The receiver generates a random five-digit PIN on each start. Send it as a
string of exactly five ASCII digits, preserving leading zeros. It compares
PINs in constant time and accepts one controller at a time. After five failed
pairing attempts within 60 seconds, it blocks new pairing until failures expire
from that window. This budget is shared across connections and does not interrupt
an already paired controller. The successful response is:

```json
{"type":"paired","version":2}
```

Wait for this response before sending input. Reconnect and pair again after
disconnecting; use the newly printed PIN if the receiver has restarted.
Version 1 clients are incompatible; update both the app and receiver together.

## Input messages

| Command | Example | Meaning |
| --- | --- | --- |
| Move | `{"type":"move","dx":12.5,"dy":-4}` | Relative pixels; positive x right, positive y down |
| Scroll | `{"type":"scroll","dx":0,"dy":1}` | Wheel steps; positive x right, positive y down |
| Hold / release | `{"type":"button","button":"left","down":true}` | Start a drag; send `false` to end it |
| Click | `{"type":"click","button":"right"}` | One press/release pair |
| Release all | `{"type":"release_all"}` | Release all buttons held by this controller |
| Heartbeat | `{"type":"heartbeat"}` | Keep the input session alive while idle |

Buttons are `left`, `right`, or `middle`. Duplicate button presses/releases have
no effect. A click on an already held button has no effect, preserving the drag.
Fractional movement and scrolling accumulate until an integer pixel or wheel
step is available. Release-all and disconnect clear those remainders.

Messages must use exactly the documented fields. Duplicate fields, binary
frames, booleans in coordinates, nonfinite numbers, and unknown commands are
rejected. Each move axis is bounded to ±2048 pixels and each scroll axis to ±10
steps per message. Maximum message size is 1024 UTF-8 bytes.

Send a heartbeat every second even if there are no gestures. Five seconds
without an input message or heartbeat closes the session and releases buttons.
WebSocket ping/pong is additional transport monitoring; it does not replace the
application heartbeat, which detects a stalled input loop.

Input has no per-command response to avoid extra touchpad traffic. The receiver
closes the connection on any invalid command. Keep movement at or below 120
messages/second in the Android app; the receiver permits 240 messages/second
with a burst of 60. Excess traffic closes the session rather than leaving a
queue of stale mouse movement.

## Close codes

| Code | Meaning |
| --- | --- |
| 1000 | Session ended normally |
| 1001 | Receiver stopping or stopped |
| 1009 | WebSocket message too large |
| 1011 | Receiver or mouse cleanup error |
| 4000 | Application heartbeat timeout |
| 4001 | Pairing missing, invalid, unsupported, or timed out |
| 4002 | Invalid input message |
| 4003 | Too many pairing failures; wait one minute before retrying |
| 4008 | Input rate exceeded |
| 4009 | Controller already connected or too many pending pairings |

The receiver releases its held buttons before waiting for the WebSocket close
handshake. Ctrl+C and SIGTERM also release them. If releasing a button fails,
the receiver stops accepting controllers until restarted. Forced process
termination or a lost X server cannot guarantee release.

## Network scope

The CLI selects an active private IPv4 interface automatically, preferring the
default-route interface and excluding common VPN/container interfaces. Use
`--interface wlan0` or `--host 192.168.1.20` to override it. Allowed LAN addresses
are in `10.0.0.0/8`, `172.16.0.0/12`, or `192.168.0.0/16`; wildcard and public
binding are rejected. Use `--host 127.0.0.1` for loopback-only testing.
Native clients omit the Origin header; browser origins are rejected.
At most 16 connections may wait for application pairing at a time.

## Automatic discovery

The receiver advertises `_lanpad._tcp.local.` using DNS-SD/mDNS on the selected
interface. Its instance name is `LanPad <computer-name>` (renamed on conflict),
with the selected IPv4 address, listening port (default `8765`), and a TXT field
`version=2`. The PIN is never advertised. `--no-discovery` disables publication;
loopback listeners are never advertised.

Android NSD browses `_lanpad._tcp.`, resolves compatible private IPv4 endpoints,
and automatically selects the sole receiver or a previously selected name. With
several receivers, choose one. Manual address entry is available when multicast
is blocked. Discovery requires local UDP 5353; control requires the advertised
TCP port. This works without assuming a /24 subnet or probing unrelated devices.

`ws://` sends PINs and input in plaintext. Use only a trusted private
network during development. Encrypted transport with verified receiver identity
is required before regular use, as specified in roadmap step 5.

# Android client

Kotlin/Compose client speaking the same protocol (docs/PROTOCOL.md,
proto 1 + v1.1 names) against the same relay. No server changes needed —
an Android phone and a Sailfish device can call each other today.

## Architecture (mirrors the Sailfish daemon design)

- **CallService** (foreground service) owns the engine permanently:
  WebSocket + audio, auto-answer with the UI closed, full-screen
  incoming-call notification, METRIC lines to logcat.
- **CallEngine** is a line-for-line mirror of the Sailfish engine: same
  states, same 1/2/5 s backoff, persist-on-takeover, idempotent
  configure.
- **AudioPipeline**: AudioRecord → Opus (Concentus, pure JVM — no NDK)
  → 7-byte framing → WS, and back to AudioTrack. Same parameters as the
  GStreamer side: 48 kHz mono, 24 kbit/s VOIP, 20 ms, inband FEC.
- **MainActivity**: the one big Compose button + settings
  (SharedPreferences), thin client of the service.

## Setup on Ubuntu

1. Install **Android Studio** from the official tarball (or JetBrains
   Toolbox). Avoid Snap/Flatpak builds — SDK paths and adb/USB access
   are less predictable there.
2. First run installs the SDK; also install a platform matching
   `compileSdk` in `app/build.gradle.kts` when prompted.
3. Generate the Gradle wrapper once (the repo does not ship the binary
   wrapper jar):
   ```bash
   sudo apt install gradle          # any version; only used for this step
   cd client-android
   gradle wrapper --gradle-version 8.9
   ```
   Then open `client-android/` in Android Studio and let it sync.
4. USB: enable Developer options + USB debugging on the phone, plug in,
   accept the fingerprint dialog. `adb devices` should list it. On some
   distros Samsung devices need a udev rule; Android Studio's device
   manager will say so if it does.

## Devices & SDK levels

`minSdk = 33` (Samsung S22 Ultra on Android 13). `compileSdk`/`targetSdk`
are set to 36 — if Android Studio suggests a newer installed platform
(e.g. for an Android 17 Pixel), bumping these two numbers is the only
change needed.

## First run

1. On the Pi: `python3 gen_pair.py android1 Pixel Tab` → put the snippet
   in `/opt/paniccall/pairs.json`, `docker restart paniccall`.
2. Run the app from Android Studio on the phone, open Settings, enter
   relay URL + token + name, Save. Grant mic + notification permissions
   and the battery-optimization exemption when asked.
3. Test alone with the echo peer from the desktop:
   ```bash
   python3 client/tools/echo_peer.py wss://your-server.example/panic/ws \
       <OTHER_TOKEN> --call
   ```
   Expected: the phone auto-answers with the app closed (kill it from
   recents first), the full-screen notification appears, and logcat
   shows the familiar lines:
   ```bash
   adb logcat -s PanicCall
   # state in_call / METRIC call_setup_ms=… / METRIC alive …
   ```
4. Cross-platform: call the Sailfish emulator (or later the real
   device) — pair an Android token with a Sailfish token and press the
   button. The relay does not know the difference.

## The honest Android caveat

Android has no suspend problem like Sailfish, but it has a **policy**
problem: since Android 12, background microphone access is restricted.
Receiving and *hearing* the caller works unattended; whether the callee's
microphone opens without a screen tap depends on OS version and OEM. The
full-screen intent (tap = app in use = mic allowed) is the standard
escape hatch. For the baby-monitor scenario (device charging, app
visible) none of this applies. Measuring exactly where each of the three
test devices draws this line is the point of the device matrix.

Doze will also throttle the 2.5-minute keepalive when the phone sleeps
deeply; the `METRIC wakeup`/`METRIC alive` lines exist to quantify that,
same philosophy as the Sailfish measuring campaign.

## Skeleton status

Written blind against the SDK (no Android toolchain in the authoring
environment): expect the first Android Studio sync/build to surface
small fixable issues (an import, a deprecation, a Concentus API detail —
the encoder/decoder construction may need `OpusEncoder.create(...)`
style factory calls depending on the artifact version). The
architecture, protocol layer and threading model are the load-bearing
parts and match the proven Sailfish implementation.

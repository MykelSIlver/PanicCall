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
- **BootReceiver**: restarts `CallService` after a reboot or an app
  update, so the app behaves like the Sailfish daemon rather than like
  an app you have to remember to open. See "Starting at boot" below —
  this is less trivial than it sounds.

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

`minSdk = 26` — the practical floor, since `NotificationChannel`
requires it. That covers a colleague's Android 12 phone with room to
spare. `compileSdk`/`targetSdk` are set to 36; if Android Studio
suggests a newer installed platform, bumping those two numbers is
normally the only change needed.

Note that `targetSdk` is not a formality here: several behaviours this
app depends on are gated on it, in particular the restrictions on what
a `BOOT_COMPLETED` receiver may start (see below). Raising it means
re-running the boot test.

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

## Starting at boot without the app being opened

This is the Android counterpart of the Sailfish systemd user-service
autostart, and getting it working needed one non-obvious trick.

### The problem

`CallService` used to be started from exactly one place:
`MainActivity.onCreate()`. That meant that after a reboot nothing ran
until the user opened the app — no relay connection, no quick messages,
no incoming calls. On Sailfish the daemon is up before you touch the
device; on Android it was not.

The obvious fix is a `BOOT_COMPLETED` broadcast receiver, and the
obvious fix does not work. `CallService` declares the `microphone`
foreground-service type, and **apps targeting Android 14 or higher may
not launch a microphone foreground service from a `BOOT_COMPLETED`
receiver** — the system throws `ForegroundServiceStartNotAllowedException`.
Android 15 extends the same ban to `phoneCall`, `camera`, `dataSync` and
`mediaPlayback`, so there is no convenient type to hide behind either
(and `dataSync` additionally has a hard six-hour runtime limit).

### The solution: two modes, one service

The service now has two modes and switches between them by calling
`startForeground()` again, which is the only way to change an existing
foreground service's type.

| Mode | FGS type | What it can do |
| --- | --- | --- |
| **Standby** | `specialUse` | Hold the relay WebSocket open, receive and post text notifications, keep local history |
| **Call-capable** | `specialUse \| microphone` | All of the above, plus open `AudioRecord` for a call |

Standby needs no microphone at all — it is just a socket — and
`specialUse` is the one type a `BOOT_COMPLETED` receiver is still
allowed to start. `BootReceiver` therefore brings the service up in
standby, and `CallService.ensureCallCapable()` promotes it to
call-capable later. Once promoted it stays promoted for the rest of the
service's life: re-promoting per call would only add further chances of
being refused, and the type is a *declared capability*, not live
microphone use (the privacy indicator follows `AudioRecord`, not the
FGS type).

The manifest declares both types (`microphone|specialUse`) plus
`FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, and a
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining the use to the platform.

### When the promotion is attempted

Promotion may be refused: "while-in-use" permissions such as
`RECORD_AUDIO` cannot be claimed by an app that is currently in the
background, and the usual exemptions — including the
battery-optimization exemption this app requests — explicitly do not
cover that case. So it is attempted at three points, earliest first:

1. **When the UI binds** (`MainActivity.onServiceConnected`). The app is
   visibly in the foreground, which is the case the platform is
   happiest with. Covers every normal launch.
2. **When a call actually arrives**, via `CallEngine.ensureMicrophoneAllowed`.
   This has to be a direct, synchronous hook rather than a collector on
   `engine.state`: `CallEngine` auto-answers from inside its own
   WebSocket handling, before any `StateFlow` collector in `CallService`
   gets a turn, so a collector would always run too late.
3. **Implicitly via (1) again** after the user taps the full-screen
   incoming-call notification, if (2) was refused.

A refusal costs exactly one thing: auto-answer on a cold-booted phone
that has not been opened since. The call still rings; it needs one tap.
**Quick messages and their notifications are unaffected either way** —
they need no microphone.

`BootReceiver` deliberately does nothing when no relay URL and token are
configured: an ongoing notification for an app that cannot connect to
anything is pure noise. It also listens for `MY_PACKAGE_REPLACED`, so
the service returns by itself after an app update.

### Measured result

On a Samsung Galaxy S22 Ultra, after a real reboot with the app never
opened:

```
METRIC boot_start action=android.intent.action.BOOT_COMPLETED
state connecting
METRIC fgs_promote result=ok
state idle
```

The paired Sailfish device showed the phone as online without the app
ever being launched. One honest loose end: the promotion fired at boot,
and it is not clear from the code which of the three call sites
triggered it that early — neither should run before the UI binds or a
call arrives. Worth a wider `adb logcat | grep -i paniccall` (not
`-s PanicCall`, which hides other tags) on the next reboot test.

### Testing pitfalls

Two things make this painful to test, both discovered the hard way:

- **`BOOT_COMPLETED` is a protected broadcast.** `adb shell am broadcast
  -a android.intent.action.BOOT_COMPLETED` fails with a `SecurityException`
  on any retail device; only the system may send it. Google's own docs
  mention the command, but it needs a rooted/eng build. Just reboot.
- **`am force-stop` puts the app in the stopped state, and a reboot does
  not clear it.** A stopped app receives no broadcasts at all until the
  user launches it by hand — so a force-stop followed by a reboot tests
  nothing. Check with
  `adb shell dumpsys package com.mykelsilver.paniccall | grep -o "stopped=[a-z]*"`
  before concluding anything.

The `FGS_BOOT_COMPLETED_RESTRICTIONS` compat flag is not needed here:
`targetSdk` is 36, so the restrictions already apply.

## Why not push notifications (UnifiedPush / FCM)

Considered and deliberately rejected. The persistent foreground service
already delivers messages and calls with the app closed, so push would
solve a problem this app does not have — and it would cost real things:

- **It does not help with the background restrictions.** A push message
  wakes the app in the background, where exactly the same
  foreground-service rules apply. Worse, there is an asymmetry: a
  high-priority *FCM* message is on Google's documented exemption list
  from the background-start restrictions, and UnifiedPush cannot be. For
  the call path — where the whole point is answering immediately — push
  is strictly worse than the socket that is already open.
- **It needs a second app on every device.** UnifiedPush works through a
  user-installed *distributor* (ntfy, NextPush, …). That is one more
  thing to install and configure, against the zero-cognitive-load brief.
- **It breaks the relay's privacy design.** The relay would have to
  store a push endpoint URL per device and POST to it. Today it keeps no
  per-device state beyond a single in-flight pending message.

FCM is rejected outright for the self-hosted, no-Google-account
philosophy of the project. UnifiedPush stays a reasonable *fallback*
option if measurement ever shows the socket dying in Doze — and then
only as an extra wake channel for text messages, never for calls.

## Speaker routing

Android routes `VOICE_COMMUNICATION` audio to the earpiece by default —
same as a normal phone call, for privacy. That is the wrong default for
a panic/baby-monitor call, where being heard is the point. `CallService`
therefore enters `MODE_IN_COMMUNICATION` and switches to the loudspeaker
the moment a call starts, with a "Speaker on / Earpiece" chip in the UI
to switch back. Two code paths: `setCommunicationDevice` (API 31+,
explicit device selection) and the deprecated `isSpeakerphoneOn`
(needed for API 30 and below — e.g. an unpatched Android 13 device).
Requires `MODIFY_AUDIO_SETTINGS`, already in the manifest.

Known rough edge: some OEM audio HALs (Samsung has a history here) can
be slow to apply `setCommunicationDevice`, or briefly click/pop on
switch. Worth confirming on both the Pixel and the S22 specifically.

## Ringtone

`CallService.ringLoop()` plays the device's *own* default ringtone via
`RingtoneManager` while `state == "ringing"` — not a bundled sound, so
it automatically respects the user's chosen tone, volume, and Do Not
Disturb / silent-mode policy, exactly like the Sailfish side (which
instead synthesizes a SID-style arpeggio, since Sailfish has no
per-app-inherits-the-OS-ringtone equivalent).

`Ringtone` has no built-in loop, so `ringLoop()` polls `isPlaying()`
every 400 ms and calls `play()` again when it stops. Start/stop is
tied to Kotlin coroutine cancellation rather than manual bookkeeping:
`ringLoop()` is only called from inside
`engine.state.collectLatest { ... }` guarded on `s == "ringing"`, and
`collectLatest` cancels the previous block the instant the state
changes again — so answer, hangup, peer-hangup, and disconnect all stop
the ringtone for free via the `finally { ringtone.stop() }`, with no
call site able to forget. The `CH_CALL` notification channel has its
own sound explicitly disabled (`setSound(null, null)`) to avoid playing
the ringtone twice.

Doze will also throttle the 2.5-minute keepalive when the phone sleeps
deeply; the `METRIC wakeup`/`METRIC alive` lines exist to quantify that,
same philosophy as the Sailfish measuring campaign.

## Quick message

One configurable canned message ("Call me on MeshChat instead" by
default, same as Sailfish), sent with a single tap via `sendText()` on
`CallEngine` -- no free-text keyboard, matching the "zero cognitive
load" design goal from ARCHITECTURE.md. Settings has a plain text field
to change it; the main-screen button's own label always shows the
exact text that will be sent, so there's nothing to remember before
tapping. Enabled only when `state == "idle"`, same gating as the call
button.

Receiving a text shows a normal (not full-screen) notification on its
own channel (`paniccall_text`) via `CallService.postTextNotification()`,
driven by `CallEngine.textReceived` -- a `StateFlow<TextEvent?>` where
`TextEvent` carries a monotonic `id` alongside `from`/`message`
specifically so two identical messages in a row still each produce a
notification (a plain `StateFlow` only notifies on an actual value
*change*, so without the id a repeat send would silently no-op the
second time).

## Release builds and distribution

No Play Store: releases are signed APKs attached to GitHub Releases.

Signing config lives in `client-android/key.properties`, which is
excluded by `.gitignore` and holds the keystore path and password. The
keystore itself lives outside the repo entirely. Without that file the
release build simply produces an unsigned APK instead of failing, so a
fresh clone stays buildable by anyone who only wants a debug build.

```bash
cd client-android
./gradlew assembleRelease
~/Android/Sdk/build-tools/35.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk   # expect CN=PanicCall
```

Two things that are easy to get wrong:

- **Bump `versionCode` on every release.** Android detects updates by
  `versionCode`, not by tag name or `versionName`. Releases v0.1.0
  through v0.2.5 all shipped as `versionCode = 1`, which is why they
  were indistinguishable to the system and to update tooling.
- **`key.properties` is a Java properties file, not shell.** A backslash
  in the password is an escape character and is silently swallowed,
  producing a "keystore password was incorrect" failure even though the
  same string works with `keytool` on the command line. Prefer a
  password without backslashes.

Distribution is via [Obtainium](https://github.com/ImranR98/Obtainium),
pointed at the repository URL: it watches GitHub Releases and offers
updates, which is the closest thing to an app store that does not
involve an app store. Name the asset `paniccall-vX.Y.Z.apk` rather than
`app-release.apk` so releases are distinguishable and Obtainium's asset
matching has something to match on.

Changing signing keys (for example moving off the debug keystore)
requires uninstalling first on every device — Android refuses an update
with a different signature — which also wipes local settings and message
history on those devices. Say so in the release notes.

## Status

Tested working end to end on real hardware: a Google Pixel 8 Pro, a
Samsung Galaxy S22 Ultra, and a colleague's Android 12 phone. Calls,
auto-answer, quick messages, message history, speaker routing and
start-at-boot all work, against both other Android devices and the
SailfishOS client through the same relay.

The app is still entirely English: unlike the Sailfish client (English
source, Dutch translation), no `strings.xml`/i18n has been set up at
all, and every user-facing string is hardcoded in Kotlin.

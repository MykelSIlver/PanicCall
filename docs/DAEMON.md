# Background daemon (wake-up architecture)

The emergency use case requires receiving calls **without the app open**.
This is solved by moving engine ownership into a background daemon.

## Architecture

- `harbour-paniccall-daemon` (systemd user service) **owns** the
  WebSocket connection and both audio pipelines, permanently. Calls
  arrive and auto-answer even with the UI closed.
- The UI app detects at startup whether the daemon's D-Bus service
  (`com.mykelsilver.PanicCall`) is registered. If yes, it becomes a thin
  remote control (`DaemonProxy`); if no, it runs the engine embedded as
  before. The QML is identical in both modes.
- On an incoming call the daemon additionally launches the UI via
  `invoker` — cosmetic; audio never depends on it.
- Configuration is shared through dconf: the app's settings dialog
  writes `/apps/harbour-paniccall/*`, the daemon reads the same keys via
  mlite and follows changes live.
- The service name equals the app's Sailjail identity
  (OrganizationName + ApplicationName), so the sandboxed app is allowed
  to talk to the unsandboxed daemon. **Confirmed working**: an
  invoker-launched, sandboxed app detects the daemon and switches to
  proxy mode.

**Rule to remember:** when the daemon is enabled, the app must not run
in standalone mode (two connections with one token fight via close
4003). The auto-detection handles this as long as the daemon is running
before the app starts. `systemctl --user stop` the daemon if you want
the old standalone behaviour.

## Enable

```bash
systemctl --user daemon-reload
systemctl --user enable --now harbour-paniccall-daemon
systemctl --user status harbour-paniccall-daemon
```

Configure URL/token/name once via the app's settings (writes dconf; the
daemon picks it up immediately).

## Poking it over D-Bus

```bash
dbus-send --session --print-reply --dest=com.mykelsilver.PanicCall \
    / com.mykelsilver.PanicCall.Engine.State
dbus-send --session --print-reply --dest=com.mykelsilver.PanicCall \
    / com.mykelsilver.PanicCall.Engine.StartCall
dbus-send --session --dest=com.mykelsilver.PanicCall \
    / com.mykelsilver.PanicCall.Engine.Hangup
dbus-monitor "sender='com.mykelsilver.PanicCall'"
```

## The wake-up test (emulator, no device needed)

1. Deploy, enable the daemon, configure via the app, then **close the
   app completely**.
2. On the desktop, let the peer initiate the call:
   ```bash
   python3 client/tools/echo_peer.py wss://your-server.example/panic/ws \
       <OTHER_TOKEN> --call
   ```
3. Expected: the daemon auto-answers (audio opens; with
   `PANICCALL_TESTTONE=1` on the daemon you hear the tone), the UI pops
   up via invoker, and the journal shows `METRIC call_setup_ms=<n>`.

Watch it live:

```bash
journalctl --user -fu harbour-paniccall-daemon
# or, when running the daemon manually for a quick dev loop:
systemctl --user stop harbour-paniccall-daemon
PANICCALL_TESTTONE=1 harbour-paniccall-daemon
```

## Metrics

Everything measurable is one grep away:

```bash
journalctl --user -u harbour-paniccall-daemon | grep METRIC
```

- `METRIC alive uptime_s=… state=… reconnects=… wakeups=…` every 5 min —
  connection stability over time.
- `METRIC call_setup_ms=…` — incoming call to audio-open latency (the
  "emergency second").
- `METRIC wakeup n=…` — each KeepAlive suspend-wakeup (device only).

The October real-device phase is then a measuring campaign: leave the
phone overnight on battery, read the journal, and the open questions
(does the socket survive suspend, how many reconnects per night, what
does 2.5-minute wakeup cost in battery) get numbers instead of guesses.

## KeepAlive (device builds)

The suspend-wakeup machinery compiles in automatically when the target
has `nemo-keepalive-devel`:

```bash
sfdk tools package-install <target> nemo-keepalive-devel
```

Without it (e.g. the emulator, which never suspends) the daemon builds
and runs fine and says so at startup. The wakeup frequency is currently
fixed at 2.5 minutes; making it configurable is part of the planned
battery-vs-latency tuning knob.

## Known limitations of this skeleton

- No ringtone yet; auto-answer is the assumed mode.
- The daemon is not sandboxed (systemd-launched, outside Sailjail) —
  usual for daemons, but worth revisiting.
- If daemon and standalone app ever run simultaneously they fight over
  the token (by design of the reconnect takeover); the detection order
  described above avoids it in practice.

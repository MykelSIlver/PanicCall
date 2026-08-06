# PanicCall

**One button. One person. Zero menus.**

A walkie-talkie style voice app for SailfishOS that connects you to one
pre-configured person the moment you press the big red button. No login, no
password, no contact list, no "press 1 for..." — you press, they hear you.

## Why does this exist?

Two reasons. One serious, one... field-tested.

**The serious one:** emergency lines keep getting slower. In some countries,
calling the emergency number now means answering a small quiz before you reach
an actual human — name, location, nature of emergency, please hold. In a real
panic, every menu is one menu too many. PanicCall is the opposite philosophy:
the person you trust most is one press away, with two-way audio open
*immediately*. Think baby monitor, dead-man's-switch, or "grandma fell"
button — but between two phones you already own.

**The field-tested one:** it is 2 AM. You have had precisely one beer more
than the amount at which your phone's lock screen stops being a phone and
starts being an IQ test. Your password contains a `%` and two capital letters
and you no longer believe in any of them. You just want to tell your best
friend that you love them and also that you cannot find your shoes. PanicCall
does not judge. PanicCall has one button, and the button is very large.

Both scenarios share the same requirement: **reaching a human must require
zero cognitive capacity.** That's the entire design brief.

## How it works (the one-paragraph version)

Both phones keep an *outbound* WebSocket connection to a small relay server
you host yourself (a Raspberry Pi is plenty). Voice is Opus at 24 kbit/s in
20 ms frames — about 4 kB/s per direction, works on terrible connections.
Because both sides connect outward, there is **no WebRTC, no STUN, no TURN,
no ICE, and no carrier-grade NAT misery on mobile data**. The relay just
copies frames between the two members of a pair, authenticated by pre-shared
tokens. The callee can auto-answer: audio opens the instant the call arrives.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the real explanation and
[docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire format.

Beyond voice, either side can send one short canned message ("call me on
MeshChat instead") with a single tap — no keyboard, same zero-cognitive-load
philosophy as the call button itself. If the other person is offline, the
relay holds the message and delivers it the moment they reconnect; each
device keeps its own local history of what it sent and received, with a
checkmark once a sent message is confirmed delivered.

## ⚠️ Alpha status — read this before trusting it with anything

This project is in its **very early infancy**. It works — voice flows crystal
clear through the whole chain in testing — but:

- **No end-to-end encryption.** TLS protects the wire; the relay sees your
  audio. Planned, not built. Do not use this for secrets.
- **Partial jitter mitigation only.** Playback waits for a few frames to
  buffer before starting (absorbs a brief stall right after answering),
  but there is no full mid-call jitter smoothing yet — that needs
  clock-synced playback against the seq/timestamp already in every
  frame, and prototyping found it isn't a simple property tweak (see
  [docs/CLIENT.md](docs/CLIENT.md)). Choppy networks mid-call will still
  sound choppy.
- **Background wake-up works, but is only proven on the emulator so
  far.** A systemd daemon owns the connection and auto-answers with the
  app closed (46 ms call setup in testing). Real-device behaviour —
  surviving suspend, battery cost of the wakeups — is untested until
  hardware arrives. See [docs/DAEMON.md](docs/DAEMON.md).
- **Strict 1-on-1 pairs only.** One token talks to exactly one other token.
  A contacts model (one parent, multiple kids) is designed but not built.
- **The protocol may change without mercy** between versions. It is
  versioned (`proto`), so mismatched clients fail loudly instead of weirdly.
- Tested on the SailfishOS 5.0 emulator and, so far, exactly zero real
  emergencies.
- **Text messages are a nudge, not a chat.** One configurable canned
  message per device, not free text — deliberately, see the
  zero-cognitive-load philosophy above. Offline delivery is durable (a
  relay restart won't lose a queued message), but the *delivery
  receipt* itself isn't: if the sender happens to be offline at the
  exact moment their message arrives, the checkmark never comes, even
  though the message did. Sent/delivered only, no read receipts. Not
  encrypted either — same as everything else in v1.

- The **Android client** (see [docs/ANDROID.md](docs/ANDROID.md)) speaks
  the same protocol against the same relay, and has been tested working
  end to end on real hardware: a Google Pixel 8 Pro, a Samsung Galaxy
  S22 Ultra, and a colleague's Android 12 phone. Calls, auto-answer, and
  the background service all work — the one thing to know is that the
  lock screen shows it running in the background, same as any app with
  an active foreground service.

If any of this excites rather than worries you: welcome.

## Repository layout

```
server/          Python relay (asyncio + websockets), Dockerfile, pairing tool, e2e tests
client/          Native SailfishOS app (C++/QML, GStreamer, QWebSocket)
client-android/  Android app (Kotlin/Compose, OkHttp, Concentus Opus)
docs/            Protocol spec, architecture, build & test guides
```

## Quick start

**Server** (any Linux box with Python ≥ 3.10 or Docker):

```bash
cd server
pip install "websockets>=12"
python3 gen_pair.py contact1 Alice Bob     # prints two tokens + a JSON snippet
# paste the snippet into pairs.json (see pairs.example.json for the shape)
python3 relay_server.py --pairs pairs.json
python3 test_e2e.py                       # 13 checks, all green = good
```

Put nginx with TLS in front for production; config in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

**Client** (Sailfish SDK):

```bash
cd client
sfdk build
sfdk deploy --sdk
```

Then open the app → pull down → Settings → enter your relay URL, your
token and your name (names live on the devices; the server config only
holds fallbacks). The UI follows the phone's language (English source,
Dutch translation included; more welcome). Press the button. Details, pitfalls and the test plan (including how
to test alone with an echo peer): [docs/CLIENT.md](docs/CLIENT.md) and
[docs/TESTING.md](docs/TESTING.md).

## Roadmap

Roughly in order:

1. **Background wake-up** — skeleton built (see
   [docs/DAEMON.md](docs/DAEMON.md)): systemd daemon owns the engine, UI
   auto-switches to a D-Bus remote control, KeepAlive wired for device
   builds, metrics in the journal. Remaining: real-device measuring
   campaign (suspend survival, battery cost).
2. **v2 contacts model** — one token per device, multiple peers per device,
   one button per contact on the caller side (proto 2).
3. **Full jitter buffer** — clock-synced playback against the seq/
   timestamp already in every frame. Prototyping (see
   [docs/CLIENT.md](docs/CLIENT.md)) found the naive approaches (queue
   threshold alone; PTS + sync=true on a test sink) don't smooth ongoing
   jitter as expected -- needs real testing against a live pulsesink,
   not just a headless sandbox, before shipping. A small proven-safe
   start-of-call prebuffer is in place in the meantime.
4. **Speaker routing (done on Android), reconnect indicator.**
   Ringtone: done on Sailfish (synthesized SID-style arpeggio, see
   [docs/CLIENT.md](docs/CLIENT.md)); Android still needs one.
5. **Application-level encryption.**

## License

MIT — see [LICENSE](LICENSE).

Built for the Commodore Callback 8020, tested against a Raspberry Pi in a
Dutch broom closet. No shoes were found during the development of this
software.

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

## ⚠️ Alpha status — read this before trusting it with anything

This project is in its **very early infancy**. It works — voice flows crystal
clear through the whole chain in testing — but:

- **No end-to-end encryption.** TLS protects the wire; the relay sees your
  audio. Planned, not built. Do not use this for secrets.
- **No jitter buffer yet.** Fine on decent connections; choppy networks will
  sound choppy.
- **The app must be in the foreground to receive calls.** Background
  wake-up (the most important feature for the emergency use case) is the top
  of the roadmap, not in the code.
- **Strict 1-on-1 pairs only.** One token talks to exactly one other token.
  A contacts model (one parent, multiple kids) is designed but not built.
- **The protocol may change without mercy** between versions. It is
  versioned (`proto`), so mismatched clients fail loudly instead of weirdly.
- Tested on the SailfishOS 5.0 emulator and, so far, exactly zero real
  emergencies.

If any of this excites rather than worries you: welcome.

## Repository layout

```
server/   Python relay (asyncio + websockets), Dockerfile, pairing tool, e2e tests
client/   Native SailfishOS app (C++/QML, GStreamer, QWebSocket)
docs/     Protocol spec, architecture, build & test guides
```

## Quick start

**Server** (any Linux box with Python ≥ 3.10 or Docker):

```bash
cd server
pip install "websockets>=12"
python3 gen_pair.py family1 Alice Bob     # prints two tokens + a JSON snippet
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

Then open the app → pull down → Settings → enter your relay URL and your
token. Press the button. Details, pitfalls and the test plan (including how
to test alone with an echo peer): [docs/CLIENT.md](docs/CLIENT.md) and
[docs/TESTING.md](docs/TESTING.md).

## Roadmap

Roughly in order:

1. **Background wake-up** — receive calls without the app open (daemon +
   Sailfish KeepAlive). *The* feature for the emergency use case.
2. **v2 contacts model** — one token per device, multiple peers per device,
   one button per contact on the caller side (proto 2).
3. **Jitter buffer** — use the seq/timestamp already in every frame.
4. **Ringtone, speaker routing, reconnect indicator.**
5. **Application-level encryption.**

## License

MIT — see [LICENSE](LICENSE).

Built for the Commodore Callback 8020, tested against a Raspberry Pi in a
Dutch broom closet. No shoes were found during the development of this
software.

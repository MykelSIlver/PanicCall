# Testing

## Server: automated end-to-end

```bash
cd server
python3 relay_server.py --pairs pairs.example.json &
python3 test_e2e.py
```

13 checks: auth, bad-token reject (4001), presence pushes, call
signalling, 200 audio frames relayed byte-exact and in order, reverse
direction, hangup, reconnect takeover (4003) without disturbing the
peer. The e2e script expects the test tokens from `pairs.example.json`
(`aaaa…`/`bbbb…`) — never deploy those tokens.

## Client: test alone with the echo peer

`client/tools/echo_peer.py` connects as the *other* member of your pair,
auto-answers, and echoes every audio frame back after a delay
(default 1 s). Speak into the phone, hear yourself a second later —
that proves the entire chain in both directions:

```
mic → opusenc → framing → wss → nginx → relay → echo → relay → wss
    → opusdec → speaker
```

```bash
python3 client/tools/echo_peer.py wss://your-server.example/panic/ws <OTHER_TOKEN>
```

It also prints frame-size statistics every ~5 s. Opus is variable
bitrate, so the sizes tell you whether actual sound is in the frames
without playing anything: flat ~29 B = digital silence, peaks of
60–120 B = speech ("SPEECH detected"). Constant 29 B while you are
talking means the capture side gets no signal (in the emulator: check
VirtualBox audio input; on device: check Sailjail permissions).

## Suggested test ladder

1. **Emulator + test tone + echo peer.** `PANICCALL_TESTTONE=1`; expect
   the 440 Hz tone back after ~1.5 s.
2. **Emulator + host microphone** (VirtualBox audio input enabled) or
   skip straight to:
3. **Real device + echo peer.** Launch via the app icon (that's when
   Sailjail actually applies), speak, listen.
4. **Two devices**, each with its own token, auto-answer on the callee.
5. **The moment of truth: one device on mobile data, wifi off.** This is
   the exact scenario where WebRTC-based approaches fail behind CGNAT;
   here it must behave identically to wifi, because both sides only ever
   connect outbound.

## Watching the server

```bash
docker logs -f paniccall
```

`AUTH OK <name> (<pair>)`, `AUTH FAIL token=…`, `REJECT <reason>`,
`CALL A -> B (online|OFFLINE)`, `DROP audio … peer offline`,
`OFFLINE <name>` — every event of interest is one grep away.

# PanicCall relay protocol — version 1

Transport: one WebSocket connection per device to the relay server
(behind nginx: `wss://your-server.example/panic/ws`). Both devices connect
*outbound*; the server copies frames between the two members of a pair.
There is no peer-to-peer traffic.

WebSocket has two frame types and we use both:

- **Text frames** = JSON control messages (auth, call, hangup, presence)
- **Binary frames** = audio (one Opus packet per WebSocket message)

## 1. Handshake

The **first** message after connecting must be a text frame within
5 seconds:

```json
{"type": "hello", "token": "<64 hex chars>", "proto": 1, "name": "Alice"}
```

`name` is **optional** (added in v1.1, backward compatible): the display
name chosen on the device, max 32 printable characters. The server
remembers it for the session lifetime and uses it towards the peer in
`welcome`, `incoming_call`, `hangup` and `peer_name`. Without it, the
fallback name from the server's `pairs.json` is used.

Server reply on success:

```json
{"type": "welcome", "you": "Kid", "peer": "Dad", "peer_online": true}
```

On an unknown token or timeout the server closes the connection with
WebSocket close code **4001** (bad token) or **4002** (hello timeout).
Any other frame before the hello → close 4000.

If the same token connects again (e.g. after a wifi→5G switch), the old
connection is closed with code **4003** (replaced) and the new one takes
over. Clients may therefore always reconnect blindly.

## 2. Control messages (text/JSON)

| Direction       | Message                                        | Meaning |
|-----------------|------------------------------------------------|---------|
| client → server | `{"type":"call"}`                              | Ring the peer; server forwards as `incoming_call` |
| server → client | `{"type":"incoming_call","from":"Kid"}`        | Peer is calling → show UI / auto-answer, open audio |
| client → server | `{"type":"hangup"}`                            | End the call; forwarded to the peer |
| server → client | `{"type":"hangup","from":"Dad"}`               | Peer hung up |
| server → client | `{"type":"peer_online"}` / `{"type":"peer_offline"}` | Presence change of the peer |
| server → client | `{"type":"peer_name","name":"Alice"}`          | Peer (re)connected with a display name; update the UI |
| server → client | `{"type":"error","reason":"..."}`              | Non-fatal error (e.g. peer offline on `call`) |

Clients **must ignore** unknown `type` values (forward compatible).

Keepalive: the server sends a WebSocket **ping** (protocol level, not
JSON) every 30 s. QWebSocket answers with a pong automatically; no client
code needed. After 3 missed pongs the server drops the connection and the
peer receives `peer_offline`.

## 3. Audio frames (binary)

One Opus packet per binary WebSocket message, with a 7-byte header:

```
offset  size  content
0       1     frame type: 0x01 = AUDIO (other values: ignore)
1       2     sequence number, uint16 big-endian, wraps 65535→0
3       4     capture timestamp in ms, uint32 big-endian (wraps; only for
              jitter buffering / relative timing — NOT wall-clock time)
7       n     raw Opus packet, exactly as produced by opusenc
```

The server relays the payload **byte-for-byte unchanged** to the peer.
Sequence and timestamp exist purely for the receiving client: seq gaps =
packet loss (let Opus PLC do its job), the timestamp feeds a jitter
buffer.

Recommended encoder settings (GStreamer):

```
opusenc bitrate=24000 frame-size=20 audio-type=voice inband-fec=true
```

- 20 ms frames → 50 packets/s → seq wraps in ~22 min, fine.
- 24 kbit/s + header + WS overhead ≈ 4 kB/s per direction.
- `inband-fec=true` makes Opus embed recovery data; free robustness.

Audio may only flow after `welcome`. Frames arriving while the peer is
offline are silently dropped by the server (counter in the log). There is
deliberately **no "call accepted" handshake before audio**: for the baby
monitor / emergency scenario the caller streams immediately after `call`,
and the callee opens its playback pipeline directly on `incoming_call`.

## 4. Pairing (outside the protocol)

Tokens are 32 random bytes, hex-encoded (64 characters), generated with
`gen_pair.py`. Each pair has exactly 2 tokens. The server knows them via
`pairs.json`; the devices receive their token once during setup (QR code
or typed in). No usernames, no passwords. The names in `pairs.json` are
only fallbacks: each device announces its own display name in the
`hello` (see §1), so renaming happens on the phone, not on the server. Note: v1 has no
application-level encryption — TLS via nginx is the only protection in
transit, and the token is the only credential. Treat it like a key.

## 5. Versioning

`proto` in the hello is currently 1. Server and client refuse each other
on an unknown major version (close 4004), so breaking changes can be made
later without silent corruption.

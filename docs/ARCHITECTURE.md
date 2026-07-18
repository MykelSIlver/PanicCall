# Architecture

## The problem this design avoids

The obvious way to build phone-to-phone voice is WebRTC. WebRTC tries to
establish a direct peer-to-peer connection (STUN/ICE). On 4G/5G, virtually
every phone sits behind carrier-grade NAT (CGNAT): it has no public IP and
hole punching frequently fails. WebRTC then falls back to a TURN relay —
which most hobby deployments don't run. The result is the classic symptom:
*works on wifi, fails on mobile data*.

PanicCall skips the entire problem: **there is no P2P attempt at all.**
Both phones make an *outbound* connection to a relay you host, and the
relay copies audio frames between them. Outbound connections work through
CGNAT, double NAT and firewalls, deterministically. The price is that all
audio passes through your server — acceptable for a self-hosted tool, and
the bandwidth is trivial (~4 kB/s per direction per call).

## Components

```
Phone A ──wss──▶ nginx (TLS) ──▶ relay (Docker) ◀── pairs.json
Phone B ──wss──▶      │                │
                      └── /panic/ws ───┘
```

### Relay server (`server/relay_server.py`)

A small asyncio Python program (~250 lines) using the `websockets`
library:

- validates the `hello` token against `pairs.json`
- tracks presence per pair member and pushes `peer_online`/`peer_offline`
- forwards `call`/`hangup` control messages
- copies binary audio frames byte-for-byte to the peer
- replaces the old connection when a token reconnects (close 4003), which
  makes wifi↔mobile-data switches self-healing
- logs every auth, call, reject and drop, so `docker logs -f paniccall`
  is a complete diagnostic tool

Deliberate non-features: no accounts, no persistence, no message history,
no TLS (nginx owns that), no transcoding (frames pass through untouched).

### nginx

Standard WebSocket proxy location:

```nginx
location /panic/ws {
    proxy_pass http://127.0.0.1:8765;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 300s;    # > server ping interval of 30s
    proxy_send_timeout 300s;
    proxy_buffering off;        # do not buffer audio
}
```

Note: probing this endpoint with plain `curl`/HEAD returns an error
status. That is expected — the websockets library does not speak plain
HTTP. Test with a real WebSocket client.

### Docker

`server/Dockerfile` builds a python:3.12-alpine image. Recommended
hardening (see `server/docker-compose.example.yml`): `read_only`,
`cap_drop: [ALL]`, `no-new-privileges`, `init: true`, log rotation, and
publishing only on `127.0.0.1` so nginx is the sole entry point. The
relay makes no outbound connections and writes nothing to disk;
`pairs.json` is mounted read-only.

### Sailfish client (`client/`)

Native C++/QML app. The interesting part is `src/callengine.{h,cpp}`:

- **One QWebSocket** carries JSON control (text frames) and audio
  (binary frames).
- **Send pipeline** (GStreamer):
  `pulsesrc ! opusenc bitrate=24000 frame-size=20 audio-type=voice
  inband-fec=true ! appsink`. Each Opus packet gets the 7-byte header
  and goes out as one binary WebSocket message.
- **Receive pipeline**:
  `appsrc ! queue ! opusdec plc=true ! ... ! queue ! pulsesink
  sync=false`. Header stripped, packet pushed into the appsrc.
- **Threading**: GStreamer's appsink callback runs on a streaming
  thread; QWebSocket is not thread-safe. Frames therefore hop to the Qt
  main thread via a `Qt::QueuedConnection` signal before being sent.
- **State machine**: `disconnected → connecting → idle → ringing /
  in_call`, driven by the protocol messages. Close codes 4001/4003/4004
  stop the reconnect loop with a clear error; everything else reconnects
  with 1/2/5 s backoff.
- **Bus watch**: a 200 ms timer polls both pipeline buses and surfaces
  GStreamer errors into the UI — silent pipeline death was the hardest
  bug class during development.
- **Auto-answer**: on `incoming_call` the callee opens audio
  immediately (configurable). No accept round-trip: the caller streams
  right after `call`. That is the emergency-use design decision.

`sync=false` on pulsesink is a deliberate v1 trade-off: frames play as
they arrive (walkie-talkie model) instead of chasing network-jittery
timestamps. Long-running calls between devices with drifting clocks will
eventually need the real jitter buffer (roadmap).

### Sailjail

The `.desktop` file requests `Permissions=Internet;Audio;Microphone`.
Two hard-won notes: an **empty** `Permissions=` line silently blocks all
network access, and if microphone capture is silent without errors, try
adding `RecordMedia` (varies per SailfishOS release).

## Design principles

1. **Zero cognitive load in the moment of use.** One button. Everything
   configurable happens during setup, nothing during a panic.
2. **Outbound-only networking.** If it works on hostile hotel wifi, it
   works everywhere.
3. **Boring transports.** TCP/WebSocket + TLS, not UDP + custom crypto.
   Latency of 100–200 ms is fine for voice; predictability wins.
4. **The server is dumb.** All intelligence lives at the edges; the relay
   only authenticates and copies. This keeps the trusted computing base
   tiny and the server replaceable.
5. **Fail loudly.** Versioned protocol, distinct close codes, logged
   rejects, bus-error surfacing. Silent failure modes cost the most
   debugging time of this whole project.

#!/usr/bin/env python3
"""Echo peer — test the phone client without a second device.

Connects to the relay as the OTHER member of the pair, auto-answers, and
echoes every received audio frame back after a delay. Speak into the
phone and you hear yourself ~1 s later: proves the entire chain
(mic -> opusenc -> framing -> WS -> nginx -> relay -> WS -> opusdec ->
speaker) in both directions.

Usage:
    python3 echo_peer.py wss://your-server.example/panic/ws <TOKEN> [delay_s] [--call]

With --call the peer initiates the call itself (needed to test the
background daemon: nobody touches the phone, the daemon must answer).
"""
import asyncio
import json
import sys

import websockets


async def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    url, token = sys.argv[1], sys.argv[2]
    rest = sys.argv[3:]
    initiate = "--call" in rest
    rest = [a for a in rest if a != "--call"]
    delay = float(rest[0]) if rest else 1.0

    async with websockets.connect(url) as ws:
        await ws.send(json.dumps({"type": "hello", "token": token, "proto": 1}))
        if initiate:
            await ws.send(json.dumps({"type": "call"}))
            print(">>> call sent — the peer's daemon should auto-answer now")

        async def echo_later(frame: bytes):
            await asyncio.sleep(delay)
            try:
                await ws.send(frame)
            except websockets.ConnectionClosed:
                pass

        n = 0
        size_sum = 0
        size_min = 10**9
        size_max = 0
        async for raw in ws:
            if isinstance(raw, bytes):
                n += 1
                size_sum += len(raw)
                size_min = min(size_min, len(raw))
                size_max = max(size_max, len(raw))
                if n % 250 == 1:                  # ~every 5 s of audio
                    avg = size_sum / n
                    print(f"audio: {n} frames, avg {avg:.0f} B "
                          f"(min {size_min}, max {size_max}) — "
                          f"{'SPEECH detected' if size_max > 45 else 'looks like SILENCE'}")
                asyncio.ensure_future(echo_later(raw))
            else:
                msg = json.loads(raw)
                print("ctrl:", msg)
                if msg.get("type") == "incoming_call":
                    print(">>> call from", msg.get("from"),
                          "- echo active, start talking")


asyncio.run(main())

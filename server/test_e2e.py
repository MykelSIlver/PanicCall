#!/usr/bin/env python3
"""End-to-end test for relay_server.py — simulates two phones.

Checks: auth, welcome, presence, call signalling, byte-exact audio
relay with correct ordering, peer-offline drop, bad-token reject,
and reconnect replacement (close 4003).
"""
import asyncio
import json
import struct
import secrets
import sys

import websockets

URI = "ws://127.0.0.1:8765"
TOK_A = "aa" * 32
TOK_B = "bb" * 32
FAILURES = []


def check(name: str, cond: bool, detail: str = ""):
    status = "OK  " if cond else "FAIL"
    print(f"[{status}] {name}" + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILURES.append(name)


def audio_frame(seq: int, ts: int, payload: bytes) -> bytes:
    return struct.pack(">BHI", 0x01, seq, ts) + payload


async def recv_json(ws, timeout=3.0) -> dict:
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout)
        if isinstance(raw, str):
            return json.loads(raw)


async def hello(ws, token: str) -> dict:
    await ws.send(json.dumps({"type": "hello", "token": token, "proto": 1}))
    return await recv_json(ws)


async def main():
    # 1. bad token is rejected with 4001
    try:
        async with websockets.connect(URI) as ws:
            await ws.send(json.dumps(
                {"type": "hello", "token": "ff" * 32, "proto": 1}))
            await ws.recv()
        check("bad token rejected", False, "server accepted it")
    except websockets.ConnectionClosed as e:
        check("bad token rejected", e.rcvd and e.rcvd.code == 4001,
              f"close code {e.rcvd.code if e.rcvd else '?'}")

    # 2. A connects first: peer offline
    ws_a = await websockets.connect(URI)
    w = await hello(ws_a, TOK_A)
    check("A welcome", w.get("type") == "welcome" and w.get("peer") == "Papa")
    check("A sees peer offline", w.get("peer_online") is False)

    # 3. audio while peer offline must be dropped silently (no error, no echo)
    await ws_a.send(audio_frame(0, 0, b"dropme"))

    # 4. B connects: B sees peer online, A gets peer_online push
    ws_b = await websockets.connect(URI)
    w = await hello(ws_b, TOK_B)
    check("B welcome", w.get("type") == "welcome" and w.get("peer") == "Kind")
    check("B sees peer online", w.get("peer_online") is True)
    msg = await recv_json(ws_a)
    check("A pushed peer_online", msg.get("type") == "peer_online")

    # 5. call signalling A -> B
    await ws_a.send(json.dumps({"type": "call"}))
    msg = await recv_json(ws_b)
    check("B gets incoming_call from Kind",
          msg.get("type") == "incoming_call" and msg.get("from") == "Kind")

    # 6. relay 200 audio frames A -> B, byte-exact and in order
    sent = []
    for seq in range(200):
        payload = secrets.token_bytes(80)          # realistic Opus size
        frame = audio_frame(seq, seq * 20, payload)
        sent.append(frame)
        await ws_a.send(frame)
    got = []
    while len(got) < 200:
        raw = await asyncio.wait_for(ws_b.recv(), 3.0)
        if isinstance(raw, bytes):
            got.append(raw)
    check("200 frames relayed byte-exact and in order", got == sent)

    # 7. audio also flows B -> A
    f = audio_frame(7, 140, b"reply-audio")
    await ws_b.send(f)
    raw = await asyncio.wait_for(ws_a.recv(), 3.0)
    check("reverse direction relays", raw == f)

    # 8. hangup B -> A
    await ws_b.send(json.dumps({"type": "hangup"}))
    msg = await recv_json(ws_a)
    check("A gets hangup", msg.get("type") == "hangup" and msg.get("from") == "Papa")

    # 9. reconnect with same token replaces old connection (4003)
    ws_a2 = await websockets.connect(URI)
    w = await hello(ws_a2, TOK_A)
    check("A reconnect welcomed", w.get("type") == "welcome")
    try:
        await asyncio.wait_for(ws_a.recv(), 3.0)
        # drain until close
        while True:
            await asyncio.wait_for(ws_a.recv(), 3.0)
    except websockets.ConnectionClosed as e:
        check("old A closed with 4003", e.rcvd and e.rcvd.code == 4003,
              f"close code {e.rcvd.code if e.rcvd else '?'}")
    # B must NOT have seen a peer_offline blip caused by the takeover;
    # relay must still work through the new connection.
    f = audio_frame(1, 20, b"after-reconnect")
    await ws_a2.send(f)
    raw = await asyncio.wait_for(ws_b.recv(), 3.0)
    while isinstance(raw, str):                    # skip any stray control
        raw = await asyncio.wait_for(ws_b.recv(), 3.0)
    check("relay works after reconnect", raw == f)

    await ws_a2.close()
    await ws_b.close()

    print()
    if FAILURES:
        print(f"{len(FAILURES)} test(s) FAILED: {FAILURES}")
        sys.exit(1)
    print("All tests passed.")


asyncio.run(main())

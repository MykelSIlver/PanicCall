#!/usr/bin/env python3
"""PanicCall relay server — proto 1.

Relays Opus-over-WebSocket audio between the two members of a pair.
No P2P, no STUN/TURN: both phones connect *outbound* to this server,
which makes it work behind CGNAT (5G) deterministically.

Usage:
    python3 relay_server.py [--host 0.0.0.0] [--port 8765] [--pairs pairs.json]

Requires: websockets >= 12  (pip install websockets)
"""

import argparse
import asyncio
import json
import logging
import secrets
import signal
import sys
from dataclasses import dataclass, field
from pathlib import Path

import websockets
from websockets.asyncio.server import serve, ServerConnection

PROTO_VERSION = 1
HELLO_TIMEOUT = 5.0          # seconds to send the hello frame
PING_INTERVAL = 30           # server-side WS ping
PING_TIMEOUT = 90            # 3 missed pings -> drop
MAX_MESSAGE = 4096           # bytes; an Opus voice packet is ~60-120 B

CLOSE_BAD_FRAME = 4000
CLOSE_BAD_TOKEN = 4001
CLOSE_HELLO_TIMEOUT = 4002
CLOSE_REPLACED = 4003
CLOSE_BAD_PROTO = 4004

log = logging.getLogger("paniccall")


@dataclass
class Member:
    token: str
    name: str
    pair_id: str
    peer: "Member" = None                      # set after config load
    conn: ServerConnection = None              # live connection or None
    dropped_audio: int = field(default=0)      # frames dropped (peer offline)


def load_pairs(path: Path) -> dict[str, Member]:
    """Return token -> Member, with peer links resolved."""
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"config not found: {path}")
    except ValueError as e:
        sys.exit(f"{path} is not valid JSON: {e}")
    if not isinstance(data, dict) or "pairs" not in data:
        sys.exit(f"{path} is missing the outer 'pairs' key. Expected shape: "
                 '{"pairs": [ <snippet from gen_pair.py>, ... ]} — the snippet '
                 "printed by gen_pair.py is not the whole file.")
    members: dict[str, Member] = {}
    for pair in data["pairs"]:
        pid = pair["id"]
        entries = list(pair["members"].items())
        if len(entries) != 2:
            sys.exit(f"pair '{pid}' must have exactly 2 members")
        a = Member(token=entries[0][0], name=entries[0][1], pair_id=pid)
        b = Member(token=entries[1][0], name=entries[1][1], pair_id=pid)
        a.peer, b.peer = b, a
        for m in (a, b):
            if m.token in members:
                sys.exit(f"duplicate token in config: {m.token[:8]}…")
            members[m.token] = m
    return members


async def send_json(conn: ServerConnection, obj: dict) -> None:
    try:
        await conn.send(json.dumps(obj))
    except websockets.ConnectionClosed:
        pass


class Relay:
    def __init__(self, members: dict[str, Member]):
        self.members = members

    async def handler(self, conn: ServerConnection) -> None:
        member = await self._handshake(conn)
        if member is None:
            return
        try:
            await self._session(conn, member)
        finally:
            # Only clean up if we are still the active connection —
            # a reconnect may already have taken over.
            if member.conn is conn:
                member.conn = None
                log.info("OFFLINE %s (%s)", member.name, member.pair_id)
                if member.peer.conn is not None:
                    await send_json(member.peer.conn, {"type": "peer_offline"})

    async def _handshake(self, conn: ServerConnection) -> Member | None:
        addr = conn.remote_address
        try:
            raw = await asyncio.wait_for(conn.recv(), timeout=HELLO_TIMEOUT)
        except asyncio.TimeoutError:
            log.warning("REJECT hello timeout from %s", addr)
            await conn.close(CLOSE_HELLO_TIMEOUT, "hello timeout")
            return None
        except websockets.ConnectionClosed:
            log.info("REJECT closed before hello from %s", addr)
            return None

        if isinstance(raw, bytes):
            log.warning("REJECT binary frame before hello from %s", addr)
            await conn.close(CLOSE_BAD_FRAME, "expected hello")
            return None
        try:
            hello = json.loads(raw)
            assert hello["type"] == "hello"
            token = str(hello["token"])
            proto = int(hello["proto"])
        except (ValueError, KeyError, AssertionError, TypeError):
            log.warning("REJECT malformed hello from %s: %.80s", addr, raw)
            await conn.close(CLOSE_BAD_FRAME, "malformed hello")
            return None

        if proto != PROTO_VERSION:
            log.warning("REJECT proto %s (server speaks %d) from %s",
                        proto, PROTO_VERSION, addr)
            await conn.close(CLOSE_BAD_PROTO, f"server speaks proto {PROTO_VERSION}")
            return None

        member = self._lookup(token)
        if member is None:
            log.warning("AUTH FAIL token=%s… from %s", token[:8], conn.remote_address)
            await conn.close(CLOSE_BAD_TOKEN, "unknown token")
            return None

        # Reconnect: kick the previous connection for this token.
        if member.conn is not None:
            old = member.conn
            member.conn = None
            await old.close(CLOSE_REPLACED, "replaced by new connection")

        member.conn = conn
        log.info("AUTH OK %s (%s) from %s",
                 member.name, member.pair_id, conn.remote_address)

        peer_online = member.peer.conn is not None
        await send_json(conn, {
            "type": "welcome",
            "you": member.name,
            "peer": member.peer.name,
            "peer_online": peer_online,
        })
        if peer_online:
            await send_json(member.peer.conn, {"type": "peer_online"})
        return member

    def _lookup(self, token: str) -> Member | None:
        # Constant-time compare against every known token; the config is
        # tiny, so this is cheap and avoids a timing oracle on the dict.
        found = None
        for known, m in self.members.items():
            if secrets.compare_digest(known, token):
                found = m
        return found

    async def _session(self, conn: ServerConnection, member: Member) -> None:
        try:
            async for raw in conn:
                if member.conn is not conn:    # we were replaced mid-loop
                    return
                if isinstance(raw, bytes):
                    await self._relay_audio(member, raw)
                else:
                    await self._handle_control(conn, member, raw)
        except websockets.ConnectionClosed:
            pass                                # normal drop or 4003 takeover

    async def _relay_audio(self, member: Member, frame: bytes) -> None:
        if len(frame) < 7 or frame[0] != 0x01:
            return                              # unknown binary frame: ignore
        peer_conn = member.peer.conn
        if peer_conn is None:
            member.dropped_audio += 1
            if member.dropped_audio % 250 == 1:  # ~every 5 s of speech
                log.info("DROP audio from %s: peer offline (total %d)",
                         member.name, member.dropped_audio)
            return
        try:
            await peer_conn.send(frame)         # relay byte-for-byte
        except websockets.ConnectionClosed:
            pass

    async def _handle_control(self, conn, member: Member, raw: str) -> None:
        try:
            msg = json.loads(raw)
            mtype = msg.get("type")
        except ValueError:
            return
        peer_conn = member.peer.conn

        if mtype == "call":
            log.info("CALL %s -> %s (%s)", member.name, member.peer.name,
                     "online" if peer_conn else "OFFLINE")
            if peer_conn is None:
                await send_json(conn, {"type": "error", "reason": "peer_offline"})
            else:
                await send_json(peer_conn,
                                {"type": "incoming_call", "from": member.name})
        elif mtype == "hangup":
            log.info("HANGUP %s", member.name)
            if peer_conn is not None:
                await send_json(peer_conn, {"type": "hangup", "from": member.name})
        # unknown types: ignore (forward compatible)


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--pairs", default="pairs.json", type=Path)
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")

    members = load_pairs(args.pairs)
    n_pairs = len({m.pair_id for m in members.values()})
    log.info("loaded %d pair(s), %d token(s)", n_pairs, len(members))

    relay = Relay(members)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    async with serve(relay.handler, args.host, args.port,
                     ping_interval=PING_INTERVAL,
                     ping_timeout=PING_TIMEOUT,
                     max_size=MAX_MESSAGE,
                     compression=None):        # never compress 20ms audio
        log.info("listening on %s:%d", args.host, args.port)
        await stop.wait()
    log.info("bye")


if __name__ == "__main__":
    asyncio.run(main())

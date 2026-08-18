#!/usr/bin/env python3
"""Inspect (and optionally clear) the relay's queued-message state.

The relay holds up to --max-pending texts per member while that member is
offline, and mirrors them to its --pending-state file on every change.
This reads that file, resolves the opaque tokens against pairs.json, and
prints who has what waiting.

    sudo ./pending_tool.py                      # show everything
    sudo ./pending_tool.py --pair emulator-s22  # one pair only
    sudo ./pending_tool.py --json               # machine-readable

Clearing (READ THIS FIRST):

    The running relay keeps the queues in memory and rewrites the state
    file on every change. Editing the file underneath a live relay does
    nothing -- the next queued message overwrites your edit, and a
    reconnecting member is still handed the old backlog from memory. So
    the relay MUST be stopped first:

        docker compose stop paniccall
        sudo ./pending_tool.py --clear
        docker compose start paniccall

    --clear refuses to run unless you pass --i-stopped-the-relay, purely
    so this cannot be done by accident half-way through a test.
"""
import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

# Defaults match the paths in docker-compose.example.yml as seen from the
# HOST (the container sees them as /config and /state).
DEFAULT_PAIRS = Path("/opt/paniccall/pairs.json")
DEFAULT_STATE = Path("/opt/paniccall/state/pending.json")


def load_pairs(path: Path) -> dict[str, tuple[str, str]]:
    """token -> (display name, pair id)"""
    data = json.loads(path.read_text())
    out = {}
    for pair in data.get("pairs", []):
        for token, name in pair.get("members", {}).items():
            out[token] = (name, pair["id"])
    return out


def load_state(path: Path) -> dict[str, list]:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text())
    except ValueError as e:
        sys.exit(f"{path} is not valid JSON: {e}")
    # Pre-v0.2.8 wrote a single object per token instead of a list; the
    # relay still reads that, so this should too.
    return {tok: (p if isinstance(p, list) else [p]) for tok, p in data.items()}


def report(pairs, state, only_pair=None):
    total = 0
    by_pair: dict[str, list] = {}
    for token, (name, pair_id) in pairs.items():
        if only_pair and pair_id != only_pair:
            continue
        by_pair.setdefault(pair_id, []).append((name, token, state.get(token, [])))

    if not by_pair:
        print(f"No pair matching {only_pair!r} in pairs.json.")
        return 0

    for pair_id in sorted(by_pair):
        print(f"\n{pair_id}")
        for name, token, queued in sorted(by_pair[pair_id]):
            total += len(queued)
            head = f"  {name:<12} {token[:8]}…"
            if not queued:
                print(f"{head}  (nothing waiting)")
                continue
            print(f"{head}  {len(queued)} waiting:")
            for i, m in enumerate(queued, 1):
                sender = m.get("from", "?")
                text = str(m.get("message", "")).replace("\n", " ")
                if len(text) > 60:
                    text = text[:57] + "…"
                print(f"      {i}. from {sender}: {text}")

    # Anything in the state file whose token is no longer in pairs.json:
    # the relay ignores these on load, so they would sit there forever.
    orphans = {t: q for t, q in state.items() if t not in pairs and q}
    if orphans and not only_pair:
        print("\nOrphaned (token no longer in pairs.json, relay ignores these):")
        for token, queued in orphans.items():
            print(f"  {token[:8]}…  {len(queued)} message(s)")

    print(f"\n{total} message(s) queued in total.")
    return total


def main():
    ap = argparse.ArgumentParser(
        description="Inspect or clear the relay's queued-message state.")
    ap.add_argument("--pairs", type=Path, default=DEFAULT_PAIRS)
    ap.add_argument("--state", type=Path, default=DEFAULT_STATE)
    ap.add_argument("--pair", metavar="ID", help="limit to one pair id")
    ap.add_argument("--json", action="store_true",
                    help="dump the resolved state as JSON instead")
    ap.add_argument("--clear", action="store_true",
                    help="empty the queues (relay must be stopped first)")
    ap.add_argument("--i-stopped-the-relay", action="store_true",
                    help="required confirmation for --clear")
    args = ap.parse_args()

    if not args.pairs.exists():
        sys.exit(f"pairs.json not found at {args.pairs} "
                 f"(use --pairs to point somewhere else)")
    pairs = load_pairs(args.pairs)
    state = load_state(args.state)

    if args.json:
        out = {}
        for token, (name, pair_id) in pairs.items():
            if args.pair and pair_id != args.pair:
                continue
            out.setdefault(pair_id, {})[name] = state.get(token, [])
        print(json.dumps(out, indent=2, ensure_ascii=False))
        return

    if not args.clear:
        report(pairs, state, args.pair)
        return

    if not args.i_stopped_the_relay:
        sys.exit(
            "Refusing to clear while the relay may still be running.\n"
            "A live relay keeps the queues in memory and would overwrite\n"
            "this file on the next message, so clearing it would do\n"
            "nothing. Stop it first:\n\n"
            "    docker compose stop paniccall\n"
            f"    {sys.argv[0]} --clear --i-stopped-the-relay\n"
            "    docker compose start paniccall")

    total = report(pairs, state, args.pair)
    if total == 0 and not args.pair:
        print("Nothing to clear.")
        return

    if args.pair:
        # Keep other pairs' queues intact.
        drop = {t for t, (_, pid) in pairs.items() if pid == args.pair}
        remaining = {t: q for t, q in state.items() if t not in drop and q}
    else:
        remaining = {}

    if args.state.exists():
        backup = args.state.with_suffix(
            f".bak-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
        backup.write_text(args.state.read_text())
        print(f"\nBacked up to {backup}")

    # Same atomic write the relay uses, so an interrupted clear cannot
    # leave a half-written file for the relay to choke on at startup.
    tmp = args.state.with_suffix(".tmp")
    tmp.write_text(json.dumps(remaining))
    tmp.replace(args.state)
    print(f"Cleared. {args.state} now holds "
          f"{sum(len(q) for q in remaining.values())} message(s).")
    print("Start the relay again: docker compose start paniccall")


if __name__ == "__main__":
    main()

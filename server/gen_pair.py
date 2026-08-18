#!/usr/bin/env python3
"""Generate a new pair for pairs.json.

Usage: python3 gen_pair.py <pair_id> <naam_A> <naam_B>
Prints a JSON snippet you can paste into pairs.json, plus the two
tokens to put on the two phones.
"""
import json
import secrets
import sys

if len(sys.argv) != 4:
    sys.exit(__doc__)

pair_id, name_a, name_b = sys.argv[1:4]
tok_a = secrets.token_hex(32)
tok_b = secrets.token_hex(32)

snippet = {"id": pair_id, "members": {tok_a: name_a, tok_b: name_b}}
print(json.dumps(snippet, indent=2, ensure_ascii=False))
print(f"\nToken for {name_a}'s device:\n  {tok_a}")
print(f"\nToken for {name_b}'s device:\n  {tok_b}")

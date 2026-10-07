"""Live read-only provider smoke on this Mac: counts, statuses, model IDs. Prints no transcript text.

Usage: cd server && .venv/bin/python ../tools/providers_live_smoke.py
"""
import collections
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "server"))
from agentdeck import providers  # noqa: E402

t = time.monotonic()
s = providers.discover_sessions()
t1 = time.monotonic() - t
top = [x for x in s if not x.get("isSubagent")]
kids = [x for x in s if x.get("isSubagent")]
print(f"discover_sessions: {len(s)} rows in {t1:.2f}s ({len(top)} top-level, {len(kids)} subagents)")
print("top-level by agent/status/evidence:", dict(collections.Counter((x["agent"], x["status"], x["statusEvidence"]) for x in top)))
print("subagents by agent/status/evidence:", dict(collections.Counter((x["agent"], x["status"], x["statusEvidence"]) for x in kids)))
parents = {x["id"] for x in top}
print("subagents whose parent is listed:", sum(1 for k in kids if k["parentSessionId"] in parents or k["parentSessionId"].startswith("claude-sub:")), "/", len(kids))
print("live sessions:", [(x["agent"], x["nativeId"][:8], x["status"]) for x in top if x.get("live")])
t = time.monotonic()
m = providers.discover_models(refresh=True)
print(f"discover_models: {time.monotonic() - t:.1f}s")
for a in m["agents"]:
    print(f"  {a['id']}: version={a['version']!r} source={a['modelSource']} error={a['error']}")
    print("    ids:", [x["id"] for x in a["models"]])
    if a["id"] == "claude":
        print("    resolved:", [x.get("resolvedModel") for x in a["models"]])

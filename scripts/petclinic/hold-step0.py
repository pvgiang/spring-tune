#!/usr/bin/env python3
"""Step 0 of docs/benchmarks/petclinic-hold.md: what the committed 2026-11-09 polls can and cannot say.

    python3 -I scripts/petclinic/hold-step0.py [runs-dir]

Prints (1) every field found in the polls' Hikari signals and recommendation evidence, and (2) per scenario and run
the acquire/hold mean ratio (meanAcquireTime / meanConnectionHoldTime, from the recommendation evidence) over the
evaluated polls (generatedAt between driver start + 360 s and start + 450 s, the rule used in petclinic.md).
"""
import json
import os
import re
import sys
from datetime import datetime, timezone

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "benchmarks", "petclinic", "2026-11-09-runs")


def ts(s):
    """Whole seconds, fraction dropped, exactly as scripts/petclinic/evaluate.sh does."""
    m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(\.\d+)?Z$", s)
    return datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()


def ms(v):
    m = re.match(r"^([0-9.]+) ms$", v)
    if m:
        return float(m.group(1))
    m = re.match(r"^([0-9.]+) s$", v)
    return float(m.group(1)) * 1000 if m else None


def rng(xs, f="%.0f"):
    return (f % min(xs) + ".." + f % max(xs)) if xs else "n/a"


def main():
    signal_keys, evidence_keys = set(), set()
    print("scenario\trun\tevaluated_polls\tpolls_with_both_means\tmeanHold_ms\tmeanAcquire_ms\tacquire/hold")
    for s in ("s1", "s2", "s3", "s4"):
        allr = []
        for n in (1, 2, 3):
            d = os.path.join(ROOT, s, f"run-{n}")
            drv = json.load(open(os.path.join(d, "driver-summary.json")))
            st = ts(drv["started_at"])
            end = st + drv["duration_seconds"]
            ev = []
            for line in open(os.path.join(d, "polls.jsonl")):
                p = json.loads(line)
                for c in p.get("components", []):
                    if c["id"].startswith("hikari"):
                        signal_keys.update(c.get("signals", {}).keys())
                for r in p.get("recommendations", []):
                    evidence_keys.update(r.get("evidence", {}).keys())
                if p.get("generatedAt") and st + 360 <= ts(p["generatedAt"]) <= end:
                    ev.append(p)
            holds, acqs, ratios = [], [], []
            for p in ev:
                for r in p["recommendations"]:
                    e = r["evidence"]
                    if "meanConnectionHoldTime" in e and "meanAcquireTime" in e:
                        h, a = ms(e["meanConnectionHoldTime"]), ms(e["meanAcquireTime"])
                        holds.append(h)
                        acqs.append(a)
                        if h:
                            ratios.append(a / h)
            allr += ratios
            print(f"{s}\t{n}\t{len(ev)}\t{len(holds)}\t{rng(holds)}\t{rng(acqs)}\t{rng(ratios, '%.1f')}")
        if allr:
            print(f"{s}\tall\t\t\t\t\t{min(allr):.1f}..{max(allr):.1f}")
    print()
    print("Hikari signal fields in polls: " + ", ".join(sorted(signal_keys)))
    print("Recommendation evidence fields in polls: " + ", ".join(sorted(evidence_keys)))


if __name__ == "__main__":
    main()

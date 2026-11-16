#!/usr/bin/env python3
"""Offline evaluation of the pre-registered hold candidates (docs/benchmarks/petclinic-hold.md, step 3).

    python3 -I scripts/petclinic/evaluate-hold.py [runs-dir] [--polls]

Reads <runs-dir>/{s1,s2,s3,s3p,s4,s5}/run-{1,2,3}/{polls.jsonl,driver-summary.json} (default: the 2026-11-15 runs)
and prints, as Markdown: per run, the evaluated polls and what the unchanged rule, P_max and P_share give in them;
then the bar per candidate. --polls also prints every evaluated poll with the candidates' inputs.

Every definition below is the one fixed in the pre-registration; do not change it after the runs.
"""
import json
import os
import re
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT = os.path.join(HERE, "..", "..", "docs", "benchmarks", "petclinic", "2026-11-15-runs")
SCENARIOS = [("S1", "s1"), ("S2", "s2"), ("S3", "s3"), ("S3'", "s3p"), ("S4", "s4"), ("S5", "s5")]
RUNS = (1, 2, 3)

EVAL_FROM_S = 360        # evaluated polls: generatedAt in [start + 360 s, start + duration]
WINDOW_S = 300           # candidate window: polls in [t - 300 s, t]
MIN_WINDOW_POLLS = 3     # fewer: fall back to the unchanged rule
SLOW_USAGE_S = 0.5       # springtune.hikari.slow-usage
P_MAX_RATIO = 0.80       # P_max: share of waiting polls with usage MAX > slow-usage
P_SHARE_THRESHOLD = 0.02 # P_share: share of checkouts > 500 ms

RAISE = "ST-REC-HIKARI-001"
HOLD_SLOW = "ST-REC-HIKARI-002"


def ts(s):
    """Whole seconds, fraction dropped (as scripts/petclinic/evaluate.sh)."""
    m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(\.\d+)?Z$", s)
    return datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()


def rec_str(r):
    return f"{r['ruleId']} {r['action']} {r['currentValue']}->{r['recommendedValue']}"


def hikari(poll):
    for c in poll.get("components", []):
        if c["id"].startswith("hikari"):
            return c
    return None


def waiting(poll):
    c = hikari(poll)
    return c is not None and (c.get("signals", {}).get("waitingThreads") or 0) > 0


def usage_max(poll):
    h = poll.get("hold") or {}
    u = h.get("usage")
    return u.get("MAX") if isinstance(u, dict) else None


def buckets(poll):
    h = poll.get("hold") or {}
    return h.get("usage_le_500ms"), h.get("usage_le_1h")


def is_increase(recs):
    return any(r["ruleId"] == RAISE and r["action"] == "INCREASE" for r in recs)


def hold_instead(recs, why):
    out = []
    for r in recs:
        if r["ruleId"] == RAISE and r["action"] == "INCREASE":
            out.append({"ruleId": HOLD_SLOW, "action": "HOLD", "currentValue": r["currentValue"],
                        "recommendedValue": r["currentValue"], "why": why})
        else:
            out.append(r)
    return out


def p_max(recs, window):
    if not is_increase(recs):
        return recs, "n/a (no INCREASE)"
    if len(window) < MIN_WINDOW_POLLS or any(usage_max(q) is None for q in window):
        return recs, "fallback"
    w = [q for q in window if waiting(q)]
    if not w:
        return recs, "no waiting poll"
    share = sum(1 for q in w if usage_max(q) > SLOW_USAGE_S) / len(w)
    info = f"{share:.0%} of {len(w)}"
    return (hold_instead(recs, "P_max " + info), info) if share >= P_MAX_RATIO else (recs, info)


def p_share(recs, window):
    if not is_increase(recs):
        return recs, "n/a (no INCREASE)"
    if len(window) < MIN_WINDOW_POLLS or any(None in buckets(q) for q in window):
        return recs, "fallback"
    total = sum(buckets(q)[1] for q in window)
    if total <= 0:
        return recs, "fallback (0 checkouts)"
    share = sum(buckets(q)[1] - buckets(q)[0] for q in window) / total
    info = f"{share:.2%}"
    return (hold_instead(recs, "P_share " + info), info) if share >= P_SHARE_THRESHOLD else (recs, info)


def load_run(root, d, n):
    path = os.path.join(root, d, f"run-{n}")
    pf, df = os.path.join(path, "polls.jsonl"), os.path.join(path, "driver-summary.json")
    if not (os.path.exists(pf) and os.path.exists(df)):
        return None
    drv = json.load(open(df))
    polls = [json.loads(line) for line in open(pf) if line.strip()]
    st = ts(drv["started_at"])
    end = st + drv["duration_seconds"]
    ok = [p for p in polls if p.get("generatedAt") and not p.get("error")]
    evaluated = []
    for p in polls:
        if p.get("error") or not p.get("generatedAt"):
            # A failed read has no generatedAt; it is placed by its slot on the wall-clock schedule (load start
            # + elapsed_seconds) and, when evaluated, counts as "no recommendation" (pre-registration).
            if EVAL_FROM_S <= p.get("elapsed_seconds", -1) <= drv["duration_seconds"]:
                evaluated.append({"at": "error-at-slot-%ss" % p["elapsed_seconds"], "base": [], "p_max": [],
                                  "p_max_info": "read failed", "p_share": [], "p_share_info": "read failed",
                                  "window": 0, "max": None, "buckets": (None, None), "waiting": False})
            continue
        t = ts(p["generatedAt"])
        if st + EVAL_FROM_S <= t <= end:
            window = [q for q in ok if t - WINDOW_S <= ts(q["generatedAt"]) <= t]
            base = p.get("recommendations", [])
            pm, pm_info = p_max(base, window)
            ps, ps_info = p_share(base, window)
            evaluated.append({"at": p["generatedAt"], "base": base, "p_max": pm, "p_max_info": pm_info,
                              "p_share": ps, "p_share_info": ps_info, "window": len(window),
                              "max": usage_max(p), "buckets": buckets(p), "waiting": waiting(p)})
    errors = sum(1 for p in polls if p.get("error"))
    return {"evaluated": evaluated, "errors": errors, "polls": len(polls), "seed": drv["seed"],
            "skipped": drv.get("skipped_in_flight_cap"), "slow_edits": drv.get("slow_edits")}


def fmt(recs):
    return "; ".join(sorted({rec_str(r) for r in recs})) or "none"


def run_ok(scenario, run, key):
    """Bar item for one run and one output key (base, p_max or p_share)."""
    if run is None or not run["evaluated"]:
        return False
    ev = run["evaluated"]
    if scenario in ("S3", "S3'"):
        return not any(any(r["action"] == "INCREASE" for r in e[key]) for e in ev)
    if scenario in ("S2", "S5"):
        return all(any(rec_str(r) == f"{RAISE} INCREASE 2->3" for r in e[key]) for e in ev)
    if scenario == "S1":
        return all(not e[key] for e in ev)
    if scenario == "S4":
        return all(fmt(e[key]) == fmt(e["base"]) for e in ev)
    raise ValueError(scenario)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    show_polls = "--polls" in sys.argv
    root = args[0] if args else DEFAULT
    data = {(s, n): load_run(root, d, n) for s, d in SCENARIOS for n in RUNS}

    print("| Scenario | Run | Seed | Evaluated polls | Unchanged rule | P_max | P_max inputs | P_share | P_share inputs |")
    print("|---|---|---|---|---|---|---|---|---|")
    for s, _ in SCENARIOS:
        for n in RUNS:
            r = data[(s, n)]
            if r is None:
                print(f"| {s} | {n} | | missing | | | | | |")
                continue
            ev = r["evaluated"]
            print(f"| {s} | {n} | {r['seed']} | {len(ev)} | {fmt([x for e in ev for x in e['base']])} "
                  f"| {fmt([x for e in ev for x in e['p_max']])} | {', '.join(e['p_max_info'] for e in ev)} "
                  f"| {fmt([x for e in ev for x in e['p_share']])} | {', '.join(e['p_share_info'] for e in ev)} |")
    print()
    print("| Bar item | Unchanged rule | P_max | P_share |")
    print("|---|---|---|---|")
    verdict = {"base": True, "p_max": True, "p_share": True}
    items = [("S3: no INCREASE, 3 of 3", "S3"), ("S3': no INCREASE, 3 of 3", "S3'"),
             ("S2: INCREASE 2->3 in every evaluated poll, 3 of 3", "S2"),
             ("S5: INCREASE 2->3 in every evaluated poll, 3 of 3", "S5"),
             ("S1: no recommendation, 3 of 3", "S1"), ("S4: unchanged, 3 of 3", "S4")]
    for label, s in items:
        cells = []
        for key in ("base", "p_max", "p_share"):
            oks = [run_ok(s, data[(s, n)], key) for n in RUNS]
            passed = all(oks)
            if key != "base" or s != "S4":
                verdict[key] &= passed
            cells.append(f"{'pass' if passed else 'FAIL'} ({sum(oks)} of 3)")
        print(f"| {label} | " + " | ".join(cells) + " |")
    print(f"| **Scenario part of the bar** | {'pass' if verdict['base'] else 'FAIL'} "
          f"| {'pass' if verdict['p_max'] else 'FAIL'} | {'pass' if verdict['p_share'] else 'FAIL'} |")
    print()
    print("(The tests part of the bar applies only to a candidate that is implemented; the unchanged rule's S4 "
          "item is trivially 'unchanged'.)")

    # Supplementary, not part of the bar (added after the first runs, see the results): are the two bucket gauges
    # consistent with each other and with MAX? Over every poll (not only evaluated ones) with both buckets and MAX.
    print()
    print("| Scenario | Polls with buckets | le 0.5 > le 1h (impossible in one snapshot) | le 1h > le 0.5 while usage MAX <= 0.5 s |")
    print("|---|---|---|---|")
    for s, d in SCENARIOS:
        tot = neg = phantom = 0
        for n in RUNS:
            path = os.path.join(root, d, f"run-{n}", "polls.jsonl")
            if not os.path.exists(path) or data[(s, n)] is None:
                continue
            for line in open(path):
                p = json.loads(line)
                b05, b1h = buckets(p)
                mx = usage_max(p)
                if b05 is None or b1h is None or mx is None:
                    continue
                tot += 1
                neg += b05 > b1h
                phantom += (b1h > b05 and mx <= SLOW_USAGE_S)
        print(f"| {s} | {tot} | {neg} | {phantom} |")

    if show_polls:
        print()
        print("| Scenario | Run | generatedAt | window polls | waiting | usage MAX s | le 0.5 / le 1h | unchanged "
              "| P_max | P_share |")
        print("|---|---|---|---|---|---|---|---|---|---|")
        for s, _ in SCENARIOS:
            for n in RUNS:
                r = data[(s, n)]
                for e in (r["evaluated"] if r else []):
                    b = e["buckets"]
                    print(f"| {s} | {n} | {e['at'][11:19]} | {e['window']} | {'yes' if e['waiting'] else 'no'} "
                          f"| {e['max']} | {b[0]} / {b[1]} | {fmt(e['base'])} | {fmt(e['p_max'])} "
                          f"({e['p_max_info']}) | {fmt(e['p_share'])} ({e['p_share_info']}) |")


if __name__ == "__main__":
    main()

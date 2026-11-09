"""Compares two corpus runs finding by finding.

usage: python3 -I scripts/corpus/diff-runs.py <old-out-dir> <new-out-dir>

A finding's identity is (ruleId, severity, module, subject, location). The script prints, per repository,
findings only in the old run, findings only in the new run, findings whose message/why/fix text changed,
and changes in modules and "not evaluated" diagnostics. Exit code 0 when the finding sets are identical,
1 otherwise (text-only changes do not fail).
"""
import collections
import json
import pathlib
import sys

old_dir, new_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])


def key(f):
    loc = f.get("location") or {}
    return (f["ruleId"], f["severity"], f["module"], f.get("subject"),
            loc.get("path"), loc.get("line"), loc.get("column"))


def canon(items):
    return collections.Counter(json.dumps(x, sort_keys=True) for x in items)


totals = collections.Counter()
per_rule = collections.defaultdict(lambda: collections.Counter())
for old_file in sorted(old_dir.glob("*.json")):
    new_file = new_dir / old_file.name
    if not new_file.exists():
        print(f"{old_file.stem}: missing in new run")
        totals["missing-report"] += 1
        continue
    old, new = json.loads(old_file.read_text()), json.loads(new_file.read_text())
    lines = []
    old_f = {key(f): f for f in old["findings"] if not f["suppressed"]}
    new_f = {key(f): f for f in new["findings"] if not f["suppressed"]}
    for k in old_f.keys() - new_f.keys():
        lines.append(f"  - removed {k}")
        totals["removed"] += 1
        per_rule[k[0]]["removed"] += 1
    for k in new_f.keys() - old_f.keys():
        lines.append(f"  + added   {k}")
        totals["added"] += 1
        per_rule[k[0]]["added"] += 1
    for k in old_f.keys() & new_f.keys():
        per_rule[k[0]]["same"] += 1
        a, b = old_f[k], new_f[k]
        for field in ("message", "why", "fix"):
            if a.get(field) != b.get(field):
                totals[f"{field}-changed"] += 1
                lines.append(f"  ~ {field} {k[0]} {k[2]}\n      old: {a.get(field)}\n      new: {b.get(field)}")
    for part in ("modules", "notEvaluated"):
        a, b = canon(old[part]), canon(new[part])
        if a != b:
            totals[f"{part}-changed"] += 1
            lines.append(f"  ~ {part}: removed {list((a - b).elements())} added {list((b - a).elements())}")
    print(f"{old_file.stem}: {old.get('version')} -> {new.get('version')}"
          + ("" if lines else ", identical findings, modules and diagnostics"))
    for line in lines:
        print(line)

print()
print("| Rule | Same | Removed | Added |")
print("|---|---:|---:|---:|")
for rule in sorted(per_rule):
    c = per_rule[rule]
    print(f"| {rule} | {c['same']} | {c['removed']} | {c['added']} |")
print()
print("Totals:", dict(totals) or "no differences")
sys.exit(1 if totals["removed"] or totals["added"] or totals["missing-report"] else 0)

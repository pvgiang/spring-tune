"""Aggregates corpus reports. usage: python3 -I scripts/corpus/summarize.py <out-dir>"""
import collections
import json
import pathlib
import sys

out = pathlib.Path(sys.argv[1])
shas = dict(line.split("\t") for line in (out / "shas.tsv").read_text().split("\n") if line)
codes = dict(line.split("\t") for line in (out / "exit-codes.tsv").read_text().split("\n") if line)
by_rule = collections.Counter()
repos_by_rule = collections.defaultdict(set)
diag_codes = collections.Counter()
rows = []
for slug in shas:
    report_file = out / (slug.replace("/", "__") + ".json")
    if not report_file.exists():
        rows.append((slug, shas[slug][:10], codes.get(slug, "?"), "-", "-", "-", "-", "-"))
        continue
    report = json.loads(report_file.read_text())
    modules = report["modules"]
    findings = [f for f in report["findings"] if not f["suppressed"]]
    for f in findings:
        by_rule[f["ruleId"]] += 1
        repos_by_rule[f["ruleId"]].add(slug)
    for d in report["notEvaluated"]:
        diag_codes[d["code"]] += 1
    boot = sorted({m["springBootVersion"] or "?" for m in modules})
    s = report["summary"]
    rows.append((slug, shas[slug][:10], codes.get(slug, "?"), len(modules), ",".join(boot)[:24],
                 s["error"], s["warning"], s["info"]))

print("| Repository | Commit | Exit | Modules | Boot | Error | Warning | Info |")
print("|---|---|---|---:|---|---:|---:|---:|")
for r in rows:
    print("| " + " | ".join(str(c) for c in r) + " |")
print()
print("| Rule | Findings | Repos |")
print("|---|---:|---:|")
for rule, count in sorted(by_rule.items()):
    print(f"| {rule} | {count} | {len(repos_by_rule[rule])} |")
print()
print("Not evaluated:", dict(diag_codes))

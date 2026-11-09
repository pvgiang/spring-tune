"""Breaks down the HikariCP and HTTP-client findings of a corpus run, for the numbers launch content cites.

usage: python3 -I scripts/corpus/breakdown.py <out-dir>
"""
import collections
import json
import pathlib
import sys

out = pathlib.Path(sys.argv[1])
hikari_modules = evaluated = 0
not_evaluated = collections.Counter()
timeout = collections.Counter()
leak_off = 0
http = collections.Counter()
http_repos = set()
for report_file in sorted(out.glob("*.json")):
    report = json.loads(report_file.read_text())
    findings = [f for f in report["findings"] if not f["suppressed"]]
    h1 = {f["module"]: f for f in findings if f["ruleId"] == "ST-HIKARI-001"}
    h2 = {f["module"] for f in findings if f["ruleId"] == "ST-HIKARI-002"}
    for m in report["modules"]:
        if "hikari" not in m["technologies"]:
            continue
        hikari_modules += 1
        if m["path"] in h1:
            evaluated += 1
            msg = h1[m["path"]]["message"]
            if "not set" in msg:
                timeout["unset (HikariCP default 30000 ms)"] += 1
            else:
                timeout["set explicitly: " + msg.split(",")[0].replace("connectionTimeout is ", "")] += 1
            leak_off += m["path"] in h2
            continue
        reasons = [d["message"] for d in report["notEvaluated"] if d["module"] == m["path"]]
        if any("builds its own DataSource" in r for r in reasons):
            not_evaluated["pool built in code (custom prefix or programmatic)"] += 1
        elif any("Local config is incomplete" in r for r in reasons):
            not_evaluated["config loaded from outside the repository (Config Server, Nacos)"] += 1
        else:
            not_evaluated["other / not flagged: " + "; ".join(r[:60] for r in reasons)] += 1
    for f in findings:
        if f["ruleId"] == "ST-HTTP-001":
            http[f["message"].split(" uses ")[0].split(" sets ")[0]] += 1
            http_repos.add(report_file.stem)

print(f"HikariCP modules detected: {hikari_modules}")
print(f"  evaluated and flagged by ST-HIKARI-001: {evaluated}")
for k, v in timeout.most_common():
    print(f"    connectionTimeout {k}: {v}")
print(f"  of those, leak detection off (ST-HIKARI-002): {leak_off}")
for k, v in not_evaluated.most_common():
    print(f"  not evaluated, {k}: {v}")
print(f"ST-HTTP-001 findings: {sum(http.values())} in {len(http_repos)} repositories")
for k, v in http.most_common():
    print(f"  {k}: {v}")

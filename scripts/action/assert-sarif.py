#!/usr/bin/env python3
"""Asserts that a SpringTune SARIF report contains the expected findings, each with a line location.

    scripts/action/assert-sarif.py [--tool-version X.Y.Z] [--exact] REPORT.sarif RULE=PATH:LINE [RULE=PATH:LINE ...]

Each expectation needs at least one result with that ruleId whose first physicalLocation has artifactLocation.uri
equal to PATH and region.startLine equal to LINE (LINE "*" accepts any line >= 1). Results that match no
expectation are listed and allowed, unless --exact is given: then every result must match one, so a new or moved
finding fails too. With --tool-version, the report must come from that SpringTune version
(runs[].tool.driver.version). Exit 0 when every expectation holds, 1 otherwise. Used by the demo job in ci.yml.
"""
import json
import sys


def main(argv):
    tool_version = None
    exact = False
    while len(argv) > 1 and argv[1].startswith("--"):
        if argv[1] == "--tool-version" and len(argv) > 2:
            tool_version = argv[2]
            argv = argv[:1] + argv[3:]
        elif argv[1] == "--exact":
            exact = True
            argv = argv[:1] + argv[2:]
        else:
            print(__doc__.strip(), file=sys.stderr)
            return 2
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8") as f:
        sarif = json.load(f)
    if tool_version is not None:
        versions = {run.get("tool", {}).get("driver", {}).get("version") for run in sarif.get("runs", [])}
        if versions != {tool_version}:
            print(f"::error::SARIF report was written by SpringTune {sorted(map(str, versions))}, expected {tool_version}")
            return 1
        print(f"tool: SpringTune {tool_version}")
    results = [r for run in sarif.get("runs", []) for r in run.get("results", [])]
    found = []
    for r in results:
        locs = r.get("locations") or [{}]
        phys = locs[0].get("physicalLocation", {})
        uri = phys.get("artifactLocation", {}).get("uri")
        line = phys.get("region", {}).get("startLine")
        found.append((r.get("ruleId"), uri, line, r.get("level")))
        print(f"found: {r.get('ruleId')} {r.get('level')} {uri}:{line}")

    problems = []
    expectations = []
    for spec in argv[2:]:
        rule, _, where = spec.partition("=")
        path, _, line = where.rpartition(":")
        if not rule or not path or not line or not (line == "*" or line.isdigit()):
            print(f"bad expectation {spec!r}, want RULE=PATH:LINE", file=sys.stderr)
            return 2
        expectations.append((rule, path, line))

    def matches(finding, expectation):
        rid, uri, ln, _ = finding
        rule, path, line = expectation
        return rid == rule and uri == path and isinstance(ln, int) and ln >= 1 and (line == "*" or ln == int(line))

    for spec, expectation in zip(argv[2:], expectations):
        ok = any(matches(f, expectation) for f in found)
        rule, path, line = expectation
        print(f"{'OK  ' if ok else 'MISS'} {rule} at {path}:{line}")
        if not ok:
            problems.append(spec)

    unexpected = [f for f in found if not any(matches(f, e) for e in expectations)] if exact else []
    for rid, uri, ln, level in unexpected:
        print(f"UNEXPECTED {rid} {level} at {uri}:{ln}")

    if problems:
        print(f"::error::SARIF report is missing expected findings: {', '.join(problems)}")
        return 1
    if unexpected:
        print(f"::error::SARIF report has {len(unexpected)} finding(s) not in the expected list (--exact)")
        return 1
    print(f"All {len(argv) - 2} expected findings present with line locations")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

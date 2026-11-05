#!/usr/bin/env python3
"""Structural check of docs/grafana/springtune-dashboard.json (no Grafana needed).

    python3 scripts/check-grafana-dashboard.py [dashboard.json] [--rules out.yml]

Checks: valid JSON, sane schemaVersion, datasource/application/instance variables, unique panel ids, panels inside
the 24-column grid without overlaps, every query on the templated datasource, balanced PromQL, every $variable
defined, and the metric names the docs promise. --rules also writes every query as a Prometheus recording rule with
Grafana's variables substituted, for 'promtool check rules out.yml' (a real PromQL parse).
"""
import json
import re
import sys

REQUIRED_METRICS = [
    "hikaricp_connections_active", "hikaricp_connections_idle", "hikaricp_connections_pending",
    "hikaricp_connections_max", "hikaricp_connections_timeout_total",
    "hikaricp_connections_acquire_seconds_sum", "hikaricp_connections_acquire_seconds_count",
    "hikaricp_connections_usage_seconds_sum", "hikaricp_connections_usage_seconds_count",
    "executor_active_threads", "executor_queued_tasks", "executor_pool_size_threads",
    "springtune_recommendations", "springtune_changes_total",
    "springtune_limiter_limit", "springtune_limiter_inflight", "springtune_limiter_requests_total",
]
GRAFANA_BUILTINS = {"__rate_interval", "__interval", "__range", "__range_s", "__all"}


def fail(errors, msg):
    errors.append(msg)


def balanced(expr):
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    in_str = False
    for ch in expr:
        if ch == '"':
            in_str = not in_str
        elif in_str:
            continue
        elif ch in "([{":
            stack.append(ch)
        elif ch in ")]}":
            if not stack or stack.pop() != pairs[ch]:
                return False
    return not stack and not in_str


def main(argv):
    path = "docs/grafana/springtune-dashboard.json"
    rules_out = None
    args = list(argv)
    if "--rules" in args:
        i = args.index("--rules")
        rules_out = args[i + 1]
        del args[i:i + 2]
    if args:
        path = args[0]

    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    errors = []

    for key in ("title", "uid", "schemaVersion", "panels", "templating", "time"):
        if key not in d:
            fail(errors, f"missing top-level key '{key}'")
    if not (30 <= d.get("schemaVersion", 0) <= 41):
        fail(errors, f"schemaVersion {d.get('schemaVersion')} outside 30..41 (Grafana 9-12)")

    variables = {v["name"]: v for v in d.get("templating", {}).get("list", [])}
    ds = variables.get("datasource")
    if not ds or ds.get("type") != "datasource" or ds.get("query") != "prometheus":
        fail(errors, "templated Prometheus datasource variable 'datasource' missing")
    for name in ("application", "instance"):
        if name not in variables:
            fail(errors, f"variable '{name}' missing")

    ids = set()
    cells = {}
    exprs = []
    for p in d.get("panels", []):
        pid = p.get("id")
        if pid in ids:
            fail(errors, f"duplicate panel id {pid}")
        ids.add(pid)
        g = p.get("gridPos", {})
        if g.get("x", 0) + g.get("w", 0) > 24 or g.get("w", 0) <= 0 or g.get("h", 0) <= 0:
            fail(errors, f"panel {pid} '{p.get('title')}' gridPos out of the 24-column grid: {g}")
        for x in range(g.get("x", 0), g.get("x", 0) + g.get("w", 0)):
            for y in range(g.get("y", 0), g.get("y", 0) + g.get("h", 0)):
                if (x, y) in cells:
                    fail(errors, f"panel {pid} overlaps panel {cells[(x, y)]} at x={x} y={y}")
                    break
                cells[(x, y)] = pid
        if p.get("type") == "row":
            continue
        if not p.get("title"):
            fail(errors, f"panel {pid} has no title")
        if p.get("datasource", {}).get("uid") != "${datasource}":
            fail(errors, f"panel {pid} '{p.get('title')}' is not on the templated datasource")
        if not p.get("targets"):
            fail(errors, f"panel {pid} '{p.get('title')}' has no queries")
        for t in p.get("targets", []):
            if t.get("datasource", {}).get("uid") != "${datasource}":
                fail(errors, f"panel {pid} query {t.get('refId')} is not on the templated datasource")
            exprs.append((pid, p.get("title"), t.get("expr", "")))

    var_queries = [(v["name"], v.get("definition", "")) for v in variables.values() if v.get("type") == "query"]
    for where, expr in [(f"panel {pid} '{title}'", e) for pid, title, e in exprs] + \
                       [(f"variable '{n}'", e) for n, e in var_queries]:
        if not expr.strip():
            fail(errors, f"{where}: empty query")
        if not balanced(expr):
            fail(errors, f"{where}: unbalanced brackets or quotes: {expr}")
        for ref in re.findall(r"\$\{?(\w+)", expr):
            if ref not in variables and ref not in GRAFANA_BUILTINS:
                fail(errors, f"{where}: undefined variable ${ref}")

    all_text = " ".join(e for _, _, e in exprs)
    for metric in REQUIRED_METRICS:
        if not re.search(rf"\b{metric}\b", all_text):
            fail(errors, f"no panel queries {metric}")

    if rules_out:
        def substitute(expr):
            expr = expr.replace("$__rate_interval", "5m").replace("$__range", "1h").replace("$__interval", "1m")
            return re.sub(r"\$\{?\w+\}?", ".*", expr)
        with open(rules_out, "w", encoding="utf-8") as f:
            f.write("groups:\n  - name: springtune-dashboard\n    rules:\n")
            for i, (_, _, e) in enumerate(exprs):
                f.write(f"      - record: springtune_dashboard_q{i}\n        expr: {json.dumps(substitute(e))}\n")

    for e in errors:
        print("ERROR:", e)
    panels = [p for p in d.get("panels", []) if p.get("type") != "row"]
    print(f"{path}: schemaVersion {d.get('schemaVersion')}, {len(panels)} panels, {len(exprs)} queries, "
          f"{len(variables)} variables, {len(errors)} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

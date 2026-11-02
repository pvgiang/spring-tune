# `springtune.yml` reference

Optional. It lives at the analysis root (or pass `--config`). Every field is optional. The file is
validated: unknown rule IDs, undeclared databases and suppressions without a reason fail with exit code 2,
listing every problem.

```yaml
failOn: ERROR                 # ERROR | WARNING | INFO | NONE (CLI --fail-on wins)
profiles: [prod]              # profiles to analyze when none are given on the command line

databases:                    # inputs for ST-HIKARI-003 and ST-HIKARI-004
  - name: shop-db
    maxConnections: 100       # required: the database's max_connections
    idleTimeout: 10m          # optional: server/proxy idle timeout (Spring Duration syntax)
    safetyFactor: 0.8         # optional, (0, 1]: share of max_connections pools may use

services:                     # per-module deployment facts; path is relative to the root ("." = root)
  - path: orders
    database: shop-db         # optional when exactly one database is declared
    deployment: [orders/chart/values.yaml]   # Helm values or k8s manifests to read replicas from
  - path: payments
    database: shop-db
    maxReplicas: 4            # explicit value wins over deployment files

rules:
  ST-HIKARI-001:
    severity: ERROR           # override severity of every finding of this rule
    options:
      max-ms: 3000            # rule-specific options: see each rule's docs page
  ST-TX-001:
    enabled: false

suppress:
  - rule: ST-ASYNC-002
    module: legacy-batch      # optional: only this module
    subject: Jobs#pool:ThreadPoolTaskExecutor   # optional: only this property/symbol (see JSON report "subject")
    reason: "Bounded by the job scheduler; reviewed 2026-10"   # required
```

## Where replica counts come from (ST-HIKARI-004)

1. `services[].maxReplicas`
2. `services[].deployment` files: `HorizontalPodAutoscaler.spec.maxReplicas` (max over HPAs) wins over
   `Deployment`/`StatefulSet` `spec.replicas`. In Helm values, `autoscaling.maxReplicas` is used when
   `autoscaling.enabled: true`, else `replicaCount`.
3. Auto-discovery inside the module directory (`values.yaml`, or YAML under `k8s/`, `kubernetes/`,
   `deploy/`, `deployment/`, `manifests/`, `helm/`, `charts/`, `chart/`), used **only** when exactly one
   file yields a value. Otherwise the rule is skipped with an explanation. The check never assumes a replica
   count.

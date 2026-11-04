# Grafana dashboard

[`springtune-dashboard.json`](springtune-dashboard.json) shows the signals the runtime starter reasons about, next
to what SpringTune itself reports. Use it to check a recommendation before you accept it, and to watch a guarded
apply or a limiter in shadow mode.

| Row | Metrics (Prometheus names) |
|---|---|
| Overview | open recommendations, Hikari timeouts, reverted changes and limiter rejections over the selected range |
| HikariCP | `hikaricp_connections_active` / `_idle` / `_pending` / `_max`, mean acquire time and mean usage (hold) time from `hikaricp_connections_acquire_seconds` and `hikaricp_connections_usage_seconds` (`rate(_sum) / rate(_count)`), `hikaricp_connections_timeout_total` |
| Task executors | `executor_active_threads`, `executor_pool_size_threads`, `executor_pool_max_threads`, `executor_queued_tasks`, `executor_completed_tasks_total` |
| SpringTune | `springtune_recommendations`, `springtune_changes_total{outcome}` |
| Adaptive limiter | `springtune_limiter_limit`, `springtune_limiter_inflight`, `springtune_limiter_requests_total{outcome}` and the rejected-or-would-reject ratio |

Variables: `datasource` (any Prometheus datasource), `application`, `instance`, `pool` (Hikari pool name) and
`executor` (the `name` tag of executor metrics). All are multi-select with *All* as the default.

## What your application needs

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  endpoints.web.exposure.include: health,prometheus,tuning
  metrics.tags.application: ${spring.application.name}   # the dashboard's 'application' variable
```

- **`application` tag.** Spring Boot does not add one by default. Without it the variable is empty and *All*
  still matches every series, so the dashboard works, but you cannot pick one service.
- **`instance`** comes from your Prometheus scrape config (it is the scrape target by default).
- **Hikari metrics** appear when the `DataSource` is a `HikariDataSource` and Micrometer is present (Spring Boot
  wires `MicrometerMetricsTrackerFactory`). Acquire and usage times need it too; without them ST-REC-HIKARI-002
  reports LOW confidence.
- **Executor metrics** are registered by Spring Boot for every `ThreadPoolTaskExecutor` and
  `ThreadPoolTaskScheduler` bean, with the bean name as tag `name` (for example `applicationTaskExecutor`;
  checked against Spring Boot 3.5.16 and 4.1.2). An executor you create with `new` and never expose as a bean
  has no metrics.
- **`springtune_changes_total`** exists only after the first guarded-apply change has finished, and
  **`springtune_limiter_*`** only when `springtune-limiter` is on the classpath and enabled. Empty panels in those
  rows are expected otherwise.

## Import

1. Grafana → **Dashboards → New → Import**.
2. Upload `springtune-dashboard.json` (or paste its content) and click **Load**.
3. Pick your Prometheus datasource and **Import**. The dashboard UID is `springtune-overview`; importing again
   overwrites it.

Provisioning instead (Grafana 9+):

```yaml
# /etc/grafana/provisioning/dashboards/springtune.yml
apiVersion: 1
providers:
  - name: springtune
    type: file
    options:
      path: /var/lib/grafana/dashboards/springtune   # put springtune-dashboard.json here
```

With provisioning the datasource variable defaults to Grafana's default datasource; pick another one in the
dashboard's top bar.

## Compatibility and how it was checked

`schemaVersion` 39 (Grafana 10.x). Grafana 10, 11 and 12 open it and migrate it if needed. It uses only the
built-in *Time series* and *Stat* panels.

It has **not** been loaded into a running Grafana or Prometheus yet (no container runtime where it was built).
[`scripts/check-grafana-dashboard.py`](../../scripts/check-grafana-dashboard.py) checks the structure: valid
JSON, the schema version, the variables, unique panel IDs, panels inside the 24-column grid without overlaps,
every query on the templated datasource, balanced PromQL, no undefined `$variables`, and every metric listed above.
Run it after editing:

```bash
python3 scripts/check-grafana-dashboard.py docs/grafana/springtune-dashboard.json
# real PromQL parse, if you have promtool:
python3 scripts/check-grafana-dashboard.py docs/grafana/springtune-dashboard.json --rules /tmp/q.yml && promtool check rules /tmp/q.yml
```

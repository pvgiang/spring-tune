# Captured outputs shown in the README

Every output block in the [README](../../README.md) is copied from a file here.

| File | What | Produced by |
|---|---|---|
| `check-runs.txt` | The CLI on copies of `examples/broken-config-ci`: as shipped, after the fixes, a suppression, config in Config Server, the connection budget | [`scripts/readme/capture-check-runs.sh`](../../scripts/readme/capture-check-runs.sh) with `springtune-cli-0.4.1.jar` from the v0.4.1 GitHub release, verified against its `SHA256SUMS` |
| `build-plugins-0.4.1.txt` | `mvn -B verify` and `gradle check` with the README's plugin snippets on the same demo (project paths replaced by `<project>`) | [`scripts/readme/capture-build-plugin-runs.sh`](../../scripts/readme/capture-build-plugin-runs.sh) `0.4.1`, with 0.4.1 built locally from the `v0.4.1` tag (`versions:set -DnewVersion=0.4.1`, as the release job does) because 0.4.1 was not yet on Maven Central on 2026-11-13 |
| `runtime-0.4.1/*.json` | `GET /actuator/tuning` of the sample app: just started; 60 callers × 50 ms on a pool of 20; 60 × 2 s on a pool of 20; 40 × 50 ms on a pool of 8 | [`scripts/readme/capture-runtime-runs.sh`](../../scripts/readme/capture-runtime-runs.sh) with `JAR=` the sample built from the `v0.4.1` tag; window shortened to 1 minute |

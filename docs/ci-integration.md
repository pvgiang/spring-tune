# CI integration

## GitHub Actions with code scanning (SARIF)

Run from the repository root so SARIF paths match the repository:

```yaml
name: springtune
on: [pull_request]
permissions:
  contents: read
  security-events: write
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pvgiang/spring-tune@v0.1.0      # downloads the released CLI, needs no build
        id: springtune
        with:
          profile: prod                        # optional
          fail-on: ERROR                       # optional (default: springtune.yml failOn, else ERROR)
      - uses: github/codeql-action/upload-sarif@v3
        if: always()                           # upload even when the check fails the job
        with:
          sarif_file: ${{ steps.springtune.outputs.sarif }}
          category: springtune
```

Action inputs: `path`, `version`, `profile`, `fail-on`, `format`, `output`, `config`. Outputs: `sarif`,
`exit-code`. The Markdown report is added to the job summary.

Findings then appear as code scanning alerts on the exact `file:line`. Suppressed findings are uploaded with
`suppressions[].justification`, so the reason stays visible. `partialFingerprints` keep alerts stable when
lines move.

## Maven

```bash
mvn verify                                   # fails on ERROR by default
mvn verify -Dspringtune.failOn=WARNING -Dspringtune.profiles=prod
```

Reports are written to `target/springtune/springtune-report.{md,sarif}`.

## Gradle

```bash
./gradlew check                                       # runs springtuneCheck; fails on ERROR by default
./gradlew springtuneCheck -Pspringtune.failOn=WARNING -Pspringtune.profiles=prod
```

Reports are written to `build/springtune/springtune-report.{md,sarif}` in each project, with paths relative
to the root project. To upload them to code scanning, point `upload-sarif` at the directory:

```yaml
      - run: ./gradlew springtuneCheck
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: app/build/springtune/springtune-report.sarif
```

Setup: [Gradle plugin](check.md#gradle-plugin).

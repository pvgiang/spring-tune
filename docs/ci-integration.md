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
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - name: SpringTune
        run: |
          java -jar springtune-check-cli-all.jar check . --profile prod \
            --format text,sarif --output springtune-reports
      - uses: github/codeql-action/upload-sarif@v3
        if: always()            # upload even when the check fails the job
        with:
          sarif_file: springtune-reports/springtune-report.sarif
          category: springtune
```

Findings then appear as code scanning alerts on the exact `file:line`. Suppressed findings are uploaded with
`suppressions[].justification`, so the reason stays visible. `partialFingerprints` keep alerts stable when
lines move.

## Maven

```bash
mvn verify                                   # fails on ERROR by default
mvn verify -Dspringtune.failOn=WARNING -Dspringtune.profiles=prod
```

Reports are written to `target/springtune/springtune-report.{md,sarif}`.

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
      - uses: pvgiang/spring-tune@v0.4.1      # downloads the released CLI, needs no build
        id: springtune
        with:
          version: "0.4.1"                     # pass it: the v0.4.1 tag defaults to CLI 0.4.0
          profile: prod                        # optional
          fail-on: ERROR                       # optional (default: springtune.yml failOn, else ERROR)
      - uses: github/codeql-action/upload-sarif@v3
        if: always()                           # upload even when the check fails the job
        with:
          sarif_file: ${{ steps.springtune.outputs.sarif }}
          category: springtune
```

Action inputs: `path`, `version`, `profile`, `fail-on`, `format`, `output`, `config`, `jar`, `token`. Outputs: `sarif`,
`exit-code`. The Markdown report is added to the job summary. A copy-paste version of this workflow is
[examples/github-workflow.yml](../examples/github-workflow.yml), and [examples/broken-config-ci](../examples/broken-config-ci)
is a project it fails on.

**Verified download.** The Action downloads `springtune-cli-<version>.jar` and the release's `SHA256SUMS` (through
the GitHub REST API with the workflow's token, input `token`; set it to `""`, or give a token that cannot read
the release, and it falls back to anonymous `curl` over HTTPS, which needs this repository to be public; the token
is never passed to the CLI), and checks the jar's line with `sha256sum -c` ([`scripts/action/verify-sha256.sh`](../scripts/action/verify-sha256.sh)) before
running it. A mismatch, a missing `SHA256SUMS` or jar line, or a `version` that is not a release number (such as
`0.4.1`) stops the step with exit code 2 and the jar never runs. A jar passed through the `jar` input (your own build) is not verified. The verification
ships from the `v0.4.1` tag on. That tag's `action.yml` defaults to CLI 0.4.0 (the 0.4.1 jar did not exist when it
was written; see the [v0.4.1 notes](releases/v0.4.1.md)), so pass `version: "0.4.1"` as above. The `v0.4.0` tag's
Action defaults to CLI 0.1.0 and downloads the jar without checking it: move to `@v0.4.1`.

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

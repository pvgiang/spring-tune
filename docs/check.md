# The check (`springtune check`, `mvn springtune:check`)

## CLI

```
springtune check [ROOT] [-m DIR]... [-p PROFILE[,PROFILE]] [-c springtune.yml]
                 [--fail-on ERROR|WARNING|INFO|NONE] [-f text,markdown,json,sarif] [-o DIR] [-q]
springtune rules
```

| Option | Default | Meaning |
|---|---|---|
| `ROOT` | `.` | Project root. Report paths are relative to it, so run it from the repository root for SARIF. |
| `-m, --module` | discover | Analyze only these module directories. |
| `-p, --profile` | `springtune.yml` `profiles`, else each module's `spring.profiles.active`, else `default` | Spring profiles to activate. |
| `-c, --config` | `ROOT/springtune.yml` if present | Settings file ([reference](configuration.md)). |
| `--fail-on` | `springtune.yml` `failOn`, else `ERROR` | Exit 1 when an unsuppressed finding is at or above this level. |
| `-f, --format` | `text` | Text goes to stdout. Other formats are written to `--output` as `springtune-report.{md,json,sarif}`. |
| `-o, --output` | `springtune-reports` | Report directory. |

Exit codes: `0` no blocking findings, `1` blocking findings, `2` invalid usage or configuration (unknown rule
ID, suppression without reason, missing files, and so on).

## Maven plugin

`check` binds to `verify` and analyzes the current module. Properties: `springtune.profiles`,
`springtune.failOn`, `springtune.formats` (default `markdown,sarif`), `springtune.outputDirectory` (default
`target/springtune`), `springtune.configFile`, `springtune.rootDirectory` (default: the multi-module root,
where `springtune.yml` is looked up), and `springtune.skip`. A module with no Spring Boot configuration and no
`@SpringBootApplication` is skipped.

## What is a module

A directory with `src/main/resources[/config]/application*.{yml,yaml,properties}` or a
`@SpringBootApplication` class under `src/main/java`. Discovery skips hidden directories, `target`, `build`,
`out`, `bin` and `node_modules`, and never reads `src/test`.

## How configuration is resolved

The check uses Spring Boot's own `YamlPropertySourceLoader`, `PropertiesPropertySourceLoader`, `Binder`,
placeholder resolver and `Profiles.of(...)`. Relaxed names (`maximumPoolSize` = `maximum-pool-size`), type
conversion (`Duration`, `DataSize`) and origins (file:line for every finding) match runtime behavior.
Precedence, lowest to highest (verified by `ProjectConfigLoaderTest`):

1. `src/main/resources/application.yml`/`.yaml`, then `application.properties` (properties win in the same location)
2. `src/main/resources/config/application.*`
3. For each active profile in order: `application-{profile}.*`, then `config/application-{profile}.*`
4. In multi-document files, later documents win. `spring.config.activate.on-profile` expressions such as
   `prod & !eu` are honored. Documents with `on-cloud-platform` are excluded.

Active profiles are taken from the request, else from `spring.profiles.active` in the base files, else from
`spring.profiles.default` (`default`). `spring.profiles.include` is appended, and `spring.profiles.group.*`
members follow their group.

### Values that cannot be known statically

`${VAR}` with no default, or a value Spring could not convert (e.g. `30s` for HikariCP's millisecond
`long`), is **not evaluated**. It appears under *Not evaluated* with its location. `${VAR:10}` resolves to `10`.

## Which rules apply

A rule family runs only when the module uses the technology. This keeps the check quiet on projects it does
not understand:

| Technology | Detected by |
|---|---|
| HikariCP | `spring-boot-starter-jdbc/data-jpa/data-jdbc/jooq`, `spring-boot-jdbc` or `HikariCP` in the build, or any `spring.datasource.*`, unless `spring.datasource.type` names another pool |
| Tomcat | `spring-boot-starter-web/webmvc/tomcat` without Jetty/Undertow, or any `server.tomcat.*` |
| Virtual threads | `spring.threads.virtual.enabled=true` (disables ST-TOMCAT-001 and ST-ASYNC-001) |
| OpenFeign | `spring-cloud-starter-openfeign` or `@EnableFeignClients` |
| Resilience4j | `resilience4j.*` properties |

Build files: Maven `<dependencies>` (not `dependencyManagement`) of the module and its ancestor directories
up to the root; Gradle coordinates in `build.gradle(.kts)`. The Spring Boot version is read from the parent
POM, the BOM import, or the Gradle plugin, and is shown in the report.

## Known limitations

| Not modeled | Effect | Workaround |
|---|---|---|
| Environment variables, system properties, command-line args, `spring.config.import`, config server | Values set only there are invisible | Put production values in `application-prod.yml`, or analyze with the profile your deployment uses |
| Kotlin sources | Source rules do not see them | Config rules still apply |
| Symbol resolution | Source rules match names, not types; builders stored in variables are not followed | Conservative by design: false negatives over false positives |
| Helm templates | Only plain `values.yaml` and manifests are read | List the right file under `services[].deployment` |
| Custom datasource prefixes (multiple datasources) | Only `spring.datasource.hikari.*` is analyzed | Planned |

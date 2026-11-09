# The check (`springtune check`, `mvn springtune:check`, `./gradlew springtuneCheck`)

## CLI

Get the runnable jar (Java 17+) from the [v0.4.0 release](https://github.com/pvgiang/spring-tune/releases/tag/v0.4.0)
(`springtune-cli-0.4.0.jar`; verify it against the release's `SHA256SUMS`, see the [README](../README.md#quick-start))
or from Maven Central as `io.github.winkytek.springtune:springtune-check-cli:0.4.0:all`. Run it as
`java -jar springtune-cli-0.4.0.jar check ...`.

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

## Gradle plugin

Plugin id `io.github.winkytek.springtune`, Gradle 8.5+ (tested with 8.5, 8.14 and 9.8; configuration cache
compatible). It registers `springtuneCheck` in every project it is applied to. With the `java` plugin, `check`
depends on it.

```kotlin
// build.gradle.kts: the plugin resolves through the Gradle Plugin Portal (see below), the engine from Maven Central
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.github.winkytek.springtune") version "0.4.0"
}

repositories {
    mavenCentral()                 // the plugin resolves the check engine here (see below)
}

springtune {                       // all optional
    profiles.set(listOf("prod"))
    failOn.set("WARNING")          // ERROR, WARNING, INFO or NONE
    formats.set(listOf("markdown", "sarif", "json"))
}
```

**Where the plugin comes from.** The plugin and its marker are published to Maven Central. They are not listed on
the Gradle Plugin Portal yet (the listing follows 0.4.0), but the Portal serves Maven Central artifacts, so
`plugins { id("io.github.winkytek.springtune") version "0.4.0" }` resolves with Gradle's default plugin repository
(the post-release smoke proves both routes on Gradle 8.14 and 9.8). Until the Plugin Portal listing, add
`mavenCentral()` to `pluginManagement.repositories` if your build doesn't use `gradlePluginPortal()`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()             // or your mirror of it
        gradlePluginPortal()       // keep it if your other plugins need it
    }
}
```

| Property | `-P` override | Default |
|---|---|---|
| `profiles` | `-Pspringtune.profiles=prod,eu` | `springtune.yml` `profiles`, else `spring.profiles.active` |
| `failOn` | `-Pspringtune.failOn=NONE` | `springtune.yml` `failOn`, else `ERROR` |
| `formats` | `-Pspringtune.formats=sarif` | `markdown`, `sarif` |
| `configFile` | | `<rootDirectory>/springtune.yml` if present |
| `rootDirectory` | | the root project directory (report paths are relative to it) |
| `outputDirectory` | | `build/springtune` |
| `toolVersion` | | the plugin's version |

**The resolved classpath decides which rules apply.** The plugin passes the module names on the project's
resolved `runtimeClasspath`, and the resolved `org.springframework.boot:spring-boot` version, to the engine.
They add to what is read from the build files, so dependencies declared through version catalogs, convention
plugins or BOMs are seen, which a literal read of `build.gradle(.kts)` misses. This is also what Spring Boot's
auto-configuration reacts to. If a dependency cannot be resolved (offline, missing repository), the check
still uses the module name it requested, and warns that its transitive libraries are unknown.

**The engine runs in isolation.** The plugin jar has no dependencies. The engine (Spring Boot, Jackson,
JavaParser) is resolved through the `springtune` configuration from the project's repositories and runs in a
Gradle worker with its own class loader, so its library versions never meet the Spring Boot Gradle plugin's or
the build's. To pin or replace it, set `toolVersion` or add a dependency to the `springtune` configuration.

`springtuneCheck` is never up-to-date: the engine reads parent build files, in-repo library sources and
deployment manifests anywhere below the root, which cannot be declared as precise task inputs.

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
| JDBC (any pool) | JDBC/JPA/jOOQ starters, `spring-boot-jdbc`, `HikariCP`, Druid or dynamic-datasource starters, or any `spring.datasource.*` (used by ST-TX-001) |
| HikariCP via `spring.datasource.hikari.*` | JDBC, and none of: a non-Hikari `spring.datasource.type`; Druid starter or `spring.datasource.druid.*`; dynamic-datasource or `spring.datasource.dynamic.*`; Spring Boot 1.x without an explicit HikariCP dependency (1.x defaults to tomcat-jdbc); a DataSource built in the module's code or a `@Bean DataSource` in a library it depends on; `DataSourceAutoConfiguration` excluded |
| Tomcat | `spring-boot-starter-web/webmvc/tomcat` without Jetty/Undertow, or any `server.tomcat.*` |
| Virtual threads | `spring.threads.virtual.enabled=true` (disables ST-TOMCAT-001 and ST-ASYNC-001) |
| OpenFeign | `@EnableFeignClients` in the module or an in-repo library it depends on |
| Resilience4j | `resilience4j.*` properties |

**Incomplete local config.** When the module's code sets `spring.config.name`/`spring.config.location`, when
`spring.config.import` loads a remote source (`configserver:`, `nacos:`, `consul:`, `zookeeper:`, `vault:`,
`aws-…`, `kubernetes:`, `apollo:`), or when application/bootstrap config points at Spring Cloud Config,
Nacos, Consul, ZooKeeper, Vault or Apollo, a property missing from the files proves nothing. Rules that report
*missing* properties are then skipped for that module, and the report says why.

**Library code.** Every `src/main/java` root in the repository is a unit, keyed by Maven `artifactId` (Gradle:
directory name, `project(':name')`). A module also sees the code of the in-repo libraries it depends on,
transitively. That is how an `AsyncConfigurer`, `@Async` methods or a `@Bean DataSource` in a shared module
are taken into account. Artifact ids that occur twice are not followed.

Build files: Maven `<dependencies>` (not `dependencyManagement`) of the module and its ancestor directories
up to the root; Gradle coordinates in `build.gradle(.kts)`. The Spring Boot version is read from the parent
POM, the BOM import, or the Gradle plugin, and is shown in the report. Run through the Gradle plugin, the
resolved runtime classpath and Spring Boot version are added (see [Gradle plugin](#gradle-plugin)).

## Known limitations

| Not modeled | Effect | Workaround |
|---|---|---|
| Environment variables, system properties, command-line args, `spring.config.import`, config server | Values set only there are invisible | Put production values in `application-prod.yml`, or analyze with the profile your deployment uses |
| Kotlin sources | Source rules do not see them | Config rules still apply |
| Symbol resolution | Source rules match names, not types; builders stored in variables are not followed | Conservative by design: false negatives over false positives |
| Helm templates | Only plain `values.yaml` and manifests are read | List the right file under `services[].deployment` |
| Custom datasource prefixes (multiple datasources) | Only `spring.datasource.hikari.*` is analyzed | Planned |

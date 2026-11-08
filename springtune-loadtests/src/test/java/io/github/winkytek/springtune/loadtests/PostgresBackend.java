package io.github.winkytek.springtune.loadtests;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL server the load suites run against. Two backends, same tests:
 *
 * <ul>
 *   <li><b>external</b> when {@code -Dspringtune.it.postgres.url} is set (a superuser URL, plus
 *       {@code .user}/{@code .password}); server-log assertions read {@code -Dspringtune.it.postgres.log}, which is
 *       then required;</li>
 *   <li><b>Testcontainers</b> {@code postgres:17-alpine} otherwise (CI), with {@code max_connections=23} and
 *       {@code log_connections=on}; the server log is the container's output.</li>
 * </ul>
 *
 * Why 23: PostgreSQL keeps {@code superuser_reserved_connections} (3) for superusers, so application roles can open
 * {@code 23 - 3 = 20}: the "max_connections = 20" of the brief's budget, and exactly what SpringTune's
 * {@code BUDGET_EXCEEDS_SERVER} check reads. With {@code max_connections=20} the brief's own budget of 20 would be
 * refused by that check, correctly. Superuser sessions (the probe) still count against the 20, see
 * {@link ActivityProbe}.
 *
 * <p>Each test creates its own database and roles ({@link Scenario}); nothing changes server-wide settings.
 */
abstract class PostgresBackend {

    static final String URL = "springtune.it.postgres.url";
    static final String USER = "springtune.it.postgres.user";
    static final String PASSWORD = "springtune.it.postgres.password";
    static final String LOG = "springtune.it.postgres.log";
    static final String REQUIRE_DOCKER = "springtune.it.requireDocker";
    static final int MAX_CONNECTIONS = 23;
    /** What application (non-superuser) roles can open: max_connections - superuser_reserved_connections. */
    static final int APP_CONNECTION_LIMIT = 20;
    static final String IMAGE = "postgres:17-alpine";

    private static PostgresBackend instance;

    static boolean externalConfigured() {
        String url = System.getProperty(URL);
        return url != null && !url.isBlank();
    }

    static synchronized PostgresBackend get() {
        if (instance == null) {
            instance = externalConfigured() ? new External() : new Container();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
            instance.checkServer();
        }
        return instance;
    }

    abstract String host();

    abstract int port();

    abstract String user();

    abstract String password();

    abstract String adminDatabase();

    abstract String description();

    /** The server log written so far, from the start of this JVM's use of the server. */
    abstract String serverLog();

    void stop() {
    }

    String jdbcUrl(String database, String applicationName) {
        return "jdbc:postgresql://" + host() + ":" + port() + "/" + database + "?ApplicationName=" + applicationName;
    }

    Connection superuser(String database, String applicationName) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user());
        props.setProperty("password", password());
        return DriverManager.getConnection(jdbcUrl(database, applicationName), props);
    }

    private void checkServer() {
        try (Connection connection = superuser(adminDatabase(), "springtune-loadtest-admin");
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(
                        "SELECT current_setting('max_connections')::int - current_setting('superuser_reserved_connections')::int"
                                + " - coalesce(current_setting('reserved_connections', true)::int, 0),"
                                + " current_setting('log_connections'), version()")) {
            rs.next();
            int appLimit = rs.getInt(1);
            if (appLimit != APP_CONNECTION_LIMIT) {
                throw new IllegalStateException("The load suites need a server on which application roles can open "
                        + APP_CONNECTION_LIMIT + " connections (max_connections=" + MAX_CONNECTIONS
                        + " with superuser_reserved_connections=3); " + description() + " allows " + appLimit + ".");
            }
            if (!"on".equals(rs.getString(2))) {
                throw new IllegalStateException("The load suites need log_connections=on on " + description());
            }
            System.out.println("[loadtests] backend " + description() + ": " + rs.getString(3));
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot reach " + description(), ex);
        }
    }

    /** {@code -Dspringtune.it.postgres.url=jdbc:postgresql://localhost:55432/postgres}. */
    static final class External extends PostgresBackend {

        private final URI uri;
        private final Path log;
        private final long logStart;

        External() {
            String url = System.getProperty(URL);
            if (!url.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException(URL + " must be a jdbc:postgresql:// URL, was " + url);
            }
            uri = URI.create(url.substring("jdbc:".length()));
            String logPath = System.getProperty(LOG);
            if (logPath == null || logPath.isBlank()) {
                throw new IllegalStateException("External PostgreSQL: server-log assertions need -D" + LOG
                        + "=<path of the server log> (log_connections=on).");
            }
            log = Path.of(logPath);
            if (!Files.isReadable(log)) {
                throw new IllegalStateException("Cannot read the server log " + log);
            }
            try {
                logStart = Files.size(log);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        String host() {
            return uri.getHost();
        }

        @Override
        int port() {
            return uri.getPort() < 0 ? 5432 : uri.getPort();
        }

        @Override
        String user() {
            return System.getProperty(USER, "postgres");
        }

        @Override
        String password() {
            return System.getProperty(PASSWORD, "");
        }

        @Override
        String adminDatabase() {
            String path = uri.getPath();
            return path == null || path.length() <= 1 ? "postgres" : path.substring(1);
        }

        @Override
        String description() {
            return "external PostgreSQL " + host() + ":" + port();
        }

        @Override
        String serverLog() {
            try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                long length = file.length();
                byte[] bytes = new byte[(int) Math.max(0, length - logStart)];
                file.seek(Math.min(logStart, length));
                file.readFully(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read the server log " + log, ex);
            }
        }
    }

    /** Testcontainers {@code postgres:17-alpine}. */
    static final class Container extends PostgresBackend {

        private final PostgreSQLContainer container;

        @SuppressWarnings("resource")
        Container() {
            container = new PostgreSQLContainer(IMAGE)
                    .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS, "-c", "log_connections=on",
                            "-c", "fsync=off");
            container.start();
        }

        @Override
        String host() {
            return container.getHost();
        }

        @Override
        int port() {
            return container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        }

        @Override
        String user() {
            return container.getUsername();
        }

        @Override
        String password() {
            return container.getPassword();
        }

        @Override
        String adminDatabase() {
            return container.getDatabaseName();
        }

        @Override
        String description() {
            return "Testcontainers " + IMAGE;
        }

        @Override
        String serverLog() {
            return container.getLogs();
        }

        @Override
        void stop() {
            container.stop();
        }
    }
}

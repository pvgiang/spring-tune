package io.github.winkytek.springtune.loadtests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One test's world on the shared server: its own database and roles (dropped afterwards), its replicas, load
 * generators and {@code pg_stat_activity} probe, the server-log slice between its markers, and a result file
 * ({@code target/loadtests/<name>.txt}) with every measured count. Server-wide settings are never changed.
 */
final class Scenario implements AutoCloseable {

    static final String PASSWORD = "lt-pw";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final String name;
    private final PostgresBackend backend = PostgresBackend.get();
    private final String databaseName;
    private final List<String> roles = new ArrayList<>();
    private final Deque<AutoCloseable> closeables = new ArrayDeque<>();
    private final List<String> results = new ArrayList<>();
    private final String begin;
    private final String end;
    private ActivityProbe probe;
    private ServerLog serverLog;

    Scenario(String name) throws SQLException {
        this.name = name;
        String full = ("lt_" + SEQUENCE.incrementAndGet() + "_" + name.replaceAll("[^A-Za-z0-9]", "_"))
                .toLowerCase(Locale.ROOT);
        String shortDb = full.length() > 40 ? full.substring(0, 40) : full;
        this.databaseName = shortDb;
        this.begin = "springtune-loadtest begin " + shortDb + " " + System.nanoTime();
        this.end = "springtune-loadtest end " + shortDb + " " + System.nanoTime();
        admin("DROP DATABASE IF EXISTS " + shortDb + " WITH (FORCE)");
        admin("CREATE DATABASE " + shortDb);
        record("scenario " + name + " on " + backend.description() + ", database " + shortDb + ", base seed "
                + Seeds.BASE + ", started " + Instant.now());
        ServerLog.mark(backend, begin);
    }

    String database() {
        return databaseName;
    }

    PostgresBackend backend() {
        return backend;
    }

    /** Creates a login role (dropped on close); {@code connectionLimit} -1 for none. */
    String role(String suffix, int connectionLimit) throws SQLException {
        String role = (databaseName.length() > 30 ? databaseName.substring(0, 30) : databaseName) + "_" + suffix;
        admin("DROP ROLE IF EXISTS " + role);
        admin("CREATE ROLE " + role + " LOGIN PASSWORD '" + PASSWORD + "' CONNECTION LIMIT " + connectionLimit);
        roles.add(role);
        return role;
    }

    void admin(String sql) throws SQLException {
        try (Connection connection = backend.superuser(backend.adminDatabase(), "springtune-loadtest-admin");
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    ActivityProbe probe() throws SQLException {
        if (probe == null) {
            probe = new ActivityProbe(backend);
            closeables.push(probe);
        }
        return probe;
    }

    Replica replica(String applicationName, String role, Map<String, String> settings) {
        Replica replica = Replica.start(applicationName, backend.jdbcUrl(databaseName, applicationName), role,
                PASSWORD, settings);
        closeables.push(replica);
        return replica;
    }

    OpenLoopJdbcLoad load(OpenLoopJdbcLoad load) {
        closeables.push(load);
        return load;
    }

    <T extends AutoCloseable> T closeLater(T closeable) {
        closeables.push(closeable);
        return closeable;
    }

    void record(String line) {
        System.out.println("[loadtests] " + name + ": " + line);
        results.add(line);
    }

    /** The server log of this scenario so far (writes the end marker on first call). */
    ServerLog serverLog() throws SQLException {
        if (serverLog == null) {
            ServerLog.mark(backend, end);
            // The log is written by the server process; give the end marker a moment to reach the file.
            for (int i = 0; i < 50; i++) {
                String log = backend.serverLog();
                if (log.contains(end)) {
                    serverLog = ServerLog.between(log, begin, end);
                    return serverLog;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            serverLog = ServerLog.between(backend.serverLog(), begin, end);
        }
        return serverLog;
    }

    @Override
    public void close() throws Exception {
        Exception first = null;
        while (!closeables.isEmpty()) {
            try {
                closeables.pop().close();
            } catch (Exception ex) {
                first = first == null ? ex : first;
            }
        }
        try {
            if (serverLog == null) {
                serverLog();
            }
        } catch (Exception ex) {
            first = first == null ? ex : first;
        }
        try {
            admin("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            for (String role : roles) {
                admin("DROP ROLE IF EXISTS " + role);
            }
        } catch (SQLException ex) {
            first = first == null ? ex : first;
        }
        write();
        if (first != null) {
            throw first;
        }
    }

    private void write() {
        String dir = System.getProperty("springtune.it.results", "target/loadtests");
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            String file = name.replaceAll("[^A-Za-z0-9.]", "_");
            Files.write(out.resolve(file + ".txt"), results, StandardCharsets.UTF_8);
            if (serverLog != null) {
                Files.writeString(out.resolve(file + ".server.log"), serverLog.text(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            System.out.println("[loadtests] cannot write results: " + ex);
        }
    }
}

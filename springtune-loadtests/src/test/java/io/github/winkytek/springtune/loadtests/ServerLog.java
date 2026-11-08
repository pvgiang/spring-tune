package io.github.winkytek.springtune.loadtests;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The PostgreSQL server log of one scenario: the lines between two markers the scenario writes with
 * {@code RAISE LOG}. What the database itself reports, independent of any client: refused connections
 * ({@code too many clients already}, reserved slots, a role's {@code CONNECTION LIMIT}, all SQLSTATE 53300) and,
 * with {@code log_connections=on}, every authorized connection per role.
 */
record ServerLog(List<String> lines) {

    /** Server-wide limit reached: SQLSTATE 53300 "sorry, too many clients already" or the reserved-slots variant. */
    static final Pattern TOO_MANY_CLIENTS = Pattern.compile(
            "too many clients already|remaining connection slots are reserved");
    /** A role's CONNECTION LIMIT reached (also SQLSTATE 53300). */
    static final Pattern ROLE_LIMIT = Pattern.compile("too many connections for role");

    static void mark(PostgresBackend backend, String marker) throws SQLException {
        try (Connection connection = backend.superuser(backend.adminDatabase(), "springtune-loadtest-admin");
                Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN RAISE LOG '" + marker.replace("'", "") + "'; END $$");
        }
    }

    /** The lines after {@code begin} and before {@code end} (to the end of the log when {@code end} is missing). */
    static ServerLog between(String log, String begin, String end) {
        int from = log.indexOf(begin);
        if (from < 0) {
            throw new IllegalStateException("Marker '" + begin + "' not found in the server log");
        }
        int to = log.indexOf(end, from);
        String slice = log.substring(from, to < 0 ? log.length() : to);
        return new ServerLog(slice.lines().toList());
    }

    long count(Pattern pattern) {
        return lines.stream().filter(line -> pattern.matcher(line).find()).count();
    }

    /** {@code connection authorized: user=<role>} lines (log_connections=on). */
    long authorizedConnections(String role) {
        Pattern pattern = Pattern.compile("connection authorized: user=" + Pattern.quote(role) + "\\b");
        return count(pattern);
    }

    String text() {
        return String.join("\n", lines);
    }
}

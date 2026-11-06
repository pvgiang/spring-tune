package io.github.winkytek.springtune.boot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * The number of connections the database server accepts from ordinary (non-administrative) roles, read through
 * the pool once: a configured {@code springtune.budget.database-max-connections} above it is a misconfiguration
 * that only a real database can reveal ({@link ApplyGuard.Code#BUDGET_EXCEEDS_SERVER}).
 *
 * <ul>
 *   <li>PostgreSQL: {@code max_connections - superuser_reserved_connections - reserved_connections} (the last one
 *       exists from PostgreSQL 16 on and counts as 0 before).</li>
 *   <li>MySQL / MariaDB: {@code @@max_connections} (the extra administrative connection is not counted).</li>
 *   <li>Any other database: {@link Status#UNSUPPORTED}, not checked (the apply plan says so).</li>
 * </ul>
 *
 * @param status  whether {@code limit} is known
 * @param limit   connections available to application roles, or {@code null}
 * @param product database product name, or {@code null} when not read
 * @param detail  how the limit was computed, or why it is not known
 */
record ServerConnectionLimit(Status status, Integer limit, String product, String detail) {

    enum Status {
        /** Read from the server. */
        KNOWN,
        /** Not read yet: the pool has not had an idle connection to read it with, or the read failed. */
        NOT_READ,
        /** A database SpringTune cannot read the limit of. */
        UNSUPPORTED,
        /** No connection budget configured, so there is nothing to compare. */
        NOT_APPLICABLE
    }

    static final String POSTGRES_QUERY = "SELECT current_setting('max_connections')::int,"
            + " current_setting('superuser_reserved_connections')::int,"
            + " coalesce(current_setting('reserved_connections', true)::int, 0)";

    static ServerConnectionLimit notRead(String why) {
        return new ServerConnectionLimit(Status.NOT_READ, null, null, why);
    }

    static ServerConnectionLimit notApplicable() {
        return new ServerConnectionLimit(Status.NOT_APPLICABLE, null, null, "no connection budget configured");
    }

    boolean known() {
        return status == Status.KNOWN;
    }

    /** Reads the limit with {@code connection} (borrowed from the pool and returned by the caller). */
    static ServerConnectionLimit read(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
        try (Statement statement = connection.createStatement()) {
            if (name.contains("postgresql")) {
                try (ResultSet rs = statement.executeQuery(POSTGRES_QUERY)) {
                    rs.next();
                    int max = rs.getInt(1);
                    int superuserReserved = rs.getInt(2);
                    int reserved = rs.getInt(3);
                    int limit = max - superuserReserved - reserved;
                    return new ServerConnectionLimit(Status.KNOWN, limit, product, "PostgreSQL max_connections " + max
                            + " - superuser_reserved_connections " + superuserReserved + " - reserved_connections "
                            + reserved + " = " + limit);
                }
            }
            if (name.contains("mysql") || name.contains("mariadb")) {
                try (ResultSet rs = statement.executeQuery("SELECT @@max_connections")) {
                    rs.next();
                    int limit = rs.getInt(1);
                    return new ServerConnectionLimit(Status.KNOWN, limit, product, product + " @@max_connections = "
                            + limit);
                }
            }
        }
        return new ServerConnectionLimit(Status.UNSUPPORTED, null, product, "SpringTune cannot read the connection"
                + " limit of " + product + "; database-max-connections is not checked against the server");
    }

    /** One line for the apply plan's budget arithmetic. */
    String planLine(Integer configured) {
        return switch (status) {
            case KNOWN -> "server limit: " + detail + "; database-max-connections " + configured
                    + (configured != null && configured > limit
                            ? " > " + limit + ": apply mode refuses to raise the pool (BUDGET_EXCEEDS_SERVER)"
                            : " <= " + limit + ": consistent with the server");
            case NOT_READ -> "server limit not checked yet (" + detail + ")";
            case UNSUPPORTED -> "server limit not checked: " + detail;
            case NOT_APPLICABLE -> "server limit not checked: " + detail;
        };
    }
}

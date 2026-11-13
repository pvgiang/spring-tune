package io.github.winkytek.springtune.boot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;

/**
 * The number of connections the database server accepts from ordinary (non-administrative) roles, read through
 * the pool: a configured {@code springtune.budget.database-max-connections} above it is a misconfiguration that only
 * a real database can reveal ({@link ApplyGuard.Code#BUDGET_EXCEEDS_SERVER}). It is read once in the background
 * after the pool starts (for the apply plan) and <b>re-read before every apply attempt</b>, because an operator can
 * lower {@code max_connections} (with a server restart) while the application keeps running.
 *
 * <ul>
 *   <li>PostgreSQL: {@code max_connections - superuser_reserved_connections - reserved_connections} (the last one
 *       exists from PostgreSQL 16 on and counts as 0 before).</li>
 *   <li>MySQL / MariaDB: {@code @@max_connections} (the extra administrative connection is not counted).</li>
 *   <li>Any other database: {@link Status#UNSUPPORTED}, not checked (the apply plan says so).</li>
 * </ul>
 *
 * @param status         whether {@code limit} is known
 * @param limit          connections available to application roles, or {@code null}
 * @param product        database product name, or {@code null} when not read
 * @param detail         how the limit was computed, or why it is not known
 * @param checkedAt      when {@code limit} was read, or {@code null}
 * @param recheckFailure for a known limit: why the latest re-read failed (and when), or {@code null}
 */
record ServerConnectionLimit(Status status, Integer limit, String product, String detail, Instant checkedAt,
        String recheckFailure) {

    enum Status {
        /** Read from the server. */
        KNOWN,
        /** Not read: the pool has not had a connection to read it with yet, or the (re-)read failed. */
        NOT_READ,
        /** A database SpringTune cannot read the limit of. */
        UNSUPPORTED,
        /** No connection budget configured, so there is nothing to compare. */
        NOT_APPLICABLE,
        /**
         * Deliberately not read for this apply attempt: under the same lock hold, from the same re-evaluated
         * recommendation that the guard then judges, the change was found not to raise the pool (a decrease, or a
         * request the guard refuses anyway). Never cached; {@link ApplyGuard} refuses <b>any</b> raise that carries it
         * ({@code SERVER_LIMIT_UNKNOWN}), so a recommendation that turned into a raise can never pass unchecked.
         */
        SKIPPED_FOR_DECREASE
    }

    ServerConnectionLimit(Status status, Integer limit, String product, String detail) {
        this(status, limit, product, detail, null, null);
    }

    static final String POSTGRES_QUERY = "SELECT current_setting('max_connections')::int,"
            + " current_setting('superuser_reserved_connections')::int,"
            + " coalesce(current_setting('reserved_connections', true)::int, 0)";

    static ServerConnectionLimit notRead(String why) {
        return new ServerConnectionLimit(Status.NOT_READ, null, null, why);
    }

    /** See {@link Status#SKIPPED_FOR_DECREASE}. */
    static ServerConnectionLimit skippedForDecrease() {
        return new ServerConnectionLimit(Status.SKIPPED_FOR_DECREASE, null, null, "not read for this apply: it was"
                + " evaluated as not raising the pool");
    }

    static ServerConnectionLimit notApplicable() {
        return new ServerConnectionLimit(Status.NOT_APPLICABLE, null, null, "no connection budget configured");
    }

    boolean known() {
        return status == Status.KNOWN;
    }

    ServerConnectionLimit checkedAt(Instant at) {
        return new ServerConnectionLimit(status, limit, product, detail, at, null);
    }

    /** This (known) limit, annotated with a failed re-read: kept for the apply plan, never used to approve a raise. */
    ServerConnectionLimit withRecheckFailure(String failure) {
        return new ServerConnectionLimit(status, limit, product, detail, checkedAt, failure);
    }

    /** "last known 20 (checked at ...)" for messages, or {@code null} when nothing was ever read. */
    String lastKnown() {
        return known() ? "last known limit " + limit + ", read at " + checkedAt : null;
    }

    /**
     * Reads the limit with {@code connection} (borrowed from the pool and returned by the caller, which has already
     * bounded its network timeout: {@code HikariComponent#boundNetworkTimeout}).
     */
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
                            : " <= " + limit + ": consistent with the server")
                    + (checkedAt != null ? "; last checked at " + checkedAt + ", re-read before every apply" : "")
                    + (recheckFailure != null ? "; " + recheckFailure + ": a raise is refused while it cannot be"
                            + " re-read (SERVER_LIMIT_UNKNOWN)" : "");
            case NOT_READ -> "server limit not checked yet (" + detail + "); it is re-read before every apply, and a"
                    + " raise is refused if it cannot be read (SERVER_LIMIT_UNKNOWN)";
            case UNSUPPORTED -> "server limit not checked: " + detail;
            case NOT_APPLICABLE -> "server limit not checked: " + detail;
            case SKIPPED_FOR_DECREASE -> "server limit not read: " + detail + "; a raise needs a fresh read";
        };
    }
}

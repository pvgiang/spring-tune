package io.github.winkytek.springtune.loadtests;

import java.sql.PreparedStatement;

/**
 * Statements whose duration the <b>database</b> decides: {@code pg_sleep}, not client-side sleeps, so a slow
 * request is slow in the server and a loaded test JVM cannot fake or hide it.
 */
final class Workloads {

    private Workloads() {
    }

    /** Holds the connection for {@code seconds} in the server. */
    static OpenLoopJdbcLoad.Request sleep(double seconds) {
        return connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_sleep(?)")) {
                statement.setDouble(1, seconds);
                statement.execute();
            }
        };
    }

    /**
     * A database that gets slower with more sessions, decided by the server: each statement counts the
     * <i>active</i> sessions of its own application ({@code pg_stat_activity}, same {@code application_name}, itself
     * included) and sleeps {@code slowSeconds} when there are more than {@code fastUpTo}, {@code fastSeconds}
     * otherwise.
     */
    static OpenLoopJdbcLoad.Request slowerAbove(int fastUpTo, double fastSeconds, double slowSeconds) {
        return connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_sleep(CASE WHEN"
                    + " (SELECT count(*) FROM pg_stat_activity WHERE application_name ="
                    + " current_setting('application_name') AND state = 'active') > ? THEN ? ELSE ? END)")) {
                statement.setInt(1, fastUpTo);
                statement.setDouble(2, slowSeconds);
                statement.setDouble(3, fastSeconds);
                statement.execute();
            }
        };
    }
}

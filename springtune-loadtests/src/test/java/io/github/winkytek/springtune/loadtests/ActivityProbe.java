package io.github.winkytek.springtune.loadtests;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * Samples {@code pg_stat_activity} every {@value #INTERVAL_MS} ms over one superuser connection: the server's own
 * count of client backends, per {@code application_name}, in every state (an idle pooled connection is a session
 * too). The probe's connection is one of the sessions the server counts against the 20 that application roles may
 * open; every result says so.
 */
final class ActivityProbe implements AutoCloseable {

    static final long INTERVAL_MS = 50;
    static final String APPLICATION_NAME = "springtune-probe";

    /** One reading: sessions per application_name (all client backends of the server). */
    record Sample(long atMillis, Map<String, Integer> byApplication, int total) {

        int sum(Predicate<String> applications) {
            return byApplication.entrySet().stream().filter(e -> applications.test(e.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();
        }
    }

    private final Connection connection;
    private final List<Sample> samples = new CopyOnWriteArrayList<>();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile SQLException failure;

    ActivityProbe(PostgresBackend backend) throws SQLException {
        this.connection = backend.superuser(backend.adminDatabase(), APPLICATION_NAME);
        this.thread = new Thread(this::run, "pg-activity-probe");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void run() {
        String sql = "SELECT coalesce(application_name, ''), count(*) FROM pg_stat_activity"
                + " WHERE backend_type = 'client backend' GROUP BY 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            while (running) {
                Map<String, Integer> counts = new HashMap<>();
                int total = 0;
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        counts.put(rs.getString(1), rs.getInt(2));
                        total += rs.getInt(2);
                    }
                }
                samples.add(new Sample(System.currentTimeMillis(), Map.copyOf(counts), total));
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(INTERVAL_MS));
            }
        } catch (SQLException ex) {
            if (running) {
                failure = ex;
            }
        }
    }

    List<Sample> samples() {
        if (failure != null) {
            throw new IllegalStateException("pg_stat_activity probe failed", failure);
        }
        return List.copyOf(samples);
    }

    int count() {
        return samples().size();
    }

    int maxOf(String applicationName) {
        return samples().stream().mapToInt(s -> s.byApplication().getOrDefault(applicationName, 0)).max().orElse(0);
    }

    int maxSum(Predicate<String> applications) {
        return samples().stream().mapToInt(s -> s.sum(applications)).max().orElse(0);
    }

    int maxTotal() {
        return samples().stream().mapToInt(Sample::total).max().orElse(0);
    }

    /** Readings in which the selected applications held more than {@code limit} sessions together. */
    long samplesAbove(Predicate<String> applications, int limit) {
        return samples().stream().filter(s -> s.sum(applications) > limit).count();
    }

    /** Highest reading per application_name. */
    Map<String, Integer> maxByApplication() {
        Map<String, Integer> max = new TreeMap<>();
        for (Sample sample : samples()) {
            sample.byApplication().forEach((app, n) -> max.merge(app, n, Math::max));
        }
        return max;
    }

    /** Waits until at least {@code n} more readings were taken (so a reading covers the moment of interest). */
    void awaitReadings(int n) {
        int target = samples.size() + n;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (samples.size() < target && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(INTERVAL_MS));
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing
        }
    }
}

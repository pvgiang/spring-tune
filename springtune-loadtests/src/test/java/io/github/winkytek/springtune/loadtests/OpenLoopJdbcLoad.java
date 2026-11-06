package io.github.winkytek.springtune.loadtests;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;

/**
 * Open-loop JDBC load: requests arrive at a fixed mean rate whatever the system does (an arrival scheduler with
 * seeded jitter, the seed logged), a fixed pool of worker threads serves them, and a bounded queue in between
 * counts what it drops. Each request borrows a connection, runs its statement (the hold time comes from the
 * database, {@code pg_sleep}) and returns the connection.
 *
 * <p>A worker that finished a request goes back to the idle pool for {@code thinkTime} before it may take the next
 * one. Without that the returning thread re-borrows its own connection through HikariCP's thread-local fast path
 * before a waiting thread receives the handoff (see {@code HikariApplyIntegrationTest}): acquire times would then
 * measure the fast path, not the queue.
 */
final class OpenLoopJdbcLoad implements AutoCloseable {

    @FunctionalInterface
    interface Request {
        void run(Connection connection) throws SQLException;
    }

    record Stats(long arrivals, long dropped, long completed, long failed, Map<String, Long> sqlStates) {

        long sqlState(String state) {
            return sqlStates.getOrDefault(state, 0L);
        }

        @Override
        public String toString() {
            return "arrivals=" + arrivals + " dropped=" + dropped + " completed=" + completed + " failed=" + failed
                    + " sqlStates=" + sqlStates;
        }
    }

    private final String name;
    private final DataSource dataSource;
    private final Request request;
    private final double ratePerSecond;
    private final long seed;
    private final Duration thinkTime;
    private final BlockingQueue<Long> queue;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    private final AtomicLong arrivals = new AtomicLong();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final Map<String, LongAdder> sqlStates = new ConcurrentHashMap<>();

    private OpenLoopJdbcLoad(String name, DataSource dataSource, Request request, double ratePerSecond, int workers,
            int queueCapacity, long seed, Duration thinkTime) {
        this.name = name;
        this.dataSource = dataSource;
        this.request = request;
        this.ratePerSecond = ratePerSecond;
        this.seed = seed;
        this.thinkTime = thinkTime;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        System.out.println("[loadtests] load " + name + ": " + ratePerSecond + " req/s, " + workers + " workers, queue "
                + queueCapacity + ", think " + thinkTime.toMillis() + " ms, seed " + seed);
        Thread arrivals = new Thread(this::arrive, "load-" + name + "-arrivals");
        arrivals.setDaemon(true);
        threads.add(arrivals);
        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(this::work, "load-" + name + "-worker-" + i);
            worker.setDaemon(true);
            threads.add(worker);
        }
        threads.forEach(Thread::start);
    }

    static Builder on(String name, DataSource dataSource) {
        return new Builder(name, dataSource);
    }

    static final class Builder {
        private final String name;
        private final DataSource dataSource;
        private double rate = 100;
        private int workers = 12;
        private int queue = 100;
        private Duration think = Duration.ofMillis(1);
        private final long seed;

        private Builder(String name, DataSource dataSource) {
            this.name = name;
            this.dataSource = dataSource;
            this.seed = Seeds.derive(name.hashCode());
        }

        Builder rate(double perSecond) {
            this.rate = perSecond;
            return this;
        }

        Builder workers(int workers) {
            this.workers = workers;
            return this;
        }

        Builder queue(int capacity) {
            this.queue = capacity;
            return this;
        }

        Builder thinkTime(Duration think) {
            this.think = think;
            return this;
        }

        OpenLoopJdbcLoad start(Request request) {
            return new OpenLoopJdbcLoad(name, dataSource, request, rate, workers, queue, seed, think);
        }
    }

    /** Arrival times: mean interval 1/rate, each interval jittered uniformly in [0.5, 1.5] of it (seeded). */
    private void arrive() {
        Random random = new Random(seed);
        double meanNanos = 1_000_000_000.0 / ratePerSecond;
        long next = System.nanoTime();
        while (running) {
            next += (long) (meanNanos * (0.5 + random.nextDouble()));
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            if (!running) {
                return;
            }
            // Open loop: an arrival that finds the queue full is dropped and counted, never delayed.
            arrivals.incrementAndGet();
            if (!queue.offer(System.nanoTime())) {
                dropped.increment();
            }
        }
    }

    private void work() {
        while (running) {
            Long arrival;
            try {
                arrival = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                return;
            }
            if (arrival == null) {
                continue;
            }
            try (Connection connection = dataSource.getConnection()) {
                request.run(connection);
                completed.increment();
            } catch (SQLException ex) {
                if (!running) {
                    return;
                }
                failed.increment();
                sqlStates.computeIfAbsent(sqlState(ex), k -> new LongAdder()).increment();
            } catch (RuntimeException ex) {
                if (!running) {
                    return;
                }
                failed.increment();
                sqlStates.computeIfAbsent(ex.getClass().getSimpleName(), k -> new LongAdder()).increment();
            }
            LockSupport.parkNanos(thinkTime.toNanos());
        }
    }

    /** The most specific SQLSTATE in the cause chain (Hikari's timeout carries the last connect failure's). */
    static String sqlState(Throwable ex) {
        String state = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                state = sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return state == null ? "none:" + ex.getClass().getSimpleName() : state;
    }

    String name() {
        return name;
    }

    long seed() {
        return seed;
    }

    Stats stats() {
        Map<String, Long> states = new TreeMap<>();
        sqlStates.forEach((k, v) -> states.put(k, v.sum()));
        return new Stats(arrivals.get(), dropped.sum(), completed.sum(), failed.sum(), states);
    }

    @Override
    public void close() {
        running = false;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(5000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The server-limit read bounds its borrowed connection's network timeout (iteration 6 row 9) and HikariCP gives the
 * pool's own value back when the connection is returned. H2 ignores network timeouts, so the physical connections
 * are wrapped in a recording proxy that keeps the value like a real driver (or refuses it like an old one). The
 * real effect (a half-open socket ends with an I/O error and the connection is evicted) is proven against PostgreSQL
 * by {@code ServerLimitHangLoadTest}.
 */
class ServerLimitNetworkTimeoutTest {

    /** How the fake driver answers {@code setNetworkTimeout}. */
    enum Driver { KEEPS, NOT_SUPPORTED, ABSTRACT_METHOD }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final SpringTuneProperties properties = new SpringTuneProperties();
    /** Every {@code setNetworkTimeout} that reached a physical connection, in order. */
    private final List<Integer> physicalCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger physicalValue = new AtomicInteger();
    private HikariDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariComponent component(Driver driver, long connectionTimeoutMs, HikariComponent.LimitReader reader)
            throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:nettimeout-" + driver + ";DB_CLOSE_DELAY=-1");
        dataSource = new HikariDataSource();
        dataSource.setDataSource(recording(h2, driver));
        dataSource.setPoolName("orders");
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionTimeout(connectionTimeoutMs);
        try (Connection ignored = dataSource.getConnection()) {
            // starts the pool
        }
        properties.setMode(SpringTuneProperties.Mode.APPLY);
        properties.getBudget().setDatabaseMaxConnections(20);
        return new HikariComponent("dataSource", dataSource, TimerReader.NONE, properties, Optional.of(5), clock,
                reader);
    }

    private static ServerConnectionLimit known() {
        return new ServerConnectionLimit(ServerConnectionLimit.Status.KNOWN, 20, "PostgreSQL", "= 20");
    }

    @Test
    void theReadRunsWithTheBoundAndThePoolGetsItsOwnTimeoutBack() throws Exception {
        AtomicInteger seenByTheRead = new AtomicInteger(-1);
        HikariComponent component = component(Driver.KEEPS, 1_500, connection -> {
            seenByTheRead.set(connection.getNetworkTimeout());
            return known();
        });
        int poolValue = physicalValue.get();
        physicalCalls.clear();

        assertThat(component.serverLimitWaitMs()).isEqualTo(1_500);
        assertThat(component.recheckServerLimit().known()).isTrue();

        assertThat(seenByTheRead).as("network timeout during the limit query").hasValue(1_500);
        // Set to the bound for the read, then reset by HikariCP when the connection was returned. (A liveness check
        // on borrow may add its own set-and-restore pair before.)
        assertThat(physicalCalls).contains(1_500);
        assertThat(physicalCalls.subList(physicalCalls.indexOf(1_500), physicalCalls.size()))
                .containsExactly(1_500, poolValue);
        assertThat(physicalValue).as("what the pooled connection keeps").hasValue(poolValue);
        try (Connection next = dataSource.getConnection()) {
            assertThat(next.getNetworkTimeout()).as("the next borrower").isEqualTo(poolValue);
        }
    }

    @Test
    void theBoundIsCappedAtThirtySeconds() throws Exception {
        AtomicInteger seenByTheRead = new AtomicInteger(-1);
        HikariComponent component = component(Driver.KEEPS, 120_000, connection -> {
            seenByTheRead.set(connection.getNetworkTimeout());
            return known();
        });
        assertThat(component.serverLimitWaitMs()).isEqualTo(HikariComponent.MAX_SERVER_LIMIT_WAIT_MS);
        assertThat(component.recheckServerLimit().known()).isTrue();
        assertThat(seenByTheRead).hasValue((int) HikariComponent.MAX_SERVER_LIMIT_WAIT_MS);
    }

    @Test
    void aDriverWithoutNetworkTimeoutsStillReadsTheLimit() throws Exception {
        for (Driver driver : List.of(Driver.NOT_SUPPORTED, Driver.ABSTRACT_METHOD)) {
            AtomicInteger reads = new AtomicInteger();
            HikariComponent component = component(driver, 1_500, connection -> {
                reads.incrementAndGet();
                return known();
            });
            assertThat(component.recheckServerLimit().known()).as(driver.name()).isTrue();
            assertThat(reads).as(driver.name()).hasValue(1);
            dataSource.close();
        }
    }

    @Test
    void anyOtherFailureToSetTheBoundIsAFailedRead() throws SQLException {
        Connection broken = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setNetworkTimeout")) {
                        throw new SQLException("This connection has been closed.", "08003");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        org.assertj.core.api.Assertions.assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> HikariComponent.boundNetworkTimeout(broken, 1_000)).withMessageContaining("closed");
    }

    // ------------------------------------------------------------------ recording driver

    private DataSource recording(JdbcDataSource h2, Driver driver) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return wrap(h2.getConnection(), driver);
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                return wrap(h2.getConnection(user, password), driver);
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private Connection wrap(Connection physical, Driver driver) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setNetworkTimeout" -> {
                    if (driver == Driver.NOT_SUPPORTED) {
                        throw new SQLFeatureNotSupportedException("network timeouts are not supported");
                    }
                    if (driver == Driver.ABSTRACT_METHOD) {
                        throw new AbstractMethodError("setNetworkTimeout");
                    }
                    physicalCalls.add((Integer) args[1]);
                    physicalValue.set((Integer) args[1]);
                    return null;
                }
                case "getNetworkTimeout" -> {
                    if (driver == Driver.NOT_SUPPORTED) {
                        throw new SQLFeatureNotSupportedException("network timeouts are not supported");
                    }
                    if (driver == Driver.ABSTRACT_METHOD) {
                        throw new AbstractMethodError("getNetworkTimeout");
                    }
                    return physicalValue.get();
                }
                default -> {
                    try {
                        return method.invoke(physical, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                }
            }
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                handler);
    }
}

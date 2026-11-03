package io.github.pvgiang.springtune.check.rules;

/** HikariCP property keys and library defaults (HikariCP 4.x-7.x, unchanged across Spring Boot 2.x-4.x). */
final class Hikari {

    static final String PREFIX = "spring.datasource.hikari.";
    static final String MAXIMUM_POOL_SIZE = PREFIX + "maximum-pool-size";
    static final String CONNECTION_TIMEOUT = PREFIX + "connection-timeout";
    static final String LEAK_DETECTION_THRESHOLD = PREFIX + "leak-detection-threshold";
    static final String MAX_LIFETIME = PREFIX + "max-lifetime";

    static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;
    static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;
    static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000;
    /** HikariCP silently resets {@code maxLifetime} below this to the default. */
    static final long MIN_MAX_LIFETIME_MS = 30_000;
    /** HikariCP disables leak detection below this value. */
    static final long MIN_LEAK_DETECTION_MS = 2_000;

    private Hikari() {
    }

    /** {@code maxLifetime} as HikariCP will actually use it; 0 means connections never retire. */
    static long effectiveMaxLifetime(long configured) {
        return configured != 0 && configured < MIN_MAX_LIFETIME_MS ? DEFAULT_MAX_LIFETIME_MS : configured;
    }
}

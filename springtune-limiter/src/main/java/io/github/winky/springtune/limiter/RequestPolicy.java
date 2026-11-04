package io.github.winky.springtune.limiter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides which requests are never limited: excluded paths (always including the actuator base path), requests
 * on the management port, {@code Accept: text/event-stream} and WebSocket upgrades. Shared by the servlet and
 * reactive filters.
 */
final class RequestPolicy {

    /** Body of a rejected request: tiny on purpose, a rejection must cost next to nothing. */
    static final byte[] REJECTION_BODY = "Too many concurrent requests, retry later.\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final long PORT_RECHECK_NANOS = 5_000_000_000L;

    private final List<PathPattern> include;

    private final List<PathPattern> exclude;

    private final Environment environment;

    private volatile int managementPort = -1;

    private volatile long nextPortCheck;

    RequestPolicy(LimiterProperties properties, Environment environment) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.include = properties.getIncludePaths().stream().map(parser::parse).toList();
        Set<String> excluded = new LinkedHashSet<>(properties.getExcludePaths());
        String basePath = environment.getProperty("management.endpoints.web.base-path", "/actuator");
        if (!basePath.isEmpty() && !basePath.equals("/")) {
            excluded.add((basePath.startsWith("/") ? basePath : "/" + basePath) + "/**");
        }
        this.exclude = excluded.stream().map(parser::parse).toList();
        this.environment = environment;
        this.nextPortCheck = System.nanoTime();
    }

    /**
     * @param path path within the application (without context path)
     * @param localPort port the request arrived on, or -1 if unknown
     */
    boolean isBypassed(String path, int localPort, String accept, String upgrade) {
        if (localPort > 0 && localPort == managementPort()) {
            return true;
        }
        if (accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
            return true;
        }
        if (upgrade != null && upgrade.toLowerCase(java.util.Locale.ROOT).contains("websocket")) {
            return true;
        }
        PathContainer container = PathContainer.parsePath(path.isEmpty() ? "/" : path);
        for (PathPattern pattern : exclude) {
            if (pattern.matches(container)) {
                return true;
            }
        }
        for (PathPattern pattern : include) {
            if (pattern.matches(container)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The separate management port, if any. Boot publishes {@code local.management.port} once the management
     * server has started (also for {@code management.server.port=0}); until then it is re-read at most every 5 s.
     */
    private int managementPort() {
        int port = managementPort;
        if (port > 0) {
            return port;
        }
        long now = System.nanoTime();
        if (now - nextPortCheck < 0) {
            return -1;
        }
        nextPortCheck = now + PORT_RECHECK_NANOS;
        Integer local = environment.getProperty("local.management.port", Integer.class);
        Integer server = environment.getProperty("local.server.port", Integer.class);
        if (local != null && local > 0 && !local.equals(server)) {
            managementPort = local;
            return local;
        }
        return -1;
    }
}

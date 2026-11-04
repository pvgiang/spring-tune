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

    /** Reactive applications: actuator endpoints are served relative to the application path. */
    RequestPolicy(LimiterProperties properties, Environment environment) {
        this(properties, environment, "");
    }

    /**
     * @param servletPath the {@code DispatcherServlet} path ({@code spring.mvc.servlet.path}); Spring MVC serves
     * actuator endpoints below it, e.g. {@code /api/actuator/health} for {@code /api}
     */
    RequestPolicy(LimiterProperties properties, Environment environment, String servletPath) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.include = properties.getIncludePaths().stream().map(parser::parse).toList();
        Set<String> excluded = new LinkedHashSet<>(properties.getExcludePaths());
        String prefix = trimSlashes(servletPath);
        String basePath = trimSlashes(environment.getProperty("management.endpoints.web.base-path", "/actuator"));
        if (basePath.isEmpty()) {
            // Endpoints at the root (/health, /info, ...): at least never limit the probes.
            String health = trimSlashes(environment.getProperty("management.endpoints.web.path-mapping.health",
                    "health"));
            excluded.add(prefix + (health.isEmpty() ? "/health" : health) + "/**");
        } else {
            excluded.add(prefix + basePath + "/**");
        }
        if (environment.getProperty("management.endpoint.health.probes.add-additional-paths", Boolean.class,
                false)) {
            // Boot serves the liveness and readiness probes on the main port too.
            excluded.add(prefix + "/livez/**");
            excluded.add(prefix + "/readyz/**");
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

    /** {@code "/a/b/"}, {@code "a/b"} → {@code "/a/b"}; {@code null}, {@code ""}, {@code "/"} → {@code ""}. */
    private static String trimSlashes(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
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

package io.github.winky.springtune.check.rules;

import io.github.winky.springtune.check.config.PropertyValue;
import io.github.winky.springtune.check.project.ModuleModel;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Location;
import io.github.winky.springtune.model.Severity;
import java.util.List;

/** ST-TOMCAT-001: far more request threads than connections makes threads queue on the pool. */
public final class TomcatThreadsVsPoolRule extends ModuleRule {

    static final String THREADS_MAX = "server.tomcat.threads.max";
    /** Spring Boot 2.2 and earlier. */
    static final String LEGACY_THREADS_MAX = "server.tomcat.max-threads";
    static final int DEFAULT_THREADS_MAX = 200;
    static final long DEFAULT_MAX_RATIO = 10;

    public TomcatThreadsVsPoolRule() {
        super("ST-TOMCAT-001", "Tomcat threads far above connection pool size", Severity.WARNING, "tomcat",
                "server.tomcat.threads.max exceeds maximumPoolSize x ratio (INFO when both are library defaults).");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        if (!module.usesBootManagedHikari() || !module.usesTomcat() || module.usesVirtualThreads()) {
            return;
        }
        PropertyValue<Integer> threads = module.config().getInt(THREADS_MAX);
        if (threads.isAbsent()) {
            threads = module.config().getInt(LEGACY_THREADS_MAX);
        }
        PropertyValue<Integer> pool = module.config().getInt(Hikari.MAXIMUM_POOL_SIZE);
        if (threads.isUnresolved() || pool.isUnresolved()) {
            return;
        }
        long ratio = options.getLong("max-ratio", DEFAULT_MAX_RATIO);
        int threadCount = threads.orDefault(DEFAULT_THREADS_MAX);
        int poolSize = pool.orDefault(Hikari.DEFAULT_MAXIMUM_POOL_SIZE);
        if (threadCount <= (long) poolSize * ratio) {
            return;
        }
        boolean explicit = threads.isSet() || pool.isSet();
        Location location = threads.isSet() ? threads.location()
                : pool.isSet() ? pool.location() : module.fallbackLocation();
        findings.add(finding(module, explicit ? Severity.WARNING : Severity.INFO, THREADS_MAX,
                "Tomcat may run " + threadCount + " request threads" + (threads.isSet() ? "" : " (default)")
                        + " against " + poolSize + " pooled connections" + (pool.isSet() ? "" : " (default)")
                        + ", a ratio of " + (threadCount / poolSize) + ":1 (threshold " + ratio + ":1).",
                "When requests that need the database outnumber connections, the extra threads block in the pool for "
                        + "connectionTimeout. Under a slowdown all Tomcat threads end up waiting there, and even "
                        + "health checks and endpoints that never touch the database stop answering.",
                "Bound concurrency to what the database can serve: lower server.tomcat.threads.max, or raise the pool "
                        + "within the connection budget (ST-HIKARI-004). Keep connectionTimeout short (ST-HIKARI-001).",
                location));
    }
}

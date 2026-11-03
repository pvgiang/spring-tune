package io.github.winky.springtune.check.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.winky.springtune.check.config.PropertyValue;
import io.github.winky.springtune.check.project.ModuleModel;
import io.github.winky.springtune.check.source.JavaSources;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Severity;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ST-ASYNC-001: {@code @EnableAsync} relies on Spring Boot's auto-configured {@code applicationTaskExecutor},
 * whose queue is unbounded by default (8 core threads; {@code max-size} never takes effect). Before Spring Boot
 * 2.1 there is no auto-configured executor and {@code @Async} starts a new thread per task.
 *
 * <p>The module's own code and its in-repo library dependencies are searched for {@code @Async} methods (no
 * methods, no risk) and for executor beans or an {@code AsyncConfigurer} (then the default is not used).
 */
public final class AsyncDefaultExecutorRule extends ModuleRule {

    static final String QUEUE_CAPACITY = "spring.task.execution.pool.queue-capacity";
    static final Set<String> EXECUTOR_TYPES = Set.of("Executor", "TaskExecutor", "AsyncTaskExecutor",
            "AsyncListenableTaskExecutor", "ThreadPoolTaskExecutor", "ExecutorService", "ThreadPoolExecutor",
            "SimpleAsyncTaskExecutor", "VirtualThreadTaskExecutor", "ConcurrentTaskExecutor");

    public AsyncDefaultExecutorRule() {
        super("ST-ASYNC-001", "@Async on the default unbounded executor", Severity.WARNING, "async",
                "@EnableAsync with @Async methods, no executor bean or AsyncConfigurer, and no"
                        + " spring.task.execution.pool.queue-capacity.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        Optional<JavaSources.Found<AnnotationExpr>> enableAsync = module.sources().findAnnotation("EnableAsync");
        if (enableAsync.isEmpty() || module.usesVirtualThreads() || !module.configFilesKnown()) {
            return;
        }
        List<JavaSources.ParsedSource> reachable = module.reachableSources();
        if (!hasAsyncMethods(reachable) || declaresExecutor(reachable)) {
            return;
        }
        PropertyValue<Integer> capacity = module.config().getInt(QUEUE_CAPACITY);
        if (!capacity.isAbsent()) {
            return;
        }
        if (beforeBoot21(module.build().springBootVersion())) {
            findings.add(finding(module, "@EnableAsync",
                    "@EnableAsync on Spring Boot " + module.build().springBootVersion() + " (before 2.1, which"
                            + " auto-configures no task executor) runs @Async methods on SimpleAsyncTaskExecutor:"
                            + " a new thread for every task, with no limit.",
                    "Under load every async call starts another thread. Thread count, memory and context switching"
                            + " grow with traffic until the JVM runs out of memory or native threads.",
                    "Declare a ThreadPoolTaskExecutor bean named 'taskExecutor' (or implement AsyncConfigurer) with"
                            + " bounded core/max sizes, setQueueCapacity(...) and a rejection policy.",
                    enableAsync.get().location()));
            return;
        }
        findings.add(finding(module, "@EnableAsync",
                "@EnableAsync uses Spring Boot's applicationTaskExecutor with its default unbounded queue: 8 threads"
                        + " run tasks and everything else waits in memory.",
                "With an unbounded queue the pool never grows past its core size, so under load async work piles up "
                        + "without limit: latency grows unbounded, memory fills, and queued tasks are lost on "
                        + "restart. Nothing applies backpressure.",
                "Set " + QUEUE_CAPACITY + " (e.g. 100-1000) together with spring.task.execution.pool.max-size, "
                        + "or declare a ThreadPoolTaskExecutor bean with setQueueCapacity(...) and a rejection policy.",
                enableAsync.get().location()));
    }

    /** {@code true} for literal versions 1.x and 2.0.x; unknown versions are treated as current. */
    static boolean beforeBoot21(String version) {
        return version != null && (version.startsWith("1.") || version.startsWith("2.0."));
    }

    private static boolean hasAsyncMethods(List<JavaSources.ParsedSource> sources) {
        return sources.stream().anyMatch(s -> s.unit().findFirst(AnnotationExpr.class,
                a -> a.getName().getIdentifier().equals("Async")).isPresent());
    }

    private static boolean declaresExecutor(List<JavaSources.ParsedSource> sources) {
        for (JavaSources.ParsedSource source : sources) {
            boolean configurer = source.unit().findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("AsyncConfigurer"))
                            || c.getExtendedTypes().stream()
                                    .anyMatch(t -> t.getNameAsString().equals("AsyncConfigurerSupport")))
                    .isPresent();
            boolean bean = source.unit().findFirst(MethodDeclaration.class,
                    m -> Sources.isBeanMethod(m) && EXECUTOR_TYPES.contains(simpleName(m.getType().asString())))
                    .isPresent();
            if (configurer || bean) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String type) {
        String raw = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        return raw.substring(raw.lastIndexOf('.') + 1);
    }
}

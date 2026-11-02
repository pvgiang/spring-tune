package io.github.pvgiang.springtune.check.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.check.source.JavaSources;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ST-ASYNC-001: {@code @EnableAsync} relies on Spring Boot's auto-configured {@code applicationTaskExecutor},
 * whose queue is unbounded by default (8 core threads; {@code max-size} never takes effect).
 */
public final class AsyncDefaultExecutorRule extends ModuleRule {

    static final String QUEUE_CAPACITY = "spring.task.execution.pool.queue-capacity";
    static final Set<String> EXECUTOR_TYPES = Set.of("Executor", "TaskExecutor", "AsyncTaskExecutor",
            "AsyncListenableTaskExecutor", "ThreadPoolTaskExecutor", "ExecutorService", "ThreadPoolExecutor",
            "SimpleAsyncTaskExecutor", "VirtualThreadTaskExecutor", "ConcurrentTaskExecutor");

    public AsyncDefaultExecutorRule() {
        super("ST-ASYNC-001", "@Async on the default unbounded executor", Severity.WARNING, "async",
                "@EnableAsync with no executor bean and no spring.task.execution.pool.queue-capacity.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        Optional<JavaSources.Found<AnnotationExpr>> enableAsync = module.sources().findAnnotation("EnableAsync");
        if (enableAsync.isEmpty() || module.usesVirtualThreads() || declaresExecutor(module.sources())) {
            return;
        }
        PropertyValue<Integer> capacity = module.config().getInt(QUEUE_CAPACITY);
        if (!capacity.isAbsent()) {
            return;
        }
        findings.add(finding(module, "@EnableAsync",
                "@EnableAsync uses Spring Boot's applicationTaskExecutor with its default unbounded queue: 8 threads"
                        + " run tasks and everything else waits in memory.",
                "With an unbounded queue the pool never grows past its core size, so under load async work piles up "
                        + "without limit: latency grows unbounded, memory fills, and queued tasks are lost on "
                        + "restart. Nothing applies backpressure.",
                "Set " + QUEUE_CAPACITY + " (e.g. 100–1000) together with spring.task.execution.pool.max-size, "
                        + "or declare a ThreadPoolTaskExecutor bean with setQueueCapacity(...) and a rejection policy.",
                enableAsync.get().location()));
    }

    private static boolean declaresExecutor(JavaSources sources) {
        for (JavaSources.ParsedSource source : sources.all()) {
            boolean configurer = source.unit().findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().equals("AsyncConfigurer")))
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

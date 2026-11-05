package io.github.winkytek.springtune.check.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.github.winkytek.springtune.check.project.ModuleModel;
import io.github.winkytek.springtune.check.source.JavaSources;
import io.github.winkytek.springtune.model.Finding;
import io.github.winkytek.springtune.model.Severity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ST-ASYNC-002: executors created in code with an unbounded queue or unbounded thread count. */
public final class UnboundedExecutorRule extends ModuleRule {

    private static final Map<String, String> FACTORIES = Map.of(
            "newFixedThreadPool", "an unbounded LinkedBlockingQueue",
            "newSingleThreadExecutor", "an unbounded LinkedBlockingQueue",
            "newCachedThreadPool", "an unbounded number of threads");
    private static final Set<String> UNBOUNDED_QUEUES = Set.of("LinkedBlockingQueue", "LinkedBlockingDeque");
    private static final String WHY = "Without a bound, a slow downstream turns into unlimited queued work (memory "
            + "growth, unbounded latency, lost tasks on restart) or unlimited threads (CPU and memory exhaustion). "
            + "Nothing pushes back on the caller.";

    public UnboundedExecutorRule() {
        super("ST-ASYNC-002", "Executor with unbounded queue or threads", Severity.WARNING, "async",
                "ThreadPoolTaskExecutor without setQueueCapacity, Executors.newFixed/Single/CachedThreadPool, or "
                        + "ThreadPoolExecutor with an unbounded queue.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        for (JavaSources.ParsedSource source : module.sources().all()) {
            for (ObjectCreationExpr creation : source.unit().findAll(ObjectCreationExpr.class)) {
                String type = creation.getType().getNameAsString();
                if (type.equals("ThreadPoolTaskExecutor")
                        && !Sources.callsAny(Sources.scopeOf(creation), Set.of("setQueueCapacity"))) {
                    findings.add(finding(module, Sources.owner(creation) + ":ThreadPoolTaskExecutor",
                            "ThreadPoolTaskExecutor is created without setQueueCapacity(...); its default queue "
                                    + "capacity is Integer.MAX_VALUE, so maxPoolSize never takes effect.",
                            WHY, "Call setQueueCapacity(n) with a bound sized to acceptable wait time, set "
                                    + "maxPoolSize, and choose a RejectedExecutionHandler (e.g. CallerRunsPolicy).",
                            source.locationOf(creation)));
                } else if (type.equals("ThreadPoolExecutor") && creation.getArguments().stream()
                        .anyMatch(UnboundedExecutorRule::isUnboundedQueue)) {
                    findings.add(finding(module, Sources.owner(creation) + ":ThreadPoolExecutor",
                            "ThreadPoolExecutor is created with a LinkedBlockingQueue/Deque without capacity.",
                            WHY, "Pass a capacity, e.g. new ArrayBlockingQueue<>(n) or new LinkedBlockingQueue<>(n), "
                                    + "and a RejectedExecutionHandler.",
                            source.locationOf(creation)));
                }
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                String why = FACTORIES.get(call.getNameAsString());
                if (why != null && call.getScope().filter(s -> Sources.isTypeReference(s, "Executors")).isPresent()) {
                    findings.add(finding(module, Sources.owner(call) + ":Executors." + call.getNameAsString(),
                            "Executors." + call.getNameAsString() + "(...) creates a pool with " + why + ".",
                            WHY, "Build a ThreadPoolExecutor (or ThreadPoolTaskExecutor) with a bounded queue, a "
                                    + "maximum thread count and a rejection policy.",
                            source.locationOf(call)));
                }
            }
        }
    }

    private static boolean isUnboundedQueue(Expression argument) {
        return argument instanceof ObjectCreationExpr queue
                && UNBOUNDED_QUEUES.contains(queue.getType().getNameAsString())
                && queue.getArguments().isEmpty();
    }
}

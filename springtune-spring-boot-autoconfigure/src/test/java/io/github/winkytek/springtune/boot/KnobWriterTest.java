package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Writers against real components: Spring's ThreadPoolTaskExecutor and a started HikariCP pool on H2. */
class KnobWriterTest {

    private static ThreadPoolTaskExecutor executor(int core, int max, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    @Test
    void wrongOrderIsWhatTheWriterAvoids() {
        ThreadPoolTaskExecutor executor = executor(2, 4, Integer.MAX_VALUE);
        try {
            assertThatIllegalArgumentException().isThrownBy(() -> executor.getThreadPoolExecutor().setCorePoolSize(6));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void unboundedQueueCoreSizeGrowsAndShrinksAcrossTheMaximum() {
        ThreadPoolTaskExecutor executor = executor(2, 4, Integer.MAX_VALUE);
        try {
            ExecutorPoolWriter writer = new ExecutorPoolWriter(executor, ExecutorPoolWriter.CORE);
            assertThat(writer.plan(3)).containsExactly(Map.entry("corePoolSize", 3L));
            Map<String, Long> before = writer.read();
            Map<String, Long> grow = writer.plan(6);
            assertThat(grow).containsExactly(Map.entry("corePoolSize", 6L), Map.entry("maxPoolSize", 6L));
            writer.write(grow); // max first, then core
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertThat(pool.getCorePoolSize()).isEqualTo(6);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(6);
            writer.write(before); // core first, then max
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
            assertThat(writer.read()).containsExactly(Map.entry("corePoolSize", 2L), Map.entry("maxPoolSize", 4L));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void boundedQueueMaxSizeShrinksBelowAndGrowsBackAboveTheCore() {
        ThreadPoolTaskExecutor executor = executor(4, 8, 10);
        try {
            ExecutorPoolWriter writer = new ExecutorPoolWriter(executor, ExecutorPoolWriter.MAX);
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            writer.write(writer.plan(12));
            assertThat(pool.getMaximumPoolSize()).isEqualTo(12);
            assertThat(pool.getCorePoolSize()).isEqualTo(4);

            Map<String, Long> shrink = writer.plan(2);
            assertThat(shrink).containsExactly(Map.entry("maxPoolSize", 2L), Map.entry("corePoolSize", 2L));
            writer.write(shrink); // core first, then max
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);

            writer.write(Map.of("maxPoolSize", 8L, "corePoolSize", 4L)); // max first, then core
            assertThat(pool.getCorePoolSize()).isEqualTo(4);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(8);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectionCounterKeepsTheOriginalPolicy() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            RejectionCounter counter = RejectionCounter.install(executor.getThreadPoolExecutor());
            assertThat(RejectionCounter.install(executor.getThreadPoolExecutor())).isSameAs(counter);
            assertThat(counter.delegate()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            executor.execute(() -> await(release));
            executor.execute(() -> await(release));
            assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(TaskRejectedException.class);
            assertThat(counter.rejected()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void hikariMinimumIdleMovesWithAFixedPoolBothWays() throws Exception {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:h2:mem:writer;DB_CLOSE_DELAY=-1");
            dataSource.setMaximumPoolSize(4);
            dataSource.getConnection().close(); // start the pool: minimumIdle defaults to the maximum
            HikariPoolWriter writer = new HikariPoolWriter(dataSource::getHikariConfigMXBean);
            assertThat(writer.read()).containsExactly(Map.entry("maximumPoolSize", 4L), Map.entry("minimumIdle", 4L));
            Map<String, Long> grow = writer.plan(6);
            assertThat(grow).containsExactly(Map.entry("maximumPoolSize", 6L), Map.entry("minimumIdle", 6L));
            writer.write(grow);
            assertThat(dataSource.getHikariConfigMXBean().getMaximumPoolSize()).isEqualTo(6);
            assertThat(dataSource.getHikariConfigMXBean().getMinimumIdle()).isEqualTo(6);
            writer.write(Map.of("maximumPoolSize", 4L, "minimumIdle", 4L));
            assertThat(writer.read()).containsExactly(Map.entry("maximumPoolSize", 4L), Map.entry("minimumIdle", 4L));

            // A dynamic pool keeps its minimumIdle unless the new maximum drops below it.
            dataSource.getHikariConfigMXBean().setMinimumIdle(2);
            assertThat(writer.plan(5)).containsExactly(Map.entry("maximumPoolSize", 5L));
            assertThat(writer.plan(1)).containsExactly(Map.entry("maximumPoolSize", 1L), Map.entry("minimumIdle", 1L));
        }
    }
}

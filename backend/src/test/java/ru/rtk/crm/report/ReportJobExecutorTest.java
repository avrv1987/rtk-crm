package ru.rtk.crm.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class ReportJobExecutorTest {
    @Test
    void runsAtMostTenReportsAtOnceAndRejectsBeyondBoundedQueue() throws InterruptedException {
        ReportJobExecutor executor = new ReportJobExecutor(new ReportProperties(Path.of("unused"), 10, 2, 1, 1));
        CountDownLatch tenStarted = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(12);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        Runnable report = () -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            tenStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
                finished.countDown();
            }
        };
        try {
            for (int index = 0; index < 12; index++) {
                executor.execute(report);
            }
            assertThat(tenStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(running.get()).isEqualTo(10);
            assertThatThrownBy(() -> executor.execute(report)).isInstanceOf(TaskRejectedException.class);

            release.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(maxRunning.get()).isEqualTo(10);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}

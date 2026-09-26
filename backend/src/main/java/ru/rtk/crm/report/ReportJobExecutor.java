package ru.rtk.crm.report;

import jakarta.annotation.PreDestroy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ReportJobExecutor {
    private final ThreadPoolTaskExecutor executor;

    public ReportJobExecutor(ReportProperties properties) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.slots());
        executor.setMaxPoolSize(properties.slots());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("report-");
        executor.initialize();
    }

    public void execute(Runnable task) throws TaskRejectedException {
        executor.execute(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}

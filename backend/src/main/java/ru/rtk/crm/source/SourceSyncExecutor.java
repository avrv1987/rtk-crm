package ru.rtk.crm.source;

import jakarta.annotation.PreDestroy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class SourceSyncExecutor {
    private final ThreadPoolTaskExecutor executor;

    public SourceSyncExecutor(SourceProperties properties) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.slots());
        executor.setMaxPoolSize(properties.slots());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("source-sync-");
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

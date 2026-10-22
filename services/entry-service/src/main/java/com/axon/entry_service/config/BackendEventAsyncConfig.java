package com.axon.entry_service.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class BackendEventAsyncConfig {

    public static final String BACKEND_EVENT_TASK_EXECUTOR = "backendEventTaskExecutor";

    @Bean(name = BACKEND_EVENT_TASK_EXECUTOR)
    public Executor backendEventTaskExecutor(
            @Value("${axon.backend-event.executor.core-size:2}") int coreSize,
            @Value("${axon.backend-event.executor.max-size:2}") int maxSize,
            @Value("${axon.backend-event.executor.queue-capacity:1000}") int queueCapacity,
            @Value("${axon.backend-event.executor.thread-name-prefix:backend-event-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}

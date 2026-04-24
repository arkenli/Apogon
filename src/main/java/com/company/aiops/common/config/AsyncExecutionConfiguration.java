package com.company.aiops.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated executor for analysis jobs so webhook threads only enqueue work and return.
 */
@Configuration
@EnableAsync
public class AsyncExecutionConfiguration {

    @Bean(name = "analysisJobExecutor")
    public Executor analysisJobExecutor(AiopsProperties aiopsProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("analysis-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) aiopsProperties.analysis().asyncTimeout().toSeconds());
        executor.initialize();
        return executor;
    }
}

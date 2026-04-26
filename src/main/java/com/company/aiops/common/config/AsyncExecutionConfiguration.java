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

    /**
     * Provider invocations need their own pool because the orchestrator may wait on several
     * providers in parallel while the analysis worker thread keeps ownership of the task lifecycle.
     */
    @Bean(name = "providerExecutor")
    public ThreadPoolTaskExecutor providerExecutor(AiopsProperties aiopsProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("provider-call-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) aiopsProperties.analysis().asyncTimeout().toSeconds());
        executor.initialize();
        return executor;
    }

    /**
     * Orchestrator scheduling should also stay off the JDK common pool. These wrapper tasks wait on
     * provider futures and therefore use a dedicated coordination pool instead of borrowing request
     * threads or unrelated shared executors.
     */
    @Bean(name = "providerOrchestratorExecutor")
    public ThreadPoolTaskExecutor providerOrchestratorExecutor(AiopsProperties aiopsProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("provider-orchestrator-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) aiopsProperties.analysis().asyncTimeout().toSeconds());
        executor.initialize();
        return executor;
    }
}

package com.company.aiops.provider.orchestrator;

import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.persistence.repository.ProviderCallLogRepository;
import com.company.aiops.provider.model.ProviderCallLog;
import com.company.aiops.provider.model.ProviderResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Executes one provider call with timeout, short retry, and audit logging.
 */
@Component
public class ProviderCallExecutor {

    private final AiopsProperties aiopsProperties;
    private final ProviderCallLogRepository providerCallLogRepository;
    private final ThreadPoolTaskExecutor providerExecutor;

    public ProviderCallExecutor(
            AiopsProperties aiopsProperties,
            ProviderCallLogRepository providerCallLogRepository,
            @Qualifier("providerExecutor") ThreadPoolTaskExecutor providerExecutor
    ) {
        this.aiopsProperties = aiopsProperties;
        this.providerCallLogRepository = providerCallLogRepository;
        this.providerExecutor = providerExecutor;
    }

    /**
     * The supplier runs on the dedicated provider pool so the caller can enforce per-call timeout
     * and still continue orchestrating other providers.
     */
    public <T> ProviderResult<T> execute(
            String taskId,
            String alertId,
            String providerName,
            boolean critical,
            Callable<T> operation
    ) {
        long startNanos = System.nanoTime();
        ProviderResult<T> finalResult = null;
        int maxAttempts = Math.max(1, aiopsProperties.provider().retryTimes() + 1);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<T> future = providerExecutor.submit(operation);
            try {
                T data = future.get(aiopsProperties.provider().timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                finalResult = ProviderResult.success(providerName, data, elapsed(startNanos));
                break;
            } catch (TimeoutException exception) {
                future.cancel(true);
                finalResult = ProviderResult.timeout(
                        providerName,
                        "provider call timed out after %d ms on attempt %d/%d"
                                .formatted(aiopsProperties.provider().timeout().toMillis(), attempt, maxAttempts),
                        elapsed(startNanos)
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                finalResult = ProviderResult.failure(
                        providerName,
                        "INTERRUPTED",
                        "provider call interrupted while waiting for completion",
                        elapsed(startNanos)
                );
                break;
            } catch (ExecutionException exception) {
                Throwable rootCause = unwrap(exception);
                finalResult = ProviderResult.failure(
                        providerName,
                        "PROVIDER_EXECUTION_ERROR",
                        summarize(rootCause),
                        elapsed(startNanos)
                );
            }

            if (attempt < maxAttempts) {
                sleepBeforeRetry();
            }
        }

        ProviderResult<T> result = finalResult == null
                ? ProviderResult.failure(providerName, "PROVIDER_EXECUTION_ERROR", "provider call failed", elapsed(startNanos))
                : finalResult;
        recordCall(taskId, alertId, critical, result);
        return result;
    }

    public <T> ProviderResult<T> skipped(
            String taskId,
            String alertId,
            String providerName,
            boolean critical,
            String reason
    ) {
        ProviderResult<T> result = ProviderResult.skipped(providerName, reason);
        recordCall(taskId, alertId, critical, result);
        return result;
    }

    private void recordCall(String taskId, String alertId, boolean critical, ProviderResult<?> result) {
        providerCallLogRepository.save(new ProviderCallLog(
                taskId,
                alertId,
                result.providerName(),
                result.status(),
                critical,
                result.latency(),
                result.errorCode(),
                result.errorMessage(),
                Instant.now()
        ));
    }

    private void sleepBeforeRetry() {
        long millis = aiopsProperties.provider().retryInterval().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private Throwable unwrap(ExecutionException exception) {
        return exception.getCause() == null ? exception : exception.getCause();
    }

    private String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}

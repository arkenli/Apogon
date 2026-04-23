package com.company.aiops.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "aiops")
public record AiopsProperties(
        @Valid @NotNull Analysis analysis,
        @Valid @NotNull Provider provider,
        @Valid @NotNull Llm llm,
        @Valid @NotNull Thresholds thresholds
) {

    public record Analysis(
            @NotNull Duration dedupeWindow,
            @NotNull Duration historicalReferenceLookback,
            @Min(1) int evidenceRetentionDays,
            @Min(1) int llmLogRetentionDays,
            @NotNull Duration asyncTimeout,
            @Min(0) int limitedImpactRequestThreshold,
            @Min(1) int baselineOffsetDays,
            @NotNull Duration baselineCompareDuration,
            @Min(1) int maxInterfaceLogLimit,
            @Min(1) int maxTraceSampleSize,
            @Min(1) int maxSlowTraceSampleSize,
            @Min(1) int maxRuntimeLogSize,
            @Min(1) int runtimeLogMessageMaxChars,
            @Min(1) int maxErrorCodeSize,
            @Min(1) int maxUidSize
    ) {
    }

    public record Provider(
            @NotNull Duration timeout,
            @Min(0) int retryTimes,
            @NotNull Duration retryInterval,
            boolean parallelEnabled,
            boolean continueOnNonCriticalFailure
    ) {
    }

    public record Llm(
            boolean enabled,
            @NotBlank String provider,
            @NotBlank String model,
            @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
            @Min(1) int maxOutputTokens,
            @NotNull Duration timeout,
            boolean fallbackEnabled,
            @NotBlank String fallbackRuleEngineVersion
    ) {
    }

    public record Thresholds(
            @DecimalMin("0.0") @DecimalMax("1.0") double containerCpuHigh,
            @DecimalMin("0.0") @DecimalMax("1.0") double containerMemoryHigh,
            @DecimalMin("0.0") @DecimalMax("1.0") double cseUsageHigh,
            @DecimalMin("0.0") @DecimalMax("1.0") double dbCpuHigh,
            @DecimalMin("0.0") @DecimalMax("1.0") double dbConnectionHigh,
            @DecimalMin("0.0") @DecimalMax("1.0") double dcsCpuHigh,
            @DecimalMin("0.0") @DecimalMax("1.0") double dcsMemoryHigh
    ) {
    }
}

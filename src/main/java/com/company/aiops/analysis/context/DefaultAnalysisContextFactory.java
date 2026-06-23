package com.company.aiops.analysis.context;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.provider.model.ResourceMapping;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Converts alert fields and application properties into explicit alarm/baseline windows so later
 * phases do not duplicate time-window rules.
 */
@Component
public class DefaultAnalysisContextFactory implements AnalysisContextFactory {

    private static final Duration ERROR_RATE_LOOKBACK = Duration.ofMinutes(5);
    private static final Duration LATENCY_LOOKBACK = Duration.ofMinutes(10);
    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofMinutes(2);

    private final AiopsProperties aiopsProperties;

    public DefaultAnalysisContextFactory(AiopsProperties aiopsProperties) {
        this.aiopsProperties = aiopsProperties;
    }

    @Override
    public AnalysisContext create(AlertEvent alertEvent) {
        TimeWindow alarmWindow = buildAlarmWindow(alertEvent);
        TimeWindow baselineWindow = buildBaselineWindow(alertEvent);
        AnalysisOptions options = new AnalysisOptions(
                aiopsProperties.analysis().maxInterfaceLogLimit(),
                aiopsProperties.analysis().maxTraceSampleSize(),
                aiopsProperties.analysis().maxSlowTraceSampleSize(),
                aiopsProperties.analysis().maxRuntimeLogSize(),
                aiopsProperties.analysis().runtimeLogMessageMaxChars(),
                aiopsProperties.analysis().maxErrorCodeSize(),
                aiopsProperties.analysis().maxUidSize(),
                aiopsProperties.analysis().errorLogMessageQueryMaxAttempts(),
                aiopsProperties.analysis().errorLogMessageQueryMinChars(),
                aiopsProperties.analysis().errorLogMessageQueryMinTokens(),
                aiopsProperties.analysis().errorLogQueryLevel(),
                aiopsProperties.llm().enabled(),
                aiopsProperties.provider().parallelEnabled()
        );
        return new AnalysisContext(
                alertEvent,
                alarmWindow,
                baselineWindow,
                ResourceMapping.empty(alertEvent.serviceName(), alertEvent.cluster()),
                options
        );
    }

    private TimeWindow buildAlarmWindow(AlertEvent alertEvent) {
        Duration lookback = switch (alertEvent.category()) {
            case RPC_ERROR_RATE, SERVICE_ERROR_LOG -> ERROR_RATE_LOOKBACK;
            case RPC_LATENCY -> LATENCY_LOOKBACK;
            case UNKNOWN -> ERROR_RATE_LOOKBACK;
        };
        return new TimeWindow(
                alertEvent.metricOccurTime().minus(lookback),
                alertEvent.metricOccurTime().plus(DEFAULT_LOOKAHEAD)
        );
    }

    private TimeWindow buildBaselineWindow(AlertEvent alertEvent) {
        Duration halfCompareDuration = aiopsProperties.analysis().baselineCompareDuration().dividedBy(2);
        return new TimeWindow(
                alertEvent.metricOccurTime()
                        .minus(halfCompareDuration)
                        .minus(Duration.ofDays(aiopsProperties.analysis().baselineOffsetDays())),
                alertEvent.metricOccurTime()
                        .plus(halfCompareDuration)
                        .minus(Duration.ofDays(aiopsProperties.analysis().baselineOffsetDays()))
        );
    }
}

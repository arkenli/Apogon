package com.company.aiops.analysis.context;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.common.config.AiopsProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAnalysisContextFactoryTests {

    @Test
    void createsExpectedWindowsAndOptionsForErrorRateAlerts() {
        DefaultAnalysisContextFactory factory = new DefaultAnalysisContextFactory(defaultProperties(true));
        AlertEvent alert = alert(AlertCategory.RPC_ERROR_RATE, Instant.parse("2026-04-21T02:33:00Z"));

        AnalysisContext context = factory.create(alert);

        assertThat(context.alarmWindow().startTime()).isEqualTo(Instant.parse("2026-04-21T02:28:00Z"));
        assertThat(context.alarmWindow().endTime()).isEqualTo(Instant.parse("2026-04-21T02:35:00Z"));
        assertThat(context.baselineWindow().startTime()).isEqualTo(Instant.parse("2026-04-20T02:28:00Z"));
        assertThat(context.baselineWindow().endTime()).isEqualTo(Instant.parse("2026-04-20T02:38:00Z"));
        assertThat(context.resourceMapping().serviceName()).isEqualTo("demo-service");
        assertThat(context.resourceMapping().resources()).isEmpty();
        assertThat(context.options().maxInterfaceLogLimit()).isEqualTo(2000);
        assertThat(context.options().providerParallelEnabled()).isTrue();
    }

    @Test
    void createsExpectedWindowsForLatencyAlerts() {
        DefaultAnalysisContextFactory factory = new DefaultAnalysisContextFactory(defaultProperties(false));
        AlertEvent alert = alert(AlertCategory.RPC_LATENCY, Instant.parse("2026-04-21T02:33:00Z"));

        AnalysisContext context = factory.create(alert);

        assertThat(context.alarmWindow().startTime()).isEqualTo(Instant.parse("2026-04-21T02:23:00Z"));
        assertThat(context.alarmWindow().endTime()).isEqualTo(Instant.parse("2026-04-21T02:35:00Z"));
        assertThat(context.options().providerParallelEnabled()).isFalse();
    }

    @Test
    void createUsesServiceErrorLogWindowAndMessageQueryOptions() {
        DefaultAnalysisContextFactory factory = new DefaultAnalysisContextFactory(defaultProperties(true));
        AlertEvent alert = serviceErrorLogAlert();

        AnalysisContext context = factory.create(alert);

        assertThat(context.alarmWindow().startTime()).isEqualTo(Instant.parse("2026-06-22T05:37:00Z"));
        assertThat(context.alarmWindow().endTime()).isEqualTo(Instant.parse("2026-06-22T05:44:00Z"));
        assertThat(context.options().errorLogMessageQueryMaxAttempts()).isEqualTo(5);
        assertThat(context.options().errorLogMessageQueryMinChars()).isEqualTo(8);
        assertThat(context.options().errorLogMessageQueryMinTokens()).isEqualTo(2);
        assertThat(context.options().errorLogQueryLevel()).isEqualTo("ERROR");
    }

    private AiopsProperties defaultProperties(boolean parallelEnabled) {
        return new AiopsProperties(
                new AiopsProperties.Analysis(
                        Duration.ofMinutes(5),
                        Duration.ofDays(1),
                        30,
                        30,
                        Duration.ofMinutes(3),
                        10,
                        1,
                        Duration.ofMinutes(10),
                        2000,
                        20,
                        20,
                        50,
                        500,
                        10,
                        10,
                        5,
                        8,
                        2,
                        "ERROR",
                        5
                ),
                new AiopsProperties.Provider(
                        Duration.ofSeconds(1),
                        1,
                        Duration.ofMillis(10),
                        parallelEnabled,
                        true
                ),
                new AiopsProperties.Llm(
                        true,
                        "mock",
                        "mock-model",
                        0.1,
                        1000,
                        Duration.ofSeconds(10),
                        true,
                        "fallback-rules-v1",
                        null,
                        null
                ),
                new AiopsProperties.Thresholds(0.8, 0.85, 0.8, 0.8, 0.8, 0.8, 0.85)
        );
    }

    private AlertEvent alert(AlertCategory category, Instant occurTime) {
        return new AlertEvent(
                "alert-" + category.name(),
                category,
                "OPEN",
                "MAJOR",
                "demo-service",
                "demo.Interface.query",
                "cluster1",
                "cn_product_default",
                "tenant",
                "application",
                "demo alert",
                category == AlertCategory.RPC_LATENCY ? "rpc_latency" : "rpc_error_rate",
                category == AlertCategory.RPC_LATENCY ? "AIOps@delay" : "AIOps@rate",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(20.0, 1.0, 3000L, null, null, null, null, null, null, "demo", "raw")
        );
    }

    private AlertEvent serviceErrorLogAlert() {
        Instant occurTime = Instant.parse("2026-06-22T05:42:00Z");
        return new AlertEvent(
                "alert-service-error-log",
                AlertCategory.SERVICE_ERROR_LOG,
                "OPEN",
                "MAJOR",
                "ams_front",
                null,
                "cluster2",
                "cn_product_default",
                "tenant",
                "application",
                "CP域服务业务错误日志告警",
                "CP_all_all_sevice_running_error_activity",
                "AIOps@count",
                "raw alert",
                occurTime,
                occurTime,
                new AlertTriggerValues(
                        null,
                        null,
                        null,
                        null,
                        null,
                        2.0,
                        "ERROR",
                        "DEFAULT prebook loginAppointment failed",
                        "prebook loginAppointment failed",
                        "failureCount=2",
                        "DEFAULT prebook loginAppointment failed"
                )
        );
    }
}

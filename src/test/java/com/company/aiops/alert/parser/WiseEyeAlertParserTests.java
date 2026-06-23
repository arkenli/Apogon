package com.company.aiops.alert.parser;

import com.company.aiops.alert.classifier.DefaultAlertClassifier;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WiseEyeAlertParserTests {

    private final WiseEyeAlertParser parser = new WiseEyeAlertParser(new ObjectMapper());
    private final DefaultAlertClassifier classifier = new DefaultAlertClassifier();

    @Test
    void parsesServiceErrorLogWhenLogFieldPrecedesOtherMetricsOnSameLine() {
        String rawAlert = AlertSamples.SERVICE_ERROR_LOG_ALERT.replace(
                "失败数=2,发生时间=2026-06-22T13:42:00, 日志=DEFAULT prebook loginAppointment failed",
                "日志=DEFAULT prebook loginAppointment failed,发生时间=2026-06-22T13:42:00,失败数=2"
        );

        AlertEvent parsed = parser.parse(rawAlert);
        AlertEvent alert = parsed.withCategory(classifier.classify(parsed));

        assertThat(alert.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
        assertThat(alert.triggerValues().logMessage()).isEqualTo("DEFAULT prebook loginAppointment failed");
        assertThat(alert.triggerValues().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
        assertThat(alert.triggerValues().failureCount()).isEqualTo(2.0);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-06-22T05:42:00Z"));
    }

    @Test
    void parsesServiceErrorLogWhenFieldsAreSeparatedByChineseCommas() {
        String rawAlert = AlertSamples.SERVICE_ERROR_LOG_ALERT.replace(
                "失败数=2,发生时间=2026-06-22T13:42:00, 日志=DEFAULT prebook loginAppointment failed",
                "日志=DEFAULT prebook loginAppointment failed，发生时间=2026-06-22T13:42:00，失败数=2"
        );

        AlertEvent parsed = parser.parse(rawAlert);
        AlertEvent alert = parsed.withCategory(classifier.classify(parsed));

        assertThat(alert.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
        assertThat(alert.triggerValues().logMessage()).isEqualTo("DEFAULT prebook loginAppointment failed");
        assertThat(alert.triggerValues().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
        assertThat(alert.triggerValues().failureCount()).isEqualTo(2.0);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-06-22T05:42:00Z"));
    }
}

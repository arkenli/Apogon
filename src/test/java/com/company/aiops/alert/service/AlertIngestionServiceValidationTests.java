package com.company.aiops.alert.service;

import com.company.aiops.alert.classifier.DefaultAlertClassifier;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.alert.parser.WiseEyeAlertParser;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

class AlertIngestionServiceValidationTests {

    private final WiseEyeAlertParser parser = new WiseEyeAlertParser(new ObjectMapper());
    private final DefaultAlertClassifier classifier = new DefaultAlertClassifier();

    @Test
    void serviceErrorLogValidationAllowsMissingInterfaceNameWhenLogQueryExists() {
        AlertEvent parsed = parser.parse(AlertSamples.SERVICE_ERROR_LOG_ALERT);
        AlertEvent alert = parsed.withCategory(classifier.classify(parsed));

        assertThatCode(() -> validate(alert))
                .doesNotThrowAnyException();
    }

    @Test
    void serviceErrorLogValidationRejectsRawMetricValueTextWithoutLogQuery() {
        AlertEvent alert = serviceErrorLogAlert(new AlertTriggerValues(
                null,
                null,
                null,
                null,
                null,
                2.0,
                "ERROR",
                null,
                null,
                null,
                "failureCount=2"
        ));

        assertThatCode(() -> validate(alert))
                .isInstanceOf(AiopsException.class)
                .hasMessageContaining("log query text");
    }

    private void validate(AlertEvent alert) {
        AlertIngestionService.validateAlert(alert);
    }

    private AlertEvent serviceErrorLogAlert(AlertTriggerValues triggerValues) {
        Instant now = Instant.parse("2026-06-22T05:42:00Z");
        return new AlertEvent(
                "alert-log",
                AlertCategory.SERVICE_ERROR_LOG,
                "OPEN",
                "Major",
                "ams_front",
                null,
                "cluster2",
                "cn_product_default",
                "tenant",
                "application",
                "service error log alert",
                "CP_all_all_sevice_running_error_activity",
                "AIOps@count",
                "raw",
                now,
                now,
                triggerValues
        );
    }
}

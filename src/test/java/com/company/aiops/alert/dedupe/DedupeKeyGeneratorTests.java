package com.company.aiops.alert.dedupe;

import com.company.aiops.alert.classifier.DefaultAlertClassifier;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.alert.parser.WiseEyeAlertParser;
import com.company.aiops.support.AlertSamples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DedupeKeyGeneratorTests {

    private final WiseEyeAlertParser parser = new WiseEyeAlertParser(new ObjectMapper());
    private final DefaultAlertClassifier classifier = new DefaultAlertClassifier();
    private final DedupeKeyGenerator generator = new DedupeKeyGenerator();

    @Test
    void serviceErrorLogKeyUsesServiceClusterAndLogSignature() {
        AlertEvent alert = classified(AlertSamples.SERVICE_ERROR_LOG_ALERT);

        assertThat(generator.generate(alert))
                .isEqualTo("SERVICE_ERROR_LOG|ams_front|cluster2|prebook loginappointment failed");
    }

    @Test
    void rpcErrorRateKeyKeepsOriginalShape() {
        AlertEvent alert = classified(AlertSamples.RATE_ALERT);

        assertThat(generator.generate(alert))
                .isEqualTo("RPC_ERROR_RATE|ams_front|vmall-ams.PrizeActivityService.queryPrizeResult|cluster1");
    }

    @Test
    void serviceErrorLogSignatureKeepsStandaloneBusinessWords() {
        AlertEvent alert = serviceErrorLogAlert(new AlertTriggerValues(
                null,
                null,
                null,
                null,
                null,
                1.0,
                "ERROR",
                "trace checkout failed span uid",
                "trace checkout failed span uid",
                null,
                null
        ));

        assertThat(generator.generate(alert))
                .isEqualTo("SERVICE_ERROR_LOG|ams_front|cluster2|trace checkout failed span uid");
    }

    @Test
    void serviceErrorLogSignatureRemovesDynamicKeyValueTokens() {
        AlertEvent alert = serviceErrorLogAlert(new AlertTriggerValues(
                null,
                null,
                null,
                null,
                null,
                1.0,
                "ERROR",
                "traceId abc trace_id:def uid=123 spanId xyz checkout failed 42",
                "traceId abc trace_id:def uid=123 spanId xyz checkout failed 42",
                null,
                null
        ));

        assertThat(generator.generate(alert))
                .isEqualTo("SERVICE_ERROR_LOG|ams_front|cluster2|checkout failed");
    }

    private AlertEvent classified(String rawAlert) {
        AlertEvent parsed = parser.parse(rawAlert);
        return parsed.withCategory(classifier.classify(parsed));
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
                "CP域服务业务错误日志告警",
                "CP_all_all_sevice_running_error_activity",
                "AIOps@count",
                "raw",
                now,
                now,
                triggerValues
        );
    }
}

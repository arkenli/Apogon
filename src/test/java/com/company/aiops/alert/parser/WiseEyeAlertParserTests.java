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
        assertThat(alert.triggerValues().logMessage()).isEqualTo("prebook loginAppointment failed");
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
        assertThat(alert.triggerValues().logMessage()).isEqualTo("prebook loginAppointment failed");
        assertThat(alert.triggerValues().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
        assertThat(alert.triggerValues().failureCount()).isEqualTo(2.0);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-06-22T05:42:00Z"));
    }

    @Test
    void parsesSingleLineServiceErrorLogWithoutSwallowingDashboardAndMetadata() {
        String rawAlert = """
                CP_all_all_sevice_running_error_activity 1、告警名称：CP域服务业务错误日志告警 2、告警对象：服务名称：ams_front,集群信息：cluster2 3、告警信息：失败数=2,发生时间=2026-06-22T13:42:00, 日志=DEFAULT prebook loginAppointment failed 4、看板跳转 https://console-drcn.wisedevops.huawei.com/microApp/serviceInsight/aiops5Dashboard#/edit-dashboard?id=9dabb7e8-db11-417a-ade0-a988ad4df9e8 AlertName: CP域服务业务错误日志告警,Site: cn_product_default,MetricName: CP_all_all_sevice_running_error_activity,Category: AIOps@count | alert_id: 554631819 | Source_ID: 5ba509babfa344978b9176d1b6602581
                """;

        AlertEvent parsed = parser.parse(rawAlert);
        AlertEvent alert = parsed.withCategory(classifier.classify(parsed));

        assertThat(alert.alertId()).isEqualTo("554631819");
        assertThat(alert.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
        assertThat(alert.serviceName()).isEqualTo("ams_front");
        assertThat(alert.cluster()).isEqualTo("cluster2");
        assertThat(alert.rawCategory()).isEqualTo("AIOps@count");
        assertThat(alert.triggerValues().logMessage()).isEqualTo("prebook loginAppointment failed");
        assertThat(alert.triggerValues().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
        assertThat(alert.triggerValues().failureCount()).isEqualTo(2.0);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-06-22T05:42:00Z"));
    }
}

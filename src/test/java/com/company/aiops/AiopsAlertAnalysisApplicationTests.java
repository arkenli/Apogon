package com.company.aiops;

import com.company.aiops.alert.classifier.AlertClassifier;
import com.company.aiops.alert.dedupe.DedupeKeyGenerator;
import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.alert.parser.AlertParser;
import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.job.AnalysisJobStatus;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.persistence.repository.AlertEventRepository;
import com.company.aiops.persistence.repository.AnalysisResultRepository;
import com.company.aiops.persistence.repository.AnalysisTaskRepository;
import com.company.aiops.provider.model.ResourceMapping;
import com.company.aiops.provider.resource.ResourceMappingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiopsAlertAnalysisApplicationTests {

    private static final String RATE_ALERT = """
            WiseEye Alarm:
            Status: OPEN
            Severity: Major
            Host: CP_all_all_sevice_failrate_over70_ams:{"servicename":"ams_front","interfacename":"vmall-ams.PrizeActivityService.queryPrizeResult","cluster":"cluster1"}
            Category: AIOps@rate
            Details: CP_all_all_sevice_failrate_over70_ams 1、告警名称：CP域服务接口错误率连续1分钟大于70%
            2、告警对象：服务名称：ams_front,接口：vmall-ams.PrizeActivityService.queryPrizeResult,集群：cluster1
            3、告警信息：请求总数=3.0,失败率=100%, 发生时间=2026-04-20T18:09:00
            4、诊断信息：chat analysis vmall-service-errors-delimit-analysis serviceName='ams_front' interfaceName='vmall-ams.PrizeActivityService.queryPrizeResult' occurTime='2026-04-20T18:09:00'AlertName: CP域服务接口错误率连续1分钟大于70%,Site: cn_product_default,MetricName: CP_all_all_sevice_failrate_over70_ams,Category: AIOps@rate
            IP Address: null
            Occur Time: 2026-04-20 18:13:40+08:00
            Clear Time: null
            Template: unknown
            Handler: mWX1336861,w00836539
            Alert ID: 521600500
            Tenant: 电商
            Application: 电商业务
            Service: VmallActivityService
            Site: cn_product_default
            """;

    private static final String LATENCY_ALERT = """
            WiseEye Alarm:
            Status: OPEN
            Severity: Major
            Host: BP_ingresscache_service_delay_avg:{"servicename":"vmall_ingresscache_ica","interfacename":"persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2","cluster":"cluster2"}
            Category: AIOps@delay
            Details: BP_ingresscache_service_delay_avg
            1、告警名称：ingresscache接口平均时延超3s持续5分钟
            2、告警对象：服务名称vmall_ingresscache_ica，接口名称为persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2，集群：cluster2
            3、告警信息：平均时延=3067, 告警发生时间=2026-04-21T10:33:00, 请求总数=49.0
            4、诊断命令：chat analysis vmall-service-latency-delimit-analysis serviceName='vmall_ingresscache_ica' interfaceName='persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2' occurTime='2026-04-21T10:33:00'
            AlertName: ingresscache接口平均时延超3s持续5分钟,Site: cn_product_default,MetricName: BP_ingresscache_service_delay_avg,Category: AIOps@delay
            IP Address: null
            Occur Time: 2026-04-21 10:38:31+08:00
            Clear Time: null
            Template: unknown
            Handler: yWX1352312,y00666716
            Alert ID: 521932116
            Tenant: 电商
            Application: 电商业务
            Service: VmallOpenGWService
            Site: cn_product_default
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AiopsProperties aiopsProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private AnalysisTaskRepository analysisTaskRepository;

    @Autowired
    private AnalysisResultRepository analysisResultRepository;

    @Autowired
    private AlertParser alertParser;

    @Autowired
    private AlertClassifier alertClassifier;

    @Autowired
    private DedupeKeyGenerator dedupeKeyGenerator;

    @Autowired
    private ResourceMappingProvider resourceMappingProvider;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM aiops_analysis_job_state_log");
        jdbcTemplate.update("DELETE FROM aiops_provider_call_log");
        jdbcTemplate.update("DELETE FROM aiops_duplicate_alert_link");
        jdbcTemplate.update("DELETE FROM aiops_analysis_result");
        jdbcTemplate.update("DELETE FROM aiops_analysis_task");
        jdbcTemplate.update("DELETE FROM aiops_alert_event");
    }

    @Test
    void contextLoads() {
        assertThat(aiopsProperties.analysis().dedupeWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(aiopsProperties.llm().enabled()).isTrue();
        assertThat(aiopsProperties.provider().parallelEnabled()).isTrue();
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/api/v1/health", JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("success").asBoolean()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");
        assertThat(response.getBody().path("data").path("status").asText()).isEqualTo("UP");
    }

    @Test
    void enumsSerializeAsStableNames() throws Exception {
        assertThat(objectMapper.writeValueAsString(AlertCategory.RPC_ERROR_RATE))
                .isEqualTo("\"RPC_ERROR_RATE\"");
        assertThat(objectMapper.writeValueAsString(AnalysisJobStatus.COLLECTING_EVIDENCE))
                .isEqualTo("\"COLLECTING_EVIDENCE\"");
    }

    @Test
    void jsonFieldSerializationRoundTrips() {
        AlertTriggerValues triggerValues = new AlertTriggerValues(
                49.0,
                1.0,
                3067L,
                null,
                null,
                "avg_latency > 3000ms for 5m",
                "平均时延=3067, 请求总数=49.0"
        );

        String json = jsonMapper.toJson(triggerValues);
        AlertTriggerValues restored = jsonMapper.fromJson(json, AlertTriggerValues.class);

        assertThat(json).contains("\"requestCount\":49.0");
        assertThat(restored).isEqualTo(triggerValues);
    }

    @Test
    void flywayInitializesExpectedTables() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name LIKE 'aiops_%'
                        ORDER BY table_name
                        """,
                String.class
        );

        assertThat(tables).contains(
                "aiops_alert_event",
                "aiops_analysis_task",
                "aiops_analysis_result",
                "aiops_evidence_bundle",
                "aiops_provider_call_log",
                "aiops_llm_call_log",
                "aiops_duplicate_alert_link",
                "aiops_resource_mapping",
                "aiops_analysis_job_state_log"
        );
    }

    @Test
    void alertEventRepositoryPersistsJsonField() {
        Instant now = Instant.now();
        AlertTriggerValues triggerValues = new AlertTriggerValues(
                3.0,
                1.0,
                null,
                null,
                null,
                "fail_rate > 0.5",
                "请求总数=3, 失败率=100%"
        );
        AlertEvent alertEvent = new AlertEvent(
                "test-alert-" + now.toEpochMilli(),
                AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                "ams_front",
                "vmall-ams.PrizeActivityService.queryPrizeResult",
                "cluster1",
                "cn",
                "tenant",
                "vmall",
                "RPC error rate",
                "rpc_fail_rate",
                "AIOps@rate",
                "raw alert",
                now,
                now,
                triggerValues
        );

        alertEventRepository.save(alertEvent);

        assertThat(alertEventRepository.findByAlertId(alertEvent.alertId()))
                .hasValueSatisfying(saved -> {
                    assertThat(saved.category()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
                    assertThat(saved.triggerValues()).isEqualTo(triggerValues);
                });
    }

    @Test
    void resourceMappingProviderReadsSeededMappingsFromDatabase() {
        ResourceMapping mapping = resourceMappingProvider.queryResourceMapping("ams_front", "cluster1");

        assertThat(mapping.serviceName()).isEqualTo("ams_front");
        assertThat(mapping.clusterName()).isEqualTo("cluster1");
        assertThat(mapping.resources())
                .extracting("resourceId")
                .contains("db_ams_activity", "dcs_ams_cache");
    }

    @Test
    void parserParsesRpcErrorRateSample() {
        AlertEvent parsed = alertParser.parse(RATE_ALERT);
        AlertEvent alert = parsed.withCategory(alertClassifier.classify(parsed));

        assertThat(alert.alertId()).isEqualTo("521600500");
        assertThat(alert.category()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
        assertThat(alert.serviceName()).isEqualTo("ams_front");
        assertThat(alert.interfaceName()).isEqualTo("vmall-ams.PrizeActivityService.queryPrizeResult");
        assertThat(alert.cluster()).isEqualTo("cluster1");
        assertThat(alert.rawCategory()).isEqualTo("AIOps@rate");
        assertThat(alert.triggerValues().requestCount()).isEqualTo(3.0);
        assertThat(alert.triggerValues().failRate()).isEqualTo(1.0);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-04-20T10:09:00Z"));
    }

    @Test
    void parserParsesRpcLatencySample() {
        AlertEvent parsed = alertParser.parse(LATENCY_ALERT);
        AlertEvent alert = parsed.withCategory(alertClassifier.classify(parsed));

        assertThat(alert.alertId()).isEqualTo("521932116");
        assertThat(alert.category()).isEqualTo(AlertCategory.RPC_LATENCY);
        assertThat(alert.serviceName()).isEqualTo("vmall_ingresscache_ica");
        assertThat(alert.interfaceName())
                .isEqualTo("persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2");
        assertThat(alert.cluster()).isEqualTo("cluster2");
        assertThat(alert.rawCategory()).isEqualTo("AIOps@delay");
        assertThat(alert.triggerValues().requestCount()).isEqualTo(49.0);
        assertThat(alert.triggerValues().avgLatencyMs()).isEqualTo(3067L);
        assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-04-21T02:33:00Z"));
    }

    @Test
    void webhookCreatesTaskAndWorkerEventuallyCompletesRuleFallbackLifecycle() throws Exception {
        String alertId = nextNumericAlertId();
        ResponseEntity<JsonNode> response = postWebhook(withAlertId(RATE_ALERT, alertId));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = response.getBody().path("data");
        assertThat(data.path("alertId").asText()).isEqualTo(alertId);
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("duplicate").asBoolean()).isFalse();
        assertThat(data.path("taskId").asText()).startsWith("task_");

        String taskId = data.path("taskId").asText();
        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.alertId()).isEqualTo(alertId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);
        assertThat(task.category()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT analysis_mode FROM aiops_analysis_result WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiops_analysis_job_state_log WHERE task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rule_engine_version FROM aiops_analysis_result WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo("fallback-rules-v1");
        JsonNode result = loadResultJson(taskId);
        assertThat(result.path("rootCauses").get(0).path("causeType").asText()).isEqualTo("BUSINESS_ERROR_CODE_NOT_MASKED");
        assertThat(result.path("llmStatus").asText()).isEqualTo("TIMEOUT");
        assertThat(result.path("evidenceRefs").toString()).contains("error.top.1", "uid.top.1");
    }

    @Test
    void webhookMarksTaskDegradedWhenNonCriticalRuntimeLogProviderFails() throws Exception {
        String alertId = nextNumericAlertId();
        String serviceName = "ams_front_" + System.nanoTime() + "_mock-fail-runtime-log";
        ResponseEntity<JsonNode> response = postWebhook(withServiceName(withAlertId(RATE_ALERT, alertId), serviceName));

        String taskId = response.getBody().path("data").path("taskId").asText();
        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);

        JsonNode result = loadResultJson(taskId);
        assertThat(result.path("analysisMode").asText()).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(result.path("degraded").asBoolean()).isTrue();
        assertThat(result.path("degradedByProviderFailure").asBoolean()).isTrue();
        assertThat(result.path("llmStatus").asText()).isEqualTo("TIMEOUT");
        assertThat(result.path("missingEvidence").toString()).contains("runtimeLog");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM aiops_provider_call_log
                        WHERE task_id = ?
                          AND provider_name = 'runtimeLog'
                          AND provider_status = 'FAILED'
                        """,
                Integer.class,
                taskId
        )).isGreaterThan(0);
    }

    @Test
    void webhookFallsBackWhenCriticalInterfaceLogProviderFails() throws Exception {
        String alertId = nextNumericAlertId();
        String serviceName = "ams_front_" + System.nanoTime() + "_mock-fail-interface-log";
        ResponseEntity<JsonNode> response = postWebhook(withServiceName(withAlertId(RATE_ALERT, alertId), serviceName));

        String taskId = response.getBody().path("data").path("taskId").asText();
        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);

        JsonNode result = loadResultJson(taskId);
        assertThat(result.path("analysisMode").asText()).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(result.path("degraded").asBoolean()).isTrue();
        assertThat(result.path("degradedByProviderFailure").asBoolean()).isTrue();
        assertThat(result.path("missingEvidence").toString()).contains("interfaceLog");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM aiops_analysis_job_state_log
                        WHERE task_id = ?
                          AND to_status = 'RULE_FALLBACK_ANALYZING'
                        """,
                Integer.class,
                taskId
        )).isEqualTo(1);
    }

    @Test
    void webhookLatencyAlertPersistsTraceBasedRuleEvidenceInRuleFallbackResult() throws Exception {
        String alertId = nextNumericAlertId();
        ResponseEntity<JsonNode> response = postWebhook(withAlertId(LATENCY_ALERT, alertId));

        String taskId = response.getBody().path("data").path("taskId").asText();
        AnalysisJob task = waitForTerminalTask(taskId);
        assertThat(task.status()).isEqualTo(AnalysisJobStatus.DEGRADED_SUCCESS);

        JsonNode result = loadResultJson(taskId);
        assertThat(result.path("analysisMode").asText()).isEqualTo("RULE_BASED_DEGRADED");
        assertThat(result.path("llmStatus").asText()).isEqualTo("TIMEOUT");
        assertThat(result.path("rootCauses").get(0).path("causeType").asText()).isEqualTo("DOWNSTREAM_RPC_LATENCY");
        assertThat(result.path("evidenceRefs").toString()).contains("trace.slow.1", "trace.slow.aggregation.1");
    }

    @Test
    void duplicateWebhookWithinFiveMinutesReturnsExistingTask() {
        String firstAlertId = nextNumericAlertId();
        String secondAlertId = String.valueOf(Long.parseLong(firstAlertId) + 1);

        String firstTaskId = postWebhook(withAlertId(RATE_ALERT, firstAlertId))
                .getBody()
                .path("data")
                .path("taskId")
                .asText();
        ResponseEntity<JsonNode> duplicateResponse = postWebhook(withAlertId(RATE_ALERT, secondAlertId));

        JsonNode duplicateData = duplicateResponse.getBody().path("data");
        assertThat(duplicateData.path("duplicate").asBoolean()).isTrue();
        assertThat(duplicateData.path("taskId").asText()).isEqualTo(firstTaskId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aiops_analysis_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aiops_duplicate_alert_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void webhookReturnsHistoricalReferenceOutsideDedupeWindowAndCreatesNewTask() {
        AlertEvent parsed = alertParser.parse(RATE_ALERT);
        AlertEvent alert = parsed.withCategory(alertClassifier.classify(parsed));
        String dedupeKey = dedupeKeyGenerator.generate(alert);
        Instant createdAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        String historicalTaskId = "task_history_" + System.currentTimeMillis();
        String historicalAlertId = "history-" + System.currentTimeMillis();

        analysisTaskRepository.save(new AnalysisJob(
                historicalTaskId,
                historicalAlertId,
                AlertCategory.RPC_ERROR_RATE,
                dedupeKey,
                AnalysisJobStatus.SUCCESS,
                null,
                null,
                0,
                1,
                createdAt,
                createdAt.plusSeconds(1),
                createdAt.plusSeconds(60),
                null
        ));
        analysisResultRepository.save(new AnalysisResult(
                historicalTaskId,
                historicalAlertId,
                AlertCategory.RPC_ERROR_RATE,
                AnalysisMode.LLM_ASSISTED,
                "历史告警疑似业务 errorCode 未屏蔽。",
                new ImpactAssessment("LOW", "历史结果"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                true,
                "SUCCESS",
                false,
                false,
                null,
                null,
                0.78,
                createdAt.plusSeconds(60)
        ));

        String newAlertId = nextNumericAlertId();
        ResponseEntity<JsonNode> response = postWebhook(withAlertId(RATE_ALERT, newAlertId));

        JsonNode data = response.getBody().path("data");
        assertThat(data.path("duplicate").asBoolean()).isFalse();
        assertThat(data.path("taskId").asText()).isNotEqualTo(historicalTaskId);
        assertThat(data.path("historicalReference").path("matched").asBoolean()).isTrue();
        assertThat(data.path("historicalReference").path("taskId").asText()).isEqualTo(historicalTaskId);
        assertThat(data.path("historicalReference").path("alertId").asText()).isEqualTo(historicalAlertId);
        assertThat(data.path("historicalReference").path("summary").asText()).contains("历史告警");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM aiops_analysis_task", Integer.class)).isEqualTo(2);
    }

    private ResponseEntity<JsonNode> postWebhook(String rawAlert) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return restTemplate.postForEntity("/api/v1/alerts/webhook", new HttpEntity<>(rawAlert, headers), JsonNode.class);
    }

    private String withAlertId(String rawAlert, String alertId) {
        return rawAlert.replaceAll("(?m)^Alert ID: .+$", "Alert ID: " + alertId);
    }

    private String withServiceName(String rawAlert, String serviceName) {
        return rawAlert
                .replace("\"servicename\":\"ams_front\"", "\"servicename\":\"" + serviceName + "\"")
                .replace("serviceName='ams_front'", "serviceName='" + serviceName + "'");
    }

    private String nextNumericAlertId() {
        return String.valueOf(System.currentTimeMillis());
    }

    private JsonNode loadResultJson(String taskId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT result_json FROM aiops_analysis_result WHERE task_id = ?",
                String.class,
                taskId
        );
        return objectMapper.readTree(json);
    }

    private AnalysisJob waitForTerminalTask(String taskId) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            AnalysisJob task = analysisTaskRepository.findByTaskId(taskId).orElse(null);
            if (task != null && task.status().isTerminal()) {
                return task;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        return analysisTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new AssertionError("Task not found: " + taskId));
    }
}

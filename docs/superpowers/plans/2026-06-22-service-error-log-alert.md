# Service Error Log Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `SERVICE_ERROR_LOG` alerts so the system can parse service-level ERROR log alerts, search runtime logs by normalized message with controlled message cutting, extract trace IDs, and produce rule/LLM/fallback analysis results.

**Architecture:** Add the new alert category as an incremental path through the existing webhook, dedupe, provider, rule, evidence compression, LLM, fallback, and query pipeline. Runtime log lookup is the key evidence source for this alert type: query with `level=ERROR`, strip only a leading `DEFAULT` token, progressively shorten the message when the full message misses, then use matched runtime logs to sample trace IDs.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Spring JDBC, MySQL/Flyway, Jackson, JUnit 5, AssertJ, Maven.

---

## File Structure

Modify these existing files:

- `src/main/java/com/company/aiops/alert/model/AlertCategory.java` - add `SERVICE_ERROR_LOG`.
- `src/main/java/com/company/aiops/alert/model/AlertTriggerValues.java` - add error-log trigger fields.
- `src/main/java/com/company/aiops/alert/parser/WiseEyeAlertParser.java` - parse failure count, log message, normalized query.
- `src/main/java/com/company/aiops/alert/classifier/DefaultAlertClassifier.java` - classify `AIOps@count` service error logs before generic `error` metrics.
- `src/main/java/com/company/aiops/alert/dedupe/DedupeKeyGenerator.java` - generate service-log dedupe keys using `logSignature`.
- `src/main/java/com/company/aiops/analysis/context/DefaultAnalysisContextFactory.java` - set service-log time window.
- `src/main/java/com/company/aiops/analysis/context/AnalysisOptions.java` - carry message query limits and `ERROR` level.
- `src/main/java/com/company/aiops/common/config/AiopsProperties.java` - bind message query configuration.
- `src/main/resources/application.yml` - configure message cutting values.
- `src/main/java/com/company/aiops/provider/log/RuntimeLogProvider.java` - add message search method.
- `src/main/java/com/company/aiops/provider/model/RuntimeLogEntry.java` - add cluster, instance, span, logger, thread, exception, and stack fields.
- `src/main/java/com/company/aiops/provider/log/mock/MockRuntimeLogProvider.java` - mock full-message miss, cut-message hit, and no-hit behavior.
- `src/main/java/com/company/aiops/analysis/evidence/RuntimeLogEvidence.java` - store query attempts, matched query, and trace IDs.
- `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java` - add service-log provider flow.
- `src/main/java/com/company/aiops/analysis/rule/DefaultRuleAnalysisService.java` - route service-log alerts to the new analyzer.
- `src/main/java/com/company/aiops/analysis/rule/model/RuleAnalysisEvidence.java` - keep current structure; reuse `RuleFinding`, `TraceObservation`, `MetricAnomaly`, and `CandidateRootCause`.
- `src/main/java/com/company/aiops/analysis/bundle/CompressedEvidenceBundle.java` - add alert/runtime-log fields needed by prompts.
- `src/main/java/com/company/aiops/analysis/bundle/DefaultEvidenceCompressor.java` - materialize query attempts, matched query, and new alert values.
- `src/main/java/com/company/aiops/llm/schema/LlmOutputSchema.java` - allow new root cause types.
- `src/main/java/com/company/aiops/llm/client/mock/MockLlmClient.java` - return deterministic service-log JSON.
- `src/main/java/com/company/aiops/analysis/fallback/DefaultRuleBasedFallbackAnalyzer.java` - produce service-log summaries, impact, and recommendations.
- `src/test/java/com/company/aiops/support/AlertSamples.java` - add canonical service-log alert sample.
- Existing tests that instantiate `AlertTriggerValues`, `AnalysisOptions`, `RuntimeLogEntry`, or `CompressedEvidenceBundle.AlertSnapshot` must be updated with the new constructor arguments.

Create these new files:

- `src/main/java/com/company/aiops/analysis/rule/log/ErrorLogRuleAnalyzer.java` - category-specific rule analyzer interface.
- `src/main/java/com/company/aiops/analysis/rule/log/DefaultErrorLogRuleAnalyzer.java` - deterministic service-log rules.
- `src/main/java/com/company/aiops/analysis/rule/log/package-info.java` - package responsibility.
- `src/main/java/com/company/aiops/llm/prompt/ServiceErrorLogPromptBuilder.java` - service-log prompt builder.
- `src/test/java/com/company/aiops/alert/dedupe/DedupeKeyGeneratorTests.java` - service-log dedupe tests.
- `src/test/java/com/company/aiops/analysis/rule/log/DefaultErrorLogRuleAnalyzerTests.java` - rule analyzer tests.
- `src/test/java/com/company/aiops/llm/prompt/ServiceErrorLogPromptBuilderTests.java` - prompt tests.

No database migration is required because the new trigger values and evidence fields are stored inside existing JSON columns.

---

### Task 1: Ingress Model, Parsing, Classification, And Dedupe

**Files:**
- Modify: `src/main/java/com/company/aiops/alert/model/AlertCategory.java`
- Modify: `src/main/java/com/company/aiops/alert/model/AlertTriggerValues.java`
- Modify: `src/main/java/com/company/aiops/alert/parser/WiseEyeAlertParser.java`
- Modify: `src/main/java/com/company/aiops/alert/classifier/DefaultAlertClassifier.java`
- Modify: `src/main/java/com/company/aiops/alert/dedupe/DedupeKeyGenerator.java`
- Modify: `src/test/java/com/company/aiops/support/AlertSamples.java`
- Modify: `src/test/java/com/company/aiops/alert/AlertIngressIntegrationTests.java`
- Create: `src/test/java/com/company/aiops/alert/dedupe/DedupeKeyGeneratorTests.java`

- [ ] **Step 1: Add the failing parser/classifier test**

Append this sample to `AlertSamples`:

```java
public static final String SERVICE_ERROR_LOG_ALERT = """
        WiseEye Alarm:
        Status: OPEN
        Severity: Major
        Host: CP_all_all_sevice_running_error_activity:{"servicename":"ams_front","cluster":"cluster2"}
        Category: AIOps@count
        Details: CP_all_all_sevice_running_error_activity
        1、告警名称：CP域服务业务错误日志告警
        2、告警对象：服务名称：ams_front,集群信息：cluster2
        3、告警信息：失败数=2,发生时间=2026-06-22T13:42:00, 日志=DEFAULT prebook loginAppointment failed
        4、看板跳转：https://console-drcn.wisedevops.huawei.com/microApp/serviceInsight/aiops5Dashboard#/edit-dashboard?id=9dabb7e8-db11-417a-ade0-a988ad4df9e8
        AlertName: CP域服务业务错误日志告警,Site: cn_product_default,MetricName: CP_all_all_sevice_running_error_activity,Category: AIOps@count
        IP Address: null
        Occur Time: 2026-06-22 13:42:30+08:00
        Clear Time: null
        Template: unknown
        Handler: unknown
        Alert ID: 554631819
        Tenant: 电商
        Application: 电商业务
        Service: VmallActivityService
        Site: cn_product_default
        Source_ID: 5ba509babfa344978b9176d1b6602581
        """;
```

Add this test to `AlertIngressIntegrationTests`:

```java
@Test
void parserParsesServiceErrorLogSample() {
    AlertEvent parsed = alertParser.parse(AlertSamples.SERVICE_ERROR_LOG_ALERT);
    AlertEvent alert = parsed.withCategory(alertClassifier.classify(parsed));

    assertThat(alert.alertId()).isEqualTo("554631819");
    assertThat(alert.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
    assertThat(alert.serviceName()).isEqualTo("ams_front");
    assertThat(alert.interfaceName()).isNull();
    assertThat(alert.cluster()).isEqualTo("cluster2");
    assertThat(alert.rawCategory()).isEqualTo("AIOps@count");
    assertThat(alert.metricName()).isEqualTo("CP_all_all_sevice_running_error_activity");
    assertThat(alert.triggerValues().failureCount()).isEqualTo(2.0);
    assertThat(alert.triggerValues().logLevel()).isEqualTo("ERROR");
    assertThat(alert.triggerValues().logMessage()).isEqualTo("DEFAULT prebook loginAppointment failed");
    assertThat(alert.triggerValues().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
    assertThat(alert.metricOccurTime()).isEqualTo(Instant.parse("2026-06-22T05:42:00Z"));
}
```

- [ ] **Step 2: Add the failing dedupe test**

Create `DedupeKeyGeneratorTests`:

```java
package com.company.aiops.alert.dedupe;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DedupeKeyGeneratorTests {

    private final DedupeKeyGenerator generator = new DedupeKeyGenerator();

    @Test
    void generateUsesLogSignatureForServiceErrorLogAlerts() {
        AlertEvent alert = new AlertEvent(
                "alert-1",
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
                "raw",
                Instant.parse("2026-06-22T05:42:00Z"),
                Instant.parse("2026-06-22T05:42:10Z"),
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
                        null,
                        "失败数=2,发生时间=2026-06-22T13:42:00, 日志=DEFAULT prebook loginAppointment failed"
                )
        );

        assertThat(generator.generate(alert))
                .isEqualTo("SERVICE_ERROR_LOG|ams_front|cluster2|prebook loginappointment failed");
    }

    @Test
    void generateKeepsInterfaceShapeForRpcAlerts() {
        AlertEvent alert = new AlertEvent(
                "alert-2",
                AlertCategory.RPC_ERROR_RATE,
                "OPEN",
                "MAJOR",
                "ams_front",
                "vmall-ams.PrizeActivityService.queryPrizeResult",
                "cluster1",
                "cn_product_default",
                "tenant",
                "application",
                "error rate",
                "CP_all_all_sevice_failrate_over70_ams",
                "AIOps@rate",
                "raw",
                Instant.parse("2026-04-20T10:09:00Z"),
                Instant.parse("2026-04-20T10:09:10Z"),
                new AlertTriggerValues(3.0, 1.0, null, null, null, null, null, null, null, null, "raw")
        );

        assertThat(generator.generate(alert))
                .isEqualTo("RPC_ERROR_RATE|ams_front|vmall-ams.PrizeActivityService.queryPrizeResult|cluster1");
    }
}
```

- [ ] **Step 3: Run the ingress tests and verify they fail**

Run:

```bash
mvn -Dtest=AlertIngressIntegrationTests,DedupeKeyGeneratorTests test
```

Expected: compilation fails because `SERVICE_ERROR_LOG`, new `AlertTriggerValues` accessors, and `DedupeKeyGeneratorTests` expectations do not exist yet.

- [ ] **Step 4: Implement the model and parser changes**

Update `AlertCategory`:

```java
public enum AlertCategory {
    RPC_ERROR_RATE,
    RPC_LATENCY,
    SERVICE_ERROR_LOG,
    UNKNOWN
}
```

Replace `AlertTriggerValues` with:

```java
public record AlertTriggerValues(
        Double requestCount,
        Double failRate,
        Long avgLatencyMs,
        Long p99LatencyMs,
        Long maxLatencyMs,
        Double failureCount,
        String logLevel,
        String logMessage,
        String normalizedLogQuery,
        String thresholdExpression,
        String rawMetricValueText
) {
}
```

In `WiseEyeAlertParser.parseTriggerValues`, return the new fields:

```java
private AlertTriggerValues parseTriggerValues(String rawText) {
    String failRateText = extractByPatterns(rawText, "失败率\\s*[=：]\\s*([0-9]+(?:\\.[0-9]+)?\\s*%?)");
    String rawMetricValueText = extractByPatterns(rawText, "告警信息[：:]\\s*([^\\n\\r]+)");
    String logMessage = extractByPatterns(rawText, "日志\\s*[=：]\\s*([^\\n\\r,，]+(?:\\s+[^\\n\\r,，]+)*)");
    return new AlertTriggerValues(
            parseDouble(extractByPatterns(rawText, "请求总数\\s*[=：]\\s*([0-9]+(?:\\.[0-9]+)?)")),
            parseRate(failRateText),
            parseLong(extractByPatterns(rawText, "平均时延\\s*[=：]\\s*([0-9]+)")),
            parseLong(extractByPatterns(rawText, "(?:P99|p99|99线)\\s*[=：]\\s*([0-9]+)")),
            parseLong(extractByPatterns(rawText, "(?:最大时延|maxLatency|max)\\s*[=：]\\s*([0-9]+)")),
            parseDouble(extractByPatterns(rawText, "失败数\\s*[=：]\\s*([0-9]+(?:\\.[0-9]+)?)")),
            logMessage == null ? null : "ERROR",
            logMessage,
            normalizeLogQuery(logMessage),
            null,
            rawMetricValueText
    );
}
```

Add the helper to `WiseEyeAlertParser`:

```java
private String normalizeLogQuery(String logMessage) {
    if (logMessage == null) {
        return null;
    }
    String normalized = logMessage.trim().replaceAll("\\s+", " ");
    normalized = normalized.replaceFirst("(?i)^DEFAULT(?:\\s+|$)", "").trim();
    return normalized.isBlank() ? null : normalized;
}
```

Keep `parseMetricOccurTime` as-is; it already recognizes `发生时间=2026-06-22T13:42:00`.

- [ ] **Step 5: Implement classifier and dedupe**

Update `DefaultAlertClassifier.classify` so service logs are checked before generic `error` metrics:

```java
if ("AIOps@count".equals(alert.rawCategory()) && isServiceErrorLog(alert)) {
    return AlertCategory.SERVICE_ERROR_LOG;
}
```

Add this helper:

```java
private boolean isServiceErrorLog(AlertEvent alert) {
    String metricName = alert.metricName() == null ? "" : alert.metricName().toLowerCase(Locale.ROOT);
    String alertName = alert.alertName() == null ? "" : alert.alertName();
    return metricName.contains("service_running_error")
            || metricName.contains("sevice_running_error")
            || metricName.contains("running_error")
            || metricName.contains("error_activity")
            || alertName.contains("错误日志");
}
```

Update `DedupeKeyGenerator.generate`:

```java
public String generate(AlertEvent alert) {
    if (alert.category() == AlertCategory.SERVICE_ERROR_LOG) {
        return String.join("|",
                alert.category().name(),
                normalize(alert.serviceName()),
                normalize(alert.cluster()),
                logSignature(alert)
        );
    }
    return String.join("|",
            alert.category().name(),
            normalize(alert.serviceName()),
            normalize(alert.interfaceName()),
            normalize(alert.cluster())
    );
}
```

Add the helpers:

```java
private String logSignature(AlertEvent alert) {
    String value = alert.triggerValues() == null ? null : alert.triggerValues().normalizedLogQuery();
    String normalized = normalize(value).toLowerCase(java.util.Locale.ROOT);
    normalized = normalized.replaceAll("\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b", " ");
    normalized = normalized.replaceAll("\\btrace[-_a-z0-9]*\\b", " ");
    normalized = normalized.replaceAll("\\bspan[-_a-z0-9]*\\b", " ");
    normalized = normalized.replaceAll("\\buid[-_a-z0-9]*\\b", " ");
    normalized = normalized.replaceAll("\\b\\d+\\b", " ");
    return normalized.replaceAll("\\s+", " ").trim();
}
```

- [ ] **Step 6: Update constructor call sites**

Search and update old `new AlertTriggerValues` constructor calls:

```bash
rg "new AlertTriggerValues" src
```

For RPC calls, add these six arguments before the existing `thresholdExpression` and `rawMetricValueText`:

```java
null, null, null, null, thresholdExpression, rawMetricValueText
```

For service-log tests, pass:

```java
2.0, "ERROR", "DEFAULT prebook loginAppointment failed", "prebook loginAppointment failed", null, rawMetricValueText
```

- [ ] **Step 7: Run the tests and verify they pass**

Run:

```bash
mvn -Dtest=AlertIngressIntegrationTests,DedupeKeyGeneratorTests test
```

Expected: PASS. The service-log parser test must prove `DEFAULT` was removed only in `normalizedLogQuery`, not in `logMessage`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/aiops/alert src/test/java/com/company/aiops/alert src/test/java/com/company/aiops/support/AlertSamples.java
git commit -m "feat: parse service error log alerts"
```

---

### Task 2: Context And Configuration

**Files:**
- Modify: `src/main/java/com/company/aiops/common/config/AiopsProperties.java`
- Modify: `src/main/java/com/company/aiops/analysis/context/AnalysisOptions.java`
- Modify: `src/main/java/com/company/aiops/analysis/context/DefaultAnalysisContextFactory.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/company/aiops/analysis/context/DefaultAnalysisContextFactoryTests.java`
- Modify: `src/test/java/com/company/aiops/analysis/rule/RuleAnalysisTestFixtures.java`

- [ ] **Step 1: Add failing context/config tests**

In `DefaultAnalysisContextFactoryTests`, add:

```java
@Test
void createUsesServiceErrorLogWindowAndMessageQueryOptions() {
    AlertEvent alert = new AlertEvent(
            "alert-log",
            AlertCategory.SERVICE_ERROR_LOG,
            "OPEN",
            "MAJOR",
            "ams_front",
            null,
            "cluster2",
            "site",
            "tenant",
            "application",
            "CP域服务业务错误日志告警",
            "CP_all_all_sevice_running_error_activity",
            "AIOps@count",
            "raw",
            Instant.parse("2026-06-22T05:42:00Z"),
            Instant.parse("2026-06-22T05:42:10Z"),
            new AlertTriggerValues(null, null, null, null, null, 2.0, "ERROR",
                    "DEFAULT prebook loginAppointment failed", "prebook loginAppointment failed", null, "raw")
    );

    AnalysisContext context = factory.create(alert);

    assertThat(context.alarmWindow().startTime()).isEqualTo(Instant.parse("2026-06-22T05:37:00Z"));
    assertThat(context.alarmWindow().endTime()).isEqualTo(Instant.parse("2026-06-22T05:44:00Z"));
    assertThat(context.options().errorLogMessageQueryMaxAttempts()).isEqualTo(5);
    assertThat(context.options().errorLogMessageQueryMinChars()).isEqualTo(8);
    assertThat(context.options().errorLogMessageQueryMinTokens()).isEqualTo(2);
    assertThat(context.options().errorLogQueryLevel()).isEqualTo("ERROR");
}
```

- [ ] **Step 2: Run the context test and verify it fails**

Run:

```bash
mvn -Dtest=DefaultAnalysisContextFactoryTests test
```

Expected: FAIL because the new option accessors are missing and the category is not handled by the switch.

- [ ] **Step 3: Add properties and options**

Extend `AiopsProperties.Analysis`:

```java
@Min(1) int errorLogMessageQueryMaxAttempts,
@Min(1) int errorLogMessageQueryMinChars,
@Min(1) int errorLogMessageQueryMinTokens,
@NotBlank String errorLogQueryLevel,
@Min(1) int historyQueryMaxLimit
```

Extend `AnalysisOptions` with:

```java
int errorLogMessageQueryMaxAttempts,
int errorLogMessageQueryMinChars,
int errorLogMessageQueryMinTokens,
String errorLogQueryLevel,
```

Update `DefaultAnalysisContextFactory` options construction:

```java
aiopsProperties.analysis().errorLogMessageQueryMaxAttempts(),
aiopsProperties.analysis().errorLogMessageQueryMinChars(),
aiopsProperties.analysis().errorLogMessageQueryMinTokens(),
aiopsProperties.analysis().errorLogQueryLevel(),
aiopsProperties.llm().enabled(),
aiopsProperties.provider().parallelEnabled()
```

Update the time-window switch:

```java
Duration lookback = switch (alertEvent.category()) {
    case RPC_ERROR_RATE, SERVICE_ERROR_LOG -> ERROR_RATE_LOOKBACK;
    case RPC_LATENCY -> LATENCY_LOOKBACK;
    case UNKNOWN -> ERROR_RATE_LOOKBACK;
};
```

- [ ] **Step 4: Add YAML values**

In `application.yml`, under `aiops.analysis`, add:

```yaml
    error-log-message-query-max-attempts: 5
    error-log-message-query-min-chars: 8
    error-log-message-query-min-tokens: 2
    error-log-query-level: ERROR
```

Update all `new AiopsProperties.Analysis` and `new AnalysisOptions` constructor calls with:

```java
5,
8,
2,
"ERROR",
```

- [ ] **Step 5: Run the context and smoke tests**

Run:

```bash
mvn -Dtest=DefaultAnalysisContextFactoryTests,ApplicationSmokeTests test
```

Expected: PASS. Configuration binding must start the Spring context without missing-property errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/aiops/common/config/AiopsProperties.java src/main/java/com/company/aiops/analysis/context src/main/resources/application.yml src/test/java/com/company/aiops
git commit -m "feat: configure service error log analysis options"
```

---

### Task 3: Runtime Log Message Search Contract

**Files:**
- Modify: `src/main/java/com/company/aiops/provider/log/RuntimeLogProvider.java`
- Modify: `src/main/java/com/company/aiops/provider/model/RuntimeLogEntry.java`
- Modify: `src/main/java/com/company/aiops/analysis/evidence/RuntimeLogEvidence.java`
- Modify: `src/main/java/com/company/aiops/provider/log/mock/MockRuntimeLogProvider.java`
- Modify: tests that construct `RuntimeLogEntry`
- Create: `src/test/java/com/company/aiops/provider/log/mock/MockRuntimeLogProviderTests.java`

- [ ] **Step 1: Add failing provider contract tests**

Create `MockRuntimeLogProviderTests`:

```java
package com.company.aiops.provider.log.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockRuntimeLogProviderTests {

    private final MockRuntimeLogProvider provider = new MockRuntimeLogProvider();
    private final TimeWindow window = new TimeWindow(
            Instant.parse("2026-06-22T05:37:00Z"),
            Instant.parse("2026-06-22T05:44:00Z")
    );

    @Test
    void queryByMessageRequiresErrorLevelAndCanMissFullMessage() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "ERROR",
                "prebook loginAppointment failed",
                window,
                50
        );

        assertThat(entries).isEmpty();
    }

    @Test
    void queryByMessageReturnsTraceIdsForCutMessage() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "ERROR",
                "loginAppointment failed",
                window,
                50
        );

        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.level()).isEqualTo("ERROR");
            assertThat(entry.serviceName()).isEqualTo("ams_front");
            assertThat(entry.cluster()).isEqualTo("cluster2");
            assertThat(entry.traceId()).isNotBlank();
            assertThat(entry.message()).contains("loginAppointment failed");
        });
    }

    @Test
    void queryByMessageDoesNotReturnWarnEntriesForServiceErrorLogLookup() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "WARN",
                "loginAppointment failed",
                window,
                50
        );

        assertThat(entries).isEmpty();
    }
}
```

- [ ] **Step 2: Run the provider test and verify it fails**

Run:

```bash
mvn -Dtest=MockRuntimeLogProviderTests test
```

Expected: compilation fails because `queryByMessage` and expanded `RuntimeLogEntry` fields do not exist.

- [ ] **Step 3: Expand runtime log models**

Replace `RuntimeLogEntry` with:

```java
public record RuntimeLogEntry(
        String serviceName,
        String cluster,
        String instanceId,
        String traceId,
        String spanId,
        String level,
        String logger,
        String threadName,
        String message,
        String exceptionType,
        String stackTop,
        Instant timestamp
) {
}
```

Add these nested records and fields to `RuntimeLogEvidence`:

```java
public record RuntimeLogEvidence(
        List<RuntimeLogEntry> entries,
        boolean collected,
        String missingReason,
        boolean messageQueryMatched,
        String matchedLogQuery,
        List<QueryAttempt> queryAttempts,
        List<String> sampledTraceIds
) {

    public RuntimeLogEvidence {
        entries = entries == null ? List.of() : List.copyOf(entries);
        queryAttempts = queryAttempts == null ? List.of() : List.copyOf(queryAttempts);
        sampledTraceIds = sampledTraceIds == null ? List.of() : List.copyOf(sampledTraceIds);
    }

    public static RuntimeLogEvidence available(List<RuntimeLogEntry> entries) {
        return new RuntimeLogEvidence(entries, true, null, false, null, List.of(), List.of());
    }

    public static RuntimeLogEvidence availableMessageQuery(
            List<RuntimeLogEntry> entries,
            String matchedLogQuery,
            List<QueryAttempt> queryAttempts,
            List<String> sampledTraceIds
    ) {
        return new RuntimeLogEvidence(entries, true, null, true, matchedLogQuery, queryAttempts, sampledTraceIds);
    }

    public static RuntimeLogEvidence missing(String reason) {
        return new RuntimeLogEvidence(List.of(), false, reason, false, null, List.of(), List.of());
    }

    public record QueryAttempt(
            String evidenceId,
            String query,
            String level,
            int hitCount,
            boolean selected
    ) {
    }
}
```

- [ ] **Step 4: Add the provider method and mock implementation**

Add to `RuntimeLogProvider`:

```java
List<RuntimeLogEntry> queryByMessage(
        String serviceName,
        String cluster,
        String level,
        String messageQuery,
        TimeWindow window,
        int limit
);
```

Update `MockRuntimeLogProvider.queryWarnAndErrorLogs` to construct the expanded record:

```java
new RuntimeLogEntry(serviceName, null, "mock-instance-1", traceId, null, "WARN",
        "mock.logger", "mock-thread", "mock cache access slow", null, null, timestamp)
```

Implement `queryByMessage`:

```java
@Override
public List<RuntimeLogEntry> queryByMessage(
        String serviceName,
        String cluster,
        String level,
        String messageQuery,
        TimeWindow window,
        int limit
) {
    if (serviceName != null && serviceName.contains(MockProviderTokens.FAIL_RUNTIME_LOG)) {
        throw new IllegalStateException("mock runtime-log provider failure for service " + serviceName);
    }
    if (!"ERROR".equals(level)) {
        return List.of();
    }
    if (!"loginAppointment failed".equals(messageQuery)) {
        return List.of();
    }
    Instant timestamp = window.endTime().minusSeconds(10);
    return List.of(
            new RuntimeLogEntry(serviceName, cluster, "ams-front-1", "trace-log-1", "span-log-1", "ERROR",
                    "com.company.ams.AppointmentController", "http-nio-1", "prebook loginAppointment failed",
                    "AppointmentLoginException", "AppointmentController.loginAppointment:87", timestamp),
            new RuntimeLogEntry(serviceName, cluster, "ams-front-1", "trace-log-2", "span-log-2", "ERROR",
                    "com.company.ams.AppointmentController", "http-nio-2", "prebook loginAppointment failed",
                    "AppointmentLoginException", "AppointmentController.loginAppointment:87", timestamp.plusSeconds(1))
    ).stream().limit(limit).toList();
}
```

- [ ] **Step 5: Update existing RuntimeLogEntry constructor calls**

Run:

```bash
rg "new RuntimeLogEntry" src
```

For trace-based runtime log samples, use `null` for cluster/span/exception fields when unavailable:

```java
new RuntimeLogEntry(serviceName, null, "mock-instance-1", traceId, null, "ERROR",
        "mock.logger", "mock-thread", "mock downstream dependency returned an error",
        null, null, timestamp.plusSeconds(1))
```

- [ ] **Step 6: Run the provider tests**

Run:

```bash
mvn -Dtest=MockRuntimeLogProviderTests,DefaultEvidenceCompressorTests,DefaultProviderOrchestratorTests test
```

Expected: PASS after constructor updates. Provider orchestrator behavior is not changed yet beyond compilation.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/aiops/provider/log src/main/java/com/company/aiops/provider/model/RuntimeLogEntry.java src/main/java/com/company/aiops/analysis/evidence/RuntimeLogEvidence.java src/test/java/com/company/aiops
git commit -m "feat: add runtime log message search contract"
```

---

### Task 4: Provider Orchestration For SERVICE_ERROR_LOG

**Files:**
- Modify: `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`
- Modify: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`

- [ ] **Step 1: Add failing orchestration tests**

Add a test where the full message misses and the cut message hits:

```java
@Test
void collectServiceErrorLogUsesErrorLevelMessageCuttingAndRuntimeTraceIds() {
    CapturingRuntimeLogProvider runtimeLogProvider = new CapturingRuntimeLogProvider();
    DefaultProviderOrchestrator orchestrator = orchestrator(
            properties(true),
            new InMemoryProviderCallLogRepository(),
            fallbackResolver(),
            (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
            (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
            (serviceName, interfaceName, window, limit) -> List.of(),
            traceId -> List.of(new InterfaceLogEntry("ams_front", "loginAppointment", traceId, "span", null,
                    "uid", false, "E_LOG", "prebook loginAppointment failed", 40L, Instant.now())),
            runtimeLogProvider,
            new StableContainerMetricProvider(),
            new StableCseMetricProvider(),
            new StableDatabaseMetricProvider(),
            new StableDcsMetricProvider(),
            new StableMqMetricProvider()
    );

    ProviderCollectionResult result = orchestrator.collect("task-service-log", context(AlertCategory.SERVICE_ERROR_LOG));

    assertThat(runtimeLogProvider.messageQueries)
            .containsExactly("prebook loginAppointment failed", "loginAppointment failed");
    assertThat(runtimeLogProvider.levels).containsOnly("ERROR");
    assertThat(result.ruleFallbackRequired()).isFalse();
    assertThat(result.runtimeLogEvidence().messageQueryMatched()).isTrue();
    assertThat(result.runtimeLogEvidence().matchedLogQuery()).isEqualTo("loginAppointment failed");
    assertThat(result.runtimeLogEvidence().sampledTraceIds()).containsExactly("trace-log-1", "trace-log-2");
    assertThat(result.traceEvidence().collected()).isTrue();
    assertThat(result.interfaceLogEvidence().collected()).isFalse();
}
```

Add a test for all query attempts missing:

```java
@Test
void collectServiceErrorLogRequiresFallbackWhenAllMessageQueriesMiss() {
    RuntimeLogProvider noHitProvider = new RuntimeLogProvider() {
        @Override
        public List<RuntimeLogEntry> queryWarnAndErrorLogs(String traceId, String serviceName, TimeWindow window, int limit) {
            return List.of();
        }

        @Override
        public List<RuntimeLogEntry> queryByMessage(String serviceName, String cluster, String level,
                                                    String messageQuery, TimeWindow window, int limit) {
            return List.of();
        }
    };
    DefaultProviderOrchestrator orchestrator = orchestrator(
            properties(true),
            new InMemoryProviderCallLogRepository(),
            fallbackResolver(),
            (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
            (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
            (serviceName, interfaceName, window, limit) -> List.of(),
            traceId -> List.of(),
            noHitProvider,
            new StableContainerMetricProvider(),
            new StableCseMetricProvider(),
            new StableDatabaseMetricProvider(),
            new StableDcsMetricProvider(),
            new StableMqMetricProvider()
    );

    ProviderCollectionResult result = orchestrator.collect("task-service-log-no-hit", context(AlertCategory.SERVICE_ERROR_LOG));

    assertThat(result.ruleFallbackRequired()).isTrue();
    assertThat(result.degradedReason()).contains("ERROR runtime log context");
    assertThat(result.runtimeLogEvidence().collected()).isFalse();
}
```

Add this helper in the test class:

```java
private static final class CapturingRuntimeLogProvider implements RuntimeLogProvider {
    private final List<String> messageQueries = new CopyOnWriteArrayList<>();
    private final List<String> levels = new CopyOnWriteArrayList<>();

    @Override
    public List<RuntimeLogEntry> queryWarnAndErrorLogs(String traceId, String serviceName, TimeWindow window, int limit) {
        return List.of();
    }

    @Override
    public List<RuntimeLogEntry> queryByMessage(String serviceName, String cluster, String level,
                                                String messageQuery, TimeWindow window, int limit) {
        messageQueries.add(messageQuery);
        levels.add(level);
        if (!"loginAppointment failed".equals(messageQuery)) {
            return List.of();
        }
        return List.of(
                new RuntimeLogEntry(serviceName, cluster, "ams-front-1", "trace-log-1", "span-1", "ERROR",
                        "logger", "thread-1", "prebook loginAppointment failed",
                        "AppointmentLoginException", "AppointmentController.loginAppointment:87", window.endTime()),
                new RuntimeLogEntry(serviceName, cluster, "ams-front-1", "trace-log-2", "span-2", "ERROR",
                        "logger", "thread-2", "prebook loginAppointment failed",
                        "AppointmentLoginException", "AppointmentController.loginAppointment:87", window.endTime())
        );
    }
}
```

- [ ] **Step 2: Run the orchestration tests and verify they fail**

Run:

```bash
mvn -Dtest=DefaultProviderOrchestratorTests test
```

Expected: FAIL because `DefaultProviderOrchestrator` still always starts from `InterfaceLogProvider`.

- [ ] **Step 3: Add service-log branching in `collect`**

At the start of `collect`, branch by category:

```java
if (initialContext.alert().category() == AlertCategory.SERVICE_ERROR_LOG) {
    return collectServiceErrorLog(taskId, initialContext);
}
```

Implement a dedicated method that keeps resource, baseline, container, CSE, DB/DCS/MQ collection behavior but replaces the interface-log first step with message-based runtime logs:

```java
private ProviderCollectionResult collectServiceErrorLog(String taskId, AnalysisContext initialContext) {
    ProviderResult<List<RuntimeLogEntry>> runtimeLogResult = collectRuntimeLogsByMessage(taskId, initialContext);
    RuntimeLogEvidence runtimeLogEvidence = toServiceErrorRuntimeLogEvidence(runtimeLogResult);
    List<String> sampledTraceIds = runtimeLogEvidence.sampledTraceIds();
    ProviderResult<List<TraceRecord>> traceResult = collectTraces(taskId, initialContext, sampledTraceIds);
    ProviderResult<List<InterfaceLogEntry>> interfaceLogResult = providerCallExecutor.skipped(
            taskId,
            initialContext.alert().alertId(),
            PROVIDER_INTERFACE_LOG,
            false,
            "service error log alerts do not include interfaceName; trace evidence is derived from runtime log traceIds"
    );
    ProviderCollectionResult common = collectServiceLevelMetrics(taskId, initialContext, interfaceLogResult, traceResult, runtimeLogEvidence);
    boolean runtimeMissed = !runtimeLogEvidence.collected();
    return runtimeMissed
            ? withServiceLogFallback(common, "Could not locate ERROR runtime log context from alert message queries.")
            : common;
}
```

Use helper methods instead of duplicating the full metric collection body. The helper must keep `ResourceMappingProvider`, `ProviderQueryTargetResolver`, `BaselineProvider`, `ContainerMetricProvider`, `CseMetricProvider`, `DatabaseMetricProvider`, `DcsMetricProvider`, and `MqMetricProvider` behavior identical to the existing RPC path.

- [ ] **Step 4: Implement message query generation**

Add a method in `DefaultProviderOrchestrator`:

```java
private List<String> messageQueries(AnalysisContext context) {
    String normalized = context.alert().triggerValues() == null
            ? null
            : context.alert().triggerValues().normalizedLogQuery();
    if (normalized == null || normalized.isBlank()) {
        return List.of();
    }
    List<String> candidates = new ArrayList<>();
    String[] tokens = normalized.trim().split("\\s+");
    candidates.add(normalized.trim());
    if (tokens.length > 1) {
        candidates.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length)));
    }
    if (tokens.length > 2) {
        candidates.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, 2, tokens.length)));
    }
    if (tokens.length >= 2) {
        candidates.add(tokens[tokens.length - 2] + " " + tokens[tokens.length - 1]);
    }
    return candidates.stream()
            .map(String::trim)
            .filter(query -> query.length() >= context.options().errorLogMessageQueryMinChars())
            .filter(query -> query.split("\\s+").length >= context.options().errorLogMessageQueryMinTokens())
            .distinct()
            .limit(context.options().errorLogMessageQueryMaxAttempts())
            .toList();
}
```

- [ ] **Step 5: Implement runtime lookup attempts**

Add `collectRuntimeLogsByMessage`:

```java
private ProviderResult<List<RuntimeLogEntry>> collectRuntimeLogsByMessage(String taskId, AnalysisContext context) {
    List<RuntimeLogEvidence.QueryAttempt> attempts = new ArrayList<>();
    List<String> queries = messageQueries(context);
    for (int index = 0; index < queries.size(); index++) {
        String query = queries.get(index);
        ProviderResult<List<RuntimeLogEntry>> result = execute(taskId, context, PROVIDER_RUNTIME_LOG, true, () ->
                runtimeLogProvider.queryByMessage(
                        context.alert().serviceName(),
                        context.alert().cluster(),
                        context.options().errorLogQueryLevel(),
                        query,
                        context.alarmWindow(),
                        context.options().maxRuntimeLogSize()
                ));
        int hitCount = result.isUsable() && result.data() != null ? result.data().size() : 0;
        boolean selected = hitCount > 0;
        attempts.add(new RuntimeLogEvidence.QueryAttempt(
                "runtime.query.attempt." + (index + 1),
                query,
                context.options().errorLogQueryLevel(),
                hitCount,
                selected
        ));
        if (selected) {
            return ProviderResult.success(PROVIDER_RUNTIME_LOG, result.data(), result.latency());
        }
        if (result.isFailure()) {
            return result;
        }
    }
    return ProviderResult.failure(PROVIDER_RUNTIME_LOG, "RUNTIME_LOG_NOT_FOUND",
            "No ERROR runtime logs matched alert message queries: " + queries, Duration.ZERO);
}
```

If `attempts` need to be carried alongside the result, store them in a local holder method that returns a new private record:

```java
private record RuntimeMessageLookup(
        ProviderResult<List<RuntimeLogEntry>> result,
        List<RuntimeLogEvidence.QueryAttempt> attempts,
        String matchedLogQuery
) {
}
```

Use that record in `collectServiceErrorLog` so `RuntimeLogEvidence.availableMessageQuery` receives attempts and matched query.

- [ ] **Step 6: Update fallback and critical-provider logic**

Change interface-log fallback logic:

```java
boolean interfaceLogUnavailable = context.alert().category() != AlertCategory.SERVICE_ERROR_LOG
        && interfaceLogResult.isFailure();
```

Change critical provider logic:

```java
if (PROVIDER_RUNTIME_LOG.equals(providerName) && category == AlertCategory.SERVICE_ERROR_LOG) {
    return true;
}
```

Change `impactFor` runtime-log branch for service logs:

```java
case PROVIDER_RUNTIME_LOG -> category == AlertCategory.SERVICE_ERROR_LOG
        ? "Cannot locate the ERROR runtime log context that triggered this service-level alert."
        : "WARN/ERROR runtime log samples are unavailable for this alert window.";
```

- [ ] **Step 7: Run the orchestration tests**

Run:

```bash
mvn -Dtest=DefaultProviderOrchestratorTests test
```

Expected: PASS. The service-log path must not require interface logs to start, and it must require runtime-log message evidence.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java
git commit -m "feat: collect service error log evidence"
```

---

### Task 5: Service Error Log Rule Analyzer

**Files:**
- Create: `src/main/java/com/company/aiops/analysis/rule/log/ErrorLogRuleAnalyzer.java`
- Create: `src/main/java/com/company/aiops/analysis/rule/log/DefaultErrorLogRuleAnalyzer.java`
- Create: `src/main/java/com/company/aiops/analysis/rule/log/package-info.java`
- Modify: `src/main/java/com/company/aiops/analysis/rule/DefaultRuleAnalysisService.java`
- Modify: `src/main/java/com/company/aiops/llm/schema/LlmOutputSchema.java`
- Create: `src/test/java/com/company/aiops/analysis/rule/log/DefaultErrorLogRuleAnalyzerTests.java`

- [ ] **Step 1: Add failing rule analyzer tests**

Create `DefaultErrorLogRuleAnalyzerTests`:

```java
package com.company.aiops.analysis.rule.log;

import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.evidence.TraceRecord;
import com.company.aiops.analysis.rule.RuleAnalysisTestFixtures;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.InterfaceLogEntry;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultErrorLogRuleAnalyzerTests {

    private final DefaultErrorLogRuleAnalyzer analyzer = new DefaultErrorLogRuleAnalyzer();

    @Test
    void analyzeFindsCurrentServiceRuntimeExceptionFromMatchedErrorLogsAndTrace() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
        RuntimeLogEntry logEntry = new RuntimeLogEntry(
                "ams_front", "cluster2", "ams-front-1", "trace-log-1", "span-log-1", "ERROR",
                "com.company.ams.AppointmentController", "http-nio-1", "prebook loginAppointment failed",
                "AppointmentLoginException", "AppointmentController.loginAppointment:87",
                Instant.parse("2026-06-22T05:42:01Z")
        );
        InterfaceLogEntry traceSpan = new InterfaceLogEntry(
                "ams_front", "loginAppointment", "trace-log-1", "span-log-1", null,
                "uid-1", false, "E_LOG", "prebook loginAppointment failed", 40L,
                Instant.parse("2026-06-22T05:42:01Z")
        );

        var result = analyzer.analyze(RuleAnalysisTestFixtures.collection(
                alert,
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.missing("service error log alert has no interfaceName"),
                TraceEvidence.available(List.of(new TraceRecord("trace-log-1", List.of(traceSpan)))),
                RuntimeLogEvidence.availableMessageQuery(
                        List.of(logEntry),
                        "loginAppointment failed",
                        List.of(new RuntimeLogEvidence.QueryAttempt(
                                "runtime.query.attempt.1",
                                "loginAppointment failed",
                                "ERROR",
                                1,
                                true
                        )),
                        List.of("trace-log-1")
                ),
                MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(),
                        DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null)
        ));

        assertThat(result.ruleFindings()).extracting("type")
                .contains("CURRENT_SERVICE_RUNTIME_EXCEPTION");
        assertThat(result.candidateRootCauses()).first().satisfies(rootCause -> {
            assertThat(rootCause.causeType()).isEqualTo("CURRENT_SERVICE_RUNTIME_EXCEPTION");
            assertThat(rootCause.evidenceIds()).contains("runtime.log.1", "trace.failure.1");
        });
        assertThat(result.evidenceRefs()).extracting("evidenceId")
                .contains("runtime.query.attempt.1", "runtime.log.1", "trace.failure.1");
    }

    @Test
    void analyzeReturnsUnknownWhenRuntimeLogContextIsMissing() {
        AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();

        var result = analyzer.analyze(RuleAnalysisTestFixtures.collection(
                alert,
                BaselineEvidence.available(InterfaceBaseline.empty()),
                InterfaceLogEvidence.missing("service error log alert has no interfaceName"),
                TraceEvidence.missing("runtime log trace ids unavailable"),
                RuntimeLogEvidence.missing("No ERROR runtime logs matched alert message queries"),
                MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(),
                        DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null)
        ));

        assertThat(result.candidateRootCauses()).singleElement().satisfies(rootCause -> {
            assertThat(rootCause.causeType()).isEqualTo("UNKNOWN");
            assertThat(rootCause.reasoning()).contains("No ERROR runtime logs matched");
        });
        assertThat(result.insufficientEvidence()).anyMatch(item -> item.contains("ERROR runtime logs"));
    }
}
```

- [ ] **Step 2: Add a fixture helper**

In `RuleAnalysisTestFixtures`, add:

```java
public static AlertEvent serviceErrorLogAlert() {
    Instant occurTime = Instant.parse("2026-06-22T05:42:00Z");
    return new AlertEvent(
            "alert-service-log",
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
            new AlertTriggerValues(null, null, null, null, null, 2.0, "ERROR",
                    "DEFAULT prebook loginAppointment failed", "prebook loginAppointment failed", null, "raw")
    );
}
```

- [ ] **Step 3: Run the rule analyzer tests and verify they fail**

Run:

```bash
mvn -Dtest=DefaultErrorLogRuleAnalyzerTests test
```

Expected: compilation fails because the new analyzer does not exist.

- [ ] **Step 4: Implement analyzer interface and routing**

Create `ErrorLogRuleAnalyzer`:

```java
package com.company.aiops.analysis.rule.log;

import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

public interface ErrorLogRuleAnalyzer {
    RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult);
}
```

Update `DefaultRuleAnalysisService` constructor to accept `ErrorLogRuleAnalyzer`, and route:

```java
case SERVICE_ERROR_LOG -> errorLogRuleAnalyzer.analyze(collectionResult);
```

- [ ] **Step 5: Implement deterministic service-log rules**

Create `DefaultErrorLogRuleAnalyzer` with these core behaviors:

```java
@Component
public class DefaultErrorLogRuleAnalyzer implements ErrorLogRuleAnalyzer {

    @Override
    public RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult) {
        RuntimeLogEvidence runtime = collectionResult.runtimeLogEvidence();
        if (!runtime.collected() || runtime.entries().isEmpty()) {
            return missingRuntimeLogEvidence(runtime);
        }
        List<EvidenceReference> evidenceRefs = new ArrayList<>();
        List<RuleFinding> findings = new ArrayList<>();
        List<CandidateRootCause> candidates = new ArrayList<>();
        List<TraceObservation> traceObservations = traceObservations(collectionResult, evidenceRefs);
        addRuntimeEvidence(runtime, evidenceRefs);

        boolean currentServiceFailure = traceObservations.stream()
                .anyMatch(observation -> collectionResult.context().alert().serviceName().equals(observation.serviceName())
                        && !observation.success());
        if (currentServiceFailure || runtime.entries().stream().anyMatch(entry -> hasText(entry.exceptionType()))) {
            findings.add(new RuleFinding(
                    "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                    0.68,
                    "Matched ERROR runtime logs and trace evidence point to current service runtime failure.",
                    List.of("runtime.log.1", firstTraceEvidenceId(traceObservations))
            ));
            candidates.add(new CandidateRootCause(
                    "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                    68,
                    0.68,
                    List.of("runtime.log.1", firstTraceEvidenceId(traceObservations)),
                    "ERROR logs matched by message contain traceId and the first failed span is in the alerting service."
            ));
        }
        if (isSingleInstance(runtime.entries())) {
            findings.add(new RuleFinding(
                    "SINGLE_INSTANCE_ABNORMAL",
                    0.55,
                    "Matched ERROR logs are concentrated on one instance.",
                    List.of("runtime.instance.1")
            ));
            candidates.add(new CandidateRootCause(
                    "SINGLE_INSTANCE_ABNORMAL",
                    55,
                    0.55,
                    List.of("runtime.instance.1"),
                    "All matched ERROR logs are emitted by the same instance."
            ));
        }
        if (candidates.isEmpty()) {
            candidates.add(new CandidateRootCause(
                    "UNKNOWN",
                    20,
                    0.20,
                    List.of("runtime.log.1"),
                    "Runtime logs were found, but deterministic rules did not identify a confident root cause."
            ));
        }
        return new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                traceObservations,
                List.of(),
                List.of(),
                findings,
                candidates.stream().sorted(Comparator.comparingInt(CandidateRootCause::score).reversed()).toList(),
                evidenceRefs,
                List.of()
        );
    }
}
```

Implement the helper methods in the same class:

```java
private RuleAnalysisEvidence missingRuntimeLogEvidence(RuntimeLogEvidence runtime) {
    String reason = runtime.missingReason() == null
            ? "No ERROR runtime logs matched alert message queries."
            : runtime.missingReason();
    return new RuleAnalysisEvidence(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new RuleFinding("UNKNOWN", 0.20, reason, List.of("runtime.query.missing"))),
            List.of(new CandidateRootCause("UNKNOWN", 20, 0.20, List.of("runtime.query.missing"), reason)),
            List.of(new EvidenceReference("runtime.query.missing", "RUNTIME_LOG_QUERY", reason)),
            List.of(reason)
    );
}

private boolean isSingleInstance(List<RuntimeLogEntry> entries) {
    return entries.stream()
            .map(RuntimeLogEntry::instanceId)
            .filter(this::hasText)
            .distinct()
            .count() == 1;
}

private boolean hasText(String value) {
    return value != null && !value.isBlank();
}
```

- [ ] **Step 6: Add root cause types to schema**

Update `LlmOutputSchema.ROOT_CAUSE_TYPES`:

```java
"CURRENT_SERVICE_RUNTIME_EXCEPTION",
"REPEATED_BUSINESS_LOG_ERROR",
```

- [ ] **Step 7: Run rule and schema tests**

Run:

```bash
mvn -Dtest=DefaultErrorLogRuleAnalyzerTests,DefaultLlmOutputValidatorTests test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/company/aiops/analysis/rule src/main/java/com/company/aiops/llm/schema/LlmOutputSchema.java src/test/java/com/company/aiops/analysis/rule
git commit -m "feat: add service error log rule analyzer"
```

---

### Task 6: Evidence Compression For Service Error Logs

**Files:**
- Modify: `src/main/java/com/company/aiops/analysis/bundle/CompressedEvidenceBundle.java`
- Modify: `src/main/java/com/company/aiops/analysis/bundle/DefaultEvidenceCompressor.java`
- Modify: `src/test/java/com/company/aiops/analysis/bundle/DefaultEvidenceCompressorTests.java`

- [ ] **Step 1: Add failing compression test**

Add to `DefaultEvidenceCompressorTests`:

```java
@Test
void compressPreservesServiceErrorLogQueryAttemptsAndAlertValues() {
    AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
    RuntimeLogEntry logEntry = new RuntimeLogEntry(
            "ams_front", "cluster2", "ams-front-1", "trace-log-1", "span-log-1", "ERROR",
            "logger", "thread", "prebook loginAppointment failed",
            "AppointmentLoginException", "AppointmentController.loginAppointment:87",
            Instant.parse("2026-06-22T05:42:01Z")
    );
    ProviderCollectionResult collection = RuleAnalysisTestFixtures.collection(
            alert,
            BaselineEvidence.available(InterfaceBaseline.empty()),
            InterfaceLogEvidence.missing("service error log alert has no interfaceName"),
            TraceEvidence.available(List.of()),
            RuntimeLogEvidence.availableMessageQuery(
                    List.of(logEntry),
                    "loginAppointment failed",
                    List.of(
                            new RuntimeLogEvidence.QueryAttempt("runtime.query.attempt.1",
                                    "prebook loginAppointment failed", "ERROR", 0, false),
                            new RuntimeLogEvidence.QueryAttempt("runtime.query.attempt.2",
                                    "loginAppointment failed", "ERROR", 1, true)
                    ),
                    List.of("trace-log-1")
            ),
            MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(),
                    DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null)
    );
    RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new RuleFinding("CURRENT_SERVICE_RUNTIME_EXCEPTION", 0.68,
                    "runtime exception", List.of("runtime.log.1"))),
            List.of(new CandidateRootCause("CURRENT_SERVICE_RUNTIME_EXCEPTION", 68, 0.68,
                    List.of("runtime.log.1", "runtime.query.attempt.2"), "matched runtime log")),
            List.of(new EvidenceReference("runtime.log.1", "RUNTIME_LOG", "matched runtime log")),
            List.of()
    );

    CompressedEvidenceBundle bundle = compressor.compress(collection, ruleAnalysis);

    assertThat(bundle.alert().failureCount()).isEqualTo(2.0);
    assertThat(bundle.alert().logLevel()).isEqualTo("ERROR");
    assertThat(bundle.alert().logMessage()).isEqualTo("DEFAULT prebook loginAppointment failed");
    assertThat(bundle.alert().normalizedLogQuery()).isEqualTo("prebook loginAppointment failed");
    assertThat(bundle.runtimeLogs().messageQueryMatched()).isTrue();
    assertThat(bundle.runtimeLogs().matchedLogQuery()).isEqualTo("loginAppointment failed");
    assertThat(bundle.runtimeLogs().queryAttempts()).extracting("evidenceId")
            .containsExactly("runtime.query.attempt.1", "runtime.query.attempt.2");
    assertThat(bundle.evidenceRefs()).extracting(EvidenceReference::evidenceId)
            .contains("runtime.query.attempt.1", "runtime.query.attempt.2", "runtime.log.1");
}
```

- [ ] **Step 2: Run compressor test and verify it fails**

Run:

```bash
mvn -Dtest=DefaultEvidenceCompressorTests test
```

Expected: FAIL because compressed alert/runtime snapshots lack service-log fields.

- [ ] **Step 3: Extend compressed alert snapshot**

Add fields to `CompressedEvidenceBundle.AlertSnapshot` after `p99LatencyMs`:

```java
String failureCountEvidenceId,
Double failureCount,
String logLevelEvidenceId,
String logLevel,
String logMessageEvidenceId,
String logMessage,
String normalizedLogQueryEvidenceId,
String normalizedLogQuery
```

In `DefaultEvidenceCompressor`, add constants:

```java
private static final String ALERT_FAILURE_COUNT_EVIDENCE_ID = "alert.failure.count";
private static final String ALERT_LOG_LEVEL_EVIDENCE_ID = "alert.log.level";
private static final String ALERT_LOG_MESSAGE_EVIDENCE_ID = "alert.log.message";
private static final String ALERT_NORMALIZED_LOG_QUERY_EVIDENCE_ID = "alert.log.normalized_query";
```

Update `alertSnapshot` to populate the values from `alert.triggerValues()`.

- [ ] **Step 4: Extend runtime log snapshot**

Add to `CompressedEvidenceBundle.RuntimeLogSnapshot`:

```java
boolean messageQueryMatched,
String matchedLogQuery,
List<RuntimeLogQueryAttempt> queryAttempts,
List<String> sampledTraceIds
```

Add nested record:

```java
public record RuntimeLogQueryAttempt(
        String evidenceId,
        String query,
        String level,
        int hitCount,
        boolean selected
) {
}
```

Update `DefaultEvidenceCompressor` to copy attempts:

```java
List<CompressedEvidenceBundle.RuntimeLogQueryAttempt> attempts = collection.runtimeLogEvidence().queryAttempts().stream()
        .map(attempt -> new CompressedEvidenceBundle.RuntimeLogQueryAttempt(
                attempt.evidenceId(),
                attempt.query(),
                attempt.level(),
                attempt.hitCount(),
                attempt.selected()
        ))
        .toList();
```

When building evidence references, add:

```java
for (CompressedEvidenceBundle.RuntimeLogQueryAttempt attempt : runtimeLogSnapshot.queryAttempts()) {
    references.putIfAbsent(attempt.evidenceId(), new EvidenceReference(
            attempt.evidenceId(),
            "RUNTIME_LOG_QUERY",
            "Runtime log query attempt level=%s query=%s hitCount=%d selected=%s."
                    .formatted(attempt.level(), attempt.query(), attempt.hitCount(), attempt.selected())
    ));
}
```

- [ ] **Step 5: Update constructor call sites**

Run:

```bash
rg "new CompressedEvidenceBundle\\.AlertSnapshot|new CompressedEvidenceBundle\\.RuntimeLogSnapshot" src
```

For existing RPC tests, pass null service-log fields:

```java
"alert.failure.count",
null,
"alert.log.level",
null,
"alert.log.message",
null,
"alert.log.normalized_query",
null
```

For existing runtime snapshots without message query, pass:

```java
false,
null,
List.of(),
List.of()
```

- [ ] **Step 6: Run compressor and LLM tests**

Run:

```bash
mvn -Dtest=DefaultEvidenceCompressorTests,DefaultLlmAnalysisServiceTests,RpcErrorRatePromptBuilderTests,RpcLatencyPromptBuilderTests test
```

Expected: PASS after constructor updates.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/aiops/analysis/bundle src/test/java/com/company/aiops/analysis/bundle src/test/java/com/company/aiops/llm src/test/java/com/company/aiops/analysis/fallback
git commit -m "feat: compress service error log evidence"
```

---

### Task 7: Service Error Log Prompt And Mock LLM

**Files:**
- Create: `src/main/java/com/company/aiops/llm/prompt/ServiceErrorLogPromptBuilder.java`
- Modify: `src/main/java/com/company/aiops/llm/client/mock/MockLlmClient.java`
- Create: `src/test/java/com/company/aiops/llm/prompt/ServiceErrorLogPromptBuilderTests.java`
- Modify: `src/test/java/com/company/aiops/llm/service/DefaultLlmAnalysisServiceTests.java`

- [ ] **Step 1: Add failing prompt test**

Create `ServiceErrorLogPromptBuilderTests`:

```java
package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceErrorLogPromptBuilderTests {

    @Test
    void buildMessagesHighlightsMessageQueryTraceAndFailureCountRules() {
        ServiceErrorLogPromptBuilder builder = new ServiceErrorLogPromptBuilder(
                new JsonMapper(new ObjectMapper().findAndRegisterModules())
        );

        String combined = String.join("\n", builder.buildMessages(compressedEvidence(), null).stream()
                .map(message -> message.content())
                .toList());

        assertThat(builder.supportedCategory()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
        assertThat(combined).contains("服务错误日志告警");
        assertThat(combined).contains("ERROR 运行日志上下文");
        assertThat(combined).contains("matchedLogQuery");
        assertThat(combined).contains("traceId");
        assertThat(combined).contains("failureCount 较小时只能说明当前影响范围可能有限");
        assertThat(combined).contains("CURRENT_SERVICE_RUNTIME_EXCEPTION");
        assertThat(combined).contains("REPEATED_BUSINESS_LOG_ERROR");
    }

    private CompressedEvidenceBundle compressedEvidence() {
        Instant occurTime = Instant.parse("2026-06-22T05:42:00Z");
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.SERVICE_ERROR_LOG,
                        "ams_front",
                        null,
                        "cluster2",
                        occurTime,
                        "alert.request.count",
                        null,
                        "alert.fail.rate",
                        null,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null,
                        "alert.failure.count",
                        2.0,
                        "alert.log.level",
                        "ERROR",
                        "alert.log.message",
                        "DEFAULT prebook loginAppointment failed",
                        "alert.log.normalized_query",
                        "prebook loginAppointment failed"
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null,
                        "baseline.request.count", "baseline.fail.rate",
                        "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(false, "service alert has no interface",
                        "interface.request.count", 0, "interface.failure.rate", 0, 0.0,
                        "interface.latency.profile", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of("trace-log-1"), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of(),
                        true, "loginAppointment failed",
                        List.of(new CompressedEvidenceBundle.RuntimeLogQueryAttempt(
                                "runtime.query.attempt.1", "loginAppointment failed", "ERROR", 2, true)),
                        List.of("trace-log-1")),
                new CompressedEvidenceBundle.MetricSnapshot(true, null,
                        ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(),
                        DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty("ams_front", "cluster2"),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("runtime.query.attempt.1", "RUNTIME_LOG_QUERY", "matched query")),
                List.of(),
                List.of()
        );
    }
}
```

- [ ] **Step 2: Run prompt test and verify it fails**

Run:

```bash
mvn -Dtest=ServiceErrorLogPromptBuilderTests test
```

Expected: compilation fails because `ServiceErrorLogPromptBuilder` does not exist.

- [ ] **Step 3: Implement prompt builder**

Create `ServiceErrorLogPromptBuilder` using the same structure as `RpcLatencyPromptBuilder`. Set:

```java
@Override
public AlertCategory supportedCategory() {
    return AlertCategory.SERVICE_ERROR_LOG;
}
```

Use this default user instruction:

```java
private static final String DEFAULT_USER_INSTRUCTIONS = """
        请分析以下服务错误日志告警。

        分析重点：
        1. 判断告警 msg 是否成功查询到 ERROR 运行日志上下文。
        2. 如果完整 msg 未命中而使用了切割后的 matchedLogQuery，需要说明切割查询带来的不确定性。
        3. 判断是否提取到 traceId，并根据 trace 判断首个失败点在当前服务还是下游服务。
        4. 判断错误是否集中在单实例、少量 trace、少量 uid、同一 exceptionType 或同一 stackTop。
        5. 判断数据库、DCS、MQ、容器 CPU、内存、GC、实例状态、CSE 使用率是否与错误日志同窗口相关。
        6. failureCount 较小时只能说明当前影响范围可能有限，不能判定为误报或跳过分析。
        7. 给出人工排查建议，包括优先查看的 traceId、实例、logger、exceptionType、matchedLogQuery 和资源实例，但不要执行任何动作。
        """;
```

Update the fixed output contract cause-type list to include:

```text
CURRENT_SERVICE_RUNTIME_EXCEPTION, REPEATED_BUSINESS_LOG_ERROR
```

- [ ] **Step 4: Update MockLlmClient**

In `buildValidJson`, add:

```java
case SERVICE_ERROR_LOG -> buildServiceErrorLogJson(compressedEvidence);
```

Implement:

```java
private String buildServiceErrorLogJson(CompressedEvidenceBundle compressedEvidence) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("summary", "服务 ERROR 日志已通过 msg 查询命中并提取到 traceId，首个失败点更可能在当前服务。");
    root.set("impact", impact("LOW", "failureCount 较小，当前影响范围可能有限，但仍需确认错误是否重复出现。"));

    ArrayNode rootCauses = root.putArray("rootCauses");
    rootCauses.add(rootCause(
            "CURRENT_SERVICE_RUNTIME_EXCEPTION",
            "命中的 ERROR 日志和 trace 证据指向当前服务运行时异常。",
            0.72,
            evidenceIds(compressedEvidence, List.of("runtime.log.1", "runtime.query.attempt.1"), 2),
            "日志查询命中 ERROR 级别记录，且关联 traceId 可用于定位当前服务失败。"
    ));

    ArrayNode recommendations = root.putArray("recommendations");
    recommendations.add(recommendation(
            "P1",
            "CHECK_LOG",
            "查看 matchedLogQuery 对应的 ERROR 日志上下文和异常栈顶部。",
            firstExistingEvidenceId(compressedEvidence, "runtime.query.attempt.1")
    ));
    recommendations.add(recommendation(
            "P1",
            "CHECK_TRACE",
            "根据日志中提取到的 traceId 查看首个失败 span。",
            firstExistingEvidenceId(compressedEvidence, "trace.failure.1")
    ));

    root.put("overallConfidence", compressedEvidence.missingEvidence().isEmpty() ? 0.72 : 0.66);
    root.set("insufficientEvidence", insufficientEvidence(compressedEvidence));
    return writeJson(root);
}
```

- [ ] **Step 5: Add LLM service coverage**

In `DefaultLlmAnalysisServiceTests`, add `new ServiceErrorLogPromptBuilder(jsonMapper)` to the prompt builder list in test setup, and add a test using `SERVICE_ERROR_LOG` compressed evidence:

```java
@Test
void analyzeSupportsServiceErrorLogPromptBuilder() {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    JsonMapper jsonMapper = new JsonMapper(objectMapper);
    InMemoryLlmCallLogRepository llmCallLogRepository = new InMemoryLlmCallLogRepository();
    LlmClient llmClient = request -> new LlmResponse("""
            {
              "summary": "service log summary",
              "impact": {"level": "LOW", "description": "limited failure count"},
              "rootCauses": [{
                "causeType": "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                "description": "current service runtime exception",
                "confidence": 0.7,
                "evidenceIds": ["runtime.query.attempt.1"],
                "reasoning": "matched ERROR runtime log query"
              }],
              "recommendations": [{
                "priority": "P1",
                "actionType": "CHECK_LOG",
                "description": "inspect matched error log",
                "target": "runtime.query.attempt.1"
              }],
              "overallConfidence": 0.7,
              "insufficientEvidence": []
            }
            """, "mock-model", 100, 60, Duration.ofMillis(20), "stop");
    DefaultLlmAnalysisService service = new DefaultLlmAnalysisService(
            properties(),
            jsonMapper,
            Map.of("mock", llmClient),
            List.of(new RpcErrorRatePromptBuilder(jsonMapper),
                    new RpcLatencyPromptBuilder(jsonMapper),
                    new ServiceErrorLogPromptBuilder(jsonMapper)),
            new DefaultLlmOutputValidator(objectMapper),
            llmCallLogRepository
    );

    var result = service.analyze("task-service-log", serviceLogCollection(), serviceLogCompressedEvidence());

    assertThat(result.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
    assertThat(result.rootCauses()).singleElement()
            .satisfies(rootCause -> assertThat(rootCause.causeType()).isEqualTo("CURRENT_SERVICE_RUNTIME_EXCEPTION"));
}
```

- [ ] **Step 6: Run LLM/prompt tests**

Run:

```bash
mvn -Dtest=ServiceErrorLogPromptBuilderTests,DefaultLlmAnalysisServiceTests,MiniMaxLlmIntegrationTest test
```

Expected: unit tests PASS. If `MiniMaxLlmIntegrationTest` is environment-gated and skips without credentials, that is acceptable.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/company/aiops/llm src/test/java/com/company/aiops/llm
git commit -m "feat: add service error log llm prompt"
```

---

### Task 8: Rule-Based Fallback Output

**Files:**
- Modify: `src/main/java/com/company/aiops/analysis/fallback/DefaultRuleBasedFallbackAnalyzer.java`
- Modify: `src/test/java/com/company/aiops/analysis/fallback/DefaultRuleBasedFallbackAnalyzerTests.java`

- [ ] **Step 1: Add failing fallback tests**

Add to `DefaultRuleBasedFallbackAnalyzerTests`:

```java
@Test
void analyzeBuildsServiceErrorLogFallbackFromRuntimeLogRuleEvidence() {
    AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
    ProviderCollectionResult collection = completeServiceLogCollection(alert);
    RuleAnalysisEvidence ruleAnalysis = new RuleAnalysisEvidence(
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new RuleFinding("CURRENT_SERVICE_RUNTIME_EXCEPTION", 0.68,
                    "matched ERROR runtime log", List.of("runtime.log.1"))),
            List.of(new CandidateRootCause("CURRENT_SERVICE_RUNTIME_EXCEPTION", 68, 0.68,
                    List.of("runtime.log.1", "runtime.query.attempt.1"), "matched current service error log")),
            List.of(new EvidenceReference("runtime.log.1", "RUNTIME_LOG", "matched runtime log"),
                    new EvidenceReference("runtime.query.attempt.1", "RUNTIME_LOG_QUERY", "selected query")),
            List.of()
    );

    var result = analyzer.analyze(new RuleBasedAnalysisRequest(
            "task-service-log",
            collection,
            ruleAnalysis,
            null,
            AnalysisMode.RULE_BASED_ONLY,
            "DISABLED",
            null
    ));

    assertThat(result.summary()).contains("服务错误日志");
    assertThat(result.impact().description()).contains("failureCount");
    assertThat(result.rootCauses()).singleElement()
            .satisfies(rootCause -> assertThat(rootCause.causeType()).isEqualTo("CURRENT_SERVICE_RUNTIME_EXCEPTION"));
    assertThat(result.recommendations()).extracting("actionType")
            .contains("CHECK_LOG", "CHECK_TRACE");
}
```

Add:

```java
@Test
void analyzeBuildsDegradedServiceLogResultWhenRuntimeLogLookupMisses() {
    AlertEvent alert = RuleAnalysisTestFixtures.serviceErrorLogAlert();
    ProviderCollectionResult collection = new ProviderCollectionResult(
            context(alert),
            BaselineEvidence.available(InterfaceBaseline.empty()),
            InterfaceLogEvidence.missing("service error log alert has no interfaceName"),
            TraceEvidence.missing("runtime log trace ids unavailable"),
            RuntimeLogEvidence.missing("No ERROR runtime logs matched alert message queries"),
            MetricEvidence.available(ContainerMetrics.empty(), CseMetrics.empty(),
                    DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), null),
            List.of(new MissingEvidence("runtimeLog",
                    "No ERROR runtime logs matched alert message queries",
                    true,
                    "Cannot locate the ERROR runtime log context that triggered this service-level alert.")),
            true,
            true,
            "Could not locate ERROR runtime log context from alert message queries.",
            List.of()
    );

    var result = analyzer.analyze(new RuleBasedAnalysisRequest(
            "task-service-log-miss",
            collection,
            null,
            null,
            AnalysisMode.RULE_BASED_DEGRADED,
            "SKIPPED_BY_PROVIDER_FAILURE",
            collection.degradedReason()
    ));

    assertThat(result.degraded()).isTrue();
    assertThat(result.rootCauses()).singleElement()
            .satisfies(rootCause -> assertThat(rootCause.causeType()).isEqualTo("UNKNOWN"));
    assertThat(result.summary()).contains("未能根据告警 msg 定位 ERROR 运行日志上下文");
    assertThat(result.recommendations()).extracting("actionType").contains("CHECK_LOG");
}
```

- [ ] **Step 2: Run fallback tests and verify they fail**

Run:

```bash
mvn -Dtest=DefaultRuleBasedFallbackAnalyzerTests test
```

Expected: FAIL because fallback wording and recommendation mapping do not know `SERVICE_ERROR_LOG`.

- [ ] **Step 3: Add service-log summary handling**

In `buildSummary`, before generic wording, branch:

```java
if (alert.category() == AlertCategory.SERVICE_ERROR_LOG) {
    return serviceErrorLogSummary(request, rootCauses, degradedReason);
}
```

Implement:

```java
private String serviceErrorLogSummary(
        RuleBasedAnalysisRequest request,
        List<RootCauseHypothesis> rootCauses,
        String degradedReason
) {
    if (degradedReason != null && degradedReason.contains("ERROR runtime log context")) {
        return "【规则降级分析】未能根据告警 msg 定位 ERROR 运行日志上下文，无法提取 traceId 进行链路归因。";
    }
    String topCause = rootCauses.isEmpty() ? "UNKNOWN" : rootCauses.get(0).causeType();
    return switch (topCause) {
        case "CURRENT_SERVICE_RUNTIME_EXCEPTION" ->
                "【规则分析】服务错误日志已命中 ERROR 运行日志上下文，关联证据更倾向当前服务运行时异常。";
        case "DOWNSTREAM_RPC_FAILURE" ->
                "【规则分析】服务错误日志关联 trace 的首个失败点疑似在下游服务。";
        case "REPEATED_BUSINESS_LOG_ERROR" ->
                "【规则分析】服务错误日志更像重复业务动作失败或可预期业务失败日志。";
        default ->
                "【规则分析】服务错误日志已完成证据采集，但规则未能定位明确根因。";
    };
}
```

- [ ] **Step 4: Add service-log impact and recommendations**

In `buildImpactAssessment`, branch for `SERVICE_ERROR_LOG`:

```java
case SERVICE_ERROR_LOG -> serviceErrorLogImpact(alert, request, degraded, degradedReason);
```

Implement:

```java
private ImpactAssessment serviceErrorLogImpact(
        AlertEvent alert,
        RuleBasedAnalysisRequest request,
        boolean degraded,
        String degradedReason
) {
    Double failureCount = alert.triggerValues() == null ? null : alert.triggerValues().failureCount();
    String description = failureCount != null && failureCount <= 3
            ? "failureCount=%s，当前触发次数较少，影响范围可能有限，但仍需确认 ERROR 日志对应 trace 和实例。".formatted(failureCount)
            : "服务 ERROR 日志触发告警，需要结合日志命中数量、traceId 和实例分布确认影响范围。";
    if (degraded) {
        description = description + " " + defaultIfBlank(degradedReason, "当前结果为规则降级分析。");
    }
    return new ImpactAssessment(failureCount != null && failureCount <= 3 ? "LOW" : "MEDIUM", description);
}
```

In recommendation mapping for cause types, add:

```java
case "CURRENT_SERVICE_RUNTIME_EXCEPTION" -> List.of(
        new Recommendation("P1", "CHECK_LOG", "查看 matchedLogQuery 命中的 ERROR 日志上下文和异常栈顶部。", firstEvidenceId(rootCause)),
        new Recommendation("P1", "CHECK_TRACE", "根据日志提取的 traceId 查看首个失败 span。", firstEvidenceId(rootCause))
);
case "REPEATED_BUSINESS_LOG_ERROR" -> List.of(
        new Recommendation("P2", "CHECK_LOG", "确认该业务动作失败是否属于可预期业务失败以及是否需要调整告警规则。", firstEvidenceId(rootCause))
);
```

For service-log degraded `UNKNOWN`, return:

```java
new Recommendation("P1", "CHECK_LOG",
        "使用 normalizedLogQuery 和切割后的 matchedLogQuery 在运行日志系统中人工复核 ERROR 日志。",
        alert.serviceName())
```

- [ ] **Step 5: Run fallback tests**

Run:

```bash
mvn -Dtest=DefaultRuleBasedFallbackAnalyzerTests test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/company/aiops/analysis/fallback/DefaultRuleBasedFallbackAnalyzer.java src/test/java/com/company/aiops/analysis/fallback/DefaultRuleBasedFallbackAnalyzerTests.java
git commit -m "feat: add service error log fallback output"
```

---

### Task 9: End-To-End Integration And Replay

**Files:**
- Modify: `src/test/java/com/company/aiops/alert/AlertIngressIntegrationTests.java`
- Modify: `src/test/java/com/company/aiops/analysis/replay/RuleOnlyReplayIntegrationTests.java`
- Modify: `src/test/java/com/company/aiops/analysis/orchestrator/worker/AnalysisJobWorkerTests.java`
- Modify: `src/test/java/com/company/aiops/analysis/query/model/AnalysisTaskQueryResponseTests.java`

- [ ] **Step 1: Add end-to-end webhook test**

In `AlertIngressIntegrationTests`, add:

```java
@Test
void serviceErrorLogWebhookCreatesTaskAndUsesServiceLogDedupeKey() {
    String alertId = nextNumericAlertId();
    ResponseEntity<JsonNode> response = postWebhook(AlertSamples.withAlertId(AlertSamples.SERVICE_ERROR_LOG_ALERT, alertId));

    JsonNode data = requireData(response);
    assertThat(data.path("duplicate").asBoolean()).isFalse();
    assertThat(data.path("taskId").asText()).isNotBlank();

    String dedupeKey = jdbcTemplate.queryForObject(
            "SELECT dedupe_key FROM aiops_analysis_task WHERE alert_id = ?",
            String.class,
            alertId
    );
    assertThat(dedupeKey).isEqualTo("SERVICE_ERROR_LOG|ams_front|cluster2|prebook loginappointment failed");
}
```

- [ ] **Step 2: Add rule-only replay coverage**

In `RuleOnlyReplayIntegrationTests`, add a test that posts `SERVICE_ERROR_LOG_ALERT`, waits for completion, and asserts:

```java
assertThat(result.category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
assertThat(result.result().summary()).contains("服务错误日志");
assertThat(result.result().evidenceRefs()).extracting(EvidenceReference::evidenceId)
        .contains("runtime.query.attempt.1");
```

Use the existing wait/query helper in that test class.

- [ ] **Step 3: Run integration tests and verify initial failures**

Run:

```bash
mvn -Dtest=AlertIngressIntegrationTests,RuleOnlyReplayIntegrationTests test
```

Expected: failures identify any missing worker/query/output branches.

- [ ] **Step 4: Fix worker/query display labels**

Search:

```bash
rg "RPC_ERROR_RATE|RPC_LATENCY|未知类型|RPC错误率|RPC时延" src/main/java src/test/java
```

Apply these additions where category display or switch handling is exhaustive:

```java
case SERVICE_ERROR_LOG -> "服务错误日志";
```

In any switch that currently handles only `RPC_ERROR_RATE`, `RPC_LATENCY`, and `UNKNOWN`, add a `SERVICE_ERROR_LOG` branch with the behavior introduced in earlier tasks.

- [ ] **Step 5: Run focused and full test suites**

Run:

```bash
mvn -Dtest=AlertIngressIntegrationTests,DefaultProviderOrchestratorTests,DefaultErrorLogRuleAnalyzerTests,DefaultEvidenceCompressorTests,ServiceErrorLogPromptBuilderTests,DefaultRuleBasedFallbackAnalyzerTests test
```

Expected: PASS.

Then run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java src/main/resources/application.yml
git commit -m "feat: support service error log alert analysis"
```

---

### Task 10: Documentation And Manual Verification Notes

**Files:**
- Modify: `docs/aiops-alert-analysis-architecture.md`
- Modify: `docs/development-log-2026-06-22.md`

- [ ] **Step 1: Update architecture documentation**

Add `SERVICE_ERROR_LOG` to the supported category list in `docs/aiops-alert-analysis-architecture.md`, and add this provider-flow note:

```markdown
For `SERVICE_ERROR_LOG`, runtime log lookup is the primary evidence source. The system searches
`level=ERROR` logs by normalized alert msg, removes only a leading `DEFAULT` wrapper token, cuts the
message when the full query misses, extracts trace IDs from matched logs, and then collects Trace and
metric evidence.
```

- [ ] **Step 2: Add development log**

Create `docs/development-log-2026-06-22.md` with:

````markdown
# AIOps 告警自动分析系统开发日志

日期：2026-06-22

## 本次目标

实现服务错误日志告警 `SERVICE_ERROR_LOG`，覆盖告警解析、按 ERROR 日志 msg 查询、msg 切割查询、traceId 提取、规则分析、证据压缩、LLM Prompt 和规则降级输出。

## 验证命令

```bash
mvn test
```

## 关键口径

1. 告警 msg 查询运行日志前只移除开头单独出现的 `DEFAULT`。
2. RuntimeLogProvider 查询必须带 `level=ERROR`。
3. 完整 msg 无命中时逐步切割 msg 查询，并保留 queryAttempts。
4. 所有 msg 查询无命中时返回规则降级结果，不调用 LLM 做无证据推理。
````

- [ ] **Step 3: Run final verification**

Run:

```bash
git status --short
mvn test
```

Expected: only intended source/test/docs files are modified before commit; `mvn test` passes.

- [ ] **Step 4: Commit docs**

```bash
git add docs/aiops-alert-analysis-architecture.md docs/development-log-2026-06-22.md
git commit -m "docs: document service error log alert support"
```

---

## Self-Review

Spec coverage:

- `SERVICE_ERROR_LOG` category: Task 1 and Task 9.
- Service-level alerts without `interfaceName`: Task 1, Task 4, Task 9.
- Remove leading `DEFAULT` before provider query: Task 1 and Task 4.
- Query RuntimeLogProvider with `level=ERROR`: Task 3 and Task 4.
- Full message miss then cut message query: Task 3 and Task 4.
- Preserve query attempts and matched query in evidence: Task 3, Task 4, Task 6.
- Extract trace IDs from matched logs and query TraceProvider: Task 4 and Task 5.
- Rule analysis for current service, downstream, repeated business error, single instance, and resource correlation: Task 5.
- LLM prompt and schema support: Task 7.
- Rule fallback for all-message-miss and LLM-disabled scenarios: Task 8.
- End-to-end webhook/replay acceptance: Task 9.

Placeholder scan:

- This plan contains no forbidden placeholder markers or unspecified implementation steps.
- Every task has concrete files, test commands, implementation snippets, and expected results.

Type consistency:

- `AlertTriggerValues` field order is used consistently in tests and fixtures.
- `RuntimeLogEntry` field order is used consistently in provider, tests, and compressor.
- New root cause strings match `LlmOutputSchema` and prompt contracts.

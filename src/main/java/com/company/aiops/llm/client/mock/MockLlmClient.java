package com.company.aiops.llm.client.mock;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.client.LlmClient;
import com.company.aiops.llm.client.LlmClientException;
import com.company.aiops.llm.client.LlmMessage;
import com.company.aiops.llm.client.LlmRequest;
import com.company.aiops.llm.client.LlmResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Mock model client that derives deterministic JSON from the compressed evidence embedded in the
 * prompt and supports token-based failure modes for stage-8 tests.
 */
@Component("mock")
public class MockLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;

    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        CompressedEvidenceBundle compressedEvidence = extractEvidence(request);
        String serviceName = compressedEvidence.alert() == null ? null : compressedEvidence.alert().serviceName();
        boolean repairPrompt = request.messages().stream()
                .map(LlmMessage::content)
                .anyMatch(content -> content.contains("上一次输出校验失败"));

        if (containsToken(serviceName, MockLlmTokens.TIMEOUT)) {
            throw new LlmClientException(
                    LlmCallStatus.TIMEOUT,
                    "mock llm timed out for service " + defaultIfBlank(serviceName, "UNKNOWN")
            );
        }
        if (containsToken(serviceName, MockLlmTokens.RETRY_SUCCESS_INVALID_JSON) && !repairPrompt) {
            return rawResponse("{\"summary\":", request);
        }
        if (containsToken(serviceName, MockLlmTokens.INVALID_JSON)) {
            return rawResponse("{\"summary\":", request);
        }
        if (containsToken(serviceName, MockLlmTokens.INVALID_SCHEMA)) {
            return rawResponse("""
                    {
                      "summary": "schema broken"
                    }
                    """, request);
        }
        if (containsToken(serviceName, MockLlmTokens.INVALID_EVIDENCE)) {
            return rawResponse(buildInvalidEvidenceJson(compressedEvidence), request);
        }
        return rawResponse(buildValidJson(compressedEvidence), request);
    }

    private LlmResponse rawResponse(String content, LlmRequest request) {
        int inputTokens = Math.max(80, request.messages().stream().mapToInt(message -> message.content().length() / 4 + 1).sum());
        int outputTokens = Math.max(40, content.length() / 4 + 1);
        return new LlmResponse(
                content,
                request.model(),
                inputTokens,
                outputTokens,
                Duration.ofMillis(35),
                "stop"
        );
    }

    private String buildValidJson(CompressedEvidenceBundle compressedEvidence) {
        AlertCategory category = compressedEvidence.alert() == null
                ? AlertCategory.RPC_ERROR_RATE
                : compressedEvidence.alert().category();
        return switch (category) {
            case RPC_ERROR_RATE -> buildErrorRateJson(compressedEvidence);
            case RPC_LATENCY -> buildLatencyJson(compressedEvidence);
            case UNKNOWN -> buildErrorRateJson(compressedEvidence);
        };
    }

    private String buildErrorRateJson(CompressedEvidenceBundle compressedEvidence) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", "告警窗口请求量有限，但失败集中在业务校验语义 errorCode，疑似业务校验失败未屏蔽。");
        root.set("impact", impact("LOW", "当前请求量较小，影响范围可能有限，但需要确认是否应屏蔽业务校验失败。"));

        ArrayNode rootCauses = root.putArray("rootCauses");
        rootCauses.add(rootCause(
                "LOW_TRAFFIC_LIMITED_IMPACT",
                "告警窗口请求量较小，当前影响范围可能有限，但仍需继续确认失败语义。",
                0.83,
                evidenceIds(compressedEvidence, List.of("alert.request.count", "rule.limited_impact"), 2),
                "请求量较小，只能说明影响范围有限，不能直接判定为误报。"
        ));
        rootCauses.add(rootCause(
                "BUSINESS_ERROR_CODE_NOT_MASKED",
                "失败集中在同一业务 errorCode，相关 errorMsg 呈业务校验语义，可能属于应屏蔽但未屏蔽的业务失败。",
                0.72,
                evidenceIds(compressedEvidence, List.of("error.top.1", "uid.top.1"), 2),
                "接口日志中的 Top errorCode 与代表性 errorMsg 指向业务校验语义，未观察到明显资源异常。"
        ));

        ArrayNode recommendations = root.putArray("recommendations");
        recommendations.add(recommendation(
                "P1",
                "EVALUATE_ERROR_CODE_MASKING",
                "结合 Top errorCode 对应的 errorMsg，确认是否应加入告警屏蔽配置。",
                firstExistingEvidenceId(compressedEvidence, "error.top.1")
        ));
        recommendations.add(recommendation(
                "P2",
                "CHECK_LOG",
                "复核接口错误日志中的代表性 errorMsg，确认是否均为业务校验失败。",
                defaultIfBlank(compressedEvidence.alert() == null ? null : compressedEvidence.alert().interfaceName(), "UNKNOWN")
        ));

        root.put("overallConfidence", compressedEvidence.missingEvidence().isEmpty() ? 0.79 : 0.74);
        root.set("insufficientEvidence", insufficientEvidence(compressedEvidence));
        return writeJson(root);
    }

    private String buildLatencyJson(CompressedEvidenceBundle compressedEvidence) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", "该时延告警表现为平均时延升高，慢请求样本中的下游调用耗时占比更高，疑似下游链路变慢。");
        root.set("impact", impact("MEDIUM", "平均时延已超过 3 秒，可能影响当前接口处理效率。"));

        ArrayNode rootCauses = root.putArray("rootCauses");
        rootCauses.add(rootCause(
                "DOWNSTREAM_RPC_LATENCY",
                "慢请求中最慢 span 多次集中在同一条下游调用链路。",
                0.77,
                evidenceIds(compressedEvidence, List.of("trace.slow.aggregation.1", "trace.slow.1"), 2),
                "慢 trace 聚合结果和代表性样本都指向下游调用耗时更高。"
        ));
        rootCauses.add(rootCause(
                "DCS_LATENCY_OR_FAILURE",
                "缓存相关指标在告警窗口内出现抬升，可能放大接口耗时。",
                0.60,
                evidenceIds(compressedEvidence, List.of("metric.dcs.p99.1", "metric.dcs.slow_query.1"), 2),
                "相关资源指标与时延窗口重合，需要进一步确认缓存链路。"
        ));

        ArrayNode recommendations = root.putArray("recommendations");
        recommendations.add(recommendation(
                "P1",
                "CHECK_TRACE",
                "优先检查慢 trace 中最慢 span 对应的下游接口和依赖资源。",
                firstExistingEvidenceId(compressedEvidence, "trace.slow.aggregation.1")
        ));
        recommendations.add(recommendation(
                "P2",
                "CHECK_DCS",
                "核对缓存访问指标和慢查询情况，确认是否与时延窗口同步抬升。",
                firstExistingEvidenceId(compressedEvidence, "metric.dcs.p99.1")
        ));

        root.put("overallConfidence", compressedEvidence.missingEvidence().isEmpty() ? 0.76 : 0.71);
        root.set("insufficientEvidence", insufficientEvidence(compressedEvidence));
        return writeJson(root);
    }

    private String buildInvalidEvidenceJson(CompressedEvidenceBundle compressedEvidence) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", "mock invalid evidence");
        root.set("impact", impact("LOW", "mock invalid evidence"));
        ArrayNode rootCauses = root.putArray("rootCauses");
        rootCauses.add(rootCause(
                "BUSINESS_ERROR_CODE_NOT_MASKED",
                "mock invalid evidence cause",
                0.55,
                List.of("missing.evidence.1"),
                "mock invalid evidence reasoning"
        ));
        ArrayNode recommendations = root.putArray("recommendations");
        recommendations.add(recommendation(
                "P2",
                "CHECK_LOG",
                "mock invalid evidence recommendation",
                defaultIfBlank(compressedEvidence.alert() == null ? null : compressedEvidence.alert().interfaceName(), "UNKNOWN")
        ));
        root.put("overallConfidence", 0.55);
        root.putArray("insufficientEvidence");
        return writeJson(root);
    }

    private ObjectNode impact(String level, String description) {
        ObjectNode impact = objectMapper.createObjectNode();
        impact.put("level", level);
        impact.put("description", description);
        return impact;
    }

    private ObjectNode rootCause(
            String causeType,
            String description,
            double confidence,
            List<String> evidenceIds,
            String reasoning
    ) {
        ObjectNode rootCause = objectMapper.createObjectNode();
        rootCause.put("causeType", causeType);
        rootCause.put("description", description);
        rootCause.put("confidence", confidence);
        ArrayNode ids = rootCause.putArray("evidenceIds");
        evidenceIds.forEach(ids::add);
        rootCause.put("reasoning", reasoning);
        return rootCause;
    }

    private ObjectNode recommendation(String priority, String actionType, String description, String target) {
        ObjectNode recommendation = objectMapper.createObjectNode();
        recommendation.put("priority", priority);
        recommendation.put("actionType", actionType);
        recommendation.put("description", description);
        recommendation.put("target", defaultIfBlank(target, "UNKNOWN"));
        return recommendation;
    }

    private ArrayNode insufficientEvidence(CompressedEvidenceBundle compressedEvidence) {
        ArrayNode notes = objectMapper.createArrayNode();
        LinkedHashSet<String> merged = new LinkedHashSet<>(compressedEvidence.insufficientEvidence());
        if (merged.isEmpty() && !compressedEvidence.missingEvidence().isEmpty()) {
            merged.add("存在缺失的 Provider 证据，结论置信度已下调。");
        }
        merged.forEach(notes::add);
        return notes;
    }

    private List<String> evidenceIds(CompressedEvidenceBundle compressedEvidence, List<String> preferredIds, int limit) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        preferredIds.stream()
                .map(preferred -> firstExistingEvidenceId(compressedEvidence, preferred))
                .filter(value -> !value.isBlank())
                .forEach(selected::add);
        compressedEvidence.evidenceRefs().stream()
                .map(EvidenceReference::evidenceId)
                .limit(limit)
                .forEach(selected::add);
        return selected.stream().limit(limit).toList();
    }

    private String firstExistingEvidenceId(CompressedEvidenceBundle compressedEvidence, String preferredId) {
        return compressedEvidence.evidenceRefs().stream()
                .map(EvidenceReference::evidenceId)
                .filter(id -> id.equals(preferredId))
                .findFirst()
                .orElseGet(() -> compressedEvidence.evidenceRefs().stream()
                        .map(EvidenceReference::evidenceId)
                        .findFirst()
                        .orElse(""));
    }

    private CompressedEvidenceBundle extractEvidence(LlmRequest request) {
        String prompt = request.messages().stream()
                .map(LlmMessage::content)
                .filter(content -> content.contains("证据 JSON："))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new LlmClientException(LlmCallStatus.FAILED, "mock llm request does not contain prompt content"));
        int jsonStart = prompt.indexOf('{');
        int jsonEnd = prompt.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            throw new LlmClientException(LlmCallStatus.FAILED, "mock llm request does not contain compressed evidence json");
        }
        try {
            return objectMapper.readValue(prompt.substring(jsonStart, jsonEnd + 1), CompressedEvidenceBundle.class);
        } catch (JsonProcessingException exception) {
            throw new LlmClientException(LlmCallStatus.FAILED, "mock llm could not parse compressed evidence json", exception);
        }
    }

    private boolean containsToken(String serviceName, String token) {
        return serviceName != null && serviceName.toLowerCase(Locale.ROOT).contains(token);
    }

    private String writeJson(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LlmClientException(LlmCallStatus.FAILED, "mock llm failed to serialize response", exception);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.llm.client.LlmMessage;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Latency prompt tailored for downstream slow-point and infrastructure-correlation analysis.
 */
@Component
public class RpcLatencyPromptBuilder implements PromptBuilder {

    private static final String DEFAULT_SYSTEM_INSTRUCTIONS = """
            你是电商微服务 AIOps 告警分析助手。

            你只能基于用户提供的结构化证据进行分析，不能编造任何不存在的指标、日志、trace、服务名、接口名、errorCode 或资源实例。
            如果证据不足，必须明确说明"不确定"。

            你的任务是：
            1. 判断告警影响和优先级。
            2. 给出最多 5 个根因假设，并按置信度降序排列。
            3. 每个根因假设必须引用 evidenceIds。
            4. 给出可执行的人工排查建议，但不能建议系统已经自动执行任何动作。
            5. 对时延告警，需要特别判断慢点在当前服务、下游 RPC、数据库、DCS、MQ、容器资源还是 GC。
            """;

    private static final String DEFAULT_USER_INSTRUCTIONS = """
            请分析以下 RPC 接口时延告警。

            分析重点：
            1. 区分平均时延升高和 P99 尾延迟升高。
            2. 判断慢点更可能在当前服务还是下游服务。
            3. 如果慢点在下游，请指出最可疑的 serviceName、interfaceName 和 evidenceIds。
            4. 判断数据库、DCS、MQ、容器 CPU、内存、GC、实例状态、CSE 使用率是否与时延升高相关。
            5. 判断请求量突增是否可能导致时延升高。
            6. 给出人工排查建议，但不要执行任何动作。
            """;

    private static final String FIXED_OUTPUT_CONTRACT = """
            你必须输出如下格式的 JSON 对象：

            {
              "summary": "一句话总结告警原因和处理优先级",
              "impact": {
                "level": "LOW 或 MEDIUM 或 HIGH 或 UNKNOWN",
                "description": "详细影响说明"
              },
              "rootCauses": [
                {
                  "causeType": "根因类型枚举值",
                  "description": "根因描述",
                  "confidence": 0.85,
                  "evidenceIds": ["证据ID1", "证据ID2"],
                  "reasoning": "推理过程说明"
                }
              ],
              "recommendations": [
                {
                  "priority": "P0 或 P1 或 P2 或 P3",
                  "actionType": "建议动作类型",
                  "description": "建议描述",
                  "target": "目标对象"
                }
              ],
              "overallConfidence": 0.78,
              "insufficientEvidence": ["证据不足项1", "证据不足项2"]
            }

            重要约束：
            - impact 必须是包含 level 和 description 的对象，不能是字符串
            - causeType 必须是以下之一：BUSINESS_ERROR_CODE_NOT_MASKED, LOW_TRAFFIC_LIMITED_IMPACT, DOWNSTREAM_RPC_FAILURE, DOWNSTREAM_RPC_LATENCY, DATABASE_SLOW_QUERY, DATABASE_RESOURCE_PRESSURE, DCS_LATENCY_OR_FAILURE, MQ_BACKLOG_OR_LATENCY, CONTAINER_CPU_PRESSURE, CONTAINER_MEMORY_PRESSURE, GC_PRESSURE, INSTANCE_STARTING_OR_FAILED, SINGLE_INSTANCE_ABNORMAL, TRAFFIC_SPIKE, UNKNOWN
            - evidenceIds 必须引用证据包中存在的 evidenceId
            - priority 必须是 P0 / P1 / P2 / P3
            - actionType 必须是以下之一：CHECK_TRACE, CHECK_LOG, CHECK_METRIC, CHECK_DOWNSTREAM, CHECK_DATABASE, CHECK_DCS, CHECK_MQ, EVALUATE_ERROR_CODE_MASKING, TUNE_ALERT_RULE, MANUAL_CONFIRMATION
            - overallConfidence 必须是 0 到 1 之间的数字

            不要输出 Markdown，不要输出 JSON 之外的任何文本。
            """;

    private final JsonMapper jsonMapper;
    private final ServicePromptTemplateResolver templateResolver;

    public RpcLatencyPromptBuilder(JsonMapper jsonMapper) {
        this(jsonMapper, ServicePromptTemplateResolver.none());
    }

    @Autowired
    public RpcLatencyPromptBuilder(JsonMapper jsonMapper, ServicePromptTemplateResolver templateResolver) {
        this.jsonMapper = jsonMapper;
        this.templateResolver = templateResolver;
    }

    @Override
    public AlertCategory supportedCategory() {
        return AlertCategory.RPC_LATENCY;
    }

    @Override
    public List<LlmMessage> buildMessages(CompressedEvidenceBundle compressedEvidence, String validationError) {
        ServicePromptTemplateRecord template = resolveTemplate(compressedEvidence);
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", systemPrompt(template)));
        messages.add(new LlmMessage("user", userPrompt(template, compressedEvidence)));
        if (validationError != null && !validationError.isBlank()) {
            messages.add(new LlmMessage("user", repairPrompt(compressedEvidence, validationError)));
        }
        return List.copyOf(messages);
    }

    private ServicePromptTemplateRecord resolveTemplate(CompressedEvidenceBundle compressedEvidence) {
        if (compressedEvidence == null || compressedEvidence.alert() == null) {
            return null;
        }
        return templateResolver.resolve(compressedEvidence.alert().serviceName(), supportedCategory())
                .orElse(null);
    }

    private String systemPrompt(ServicePromptTemplateRecord template) {
        String instructions = template == null ? DEFAULT_SYSTEM_INSTRUCTIONS : template.systemPrompt();
        return instructions.strip() + "\n\n" + FIXED_OUTPUT_CONTRACT;
    }

    private String userPrompt(ServicePromptTemplateRecord template, CompressedEvidenceBundle compressedEvidence) {
        String instructions = template == null ? DEFAULT_USER_INSTRUCTIONS : template.userPrompt();
        return """
                %s

                证据 JSON：
                %s

                请严格按照 system prompt 中定义的 JSON 格式输出，不要输出任何其他文本。
                """.formatted(instructions.strip(), jsonMapper.toJson(compressedEvidence));
    }

    private String repairPrompt(CompressedEvidenceBundle compressedEvidence, String validationError) {
        return """
                上一次输出校验失败，原因如下：
                %s

                请仔细检查以下几点：
                - impact 必须是对象 {level, description}，不能是字符串
                - 字段名必须是 rootCauses（不是 rootCauseHypotheses）
                - 所有 evidenceIds 必须来自以下列表：
                %s

                请仅返回合法 JSON。
                """.formatted(
                validationError,
                compressedEvidence.evidenceRefs().stream()
                        .map(reference -> reference.evidenceId() + ":" + reference.description())
                        .collect(Collectors.joining(", "))
        );
    }
}

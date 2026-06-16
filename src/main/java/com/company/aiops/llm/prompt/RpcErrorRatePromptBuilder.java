package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.llm.client.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Error-rate prompt that keeps the design-document instructions centralized and injects the bounded
 * compressed evidence JSON as the only fact source available to the model.
 */
@Component
public class RpcErrorRatePromptBuilder implements PromptBuilder {

    private static final String INTERNAL_ERROR_MESSAGE = ErrorCode.INTERNAL_ERROR.defaultMessage();

    private static final String SYSTEM_PROMPT = """
            你是电商微服务 AIOps 告警分析助手。

            你只能基于用户提供的结构化证据进行分析，不能编造任何不存在的指标、日志、trace、服务名、接口名、errorCode 或资源实例。
            如果证据不足，必须明确说明"不确定"。

            你的任务是：
            1. 判断告警影响和优先级。
            2. 给出最多 5 个根因假设，并按置信度降序排列。
            3. 每个根因假设必须引用 evidenceIds。
            4. 给出可执行的人工排查建议，但不能建议系统已经自动执行任何动作。
            5. 对错误率告警，需要特别根据 errorMsg 判断是否可能是业务 errorCode 未屏蔽；如果同一 errorCode 的代表性 errorMsg 为“%s”等系统异常语义，且日志、trace 或其他证据里没有明确表明这是正常业务校验导致的直接证据，则应优先按需要处理的代码问题或系统故障分析。仅出现业务功能名、业务场景词或 feature 描述，不足以判定为正常业务校验。请求量较小时只能说明影响范围可能有限，不能判定为误报或跳过分析。

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
            """.formatted(INTERNAL_ERROR_MESSAGE);

    private final JsonMapper jsonMapper;

    public RpcErrorRatePromptBuilder(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AlertCategory supportedCategory() {
        return AlertCategory.RPC_ERROR_RATE;
    }

    @Override
    public List<LlmMessage> buildMessages(CompressedEvidenceBundle compressedEvidence, String validationError) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", SYSTEM_PROMPT));
        messages.add(new LlmMessage("user", """
                请分析以下 RPC 接口错误率告警。

                分析重点：
                1. 是否为真实系统故障。
                2. 请求量规模是否说明当前告警暂不表现为大范围故障。
                3. 是否为业务校验 errorCode 未屏蔽，判断依据必须来自 errorMsg，而不是 errorCode 字面值。
                4. 如果代表性 errorMsg 为“%s”等系统异常语义，只有在日志或其他证据明确表明这是正常业务校验导致时，才能判断为正常业务校验；仅出现业务功能名、业务场景词或 feature 描述不构成充分依据。
                5. 失败是否集中在某个 errorCode、某类 errorMsg、少量 uid 或少量 trace。
                6. 是否存在下游 RPC、数据库、DCS、MQ 或容器资源异常。
                7. 给出人工排查建议和 errorCode 屏蔽建议，但不要执行任何动作。

                证据 JSON：
                %s

                请严格按照 system prompt 中定义的 JSON 格式输出，不要输出任何其他文本。
                """.formatted(INTERNAL_ERROR_MESSAGE, jsonMapper.toJson(compressedEvidence))));
        if (validationError != null && !validationError.isBlank()) {
            messages.add(new LlmMessage("user", """
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
            )));
        }
        return List.copyOf(messages);
    }
}

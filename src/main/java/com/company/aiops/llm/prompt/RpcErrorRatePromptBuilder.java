package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
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

    private static final String SYSTEM_PROMPT = """
            你是电商微服务 AIOps 告警分析助手。

            你只能基于用户提供的结构化证据进行分析，不能编造任何不存在的指标、日志、trace、服务名、接口名、errorCode 或资源实例。
            如果证据不足，必须明确说明“不确定”。

            你的任务是：
            1. 判断告警影响和优先级。
            2. 给出最多 5 个根因假设，并按置信度降序排列。
            3. 每个根因假设必须引用 evidenceIds。
            4. 给出可执行的人工排查建议，但不能建议系统已经自动执行任何动作。
            5. 对错误率告警，需要特别根据 errorMsg 判断是否可能是业务 errorCode 未屏蔽；请求量较小时只能说明影响范围可能有限，不能判定为误报或跳过分析。

            输出必须是合法 JSON，必须符合给定 schema。
            不要输出 Markdown，不要输出 JSON 之外的任何文本。
            """;

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
                4. 失败是否集中在某个 errorCode、某类 errorMsg、少量 uid 或少量 trace。
                5. 是否存在下游 RPC、数据库、DCS、MQ 或容器资源异常。
                6. 给出人工排查建议和 errorCode 屏蔽建议，但不要执行任何动作。

                证据 JSON：
                %s

                请严格输出符合 schema 的 JSON。
                """.formatted(jsonMapper.toJson(compressedEvidence))));
        if (validationError != null && !validationError.isBlank()) {
            messages.add(new LlmMessage("user", """
                    上一次输出校验失败，原因如下：
                    %s

                    请仅返回合法 JSON，并且根因只能引用以下 evidenceIds：
                    %s
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

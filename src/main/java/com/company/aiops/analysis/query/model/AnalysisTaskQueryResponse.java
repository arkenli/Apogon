package com.company.aiops.analysis.query.model;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.job.AnalysisJobStatus;

import java.time.Instant;

/**
 * Public payload for single-task and historical analysis lookups.
 */
public record AnalysisTaskQueryResponse(
        String taskId,
        String alertId,
        AnalysisJobStatus status,
        AlertCategory category,
        String serviceName,
        String interfaceName,
        String cluster,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String currentStep,
        String statusReason,
        String failureReason,
        AnalysisResultView result
) {

    public String toChineseResultText() {
        StringBuilder builder = new StringBuilder();
        builder.append(taskStatusText());
        builder.append("告警类型：").append(categoryText(category)).append("；");
        builder.append("服务：").append(defaultText(serviceName, "未知服务")).append("；");
        builder.append("接口：").append(defaultText(interfaceName, "未知接口")).append("；");
        builder.append("集群：").append(defaultText(cluster, "未知集群")).append("。");

        if (result == null) {
            appendTaskOnlyDetails(builder);
            return builder.toString();
        }

        builder.append("\n");
        builder.append("结论摘要：").append(defaultText(result.summary(), "暂无明确结论")).append("。");
        if (result.impact() != null) {
            builder.append("\n");
            builder.append("影响评估：")
                    .append(defaultText(result.impact().level(), "UNKNOWN"))
                    .append("（")
                    .append(defaultText(result.impact().description(), "暂无影响说明"))
                    .append("）");
        }
        builder.append("\n");
        builder.append("整体置信度：").append(percentText(result.overallConfidence())).append("。");

        builder.append("\n\n");
        builder.append("分析明细：");
        builder.append("\n");
        builder.append("- 分析方式：").append(analysisModeText(result.analysisMode()));
        builder.append("\n");
        builder.append("- LLM状态：").append(defaultText(result.llmStatus(), result.llmUsed() ? "UNKNOWN" : "未使用"));
        builder.append("\n");
        builder.append("- 是否降级：").append(result.degraded() ? "是" : "否");
        if (result.degraded() && hasText(result.degradedReason())) {
            builder.append("\n");
            builder.append("- 降级原因：").append(result.degradedReason());
        }
        if (result.degradedByProviderFailure()) {
            builder.append("\n");
            builder.append("- 降级来源：Provider取证不完整");
        }

        appendRootCauses(builder);
        appendRecommendations(builder);
        appendMissingEvidence(builder);
        appendInsufficientEvidence(builder);
        appendHistoricalReference(builder);
        return builder.toString();
    }

    private String taskStatusText() {
        if (status == null) {
            return "分析任务状态未知。";
        }
        return switch (status) {
            case SUCCESS -> "分析任务已完成。";
            case DEGRADED_SUCCESS -> "分析任务已完成，但结果已降级。";
            case FAILED -> "分析任务执行失败。";
            case SKIPPED_DUPLICATE -> "分析任务已跳过，命中重复告警。";
            default -> "分析任务正在执行中。";
        };
    }

    private void appendTaskOnlyDetails(StringBuilder builder) {
        if (status == AnalysisJobStatus.FAILED && hasText(failureReason)) {
            builder.append("\n");
            builder.append("失败原因：").append(failureReason).append("。");
            return;
        }
        if (status == AnalysisJobStatus.SKIPPED_DUPLICATE) {
            builder.append("\n");
            builder.append("状态说明：").append(defaultText(statusReason, "该告警复用了已有分析任务结果")).append("。");
            return;
        }
        if (status != null && !status.isTerminal()) {
            if (hasText(currentStep)) {
                builder.append("\n");
                builder.append("当前阶段：").append(currentStep).append("。");
            }
            if (hasText(statusReason)) {
                builder.append("\n");
                builder.append("状态说明：").append(statusReason).append("。");
            }
        }
    }

    private void appendRootCauses(StringBuilder builder) {
        builder.append("\n");
        builder.append("可能根因：");
        if (result.rootCauses() == null || result.rootCauses().isEmpty()) {
            builder.append("\n- 暂无明确根因");
            return;
        }
        int index = 1;
        for (var rootCause : result.rootCauses().stream().limit(3).toList()) {
            builder.append("\n");
            builder.append(index++).append(". ")
                    .append(defaultText(rootCause.description(), defaultText(rootCause.causeType(), "未知根因")))
                    .append("（置信度 ")
                    .append(percentText(rootCause.confidence()))
                    .append("）");
        }
    }

    private void appendRecommendations(StringBuilder builder) {
        builder.append("\n");
        builder.append("处理建议：");
        if (result.recommendations() == null || result.recommendations().isEmpty()) {
            builder.append("\n- 暂无建议");
            return;
        }
        int index = 1;
        for (var recommendation : result.recommendations().stream().limit(3).toList()) {
            builder.append("\n");
            builder.append(index++).append(". ");
            if (hasText(recommendation.priority())) {
                builder.append("[").append(recommendation.priority()).append("] ");
            }
            builder.append(defaultText(recommendation.description(), defaultText(recommendation.actionType(), "待确认动作")));
            if (hasText(recommendation.target())) {
                builder.append("（目标：").append(recommendation.target()).append("）");
            }
        }
    }

    private void appendMissingEvidence(StringBuilder builder) {
        if (result.missingEvidence() == null || result.missingEvidence().isEmpty()) {
            return;
        }
        builder.append("\n");
        builder.append("缺失证据：");
        for (var evidence : result.missingEvidence()) {
            builder.append("\n- ")
                    .append(defaultText(evidence.providerName(), "unknown"))
                    .append("：")
                    .append(defaultText(evidence.reason(), "未知原因"));
            if (hasText(evidence.impact())) {
                builder.append("（影响：").append(evidence.impact()).append("）");
            }
        }
    }

    private void appendInsufficientEvidence(StringBuilder builder) {
        if (result.insufficientEvidence() == null || result.insufficientEvidence().isEmpty()) {
            return;
        }
        builder.append("\n");
        builder.append("证据不足：");
        for (String item : result.insufficientEvidence()) {
            builder.append("\n- ").append(defaultText(item, "未知项"));
        }
    }

    private void appendHistoricalReference(StringBuilder builder) {
        if (result.historicalReference() == null || !result.historicalReference().matched()) {
            return;
        }
        builder.append("\n");
        builder.append("历史参考：")
                .append(defaultText(result.historicalReference().summary(), "命中过往相似问题"));
        if (hasText(result.historicalReference().taskId())) {
            builder.append("（任务 ").append(result.historicalReference().taskId());
            builder.append("，置信度 ").append(percentText(result.historicalReference().overallConfidence())).append("）");
        }
    }

    private String categoryText(com.company.aiops.alert.model.AlertCategory value) {
        if (value == null) {
            return "未知类型";
        }
        return switch (value) {
            case RPC_ERROR_RATE -> "RPC错误率";
            case RPC_LATENCY -> "RPC时延";
            case UNKNOWN -> "未知类型";
        };
    }

    private String analysisModeText(com.company.aiops.analysis.result.AnalysisMode value) {
        if (value == null) {
            return "未知分析方式";
        }
        return switch (value) {
            case LLM_ASSISTED -> "LLM辅助分析";
            case RULE_BASED_DEGRADED -> "规则降级分析";
            case RULE_BASED_ONLY -> "纯规则分析";
        };
    }

    private String percentText(double value) {
        return Math.round(value * 100) + "%";
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

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
 * Prompt builder for service-level ERROR runtime-log alerts.
 */
@Component
public class ServiceErrorLogPromptBuilder implements PromptBuilder {

    private static final String DEFAULT_SYSTEM_INSTRUCTIONS = """
            You are an AIOps alert-analysis assistant for microservice operations.

            Use only the structured evidence supplied in this request. Do not invent metrics,
            logs, trace ids, service names, exception types, stack frames, or resource instances.
            If the evidence is insufficient, say so explicitly and lower confidence.

            Your task:
            1. Judge impact and priority.
            2. Return at most 5 root-cause hypotheses ordered by confidence.
            3. Every root-cause hypothesis must cite existing evidenceIds.
            4. Return actionable manual investigation recommendations only; do not claim any
               action has already been executed.
            """;

    private static final String DEFAULT_USER_INSTRUCTIONS = """
            Analyze the following service error log alert.

            Focus areas:
            1. Determine whether the alert msg matched ERROR runtime log context.
            2. If the full msg did not match and a cut matchedLogQuery was used, explain the
               uncertainty introduced by the reduced query.
            3. Determine whether traceId values were extracted, and use trace evidence to decide
               whether the first failed point is in the current service or downstream.
            4. Check whether failures concentrate on one instance, a small set of traceIds/uids,
               the same exceptionType, or the same stackTop.
            5. Check whether DB, DCS, MQ, container CPU/memory, GC, instance state, or CSE metrics
               correlate with the runtime-log window.
            6. A small failureCount only means the current impact scope may be limited; it must not
               be treated as a false positive or a reason to skip analysis.
            7. Recommendations should name the traceId, instance, logger, exceptionType,
               matchedLogQuery, and resource instances to inspect when those evidence fields exist.
            """;

    private static final String FIXED_OUTPUT_CONTRACT = """
            You must return exactly one JSON object with this shape:

            {
              "summary": "one sentence summary of likely cause and priority",
              "impact": {
                "level": "LOW or MEDIUM or HIGH or UNKNOWN",
                "description": "impact explanation"
              },
              "rootCauses": [
                {
                  "causeType": "enumerated root cause type",
                  "description": "root cause description",
                  "confidence": 0.85,
                  "evidenceIds": ["evidenceId1", "evidenceId2"],
                  "reasoning": "reasoning based only on cited evidence"
                }
              ],
              "recommendations": [
                {
                  "priority": "P0 or P1 or P2 or P3",
                  "actionType": "action type",
                  "description": "manual action description",
                  "target": "target object or evidence id"
                }
              ],
              "overallConfidence": 0.78,
              "insufficientEvidence": ["missing evidence item"]
            }

            Constraints:
            - impact must be an object containing level and description.
            - causeType must be one of: BUSINESS_ERROR_CODE_NOT_MASKED, LOW_TRAFFIC_LIMITED_IMPACT,
              DOWNSTREAM_RPC_FAILURE, DOWNSTREAM_RPC_LATENCY, CURRENT_SERVICE_RUNTIME_EXCEPTION,
              REPEATED_BUSINESS_LOG_ERROR, DATABASE_SLOW_QUERY, DATABASE_RESOURCE_PRESSURE,
              DCS_LATENCY_OR_FAILURE, MQ_BACKLOG_OR_LATENCY, CONTAINER_CPU_PRESSURE,
              CONTAINER_MEMORY_PRESSURE, GC_PRESSURE, INSTANCE_STARTING_OR_FAILED,
              SINGLE_INSTANCE_ABNORMAL, TRAFFIC_SPIKE, UNKNOWN.
            - evidenceIds must reference evidence ids present in the evidence bundle.
            - priority must be P0 / P1 / P2 / P3.
            - actionType must be one of: CHECK_TRACE, CHECK_LOG, CHECK_METRIC, CHECK_DOWNSTREAM,
              CHECK_DATABASE, CHECK_DCS, CHECK_MQ, EVALUATE_ERROR_CODE_MASKING, TUNE_ALERT_RULE,
              MANUAL_CONFIRMATION.
            - overallConfidence must be a number between 0 and 1.
            - Return JSON only. Do not return Markdown.
            """;

    private final JsonMapper jsonMapper;
    private final ServicePromptTemplateResolver templateResolver;

    public ServiceErrorLogPromptBuilder(JsonMapper jsonMapper) {
        this(jsonMapper, ServicePromptTemplateResolver.none());
    }

    @Autowired
    public ServiceErrorLogPromptBuilder(JsonMapper jsonMapper, ServicePromptTemplateResolver templateResolver) {
        this.jsonMapper = jsonMapper;
        this.templateResolver = templateResolver;
    }

    @Override
    public AlertCategory supportedCategory() {
        return AlertCategory.SERVICE_ERROR_LOG;
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

                Evidence JSON:
                %s

                Return JSON only and follow the system prompt contract exactly.
                """.formatted(instructions.strip(), jsonMapper.toJson(compressedEvidence));
    }

    private String repairPrompt(CompressedEvidenceBundle compressedEvidence, String validationError) {
        return """
                The previous output failed validation:
                %s

                Re-check these requirements:
                - impact must be an object {level, description}.
                - The root cause array field must be rootCauses.
                - All evidenceIds must come from this list:
                %s

                Return valid JSON only.
                """.formatted(
                validationError,
                compressedEvidence.evidenceRefs().stream()
                        .map(reference -> reference.evidenceId() + ":" + reference.description())
                        .collect(Collectors.joining(", "))
        );
    }
}

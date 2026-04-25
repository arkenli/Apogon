package com.company.aiops.llm.service;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.result.AnalysisMode;
import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.analysis.result.RootCauseHypothesis;
import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.client.LlmClient;
import com.company.aiops.llm.client.LlmClientException;
import com.company.aiops.llm.client.LlmRequest;
import com.company.aiops.llm.client.LlmResponse;
import com.company.aiops.llm.prompt.PromptBuilder;
import com.company.aiops.llm.schema.LlmAnalysisOutput;
import com.company.aiops.llm.validator.LlmOutputValidationException;
import com.company.aiops.llm.validator.LlmOutputValidator;
import com.company.aiops.persistence.repository.LlmCallLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default stage-8 service that keeps retry, validation, and audit logging out of the analysis
 * worker so the worker only manages lifecycle transitions.
 */
@Service
public class DefaultLlmAnalysisService implements LlmAnalysisService {

    private static final String RESPONSE_FORMAT = "json_object";
    private static final int MAX_ATTEMPTS = 2;

    private final AiopsProperties aiopsProperties;
    private final JsonMapper jsonMapper;
    private final Map<String, LlmClient> llmClients;
    private final Map<AlertCategory, PromptBuilder> promptBuilders;
    private final LlmOutputValidator llmOutputValidator;
    private final LlmCallLogRepository llmCallLogRepository;

    public DefaultLlmAnalysisService(
            AiopsProperties aiopsProperties,
            JsonMapper jsonMapper,
            Map<String, LlmClient> llmClients,
            List<PromptBuilder> promptBuilders,
            LlmOutputValidator llmOutputValidator,
            LlmCallLogRepository llmCallLogRepository
    ) {
        this.aiopsProperties = aiopsProperties;
        this.jsonMapper = jsonMapper;
        this.llmClients = Map.copyOf(llmClients);
        this.promptBuilders = mapPromptBuilders(promptBuilders);
        this.llmOutputValidator = llmOutputValidator;
        this.llmCallLogRepository = llmCallLogRepository;
    }

    @Override
    public AnalysisResult analyze(String taskId, ProviderCollectionResult collection, CompressedEvidenceBundle compressedEvidence) {
        AlertEvent alert = collection.context().alert();
        PromptBuilder promptBuilder = resolvePromptBuilder(alert.category());
        LlmClient llmClient = resolveClient();
        String validationError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LlmRequest request = new LlmRequest(
                    aiopsProperties.llm().model(),
                    promptBuilder.buildMessages(compressedEvidence, validationError),
                    aiopsProperties.llm().temperature(),
                    aiopsProperties.llm().maxOutputTokens(),
                    aiopsProperties.llm().timeout(),
                    RESPONSE_FORMAT
            );
            Instant createdAt = Instant.now();
            Instant expireAt = createdAt.plus(aiopsProperties.analysis().llmLogRetentionDays(), ChronoUnit.DAYS);
            String requestJson = jsonMapper.toJson(request);

            try {
                LlmResponse response = llmClient.complete(request);
                try {
                    LlmAnalysisOutput output = llmOutputValidator.validate(response.content(), compressedEvidence);
                    llmCallLogRepository.save(successLog(taskId, alert.alertId(), requestJson, response, expireAt, createdAt));
                    return toAnalysisResult(taskId, collection, compressedEvidence, output, response.model());
                } catch (LlmOutputValidationException exception) {
                    llmCallLogRepository.save(failedLog(
                            taskId,
                            alert.alertId(),
                            requestJson,
                            response,
                            exception.status(),
                            exception.getMessage(),
                            expireAt,
                            createdAt
                    ));
                    if (attempt < MAX_ATTEMPTS) {
                        validationError = exception.getMessage();
                        continue;
                    }
                    throw new LlmAnalysisException(exception.status(), exception.getMessage());
                }
            } catch (LlmClientException exception) {
                llmCallLogRepository.save(failedLog(
                        taskId,
                        alert.alertId(),
                        requestJson,
                        null,
                        exception.status(),
                        exception.getMessage(),
                        expireAt,
                        createdAt
                ));
                throw new LlmAnalysisException(exception.status(), exception.getMessage());
            }
        }

        throw new LlmAnalysisException(LlmCallStatus.FAILED, "LLM analysis exhausted all attempts without producing a usable result");
    }

    private Map<AlertCategory, PromptBuilder> mapPromptBuilders(List<PromptBuilder> promptBuilders) {
        Map<AlertCategory, PromptBuilder> builderMap = new LinkedHashMap<>();
        for (PromptBuilder promptBuilder : promptBuilders) {
            builderMap.put(promptBuilder.supportedCategory(), promptBuilder);
        }
        return Map.copyOf(builderMap);
    }

    private PromptBuilder resolvePromptBuilder(AlertCategory category) {
        PromptBuilder promptBuilder = promptBuilders.get(category);
        if (promptBuilder == null) {
            throw new LlmAnalysisException(LlmCallStatus.FAILED, "No PromptBuilder is registered for category " + category);
        }
        return promptBuilder;
    }

    private LlmClient resolveClient() {
        String provider = aiopsProperties.llm().provider();
        LlmClient client = llmClients.get(provider);
        if (client == null) {
            throw new LlmAnalysisException(LlmCallStatus.UNAVAILABLE, "No LlmClient bean is registered for provider " + provider);
        }
        return client;
    }

    private LlmCallLog successLog(
            String taskId,
            String alertId,
            String requestJson,
            LlmResponse response,
            Instant expireAt,
            Instant createdAt
    ) {
        return new LlmCallLog(
                taskId,
                alertId,
                aiopsProperties.llm().provider(),
                response.model(),
                requestJson,
                response.content(),
                response.inputTokens(),
                response.outputTokens(),
                response.latency(),
                LlmCallStatus.SUCCESS,
                null,
                expireAt,
                createdAt
        );
    }

    private LlmCallLog failedLog(
            String taskId,
            String alertId,
            String requestJson,
            LlmResponse response,
            LlmCallStatus status,
            String errorMessage,
            Instant expireAt,
            Instant createdAt
    ) {
        return new LlmCallLog(
                taskId,
                alertId,
                aiopsProperties.llm().provider(),
                response == null ? aiopsProperties.llm().model() : response.model(),
                requestJson,
                response == null ? null : response.content(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                response == null ? null : response.latency(),
                status,
                errorMessage,
                expireAt,
                createdAt
        );
    }

    private AnalysisResult toAnalysisResult(
            String taskId,
            ProviderCollectionResult collection,
            CompressedEvidenceBundle compressedEvidence,
            LlmAnalysisOutput output,
            String responseModel
    ) {
        boolean degradedByProviderFailure = collection.degradedByProviderFailure();
        List<RootCauseHypothesis> rootCauses = output.rootCauses().stream()
                .map(rootCause -> new RootCauseHypothesis(
                        rootCause.causeType(),
                        rootCause.description(),
                        rootCause.confidence(),
                        rootCause.evidenceIds(),
                        "MERGED",
                        rootCause.reasoning()
                ))
                .toList();
        LinkedHashSet<String> insufficientEvidence = new LinkedHashSet<>(compressedEvidence.insufficientEvidence());
        insufficientEvidence.addAll(output.insufficientEvidence());
        if (degradedByProviderFailure && collection.degradedReason() != null && !collection.degradedReason().isBlank()) {
            insufficientEvidence.add(collection.degradedReason());
        }

        double overallConfidence = clamp(output.overallConfidence() - (degradedByProviderFailure ? 0.05 : 0.0), 0.0, 1.0);
        String summary = output.summary();
        if (responseModel != null && !responseModel.isBlank() && !summary.contains(responseModel)) {
            // Keep the summary human-readable while preserving which model actually produced it in
            // the persisted envelope rather than polluting the natural-language text.
        }

        return new AnalysisResult(
                taskId,
                collection.context().alert().alertId(),
                collection.context().alert().category(),
                AnalysisMode.LLM_ASSISTED,
                summary,
                output.impact(),
                rootCauses,
                output.recommendations(),
                compressedEvidence.evidenceRefs(),
                compressedEvidence.missingEvidence(),
                List.copyOf(insufficientEvidence),
                null,
                true,
                LlmCallStatus.SUCCESS.name(),
                degradedByProviderFailure,
                degradedByProviderFailure,
                degradedByProviderFailure ? collection.degradedReason() : null,
                null,
                overallConfidence,
                Instant.now()
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

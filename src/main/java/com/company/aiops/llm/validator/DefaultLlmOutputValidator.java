package com.company.aiops.llm.validator;

import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.analysis.result.ImpactAssessment;
import com.company.aiops.analysis.result.Recommendation;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.schema.LlmAnalysisOutput;
import com.company.aiops.llm.schema.LlmOutputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Code-based validator that mirrors the stage-8 JSON schema and enforces evidence-id references
 * against the concrete phase-6 bundle.
 */
@Component
public class DefaultLlmOutputValidator implements LlmOutputValidator {

    private final ObjectMapper objectMapper;

    public DefaultLlmOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmAnalysisOutput validate(String rawContent, CompressedEvidenceBundle compressedEvidence) {
        JsonNode root = parseJson(rawContent);
        String summary = requiredText(root, "summary");
        JsonNode impactNode = requiredObject(root, "impact");
        String impactLevel = requiredText(impactNode, "level");
        String impactDescription = requiredText(impactNode, "description");
        ensureAllowed(impactLevel, LlmOutputSchema.IMPACT_LEVELS, "impact.level");

        JsonNode rootCausesNode = requiredArray(root, "rootCauses");
        if (rootCausesNode.isEmpty()) {
            throw new LlmOutputValidationException(LlmCallStatus.SCHEMA_VALIDATION_FAILED, "rootCauses must contain at least one item");
        }
        if (rootCausesNode.size() > 5) {
            throw new LlmOutputValidationException(LlmCallStatus.SCHEMA_VALIDATION_FAILED, "rootCauses cannot contain more than 5 items");
        }

        Set<String> evidenceIds = compressedEvidence.evidenceRefs().stream()
                .map(EvidenceReference::evidenceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<LlmAnalysisOutput.LlmRootCauseOutput> rootCauses = new ArrayList<>();
        for (JsonNode rootCauseNode : rootCausesNode) {
            String causeType = requiredText(rootCauseNode, "causeType");
            if (!LlmOutputSchema.ROOT_CAUSE_TYPES.contains(causeType)) {
                causeType = "UNKNOWN";
            }
            String description = requiredText(rootCauseNode, "description");
            double confidence = requiredNumber(rootCauseNode, "confidence");
            if (confidence < 0 || confidence > 1) {
                throw new LlmOutputValidationException(
                        LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                        "rootCauses[*].confidence must stay within [0, 1]"
                );
            }
            JsonNode evidenceIdNode = requiredArray(rootCauseNode, "evidenceIds");
            if (evidenceIdNode.isEmpty()) {
                throw new LlmOutputValidationException(
                        LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                        "rootCauses[*].evidenceIds must contain at least one item"
                );
            }
            List<String> causeEvidenceIds = new ArrayList<>();
            for (JsonNode idNode : evidenceIdNode) {
                String evidenceId = requireNonBlank(idNode.asText(null), "rootCauses[*].evidenceIds");
                if (!evidenceIds.contains(evidenceId)) {
                    throw new LlmOutputValidationException(
                            LlmCallStatus.INVALID_EVIDENCE_REFERENCE,
                            "rootCauses references unknown evidenceId: " + evidenceId
                    );
                }
                causeEvidenceIds.add(evidenceId);
            }
            String reasoning = requiredText(rootCauseNode, "reasoning");
            rootCauses.add(new LlmAnalysisOutput.LlmRootCauseOutput(
                    causeType,
                    description,
                    confidence,
                    causeEvidenceIds,
                    reasoning
            ));
        }

        JsonNode recommendationsNode = requiredArray(root, "recommendations");
        if (recommendationsNode.isEmpty()) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    "recommendations must contain at least one item"
            );
        }
        if (recommendationsNode.size() > 8) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    "recommendations cannot contain more than 8 items"
            );
        }
        List<Recommendation> recommendations = new ArrayList<>();
        for (JsonNode recommendationNode : recommendationsNode) {
            String priority = requiredText(recommendationNode, "priority");
            String actionType = requiredText(recommendationNode, "actionType");
            String description = requiredText(recommendationNode, "description");
            String target = requiredText(recommendationNode, "target");
            ensureAllowed(priority, LlmOutputSchema.RECOMMENDATION_PRIORITIES, "recommendations[*].priority");
            ensureAllowed(actionType, LlmOutputSchema.RECOMMENDATION_ACTION_TYPES, "recommendations[*].actionType");
            recommendations.add(new Recommendation(priority, actionType, description, target));
        }

        double overallConfidence = requiredNumber(root, "overallConfidence");
        if (overallConfidence < 0 || overallConfidence > 1) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    "overallConfidence must stay within [0, 1]"
            );
        }

        JsonNode insufficientEvidenceNode = requiredArray(root, "insufficientEvidence");
        List<String> insufficientEvidence = new ArrayList<>();
        for (JsonNode insufficientNode : insufficientEvidenceNode) {
            insufficientEvidence.add(requireNonBlank(insufficientNode.asText(null), "insufficientEvidence[*]"));
        }

        return new LlmAnalysisOutput(
                summary,
                new ImpactAssessment(impactLevel, impactDescription),
                rootCauses,
                recommendations,
                overallConfidence,
                insufficientEvidence
        );
    }

    private JsonNode parseJson(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new LlmOutputValidationException(LlmCallStatus.INVALID_JSON, "LLM response is blank");
        }
        try {
            return objectMapper.readTree(rawContent);
        } catch (Exception exception) {
            throw new LlmOutputValidationException(LlmCallStatus.INVALID_JSON, "LLM response is not valid JSON");
        }
    }

    private JsonNode requiredObject(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isObject()) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    fieldName + " must be an object"
            );
        }
        return value;
    }

    private JsonNode requiredArray(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isArray()) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    fieldName + " must be an array"
            );
        }
        return value;
    }

    private String requiredText(JsonNode node, String fieldName) {
        return requireNonBlank(node.path(fieldName).asText(null), fieldName);
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    fieldName + " must be a non-blank string"
            );
        }
        return value;
    }

    private double requiredNumber(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (!value.isNumber()) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    fieldName + " must be a number"
            );
        }
        return value.asDouble();
    }

    private void ensureAllowed(String value, Set<String> allowedValues, String fieldName) {
        if (!allowedValues.contains(value.toUpperCase(Locale.ROOT))) {
            throw new LlmOutputValidationException(
                    LlmCallStatus.SCHEMA_VALIDATION_FAILED,
                    fieldName + " contains an unsupported value: " + value
            );
        }
    }
}

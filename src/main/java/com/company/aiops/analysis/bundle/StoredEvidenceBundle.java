package com.company.aiops.analysis.bundle;

import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable persistence envelope for one evidence artifact.
 *
 * <p>The payload stays strongly typed in memory, while the repository serializes it into the JSON
 * column together with bundle metadata.</p>
 */
public record StoredEvidenceBundle<T>(
        String taskId,
        String alertId,
        EvidenceBundleType evidenceType,
        T payload,
        Instant expireAt,
        Instant createdAt
) {

    public StoredEvidenceBundle {
        taskId = requireText(taskId, "taskId");
        alertId = requireText(alertId, "alertId");
        evidenceType = Objects.requireNonNull(evidenceType, "evidenceType");
        payload = Objects.requireNonNull(payload, "payload");
        expireAt = Objects.requireNonNull(expireAt, "expireAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Creates the persisted rule-evidence snapshot produced by phase 5.
     */
    public static StoredEvidenceBundle<RuleAnalysisEvidence> rule(
            String taskId,
            String alertId,
            RuleAnalysisEvidence payload,
            Instant createdAt,
            Instant expireAt
    ) {
        return new StoredEvidenceBundle<>(taskId, alertId, EvidenceBundleType.RULE, payload, expireAt, createdAt);
    }

    /**
     * Creates the compressed bundle produced by phase 6.
     */
    public static StoredEvidenceBundle<CompressedEvidenceBundle> compressed(
            String taskId,
            String alertId,
            CompressedEvidenceBundle payload,
            Instant createdAt,
            Instant expireAt
    ) {
        return new StoredEvidenceBundle<>(taskId, alertId, EvidenceBundleType.COMPRESSED, payload, expireAt, createdAt);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}

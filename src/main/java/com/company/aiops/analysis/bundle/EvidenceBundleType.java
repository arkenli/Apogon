package com.company.aiops.analysis.bundle;

/**
 * Logical evidence-bundle categories stored in {@code aiops_evidence_bundle}.
 */
public enum EvidenceBundleType {
    RAW("raw"),
    RULE("rule"),
    COMPRESSED("compressed");

    private final String storageValue;

    EvidenceBundleType(String storageValue) {
        this.storageValue = storageValue;
    }

    /**
     * Returns the persisted lowercase value used by the table definition in the design document.
     */
    public String storageValue() {
        return storageValue;
    }
}

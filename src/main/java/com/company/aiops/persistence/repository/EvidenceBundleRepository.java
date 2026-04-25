package com.company.aiops.persistence.repository;

import com.company.aiops.analysis.bundle.StoredEvidenceBundle;

/**
 * Persists phase-5/6 evidence artifacts into {@code aiops_evidence_bundle}.
 */
public interface EvidenceBundleRepository {

    /**
     * Stores one evidence artifact as a JSON bundle for later audit and downstream reuse.
     */
    void save(StoredEvidenceBundle<?> evidenceBundle);
}

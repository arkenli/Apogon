package com.company.aiops.persistence.repository;

import com.company.aiops.analysis.result.AnalysisResult;
import com.company.aiops.analysis.result.HistoricalReference;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists final analysis outputs and historical lookup projections.
 */
public interface AnalysisResultRepository {

    /**
     * Inserts or updates the structured result for one task.
     */
    void save(AnalysisResult analysisResult);

    /**
     * Finds the newest successful historical result outside the duplicate merge window.
     */
    Optional<HistoricalReference> findLatestHistoricalReference(
            String dedupeKey,
            Instant historicalSince,
            Instant duplicateBefore
    );
}

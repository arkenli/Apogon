package com.company.aiops.persistence.repository;

import java.time.Instant;

/**
 * Records that one alert was merged into an existing analysis task.
 */
public interface DuplicateAlertLinkRepository {

    /**
     * Persists the source alert id and the reused task id for audit.
     */
    void save(String sourceAlertId, String targetTaskId, String dedupeKey, Instant createdAt);
}

package com.company.aiops.persistence;

import java.time.Instant;

public interface DuplicateAlertLinkRepository {

    void save(String sourceAlertId, String targetTaskId, String dedupeKey, Instant createdAt);
}

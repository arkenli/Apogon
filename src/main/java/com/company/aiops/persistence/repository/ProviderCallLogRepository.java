package com.company.aiops.persistence.repository;

import com.company.aiops.provider.model.ProviderCallLog;

import java.util.List;

/**
 * Persists provider invocation audit records.
 */
public interface ProviderCallLogRepository {

    void save(ProviderCallLog providerCallLog);

    List<ProviderCallLog> findByTaskId(String taskId);
}

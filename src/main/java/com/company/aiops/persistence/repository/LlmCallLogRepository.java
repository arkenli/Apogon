package com.company.aiops.persistence.repository;

import com.company.aiops.llm.service.LlmCallLog;

import java.util.List;

/**
 * Persists one audit row for each stage-8 LLM call attempt.
 */
public interface LlmCallLogRepository {

    /**
     * Inserts a new call attempt.
     */
    void save(LlmCallLog llmCallLog);

    /**
     * Reads all logged attempts for one task in chronological order.
     */
    List<LlmCallLog> findByTaskId(String taskId);
}

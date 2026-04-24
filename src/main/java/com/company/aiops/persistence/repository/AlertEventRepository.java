package com.company.aiops.persistence.repository;

import com.company.aiops.alert.model.AlertEvent;

import java.util.Optional;

/**
 * Persists parsed alert events before analysis begins.
 */
public interface AlertEventRepository {

    /**
     * Inserts or updates the latest snapshot for an alert id.
     */
    void save(AlertEvent alertEvent);

    /**
     * Looks up a previously stored alert by its external alert id.
     */
    Optional<AlertEvent> findByAlertId(String alertId);
}

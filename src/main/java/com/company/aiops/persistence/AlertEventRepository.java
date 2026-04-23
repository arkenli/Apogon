package com.company.aiops.persistence;

import com.company.aiops.alert.AlertEvent;

import java.util.Optional;

public interface AlertEventRepository {

    void save(AlertEvent alertEvent);

    Optional<AlertEvent> findByAlertId(String alertId);
}

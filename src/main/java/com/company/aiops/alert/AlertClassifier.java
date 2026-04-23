package com.company.aiops.alert;

public interface AlertClassifier {

    AlertCategory classify(AlertEvent alert);
}

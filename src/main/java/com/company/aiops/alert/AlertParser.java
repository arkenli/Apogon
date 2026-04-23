package com.company.aiops.alert;

public interface AlertParser {

    AlertEvent parse(String rawText);
}

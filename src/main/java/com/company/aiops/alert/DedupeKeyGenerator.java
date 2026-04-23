package com.company.aiops.alert;

import org.springframework.stereotype.Component;

@Component
public class DedupeKeyGenerator {

    public String generate(AlertEvent alert) {
        return String.join("|",
                alert.category().name(),
                normalize(alert.serviceName()),
                normalize(alert.interfaceName()),
                normalize(alert.cluster())
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

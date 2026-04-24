package com.company.aiops.alert.dedupe;

import com.company.aiops.alert.model.AlertEvent;
import org.springframework.stereotype.Component;

/**
 * 根据设计文档中的维度生成 dedupeKey。
 */
@Component
public class DedupeKeyGenerator {

    /**
     * 按 category|serviceName|interfaceName|cluster 生成稳定去重键。
     */
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

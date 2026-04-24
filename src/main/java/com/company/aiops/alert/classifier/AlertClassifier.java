package com.company.aiops.alert.classifier;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;

/**
 * 根据告警字段识别业务分类，供后续分析链路路由使用。
 */
public interface AlertClassifier {

    /**
     * 识别告警所属类别。
     */
    AlertCategory classify(AlertEvent alert);
}

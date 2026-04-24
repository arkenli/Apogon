package com.company.aiops.alert.parser;

import com.company.aiops.alert.model.AlertEvent;

/**
 * 将原始告警文本解析成结构化告警对象。
 */
public interface AlertParser {

    /**
     * 解析 WiseEye 原始告警文本。
     */
    AlertEvent parse(String rawText);
}

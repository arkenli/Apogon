package com.company.aiops.alert.model;

/**
 * 当前系统支持的告警类别。
 */
public enum AlertCategory {
    RPC_ERROR_RATE,
    RPC_LATENCY,
    SERVICE_ERROR_LOG,
    UNKNOWN
}

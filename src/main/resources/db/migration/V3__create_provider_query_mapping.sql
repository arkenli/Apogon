CREATE TABLE aiops_provider_query_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Auto-increment primary key',
    service_name VARCHAR(256) NOT NULL COMMENT 'Service name from the alert',
    alert_cluster_name VARCHAR(128) NOT NULL COMMENT 'Cluster name from the alert',
    provider_name VARCHAR(128) NOT NULL COMMENT 'Provider call name',
    query_service_name VARCHAR(256) NOT NULL COMMENT 'Service name used by the provider query',
    query_cluster_name VARCHAR(128) NOT NULL COMMENT 'Cluster name used by the provider query',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Whether this mapping is enabled',
    metadata_json JSON COMMENT 'Optional provider-specific metadata',
    created_by VARCHAR(128) COMMENT 'Creator',
    updated_by VARCHAR(128) COMMENT 'Last updater',
    created_at DATETIME(3) NOT NULL COMMENT 'Creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'Last update time',
    UNIQUE KEY uk_provider_query_mapping (service_name, alert_cluster_name, provider_name),
    KEY idx_service_cluster_enabled (service_name, alert_cluster_name, enabled),
    KEY idx_provider_enabled (provider_name, enabled),
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Provider query target mapping table';

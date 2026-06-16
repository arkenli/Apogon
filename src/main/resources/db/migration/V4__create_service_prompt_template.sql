CREATE TABLE aiops_service_prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Auto-increment primary key',
    service_name VARCHAR(256) NOT NULL COMMENT 'Service name from the alert',
    alert_category VARCHAR(64) NOT NULL COMMENT 'AlertCategory name',
    system_prompt MEDIUMTEXT NOT NULL COMMENT 'Replaceable system analysis instructions',
    user_prompt MEDIUMTEXT NOT NULL COMMENT 'Replaceable user analysis instructions',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Whether this prompt template is enabled',
    version VARCHAR(128) COMMENT 'Template version label',
    metadata_json JSON COMMENT 'Optional metadata such as owner or rollout notes',
    created_by VARCHAR(128) COMMENT 'Creator',
    updated_by VARCHAR(128) COMMENT 'Last updater',
    created_at DATETIME(3) NOT NULL COMMENT 'Creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'Last update time',
    UNIQUE KEY uk_service_prompt_template (service_name, alert_category),
    KEY idx_service_category_enabled (service_name, alert_category, enabled),
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Service-specific LLM prompt template table';

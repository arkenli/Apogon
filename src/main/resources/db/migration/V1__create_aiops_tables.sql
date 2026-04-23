CREATE TABLE aiops_alert_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    alert_id VARCHAR(128) NOT NULL COMMENT '外部告警ID，例如 WiseEye Alert ID',
    category VARCHAR(64) NOT NULL COMMENT '系统识别后的告警类型',
    raw_category VARCHAR(128) COMMENT '原始告警Category字段',
    status VARCHAR(64) COMMENT '原始告警状态，例如 OPEN/CLEAR',
    severity VARCHAR(64) COMMENT '原始告警级别',
    service_name VARCHAR(256) COMMENT '告警服务名',
    interface_name VARCHAR(512) COMMENT '告警接口名',
    cluster_name VARCHAR(128) COMMENT '集群名',
    site VARCHAR(128) COMMENT '站点',
    tenant VARCHAR(128) COMMENT '租户',
    application_name VARCHAR(128) COMMENT '应用名',
    alert_name VARCHAR(512) COMMENT '告警名称',
    metric_name VARCHAR(256) COMMENT '指标名',
    metric_occur_time DATETIME(3) COMMENT '指标触发时间，优先取Details中的发生时间',
    received_at DATETIME(3) NOT NULL COMMENT '本系统收到告警的时间',
    trigger_values_json JSON COMMENT '结构化触发值，对应AlertTriggerValues',
    raw_text MEDIUMTEXT NOT NULL COMMENT '原始告警全文',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_alert_id (alert_id),
    KEY idx_service_interface_time (service_name, interface_name(255), metric_occur_time),
    KEY idx_cluster_category_time (cluster_name, category, metric_occur_time),
    KEY idx_metric_time (metric_name, metric_occur_time),
    KEY idx_received_at (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件表，保存Webhook原始告警和解析后的结构化字段';

CREATE TABLE aiops_analysis_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '关联告警ID',
    category VARCHAR(64) NOT NULL COMMENT '告警类型',
    dedupe_key VARCHAR(1024) NOT NULL COMMENT '去重键：category|serviceName|interfaceName|cluster',
    task_status VARCHAR(64) NOT NULL COMMENT 'AnalysisJobStatus状态机状态',
    current_step VARCHAR(128) COMMENT '当前执行步骤，例如 collect_provider.metric',
    status_reason TEXT COMMENT '当前状态说明，记录等待、降级或失败原因',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '任务级重试次数',
    state_version BIGINT NOT NULL DEFAULT 0 COMMENT '状态版本号，用于乐观锁更新',
    failure_reason TEXT COMMENT '失败原因',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    started_at DATETIME(3) COMMENT '开始执行时间',
    finished_at DATETIME(3) COMMENT '结束时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_status_created_at (task_status, created_at),
    KEY idx_dedupe_status_time (dedupe_key(255), task_status, created_at),
    KEY idx_dedupe_created_at (dedupe_key(255), created_at),
    KEY idx_category_status_time (category, task_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务表，使用显式状态机管理AnalysisJob生命周期';

CREATE TABLE aiops_analysis_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    category VARCHAR(64) NOT NULL COMMENT '告警类型',
    analysis_mode VARCHAR(64) NOT NULL COMMENT 'LLM_ASSISTED/RULE_BASED_DEGRADED/RULE_BASED_ONLY',
    summary TEXT NOT NULL COMMENT '分析摘要',
    impact_level VARCHAR(64) COMMENT '影响等级 LOW/MEDIUM/HIGH/UNKNOWN',
    impact_description TEXT COMMENT '影响说明',
    overall_confidence DECIMAL(5,4) COMMENT '整体置信度，0到1',
    llm_used TINYINT(1) NOT NULL COMMENT '是否使用LLM',
    llm_status VARCHAR(64) COMMENT 'LLM调用状态',
    degraded TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为降级结果',
    degraded_by_provider_failure TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否因Provider失败导致降级或降低置信度',
    degraded_reason TEXT COMMENT '降级原因',
    rule_engine_version VARCHAR(128) COMMENT '规则降级策略版本',
    result_json JSON NOT NULL COMMENT '完整分析结果JSON',
    generated_at DATETIME(3) NOT NULL COMMENT '结果生成时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_alert_category (alert_id, category),
    KEY idx_analysis_mode_generated_at (analysis_mode, generated_at),
    KEY idx_category_generated_at (category, generated_at),
    KEY idx_generated_at (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析结果表，保存面向接口和IM输出的最终结论';

CREATE TABLE aiops_evidence_bundle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    evidence_type VARCHAR(64) NOT NULL COMMENT '证据类型，例如 raw/compressed/rule',
    evidence_json JSON NOT NULL COMMENT '证据包JSON',
    expire_at DATETIME(3) NOT NULL COMMENT '过期清理时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_task_evidence_type (task_id, evidence_type),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据包表，保存原始证据、压缩证据和规则证据';

CREATE TABLE aiops_provider_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    provider_name VARCHAR(128) NOT NULL COMMENT 'Provider名称',
    provider_status VARCHAR(64) NOT NULL COMMENT 'ProviderStatus状态',
    critical TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否关键Provider',
    latency_ms BIGINT COMMENT '调用耗时毫秒',
    error_code VARCHAR(128) COMMENT '错误码',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_provider_status_time (provider_name, provider_status, created_at),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Provider调用日志表，用于记录数据源调用失败、超时和耗时';

CREATE TABLE aiops_llm_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    provider VARCHAR(128) COMMENT 'LLM供应方',
    model_name VARCHAR(128) COMMENT '模型名称',
    request_json JSON COMMENT 'LLM请求JSON',
    response_text MEDIUMTEXT COMMENT 'LLM原始响应文本',
    input_tokens INT COMMENT '输入token数',
    output_tokens INT COMMENT '输出token数',
    latency_ms BIGINT COMMENT '调用耗时毫秒',
    call_status VARCHAR(64) NOT NULL COMMENT '调用状态 SUCCESS/TIMEOUT/FAILED等',
    error_message TEXT COMMENT '错误信息',
    expire_at DATETIME(3) NOT NULL COMMENT '过期清理时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_call_status_created_at (call_status, created_at),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM调用日志表，用于审计、排障和成本统计';

CREATE TABLE aiops_duplicate_alert_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    source_alert_id VARCHAR(128) NOT NULL COMMENT '被合并的告警ID',
    target_task_id VARCHAR(128) NOT NULL COMMENT '命中的已有分析任务ID',
    dedupe_key VARCHAR(1024) NOT NULL COMMENT '去重键',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_source_alert_id (source_alert_id),
    KEY idx_target_task_id (target_task_id),
    KEY idx_dedupe_key_time (dedupe_key(255), created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重复告警关联表，记录5分钟去重合并关系';

CREATE TABLE aiops_resource_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    service_name VARCHAR(256) NOT NULL COMMENT '服务名',
    cluster_name VARCHAR(128) NOT NULL COMMENT '集群名',
    resource_type VARCHAR(64) NOT NULL COMMENT '资源类型：DATABASE/DCS/MQ',
    resource_id VARCHAR(256) NOT NULL COMMENT '资源实例ID',
    resource_name VARCHAR(256) COMMENT '资源实例名称',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    metadata_json JSON COMMENT '扩展信息，例如所属环境、备注、连接标识',
    created_by VARCHAR(128) COMMENT '创建人',
    updated_by VARCHAR(128) COMMENT '更新人',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_service_cluster_resource (service_name, cluster_name, resource_type, resource_id),
    KEY idx_service_cluster_enabled (service_name, cluster_name, enabled),
    KEY idx_resource_type_id (resource_type, resource_id),
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务资源映射表，预置service+cluster到DB/DCS/MQ实例的关系';

CREATE TABLE aiops_analysis_job_state_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    from_status VARCHAR(64) COMMENT '变更前状态',
    to_status VARCHAR(64) NOT NULL COMMENT '变更后状态',
    step_name VARCHAR(128) COMMENT '状态对应的执行步骤',
    reason TEXT COMMENT '状态变更原因',
    state_version BIGINT NOT NULL COMMENT '变更后的状态版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_created_at (task_id, created_at),
    KEY idx_alert_id (alert_id),
    KEY idx_to_status_created_at (to_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务状态流转日志，用于审计、排障和耗时统计';

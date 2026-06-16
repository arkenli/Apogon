-- ams_front 服务级 Prompt 模板示例。
-- 本文件仅用于手工执行/参考，不是 Flyway migration。
--
-- 注意：
-- 1. system_prompt 和 user_prompt 只维护可替换的分析说明。
-- 2. JSON 输出结构、枚举约束、evidenceIds 校验约束、重试修复提示仍由代码固定追加。
-- 3. 不要在这里写完整最终 Prompt，也不要覆盖 JSON schema。

USE aiops_alert_analysis;

INSERT INTO aiops_service_prompt_template (
    service_name,
    alert_category,
    system_prompt,
    user_prompt,
    enabled,
    version,
    metadata_json,
    created_by,
    updated_by,
    created_at,
    updated_at
) VALUES
(
    'ams_front',
    'RPC_ERROR_RATE',
    '你是 ams_front 服务专属 AIOps 告警分析助手。请优先从活动、权益、订单查询和前台流量入口的业务链路理解证据，但不能编造证据中不存在的服务、接口、errorCode、日志或 trace。对于错误率告警，需要区分系统异常、下游失败和业务校验 errorCode 未屏蔽；只有证据明确显示是正常业务校验时，才按业务校验处理。',
    '请分析 ams_front 的 RPC 接口错误率告警。重点检查：1. 失败是否集中在活动、权益或订单查询链路；2. errorMsg 是否体现系统异常、参数校验、活动资格校验或下游返回异常；3. 是否存在少量 uid、少量 trace 或单一 errorCode 集中；4. 下游 RPC、数据库、DCS、MQ、容器资源是否与错误率升高相关；5. 给出人工排查建议和 errorCode 屏蔽建议，但不要执行任何动作。',
    1,
    'ams-front-error-rate-v1',
    JSON_OBJECT(
        'owner', 'ams-front-team',
        'note', '替换错误率告警分析说明，固定 JSON 输出约束由代码追加'
    ),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
),
(
    'ams_front',
    'RPC_LATENCY',
    '你是 ams_front 服务专属 AIOps 告警分析助手。请优先从前台入口、活动查询、权益查询、缓存访问和下游 RPC 调用链路理解时延证据，但不能编造证据中不存在的指标、日志、trace、服务名或资源实例。证据不足时必须明确说明不确定。',
    '请分析 ams_front 的 RPC 接口时延告警。重点检查：1. 平均时延和 P99 尾延迟哪个异常更明显；2. 慢点更可能在 ams_front 自身、缓存、CSE、数据库、DCS、MQ 还是下游服务；3. 是否有请求量突增或单实例资源压力；4. 如果慢点在下游，请指出最可疑的 serviceName、interfaceName 和 evidenceIds；5. 给出人工排查建议，但不要执行任何动作。',
    1,
    'ams-front-latency-v1',
    JSON_OBJECT(
        'owner', 'ams-front-team',
        'note', '替换时延告警分析说明，固定 JSON 输出约束由代码追加'
    ),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE
    system_prompt = VALUES(system_prompt),
    user_prompt = VALUES(user_prompt),
    enabled = VALUES(enabled),
    version = VALUES(version),
    metadata_json = VALUES(metadata_json),
    updated_by = VALUES(updated_by),
    updated_at = CURRENT_TIMESTAMP(3);

SELECT
    service_name,
    alert_category,
    enabled,
    version,
    metadata_json
FROM aiops_service_prompt_template
WHERE service_name = 'ams_front'
ORDER BY alert_category;

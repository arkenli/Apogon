-- Manual seed script for aiops_resource_mapping.
-- This file is intentionally outside Flyway's migration directory so it can be
-- executed on demand without changing immutable migration history.

USE aiops_alert_analysis;

INSERT INTO aiops_resource_mapping (
    service_name,
    cluster_name,
    resource_type,
    resource_id,
    resource_name,
    enabled,
    metadata_json,
    created_by,
    updated_by,
    created_at,
    updated_at
) VALUES
(
    'ams_front',
    'cluster1',
    'DATABASE',
    'db_ams_activity',
    'ams activity mysql',
    1,
    JSON_OBJECT('env', 'local', 'role', 'primary'),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
),
(
    'ams_front',
    'cluster1',
    'DCS',
    'dcs_ams_cache',
    'ams cache redis',
    1,
    JSON_OBJECT('env', 'local', 'role', 'cache'),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
),
(
    'vmall_ingresscache_ica',
    'cluster2',
    'DCS',
    'dcs_ingress_cache',
    'ingress cache redis',
    1,
    JSON_OBJECT('env', 'local', 'role', 'cache'),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE
    resource_name = VALUES(resource_name),
    enabled = VALUES(enabled),
    metadata_json = VALUES(metadata_json),
    updated_by = VALUES(updated_by),
    updated_at = CURRENT_TIMESTAMP(3);

-- Optional template for additional mappings:
-- INSERT INTO aiops_resource_mapping (
--     service_name, cluster_name, resource_type, resource_id, resource_name,
--     enabled, metadata_json, created_by, updated_by, created_at, updated_at
-- ) VALUES (
--     'your_service',
--     'cluster1',
--     'DATABASE',
--     'db_your_service',
--     'your service mysql',
--     1,
--     JSON_OBJECT('env', 'local', 'role', 'primary'),
--     'manual_seed',
--     'manual_seed',
--     CURRENT_TIMESTAMP(3),
--     CURRENT_TIMESTAMP(3)
-- )
-- ON DUPLICATE KEY UPDATE
--     resource_name = VALUES(resource_name),
--     enabled = VALUES(enabled),
--     metadata_json = VALUES(metadata_json),
--     updated_by = VALUES(updated_by),
--     updated_at = CURRENT_TIMESTAMP(3);
--
-- Example MQ row:
-- INSERT INTO aiops_resource_mapping (
--     service_name, cluster_name, resource_type, resource_id, resource_name,
--     enabled, metadata_json, created_by, updated_by, created_at, updated_at
-- ) VALUES (
--     'your_service',
--     'cluster1',
--     'MQ',
--     'mq_your_service',
--     'your service mq',
--     1,
--     JSON_OBJECT('env', 'local', 'role', 'producer-consumer'),
--     'manual_seed',
--     'manual_seed',
--     CURRENT_TIMESTAMP(3),
--     CURRENT_TIMESTAMP(3)
-- )
-- ON DUPLICATE KEY UPDATE
--     resource_name = VALUES(resource_name),
--     enabled = VALUES(enabled),
--     metadata_json = VALUES(metadata_json),
--     updated_by = VALUES(updated_by),
--     updated_at = CURRENT_TIMESTAMP(3);

SELECT
    service_name,
    cluster_name,
    resource_type,
    resource_id,
    resource_name,
    enabled
FROM aiops_resource_mapping
ORDER BY service_name, cluster_name, resource_type, resource_id;

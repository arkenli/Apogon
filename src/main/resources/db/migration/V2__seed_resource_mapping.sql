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
    'migration',
    'migration',
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
    'migration',
    'migration',
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
    'migration',
    'migration',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE
    resource_name = VALUES(resource_name),
    enabled = VALUES(enabled),
    metadata_json = VALUES(metadata_json),
    updated_by = VALUES(updated_by),
    updated_at = CURRENT_TIMESTAMP(3);

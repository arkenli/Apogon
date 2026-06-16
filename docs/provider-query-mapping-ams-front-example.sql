-- Example provider query target mappings for ams_front container metrics.
-- This file is for manual execution/reference only and is not a Flyway migration.

USE aiops_alert_analysis;

-- Note:
-- 1. The cluster1 -> cluster1 row is functionally optional because it matches the alert cluster.
-- 2. It is still included here as an explicit example for metric.container mapping maintenance.

INSERT INTO aiops_provider_query_mapping (
    service_name,
    alert_cluster_name,
    provider_name,
    query_service_name,
    query_cluster_name,
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
    'metric.container',
    'ams_front',
    'cluster1',
    1,
    JSON_OBJECT('note', 'container cluster keeps the same as alert cluster'),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
),
(
    'ams_front',
    'cluster2',
    'metric.container',
    'ams_front',
    'cluster2_front',
    1,
    JSON_OBJECT('note', 'container metrics need the frontend cluster alias'),
    'manual_seed',
    'manual_seed',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE
    query_service_name = VALUES(query_service_name),
    query_cluster_name = VALUES(query_cluster_name),
    enabled = VALUES(enabled),
    metadata_json = VALUES(metadata_json),
    updated_by = VALUES(updated_by),
    updated_at = CURRENT_TIMESTAMP(3);

SELECT
    service_name,
    alert_cluster_name,
    provider_name,
    query_service_name,
    query_cluster_name,
    enabled,
    metadata_json
FROM aiops_provider_query_mapping
WHERE service_name = 'ams_front'
  AND provider_name = 'metric.container'
ORDER BY alert_cluster_name;

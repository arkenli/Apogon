# Provider Query Mapping Design

## Context

The alert payload exposes one cluster name, for example `cluster2`, and the current
provider orchestration passes that value directly into service-level providers such as
`ContainerMetricProvider`, `CseMetricProvider`, and `BaselineProvider`.

That is no longer sufficient. Different observability systems can require different query
names for the same alerted service and cluster. For example, an alert can identify the
cluster as `cluster2`, while container metrics must be queried with `cluster2_front` and
CSE metrics must be queried with `cluster2_rpc`.

The existing `aiops_resource_mapping` table should not absorb this responsibility. It
currently represents service-to-resource inventory for DB, DCS, and MQ dependencies. Query
name translation is a different concept: it describes how a provider should address an
external system.

## Goals

1. Preserve `AlertEvent.cluster` as the alert-domain cluster used for dedupe, history, and
   display.
2. Allow each provider to resolve its own query service and query cluster names.
3. Keep resource inventory mapping independent from provider query-name mapping.
4. Provide a safe fallback: missing mappings use the original alert service and cluster.
5. Make the capability extensible to future providers without creating container-specific
   fields or special cases.

## Non-Goals

1. Do not change alert parsing, dedupe key generation, historical lookup, or alert storage.
2. Do not repurpose `aiops_resource_mapping` or change DB/DCS/MQ resource-id lookup behavior.
3. Do not add wildcard mapping rules in the first iteration.
4. Do not change provider method signatures unless a provider already needs a new argument.
5. Do not build a frontend management page in this iteration.

## Recommended Approach

Add a dedicated provider query mapping table and a small resolver used by
`DefaultProviderOrchestrator` before invoking providers.

The resolver converts:

```text
alert serviceName + alert cluster + providerName
```

into:

```text
query serviceName + query cluster
```

If no enabled mapping exists, the resolver returns the original alert service and cluster.

## Data Model

Create a new Flyway migration for `aiops_provider_query_mapping`.

```sql
CREATE TABLE aiops_provider_query_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Auto-increment primary key',
    service_name VARCHAR(256) NOT NULL COMMENT 'Service name from the alert',
    alert_cluster_name VARCHAR(128) NOT NULL COMMENT 'Cluster name from the alert',
    provider_name VARCHAR(128) NOT NULL COMMENT 'Provider call name, for example metric.container',
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
```

Example rows:

```text
service_name           alert_cluster_name  provider_name     query_service_name     query_cluster_name
vmall_ingresscache_ica cluster2             metric.container  vmall_ingresscache_ica cluster2_front
vmall_ingresscache_ica cluster2             metric.cse        vmall_ingresscache_ica cluster2_rpc
```

## Provider Names

Provider names should be shared constants instead of private strings duplicated across the
orchestrator, resolver, and maintenance service.

Initial names:

```text
resourceMapping
baseline
interfaceLog
runtimeLog
trace
metric.container
metric.cse
metric.database
metric.dcs
metric.mq
provider.queryMapping
```

The mapping table uses the consumer provider name, for example `metric.container`.
`provider.queryMapping` is only the provider-call-log name for the resolver lookup itself.

## Components

### ProviderQueryTarget

Add a small model:

```java
public record ProviderQueryTarget(
        String providerName,
        String serviceName,
        String clusterName,
        boolean mapped
) {
}
```

`serviceName` and `clusterName` in this record are query values, not alert identity values.
`mapped=false` means the target fell back to alert values.

### ProviderQueryTargetResolver

Add a resolver interface:

```java
public interface ProviderQueryTargetResolver {
    ProviderQueryTargets resolveAll(String serviceName, String alertClusterName, Set<String> providerNames);
}
```

`ProviderQueryTargets` can be a thin wrapper around a map:

```java
targetFor("metric.container")
```

It returns an explicit fallback target when a provider has no configured row.

### JDBC Repository

Add a repository for:

1. Loading all enabled mappings for `service_name + alert_cluster_name`.
2. Listing rows for the maintenance API.
3. Creating, updating, and deleting individual rows.

The resolver should query once per analysis task, not once per provider.

### Maintenance API

Expose a separate CRUD surface:

```text
GET    /api/v1/provider-query-mappings?serviceName=&alertClusterName=&providerName=
POST   /api/v1/provider-query-mappings/items
PUT    /api/v1/provider-query-mappings/items/{id}
DELETE /api/v1/provider-query-mappings/items/{id}
```

Request fields:

```json
{
  "serviceName": "vmall_ingresscache_ica",
  "alertClusterName": "cluster2",
  "providerName": "metric.container",
  "queryServiceName": "vmall_ingresscache_ica",
  "queryClusterName": "cluster2_front",
  "metadataJson": "{\"source\":\"ops\"}"
}
```

Validation:

1. Required fields must be non-blank after trimming.
2. `providerName` must be one of the shared provider constants.
3. `metadataJson`, when present, must be valid JSON.
4. Duplicate rows are rejected by the unique key and returned as a bad request.

The maintenance API accepts known provider names for forward-compatible configuration, but the
first runtime iteration only consumes provider query targets for `baseline`,
`metric.container`, and `metric.cse`. Rows for other known providers are stored as configuration
data and take effect only after the corresponding provider integrates query-target resolution.

## Orchestrator Data Flow

At the start of `DefaultProviderOrchestrator.collect(...)`:

1. Resolve query targets for providers that can consume service or cluster query names.
2. Wrap the resolver call with `ProviderCallExecutor` using provider name
   `provider.queryMapping`.
3. If resolution succeeds, use the returned mapped or fallback targets.
4. If resolution fails, build fallback targets from the alert service and cluster and keep
   collecting provider evidence.

Provider calls then use the query target when appropriate:

```text
baseline          -> targetFor("baseline")
metric.container  -> targetFor("metric.container")
metric.cse        -> targetFor("metric.cse")
```

`ResourceMappingProvider` continues to use the alert service and alert cluster because it
resolves DB/DCS/MQ resources bound to the alert-domain service and cluster. DB/DCS/MQ metric
providers continue to use resource ids returned by `ResourceMapping`.

`InterfaceLogProvider`, `RuntimeLogProvider`, and `TraceProvider` are not changed in the
first iteration because their current method signatures do not accept cluster names. They
can adopt the resolver later if their real implementations need external query names.

## Error Handling

Missing mapping:

```text
Use alert service and alert cluster. Do not record missing evidence.
```

Disabled mapping:

```text
Treat as missing. Use fallback values.
```

Mapping table query failure:

```text
Use alert service and alert cluster for all query targets.
Record provider.queryMapping as non-critical missing evidence.
Do not trigger fallback analysis solely because query mapping resolution failed.
```

Invalid maintenance request:

```text
Return BAD_REQUEST with the field-level cause where possible.
```

Deleted mapping:

```text
Future analysis tasks fall back to alert values for that provider.
Existing task results are not rewritten.
```

## Testing

### Resolver Tests

1. Returns mapped target for an enabled exact provider row.
2. Returns different query clusters for different providers under the same alert cluster.
3. Falls back to alert service and cluster when no row exists.
4. Ignores disabled rows.
5. Falls back when the repository throws, while exposing the failure to the orchestrator.

### Orchestrator Tests

1. `ContainerMetricProvider` receives `cluster2_front` when `metric.container` is mapped.
2. `CseMetricProvider` receives `cluster2_rpc` when `metric.cse` is mapped.
3. `BaselineProvider` receives a mapped cluster when `baseline` is mapped.
4. With no mappings, existing behavior is unchanged.
5. When query mapping resolution fails, provider collection continues with alert cluster and
   includes non-critical missing evidence for `provider.queryMapping`.
6. `ResourceMappingProvider` still receives the original alert cluster.

### Repository and API Tests

1. Create, update, list, and delete provider query mapping rows.
2. Reject duplicate `serviceName + alertClusterName + providerName` rows.
3. Reject unknown provider names.
4. Reject invalid metadata JSON.
5. Preserve filters for service, alert cluster, and provider name.

## Acceptance Criteria

1. Alert identity fields remain unchanged through ingestion, dedupe, and history.
2. `aiops_resource_mapping` keeps its existing DB/DCS/MQ behavior.
3. At least `baseline`, `metric.container`, and `metric.cse` can use provider-specific query
   service and cluster names.
4. Missing mappings are backward compatible and use alert values.
5. Mapping lookup failures degrade evidence quality but do not stop provider collection.
6. The new mapping data can be maintained through dedicated APIs.

# Provider Query Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add provider-specific query service/cluster mapping so providers can query external systems with names that differ from the alert identity.

**Architecture:** Keep alert identity and DB/DCS/MQ resource mapping unchanged. Add a dedicated `aiops_provider_query_mapping` table, a repository-backed resolver, and orchestrator wiring that resolves provider query targets once per analysis task and falls back to alert values when no mapping exists or the resolver fails.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Spring MVC, Spring JDBC, Flyway, JUnit 5, AssertJ, Mockito, MockMvc, TestRestTemplate.

---

## File Structure

Create:

- `src/main/java/com/company/aiops/provider/ProviderNames.java`  
  Shared provider-name constants and validation helpers.
- `src/main/java/com/company/aiops/provider/query/ProviderQueryTarget.java`  
  Immutable query service/cluster target for one provider.
- `src/main/java/com/company/aiops/provider/query/ProviderQueryTargets.java`  
  Immutable lookup wrapper with fallback behavior.
- `src/main/java/com/company/aiops/provider/query/ProviderQueryTargetResolver.java`  
  Resolver interface used by the orchestrator.
- `src/main/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolver.java`  
  Repository-backed resolver implementation.
- `src/main/java/com/company/aiops/persistence/repository/ProviderQueryMappingRepository.java`  
  Repository contract for resolver and CRUD.
- `src/main/java/com/company/aiops/persistence/repository/jdbc/JdbcProviderQueryMappingRepository.java`  
  JDBC implementation.
- `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingRecord.java`  
  Stored table row model.
- `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemUpsertRequest.java`  
  API create/update request.
- `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemResponse.java`  
  API response item.
- `src/main/java/com/company/aiops/provider/query/mapping/service/ProviderQueryMappingService.java`  
  CRUD validation and normalization.
- `src/main/java/com/company/aiops/provider/query/mapping/controller/ProviderQueryMappingController.java`  
  REST API.
- `src/main/resources/db/migration/V3__create_provider_query_mapping.sql`  
  Flyway migration for the new table.
- `src/test/java/com/company/aiops/provider/query/ProviderQueryTargetsTests.java`
- `src/test/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolverTests.java`
- `src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerTests.java`
- `src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerIntegrationTests.java`

Modify:

- `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`  
  Resolve and use provider query targets; include query mapping failures in missing evidence without forcing fallback.
- `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`  
  Add resolver dependency and provider query-target assertions.
- `src/test/java/com/company/aiops/support/AbstractAiopsIntegrationTestSupport.java`  
  Clean `aiops_provider_query_mapping` when database-backed test suites need full cleanup.

---

### Task 1: Shared Provider Names and Query Target Model

**Files:**
- Create: `src/main/java/com/company/aiops/provider/ProviderNames.java`
- Create: `src/main/java/com/company/aiops/provider/query/ProviderQueryTarget.java`
- Create: `src/main/java/com/company/aiops/provider/query/ProviderQueryTargets.java`
- Test: `src/test/java/com/company/aiops/provider/query/ProviderQueryTargetsTests.java`

- [ ] **Step 1: Write the failing model tests**

Create `src/test/java/com/company/aiops/provider/query/ProviderQueryTargetsTests.java`:

```java
package com.company.aiops.provider.query;

import com.company.aiops.provider.ProviderNames;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderQueryTargetsTests {

    @Test
    void targetForReturnsMappedTargetWhenPresent() {
        ProviderQueryTargets targets = new ProviderQueryTargets(
                "demo-service",
                "cluster2",
                Map.of(
                        ProviderNames.METRIC_CONTAINER,
                        ProviderQueryTarget.mapped(
                                ProviderNames.METRIC_CONTAINER,
                                "demo-service",
                                "cluster2_front"
                        )
                )
        );

        ProviderQueryTarget target = targets.targetFor(ProviderNames.METRIC_CONTAINER);

        assertThat(target.serviceName()).isEqualTo("demo-service");
        assertThat(target.clusterName()).isEqualTo("cluster2_front");
        assertThat(target.mapped()).isTrue();
    }

    @Test
    void targetForReturnsFallbackTargetWhenMissing() {
        ProviderQueryTargets targets = ProviderQueryTargets.fallback(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER)
        );

        ProviderQueryTarget target = targets.targetFor(ProviderNames.METRIC_CSE);

        assertThat(target.providerName()).isEqualTo(ProviderNames.METRIC_CSE);
        assertThat(target.serviceName()).isEqualTo("demo-service");
        assertThat(target.clusterName()).isEqualTo("cluster2");
        assertThat(target.mapped()).isFalse();
    }

    @Test
    void providerNamesRecognizesKnownProviders() {
        assertThat(ProviderNames.isKnown(ProviderNames.BASELINE)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.PROVIDER_QUERY_MAPPING)).isTrue();
        assertThat(ProviderNames.isKnown("metric.unknown")).isFalse();
        assertThat(ProviderNames.queryMappableProviderNames())
                .containsExactlyInAnyOrder(
                        ProviderNames.BASELINE,
                        ProviderNames.METRIC_CONTAINER,
                        ProviderNames.METRIC_CSE
                );
    }

    @Test
    void targetRejectsBlankValues() {
        assertThatThrownBy(() -> ProviderQueryTarget.mapped("", "demo-service", "cluster2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName must not be blank");
    }
}
```

- [ ] **Step 2: Run the model tests and verify they fail**

Run:

```bash
mvn -Dtest=ProviderQueryTargetsTests test
```

Expected: FAIL during compilation because `ProviderNames`, `ProviderQueryTarget`, and `ProviderQueryTargets` do not exist.

- [ ] **Step 3: Add shared provider names**

Create `src/main/java/com/company/aiops/provider/ProviderNames.java`:

```java
package com.company.aiops.provider;

import java.util.Set;

/**
 * Stable provider names used in provider call logs, query mapping, and orchestration.
 */
public final class ProviderNames {

    public static final String RESOURCE_MAPPING = "resourceMapping";
    public static final String BASELINE = "baseline";
    public static final String INTERFACE_LOG = "interfaceLog";
    public static final String TRACE = "trace";
    public static final String RUNTIME_LOG = "runtimeLog";
    public static final String METRIC_CONTAINER = "metric.container";
    public static final String METRIC_CSE = "metric.cse";
    public static final String METRIC_DATABASE = "metric.database";
    public static final String METRIC_DCS = "metric.dcs";
    public static final String METRIC_MQ = "metric.mq";
    public static final String PROVIDER_QUERY_MAPPING = "provider.queryMapping";

    private static final Set<String> ALL = Set.of(
            RESOURCE_MAPPING,
            BASELINE,
            INTERFACE_LOG,
            TRACE,
            RUNTIME_LOG,
            METRIC_CONTAINER,
            METRIC_CSE,
            METRIC_DATABASE,
            METRIC_DCS,
            METRIC_MQ,
            PROVIDER_QUERY_MAPPING
    );

    private static final Set<String> QUERY_MAPPABLE = Set.of(
            BASELINE,
            METRIC_CONTAINER,
            METRIC_CSE
    );

    private ProviderNames() {
    }

    public static boolean isKnown(String providerName) {
        return ALL.contains(providerName);
    }

    public static Set<String> queryMappableProviderNames() {
        return QUERY_MAPPABLE;
    }
}
```

- [ ] **Step 4: Add query target model classes**

Create `src/main/java/com/company/aiops/provider/query/ProviderQueryTarget.java`:

```java
package com.company.aiops.provider.query;

/**
 * Provider-specific service and cluster values used for external lookups.
 */
public record ProviderQueryTarget(
        String providerName,
        String serviceName,
        String clusterName,
        boolean mapped
) {

    public ProviderQueryTarget {
        requireText(providerName, "providerName");
        requireText(serviceName, "serviceName");
        requireText(clusterName, "clusterName");
    }

    public static ProviderQueryTarget mapped(String providerName, String serviceName, String clusterName) {
        return new ProviderQueryTarget(providerName, serviceName, clusterName, true);
    }

    public static ProviderQueryTarget fallback(String providerName, String serviceName, String clusterName) {
        return new ProviderQueryTarget(providerName, serviceName, clusterName, false);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
```

Create `src/main/java/com/company/aiops/provider/query/ProviderQueryTargets.java`:

```java
package com.company.aiops.provider.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lookup wrapper for provider query targets resolved for one alert.
 */
public record ProviderQueryTargets(
        String alertServiceName,
        String alertClusterName,
        Map<String, ProviderQueryTarget> targets
) {

    public ProviderQueryTargets {
        if (alertServiceName == null || alertServiceName.isBlank()) {
            throw new IllegalArgumentException("alertServiceName must not be blank");
        }
        if (alertClusterName == null || alertClusterName.isBlank()) {
            throw new IllegalArgumentException("alertClusterName must not be blank");
        }
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    public static ProviderQueryTargets fallback(
            String alertServiceName,
            String alertClusterName,
            Set<String> providerNames
    ) {
        Map<String, ProviderQueryTarget> fallbackTargets = new LinkedHashMap<>();
        for (String providerName : providerNames) {
            fallbackTargets.put(
                    providerName,
                    ProviderQueryTarget.fallback(providerName, alertServiceName, alertClusterName)
            );
        }
        return new ProviderQueryTargets(alertServiceName, alertClusterName, fallbackTargets);
    }

    public ProviderQueryTarget targetFor(String providerName) {
        ProviderQueryTarget target = targets.get(providerName);
        if (target != null) {
            return target;
        }
        return ProviderQueryTarget.fallback(providerName, alertServiceName, alertClusterName);
    }
}
```

- [ ] **Step 5: Run the model tests**

Run:

```bash
mvn -Dtest=ProviderQueryTargetsTests test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add src/main/java/com/company/aiops/provider/ProviderNames.java \
        src/main/java/com/company/aiops/provider/query/ProviderQueryTarget.java \
        src/main/java/com/company/aiops/provider/query/ProviderQueryTargets.java \
        src/test/java/com/company/aiops/provider/query/ProviderQueryTargetsTests.java
git commit -m "feat: add provider query target model"
```

---

### Task 2: Provider Query Mapping Table and Repository

**Files:**
- Create: `src/main/resources/db/migration/V3__create_provider_query_mapping.sql`
- Create: `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingRecord.java`
- Create: `src/main/java/com/company/aiops/persistence/repository/ProviderQueryMappingRepository.java`
- Create: `src/main/java/com/company/aiops/persistence/repository/jdbc/JdbcProviderQueryMappingRepository.java`

- [ ] **Step 1: Add the Flyway migration**

Create `src/main/resources/db/migration/V3__create_provider_query_mapping.sql`:

```sql
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
```

- [ ] **Step 2: Add the record model**

Create `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingRecord.java`:

```java
package com.company.aiops.provider.query.mapping.model;

import java.time.Instant;

/**
 * One stored row from aiops_provider_query_mapping.
 */
public record ProviderQueryMappingRecord(
        Long id,
        String serviceName,
        String alertClusterName,
        String providerName,
        String queryServiceName,
        String queryClusterName,
        boolean enabled,
        String metadataJson,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
```

- [ ] **Step 3: Add the repository contract**

Create `src/main/java/com/company/aiops/persistence/repository/ProviderQueryMappingRepository.java`:

```java
package com.company.aiops.persistence.repository;

import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;

import java.util.List;
import java.util.Optional;

/**
 * Reads and maintains provider-specific query target mappings.
 */
public interface ProviderQueryMappingRepository {

    List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
            String serviceName,
            String alertClusterName
    );

    List<ProviderQueryMappingRecord> findAll(
            String serviceName,
            String alertClusterName,
            String providerName
    );

    Optional<ProviderQueryMappingRecord> findById(long id);

    ProviderQueryMappingRecord create(ProviderQueryMappingRecord record);

    ProviderQueryMappingRecord update(ProviderQueryMappingRecord record);

    boolean deleteById(long id);
}
```

- [ ] **Step 4: Add the JDBC repository**

Create `src/main/java/com/company/aiops/persistence/repository/jdbc/JdbcProviderQueryMappingRepository.java`:

```java
package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for provider query target mappings.
 */
@Repository
public class JdbcProviderQueryMappingRepository implements ProviderQueryMappingRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProviderQueryMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
            String serviceName,
            String alertClusterName
    ) {
        return jdbcTemplate.query(
                """
                        SELECT id, service_name, alert_cluster_name, provider_name,
                               query_service_name, query_cluster_name, enabled, metadata_json,
                               created_by, updated_by, created_at, updated_at
                        FROM aiops_provider_query_mapping
                        WHERE service_name = ? AND alert_cluster_name = ? AND enabled = 1
                        ORDER BY provider_name
                        """,
                this::mapRecord,
                serviceName,
                alertClusterName
        );
    }

    @Override
    public List<ProviderQueryMappingRecord> findAll(
            String serviceName,
            String alertClusterName,
            String providerName
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, service_name, alert_cluster_name, provider_name,
                       query_service_name, query_cluster_name, enabled, metadata_json,
                       created_by, updated_by, created_at, updated_at
                FROM aiops_provider_query_mapping
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (hasText(serviceName)) {
            sql.append(" AND service_name = ?");
            args.add(serviceName);
        }
        if (hasText(alertClusterName)) {
            sql.append(" AND alert_cluster_name = ?");
            args.add(alertClusterName);
        }
        if (hasText(providerName)) {
            sql.append(" AND provider_name = ?");
            args.add(providerName);
        }
        sql.append(" ORDER BY service_name, alert_cluster_name, provider_name");
        return jdbcTemplate.query(sql.toString(), this::mapRecord, args.toArray());
    }

    @Override
    public Optional<ProviderQueryMappingRecord> findById(long id) {
        List<ProviderQueryMappingRecord> records = jdbcTemplate.query(
                """
                        SELECT id, service_name, alert_cluster_name, provider_name,
                               query_service_name, query_cluster_name, enabled, metadata_json,
                               created_by, updated_by, created_at, updated_at
                        FROM aiops_provider_query_mapping
                        WHERE id = ?
                        """,
                this::mapRecord,
                id
        );
        return records.stream().findFirst();
    }

    @Override
    public ProviderQueryMappingRecord create(ProviderQueryMappingRecord record) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO aiops_provider_query_mapping (
                                service_name, alert_cluster_name, provider_name,
                                query_service_name, query_cluster_name, enabled, metadata_json,
                                created_by, updated_by, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, record.serviceName());
            statement.setString(2, record.alertClusterName());
            statement.setString(3, record.providerName());
            statement.setString(4, record.queryServiceName());
            statement.setString(5, record.queryClusterName());
            statement.setBoolean(6, record.enabled());
            statement.setString(7, record.metadataJson());
            statement.setString(8, record.createdBy());
            statement.setString(9, record.updatedBy());
            statement.setTimestamp(10, timestamp(now));
            statement.setTimestamp(11, timestamp(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create provider query mapping row: generated id is null");
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Created provider query mapping row cannot be reloaded"));
    }

    @Override
    public ProviderQueryMappingRecord update(ProviderQueryMappingRecord record) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                        UPDATE aiops_provider_query_mapping
                        SET service_name = ?,
                            alert_cluster_name = ?,
                            provider_name = ?,
                            query_service_name = ?,
                            query_cluster_name = ?,
                            enabled = ?,
                            metadata_json = ?,
                            updated_by = ?,
                            updated_at = ?
                        WHERE id = ?
                        """,
                record.serviceName(),
                record.alertClusterName(),
                record.providerName(),
                record.queryServiceName(),
                record.queryClusterName(),
                record.enabled(),
                record.metadataJson(),
                record.updatedBy(),
                timestamp(now),
                record.id()
        );
        return findById(record.id())
                .orElseThrow(() -> new IllegalStateException("Updated provider query mapping row cannot be reloaded"));
    }

    @Override
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM aiops_provider_query_mapping WHERE id = ?", id) > 0;
    }

    private ProviderQueryMappingRecord mapRecord(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ProviderQueryMappingRecord(
                rs.getLong("id"),
                rs.getString("service_name"),
                rs.getString("alert_cluster_name"),
                rs.getString("provider_name"),
                rs.getString("query_service_name"),
                rs.getString("query_cluster_name"),
                rs.getBoolean("enabled"),
                rs.getString("metadata_json"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 5: Compile**

Run:

```bash
mvn -DskipTests compile
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add src/main/resources/db/migration/V3__create_provider_query_mapping.sql \
        src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingRecord.java \
        src/main/java/com/company/aiops/persistence/repository/ProviderQueryMappingRepository.java \
        src/main/java/com/company/aiops/persistence/repository/jdbc/JdbcProviderQueryMappingRepository.java
git commit -m "feat: add provider query mapping repository"
```

---

### Task 3: Repository-Backed Query Target Resolver

**Files:**
- Create: `src/main/java/com/company/aiops/provider/query/ProviderQueryTargetResolver.java`
- Create: `src/main/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolver.java`
- Test: `src/test/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolverTests.java`

- [ ] **Step 1: Write the failing resolver tests**

Create `src/test/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolverTests.java`:

```java
package com.company.aiops.provider.query;

import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultProviderQueryTargetResolverTests {

    @Test
    void resolveAllReturnsMappedTargetsForRequestedProviders() {
        DefaultProviderQueryTargetResolver resolver = new DefaultProviderQueryTargetResolver(
                repository(List.of(
                        record(ProviderNames.METRIC_CONTAINER, "cluster2_front", true),
                        record(ProviderNames.METRIC_CSE, "cluster2_rpc", true),
                        record(ProviderNames.RUNTIME_LOG, "cluster2_logs", true)
                ))
        );

        ProviderQueryTargets targets = resolver.resolveAll(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER, ProviderNames.METRIC_CSE)
        );

        assertThat(targets.targetFor(ProviderNames.METRIC_CONTAINER).clusterName()).isEqualTo("cluster2_front");
        assertThat(targets.targetFor(ProviderNames.METRIC_CSE).clusterName()).isEqualTo("cluster2_rpc");
        assertThat(targets.targetFor(ProviderNames.RUNTIME_LOG).clusterName()).isEqualTo("cluster2");
    }

    @Test
    void resolveAllFallsBackForMissingProviderRows() {
        DefaultProviderQueryTargetResolver resolver = new DefaultProviderQueryTargetResolver(repository(List.of()));

        ProviderQueryTargets targets = resolver.resolveAll(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.BASELINE)
        );

        ProviderQueryTarget target = targets.targetFor(ProviderNames.BASELINE);
        assertThat(target.serviceName()).isEqualTo("demo-service");
        assertThat(target.clusterName()).isEqualTo("cluster2");
        assertThat(target.mapped()).isFalse();
    }

    @Test
    void resolveAllLetsRepositoryFailureBubbleToOrchestrator() {
        ProviderQueryMappingRepository repository = new ThrowingProviderQueryMappingRepository();
        DefaultProviderQueryTargetResolver resolver = new DefaultProviderQueryTargetResolver(repository);

        assertThatThrownBy(() -> resolver.resolveAll(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mapping table unavailable");
    }

    private ProviderQueryMappingRepository repository(List<ProviderQueryMappingRecord> records) {
        return new ProviderQueryMappingRepository() {
            @Override
            public List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
                    String serviceName,
                    String alertClusterName
            ) {
                assertThat(serviceName).isEqualTo("demo-service");
                assertThat(alertClusterName).isEqualTo("cluster2");
                return records.stream().filter(ProviderQueryMappingRecord::enabled).toList();
            }

            @Override
            public List<ProviderQueryMappingRecord> findAll(String serviceName, String alertClusterName, String providerName) {
                return records;
            }

            @Override
            public Optional<ProviderQueryMappingRecord> findById(long id) {
                return Optional.empty();
            }

            @Override
            public ProviderQueryMappingRecord create(ProviderQueryMappingRecord record) {
                return record;
            }

            @Override
            public ProviderQueryMappingRecord update(ProviderQueryMappingRecord record) {
                return record;
            }

            @Override
            public boolean deleteById(long id) {
                return false;
            }
        };
    }

    private ProviderQueryMappingRecord record(String providerName, String queryClusterName, boolean enabled) {
        Instant now = Instant.parse("2026-06-15T00:00:00Z");
        return new ProviderQueryMappingRecord(
                1L,
                "demo-service",
                "cluster2",
                providerName,
                "demo-service",
                queryClusterName,
                enabled,
                null,
                "test",
                "test",
                now,
                now
        );
    }

    private static final class ThrowingProviderQueryMappingRepository implements ProviderQueryMappingRepository {

        @Override
        public List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
                String serviceName,
                String alertClusterName
        ) {
            throw new IllegalStateException("mapping table unavailable");
        }

        @Override
        public List<ProviderQueryMappingRecord> findAll(String serviceName, String alertClusterName, String providerName) {
            return List.of();
        }

        @Override
        public Optional<ProviderQueryMappingRecord> findById(long id) {
            return Optional.empty();
        }

        @Override
        public ProviderQueryMappingRecord create(ProviderQueryMappingRecord record) {
            return record;
        }

        @Override
        public ProviderQueryMappingRecord update(ProviderQueryMappingRecord record) {
            return record;
        }

        @Override
        public boolean deleteById(long id) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Run the resolver tests and verify they fail**

Run:

```bash
mvn -Dtest=DefaultProviderQueryTargetResolverTests test
```

Expected: FAIL during compilation because `ProviderQueryTargetResolver` and `DefaultProviderQueryTargetResolver` do not exist.

- [ ] **Step 3: Add the resolver interface**

Create `src/main/java/com/company/aiops/provider/query/ProviderQueryTargetResolver.java`:

```java
package com.company.aiops.provider.query;

import java.util.Set;

/**
 * Resolves provider-specific query targets for one alert identity.
 */
public interface ProviderQueryTargetResolver {

    ProviderQueryTargets resolveAll(String serviceName, String alertClusterName, Set<String> providerNames);
}
```

- [ ] **Step 4: Add the resolver implementation**

Create `src/main/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolver.java`:

```java
package com.company.aiops.provider.query;

import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves provider query targets from enabled database mappings.
 */
@Component
public class DefaultProviderQueryTargetResolver implements ProviderQueryTargetResolver {

    private final ProviderQueryMappingRepository providerQueryMappingRepository;

    public DefaultProviderQueryTargetResolver(ProviderQueryMappingRepository providerQueryMappingRepository) {
        this.providerQueryMappingRepository = providerQueryMappingRepository;
    }

    @Override
    public ProviderQueryTargets resolveAll(
            String serviceName,
            String alertClusterName,
            Set<String> providerNames
    ) {
        Map<String, ProviderQueryTarget> targets = new LinkedHashMap<>();
        for (ProviderQueryMappingRecord record : providerQueryMappingRepository.findEnabledByServiceAndAlertCluster(
                serviceName,
                alertClusterName
        )) {
            if (!providerNames.contains(record.providerName())) {
                continue;
            }
            targets.putIfAbsent(
                    record.providerName(),
                    ProviderQueryTarget.mapped(
                            record.providerName(),
                            record.queryServiceName(),
                            record.queryClusterName()
                    )
            );
        }
        return new ProviderQueryTargets(serviceName, alertClusterName, targets);
    }
}
```

- [ ] **Step 5: Run resolver and model tests**

Run:

```bash
mvn -Dtest=ProviderQueryTargetsTests,DefaultProviderQueryTargetResolverTests test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add src/main/java/com/company/aiops/provider/query/ProviderQueryTargetResolver.java \
        src/main/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolver.java \
        src/test/java/com/company/aiops/provider/query/DefaultProviderQueryTargetResolverTests.java
git commit -m "feat: resolve provider query targets"
```

---

### Task 4: Orchestrator Provider Query Target Wiring

**Files:**
- Modify: `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`
- Modify: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`

- [ ] **Step 1: Add failing orchestrator tests for mapped query targets**

Modify `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`:

1. Add imports:

```java
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.ProviderQueryTarget;
import com.company.aiops.provider.query.ProviderQueryTargetResolver;
import com.company.aiops.provider.query.ProviderQueryTargets;

import java.util.Map;
import java.util.Set;
```

2. Add this test method:

```java
@Test
void collectUsesProviderSpecificQueryTargetsForServiceLevelProviders() {
    CapturingBaselineProvider baselineProvider = new CapturingBaselineProvider();
    CapturingContainerMetricProvider containerMetricProvider = new CapturingContainerMetricProvider();
    CapturingCseMetricProvider cseMetricProvider = new CapturingCseMetricProvider();
    CapturingResourceMappingProvider resourceMappingProvider = new CapturingResourceMappingProvider();
    ProviderQueryTargetResolver resolver = (serviceName, alertClusterName, providerNames) -> new ProviderQueryTargets(
            serviceName,
            alertClusterName,
            Map.of(
                    ProviderNames.BASELINE,
                    ProviderQueryTarget.mapped(ProviderNames.BASELINE, "demo-service-baseline", "cluster2_base"),
                    ProviderNames.METRIC_CONTAINER,
                    ProviderQueryTarget.mapped(ProviderNames.METRIC_CONTAINER, "demo-service-container", "cluster2_front"),
                    ProviderNames.METRIC_CSE,
                    ProviderQueryTarget.mapped(ProviderNames.METRIC_CSE, "demo-service-cse", "cluster2_rpc")
            )
    );
    DefaultProviderOrchestrator orchestrator = orchestrator(
            properties(true),
            new InMemoryProviderCallLogRepository(),
            resolver,
            resourceMappingProvider,
            baselineProvider,
            (serviceName, interfaceName, window, limit) -> List.of(),
            traceId -> List.of(),
            (traceId, serviceName, window, limit) -> List.of(),
            containerMetricProvider,
            cseMetricProvider,
            new StableDatabaseMetricProvider(),
            new StableDcsMetricProvider(),
            new StableMqMetricProvider()
    );

    orchestrator.collect("task-query-targets", context(AlertCategory.RPC_ERROR_RATE));

    assertThat(baselineProvider.serviceName).isEqualTo("demo-service-baseline");
    assertThat(baselineProvider.cluster).isEqualTo("cluster2_base");
    assertThat(containerMetricProvider.serviceName).isEqualTo("demo-service-container");
    assertThat(containerMetricProvider.cluster).isEqualTo("cluster2_front");
    assertThat(cseMetricProvider.serviceName).isEqualTo("demo-service-cse");
    assertThat(cseMetricProvider.cluster).isEqualTo("cluster2_rpc");
    assertThat(resourceMappingProvider.cluster).isEqualTo("cluster1");
}
```

3. Add this test method:

```java
@Test
void collectContinuesWithAlertClusterWhenQueryMappingResolutionFails() {
    InMemoryProviderCallLogRepository callLogRepository = new InMemoryProviderCallLogRepository();
    CapturingContainerMetricProvider containerMetricProvider = new CapturingContainerMetricProvider();
    ProviderQueryTargetResolver resolver = (serviceName, alertClusterName, providerNames) -> {
        throw new IllegalStateException("mapping table unavailable");
    };
    DefaultProviderOrchestrator orchestrator = orchestrator(
            properties(true),
            callLogRepository,
            resolver,
            (serviceName, cluster) -> ResourceMapping.empty(serviceName, cluster),
            (serviceName, interfaceName, cluster, baselineWindow) -> InterfaceBaseline.empty(),
            (serviceName, interfaceName, window, limit) -> List.of(),
            traceId -> List.of(),
            (traceId, serviceName, window, limit) -> List.of(),
            containerMetricProvider,
            new StableCseMetricProvider(),
            new StableDatabaseMetricProvider(),
            new StableDcsMetricProvider(),
            new StableMqMetricProvider()
    );

    ProviderCollectionResult result = orchestrator.collect("task-query-mapping-fail", context(AlertCategory.RPC_ERROR_RATE));

    assertThat(result.ruleFallbackRequired()).isFalse();
    assertThat(result.degradedByProviderFailure()).isTrue();
    assertThat(containerMetricProvider.cluster).isEqualTo("cluster1");
    assertThat(result.missingEvidence())
            .extracting("providerName")
            .contains(ProviderNames.PROVIDER_QUERY_MAPPING);
    assertThat(callLogRepository.logs)
            .extracting(ProviderCallLog::providerName)
            .contains(ProviderNames.PROVIDER_QUERY_MAPPING, ProviderNames.METRIC_CONTAINER);
}
```

4. Change the helper constructor signature to include resolver before resource mapping:

```java
private DefaultProviderOrchestrator orchestrator(
        AiopsProperties properties,
        ProviderCallLogRepository providerCallLogRepository,
        ProviderQueryTargetResolver providerQueryTargetResolver,
        ResourceMappingProvider resourceMappingProvider,
        BaselineProvider baselineProvider,
        InterfaceLogProvider interfaceLogProvider,
        TraceProvider traceProvider,
        RuntimeLogProvider runtimeLogProvider,
        ContainerMetricProvider containerMetricProvider,
        CseMetricProvider cseMetricProvider,
        DatabaseMetricProvider databaseMetricProvider,
        DcsMetricProvider dcsMetricProvider,
        MqMetricProvider mqMetricProvider
) {
    providerExecutor = new ThreadPoolTaskExecutor();
    providerExecutor.setCorePoolSize(8);
    providerExecutor.setMaxPoolSize(8);
    providerExecutor.setQueueCapacity(32);
    providerExecutor.setThreadNamePrefix("test-provider-");
    providerExecutor.initialize();
    providerOrchestratorExecutor = new ThreadPoolTaskExecutor();
    providerOrchestratorExecutor.setCorePoolSize(8);
    providerOrchestratorExecutor.setMaxPoolSize(8);
    providerOrchestratorExecutor.setQueueCapacity(32);
    providerOrchestratorExecutor.setThreadNamePrefix("test-provider-orchestrator-");
    providerOrchestratorExecutor.initialize();
    ProviderCallExecutor providerCallExecutor = new ProviderCallExecutor(properties, providerCallLogRepository, providerExecutor);
    return new DefaultProviderOrchestrator(
            properties,
            providerCallExecutor,
            providerQueryTargetResolver,
            resourceMappingProvider,
            baselineProvider,
            interfaceLogProvider,
            traceProvider,
            runtimeLogProvider,
            containerMetricProvider,
            cseMetricProvider,
            databaseMetricProvider,
            dcsMetricProvider,
            mqMetricProvider,
            providerOrchestratorExecutor
    );
}
```

5. Update existing test calls to pass this resolver argument:

```java
fallbackResolver()
```

6. Add helper classes and methods at the bottom of the test class:

```java
private ProviderQueryTargetResolver fallbackResolver() {
    return (serviceName, alertClusterName, providerNames) ->
            ProviderQueryTargets.fallback(serviceName, alertClusterName, providerNames);
}

private static final class CapturingResourceMappingProvider implements ResourceMappingProvider {

    private String serviceName;
    private String cluster;

    @Override
    public ResourceMapping queryResourceMapping(String serviceName, String clusterName) {
        this.serviceName = serviceName;
        this.cluster = clusterName;
        return ResourceMapping.empty(serviceName, clusterName);
    }
}

private static final class CapturingBaselineProvider implements BaselineProvider {

    private String serviceName;
    private String cluster;

    @Override
    public InterfaceBaseline getInterfaceBaseline(
            String serviceName,
            String interfaceName,
            String cluster,
            TimeWindow baselineWindow
    ) {
        this.serviceName = serviceName;
        this.cluster = cluster;
        return InterfaceBaseline.empty();
    }
}

private static final class CapturingContainerMetricProvider implements ContainerMetricProvider {

    private String serviceName;
    private String cluster;

    @Override
    public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
        this.serviceName = serviceName;
        this.cluster = cluster;
        return ContainerMetrics.empty();
    }
}

private static final class CapturingCseMetricProvider implements CseMetricProvider {

    private String serviceName;
    private String cluster;

    @Override
    public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
        this.serviceName = serviceName;
        this.cluster = cluster;
        return CseMetrics.empty();
    }
}
```

- [ ] **Step 2: Run orchestrator tests and verify they fail**

Run:

```bash
mvn -Dtest=DefaultProviderOrchestratorTests test
```

Expected: FAIL during compilation because `DefaultProviderOrchestrator` does not accept `ProviderQueryTargetResolver`.

- [ ] **Step 3: Update orchestrator imports and constructor**

In `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`:

1. Add imports:

```java
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.ProviderQueryTarget;
import com.company.aiops.provider.query.ProviderQueryTargetResolver;
import com.company.aiops.provider.query.ProviderQueryTargets;

import java.util.Set;
```

2. Replace the private provider-name string constants with `ProviderNames` usage. For minimal churn, keep private aliases:

```java
private static final String PROVIDER_RESOURCE_MAPPING = ProviderNames.RESOURCE_MAPPING;
private static final String PROVIDER_BASELINE = ProviderNames.BASELINE;
private static final String PROVIDER_INTERFACE_LOG = ProviderNames.INTERFACE_LOG;
private static final String PROVIDER_TRACE = ProviderNames.TRACE;
private static final String PROVIDER_RUNTIME_LOG = ProviderNames.RUNTIME_LOG;
private static final String PROVIDER_METRIC_CONTAINER = ProviderNames.METRIC_CONTAINER;
private static final String PROVIDER_METRIC_CSE = ProviderNames.METRIC_CSE;
private static final String PROVIDER_METRIC_DATABASE = ProviderNames.METRIC_DATABASE;
private static final String PROVIDER_METRIC_DCS = ProviderNames.METRIC_DCS;
private static final String PROVIDER_METRIC_MQ = ProviderNames.METRIC_MQ;
private static final String PROVIDER_QUERY_MAPPING = ProviderNames.PROVIDER_QUERY_MAPPING;
private static final Set<String> QUERY_TARGET_PROVIDER_NAMES = ProviderNames.queryMappableProviderNames();
```

3. Add a field:

```java
private final ProviderQueryTargetResolver providerQueryTargetResolver;
```

4. Add the constructor parameter immediately after `ProviderCallExecutor providerCallExecutor` and assign it:

```java
ProviderQueryTargetResolver providerQueryTargetResolver,
```

```java
this.providerQueryTargetResolver = providerQueryTargetResolver;
```

- [ ] **Step 4: Resolve query targets at the start of collect**

Near the beginning of `collect`, before scheduling baseline/container/CSE calls, add:

```java
ProviderResult<ProviderQueryTargets> providerQueryTargetsResult = execute(
        taskId,
        initialContext,
        PROVIDER_QUERY_MAPPING,
        false,
        () -> providerQueryTargetResolver.resolveAll(
                initialContext.alert().serviceName(),
                initialContext.alert().cluster(),
                QUERY_TARGET_PROVIDER_NAMES
        )
);
ProviderQueryTargets providerQueryTargets = providerQueryTargetsResult.isUsable()
        && providerQueryTargetsResult.data() != null
        ? providerQueryTargetsResult.data()
        : ProviderQueryTargets.fallback(
                initialContext.alert().serviceName(),
                initialContext.alert().cluster(),
                QUERY_TARGET_PROVIDER_NAMES
        );
ProviderQueryTarget baselineTarget = providerQueryTargets.targetFor(PROVIDER_BASELINE);
ProviderQueryTarget containerTarget = providerQueryTargets.targetFor(PROVIDER_METRIC_CONTAINER);
ProviderQueryTarget cseTarget = providerQueryTargets.targetFor(PROVIDER_METRIC_CSE);
```

- [ ] **Step 5: Use targets for service-level provider calls**

In the baseline call, replace:

```java
initialContext.alert().serviceName(),
initialContext.alert().interfaceName(),
initialContext.alert().cluster(),
```

with:

```java
baselineTarget.serviceName(),
initialContext.alert().interfaceName(),
baselineTarget.clusterName(),
```

In the container metric call, replace:

```java
initialContext.alert().serviceName(),
initialContext.alert().cluster(),
```

with:

```java
containerTarget.serviceName(),
containerTarget.clusterName(),
```

In the CSE metric call, replace:

```java
initialContext.alert().serviceName(),
initialContext.alert().cluster(),
```

with:

```java
cseTarget.serviceName(),
cseTarget.clusterName(),
```

Do not change `ResourceMappingProvider`; it must keep using alert values.

- [ ] **Step 6: Include query mapping result in missing evidence without forcing fallback**

Add `providerQueryTargetsResult` to the `providerResults` list before `resourceMappingResult`:

```java
List<ProviderResult<?>> providerResults = List.of(
        providerQueryTargetsResult,
        resourceMappingResult,
        baselineResult,
        interfaceLogResult,
        containerMetricResult,
        cseMetricResult,
        databaseMetricResult,
        dcsMetricResult,
        mqMetricResult,
        traceResult,
        runtimeLogResult
);
```

Change the `nonCriticalFailure` stream to ignore query mapping failures:

```java
boolean nonCriticalFailure = providerResults.stream()
        .filter(result -> !Objects.equals(result.providerName(), PROVIDER_INTERFACE_LOG))
        .filter(result -> !Objects.equals(result.providerName(), PROVIDER_QUERY_MAPPING))
        .anyMatch(this::hasMissingEvidence);
```

Add `impactFor` case:

```java
case PROVIDER_QUERY_MAPPING ->
        "Provider-specific query target mapping is unavailable; provider calls used alert service and cluster values.";
```

- [ ] **Step 7: Run orchestrator tests**

Run:

```bash
mvn -Dtest=DefaultProviderOrchestratorTests test
```

Expected: PASS.

- [ ] **Step 8: Run resolver and orchestrator tests together**

Run:

```bash
mvn -Dtest=ProviderQueryTargetsTests,DefaultProviderQueryTargetResolverTests,DefaultProviderOrchestratorTests test
```

Expected: PASS.

- [ ] **Step 9: Commit Task 4**

```bash
git add src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java \
        src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java
git commit -m "feat: apply provider query targets in orchestration"
```

---

### Task 5: Provider Query Mapping CRUD Service and Controller

**Files:**
- Create: `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemUpsertRequest.java`
- Create: `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemResponse.java`
- Create: `src/main/java/com/company/aiops/provider/query/mapping/service/ProviderQueryMappingService.java`
- Create: `src/main/java/com/company/aiops/provider/query/mapping/controller/ProviderQueryMappingController.java`
- Test: `src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerTests.java`

- [ ] **Step 1: Write failing controller tests**

Create `src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerTests.java`:

```java
package com.company.aiops.provider.query.mapping;

import com.company.aiops.common.exception.GlobalExceptionHandler;
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.mapping.controller.ProviderQueryMappingController;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.service.ProviderQueryMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProviderQueryMappingController.class)
@Import(GlobalExceptionHandler.class)
class ProviderQueryMappingControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderQueryMappingService providerQueryMappingService;

    @Test
    void listReturnsMappingItems() throws Exception {
        when(providerQueryMappingService.list("demo-service", "cluster2", ProviderNames.METRIC_CONTAINER))
                .thenReturn(List.of(itemResponse(1L, ProviderNames.METRIC_CONTAINER, "cluster2_front")));

        mockMvc.perform(get("/api/v1/provider-query-mappings")
                        .param("serviceName", "demo-service")
                        .param("alertClusterName", "cluster2")
                        .param("providerName", ProviderNames.METRIC_CONTAINER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].providerName").value(ProviderNames.METRIC_CONTAINER))
                .andExpect(jsonPath("$.data[0].queryClusterName").value("cluster2_front"));

        verify(providerQueryMappingService).list("demo-service", "cluster2", ProviderNames.METRIC_CONTAINER);
    }

    @Test
    void createReturnsCreatedItem() throws Exception {
        when(providerQueryMappingService.create(eq(request())))
                .thenReturn(itemResponse(11L, ProviderNames.METRIC_CONTAINER, "cluster2_front"));

        mockMvc.perform(post("/api/v1/provider-query-mappings/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "demo-service",
                                  "alertClusterName": "cluster2",
                                  "providerName": "metric.container",
                                  "queryServiceName": "demo-service",
                                  "queryClusterName": "cluster2_front",
                                  "metadataJson": "{\\"owner\\":\\"ops\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.queryClusterName").value("cluster2_front"));
    }

    @Test
    void updateDelegatesToService() throws Exception {
        when(providerQueryMappingService.update(eq(5L), eq(request())))
                .thenReturn(itemResponse(5L, ProviderNames.METRIC_CONTAINER, "cluster2_front"));

        mockMvc.perform(put("/api/v1/provider-query-mappings/items/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "demo-service",
                                  "alertClusterName": "cluster2",
                                  "providerName": "metric.container",
                                  "queryServiceName": "demo-service",
                                  "queryClusterName": "cluster2_front",
                                  "metadataJson": "{\\"owner\\":\\"ops\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void deleteDelegatesToService() throws Exception {
        mockMvc.perform(delete("/api/v1/provider-query-mappings/items/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(providerQueryMappingService).delete(7L);
    }

    @Test
    void createRejectsBlankServiceName() throws Exception {
        mockMvc.perform(post("/api/v1/provider-query-mappings/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "",
                                  "alertClusterName": "cluster2",
                                  "providerName": "metric.container",
                                  "queryServiceName": "demo-service",
                                  "queryClusterName": "cluster2_front"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ProviderQueryMappingItemUpsertRequest request() {
        return new ProviderQueryMappingItemUpsertRequest(
                "demo-service",
                "cluster2",
                ProviderNames.METRIC_CONTAINER,
                "demo-service",
                "cluster2_front",
                "{\"owner\":\"ops\"}"
        );
    }

    private ProviderQueryMappingItemResponse itemResponse(long id, String providerName, String queryClusterName) {
        Instant now = Instant.parse("2026-06-15T13:00:00Z");
        return new ProviderQueryMappingItemResponse(
                id,
                "demo-service",
                "cluster2",
                providerName,
                "demo-service",
                queryClusterName,
                "{\"owner\":\"ops\"}",
                true,
                "provider-query-mapping-api",
                "provider-query-mapping-api",
                now,
                now
        );
    }
}
```

- [ ] **Step 2: Run controller tests and verify they fail**

Run:

```bash
mvn -Dtest=ProviderQueryMappingControllerTests test
```

Expected: FAIL during compilation because the controller, service, and API models do not exist.

- [ ] **Step 3: Add request and response models**

Create `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemUpsertRequest.java`:

```java
package com.company.aiops.provider.query.mapping.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProviderQueryMappingItemUpsertRequest(
        @NotBlank @Size(max = 256) String serviceName,
        @NotBlank @Size(max = 128) String alertClusterName,
        @NotBlank @Size(max = 128) String providerName,
        @NotBlank @Size(max = 256) String queryServiceName,
        @NotBlank @Size(max = 128) String queryClusterName,
        String metadataJson
) {
}
```

Create `src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemResponse.java`:

```java
package com.company.aiops.provider.query.mapping.model;

import java.time.Instant;

public record ProviderQueryMappingItemResponse(
        long id,
        String serviceName,
        String alertClusterName,
        String providerName,
        String queryServiceName,
        String queryClusterName,
        String metadataJson,
        boolean enabled,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
```

- [ ] **Step 4: Add the service**

Create `src/main/java/com/company/aiops/provider/query/mapping/service/ProviderQueryMappingService.java`:

```java
package com.company.aiops.provider.query.mapping.service;

import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
public class ProviderQueryMappingService {

    private static final String UPDATED_BY = "provider-query-mapping-api";

    private final ProviderQueryMappingRepository providerQueryMappingRepository;
    private final ObjectMapper objectMapper;

    public ProviderQueryMappingService(
            ProviderQueryMappingRepository providerQueryMappingRepository,
            ObjectMapper objectMapper
    ) {
        this.providerQueryMappingRepository = providerQueryMappingRepository;
        this.objectMapper = objectMapper;
    }

    public List<ProviderQueryMappingItemResponse> list(
            String serviceName,
            String alertClusterName,
            String providerName
    ) {
        return providerQueryMappingRepository.findAll(
                        normalizeFilter(serviceName),
                        normalizeFilter(alertClusterName),
                        normalizeProviderFilter(providerName)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ProviderQueryMappingItemResponse create(ProviderQueryMappingItemUpsertRequest request) {
        ProviderQueryMappingRecord draft = buildNewRecord(request);
        try {
            return toResponse(providerQueryMappingRepository.create(draft));
        } catch (DuplicateKeyException exception) {
            throw duplicate(request, exception);
        }
    }

    public ProviderQueryMappingItemResponse update(long id, ProviderQueryMappingItemUpsertRequest request) {
        ProviderQueryMappingRecord existing = providerQueryMappingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        ProviderQueryMappingRecord updated = new ProviderQueryMappingRecord(
                existing.id(),
                normalizeRequired(request.serviceName()),
                normalizeRequired(request.alertClusterName()),
                normalizeProviderName(request.providerName()),
                normalizeRequired(request.queryServiceName()),
                normalizeRequired(request.queryClusterName()),
                true,
                normalizeMetadataJson(request.metadataJson()),
                existing.createdBy(),
                UPDATED_BY,
                existing.createdAt(),
                Instant.now()
        );
        try {
            return toResponse(providerQueryMappingRepository.update(updated));
        } catch (DuplicateKeyException exception) {
            throw duplicate(request, exception);
        }
    }

    public void delete(long id) {
        providerQueryMappingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        providerQueryMappingRepository.deleteById(id);
    }

    private ProviderQueryMappingRecord buildNewRecord(ProviderQueryMappingItemUpsertRequest request) {
        return new ProviderQueryMappingRecord(
                null,
                normalizeRequired(request.serviceName()),
                normalizeRequired(request.alertClusterName()),
                normalizeProviderName(request.providerName()),
                normalizeRequired(request.queryServiceName()),
                normalizeRequired(request.queryClusterName()),
                true,
                normalizeMetadataJson(request.metadataJson()),
                UPDATED_BY,
                UPDATED_BY,
                null,
                null
        );
    }

    private ProviderQueryMappingItemResponse toResponse(ProviderQueryMappingRecord record) {
        return new ProviderQueryMappingItemResponse(
                record.id(),
                record.serviceName(),
                record.alertClusterName(),
                record.providerName(),
                record.queryServiceName(),
                record.queryClusterName(),
                record.metadataJson(),
                record.enabled(),
                record.createdBy(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private String normalizeFilter(String value) {
        return normalizeOptional(value);
    }

    private String normalizeProviderFilter(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalizeProviderName(normalized);
    }

    private String normalizeRequired(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "field must not be blank");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeProviderName(String value) {
        String normalized = normalizeRequired(value);
        if (!ProviderNames.isKnown(normalized) || ProviderNames.PROVIDER_QUERY_MAPPING.equals(normalized)) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "providerName is not supported for query mapping: " + normalized);
        }
        return normalized;
    }

    private String normalizeMetadataJson(String metadataJson) {
        String normalized = normalizeOptional(metadataJson);
        if (normalized == null) {
            return null;
        }
        try {
            objectMapper.readTree(normalized);
        } catch (IOException exception) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "metadataJson must be valid JSON");
        }
        return normalized;
    }

    private AiopsException notFound(long id) {
        return new AiopsException(ErrorCode.NOT_FOUND, "Provider query mapping item not found: id=" + id);
    }

    private AiopsException duplicate(ProviderQueryMappingItemUpsertRequest request, DuplicateKeyException exception) {
        return new AiopsException(
                ErrorCode.BAD_REQUEST,
                "Provider query mapping already exists for serviceName=%s, alertClusterName=%s, providerName=%s"
                        .formatted(
                                normalizeRequired(request.serviceName()),
                                normalizeRequired(request.alertClusterName()),
                                normalizeProviderName(request.providerName())
                        )
        );
    }
}
```

- [ ] **Step 5: Add the controller**

Create `src/main/java/com/company/aiops/provider/query/mapping/controller/ProviderQueryMappingController.java`:

```java
package com.company.aiops.provider.query.mapping.controller;

import com.company.aiops.common.api.ApiResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.service.ProviderQueryMappingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/provider-query-mappings")
public class ProviderQueryMappingController {

    private final ProviderQueryMappingService providerQueryMappingService;

    public ProviderQueryMappingController(ProviderQueryMappingService providerQueryMappingService) {
        this.providerQueryMappingService = providerQueryMappingService;
    }

    @GetMapping
    public ApiResponse<List<ProviderQueryMappingItemResponse>> list(
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String alertClusterName,
            @RequestParam(required = false) String providerName
    ) {
        return ApiResponse.ok(providerQueryMappingService.list(serviceName, alertClusterName, providerName));
    }

    @PostMapping("/items")
    public ApiResponse<ProviderQueryMappingItemResponse> create(
            @Valid @RequestBody ProviderQueryMappingItemUpsertRequest request
    ) {
        return ApiResponse.ok(providerQueryMappingService.create(request));
    }

    @PutMapping("/items/{id}")
    public ApiResponse<ProviderQueryMappingItemResponse> update(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody ProviderQueryMappingItemUpsertRequest request
    ) {
        return ApiResponse.ok(providerQueryMappingService.update(id, request));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) long id) {
        providerQueryMappingService.delete(id);
        return ApiResponse.ok(null);
    }
}
```

- [ ] **Step 6: Run controller tests**

Run:

```bash
mvn -Dtest=ProviderQueryMappingControllerTests test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 5**

```bash
git add src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemUpsertRequest.java \
        src/main/java/com/company/aiops/provider/query/mapping/model/ProviderQueryMappingItemResponse.java \
        src/main/java/com/company/aiops/provider/query/mapping/service/ProviderQueryMappingService.java \
        src/main/java/com/company/aiops/provider/query/mapping/controller/ProviderQueryMappingController.java \
        src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerTests.java
git commit -m "feat: add provider query mapping api"
```

---

### Task 6: Database-Backed API and Repository Verification

**Files:**
- Create: `src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerIntegrationTests.java`
- Modify: `src/test/java/com/company/aiops/support/AbstractAiopsIntegrationTestSupport.java`

- [ ] **Step 1: Add integration tests**

Create `src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerIntegrationTests.java`:

```java
package com.company.aiops.provider.query.mapping;

import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.ProviderQueryTargetResolver;
import com.company.aiops.provider.query.ProviderQueryTargets;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderQueryMappingControllerIntegrationTests {

    private static final String SERVICE_NAME = "it-provider-query-service";
    private static final String ALERT_CLUSTER_NAME = "cluster-it";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProviderQueryTargetResolver providerQueryTargetResolver;

    @BeforeEach
    void cleanProviderQueryMappings() {
        jdbcTemplate.update("DELETE FROM aiops_provider_query_mapping WHERE service_name = ?", SERVICE_NAME);
    }

    @Test
    void createListUpdateAndDeleteRoundTrip() {
        ResponseEntity<JsonNode> createResponse = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{\\"env\\":\\"test\\",\\"owner\\":\\"ops\\"}"
                        }
                        """
        );

        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        long id = createResponse.getBody().path("data").path("id").asLong();
        assertThat(createResponse.getBody().path("data").path("providerName").asText())
                .isEqualTo(ProviderNames.METRIC_CONTAINER);
        assertThat(createResponse.getBody().path("data").path("queryClusterName").asText())
                .isEqualTo("cluster-it-front");

        ResponseEntity<JsonNode> listResponse = restTemplate.getForEntity(
                "/api/v1/provider-query-mappings?serviceName={serviceName}&alertClusterName={alertClusterName}&providerName={providerName}",
                JsonNode.class,
                SERVICE_NAME,
                ALERT_CLUSTER_NAME,
                ProviderNames.METRIC_CONTAINER
        );
        assertThat(listResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listResponse.getBody().path("data").get(0).path("id").asLong()).isEqualTo(id);

        ResponseEntity<JsonNode> updateResponse = exchange(
                HttpMethod.PUT,
                "/api/v1/provider-query-mappings/items/" + id,
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.cse",
                          "queryServiceName": "it-provider-query-service-rpc",
                          "queryClusterName": "cluster-it-rpc",
                          "metadataJson": "{\\"env\\":\\"test\\",\\"owner\\":\\"rpc\\"}"
                        }
                        """
        );
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(updateResponse.getBody().path("data").path("providerName").asText())
                .isEqualTo(ProviderNames.METRIC_CSE);
        assertThat(updateResponse.getBody().path("data").path("queryClusterName").asText())
                .isEqualTo("cluster-it-rpc");

        ResponseEntity<JsonNode> deleteResponse = restTemplate.exchange(
                "/api/v1/provider-query-mappings/items/{id}",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                JsonNode.class,
                id
        );
        assertThat(deleteResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiops_provider_query_mapping WHERE id = ?",
                Integer.class,
                id
        );
        assertThat(count).isZero();
    }

    @Test
    void createRejectsInvalidProviderName() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.unknown",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front"
                        }
                        """
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void createRejectsInvalidMetadataJson() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{invalid-json}"
                        }
                        """
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void createRejectsDuplicateProviderMapping() {
        exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front"
                        }
                        """
        );

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front-2"
                        }
                        """
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void resolverIgnoresDisabledRows() {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_provider_query_mapping (
                            service_name, alert_cluster_name, provider_name,
                            query_service_name, query_cluster_name, enabled,
                            created_by, updated_by, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 0, 'test', 'test', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """,
                SERVICE_NAME,
                ALERT_CLUSTER_NAME,
                ProviderNames.METRIC_CONTAINER,
                SERVICE_NAME,
                "cluster-it-disabled"
        );

        ProviderQueryTargets targets = providerQueryTargetResolver.resolveAll(
                SERVICE_NAME,
                ALERT_CLUSTER_NAME,
                ProviderNames.queryMappableProviderNames()
        );

        assertThat(targets.targetFor(ProviderNames.METRIC_CONTAINER).clusterName())
                .isEqualTo(ALERT_CLUSTER_NAME);
        assertThat(targets.targetFor(ProviderNames.METRIC_CONTAINER).mapped()).isFalse();
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
```

- [ ] **Step 2: Run the integration tests and verify database migration coverage**

Run:

```bash
mvn -Dtest=ProviderQueryMappingControllerIntegrationTests test
```

Expected: PASS. This verifies Flyway creates `aiops_provider_query_mapping`, JDBC CRUD works, duplicate unique-key behavior is converted to `BAD_REQUEST`, and service validation rejects invalid provider names and metadata JSON.

- [ ] **Step 3: Add cleanup to shared integration support**

Modify `src/test/java/com/company/aiops/support/AbstractAiopsIntegrationTestSupport.java` in `cleanDatabase()` to delete provider query mappings before other task cleanup:

```java
jdbcTemplate.update("DELETE FROM aiops_provider_query_mapping");
```

Place it as the first statement in `cleanDatabase()` so future replay/smoke tests do not inherit query target rows across test methods.

- [ ] **Step 4: Run existing database-backed resource mapping tests**

Run:

```bash
mvn -Dtest=ResourceMappingControllerIntegrationTests,ApplicationSmokeTests test
```

Expected: PASS. This confirms the new table does not affect `aiops_resource_mapping` behavior or application startup.

- [ ] **Step 5: Commit Task 6**

```bash
git add src/test/java/com/company/aiops/provider/query/mapping/ProviderQueryMappingControllerIntegrationTests.java \
        src/test/java/com/company/aiops/support/AbstractAiopsIntegrationTestSupport.java
git commit -m "test: verify provider query mapping persistence"
```

---

### Task 7: Full Verification and Documentation Touch-Up

**Files:**
- Modify only if needed: `docs/superpowers/specs/2026-06-15-provider-query-mapping-design.md`

- [ ] **Step 1: Run all focused tests**

Run:

```bash
mvn -Dtest=ProviderQueryTargetsTests,DefaultProviderQueryTargetResolverTests,DefaultProviderOrchestratorTests,ProviderQueryMappingControllerTests,ProviderQueryMappingControllerIntegrationTests test
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 3: Check migration ordering**

Run:

```bash
Get-ChildItem src\main\resources\db\migration | Sort-Object Name
```

Expected output includes:

```text
V1__create_aiops_tables.sql
V2__seed_resource_mapping.sql
V3__create_provider_query_mapping.sql
```

- [ ] **Step 4: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intentional files modified or no output. The existing untracked PDF `Daumard_1990_Affaire_amour_affection_mariage_bourgeois_XIXe_OCR.pdf` may still appear; leave it untracked.

- [ ] **Step 5: Commit documentation adjustment only if one was made**

If the implementation revealed a needed correction in the design spec, commit only that document:

```bash
git add docs/superpowers/specs/2026-06-15-provider-query-mapping-design.md
git commit -m "docs: refine provider query mapping design"
```

If the design spec still matches the implementation, skip this commit.

---

## Self-Review Checklist

- Spec goal 1, preserving `AlertEvent.cluster`: covered by Task 4, where `ResourceMappingProvider` and alert context still use alert values.
- Spec goal 2, per-provider query names: covered by Tasks 1, 3, and 4.
- Spec goal 3, independent resource mapping: covered by Tasks 2, 4, and Task 6 smoke/resource mapping verification.
- Spec goal 4, fallback on missing mappings: covered by Task 1 model tests and Task 3 resolver tests.
- Spec goal 5, extensibility: covered by `ProviderNames`, `ProviderQueryTargetResolver`, and dedicated CRUD API.
- Data model and migration: covered by Task 2 and Task 6.
- Maintenance API: covered by Task 5 and Task 6.
- Mapping lookup failure handling: covered by Task 4 failure test.
- No frontend management page: plan adds API only.

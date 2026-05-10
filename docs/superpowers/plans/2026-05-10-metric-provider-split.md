# Metric Provider Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the aggregated `MetricProvider` contract into object-specific provider interfaces and migrate the orchestrator, mocks, and tests without changing runtime behavior.

**Architecture:** Keep `MetricEvidence`, provider names, orchestration stages, and degradation rules unchanged. Replace the single `MetricProvider` dependency with five focused interfaces so each metric source has one clear contract and one mock implementation.

**Tech Stack:** Java 21, Spring Boot 3.2, JUnit 5, AssertJ, Maven

---

### Task 1: Make The Tests Describe The New Dependency Shape

**Files:**
- Modify: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`
- Test: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`

- [ ] **Step 1: Write the failing test-side refactor**

Replace the single metric provider parameter in the test helper with five focused provider parameters, and split the in-file `StableMetricProvider` into five minimal test doubles.

```java
    private DefaultProviderOrchestrator orchestrator(
            AiopsProperties properties,
            ProviderCallLogRepository providerCallLogRepository,
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

```java
    private static final class StableContainerMetricProvider implements ContainerMetricProvider {
        @Override
        public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
            return ContainerMetrics.empty();
        }
    }

    private static final class StableCseMetricProvider implements CseMetricProvider {
        @Override
        public CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window) {
            return CseMetrics.empty();
        }
    }
```

```java
    private static final class StableDatabaseMetricProvider implements DatabaseMetricProvider {
        @Override
        public DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window) {
            return DatabaseMetrics.empty();
        }
    }

    private static final class StableDcsMetricProvider implements DcsMetricProvider {
        @Override
        public DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window) {
            return DcsMetrics.empty();
        }
    }

    private static final class StableMqMetricProvider implements MqMetricProvider {
        @Override
        public MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window) {
            return MqMetrics.empty();
        }
    }
```

- [ ] **Step 2: Run the targeted test to verify it fails**

Run: `mvn "-Dtest=DefaultProviderOrchestratorTests" test`

Expected: FAIL during compilation with missing symbols for `ContainerMetricProvider`, `CseMetricProvider`, `DatabaseMetricProvider`, `DcsMetricProvider`, `MqMetricProvider`, or constructor mismatch in `DefaultProviderOrchestrator`.

- [ ] **Step 3: Update the test call sites to use the focused test doubles**

Change each `orchestrator(...)` call that currently passes `new StableMetricProvider()` to pass all five focused doubles.

```java
        DefaultProviderOrchestrator orchestrator = orchestrator(
                properties(true),
                callLogRepository,
                resourceMappingProvider,
                baselineProvider,
                interfaceLogProvider,
                traceProvider,
                runtimeLogProvider,
                new StableContainerMetricProvider(),
                new StableCseMetricProvider(),
                new StableDatabaseMetricProvider(),
                new StableDcsMetricProvider(),
                new StableMqMetricProvider()
        );
```

- [ ] **Step 4: Run the targeted test again and keep it red**

Run: `mvn "-Dtest=DefaultProviderOrchestratorTests" test`

Expected: FAIL because production code still references `MetricProvider` and the new constructor signature/interfaces are not implemented yet.

- [ ] **Step 5: Commit the test-first change**

```bash
git add src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java
git commit -m "test: express split metric provider dependencies"
```

### Task 2: Introduce Focused Provider Interfaces And Split Mock Implementations

**Files:**
- Create: `src/main/java/com/company/aiops/provider/metric/ContainerMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/CseMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/DatabaseMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/DcsMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/MqMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/mock/MockContainerMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/mock/MockCseMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/mock/MockDatabaseMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/mock/MockDcsMetricProvider.java`
- Create: `src/main/java/com/company/aiops/provider/metric/mock/MockMqMetricProvider.java`
- Delete: `src/main/java/com/company/aiops/provider/metric/MetricProvider.java`
- Delete: `src/main/java/com/company/aiops/provider/metric/mock/MockMetricProvider.java`
- Test: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`

- [ ] **Step 1: Add the five focused provider interfaces**

Create the interfaces with one method each and keep the package `com.company.aiops.provider.metric`.

```java
package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.ContainerMetrics;

public interface ContainerMetricProvider {
    ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window);
}
```

```java
package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.CseMetrics;

public interface CseMetricProvider {
    CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window);
}
```

```java
package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.DatabaseMetrics;

import java.util.List;

public interface DatabaseMetricProvider {
    DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window);
}
```

```java
package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.DcsMetrics;

import java.util.List;

public interface DcsMetricProvider {
    DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window);
}
```

```java
package com.company.aiops.provider.metric;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.MqMetrics;

import java.util.List;

public interface MqMetricProvider {
    MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window);
}
```

- [ ] **Step 2: Run the targeted test to verify it still fails for the expected reason**

Run: `mvn "-Dtest=DefaultProviderOrchestratorTests" test`

Expected: FAIL because `DefaultProviderOrchestrator` and Spring mock classes still use `MetricProvider`.

- [ ] **Step 3: Split the mock implementation into five Spring components**

Port the exact sample values from `MockMetricProvider` into focused mock classes.

```java
@Component
public class MockContainerMetricProvider implements ContainerMetricProvider {

    @Override
    public ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window) {
        return new ContainerMetrics(
                0.42,
                0.61,
                0.56,
                0.72,
                0,
                0,
                3L,
                0L,
                List.of(
                        new ContainerMetrics.InstanceMetrics(serviceName + "-pod-1", 0.39, 0.53, false, false),
                        new ContainerMetrics.InstanceMetrics(serviceName + "-pod-2", 0.61, 0.72, false, false)
                )
        );
    }
}
```

```java
@Component
public class MockDatabaseMetricProvider implements DatabaseMetricProvider {

    @Override
    public DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window) {
        return new DatabaseMetrics(
                dbInstanceIds.stream()
                        .map(instanceId -> new DatabaseMetrics.InstanceMetrics(
                                instanceId, 0.45, 0.52, 0.40, 83L, 2L, 0.48, 12.0, 10.0, 22L, 4L, 0L, 1L
                        ))
                        .toList(),
                dbInstanceIds.stream()
                        .map(instanceId -> new DatabaseMetrics.SlowSqlSample(
                                instanceId,
                                "select * from activity where id = ?",
                                180L
                        ))
                        .toList()
        );
    }
}
```

Create equivalent focused classes for CSE, DCS, and MQ using the same numbers currently returned by `MockMetricProvider`.

- [ ] **Step 4: Delete the old aggregate interface and aggregate mock**

Remove:

```text
src/main/java/com/company/aiops/provider/metric/MetricProvider.java
src/main/java/com/company/aiops/provider/metric/mock/MockMetricProvider.java
```

- [ ] **Step 5: Run the targeted test and keep it red**

Run: `mvn "-Dtest=DefaultProviderOrchestratorTests" test`

Expected: FAIL because `DefaultProviderOrchestrator` still imports `MetricProvider` and has not been migrated to the focused interfaces.

- [ ] **Step 6: Commit the new interfaces and split mocks**

```bash
git add src/main/java/com/company/aiops/provider/metric src/main/java/com/company/aiops/provider/metric/mock
git commit -m "refactor: split metric provider interfaces"
```

### Task 3: Migrate The Orchestrator And Get The Tests Green

**Files:**
- Modify: `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`
- Modify: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`
- Test: `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`

- [ ] **Step 1: Update the orchestrator imports and fields**

Replace the single `MetricProvider` field with five focused provider fields.

```java
    private final ContainerMetricProvider containerMetricProvider;
    private final CseMetricProvider cseMetricProvider;
    private final DatabaseMetricProvider databaseMetricProvider;
    private final DcsMetricProvider dcsMetricProvider;
    private final MqMetricProvider mqMetricProvider;
```

Update the constructor accordingly.

```java
    public DefaultProviderOrchestrator(
            AiopsProperties aiopsProperties,
            ProviderCallExecutor providerCallExecutor,
            ResourceMappingProvider resourceMappingProvider,
            BaselineProvider baselineProvider,
            InterfaceLogProvider interfaceLogProvider,
            TraceProvider traceProvider,
            RuntimeLogProvider runtimeLogProvider,
            ContainerMetricProvider containerMetricProvider,
            CseMetricProvider cseMetricProvider,
            DatabaseMetricProvider databaseMetricProvider,
            DcsMetricProvider dcsMetricProvider,
            MqMetricProvider mqMetricProvider,
            @Qualifier("providerOrchestratorExecutor") Executor providerOrchestratorExecutor
    ) {
        this.containerMetricProvider = containerMetricProvider;
        this.cseMetricProvider = cseMetricProvider;
        this.databaseMetricProvider = databaseMetricProvider;
        this.dcsMetricProvider = dcsMetricProvider;
        this.mqMetricProvider = mqMetricProvider;
    }
```

- [ ] **Step 2: Update each orchestrator call site to use the focused providers**

Replace the old calls with the same behavior routed through the new fields.

```java
        CompletableFuture<ProviderResult<ContainerMetrics>> containerMetricFuture = schedule(() ->
                execute(taskId, initialContext, PROVIDER_METRIC_CONTAINER, false, () ->
                        containerMetricProvider.getContainerMetrics(
                                initialContext.alert().serviceName(),
                                initialContext.alert().cluster(),
                                initialContext.alarmWindow()
                        )));
```

```java
        CompletableFuture<ProviderResult<CseMetrics>> cseMetricFuture = schedule(() ->
                execute(taskId, initialContext, PROVIDER_METRIC_CSE, false, () ->
                        cseMetricProvider.getCseMetrics(
                                initialContext.alert().serviceName(),
                                initialContext.alert().cluster(),
                                initialContext.alarmWindow()
                        )));
```

```java
        CompletableFuture<ProviderResult<DatabaseMetrics>> databaseMetricFuture = schedule(() ->
                resourceMapping.resourceIds("DATABASE").isEmpty()
                        ? providerCallExecutor.skipped(taskId, context.alert().alertId(), PROVIDER_METRIC_DATABASE, false, "no DATABASE resource mapping resolved")
                        : execute(taskId, context, PROVIDER_METRIC_DATABASE, false, () ->
                                databaseMetricProvider.getDatabaseMetrics(resourceMapping.resourceIds("DATABASE"), context.alarmWindow())));
```

Mirror the same pattern for `dcsMetricProvider` and `mqMetricProvider`.

- [ ] **Step 3: Run the targeted test to verify it passes**

Run: `mvn "-Dtest=DefaultProviderOrchestratorTests" test`

Expected: PASS with all tests green and no remaining references to `MetricProvider`.

- [ ] **Step 4: Run a broader regression for the module**

Run: `mvn test`

Expected: PASS, confirming the split does not break other tests in the module.

- [ ] **Step 5: Commit the production migration**

```bash
git add src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java
git commit -m "refactor: migrate orchestrator to split metric providers"
```

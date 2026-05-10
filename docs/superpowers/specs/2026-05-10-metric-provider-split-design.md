# Metric Provider Split Design

## 背景

当前 [MetricProvider.java](C:\Users\李书昊\IdeaProjects\untitled\src\main\java\com\company\aiops\provider\metric\MetricProvider.java) 同时承担了容器、CSE、数据库、DCS、MQ 五类指标查询职责。虽然这些查询都属于“指标采集”，但它们的查询对象、入参形状和后续真实 HTTP 适配方式并不一致，继续堆在一个接口里会让 provider 边界越来越模糊。

设计文档 [aiops-alert-analysis-design.md](C:\Users\李书昊\IdeaProjects\untitled\docs\aiops-alert-analysis-design.md) 已经明确 `MetricEvidence` 需要分别承载容器、CSE、DB、DCS、MQ 的证据，本次重构顺着这个边界把单一 `MetricProvider` 拆成按查询对象划分的多个 provider。

## 目标

1. 将 `MetricProvider` 按查询对象拆成一职责 provider。
2. 让调用方显式依赖自己真正需要的 provider，而不是依赖一个大而全的指标接口。
3. 保持现有采集顺序、并发模型、降级策略和 `MetricEvidence` 输出结构不变。

## 非目标

1. 不改变 `MetricEvidence`、`ContainerMetrics`、`DatabaseMetrics`、`DcsMetrics`、`MqMetrics`、`CseMetrics` 的数据结构。
2. 不改变 provider 编排阶段、`providerName` 常量、降级判定和缺失证据文案。
3. 不引入新的 registry、strategy 或 facade 抽象。
4. 不在本次重构中接入真实 HTTP provider。

## 现状

当前 [DefaultProviderOrchestrator.java](C:\Users\李书昊\IdeaProjects\untitled\src\main\java\com\company\aiops\provider\orchestrator\DefaultProviderOrchestrator.java) 通过一个 `MetricProvider` 调用：

- `getContainerMetrics(serviceName, cluster, window)`
- `getCseMetrics(serviceName, cluster, window)`
- `getDatabaseMetrics(dbInstanceIds, window)`
- `getDcsMetrics(dcsInstanceIds, window)`
- `getMqMetrics(mqInstanceIds, window)`

这意味着：

1. provider 接口本身已经跨越了服务级指标和资源实例级指标两种不同粒度。
2. mock 实现 [MockMetricProvider.java](C:\Users\李书昊\IdeaProjects\untitled\src\main\java\com\company\aiops\provider\metric\mock\MockMetricProvider.java) 也在一个类中维护五类返回语义。
3. orchestrator 的依赖关系和调用代码是分开的，但接口边界仍然是聚合的，职责表达不够直接。

## 方案选择

本次采用“调用方直接依赖多个细分 provider”的方案，不保留聚合型 `MetricProvider` 外观。

备选方案对比：

1. 保留 facade，只在内部拆分 provider。
   这个方案可以减少调用方改动，但会保留新的“大接口入口”，后续仍然容易继续膨胀。
2. 引入按资源类型路由的 registry。
   这个方案扩展性更强，但对当前代码库属于过度抽象，会把一次简单职责拆分升级为模型重构。

## 设计决策

### 1. 接口拆分

删除现有 `MetricProvider`，新增以下五个接口，仍然保留在 `com.company.aiops.provider.metric` 包下：

- `ContainerMetricProvider`
- `CseMetricProvider`
- `DatabaseMetricProvider`
- `DcsMetricProvider`
- `MqMetricProvider`

接口职责如下：

1. `ContainerMetricProvider`
   只负责 `getContainerMetrics(String serviceName, String cluster, TimeWindow window)`。
2. `CseMetricProvider`
   只负责 `getCseMetrics(String serviceName, String cluster, TimeWindow window)`。
3. `DatabaseMetricProvider`
   只负责 `getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window)`。
4. `DcsMetricProvider`
   只负责 `getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window)`。
5. `MqMetricProvider`
   只负责 `getMqMetrics(List<String> mqInstanceIds, TimeWindow window)`。

这次不继续细分子包，原因是当前 `provider.metric` 下接口数量仍然很少，继续拆目录收益不高。等真实 HTTP 实现接入后，如果每类 provider 都出现独立适配器和配置，再评估是否按对象拆子包。

### 2. 调用方迁移

[DefaultProviderOrchestrator.java](C:\Users\李书昊\IdeaProjects\untitled\src\main\java\com\company\aiops\provider\orchestrator\DefaultProviderOrchestrator.java) 直接注入五个细分 provider：

- `ContainerMetricProvider`
- `CseMetricProvider`
- `DatabaseMetricProvider`
- `DcsMetricProvider`
- `MqMetricProvider`

迁移时只修改依赖注入和调用点，不调整以下内容：

1. `PROVIDER_METRIC_CONTAINER`、`PROVIDER_METRIC_CSE`、`PROVIDER_METRIC_DATABASE`、`PROVIDER_METRIC_DCS`、`PROVIDER_METRIC_MQ` 常量值。
2. 阶段 1/阶段 2 的并发调度顺序。
3. 无资源映射时返回 `skipped` 的行为。
4. `aggregateMetricResults(...)` 的聚合逻辑。
5. `missingEvidence`、`degradedReason` 和 `fallbackRequired` 的判定逻辑。

### 3. Mock 实现拆分

现有 [MockMetricProvider.java](C:\Users\李书昊\IdeaProjects\untitled\src\main\java\com\company\aiops\provider\metric\mock\MockMetricProvider.java) 拆成五个 mock provider：

- `MockContainerMetricProvider`
- `MockCseMetricProvider`
- `MockDatabaseMetricProvider`
- `MockDcsMetricProvider`
- `MockMqMetricProvider`

拆分原则：

1. 保持原有 mock 数据语义不变，避免因为重构让集成测试样本行为发生漂移。
2. 每个 mock 类只承载本对象类型的构造逻辑，不复用统一父类或工具类，除非出现明显重复。
3. Spring 装配保持自动发现，避免调用方额外加配置。

### 4. 测试迁移

更新 [DefaultProviderOrchestratorTests.java](C:\Users\李书昊\IdeaProjects\untitled\src\test\java\com\company\aiops\provider\orchestrator\DefaultProviderOrchestratorTests.java)，将单个 `StableMetricProvider` 测试替身拆成五个最小替身。

测试必须继续覆盖：

1. baseline、interface log、container metric 等独立 provider 的并发启动行为。
2. `runtimeLog` 失败时只降级、不触发 fallback。
3. `interfaceLog` 失败时触发 fallback。
4. `DATABASE`、`DCS`、`MQ` 无资源映射时的 `skipped` 行为仍正确。

如果现有测试只间接覆盖其中一部分，本次可以补齐与细分 provider 注入直接相关的断言，但不扩展到新的业务语义测试。

## 文件影响

预计涉及以下文件：

### 删除

- `src/main/java/com/company/aiops/provider/metric/MetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/mock/MockMetricProvider.java`

### 新增

- `src/main/java/com/company/aiops/provider/metric/ContainerMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/CseMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/DatabaseMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/DcsMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/MqMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/mock/MockContainerMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/mock/MockCseMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/mock/MockDatabaseMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/mock/MockDcsMetricProvider.java`
- `src/main/java/com/company/aiops/provider/metric/mock/MockMqMetricProvider.java`

### 修改

- `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`
- `src/test/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestratorTests.java`

## 风险与控制

### 风险 1：Spring 注入点遗漏

如果旧 `MetricProvider` 删除后仍有地方引用，会直接编译失败。这个风险是可接受的，因为它能在编译期暴露所有遗漏点。

控制方式：

1. 全量检索 `MetricProvider` 引用。
2. 编译并运行 provider orchestrator 相关测试。

### 风险 2：Mock 行为无意变化

拆成多个 mock 类时，最容易出现的回归不是接口错误，而是默认样本值偏差。

控制方式：

1. 沿用原 `MockMetricProvider` 的返回数值。
2. 以 orchestrator 现有测试为主做回归验证。

### 风险 3：重构范围外溢

如果同时调整 `MetricEvidence`、DTO、包结构或 provider 选择机制，会把这次改动从“职责拆分”升级成“架构改造”。

控制方式：

1. 明确本次不动 evidence 和 metrics DTO。
2. 不引入 facade、registry 或统一请求模型。

## 验收标准

1. 代码中不再存在 `MetricProvider` 聚合接口。
2. `DefaultProviderOrchestrator` 直接依赖五个细分 provider。
3. `MetricEvidence` 输出结构和降级行为与重构前一致。
4. mock provider 仍可支撑现有本地开发和测试场景。
5. orchestrator 相关测试通过，证明并发、降级和跳过逻辑未被破坏。

## 后续实现建议

实现阶段应优先把这次重构当成“无行为变化的结构调整”来做：

1. 先改测试替身和构造器签名，确保测试能表达新依赖关系。
2. 再拆接口和 mock 实现。
3. 最后清理旧接口和旧引用。

这样更容易在 TDD 流程下看到真实失败点，也方便定位重构引入的问题。

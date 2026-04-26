# AIOps Alert Analysis 风险修复验收记录

## 基本信息

- 日期：2026-04-26
- 范围：针对代码检视中发现的 2 个中风险问题和 1 个低风险问题进行修复
- 提交范围：仅包含本次风险修复和对应验收文档，不包含工作区内其他未提交改动

## 修复项

### 1. Provider 并行编排线程池修复

问题：
`DefaultProviderOrchestrator` 在开启并行采集时使用 `CompletableFuture.supplyAsync(...)` 默认 common pool 调度外层任务，和内部 `providerExecutor` 脱节，负载升高时容易出现额外线程竞争和调度抖动。

修复：

- 新增独立的 `providerOrchestratorExecutor`
- `DefaultProviderOrchestrator` 改为显式注入该执行器
- 并行编排改为 `CompletableFuture.supplyAsync(supplier, providerOrchestratorExecutor)`

涉及文件：

- `src/main/java/com/company/aiops/common/config/AsyncExecutionConfiguration.java`
- `src/main/java/com/company/aiops/provider/orchestrator/DefaultProviderOrchestrator.java`

### 2. 历史查询上限配置化

问题：
`GET /api/v1/analysis/history` 只有最小值校验，没有查询上限，存在把大 `limit` 直接下推到持久层的风险。

修复：

- 在 `AiopsProperties.Analysis` 中新增 `historyQueryMaxLimit`
- 在 `application.yml` 中提供默认值 `5`
- `AnalysisQueryService` 在查询前执行上限校验
- 超限时返回 `VALIDATION_FAILED`

涉及文件：

- `src/main/java/com/company/aiops/common/config/AiopsProperties.java`
- `src/main/java/com/company/aiops/analysis/query/service/AnalysisQueryService.java`
- `src/main/resources/application.yml`

新增配置：

```yaml
aiops:
  analysis:
    history-query-max-limit: 5
```

### 3. 集成测试数据清理补全

问题：
测试清库流程未覆盖 `aiops_evidence_bundle`，后续扩展相关断言时存在串数据风险。

修复：

- 在现有集成测试清理逻辑中补充 `DELETE FROM aiops_evidence_bundle`

涉及文件：

- `src/test/java/com/company/aiops/analysis/orchestrator/state/AnalysisJobStateMachineIntegrationTests.java`
- `src/test/java/com/company/aiops/analysis/query/AnalysisQueryControllerIntegrationTests.java`

## 测试与验证

新增或更新了以下验证：

- `DefaultProviderOrchestratorTests`：覆盖新的编排执行器注入方式
- `AnalysisQueryServiceTests`：新增历史查询超限校验测试
- `AnalysisQueryControllerIntegrationTests`：新增历史查询超限接口测试，并将正常场景查询上限调整到默认配置范围内
- 相关属性构造测试同步补齐 `historyQueryMaxLimit`

执行结果：

- 本地执行 `mvn test` 通过
- 测试结果：`Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`

验证环境：

- JDK 21
- Spring Boot 3.2.5
- MySQL `127.0.0.1:3307/aiops_alert_analysis`

## 验收结论

本次风险修复已完成，第二个中风险项已按要求做成配置项，默认上限为 `5`。代码和测试均已更新，并通过本地完整测试验证。

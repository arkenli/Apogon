# AIOps 告警自动分析系统开发日志
日期：2026-04-26

## 范围说明

本次只实现 `aiops-alert-analysis-development-plan.md` 中的阶段 9：查询接口。

约束执行口径：
1. 不实现真实内网 Provider，继续复用现有接口与 Mock。
2. 不修改与阶段 9 无关的业务模块。
3. 查询相关职责单独落在新的 `analysis.query` 分层中，不把查询拼装逻辑塞回 `alert.controller`、`analysis.orchestrator` 或现有写仓储。
4. 保持 JDK 21 兼容。

## 设计冲突说明

本次实现前确认到 1 个与阶段 9 直接相关的设计/实现冲突：

1. 设计文档与阶段 9 返回字段要求都包含 `historicalReference`，但当前实现里该字段只在 webhook 接收阶段临时返回，没有随最终 `AnalysisResult` 一起持久化，因此单靠 `aiops_analysis_result.result_json` 无法直接支撑查询接口返回。

最小修改建议：

1. 短期按本次实现方式处理：查询时基于任务的 `dedupeKey + createdAt` 动态回查历史结果并补齐 `historicalReference`，不改表结构。
2. 后续如需让查询结果与持久化结果完全同构，可在结果生成阶段将 `historicalReference` 一并写入 `aiops_analysis_result.result_json`。

## 本次完成内容

1. 新增阶段 9 独立查询分层：
   - `analysis.query.controller`
   - `analysis.query.service`
   - `analysis.query.model`
2. 实现查询接口：
   - `GET /api/v1/analysis/tasks/{taskId}`
   - `GET /api/v1/analysis/latest?alertId=...`
   - `GET /api/v1/analysis/history?serviceName=...&interfaceName=...&limit=...`
3. 新增 `AnalysisQueryService`：
   - 统一组装“任务元数据 + 最终结果详情”返回结构
   - 任务运行中时只返回状态元数据，不伪造最终结果
   - 当持久化结果中缺失 `historicalReference` 时，查询时按 dedupe 窗口动态补齐
4. 新增只读查询仓储：
   - `AnalysisQueryRepository`
   - `JdbcAnalysisQueryRepository`
5. `JdbcAnalysisQueryRepository` 支持以下查询能力：
   - 按 `taskId` 联表查询任务、告警维度和结果 JSON
   - 按 `alertId` 查询最新任务
   - 对 5 分钟内重复告警，若 `alertId` 本身没有任务，则通过 `aiops_duplicate_alert_link` 反查复用任务
   - 按 `serviceName + interfaceName` 查询历史完成结果，并按 `generated_at desc` 排序
6. 新增查询返回模型：
   - `AnalysisTaskQueryResponse`
   - `AnalysisResultView`
   - `AnalysisQueryProjection`
7. 更新 `analysis` 与 `persistence.repository` 的 `package-info.java`，补充阶段 9 职责边界说明。

## 本次包结构整理

根据“若当前任务涉及的目录已经出现职责混放，必须在本次提交中一并整理”的约束，本次将查询职责固定到独立目录：

```text
com.company.aiops.analysis
  query
    controller   # 阶段 9 HTTP 查询接口
    service      # 查询结果拼装、历史参考补齐
    model        # 查询返回模型与内部投影

com.company.aiops.persistence.repository
  AnalysisQueryRepository
  jdbc
    JdbcAnalysisQueryRepository
```

本次没有把查询 SQL、重复告警映射解析、`historicalReference` 补齐或返回结构组装继续堆回现有写仓储、worker 或 webhook 模块。

## 测试与验证

新增或补充测试：

1. `AnalysisQueryServiceTests`
   - 验证任务完成后会按 dedupe 时间窗动态补齐 `historicalReference`
   - 验证运行中任务不会错误触发历史参考回查
   - 验证查不到任务/告警时返回 `NOT_FOUND`
2. `AnalysisQueryControllerTests`
   - 验证阶段 9 三个接口的返回结构与参数校验
   - 验证 `limit < 1` 时返回 `VALIDATION_FAILED`
3. `AnalysisQueryControllerIntegrationTests`
   - 基于真实 JDBC 查询验证运行中任务查询
   - 验证完成结果查询时的 `historicalReference` 动态补齐
   - 验证重复告警 `latest` 会解析到被复用的原始任务
   - 验证 `history` 按 `generatedAt desc` 返回

验证情况：

1. 执行 `mvn -q -DskipTests test-compile`，通过。
2. 执行 `mvn -q "-Dtest=AnalysisQueryServiceTests,AnalysisQueryControllerTests,AnalysisQueryControllerIntegrationTests" test`，通过。
3. 按 `LOCAL_MYSQL.md` 启动本地 MySQL 后，数据库级查询集成测试通过。

## 本地环境记录

1. 本机 PowerShell 执行策略禁止直接运行 `C:\aiops-mysql\start-aiops-mysql.ps1`。
2. 本次使用以下方式启动本地测试库：

```powershell
powershell -ExecutionPolicy Bypass -File C:\aiops-mysql\start-aiops-mysql.ps1
```

3. 使用以下命令确认 MySQL 已启动：

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqladmin.exe' --defaults-file=C:\aiops-mysql\my.ini -uroot ping
```

期望输出：

```text
mysqld is alive
```

## 当前注意事项

1. 阶段 9 查询接口已经可用，但 `historicalReference` 目前仍是查询时动态补齐，而非最终结果落库字段。
2. `history` 查询当前通过 `analysis_task -> alert_event -> analysis_result` 联表实现；这满足当前阶段目标，不需要为阶段 9 单独改表。
3. 全量集成测试仍依赖本地 MySQL；重启机器后需要先按 `LOCAL_MYSQL.md` 启动 `127.0.0.1:3307` 实例。

## 下一步建议

下一阶段应进入阶段 10：测试与回放。

建议优先做：

1. 为阶段 9 查询接口补更多端到端回放样例，覆盖 webhook 真正跑完后的查询链路。
2. 评估是否要将 `historicalReference` 正式持久化进最终结果 JSON，减少查询时补算逻辑。
3. 为 `analysis/history` 增加更严格的上限控制与默认分页策略，避免后续接口被无界查询滥用。

---

## 补充记录：阶段 10 测试与回放

本次只实现 `aiops-alert-analysis-development-plan.md` 中的阶段 10：测试与回放。

### 范围说明

1. 不实现真实内网 Provider，继续复用现有 Provider 接口、Mock Provider 和 Mock LLM。
2. 不修改与阶段 10 无关的业务模块；本次主改动集中在测试代码与测试目录整理。
3. 按“职责拆分包结构”的约束，对测试目录中已经出现的职责混放做同步整理。

### 设计冲突说明

本次实现前确认到 1 个与阶段 10 直接相关的既有实现落差：

1. 阶段 9 已知 `historicalReference` 不是结果落库字段，而是查询时动态补齐；这与“回放结果和持久化结果同构”的理想状态不完全一致。

阶段 10 的最小执行口径：

1. 先按阶段 9 的既有实现验收回放链路，不在这一阶段直接扩表或重做查询模型。
2. 若后续确认要做“结果持久化后直接同构查询”，优先在结果生成/完成阶段补齐并落库，而不是继续加重查询侧补算逻辑。

### 本次完成内容

1. 删除根级混放测试 `AiopsAlertAnalysisApplicationTests`，将其职责拆分到更明确的测试目录。
2. 新增统一测试夹具与样例：
   - `com.company.aiops.support.AlertSamples`
   - `com.company.aiops.support.AbstractAiopsIntegrationTestSupport`
3. 新增系统级基础校验测试：
   - `com.company.aiops.system.ApplicationSmokeTests`
4. 新增告警接入与去重测试：
   - `com.company.aiops.alert.AlertIngressIntegrationTests`
5. 新增回放链路测试：
   - `com.company.aiops.analysis.replay.AnalysisReplayIntegrationTests`
   - `com.company.aiops.analysis.replay.RuleOnlyReplayIntegrationTests`
6. 阶段 10 回放覆盖以下场景：
   - Webhook 错误率样例解析
   - Webhook 时延样例解析
   - 5 分钟内 dedupe
   - 5 分钟外 `historicalReference`
   - 错误率业务 `errorMsg` 未屏蔽
   - 时延下游慢
   - Provider 非关键失败
   - `InterfaceLogProvider` 失败
   - `LLM timeout` 降级
   - `LLM disabled -> RULE_BASED_ONLY`
   - Webhook 到查询接口的完整回放链路
7. 补充阶段 10 缺失的指标异常显式单测：
   - `DB 慢 SQL`
   - `DCS P99 升高`
8. 状态机非法流转场景继续复用既有 `AnalysisJobStateMachineIntegrationTests`，未修改状态机生产实现。

### 本次包结构整理

根据“若当前任务涉及目录已经出现职责混放，必须在本次提交中一并整理”的约束，本次将测试代码固定到以下结构：

```text
src/test/java/com/company/aiops
  support
    AlertSamples
    AbstractAiopsIntegrationTestSupport
  system
    ApplicationSmokeTests
  alert
    AlertIngressIntegrationTests
  analysis
    replay
      AnalysisReplayIntegrationTests
      RuleOnlyReplayIntegrationTests
```

本次没有再把解析、落库、回放、失败分支和系统 smoke 测试堆回一个根级测试类。

### 测试与验证

新增或调整测试：

1. `ApplicationSmokeTests`
   - Spring Boot 启动
   - 健康检查
   - 枚举与 JSON 序列化
   - Flyway 建表
   - `AlertEvent` JSON 字段落库
   - `ResourceMapping` 预置数据读取
2. `AlertIngressIntegrationTests`
   - 两个原始告警样例解析
   - 5 分钟内重复告警合并
   - 5 分钟外历史参考返回
3. `AnalysisReplayIntegrationTests`
   - 错误率全链路回放到 `/analysis/tasks`
   - 时延全链路回放到 `/analysis/history`
   - 重复告警经 `/analysis/latest` 回查原任务
   - Provider 失败和 LLM 失败后的回放查询
4. `RuleOnlyReplayIntegrationTests`
   - `aiops.llm.enabled=false` 时走 `RULE_BASED_ONLY`
5. `DefaultMetricAnomalyDetectorTests`
   - 显式断言 `metric.database.slow_sql.1`
   - 显式断言 `metric.dcs.p99.1`

验证情况：

1. 执行 `mvn -q -DskipTests test-compile`，通过。
2. 执行阶段 10 定向测试，覆盖新增回放与指标单测，全部通过。
3. 执行 `mvn test`，57 个测试全部通过。

### 当前注意事项

1. 到本条记录为止，`historicalReference` 仍然沿用阶段 9 的查询时动态补齐方案。
2. 若后续要实现“结果持久化后直接同构查询”，应在结果完成阶段补齐并落库，而不是继续把逻辑压在查询层。

### 下一步建议

1. 将 `historicalReference` 从查询期补齐改为结果生成期落库。
2. 保留查询侧对旧数据的兼容回填，但不再让新结果依赖该补算路径。
3. 在文档中同步收敛阶段 9 的旧注意事项，避免日志中的阶段性结论长期过时。

---

## 补充记录：historicalReference 结果持久化同构修正

本次只修正上一条阶段 10 记录中遗留的 `historicalReference` 落库缺口，不扩展表结构，不改真实 Provider。

### 范围说明

1. 目标是让新生成的 `AnalysisResult` 在落库后就直接包含 `historicalReference`，避免查询接口必须二次补算才能返回完整结构。
2. 只修改结果完成、结果模型和查询兼容逻辑，不调整阶段 9 的查询接口契约。
3. 对旧数据保留查询侧兼容回填，避免历史记录立刻失效。

### 设计修正说明

上一条阶段 9 / 阶段 10 记录中的已知问题是：

1. `historicalReference` 只在 webhook 接入时临时返回，不会随最终 `AnalysisResult` 落入 `aiops_analysis_result.result_json`。

本次最小修正口径：

1. 不新增字段、不改表结构。
2. 在 `AnalysisJobCompletionService.complete()` 阶段，基于当前任务的 `dedupeKey + createdAt` 回查 5 分钟外最近历史结果。
3. 将回查到的 `historicalReference` 直接写入 `AnalysisResult`，再统一持久化。
4. 查询侧只把动态补齐保留为旧数据兼容路径。

### 本次完成内容

1. 为 `AnalysisResult` 增加 `withHistoricalReference(...)` 复制方法，避免在 completion 阶段手工重建整份结果对象。
2. 扩展 `AnalysisJobCompletionService`：
   - 注入 `AiopsProperties`
   - 注入 `AnalysisTaskRepository`
   - 在终态落库前补齐 `historicalReference`
3. 调整 `AnalysisQueryService` 注释与职责口径：
   - 新结果默认已在落库时带上 `historicalReference`
   - 查询期补齐仅服务于旧数据兼容
4. 新增 `AnalysisJobCompletionServiceTests`，验证 completion 阶段会把 `historicalReference` 写入待保存结果。
5. 更新 `AnalysisReplayIntegrationTests`，直接断言 `aiops_analysis_result.result_json` 中已存在 `historicalReference`，不只检查查询接口返回值。

### 测试与验证

新增或调整测试：

1. `AnalysisJobCompletionServiceTests`
   - 验证 completion 阶段会将历史参考写入最终结果
   - 验证结果本身已带历史参考时不会重复查任务或覆盖字段
2. `AnalysisReplayIntegrationTests`
   - 验证 webhook 完成后的 `result_json` 已直接包含 `historicalReference`
   - 验证查询接口仍能返回同构内容

验证情况：

1. 执行 `mvn -q "-Dtest=AnalysisJobCompletionServiceTests,AnalysisQueryServiceTests,AnalysisReplayIntegrationTests,RuleOnlyReplayIntegrationTests" test`，通过。
2. 执行 `mvn test`，59 个测试全部通过。

### 当前状态

1. 新生成的分析结果已经满足“结果持久化后直接同构查询”。
2. 查询侧仍保留 `historicalReference` 的 fallback 补齐逻辑，但仅用于兼容旧数据。
3. 阶段 9 记录中“`historicalReference` 不是落库字段”的结论已不再适用于当前实现。

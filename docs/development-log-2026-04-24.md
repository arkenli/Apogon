# AIOps 告警自动分析系统开发日志

日期：2026-04-24  
本次提交：`a101c24` 实现 AnalysisJob 状态机；`5eb1ee2` 按职责细化 analysis 包结构

## 已完成范围

已完成 `aiops-alert-analysis-development-plan.md` 中阶段 3 的主要内容，并对 `com.company.aiops.analysis` 目录做了进一步按职责拆分整理。

### 阶段 3：AnalysisJob 状态机

1. 实现显式状态机 `AnalysisJobStateMachine`，当前默认实现为 `DefaultAnalysisJobStateMachine`。
2. 定义并校验合法状态流转，覆盖：
   - 正常成功链路：`PENDING -> RUNNING -> COLLECTING_EVIDENCE -> RULE_ANALYZING -> COMPRESSING_EVIDENCE -> LLM_ANALYZING -> GENERATING_RESULT -> SUCCESS`
   - LLM 失败降级链路：`... -> LLM_ANALYZING -> RULE_FALLBACK_ANALYZING -> GENERATING_RESULT -> DEGRADED_SUCCESS`
   - 非法流转拒绝
3. 为任务状态更新补充乐观锁能力，使用 `task_id + state_version` 更新：
   - `task_status`
   - `current_step`
   - `status_reason`
   - `state_version`
   - `updated_at`
4. 新增状态流转日志模型和仓储：
   - `AnalysisJobStateLog`
   - `AnalysisJobStateLogRepository`
   - `JdbcAnalysisJobStateLogRepository`
5. 每次状态切换都会写入 `aiops_analysis_job_state_log`，便于审计和排障。
6. 实现异步任务入口 `AnalysisJobWorker`，并通过事务提交后的事件调度异步执行。
7. 新增 `AnalysisJobCompletionService`，将结果落库与终态切换收敛为同一完成入口。

### 阶段 3 当前执行口径

1. 阶段 3 只实现状态机和异步执行骨架，不提前实现真实 Provider、规则引擎和 LLM。
2. Webhook 创建 `PENDING` 任务后，会在事务提交后自动触发异步 worker。
3. 运行时默认路径会生成一个占位的 `RULE_BASED_ONLY` 结果，用于验证：
   - 异步领取任务
   - 状态机推进
   - 结果落库
   - 状态日志完整记录
4. 为了验证后续阶段需要的分支，测试中额外覆盖了：
   - 模拟 `LLM_ASSISTED` 成功路径
   - 模拟 `RULE_BASED_DEGRADED` 降级路径
5. 这些占位结果明确标注为阶段 3 占位，不冒充真实分析结论。

### analysis 包结构重构

为避免 `com.company.aiops.analysis` 下模型和编排职责继续混放，本次将目录进一步细化为：

```text
com.company.aiops.analysis
  context
    AnalysisContext
    AnalysisOptions
    TimeWindow
  job
    AnalysisJob
    AnalysisJobStatus
    AnalysisJobStateLog
  result
    AnalysisMode
    AnalysisResult
    EvidenceReference
    HistoricalReference
    ImpactAssessment
    MissingEvidence
    Recommendation
    RootCauseHypothesis
  orchestrator
    state
      AnalysisJobStateMachine
      DefaultAnalysisJobStateMachine
      AnalysisJobStateConflictException
      IllegalAnalysisJobStateTransitionException
    worker
      AnalysisJobWorker
      AnalysisJobCompletionService
      AnalysisJobExecutionRequestedEvent
      AnalysisJobExecutionScheduler
      AnalysisWorkerScenario
```

同时已同步修正：

1. `alert` 模块中的任务、历史参考和异步调度引用。
2. `persistence` 层 Repository 和 JDBC 实现中的分析模型引用。
3. 测试代码中的包路径和 imports。
4. `package-info.java` 中对 analysis 模块职责的说明。

## 验证情况

已运行：

```powershell
mvn test
```

结果：通过。  
当前测试数：15 个。

新增/调整测试覆盖：

1. Webhook 创建任务后异步 worker 自动完成占位生命周期。
2. AnalysisJob 正常成功流转。
3. AnalysisJob LLM 失败降级流转。
4. 非法状态流转拒绝。
5. 状态流转日志完整写入和可查询。

## 当前注意事项

1. 阶段 3 已具备可运行的异步任务骨架，但 Provider、规则分析、证据压缩和真实 LLM 仍未实现。
2. 当前默认运行路径生成的是阶段 3 占位结果，后续阶段需要替换为真实分析输出。
3. `analysis` 模块已经按职责拆分，后续新增代码应继续落在对应子包，不再回到平铺的 `analysis.model` 或 `analysis.orchestrator`。
4. 测试仍依赖本地 MySQL 运行在 `127.0.0.1:3307`。

## 下一步建议

下一阶段应实现阶段 4：Provider 抽象、并行调用和降级。

建议优先做：

1. 定义 Provider 接口和统一 `ProviderResult<T>`。
2. 实现 `ProviderOrchestrator` 并支持并行调用。
3. 接入 `aiops_provider_call_log` 写入。
4. 实现 ResourceMapping 之外的 Mock Provider。
5. 在当前状态机骨架上接入真实的 `COLLECTING_EVIDENCE` 执行逻辑。

---

## 补充记录：阶段 4 Provider 抽象、并行调用和降级

本次补充完成 `aiops-alert-analysis-development-plan.md` 中阶段 4 的主要内容，并对 `provider` 与 `analysis` 中本阶段涉及的目录继续按职责整理。

### 范围说明

1. 严格按本阶段边界实现 Provider 抽象、Mock、编排、降级和调用日志落库。
2. 不实现真实内网 Provider，只提供接口与 Mock 实现。
3. 保留阶段 5 之后的规则分析、证据压缩和 LLM 为后续阶段实现，本次只把 Provider 收集结果接入现有状态机骨架。

### 设计文档冲突说明

本次实现前确认到两个与阶段 4 直接相关的冲突：

1. `aiops-alert-analysis-design.md` 第 18.4 节将“第四阶段”写成了 LLM 集成；但 `aiops-alert-analysis-development-plan.md` 和本目录前一条开发日志都将阶段 4 定义为 Provider 抽象、并行调用和降级。本次按开发计划与最新开发日志执行。
2. 设计文档中 `ProviderStatus` 取值为 `PARTIAL_SUCCESS`，而已有实现中枚举值为 `PARTIAL`。本次已按设计文档统一为 `PARTIAL_SUCCESS`，避免后续 Provider 契约继续分叉。

最小修改建议：

1. 将设计文档中第 18 章的阶段编号与开发计划对齐，避免后续“阶段 4/阶段 8”语义混乱。
2. 在设计文档和实现中统一保留 `ProviderStatus.PARTIAL_SUCCESS` 作为稳定公共契约。

### 阶段 4 已完成内容

1. 定义 Provider 接口：
   - `MetricProvider`
   - `InterfaceLogProvider`
   - `TraceProvider`
   - `RuntimeLogProvider`
   - `BaselineProvider`
   - `ResourceMappingProvider`
2. 新增统一的 Provider 侧公共模型与 DTO：
   - `ProviderResult`
   - `ProviderCallLog`
   - `InterfaceLogEntry`
   - `RuntimeLogEntry`
   - `InterfaceBaseline`
   - `ContainerMetrics`
   - `CseMetrics`
   - `DatabaseMetrics`
   - `DcsMetrics`
   - `MqMetrics`
3. 实现 `ProviderCallExecutor`：
   - 单次调用超时控制
   - 短重试
   - 成功/失败/超时/跳过统一包装
   - 每次调用写入 `aiops_provider_call_log`
4. 实现 `ProviderOrchestrator`，当前默认实现为 `DefaultProviderOrchestrator`：
   - 无依赖 Provider 并行调用
   - `ResourceMapping` 返回后再查询 DB/DCS/MQ 指标
   - `InterfaceLog` 返回并抽样 traceId 后再查询 `Trace` 与 `RuntimeLog`
   - 汇总 `MissingEvidence`、降级标记和 Provider 收集结果
5. 新增 `analysis.context` 与 `analysis.evidence` 下本阶段需要的模型：
   - `AnalysisContextFactory`
   - `DefaultAnalysisContextFactory`
   - `BaselineEvidence`
   - `InterfaceLogEvidence`
   - `TraceEvidence`
   - `RuntimeLogEvidence`
   - `MetricEvidence`
   - `ProviderCollectionResult`
6. 将 `COLLECTING_EVIDENCE` 真正接入 `AnalysisJobWorker`：
   - worker 在 `RUNNING` 后创建 `AnalysisContext`
   - 调用 `ProviderOrchestrator` 获取证据
   - 关键 Provider 失败时走 `RULE_FALLBACK_ANALYZING`
   - 非关键 Provider 失败时继续生成占位结果，但标记 `degraded=true` 和 `degradedByProviderFailure=true`
7. 新增 Mock Provider：
   - `MockMetricProvider`
   - `MockInterfaceLogProvider`
   - `MockTraceProvider`
   - `MockRuntimeLogProvider`
   - `MockBaselineProvider`
8. 为测试注入稳定的失败场景标记 `MockProviderTokens`，不需要引入真实内网依赖或额外测试配置。

### 本次包结构整理

根据“本阶段涉及目录如果已有职责混放必须顺手整理”的约束，本次同步完成以下拆分：

1. `provider.resource` 原先同时放置接口和 JDBC 实现；现已拆为：
   - `provider.resource`：接口
   - `provider.resource.jdbc`：JDBC 实现
2. `provider` 下新增分层目录，避免继续堆积在 `provider.model` 或根目录：
   - `provider.metric`
   - `provider.metric.mock`
   - `provider.log`
   - `provider.log.mock`
   - `provider.trace`
   - `provider.trace.mock`
   - `provider.baseline`
   - `provider.baseline.mock`
   - `provider.orchestrator`
   - `provider.mock`
3. `analysis` 下新增：
   - `analysis.evidence`
   - `analysis.context` 中的工厂职责

### 当前阶段 4 执行口径

1. `InterfaceLogProvider` 失败时，不直接 `FAILED`，而是进入 `RULE_BASED_DEGRADED` 占位结果，任务终态为 `DEGRADED_SUCCESS`。
2. `TraceProvider` 对错误率告警视为重要但非绝对阻断；对时延告警视为关键，失败时会触发降级。
3. `RuntimeLogProvider`、`BaselineProvider`、非映射类指标失败时，任务继续执行，但结果中写出 `missingEvidence`。
4. 由于规则分析、证据压缩和 LLM 仍未落地，本次生成的最终结果仍是明确标注的占位结果，但已真实反映 Provider 收集是否成功、哪些证据缺失、是否因 Provider 失败而降级。
5. 本次只实现阶段 4，不接入任何真实内网 HTTP Provider。

### 验证情况

已运行：

```powershell
mvn test
```

结果：通过。  
当前测试数：22 个。

本次新增/调整测试覆盖：

1. `DefaultAnalysisContextFactory` 的告警窗口和基线窗口计算。
2. `DefaultProviderOrchestrator` 的无依赖 Provider 并行启动。
3. `DefaultProviderOrchestrator` 的非关键 Provider 失败降级但不中断。
4. `DefaultProviderOrchestrator` 的关键 `InterfaceLogProvider` 失败触发 fallback。
5. Webhook 端到端场景下，非关键 `RuntimeLogProvider` 失败后任务进入 `DEGRADED_SUCCESS` 并写入 `missingEvidence`。
6. Webhook 端到端场景下，关键 `InterfaceLogProvider` 失败后进入 `RULE_FALLBACK_ANALYZING` 并输出 `RULE_BASED_DEGRADED`。
7. `aiops_provider_call_log` 的调用记录写入。

### 当前注意事项

1. 阶段 4 已具备真实的 Provider 编排、降级和调用日志能力，但规则分析、证据压缩与 LLM 仍为后续阶段。
2. 当前最终结果仍为“Provider 驱动 + 后续阶段占位”的结构，不应被视为真实根因分析结论。
3. 运行时仍依赖本地 MySQL：`127.0.0.1:3307`。
4. 后续阶段新增代码应继续遵守本次整理后的包职责边界，不再把接口、Mock、JDBC、编排逻辑混放。

### 下一步建议

下一阶段应进入规则分析阶段，优先实现：

1. `ErrorRateRuleAnalyzer`
2. `LatencyRuleAnalyzer`
3. `MetricAnomalyDetector`
4. `TraceTopologyAnalyzer`
5. 基于本次 `ProviderCollectionResult` 的规则证据生成

---

## 补充记录：阶段 5 规则预分析

本次补充完成 `aiops-alert-analysis-development-plan.md` 中阶段 5 的主要内容，并将规则预分析相关代码按职责落入新的 `analysis.rule` 分层包。

### 范围说明
1
1. 严格按阶段 5 边界实现规则预分析，不提前实现阶段 6 的 `EvidenceCompressor` 和阶段 7 的完整规则降级结果生成。
2. 不实现真实内网 Provider，继续复用阶段 4 的 Provider 接口与 Mock。
3. 只在阶段 5 涉及的目录内新增或调整代码，未改动与本阶段无关的模块。

### 设计文档冲突说明

本次实现前再次确认到三个与阶段 5 直接相关的冲突或落差：

1. `aiops-alert-analysis-design.md` 第 18.5 节将“第五阶段”写成了“内网适配和调优”；但开发计划第 8 节和本目录最新开发日志都将阶段 5 定义为“规则预分析”。本次按开发计划与最新日志执行。
2. 设计文档将 `InterfaceLogEvidence`、`TraceEvidence`、`MetricEvidence` 描述为聚合摘要；而现有阶段 4 实现中它们是 Provider 收集后的原始交接 DTO。本次保持阶段 4 实现不回退，在阶段 5 新增 `RuleAnalysisEvidence` 作为聚合规则证据层。
3. 设计文档中的 `MetricAnomalyDetector` 示例更偏向时间序列检测接口；但当前 Provider 契约只返回聚合快照。本次先按当前契约实现阈值、单实例偏斜和高信号计数检测。

最小修改建议：

1. 将设计文档第 18 章的阶段编号与开发计划统一，避免“阶段 5”语义继续分叉。
2. 在设计文档中将证据模型拆成两层：
   - 阶段 4：Provider 原始采集 DTO
   - 阶段 5：`RuleAnalysisEvidence` 聚合规则证据
3. 在 `MetricAnomalyDetector` 章节补充说明：当前版本基于快照检测，后续真实 Provider 接入时间序列后再升级算法。

### 阶段 5 已完成内容

1. 新增 `analysis.rule` 模块分层：
   - `analysis.rule.error`
   - `analysis.rule.latency`
   - `analysis.rule.metric`
   - `analysis.rule.trace`
   - `analysis.rule.model`
2. 定义阶段 5 统一入口 `RuleAnalysisService`，默认实现为 `DefaultRuleAnalysisService`，按告警类别路由到不同规则分析器。
3. 实现 `TraceTopologyAnalyzer`，当前默认实现为 `DefaultTraceTopologyAnalyzer`：
   - 基于 `spanId` / `parentSpanId` 重建调用关系
   - 支持查找根 span、首个失败 span、最慢 span 和未解析父节点
4. 实现 `MetricAnomalyDetector`，当前默认实现为 `DefaultMetricAnomalyDetector`：
   - 容器 CPU / 内存 / GC / 实例状态
   - CSE 使用率
   - DB CPU / 连接 / 锁等待 / 写时延 / 慢 SQL
   - DCS P99 / 平均时延 / 慢查询比例 / 客户端失败
   - MQ 堆积 / 发布消费时延 / 失败次数
5. 实现 `ErrorRateRuleAnalyzer`，当前默认实现为 `DefaultErrorRateRuleAnalyzer`：
   - 请求量影响范围判断
   - `errorCode` TopN 聚合
   - 基于 `errorMsg` 的业务语义判断
   - `uid` 集中度统计
   - 基于 trace 的下游失败识别
   - 资源异常关联打分
6. 实现 `LatencyRuleAnalyzer`，当前默认实现为 `DefaultLatencyRuleAnalyzer`：
   - 平均时延 / P99 区分
   - 慢 trace 聚合
   - 最慢 span 识别
   - 下游慢点判断
   - DB / DCS / 容器 / GC / 实例状态 / 流量突增关联打分
7. 新增阶段 5 结构化输出模型：
   - `RuleAnalysisEvidence`
   - `RuleFinding`
   - `CandidateRootCause`
   - `ErrorCodeAggregate`
   - `UidAggregate`
   - `TraceObservation`
   - `DownstreamObservation`
   - `MetricAnomaly`
8. 将 `RULE_ANALYZING` 真正接入 `AnalysisJobWorker`：
   - Provider 收集完成后真实执行阶段 5 规则分析
   - 当前占位结果会携带阶段 5 产出的 evidenceId 和最高分候选根因
   - 不提前落地阶段 6 / 7 的结果生成职责
9. 更新 `AnalysisJobCompletionService` 和 `analysis/package-info.java`，使说明与当前职责边界一致。

### 当前阶段 5 执行口径

1. 阶段 5 已经真实执行规则预分析，但最终结果仍是“阶段 5 规则证据 + 阶段 6/7 占位输出”的组合，不应冒充完整最终分析报告。
2. 错误率规则中“业务 errorCode 未屏蔽”判断严格基于 `errorMsg` 语义，不根据 `errorCode` 字面值直接下结论。
3. 时延规则中调用关系仍通过 `spanId` / `parentSpanId` 推断，因此会在 `insufficientEvidence` 中明确记录该限制。
4. 当前 `MetricAnomalyDetector` 仅基于 Provider 快照做确定性检测，不依赖真实时序指标接口。

### 本次包结构整理

根据“涉及目录若存在职责混放必须在本次提交中一并整理”的约束，本次新增并固定了如下规则分析包边界：

```text
com.company.aiops.analysis.rule
  error
    ErrorRateRuleAnalyzer
    DefaultErrorRateRuleAnalyzer
  latency
    LatencyRuleAnalyzer
    DefaultLatencyRuleAnalyzer
  metric
    MetricAnomalyDetector
    DefaultMetricAnomalyDetector
  trace
    TraceTopologyAnalyzer
    DefaultTraceTopologyAnalyzer
    TraceTree
    TraceSpan
  model
    RuleAnalysisEvidence
    RuleFinding
    CandidateRootCause
    ErrorCodeAggregate
    UidAggregate
    TraceObservation
    DownstreamObservation
    MetricAnomaly
```

这次没有把规则模型继续混放回 `analysis.evidence` 或 `analysis.result`，也没有把规则执行逻辑塞进 `analysis.orchestrator`。

### 验证情况

已运行：

```powershell
mvn test
```

结果：通过。  
当前测试数：28 个。

本次新增/调整测试覆盖：

1. `DefaultTraceTopologyAnalyzer` 的调用树构建、根节点识别、未解析父节点识别、首个失败 span / 最慢 span 识别。
2. `DefaultMetricAnomalyDetector` 的容器 / CSE / DB / DCS 快照异常检测。
3. `DefaultErrorRateRuleAnalyzer` 的 `errorCode` 聚合、`errorMsg` 业务语义判断、`uid` 聚合和下游失败识别。
4. `DefaultLatencyRuleAnalyzer` 的慢 trace 聚合、最慢 span 聚合和下游慢点识别。
5. Webhook 端到端场景下，错误率占位结果已带上真实阶段 5 规则 evidenceId。
6. Webhook 端到端场景下，时延占位结果已带上真实阶段 5 trace 规则 evidenceId。

### 当前注意事项

1. 阶段 5 已具备真实规则预分析能力，但 `EvidenceCompressor`、`aiops_evidence_bundle` 落库和完整规则降级结果仍未实现。
2. 当前持久化的 `AnalysisResult` 仍是占位结果结构，只是已经带有真实的阶段 5 规则证据索引和最高分候选根因。
3. 本次没有修改任何真实内网 Provider 适配逻辑；后续“内网适配和调优”仍应放到后续阶段单独实现。

### 下一步建议

下一阶段应进入阶段 6：`EvidenceCompressor`。

建议优先做：

1. 设计并实现压缩后的结构化 evidence bundle。
2. 复用本次 `RuleAnalysisEvidence` 生成稳定 evidenceId 索引。
3. 按配置裁剪 `errorCode`、`uid`、慢 trace 和运行日志样本。
4. 落库 `aiops_evidence_bundle`。
5. 为阶段 7 的规则降级分析和阶段 8 的 LLM 输入准备统一证据格式。

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

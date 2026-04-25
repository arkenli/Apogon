# AIOps 告警自动分析系统开发计划

版本：v0.1  
日期：2026-04-23  
关联设计文档：[aiops-alert-analysis-design.md](./aiops-alert-analysis-design.md)

## 1. 开发目标

基于现有设计文档，实现一个后端服务，支持：

1. 接收 WiseEye Webhook 原始告警。
2. 解析并分类 RPC 错误率告警和 RPC 时延告警。
3. 创建 AnalysisJob，并通过显式状态机管理生命周期。
4. 并行调用 Provider 获取日志、Trace、指标、基线和资源映射。
5. 在 LLM 可用时输出 LLM 辅助分析结果。
6. 在 LLM 不可用或 Provider 部分失败时输出带标记的规则降级结果。
7. 将告警、任务、证据、Provider 调用、LLM 调用和分析结果持久化到 MySQL。
8. 提供查询接口，供外部 IM 模块拉取分析结果。

## 2. 技术基线

1. JDK 21。
2. Spring Boot。
3. MySQL。
4. Maven。
5. JSON 序列化建议使用 Jackson。
6. 数据访问层可选 MyBatis、MyBatis-Plus 或 Spring Data JPA，开发前统一选型。
7. 外部 Provider 一期使用 Mock/Stub，不接真实内网 HTTP。
8. ResourceMappingProvider 一期必须读取 MySQL 预置数据。

### 2.1 代码组织与注释要求

1. 包结构必须按职责持续细分，不能把 `Classifier`、`Response`、`Event`、`Controller`、`Repository`、`Jdbc` 实现等长期混放在同一目录。
2. 分包优先按“接口职责 + 运行层次”拆分，例如 `alert.controller`、`alert.parser`、`alert.classifier`、`alert.dedupe`、`alert.model`、`analysis.model`、`analysis.orchestrator`、`analysis.rule`、`provider.resource`、`provider.model`、`persistence.repository`、`persistence.repository.jdbc`、`common.json`、`common.error`。
3. 如果某个包下已经同时出现领域模型、响应对象、编排逻辑、基础设施实现，必须在当前阶段顺手完成拆分，不能等到后续阶段再集中清理。
4. 新增或修改代码时必须补充必要注释，至少覆盖：包级职责说明、公共类/接口/record/enum 的用途说明，以及复杂规则、状态流转、SQL 映射、降级分支等不自解释逻辑。
5. 注释要求解释“为什么这样设计、边界是什么、输入输出含义是什么”，不要只重复代码表面语义。

## 3. 推荐开发顺序

开发时严格按阶段推进。每个阶段完成后再进入下一阶段，避免在 Provider、LLM 和规则引擎之间来回返工。

### 阶段 0：项目初始化

目标：把当前 Maven 空项目整理成可运行的 Spring Boot 后端骨架。

任务：

1. 引入 Spring Boot Web、Validation、Jackson、MySQL Driver、测试依赖。
2. 配置 JDK 21 编译。
3. 建立基础包结构，并从一开始按职责拆分子包：

```text
com.company.aiops
  alert
    controller
    parser
    classifier
    dedupe
    model
  analysis
    controller
    orchestrator
    strategy
    rule
    evidence
    model
  provider
    metric
    log
    trace
    baseline
    resource
    model
  llm
    client
    prompt
    schema
    validator
  persistence
    entity
    repository
  common
    time
    json
    error
```

4. 增加 `application.yml`，写入设计文档第 16 章配置项。
5. 增加统一异常模型和统一 API 响应模型。
6. 为根包和关键子包补充 `package-info.java` 或等价包级说明，为核心公共类型补充基础注释。

验收标准：

1. 应用可以本地启动。
2. 健康检查接口可访问，例如 `GET /actuator/health` 或自定义 `GET /api/v1/health`。
3. `mvn test` 通过。
4. 基础分包已经落地，不存在明显的 Web 层、领域模型、基础设施实现混放。
5. 核心公共类型和关键包具备最少必要注释，后续阶段不需要二次返工补文档化。

## 4. 阶段 1：数据库与领域模型

目标：先把核心实体、枚举和表结构落地，后续模块都基于这些模型开发。

任务：

1. 创建设计文档第 13 章所有表：
   - `aiops_alert_event`
   - `aiops_analysis_task`
   - `aiops_analysis_result`
   - `aiops_evidence_bundle`
   - `aiops_provider_call_log`
   - `aiops_llm_call_log`
   - `aiops_duplicate_alert_link`
   - `aiops_resource_mapping`
   - `aiops_analysis_job_state_log`
2. 实现核心枚举：
   - `AlertCategory`
   - `AnalysisJobStatus`
   - `AnalysisMode`
   - `ProviderStatus`
3. 实现核心 DTO：
   - `AlertEvent`
   - `AlertTriggerValues`
   - `AnalysisJob`
   - `AnalysisContext`
   - `AnalysisResult`
   - `RootCauseHypothesis`
   - `Recommendation`
   - `MissingEvidence`
   - `HistoricalReference`
   - `TimeWindow`
   - `AnalysisOptions`
4. 实现 Repository/Mapper。
5. 准备一份 ResourceMapping 初始化 SQL。

验收标准：

1. 表结构能成功初始化。
2. 单元测试覆盖枚举序列化、JSON 字段序列化。
3. ResourceMappingProvider 可以从 DB 读取预置映射。

## 5. 阶段 2：告警接入、解析、分类和去重

目标：Webhook 收到告警后能创建任务，重复告警能正确合并或返回历史参考。

任务：

1. 实现 `AlertWebhookController`：
   - `POST /api/v1/alerts/webhook`
2. 实现 `AlertParser`：
   - 解析 `Alert ID`
   - 解析 `Category`
   - 解析 `Host` 中的 serviceName、interfaceName、cluster
   - 解析 `Details` 中的请求量、失败率、平均时延、发生时间
   - 填充 `AlertTriggerValues`
3. 实现 `AlertClassifier`：
   - `AIOps@rate` -> `RPC_ERROR_RATE`
   - `AIOps@delay` -> `RPC_LATENCY`
4. 实现 `AlertDeduplicationService`：
   - 5 分钟内相同 dedupeKey 返回已有 taskId
   - 5 分钟外相同 dedupeKey 返回 `historicalReference`
5. 实现任务创建：
   - 新告警创建 `PENDING` AnalysisJob
   - 重复告警写 `aiops_duplicate_alert_link`

验收标准：

1. 设计文档附录中的两个原始告警样例都能解析成功。
2. 错误率样例能解析出 `ams_front`、接口名、`cluster1`、`521600500`。
3. 时延样例能解析出 `vmall_ingresscache_ica`、接口名、`cluster2`、`521932116`。
4. 5 分钟内重复告警不创建新任务。
5. 5 分钟外历史告警能返回历史参考，但仍创建新任务。

## 6. 阶段 3：AnalysisJob 状态机

目标：所有分析任务都通过显式状态机推进，并记录状态流转日志。

任务：

1. 实现 `AnalysisJobStateMachine`。
2. 实现合法状态流转校验。
3. 每次状态切换更新：
   - `aiops_analysis_task.task_status`
   - `current_step`
   - `status_reason`
   - `state_version`
   - `updated_at`
4. 每次状态切换插入 `aiops_analysis_job_state_log`。
5. 使用 `task_id + state_version` 做乐观锁更新。
6. 实现异步任务执行入口 `AnalysisJobWorker`。

验收标准：

1. 正常任务状态流转：
   `PENDING -> RUNNING -> COLLECTING_EVIDENCE -> RULE_ANALYZING -> COMPRESSING_EVIDENCE -> LLM_ANALYZING -> GENERATING_RESULT -> SUCCESS`
2. LLM 失败任务状态流转：
   `... -> LLM_ANALYZING -> RULE_FALLBACK_ANALYZING -> GENERATING_RESULT -> DEGRADED_SUCCESS`
3. 非法状态流转会被拒绝。
4. 状态流转日志完整可查。

## 7. 阶段 4：Provider 抽象、并行调用和降级

目标：实现 Provider 编排框架，不接真实内网接口，但能通过 Mock 数据驱动后续分析。

任务：

1. 定义 Provider 接口：
   - `MetricProvider`
   - `InterfaceLogProvider`
   - `TraceProvider`
   - `RuntimeLogProvider`
   - `BaselineProvider`
   - `ResourceMappingProvider`
2. 实现 `ProviderResult<T>` 包装。
3. 实现 `ProviderOrchestrator`：
   - 无依赖 Provider 并行调用
   - Trace/RuntimeLog 等依赖接口日志采样后再调用
4. 实现 Provider 超时、短重试和失败记录。
5. 实现 `aiops_provider_call_log` 写入。
6. 实现 Mock Provider：
   - 错误率低请求量样例
   - 业务 errorMsg 样例
   - 下游失败样例
   - 时延下游慢样例
   - Provider 失败样例

验收标准：

1. Provider 并行调用生效。
2. 非关键 Provider 失败时任务继续，结果包含 `missingEvidence`。
3. InterfaceLogProvider 失败时进入规则降级结果，状态为 `DEGRADED_SUCCESS`。
4. ResourceMappingProvider 从 DB 表读取数据。

## 8. 阶段 5：规则预分析

目标：在不调用 LLM 的情况下，系统也能产出结构化规则证据。

任务：

1. 实现 `ErrorRateRuleAnalyzer`：
   - 请求量影响范围判断
   - errorCode 聚合
   - errorMsg 业务语义判断
   - uid 集中度
   - 下游失败判断
2. 实现 `LatencyRuleAnalyzer`：
   - 平均时延/P99 区分
   - 慢 trace 聚合
   - 下游慢点判断
   - DB/DCS/容器/GC 关联判断
3. 实现 `MetricAnomalyDetector`。
4. 实现 `TraceTopologyAnalyzer`：
   - 基于 spanId/parentSpanId 构造调用关系
5. 生成 `RuleAnalysisEvidence`。

验收标准：

1. 错误率样例能生成 errorCode/errorMsg/uid 聚合。
2. 业务 errorCode 未屏蔽判断必须基于 errorMsg，不基于 errorCode 字面值。
3. 时延样例能找出慢 trace 和最慢 span。
4. 所有规则发现都带 evidenceId。

## 9. 阶段 6：EvidenceCompressor

目标：把原始证据压缩成可送入 LLM 的结构化证据包。

任务：

1. 实现 `EvidenceCompressor`。
2. 按配置限制：
   - `max-error-code-size`
   - `max-uid-size`
   - `max-trace-sample-size`
   - `max-slow-trace-sample-size`
   - `max-runtime-log-size`
   - `runtime-log-message-max-chars`
3. 保留 uid 原值，不脱敏。
4. 每条证据生成稳定 evidenceId。
5. 保存 `aiops_evidence_bundle`。

验收标准：

1. 压缩证据不超过配置限制。
2. LLM 输入中所有结论候选都有 evidenceId。
3. 缺失 Provider 信息能进入 `missingEvidence`。

## 10. 阶段 7：规则降级分析

目标：LLM 不可用或关闭时，仍能返回可读、可审计的分析结果。

任务：

1. 实现 `RuleBasedFallbackAnalyzer`。
2. 实现错误率告警评分规则。
3. 实现时延告警评分规则。
4. 支持两种模式：
   - `RULE_BASED_DEGRADED`
   - `RULE_BASED_ONLY`
5. 输出同构 `AnalysisResult`。
6. 标记：
   - `analysisMode`
   - `degraded`
   - `degradedReason`
   - `llmUsed`
   - `llmStatus`
   - `degradedByProviderFailure`

验收标准：

1. LLM 超时时返回 `RULE_BASED_DEGRADED`。
2. `aiops.llm.enabled=false` 时返回 `RULE_BASED_ONLY`。
3. 规则降级结论必须包含 rootCauses 和 recommendations。
4. 不允许只返回“LLM 不可用”这类空结果。

## 11. 阶段 8：LLM 集成

目标：接入抽象 LLM Client，完成 Prompt 构造、调用、校验和降级。

任务：

1. 实现 `LlmClient` 抽象。
2. 实现 `MockLlmClient`。
3. 实现 OpenAI-compatible client。
4. 实现 PromptBuilder：
   - `RpcErrorRatePromptBuilder`
   - `RpcLatencyPromptBuilder`
5. 实现 JSON Schema 校验。
6. 实现 evidenceId 引用校验。
7. 实现 LLM 调用日志保存。
8. LLM 失败时调用 `RuleBasedFallbackAnalyzer`。

验收标准：

1. LLM 正常时返回 `LLM_ASSISTED`。
2. LLM 输出非法 JSON 时自动重试一次。
3. 重试失败后返回 `RULE_BASED_DEGRADED`。
4. LLM 输出引用不存在的 evidenceId 时被拒绝。

## 12. 阶段 9：查询接口

目标：提供给 IM 或外部模块查询分析结果。

任务：

1. 实现：
   - `GET /api/v1/analysis/tasks/{taskId}`
   - `GET /api/v1/analysis/latest?alertId=...`
   - `GET /api/v1/analysis/history?serviceName=...&interfaceName=...&limit=...`
2. 返回字段必须包含：
   - `analysisMode`
   - `degraded`
   - `degradedReason`
   - `degradedByProviderFailure`
   - `missingEvidence`
   - `historicalReference`
   - `rootCauses`
   - `recommendations`
3. 查询历史结果时按 `generated_at desc` 排序。

验收标准：

1. 任务运行中能查询状态。
2. 任务完成后能查询完整结果。
3. 降级结果能清晰展示降级原因。

## 13. 阶段 10：测试与回放

目标：确保核心路径可验证，便于后续接内网真实 Provider。

测试用例：

1. Webhook 解析错误率样例。
2. Webhook 解析时延样例。
3. 5 分钟内 dedupe。
4. 5 分钟外 historicalReference。
5. 错误率业务 errorMsg 未屏蔽。
6. 错误率下游失败。
7. 时延下游慢。
8. DB 慢 SQL。
9. DCS P99 升高。
10. Provider 非关键失败。
11. InterfaceLogProvider 失败。
12. LLM 超时降级。
13. LLM 关闭后 `RULE_BASED_ONLY`。
14. 状态机非法流转。

验收标准：

1. 单元测试覆盖核心规则和状态机。
2. 集成测试覆盖 Webhook 到结果查询的完整链路。
3. Mock 数据能稳定复现两类告警分析结果。

## 14. Vibe Coding 执行建议

每次让 AI 实现时，只给一个阶段或一个小任务，不要一次性要求实现所有模块。

推荐提示词格式：

```text
请基于 docs/aiops-alert-analysis-design.md 和 docs/aiops-alert-analysis-development-plan.md，以及docs目录下的development-log开头的开发日志文件
只实现阶段 X 的内容。

约束：
1. 不实现真实内网 Provider，只实现接口和 Mock。
2. 不修改与本阶段无关的模块。
3. 增加必要单元测试。
4. 保持 JDK 21 兼容。
5. 如果发现设计文档与实现冲突，先指出冲突并给出最小修改建议。
6. 严格按职责拆分包结构；如果当前任务涉及的目录已经出现职责混放，必须在本次提交中一并整理。
7. 为新增或修改的公共类型、复杂逻辑和关键流程补充必要注释，不接受“代码能跑但几乎无注释”的提交。
```

## 15. 开发完成定义

一期完成必须满足：

1. 两个原始告警样例可以通过 Webhook 触发分析。
2. 错误率和时延告警都能走完整链路。
3. Provider 并行调用和 Provider 失败降级可验证。
4. LLM 正常、LLM 超时、LLM 关闭三种路径可验证。
5. 结果持久化到 MySQL。
6. 查询接口可以返回完整分析结果。
7. 所有根因结论都有 evidenceId。
8. 没有任何自动处置动作。
9. 包结构与设计文档推荐目录基本一致，核心模块不存在长期平铺混放。
10. 核心公共类型、复杂规则和关键流程具备可维护的注释说明。

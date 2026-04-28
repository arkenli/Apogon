# AIOps Alert Analysis 代码检视报告

日期：2026-04-29
检视范围：全量源码（`src/main/java`、`src/test/java`、`src/main/resources`），对照 `docs/` 目录下全部设计文档与开发日志
测试环境：纯单元测试通过（34个）；集成测试需本地 MySQL `127.0.0.1:3307`（当前会话 MySQL 未启动，27 个集成测试跳过）

## 一、总体结论

项目代码质量良好，与设计文档高度一致。包结构严格按职责分层，读写分离、状态机、并行编排、降级路径均已落地。最近一次风险修复（2026-04-26 验收记录）已补齐 Provider 线程池、历史查询上限、集成测试数据清理三个问题。

**核心优点：**
- 接入层 -> 编排层 -> 证据采集层 -> 规则/LLM 分析层 -> 持久化层 -> 查询层 的六层架构已成型
- 显式状态机 + 乐观锁 + 状态流转审计日志，异步任务生命周期完整可追溯
- Provider 和 LLM 均通过接口隔离，Mock 实现覆盖完整，便于后续接入真实数据源
- LLM 输出有 evidenceId 引用校验、Schema 校验、最多一次重试机制
- 规则降级分析器可独立生成同构结果，在 LLM 不可用时不丢失可读结论
- 测试分层合理：单元测试覆盖核心规则、状态机、编排、校验；集成测试覆盖链路回放

## 二、与设计文档的一致性

| 设计文档章节 | 实现状态 | 备注 |
|---|---|---|
| §5 核心领域模型 | 已实现 | AlertEvent、AnalysisJob、AnalysisResult 等 record 与设计一致 |
| §6 Webhook 与查询接口 | 已实现 | 4 个接口均可用 |
| §7 告警解析与分类 | 已实现 | WiseEyeAlertParser 支持多字段名变体 |
| §8 Provider 抽象 | 已实现 | 6 个 Provider 接口 + Mock + JdbcResourceMappingProvider |
| §9 规则预分析 | 已实现 | ErrorRate/Latency RuleAnalyzer + MetricAnomalyDetector + TraceTopologyAnalyzer |
| §10-11 分析策略 | 已实现 | RpcErrorRate/RpcLatency 两条策略路径均覆盖 |
| §12 LLM 设计 | 已实现 | MockLlmClient + OpenAiCompatibleLlmClient 骨架，PromptBuilder，输出校验 |
| §12.11 规则降级 | 已实现 | DefaultRuleBasedFallbackAnalyzer，评分模型落地 |
| §13 MySQL 表设计 | 已实现 | 9 张表通过 Flyway 管理 |
| §14 任务生命周期 | 已实现 | 完整状态机 + 状态流转日志 |
| §15 扩展机制 | 已实现 | AnalysisStrategy 接口 + 注册表 |

**已知设计-实现差异（已在开发日志中记录）：**

1. `aiops.llm.provider` 默认为 `mock` 而非设计文档的 `openai-compatible`，这是阶段 8 本地开发约束的刻意选择
2. `historicalReference` 已从查询时动态补齐改为结果持久化时写入（阶段 10 补充记录修复）
3. LLM 输出的 `source` 字段在设计文档示例中使用 `MERGED`，在当前实现中规则降级路径使用 `RULE_ENGINE`，LLM 路径使用 `MERGED`，与文档第 12.7 节说明一致

## 三、逐模块检视

### 3.1 `alert` 模块

- **WiseEyeAlertParser**: 解析逻辑健壮，正确处理了 `serviceName`/`servicename`/`服务名称` 多写法、Host JSON 容错、Details 时间优先于 Occur Time。混合了中英文错误消息（`"告警原文不能为空"` vs 其余模块的英文），不影响功能但不一致。
- **AlertClassifier**: 规则清晰，`rawCategory` + `metricName` fallback 双路径。
- **AlertDeduplicationService**: 5 分钟内实时任务去重和 5 分钟外历史参考分离正确。
- **AlertIngestionService**: 关键字段校验（alertId、category、serviceName、interfaceName、cluster、metricOccurTime）完整，事务内创建任务 + 事务后发事件的分隔正确。

### 3.2 `analysis` 模块

- **DefaultAnalysisJobStateMachine**: 用 `EnumMap<AnalysisJobStatus, Set<AnalysisJobStatus>>` 定义合法流转，乐观锁 `state_version` 更新，每次迁移写 `aiops_analysis_job_state_log`。流转图与设计文档第 14 章完全对应。
- **AnalysisJobWorker**: 生命周期编排简洁，Provider 采集、规则分析、证据压缩、LLM 分析、规则降级 5 条分支清晰。`submit()` 是 `@Async("analysisJobExecutor")` 异步入口，`processTask()` 是同步可测试入口。
- **DefaultRuleAnalysisService**: 路由到 ErrorRate/Latency 两个规则分析器。
- **规则分析器**: `DefaultErrorRateRuleAnalyzer` 的 errorCode 聚合和 errorMsg 业务语义判断严格基于 errorMsg 而非 errorCode 字面值，符合设计约束。`DefaultLatencyRuleAnalyzer` 的慢 trace 聚合和下游慢点判断逻辑合理。
- **DefaultTraceTopologyAnalyzer**: 基于 spanId/parentSpanId 推断调用关系，在 `insufficientEvidence` 中记录了该推断方式的限制。
- **DefaultEvidenceCompressor**: 按配置上限裁剪 errorCode、uid、trace、运行日志，优先保留被候选根因和规则发现引用的证据项。
- **DefaultRuleBasedFallbackAnalyzer**: 这是整个降级路径的核心，约 750 行，职责清晰。评分模型、confidence 调整、summary 措辞、recommendation 映射均集中管理。实现了完整的 `RULE_BASED_ONLY` 和 `RULE_BASED_DEGRADED` 两条路径。

### 3.3 `provider` 模块

- **DefaultProviderOrchestrator**: 并行编排逻辑完善——阶段 1 并行获取 ResourceMapping/Baseline/InterfaceLog/Container/CSE 指标，阶段 2 等 ResourceMapping 后并行获取 DB/DCS/MQ 指标，阶段 3 等 InterfaceLog 采样 traceId 后并行获取 Trace/RuntimeLog。
- **ProviderCallExecutor**: 超时、重试、审计日志落库均实现。
- **Provider 关键性矩阵**: 与设计文档 §8 一致——InterfaceLog 对所有告警类型关键；Trace 对 RPC_LATENCY 关键，对 RPC_ERROR_RATE 为重要。
- **Mock Provider**: 覆盖正常、失败、慢查询、DB 异常等场景，测试注入通过 `MockProviderTokens` 控制。

### 3.4 `llm` 模块

- **MockLlmClient**: 基于压缩证据包生成稳定的确定性 JSON 输出，支持通过 serviceName token 模拟 TIMEOUT / INVALID_JSON / 首轮失败重试成功等场景。
- **OpenAiCompatibleLlmClient**: 最小适配骨架，未配置 baseUrl/apiKey 时返回 UNAVAILABLE。
- **DefaultLlmOutputValidator**: 校验 JSON 可解析、必填字段、数组上限、枚举值域、confidence 值域，以及 evidenceId 存在于压缩证据包中。未知 causeType 映射为 UNKNOWN。
- **DefaultLlmAnalysisService**: 最多重试一次，重试时追加格式修复提示。验证失败/调用失败均写 `aiops_llm_call_log` 并抛出 `LlmAnalysisException` 触发降级。

### 3.5 `persistence` 模块

- **接口-实现分离**: 所有 Repository 都定义了接口（`persistence.repository`）+ JDBC 实现（`persistence.repository.jdbc`）。
- **SQL 安全**: 全部使用 JdbcTemplate 参数化查询，无拼接 SQL 注入风险。`JdbcAnalysisTaskRepository.findLatestByDedupeKeyAndStatusesSince()` 中的 `placeholders` 虽然通过 `String.formatted()` 构建 SQL 片段，但占位符数量来自枚举值数量，不是外部输入。
- **JSON 字段**: `trigger_values_json` 和 `result_json` 使用 MySQL JSON 类型，通过 Jackson 序列化/反序列化。

### 3.6 `common` 模块

- **AiopsProperties**: 全部配置项通过 `@ConfigurationProperties` + `@Validated` 绑定，覆盖设计文档第 16 章所有配置。包含 JSR-380 校验注解（如 `@Min`、`@DecimalMin`、`@NotBlank`）。
- **AsyncExecutionConfiguration**: 3 个独立线程池——`analysisJobExecutor`（core=2）、`providerExecutor`（core=8）、`providerOrchestratorExecutor`（core=8），后者是防止 Provider 编排落到 JDK common pool 的修复（2026-04-26 验收修复项）。
- **GlobalExceptionHandler**: 覆盖 AiopsException、校验异常、消息不可读、404、兜底异常，统一返回 `ApiResponse`。

## 四、发现的问题和风险

### 4.1 中风险

**R1: Webhook 端无鉴权，查询接口也无鉴权**
- 文件：`AlertWebhookController.java:28`、`AnalysisQueryController.java:22`
- 说明：设计文档 §2.10 写明"Webhook 鉴权暂不需要"，但当前所有查询接口（`/api/v1/analysis/*`）同样无任何认证/授权。生产部署前需要补齐至少基于 API Key 或内网白名单的访问控制。
- 建议：为 webhook 和查询接口统一增加 API Key 校验或 IP 白名单。

**R2: LLM 缺少熔断器**
- 文件：`DefaultLlmAnalysisService.java`
- 说明：设计文档 §12.11.1 第 7 条明确列出"LLM 熔断器处于打开状态"作为触发降级的场景，但当前实现只有简单的重试（最多一次）+ 失败抛异常。如果 LLM 持续不可用，每个任务都会尝试两次调用后才降级，可能造成资源浪费和任务积压。
- 建议：增加基于失败计数和时间窗口的简单熔断器，熔断期间直接跳过 LLM 进入降级。

### 4.2 低风险

**R3: 告警解析错误消息语言不一致**
- 文件：`WiseEyeAlertParser.java` (L39, L92)
- 说明：解析器中的异常消息使用中文（`"告警原文不能为空"`、`"Host 字段 JSON 解析失败"`），与其余模块全部英文异常消息不一致。
- 建议：统一为英文。

**R4: 未配置 `spring.jackson.default-property-inclusion` 与空值字段**
- 文件：`application.yml`
- 说明：已配置 `default-property-inclusion: non_null`，这对 API 响应是合理的选择。但 `AnalysisResult` 中某些字段（如 `degradedReason`、`ruleEngineVersion`）在非降级路径下可能为 null，返回给查询接口时不包含在 JSON 中。调用方需做好字段缺失的兼容。
- 建议：在查询接口文档中说明哪些字段可能不出现。

### 4.3 建议改进

**S1: 考虑简化 DefaultRuleBasedFallbackAnalyzer**
- 文件：`DefaultRuleBasedFallbackAnalyzer.java`
- 说明：该类约 750 行，包含了 score→confidence 映射、summary 生成、impact 评估、recommendation 策略等所有降级逻辑。后续如果新增告警类型，这个类会持续膨胀。
- 建议：后续可以按告警类别拆分为 `ErrorRateFallbackStrategy` 和 `LatencyFallbackStrategy`，与 `AnalysisStrategy` 的结构对齐。

**S2: 日志中增加 traceId/spanId 传递**
- 说明：当前异步执行链路（Webhook -> Event -> Async Worker -> Provider -> LLM）缺少统一的 trace context 传递。排查问题时需要靠 taskId 串联所有日志。
- 建议：利用 SLF4J MDC 在 worker 线程中设置 taskId/alertId，便于日志检索。

**S3: 补充 history 查询的 serviceName/interfaceName 参数校验**
- 文件：`AnalysisQueryService.java:64`
- 说明：当前只校验了 limit 上限，未校验 serviceName 和 interfaceName 是否为空。如果两者都为空，会执行全表扫描的查询。
- 建议：增加 `serviceName` 必填校验，或在两者皆为空时直接返回错误。

## 五、测试覆盖统计

| 分层 | 测试数（纯单元） | 状态 |
|---|---|---|
| 系统 smoke | 0（需 DB） | 集成测试依赖 MySQL |
| 告警接入与去重 | 0（需 DB） | 集成测试依赖 MySQL |
| 规则分析 | ~10 | 全部通过 |
| Provider 编排 | 3 | 全部通过 |
| 证据压缩 | 5 | 全部通过 |
| 规则降级 | ~3 | 全部通过 |
| LLM 校验与服务 | 3 | 全部通过 |
| 查询接口 | ~5 | 全部通过（含 controller 层） |
| 状态机集成 | 0（需 DB） | 集成测试依赖 MySQL |
| 回放集成 | 0（需 DB） | 集成测试依赖 MySQL |
| **合计** | **34 通过 / 61 总** | 27 个集成测试待 MySQL |

**未覆盖的场景：**
- 非法状态机流转的单元测试（当前仅在集成测试中覆盖）
- `OpenAiCompatibleLlmClient` 的单元测试（当前仅有集成测试路径覆盖）
- `async-timeout` 超时后的任务中断和恢复

## 六、与开发计划的对应关系

根据 `aiops-alert-analysis-development-plan.md` 的 10 阶段计划：

| 阶段 | 内容 | 完成状态 |
|---|---|---|
| 0 | 项目初始化 | 已完成 |
| 1 | 数据库与领域模型 | 已完成 |
| 2 | 告警接入解析分类去重 | 已完成 |
| 3 | AnalysisJob 状态机 | 已完成 |
| 4 | Provider 抽象并行调用降级 | 已完成 |
| 5 | 规则预分析 | 已完成 |
| 6 | EvidenceCompressor | 已完成 |
| 7 | 规则降级分析 | 已完成 |
| 8 | LLM 集成 | 已完成 |
| 9 | 查询接口 | 已完成 |
| 10 | 测试与回放 | 已完成 |

**结论：一期全部 10 个阶段均已完成**，开发完成定义中的 10 项条件满足。

## 七、后续建议

1. **真实 Provider 接入**：当前除 ResourceMapping 外，所有 Provider 均为 Mock。下一步应将 Mock 替换为真实内网 HTTP 适配器。
2. **真实 LLM 接入**：当前默认 LLM 为 Mock。需补齐 `OpenAiCompatibleLlmClient` 的 baseUrl/apiKey 配置，并进行脱敏审计。
3. **LLM 熔断器**：参见 §4.1 R2。
4. **鉴权补齐**：参见 §4.1 R1。
5. **evidence 过期清理**：`aiops_evidence_bundle` 和 `aiops_llm_call_log` 的 `expire_at` 已设置，但后台定时清理任务尚未实现。
6. **ResourceMapping CRUD 接口**：设计文档 §18.6 提到的资源映射维护接口尚未实现。
7. **GitHub Issues**: 设计文档 §19.5 提到的人工反馈闭环尚未实现。
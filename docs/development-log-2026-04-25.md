# AIOps 告警自动分析系统开发日志
日期：2026-04-25

## 范围说明

本次只实现 `aiops-alert-analysis-development-plan.md` 中的阶段 6：`EvidenceCompressor`。

约束执行口径：

1. 不实现真实内网 Provider，继续使用现有 Provider 接口和 Mock。
2. 不提前实现阶段 7 的 `RuleBasedFallbackAnalyzer` 和阶段 8 的真实 LLM 集成。
3. 只修改阶段 6 直接涉及的 `analysis` / `persistence` / `test` 相关目录，并同步整理职责边界。

## 设计冲突说明

本次实现前确认到 1 个与阶段 6 直接相关的设计落差：

1. `aiops-alert-analysis-design.md` 第 5.5 节将 `EvidenceBundle` 描述为“原始证据聚合对象”，而阶段 6、Prompt 模板和后续阶段语义又将它当作“压缩后可送入 LLM 的证据包”。

最小修改建议：

1. 保留文档中“原始证据聚合对象”的语义。
2. 另行定义阶段 6 产物 `CompressedEvidenceBundle`，作为持久化后的结构化压缩证据包。
3. 在文档中补充一句：`EvidenceBundle` 用于原始快照聚合，`CompressedEvidenceBundle` 用于阶段 6 的 LLM 输入。

本次代码按该最小修正口径实现，没有把原始证据和压缩证据继续混放在同一包或同一类型中。

## 本次完成内容

1. 新增 `analysis.bundle` 包，明确阶段 6 的压缩证据职责边界：
   - `CompressedEvidenceBundle`
   - `EvidenceCompressor`
   - `DefaultEvidenceCompressor`
   - `StoredEvidenceBundle`
   - `EvidenceBundleType`
2. `DefaultEvidenceCompressor` 已实现以下能力：
   - 将 Provider 收集结果和阶段 5 的 `RuleAnalysisEvidence` 压缩为结构化证据包
   - 按配置限制裁剪 `errorCode`、`uid`、trace 样本和运行日志
   - 保留 uid 原值，不做脱敏
   - 为运行日志样本生成稳定 `evidenceId`
   - 优先保留被候选根因和规则发现引用的证据项，避免压缩后出现“结论引用了被裁掉的证据”
   - 将裁剪和日志截断信息写入 `insufficientEvidence`
   - 将缺失 Provider 信息透传到 `missingEvidence`
3. 新增 `EvidenceBundleRepository` 与 `JdbcEvidenceBundleRepository`，将阶段 5 和阶段 6 的证据落库到 `aiops_evidence_bundle`：
   - `rule`
   - `compressed`
4. 将 `AnalysisJobWorker` 的 `COMPRESSING_EVIDENCE` 状态接为真实执行：
   - 规则分析完成后调用 `EvidenceCompressor`
   - 按 retention 配置计算 `expire_at`
   - 持久化 `rule` 与 `compressed` evidence bundle
   - 当前最终结果仍保持阶段 7 之前的占位策略，但已优先复用阶段 6 压缩后的 `evidenceRefs`、`missingEvidence` 和 `insufficientEvidence`
5. 更新相关注释和 `package-info.java`，使“原始证据 / 压缩证据 / 结果输出 / 持久化仓储”职责分层更清晰。

## 本次包结构整理

根据“若当前任务涉及目录存在职责混放，必须在本次提交中一并整理”的约束，本次将阶段 6 的新职责固定到独立目录：

```text
com.company.aiops.analysis
  evidence     # 原始 Provider 快照
  bundle       # 阶段 6 压缩证据包
  rule         # 阶段 5 规则预分析
  result       # 最终输出结果模型

com.company.aiops.persistence.repository
  EvidenceBundleRepository
  jdbc
    JdbcEvidenceBundleRepository
```

本次没有把压缩逻辑塞回 `analysis.evidence`、`analysis.result` 或 `analysis.orchestrator`，避免继续累积职责混放。

## 测试与验证

新增单元测试：

1. `DefaultEvidenceCompressorTests`
   - 验证配置裁剪生效
   - 验证被规则引用的 evidenceId 在裁剪后仍被保留
   - 验证运行日志条数和 message 长度限制
   - 验证慢 trace 场景下使用 `max-slow-trace-sample-size`
   - 验证缺失的规则引用描述会被补成通用 `EvidenceReference`
2. `AnalysisJobWorkerTests`
   - 验证 `COMPRESSING_EVIDENCE` 阶段会真实持久化 `rule` 和 `compressed` 两类 evidence bundle
   - 验证最终占位结果已复用阶段 6 压缩后的证据索引

验证情况：

1. 已执行 `mvn "-Dtest=DefaultEvidenceCompressorTests,AnalysisJobWorkerTests" test`，通过。
2. 根据本地 MySQL 说明启动 `127.0.0.1:3307` 的实例后，已重新执行 `mvn test`，31 个测试全部通过。

## 当前注意事项

1. 阶段 6 已完成结构化压缩证据与 evidence bundle 落库，但阶段 7 的规则降级结果生成仍未实现。
2. 当前 `AnalysisResult` 仍属于“阶段 6 证据已就绪，阶段 7/8 结果生成仍占位”的状态，不应误认为完整最终结论链路已落地。
3. 全量测试仍依赖本地 MySQL；重启机器后需先按 `LOCAL_MYSQL.md` 启动 `C:\aiops-mysql` 实例。

## 下一步建议

下一阶段应进入阶段 7：`RuleBasedFallbackAnalyzer`。

建议优先做：

1. 基于 `CompressedEvidenceBundle` 和 `RuleAnalysisEvidence` 生成同构 `AnalysisResult`。
2. 区分 `RULE_BASED_ONLY` 与 `RULE_BASED_DEGRADED` 两条路径。
3. 明确复用阶段 6 的 `evidenceRefs`、`missingEvidence` 和 `insufficientEvidence`，避免重新拉取 Provider。
4. 让规则降级输出不再只是占位结果，而是可读、可审计的真实结论。

---

## 补充记录：阶段 7 规则降级分析

本次补充完成 `aiops-alert-analysis-development-plan.md` 中阶段 7 的主要内容，并将规则降级结果生成职责从 worker 中拆出到独立包，避免继续把生命周期编排与结果构造混放。

### 范围说明

1. 严格按阶段 7 边界实现 `RuleBasedFallbackAnalyzer`，不提前实现阶段 8 的真实 LLM Client / Prompt / 调用日志。
2. 不实现真实内网 Provider，继续复用阶段 4 的 Provider 接口和 Mock。
3. 只修改阶段 7 直接涉及的 `analysis` / `persistence` / `test` 相关目录，并同步整理结果生成职责边界。

### 设计冲突说明

本次实现前确认到 2 个与阶段 7 直接相关的冲突：

1. `aiops-alert-analysis-design.md` 第 18 章的阶段编号仍与开发计划和开发日志不一致；设计文档将 `RuleBasedFallbackAnalyzer` 归在更早阶段，而开发计划明确将其定义为阶段 7。本次按开发计划与最新开发日志执行。
2. 既有实现中，即使 `aiops.llm.enabled=true`，运行时默认也会直接产出 `RULE_BASED_ONLY` 占位结果；这与设计文档“仅 `enabled=false` 时返回 `RULE_BASED_ONLY`，LLM 不可用时应返回 `RULE_BASED_DEGRADED`”不一致。

最小修改建议：

1. 将设计文档第 18 章阶段编号整体对齐到开发计划，避免“阶段 3 / 4 / 7 / 8”语义继续分叉。
2. 在设计文档和实现中统一明确：`RULE_BASED_ONLY` 只用于主动关闭 LLM；`RULE_BASED_DEGRADED` 用于 LLM 超时、失败或被关键 Provider 失败触发的降级路径。

### 本次完成内容

1. 新增 `analysis.fallback` 包，明确阶段 7 的规则降级职责边界：
   - `RuleBasedFallbackAnalyzer`
   - `RuleBasedAnalysisRequest`
   - `DefaultRuleBasedFallbackAnalyzer`
2. `DefaultRuleBasedFallbackAnalyzer` 已实现以下能力：
   - 基于 `CompressedEvidenceBundle`、`RuleAnalysisEvidence` 和 `ProviderCollectionResult` 生成同构 `AnalysisResult`
   - 区分 `RULE_BASED_ONLY` 与 `RULE_BASED_DEGRADED`
   - 为 `summary`、`impact`、`rootCauses`、`recommendations`、`overallConfidence` 生成可读、可审计的规则结果
   - 优先复用阶段 6 产出的 `evidenceRefs`、`missingEvidence`、`insufficientEvidence`
   - 关键 Provider 缺失时输出 `INSUFFICIENT_CRITICAL_EVIDENCE`，而不是“LLM 不可用”这类空结果
   - 所有 `rootCauses` 都保证带 `evidenceIds`
3. 重构 `AnalysisJobWorker`：
   - `submit()` 在 `aiops.llm.enabled=false` 时走 `RULE_BASED_ONLY`
   - `submit()` 在 `aiops.llm.enabled=true` 时，当前阶段 7 默认按“LLM 超时”走 `RULE_BASED_DEGRADED`
   - 关键 Provider 失败时直接进入规则降级结果生成
   - 保留 `LLM_ASSISTED_SUCCESS` 仅作为阶段 8 之前的测试场景钩子，不作为运行时默认路径
4. 扩展 `AnalysisResult`，补齐 `ruleEngineVersion` 字段，并在 `JdbcAnalysisResultRepository` 中真实写入 `aiops_analysis_result.rule_engine_version`。
5. 更新相关注释和 `package-info.java`，使“生命周期编排 / 规则分析 / 规则降级结果生成 / 最终结果持久化”的职责分层更清晰。

### 本次包结构整理

根据“若当前任务涉及目录存在职责混放，必须在本次提交中一并整理”的约束，本次将阶段 7 的结果生成职责固定到独立目录：

```text
com.company.aiops.analysis
  rule         # 阶段 5 规则预分析
  bundle       # 阶段 6 压缩证据
  fallback     # 阶段 7 规则降级结果生成
  result       # 最终输出结果模型
  orchestrator # 生命周期编排与 worker
```

本次没有再把规则降级结果拼装逻辑堆回 `analysis.orchestrator.worker` 或 `analysis.result`，避免生命周期控制和结果构造继续耦合。

### 测试与验证

新增或更新测试：

1. `DefaultRuleBasedFallbackAnalyzerTests`
   - 验证 `RULE_BASED_ONLY` 场景能基于压缩证据生成真实规则结论
   - 验证关键 Provider 缺失时能生成 `RULE_BASED_DEGRADED` 结果与 `INSUFFICIENT_CRITICAL_EVIDENCE`
2. `AnalysisJobWorkerTests`
   - 验证 worker 会将阶段 7 请求委托给 `RuleBasedFallbackAnalyzer`
   - 验证 `submit()` 会根据 `aiops.llm.enabled` 选择 `RULE_BASED_ONLY` 或 `LLM_FALLBACK_TIMEOUT`
3. `AiopsAlertAnalysisApplicationTests`
   - 验证默认配置下 webhook 运行路径已从旧的占位 `RULE_BASED_ONLY` 切换为 `RULE_BASED_DEGRADED`
   - 验证结果中写入 `llmStatus=TIMEOUT`
   - 验证 `rule_engine_version=fallback-rules-v1`

验证情况：

1. 已执行 `mvn -q -DskipTests test-compile`，通过。
2. 已执行 `mvn test`，35 个测试全部通过。

### 当前注意事项

1. 阶段 7 已完成真实规则降级结果生成，但阶段 8 的真实 LLM 集成仍未实现；当前 `LLM_ASSISTED` 仍仅用于测试生命周期分支。
2. 运行时默认配置 `aiops.llm.enabled=true` 下，会进入“模拟 LLM 超时 -> 规则降级”的阶段 7 路径；这是当前阶段边界内的刻意实现，不代表真实 LLM 已接入。
3. 全量测试仍依赖本地 MySQL；重启机器后需先按 `LOCAL_MYSQL.md` 启动 `C:\aiops-mysql` 实例。

### 下一步建议

下一阶段应进入阶段 8：LLM 集成。

建议优先做：

1. 定义 `LlmClient` 抽象与 Mock，实现真实的 `LLM_ANALYZING` 分支。
2. 基于阶段 6 压缩证据构建 Prompt，并校验输出中的 `evidenceIds`。
3. 将当前“模拟 TIMEOUT”路径替换为真实 LLM 调用失败后的降级分支。
4. 落地 `aiops_llm_call_log` 写入，并补齐阶段 8 的集成测试。

---

## 补充记录：阶段 8 LLM 集成

本次补充完成 `aiops-alert-analysis-development-plan.md` 中阶段 8 的主要内容，并将阶段 7 中留在 worker 内的“模拟 LLM 成功 / 模拟 LLM 超时”占位路径替换为真实的阶段 8 编排：Prompt 构造、LLM Client 选择、输出校验、一次重试、调用日志落库，以及失败后的规则降级。

### 范围说明

1. 严格按阶段 8 边界实现 `LlmClient` / `PromptBuilder` / `LlmOutputValidator` / `LlmAnalysisService` / `aiops_llm_call_log`。
2. 不实现真实内网 Provider，继续复用既有 Provider 接口与 Mock。
3. 不接真实内网模型；默认运行配置切到 `mock`，仅保留 `OpenAI-compatible` 适配器接口与最小实现骨架。
4. 只修改阶段 8 直接涉及的 `analysis.orchestrator.worker` / `llm` / `persistence.repository` / `common.config` / `test` 目录，并同步整理职责边界。

### 设计冲突说明

本次实现前确认到 2 个与阶段 8 直接相关的冲突：

1. 设计文档与既有默认配置将 `aiops.llm.provider` 指向 `openai-compatible`，但当前任务约束要求本地只实现接口与 Mock，不接真实外部模型端点；同时原配置也缺少真实适配所需的 `baseUrl` / `apiKey`。
2. 设计文档 12.7 后的说明写的是“LLM 生成的 rootCause 补 `source=LLM`”，但设计文档第 20 章的正常示例输出又使用 `source=MERGED`；当前 `AnalysisResult` 也更接近最终合并视图而非原始模型输出。

最小修改建议：

1. 在文档和配置中明确：阶段 8 本地默认 provider 为 `mock`；`openai-compatible` 仅作为可选适配器，需要额外补充 `baseUrl` / `apiKey` 后才允许启用。
2. 在文档中补充区分“原始 LLM payload”与“最终 AnalysisResult”：前者可视为 `LLM`，后者在系统合并证据和外层字段后允许使用 `MERGED`。

### 本次完成内容

1. 新增 `llm` 分层目录，明确阶段 8 职责拆分：
   - `llm.client`
   - `llm.client.mock`
   - `llm.client.openai`
   - `llm.prompt`
   - `llm.schema`
   - `llm.validator`
   - `llm.service`
2. 定义并实现 LLM 调用抽象：
   - `LlmClient`
   - `LlmRequest`
   - `LlmResponse`
   - `LlmMessage`
   - `LlmCallStatus`
   - `LlmClientException`
3. 实现 `MockLlmClient`：
   - 基于阶段 6 的 `CompressedEvidenceBundle` 生成稳定 JSON 输出
   - 支持通过 serviceName token 稳定复现 `TIMEOUT`、`INVALID_JSON`、`SCHEMA_VALIDATION_FAILED`、`INVALID_EVIDENCE_REFERENCE` 和“首轮失败、重试成功”等场景
4. 实现 `OpenAiCompatibleLlmClient` 最小适配骨架：
   - 通过 `RestClient` 发送 OpenAI-compatible 请求
   - 未配置 `baseUrl` / `apiKey` 时显式返回 `UNAVAILABLE`
   - 不作为当前默认运行路径
5. 实现 Prompt 构造：
   - `RpcErrorRatePromptBuilder`
   - `RpcLatencyPromptBuilder`
   - 支持在首次校验失败后，向唯一一次重试请求追加格式修复提示
6. 实现输出校验：
   - `DefaultLlmOutputValidator`
   - 校验 JSON 可解析
   - 校验必填字段、数组上限、枚举和值域
   - 校验 `rootCauses[*].evidenceIds` 必须存在于压缩证据包
   - 未知 `causeType` 统一映射为 `UNKNOWN`
7. 实现 `DefaultLlmAnalysisService`：
   - 根据 `aiops.llm.provider` 选择 `LlmClient`
   - 根据告警类别选择 `PromptBuilder`
   - 实现“失败后最多重试一次”的阶段 8 规则
   - 成功时生成真实 `LLM_ASSISTED` 结果
   - 失败时向 worker 抛出规范化 `LlmAnalysisException`，由 worker 转入 `RULE_BASED_DEGRADED`
8. 落地 `aiops_llm_call_log`：
   - 新增 `LlmCallLogRepository`
   - 新增 `JdbcLlmCallLogRepository`
   - 记录 request / response / tokens / latency / callStatus / errorMessage / expireAt
9. 重构 `AnalysisJobWorker`：
   - 删除阶段 7 遗留的 `LLM_ASSISTED_SUCCESS` / `LLM_FALLBACK_TIMEOUT` 模拟分支
   - 将 `LLM_ANALYZING -> GENERATING_RESULT` 的正常路径接到真实 `LlmAnalysisService`
   - 将 LLM 校验失败或调用失败后的路径接到真实规则降级分支
10. 补充和更新公共类型、复杂流程与包级注释，避免阶段 8 的新目录再次出现职责混放。

### 本次包结构整理

根据“若当前任务涉及目录存在职责混放，必须在本次提交中一并整理”的约束，本次将阶段 8 的新增职责固定到独立目录：

```text
com.company.aiops
  analysis.orchestrator.worker   # 生命周期编排，只保留状态推进与分支控制
  llm.client                     # 模型调用抽象
  llm.client.mock                # Mock LLM
  llm.client.openai              # OpenAI-compatible 适配骨架
  llm.prompt                     # 分类 Prompt 构造
  llm.schema                     # 阶段 8 输出约束
  llm.validator                  # JSON / schema / evidenceId 校验
  llm.service                    # 阶段 8 总编排
  persistence.repository         # LLM 调用日志仓储接口
  persistence.repository.jdbc    # LLM 调用日志 JDBC 实现
```

本次没有把 Prompt、校验、重试、日志记录继续堆进 `AnalysisJobWorker` 或 `analysis.result`，避免生命周期编排、模型调用和结果建模继续耦合。

### 测试与验证

新增或更新测试：

1. `DefaultLlmOutputValidatorTests`
   - 验证未知 `causeType` 会映射为 `UNKNOWN`
   - 验证不存在的 `evidenceId` 会被拒绝
2. `DefaultLlmAnalysisServiceTests`
   - 验证首次 `INVALID_JSON` 后会重试一次
   - 验证重试成功后返回真实 `LLM_ASSISTED`
3. `AnalysisJobWorkerTests`
   - 验证 worker 会将阶段 8 正常路径委托给 `LlmAnalysisService`
   - 验证 LLM 失败后会进入 `RuleBasedFallbackAnalyzer`
   - 验证 `submit()` 会根据 `aiops.llm.enabled` 选择 `RULE_BASED_ONLY` 或 `USE_CONFIGURED_LLM`
4. `AnalysisJobStateMachineIntegrationTests`
   - 验证真实 `LLM_ASSISTED` 生命周期
   - 验证 `INVALID_JSON` 两次失败后转入 `RULE_BASED_DEGRADED`
   - 验证 `aiops_llm_call_log` 写入次数
5. `AiopsAlertAnalysisApplicationTests`
   - 验证默认 webhook 正常路径返回 `LLM_ASSISTED`
   - 验证非关键 Provider 失败时仍允许 `LLM_ASSISTED`，但结果带 `degraded=true`
   - 验证关键 Provider 缺失时不进入 LLM
   - 验证 `INVALID_JSON` 重试失败后返回 `RULE_BASED_DEGRADED`

验证情况：

1. 已执行 `mvn -q -DskipTests test-compile`，通过。
2. 已执行 `mvn test`，41 个测试全部通过。

### 当前注意事项

1. 阶段 8 已完成真实 LLM 编排，但当前默认仍只走 `MockLlmClient`；这满足当前任务约束，不代表已接入真实外部模型。
2. `OpenAiCompatibleLlmClient` 仅为适配骨架；若后续要启用真实端点，需明确补齐 `baseUrl` / `apiKey`、环境隔离和脱敏审计策略。
3. 当前已严格校验 `evidenceIds`，但设计文档中“输出不得出现证据包外的 serviceName / interfaceName / traceId / errorCode / resourceId”仍缺少完全结构化的可机校字段，后续若要做严格校验，建议先把这些引用升级为显式结构字段。
4. 全量测试仍依赖本地 MySQL；重启机器后需先按 `LOCAL_MYSQL.md` 启动 `C:\aiops-mysql` 实例。

### 下一步建议

下一阶段应进入阶段 9：查询接口。

建议优先做：

1. 实现 `GET /api/v1/analysis/tasks/{taskId}` 和 `GET /api/v1/analysis/latest`。
2. 让查询接口完整返回 `analysisMode`、`degraded`、`degradedReason`、`degradedByProviderFailure`、`missingEvidence`、`rootCauses`、`recommendations`。
3. 补齐 `aiops_llm_call_log` 与最终结果的联查调试能力，方便后续排查模型输出问题。
4. 在进入真实模型接入前，先把文档中的 provider 默认值、`source` 语义和阶段编号冲突收敛掉。

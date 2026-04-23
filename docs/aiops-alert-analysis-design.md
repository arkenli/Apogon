# AIOps 告警自动分析系统开发文档

版本：v0.1
日期：2026-04-21
技术栈默认：JDK 21 + Spring Boot + MySQL

## 1. 背景与目标

现有监控告警系统已经具备线上微服务、容器、中间件、接口日志、链路日志、运行日志等数据的存储和查询能力，但收到告警后仍依赖人工排查，导致定位和响应不及时。

本系统用于在收到 WiseEye 告警 Webhook 后，自动完成告警解析、分类、数据采集、规则预分析、LLM 辅助分析、结论持久化，并通过接口返回结构化分析结果。IM 输出由外部模块调用本系统接口完成。

### 1.1 一期目标

1. 支持通过 Webhook 接收 WiseEye 告警。
2. 支持 RPC 接口错误率告警和 RPC 接口时延告警。
3. 支持按告警类别路由到不同分析策略。
4. 支持抽象现有监控、日志、Trace、资源映射、基线数据 HTTP 查询能力。
5. 支持抽象 LLM 调用能力，便于切换 OpenAI-compatible、内部模型或本地模型。
6. 支持规则预分析和 LLM 分析结合，输出带证据、带置信度、可审计的分析报告。
7. 支持 MySQL 持久化告警、任务、证据摘要、LLM 输入输出、分析结果。
8. 支持相同告警 5 分钟内去重合并，避免重复调用 LLM。
9. 分析任务异步执行，目标在告警收到后 1-3 分钟内完成。

### 1.2 非目标

1. 不执行自动处置动作，例如自动重启、扩容、降级、屏蔽 errorcode。
2. 不直接适配内网真实 HTTP 接口，只定义 Provider 抽象和数据契约。
3. 不开发前端页面。
4. 不依赖 LLM 直接读取无限量原始日志做自由推理。
5. 不要求一期实现 100% 根因准确率，目标是显著减少人工排查时间。

## 2. 已确认约束与默认假设

1. 告警接入方式：Webhook。
2. 输出方式：分析结果存储到 MySQL，并通过接口返回；IM 推送由外部模块完成。
3. 服务资源映射：可以获取服务所属数据库、DCS、MQ 实例映射。
4. 服务上下游依赖和完整调用拓扑：暂不可直接获取。
5. Trace 能力：可以通过 traceId 获取链路中所有接口日志，接口日志包含 spanId 和 parentSpanId，可推断调用关系。
6. 基线数据：可获取。
7. 日志脱敏：已脱敏或匿名化，允许送入 LLM。
8. LLM：通过抽象接口适配不同模型。
9. 自动处置：只给建议，不执行动作。
10. Webhook 鉴权：暂不需要。
11. 去重规则：同一 `serviceName + interfaceName + category + cluster` 5 分钟内合并。
12. 存储周期：分析结果长期保存；原始证据包、LLM 输入输出保存 30 天。
13. LLM 失败降级：执行基于规则的降级分析策略，返回同构分析结果，并明确标注结论来源为规则降级策略。

## 3. 总体架构

```text
WiseEye Webhook
    |
    v
AlertWebhookController
    |
    v
AlertIngestionService
    |-- AlertParser
    |-- AlertClassifier
    |-- AlertDeduplicationService
    |
    v
AnalysisJobService
    |
    v
AnalysisOrchestrator
    |
    |-- AnalysisStrategyRegistry
    |       |-- RpcErrorRateAnalysisStrategy
    |       |-- RpcLatencyAnalysisStrategy
    |
    |-- Data Providers
    |       |-- MetricProvider
    |       |-- InterfaceLogProvider
    |       |-- TraceProvider
    |       |-- RuntimeLogProvider
    |       |-- BaselineProvider
    |       |-- ResourceMappingProvider
    |
    |-- RuleAnalyzers
    |       |-- ErrorRateRuleAnalyzer
    |       |-- LatencyRuleAnalyzer
    |       |-- MetricAnomalyDetector
    |       |-- TraceTopologyAnalyzer
    |
    |-- EvidenceCompressor
    |-- LlmAnalysisService
    |       |-- LlmClient
    |       |-- PromptBuilder
    |       |-- LlmOutputValidator
    |
    v
MySQL
    |
    v
AnalysisQueryController
```

核心原则：

1. 规则负责确定性判断，LLM 负责综合归纳、解释和生成排查建议。
2. LLM 输入必须是结构化证据包，而不是无边界的原始数据。
3. LLM 输出必须是严格 JSON，并通过 Schema 校验。
4. 每个结论必须引用证据，不能接受没有证据来源的根因判断。
5. 所有外部数据源和模型调用都通过接口抽象，避免业务逻辑依赖具体 HTTP 实现。

## 4. 模块划分

建议包结构：

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

### 4.1 alert 模块

职责：

1. 接收 Webhook。
2. 存储原始告警文本。
3. 解析告警结构化字段。
4. 分类告警。
5. 执行去重合并。
6. 创建异步分析任务。

### 4.2 analysis 模块

职责：

1. 编排分析任务生命周期。
2. 根据告警类别选择分析策略。
3. 调用数据 Provider 获取证据。
4. 执行规则预分析。
5. 压缩证据并调用 LLM。
6. 合并规则结论和 LLM 结论。
7. 生成最终分析结果。

### 4.3 provider 模块

职责：

1. 定义现有监控、日志、Trace、基线、资源映射数据源的抽象接口。
2. 一期可以提供 mock 或 stub 实现。
3. 后续在内网通过 HTTP 适配真实接口。

### 4.4 llm 模块

职责：

1. 抽象大模型调用接口。
2. 构造不同告警类型的 Prompt。
3. 控制 token 和日志样本数量。
4. 校验 LLM JSON 输出。
5. 处理模型失败、超时、格式错误降级。

### 4.5 persistence 模块

职责：

1. MySQL 表结构映射。
2. 告警、任务、证据、LLM 调用、分析结果持久化。
3. 支持按 alertId、taskId、serviceName、interfaceName 查询。

## 5. 核心领域模型

### 5.1 AlertCategory

```java
public enum AlertCategory {
    RPC_ERROR_RATE,
    RPC_LATENCY,
    UNKNOWN
}
```

后续新增告警类型时，只需要新增枚举、分类规则和对应策略。

### 5.2 AlertEvent

```java
public record AlertEvent(
    String alertId,
    AlertCategory category,
    String status,
    String severity,
    String serviceName,
    String interfaceName,
    String cluster,
    String site,
    String tenant,
    String application,
    String alertName,
    String metricName,
    String rawCategory,
    String rawText,
    Instant metricOccurTime,
    Instant receivedAt,
    AlertTriggerValues triggerValues
) {}
```

`AlertTriggerValues` 定义：

```java
public record AlertTriggerValues(
    Double requestCount,
    Double failRate,
    Long avgLatencyMs,
    Long p99LatencyMs,
    Long maxLatencyMs,
    String thresholdExpression,
    String rawMetricValueText
) {}
```

说明：

1. 不使用 `Map<String, Object>`，避免字段语义不清、后续代码大量强制类型转换，也便于 AI 和开发人员按明确字段实现。
2. 不同告警类型只填充相关字段，例如错误率告警填充 `requestCount` 和 `failRate`，时延告警填充 `requestCount`、`avgLatencyMs` 或 `p99LatencyMs`。
3. 无法结构化解析的原始指标片段放入 `rawMetricValueText`，后续新增字段时通过扩展 `AlertTriggerValues` 明确建模。

`triggerValues` 示例：

```json
{
  "requestCount": 49,
  "failRate": 1.0,
  "avgLatencyMs": 3067,
  "p99LatencyMs": null,
  "maxLatencyMs": null,
  "thresholdExpression": "avg_latency > 3000ms for 5m",
  "rawMetricValueText": "平均时延=3067, 请求总数=49.0"
}
```

### 5.3 AnalysisJob

```java
public record AnalysisJob(
    String taskId,
    String alertId,
    AlertCategory category,
    String dedupeKey,
    AnalysisJobStatus status,
    String currentStep,
    String statusReason,
    int retryCount,
    long stateVersion,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    String failureReason
) {}
```

分析任务必须使用显式状态机管理生命周期，每一步执行前后都要更新 DB 状态，便于排障、恢复和统计耗时。

状态枚举：

```text
PENDING
RUNNING
COLLECTING_EVIDENCE
RULE_ANALYZING
COMPRESSING_EVIDENCE
LLM_ANALYZING
RULE_FALLBACK_ANALYZING
GENERATING_RESULT
SUCCESS
FAILED
DEGRADED_SUCCESS
SKIPPED_DUPLICATE
```

状态说明：

1. `PENDING`：任务已创建，等待异步执行。
2. `RUNNING`：任务开始执行，用于标记整体运行态。
3. `COLLECTING_EVIDENCE`：正在并行调用 Provider 获取指标、日志、Trace、基线、资源映射。
4. `RULE_ANALYZING`：正在执行规则预分析。
5. `COMPRESSING_EVIDENCE`：正在生成 LLM 输入证据包。
6. `LLM_ANALYZING`：正在调用大模型并校验输出。
7. `RULE_FALLBACK_ANALYZING`：LLM 不可用或输出不合法，正在执行规则降级策略。
8. `GENERATING_RESULT`：正在合并结论并生成最终结果。
9. `SUCCESS`：正常完成，通常 `analysisMode=LLM_ASSISTED` 或 `RULE_BASED_ONLY`。
10. `DEGRADED_SUCCESS`：降级完成，例如 LLM 不可用或 Provider 部分失败。
11. `FAILED`：告警关键字段缺失、告警类型不支持、状态机执行异常，导致无法生成任何有效结果。
12. `SKIPPED_DUPLICATE`：可选状态。默认实现不为 5 分钟内重复告警创建新任务，只写 `aiops_duplicate_alert_link` 并返回已有 taskId；如果后续需要为每条重复告警保留任务记录，可创建状态为 `SKIPPED_DUPLICATE` 的影子任务。

### 5.4 AnalysisContext

```java
public record AnalysisContext(
    AlertEvent alert,
    TimeWindow alarmWindow,
    TimeWindow baselineWindow,
    ResourceMapping resourceMapping,
    AnalysisOptions options
) {}
```

默认时间窗口：

1. 错误率告警：`occurTime - 5m` 到 `occurTime + 2m`。
2. 时延告警：`occurTime - 10m` 到 `occurTime + 2m`。
3. 基线：默认取前一天同时间段，避免最近 7 天大范围查询导致接口慢或查不出数据。

通用 DTO：

```java
public record TimeWindow(
    Instant startTime,
    Instant endTime
) {}

public record AnalysisOptions(
    int maxInterfaceLogLimit,
    int maxTraceSampleSize,
    int maxSlowTraceSampleSize,
    int maxRuntimeLogSize,
    int runtimeLogMessageMaxChars,
    int maxErrorCodeSize,
    int maxUidSize,
    boolean llmEnabled,
    boolean providerParallelEnabled
) {}
```

### 5.5 EvidenceBundle

```java
public record EvidenceBundle(
    AlertEvent alert,
    BaselineEvidence baseline,
    InterfaceLogEvidence interfaceLogs,
    TraceEvidence traces,
    RuntimeLogEvidence runtimeLogs,
    MetricEvidence metrics,
    ResourceMapping resourceMapping,
    RuleAnalysisEvidence ruleAnalysis
) {}
```

证据 DTO 落地约束：

1. `BaselineEvidence` 直接包装 `InterfaceBaseline` 和缺失原因。
2. `InterfaceLogEvidence` 至少包含总请求数、失败数、失败率、时延分布、errorCode/errorMsg 聚合、uid 聚合和样本日志。
3. `TraceEvidence` 至少包含采样 traceId、每条 trace 的关键 span、首个失败 span、最慢 span 和下游聚合。
4. `RuntimeLogEvidence` 至少包含按 traceId 和 serviceName 查询到的 WARN/ERROR 日志样本。
5. `MetricEvidence` 至少包含容器、CSE、DB、DCS、MQ 的指标异常列表和 Provider 缺失信息。
6. `RuleAnalysisEvidence` 至少包含规则发现列表、候选根因分数和 evidenceId 索引。
7. 每个 Evidence 子对象都要能表达“数据缺失”，不能用 null 让后续分析器猜测是无异常还是未查询到。

### 5.6 AnalysisResult

```java
public record AnalysisResult(
    String taskId,
    String alertId,
    AlertCategory category,
    String analysisMode,
    String summary,
    ImpactAssessment impact,
    List<RootCauseHypothesis> rootCauses,
    List<Recommendation> recommendations,
    List<EvidenceReference> evidenceRefs,
    List<MissingEvidence> missingEvidence,
    List<String> insufficientEvidence,
    HistoricalReference historicalReference,
    boolean llmUsed,
    String llmStatus,
    boolean degraded,
    boolean degradedByProviderFailure,
    String degradedReason,
    double overallConfidence,
    Instant generatedAt
) {}
```

`analysisMode` 取值：

```text
LLM_ASSISTED
RULE_BASED_DEGRADED
RULE_BASED_ONLY
```

说明：

1. `LLM_ASSISTED`：规则预分析 + LLM 综合分析，正常路径。
2. `RULE_BASED_DEGRADED`：LLM 不可用、超时或输出校验失败后，由规则降级策略生成结论。
3. `RULE_BASED_ONLY`：主动关闭 LLM 时，仅使用规则策略分析，非异常降级。
4. 当 `analysisMode=RULE_BASED_DEGRADED` 时，接口返回和 IM 展示必须明确标注“该结论由规则降级策略生成，未使用大模型”。

```java
public record MissingEvidence(
    String providerName,
    String reason,
    boolean critical,
    String impact
) {}

public record HistoricalReference(
    boolean matched,
    String alertId,
    String taskId,
    String analysisMode,
    String summary,
    double overallConfidence,
    Instant generatedAt
) {}
```

```java
public record ImpactAssessment(
    String level,
    String description
) {}

public record Recommendation(
    String priority,
    String actionType,
    String description,
    String target
) {}

public record EvidenceReference(
    String evidenceId,
    String evidenceType,
    String description
) {}
```

### 5.7 RootCauseHypothesis

```java
public record RootCauseHypothesis(
    String causeType,
    String description,
    double confidence,
    List<String> evidenceIds,
    String source,
    String reasoning
) {}
```

`source` 取值：

```text
LLM
RULE_ENGINE
MERGED
```

`causeType` 建议枚举：

```text
BUSINESS_ERROR_CODE_NOT_MASKED
LOW_TRAFFIC_LIMITED_IMPACT
DOWNSTREAM_RPC_FAILURE
DOWNSTREAM_RPC_LATENCY
DATABASE_SLOW_QUERY
DATABASE_RESOURCE_PRESSURE
DCS_LATENCY_OR_FAILURE
MQ_BACKLOG_OR_LATENCY
CONTAINER_CPU_PRESSURE
CONTAINER_MEMORY_PRESSURE
GC_PRESSURE
INSTANCE_STARTING_OR_FAILED
SINGLE_INSTANCE_ABNORMAL
TRAFFIC_SPIKE
UNKNOWN
```

## 6. Webhook 与查询接口设计

### 6.1 接收告警

```http
POST /api/v1/alerts/webhook
Content-Type: text/plain
```

请求体为 WiseEye 原始告警文本。

返回：

```json
{
  "alertId": "521932116",
  "taskId": "task_20260421103831_xxx",
  "status": "PENDING",
  "duplicate": false,
  "historicalReference": {
    "matched": true,
    "alertId": "521600500",
    "taskId": "task_20260420181340_xxx",
    "summary": "历史告警疑似业务 errorCode 未屏蔽。",
    "overallConfidence": 0.78
  },
  "message": "analysis task accepted"
}
```

`historicalReference` 只表示 5 分钟外存在相同 dedupeKey 的历史完成结果，供值班人员参考；本次告警仍会创建新的分析任务。

如果 5 分钟内存在相同 dedupeKey 的运行中或已完成任务：

```json
{
  "alertId": "521932116",
  "taskId": "task_existing",
  "status": "RUNNING",
  "duplicate": true,
  "message": "duplicate alert merged into existing analysis task"
}
```

### 6.2 查询分析任务

```http
GET /api/v1/analysis/tasks/{taskId}
```

返回：

```json
{
  "taskId": "task_20260421103831_xxx",
  "alertId": "521932116",
  "status": "SUCCESS",
  "category": "RPC_LATENCY",
  "createdAt": "2026-04-21T10:38:31+08:00",
  "finishedAt": "2026-04-21T10:39:42+08:00",
  "result": {
    "analysisMode": "LLM_ASSISTED",
    "degraded": false,
    "summary": "接口平均时延升高，主要慢点疑似集中在下游缓存访问。",
    "overallConfidence": 0.76
  }
}
```

### 6.3 查询告警最新分析结果

```http
GET /api/v1/analysis/latest?alertId=521932116
```

### 6.4 按服务和接口查询历史分析

```http
GET /api/v1/analysis/history?serviceName=ams_front&interfaceName=vmall-ams.PrizeActivityService.queryPrizeResult&limit=20
```

## 7. 告警解析与分类

### 7.1 AlertParser

```java
public interface AlertParser {
    AlertEvent parse(String rawText);
}
```

解析优先级：

1. 从 `Alert ID:` 提取 alertId。
2. 从 `Category:` 提取 rawCategory。
3. 从 `Host:` 后面的 JSON 提取 serviceName、interfaceName、cluster。
4. 从 `Details:` 中提取请求数、失败率、平均时延、P99、告警发生时间。
5. 从 `Occur Time:` 提取告警展示时间。
6. 保留 rawText，便于后续审计。

注意：

1. 告警文本中 serviceName 可能出现 `servicename`、`服务名称`、`serviceName` 多种写法。
2. interfaceName 可能包含 `://`、`/`、`.`、`_` 等特殊字符，不能使用过窄正则。
3. `Details` 中的发生时间通常比 `Occur Time` 更接近指标触发时间，应优先作为 `metricOccurTime`。

### 7.2 AlertClassifier

```java
public interface AlertClassifier {
    AlertCategory classify(AlertEvent alert);
}
```

规则：

1. `rawCategory == "AIOps@rate"` -> `RPC_ERROR_RATE`。
2. `rawCategory == "AIOps@delay"` -> `RPC_LATENCY`。
3. `metricName` 包含 `failrate`、`successrate`、`error` -> `RPC_ERROR_RATE`。
4. `metricName` 包含 `delay`、`latency`、`p99`、`avg` -> `RPC_LATENCY`。
5. 以上都不匹配 -> `UNKNOWN`。

LLM 分类只作为后续增强，不作为主路径。一期遇到 `UNKNOWN` 应记录任务失败或返回不支持分析。

### 7.3 去重规则

```text
dedupeKey = category + "|" + serviceName + "|" + interfaceName + "|" + cluster
dedupeWindow = 5 minutes
```

如果窗口内已有 `PENDING`、`RUNNING`、`SUCCESS`、`DEGRADED_SUCCESS` 任务，则新告警关联到旧任务，不重复分析。

如果 5 分钟窗口内没有命中重复告警，但历史上存在相同 `dedupeKey` 且已经处理完成的告警，不阻塞本次分析，也不复用历史结论；系统应在本次分析结果中附加历史参考信息，供值班人员判断是否为重复问题。

历史参考信息建议返回：

```json
{
  "historicalReference": {
    "matched": true,
    "lookback": "P1D",
    "alertId": "521600500",
    "taskId": "task_20260420181340_xxx",
    "analysisMode": "LLM_ASSISTED",
    "summary": "历史告警疑似业务 errorCode 未屏蔽。",
    "overallConfidence": 0.78,
    "generatedAt": "2026-04-20T18:15:00+08:00"
  }
}
```

默认只查询最近 1 天的历史结果；该窗口应做成配置项，避免无限制扫描历史表。

## 8. 数据 Provider 抽象

Provider 分为两类：

1. 外部数据 Provider：监控指标、接口日志、Trace、运行日志、基线数据。一期只定义接口和 DTO，不实现真实内网 HTTP 调用，可提供 Mock 或 Stub。
2. 本地数据 Provider：ResourceMappingProvider。一期必须实现，从 MySQL 预置表 `aiops_resource_mapping` 读取服务到 DB/DCS/MQ 的映射关系。

### 8.0 Provider 并行调用与降级方案

Provider 之间没有管理或数据依赖关系时，应并行调用以降低整体分析耗时。例如容器指标、数据库指标、DCS 指标、MQ 指标、基线数据、接口日志可以在同一阶段并行获取；Trace 明细依赖接口日志中的 traceId，应在接口日志返回后再并行查询。

推荐执行模型：

```text
阶段 1：并行获取 ResourceMapping、Baseline、InterfaceLog、ContainerMetrics、CseMetrics
阶段 2：ResourceMapping 返回后，并行获取 DatabaseMetrics、DcsMetrics、MqMetrics
阶段 3：InterfaceLog 返回后，抽样 traceId，并行获取 Trace 和 RuntimeLog
阶段 4：汇总 ProviderResult，进入规则预分析
```

Provider 返回值建议统一包装：

```java
public record ProviderResult<T>(
    String providerName,
    ProviderStatus status,
    T data,
    String errorCode,
    String errorMessage,
    Duration latency
) {}

public enum ProviderStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    TIMEOUT,
    SKIPPED
}
```

当某个 Provider 数据获取失败时，以下方案作为评审选项保留；在没有新的评审结论前，开发按本节后面的“一期默认落地口径”实现。

| 方案 | 处理方式 | 优点 | 风险 | 适用场景 |
| --- | --- | --- | --- | --- |
| 方案 A：部分证据继续分析 | 失败 Provider 标记为 missingEvidence，其它 Provider 正常分析并输出结论 | 分析时效最好，单点 Provider 故障不阻塞整体 | 结论置信度可能下降 | 推荐作为一期默认方案 |
| 方案 B：关键 Provider 失败则降级 | 为不同告警类型定义关键 Provider，例如错误率告警必须有接口日志，时延告警必须有接口日志和 Trace；关键 Provider 失败则输出“证据不足”的规则降级结果 | 避免在核心证据缺失时给出误导性根因 | 可能降低自动定位覆盖率 | 对准确性要求高于时效的场景 |
| 方案 C：短重试 + 部分证据继续分析 | Provider 失败后按小间隔重试 1-2 次，仍失败则按方案 A 继续 | 可缓解瞬时网络抖动 | 增加分析耗时 | Provider 偶发超时较多但 1-3 分钟 SLA 仍可接受 |
| 方案 D：历史证据补偿 | 当前 Provider 失败时，读取最近一次同 dedupeKey 的成功证据作为参考，并明确标注为 historicalEvidence | 对反复出现的告警有帮助 | 历史证据可能过期，不能作为当前事实 | 重复告警、周期性问题 |

无论选择哪种方案，最终结果必须包含：

1. `missingEvidence`：缺失的 Provider 和失败原因。
2. `degradedByProviderFailure`：是否因 Provider 失败导致降级。
3. 降低后的 `overallConfidence`。
4. 明确说明“哪些结论没有对应 Provider 证据支撑”。

推荐一期采用方案 A + 方案 C：关键路径不被单个非核心 Provider 阻塞，同时对超时做短重试。对于接口日志 Provider，建议按方案 B 处理，因为错误率和时延分析都强依赖接口日志。

为了避免开发时对“关键 Provider 失败”理解不一致，一期默认采用以下落地口径：

1. 告警解析失败、无法得到 `serviceName`、`interfaceName`、`category`、`occurTime` 时，任务进入 `FAILED`。
2. InterfaceLogProvider 失败时，任务不直接 `FAILED`，而是进入规则降级结果：`analysisMode=RULE_BASED_DEGRADED`，主根因为 `UNKNOWN`，明确输出“接口日志缺失，无法定位 errorCode、uid、trace 和接口耗时分布”，任务状态为 `DEGRADED_SUCCESS`。
3. TraceProvider 失败时，错误率告警仍可基于接口日志、errorMsg 和资源指标分析；时延告警需要降低下游慢点判断置信度，并输出 missingEvidence。
4. MetricProvider、BaselineProvider、RuntimeLogProvider 部分失败时，继续分析，降低相关根因置信度。
5. ResourceMappingProvider 失败通常表示本地配置缺失或数据库异常。服务资源映射缺失时继续分析接口日志和容器指标，但跳过 DB/DCS/MQ 指标；ResourceMapping 表不可读时标记为 Provider 失败。

关键性矩阵：

| Provider | 错误率告警 | 时延告警 | 失败后的默认处理 |
| --- | --- | --- | --- |
| InterfaceLogProvider | 关键 | 关键 | 输出规则降级结果，状态 `DEGRADED_SUCCESS` |
| TraceProvider | 重要 | 关键 | 错误率继续分析；时延输出降级结果或显著降低下游判断置信度 |
| RuntimeLogProvider | 可选 | 可选 | 记录 missingEvidence，继续分析 |
| MetricProvider.Container/CSE | 重要 | 重要 | 记录 missingEvidence，继续分析 |
| MetricProvider.DB/DCS/MQ | 可选，依赖 ResourceMapping | 重要，依赖 ResourceMapping | 映射存在但指标失败时记录 missingEvidence，继续分析 |
| BaselineProvider | 重要 | 重要 | 记录 missingEvidence，继续分析，不做基线突增判断 |
| ResourceMappingProvider | 重要 | 重要 | 映射缺失时跳过依赖资源指标；表不可读时记录 Provider 失败 |

### 8.1 MetricProvider

```java
public interface MetricProvider {
    ContainerMetrics getContainerMetrics(String serviceName, String cluster, TimeWindow window);

    DatabaseMetrics getDatabaseMetrics(List<String> dbInstanceIds, TimeWindow window);

    DcsMetrics getDcsMetrics(List<String> dcsInstanceIds, TimeWindow window);

    MqMetrics getMqMetrics(List<String> mqInstanceIds, TimeWindow window);

    CseMetrics getCseMetrics(String serviceName, String cluster, TimeWindow window);
}
```

容器指标至少包含：

1. CPU 使用率：平均、多实例最大值、实例明细。
2. 内存使用率：平均、多实例最大值、实例明细。
3. 正在启动或启动失败实例数量。
4. YoungGC 次数、FullGC 次数。
5. CSE/RPC 框架线程池或连接使用率。

数据库指标至少包含：

1. CPU 使用率。
2. 内存使用率。
3. 连接数使用率。
4. 当前活跃连接数。
5. 慢 SQL 次数。
6. 磁盘使用率。
7. 网络输入/输出吞吐量。
8. 存储写最大时延。
9. InnoDB 行锁数、行锁等待数。
10. 慢日志个数。
11. 慢 SQL 样例或 SQL 指纹。

DCS 指标至少包含：

1. CPU 使用率。
2. 内存使用率。
3. TPS 峰值。
4. 慢查询比例。
5. 请求 P99 时延。
6. 请求平均时延。
7. 客户端调用失败次数。

### 8.2 InterfaceLogProvider

```java
public interface InterfaceLogProvider {
    List<InterfaceLogEntry> queryByInterface(
        String serviceName,
        String interfaceName,
        TimeWindow window,
        int limit
    );
}
```

```java
public record InterfaceLogEntry(
    String serviceName,
    String interfaceName,
    String traceId,
    String spanId,
    String parentSpanId,
    String uid,
    boolean success,
    String errorCode,
    String errorMsg,
    long latencyMs,
    Instant timestamp
) {}
```

### 8.3 TraceProvider

```java
public interface TraceProvider {
    List<InterfaceLogEntry> queryTraceInterfaces(String traceId);
}
```

基于 `spanId` 和 `parentSpanId` 构造调用关系：

```java
public interface TraceTopologyAnalyzer {
    TraceTree buildTraceTree(List<InterfaceLogEntry> entries);
}
```

### 8.4 RuntimeLogProvider

```java
public interface RuntimeLogProvider {
    List<RuntimeLogEntry> queryWarnAndErrorLogs(
        String traceId,
        String serviceName,
        TimeWindow window,
        int limit
    );
}
```

```java
public record RuntimeLogEntry(
    String serviceName,
    String traceId,
    String level,
    String message,
    Instant timestamp
) {}
```

### 8.5 BaselineProvider

```java
public interface BaselineProvider {
    InterfaceBaseline getInterfaceBaseline(
        String serviceName,
        String interfaceName,
        String cluster,
        Instant occurTime,
        Duration compareDuration,
        int baselineOffsetDays
    );
}
```

基线内容：

1. 前一天同时间段请求量平均值、P99、最大值。
2. 前一天同时间段失败率平均值、P99、最大值。
3. 前一天同时间段平均时延、P99 时延的平均值、P99、最大值。
4. 常见 errorCode TopN。
5. 历史是否发生过同类告警。

基线窗口计算：

```text
alarmWindow = [occurTime - compareDuration / 2, occurTime + compareDuration / 2]
baselineWindow = [alarmWindow.start - baselineOffsetDays, alarmWindow.end - baselineOffsetDays]
```

例如 `occurTime=2026-04-21T10:33:00+08:00`、`compareDuration=PT10M`、`baselineOffsetDays=1` 时，基线窗口为 `2026-04-20 10:28:00` 到 `2026-04-20 10:38:00`。

```java
public record InterfaceBaseline(
    Double requestCountAvg,
    Double requestCountP99,
    Double requestCountMax,
    Double failRateAvg,
    Double failRateP99,
    Double failRateMax,
    Long avgLatencyAvgMs,
    Long avgLatencyP99Ms,
    Long avgLatencyMaxMs,
    Long p99LatencyAvgMs,
    Long p99LatencyP99Ms,
    Long p99LatencyMaxMs,
    List<ErrorCodeBaseline> commonErrorCodes,
    boolean sameAlertOccurred
) {}

public record ErrorCodeBaseline(
    String errorCode,
    long count,
    double share,
    List<String> sampleErrorMsgs
) {}
```

### 8.6 ResourceMappingProvider

```java
public interface ResourceMappingProvider {
    ResourceMapping getResourceMapping(String serviceName, String cluster);
}
```

```java
public record ResourceMapping(
    String serviceName,
    String cluster,
    List<String> databaseInstanceIds,
    List<String> dcsInstanceIds,
    List<String> mqInstanceIds
) {}
```

ResourceMapping 数据不是实时推断数据，而是预置的服务资源映射数据，必须存储在 MySQL 中，由 `ResourceMappingProvider` 从数据库读取。未来任务：开放资源映射的增删改查接口，支持运维或平台侧维护 `serviceName + cluster -> DB/DCS/MQ` 映射关系。

## 9. 规则预分析设计

规则预分析负责产生确定性证据，降低 LLM 幻觉概率。

### 9.1 通用规则

1. 请求量规模判断：前置告警系统已经完成过滤，本系统收到告警后必须完整分析，不因请求量低跳过、忽略或判定为误报。请求量只用于判断影响范围，例如请求量较小时可以在结论中提示“暂不表现为大范围故障”。
2. 相比基线突增：当前失败率或时延超过前一天同时间段基线 P99 的 `baselineMultiplier`，默认 2 倍。
3. 单实例异常：某实例 CPU、内存、GC、错误率或时延显著高于其他实例。
4. 容器资源异常：CPU > 80%、内存 > 85%、FullGC > 0 或 YoungGC 突增。
5. 实例状态异常：启动中或启动失败实例数量 > 0。
6. CSE 资源异常：线程池、连接池或队列使用率 > 80%。
7. 数据库异常：慢 SQL、连接数、CPU、锁等待、写时延任一指标异常。
8. DCS 异常：P99、平均时延、慢查询比例、客户端失败次数异常。
9. MQ 异常：堆积、消费延迟或发送失败异常，如果后续 Provider 支持。

### 9.2 指标异常判断

建议实现通用异常检测器：

```java
public interface MetricAnomalyDetector {
    List<MetricAnomaly> detect(MetricSeries current, MetricSeries baseline, MetricThreshold threshold);
}
```

异常判断优先级：

1. 明确阈值，例如 CPU > 80%。
2. 与历史基线比，例如当前值 > 前一天同时间段基线 P99 * 2。
3. 与多实例横向比，例如单实例值 > 实例中位数 * 2 且超过绝对阈值。
4. 趋势变化，例如告警前 5 分钟斜率明显上升。

## 10. RPC 接口错误率告警分析策略

类名：

```java
public class RpcErrorRateAnalysisStrategy implements AnalysisStrategy
```

### 10.1 适用场景

RPC 接口日志中 `success=false` 占比高，导致成功率低于阈值。需要判断是真实系统故障、业务校验失败未屏蔽、影响范围较小、下游失败还是当前服务异常。

### 10.2 数据采集

默认采集：

1. 告警接口日志：按 `serviceName + interfaceName + alarmWindow` 查询，limit 默认 1000。
2. 失败样本 traceId：从失败日志中按 errorCode、uid、latency 分层采样，数量由 `max-trace-sample-size` 控制。
3. Trace 链路接口日志：对采样 traceId 查询全链路接口日志。
4. 当前服务 WARN/ERROR 运行日志：按 traceId + serviceName 查询。
5. 容器指标：CPU、内存、GC、实例状态、CSE 使用率。
6. 资源映射：数据库、DCS、MQ 实例。
7. 数据库、DCS、MQ 指标：只采集当前服务映射资源在告警窗口内的数据。
8. 基线数据：同接口前一天同时间段请求量、失败率、常见 errorCode。

### 10.3 规则预分析

#### 10.3.1 请求量与影响范围判断

```text
always analyze the alert

if requestCount < limitedImpactRequestThreshold:
    add finding LOW_TRAFFIC_LIMITED_IMPACT
```

说明：`limitedImpactRequestThreshold` 只用于报告影响范围，不用于过滤告警。示例中请求总数为 3、失败率 100%，仍然必须完整分析 errorCode、trace、运行日志和资源指标；但结论中应提示“当前样本量较小，暂不表现为大范围故障”，不能因为请求量低直接结束分析或判定为误报。

#### 10.3.2 errorCode 聚合

统计：

1. errorCode TopN。
2. 每个 errorCode 的失败次数、占比、uid 数、trace 数。
3. errorCode 是否在已屏蔽列表。
4. errorCode 是否为历史常见 errorCode。
5. 每个 errorCode 对应的 errorMsg 样例。

判断：

```text
if topErrorCodeShare >= 0.8 and related errorMsg looks like business validation:
    add finding BUSINESS_ERROR_CODE_NOT_MASKED
```

业务校验错误特征：

1. errorCode 本身只是数字和字母组合，不携带业务语义；必须根据同 errorCode 对应的 errorMsg 判断是否属于可屏蔽的业务校验失败。
2. errorMsg 包含“参数错误”、“资格不满足”、“活动结束”、“库存不足”、“重复领取”、“用户状态不满足”等业务语义。
3. 同一 uid 或少量 uid 重复触发。
4. Trace 中无明显下游异常，当前服务运行日志无 ERROR。
5. 前一天基线中该 errorCode 长期存在。

系统异常错误特征：

1. errorMsg 包含 timeout、connection refused、NPE、SQL、Redis、MQ、circuit breaker 等。
2. Trace 中出现下游失败。
3. 当前服务或下游服务有 ERROR 日志。
4. 同时出现容器、数据库、DCS、MQ 指标异常。

#### 10.3.3 uid 集中度

统计失败 uid 分布：

```text
topUidShare = top uid failure count / total failure count
```

如果失败集中在极少数 uid，且 errorMsg 为业务语义，则业务校验失败或非系统故障的可能性增加，但仍需继续结合 trace、运行日志和资源指标完成分析。

#### 10.3.4 Trace 下游失败分析

从采样 trace 构建调用树，判断：

1. 是否下游某个 service/interface 的 `success=false` 先于当前接口失败。
2. 是否下游 errorCode 与当前接口 errorCode 高度一致。
3. 是否下游异常日志集中。
4. 是否多个 trace 都失败在同一跳。

如果多个失败 trace 都指向同一下游，输出 `DOWNSTREAM_RPC_FAILURE`。

#### 10.3.5 资源指标关联

如果当前服务容器资源异常，输出对应资源类原因。

如果数据库、DCS、MQ 指标异常且 trace 或日志中存在相关调用失败，提升对应根因置信度。

### 10.4 错误率告警证据摘要

证据包必须包含：

```json
{
  "alert": {
    "category": "RPC_ERROR_RATE",
    "serviceName": "ams_front",
    "interfaceName": "vmall-ams.PrizeActivityService.queryPrizeResult",
    "cluster": "cluster1",
    "occurTime": "2026-04-20T18:09:00+08:00",
    "requestCount": 3,
    "failRate": 1.0
  },
  "baseline": {
    "requestCountP99": 120,
    "failRateP99": 0.02,
    "commonErrorCodes": ["BUSINESS_VALIDATE_FAILED"]
  },
  "errorDistribution": [
    {
      "errorCode": "ACTIVITY_NOT_ELIGIBLE",
      "count": 3,
      "share": 1.0,
      "uidCount": 2,
      "sampleErrorMsgs": ["user not eligible"]
    }
  ],
  "uidDistribution": [
    {
      "uid": "123456789",
      "count": 2,
      "share": 0.67
    }
  ],
  "traceFindings": [
    {
      "traceId": "trace_xxx",
      "firstFailedSpan": "span_2",
      "firstFailedService": "ams_front",
      "firstFailedInterface": "vmall-ams.PrizeActivityService.queryPrizeResult",
      "errorCode": "ACTIVITY_NOT_ELIGIBLE"
    }
  ],
  "metricAnomalies": [],
  "ruleFindings": [
    {
      "type": "LOW_TRAFFIC_LIMITED_IMPACT",
      "confidence": 0.85,
      "evidence": "requestCount=3, impact scope appears limited while analysis is still required"
    }
  ]
}
```

### 10.5 错误率告警输出建议

必须给出：

1. 是否疑似真实故障。
2. 是否疑似业务 errorCode 未屏蔽。
3. 当前请求量是否说明影响范围较小，但不能将其作为跳过分析的理由。
4. Top errorCode 和样例 errorMsg，屏蔽建议必须基于 errorMsg 判断。
5. 失败是否集中在少量 uid。
6. 是否出现下游失败。
7. 是否存在容器、数据库、DCS、MQ 指标异常。
8. 建议处理动作。

处理动作只能是建议，例如：

1. 建议评估是否将某 errorCode 加入屏蔽配置。
2. 建议查看某 traceId 的下游服务错误日志。
3. 建议排查某数据库慢 SQL。
4. 建议结合请求量、失败用户数和业务重要性确认处理优先级。

## 11. RPC 接口时延告警分析策略

类名：

```java
public class RpcLatencyAnalysisStrategy implements AnalysisStrategy
```

### 11.1 适用场景

接口连续 5 分钟平均时延或 P99 时延超过阈值。需要判断慢点在当前服务、下游 RPC、数据库、DCS、MQ、容器资源、GC 或少量慢请求。

### 11.2 数据采集

默认采集：

1. 告警接口日志：按 `serviceName + interfaceName + alarmWindow` 查询，limit 默认 2000。
2. 慢请求样本：按 latencyMs 倒序选取 traceId，数量由 `max-slow-trace-sample-size` 控制。
3. 正常请求样本：选取接近 P50 的 5 个 traceId，用于对比。
4. Trace 链路接口日志：查询慢请求和正常请求的全链路接口日志。
5. 当前服务 WARN/ERROR 运行日志：按慢请求 traceId 查询。
6. 容器指标：CPU、内存、GC、实例状态、CSE 使用率。
7. 数据库、DCS、MQ 指标。
8. 基线数据：同接口前一天同时间段平均时延、P99、请求量。

### 11.3 规则预分析

#### 11.3.1 平均时延与 P99 区分

```text
if avgLatency high and p99Latency high:
    likely broad latency degradation

if avgLatency normal but p99Latency high:
    likely tail latency, small number of slow requests

if avgLatency high but requestCount low:
    still analyze traces and metrics; impact scope may be limited
```

#### 11.3.2 慢请求 Trace 分析

基于 spanId、parentSpanId 构建调用树。

对每个 trace 计算：

1. 根 span 耗时。
2. 每个子 span 耗时。
3. 子 span 耗时占根 span 比例。
4. 最慢 span。
5. 首个异常 span。
6. 下游服务/接口耗时聚合。

下游慢点判断：

```text
if same downstream interface appears as top slow span in >= 50% slow traces:
    add finding DOWNSTREAM_RPC_LATENCY
```

当前服务自身慢判断：

```text
if root span latency is high but child span total latency is low:
    add finding CURRENT_SERVICE_INTERNAL_LATENCY
```

注意：没有完整调用树耗时重叠信息时，子 span 总耗时不能简单相加作为当前服务耗时，需要使用“最慢子 span”和“慢点集中度”判断。

#### 11.3.3 数据库慢点

判断条件：

1. Trace 或运行日志中出现 SQL、DAO、DB timeout、connection 等关键词。
2. 数据库慢 SQL 次数、慢日志个数、连接数、锁等待、写时延异常。
3. 慢请求时间窗口与数据库异常时间窗口重合。

满足以上多项时，输出 `DATABASE_SLOW_QUERY` 或 `DATABASE_RESOURCE_PRESSURE`。

#### 11.3.4 DCS 慢点

判断条件：

1. Trace 或日志中出现 Redis、DCS、cache timeout、slow query、get、set 等关键词。
2. DCS P99、平均时延、慢查询比例、客户端失败次数异常。
3. 慢请求集中在访问缓存的链路。

满足以上多项时，输出 `DCS_LATENCY_OR_FAILURE`。

#### 11.3.5 容器和 GC 慢点

判断条件：

1. CPU 平均或最大值高。
2. 单实例 CPU 明显高于其他实例。
3. 内存使用率高。
4. FullGC 出现或 YoungGC 突增。
5. 实例启动中或启动失败。
6. CSE 使用率高。

如果慢请求集中在某个实例，应输出单实例异常。

#### 11.3.6 请求量突增

对比基线：

```text
if currentRequestCount > baselineRequestCountP99 * 2:
    add finding TRAFFIC_SPIKE
```

请求量突增同时资源指标上升时，应提示容量压力或下游被流量放大。

### 11.4 时延告警证据摘要

```json
{
  "alert": {
    "category": "RPC_LATENCY",
    "serviceName": "vmall_ingresscache_ica",
    "interfaceName": "persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2",
    "cluster": "cluster2",
    "occurTime": "2026-04-21T10:33:00+08:00",
    "requestCount": 49,
    "avgLatencyMs": 3067
  },
  "baseline": {
    "avgLatencyP99Ms": 800,
    "p99LatencyP99Ms": 1800,
    "requestCountP99": 60
  },
  "latencyDistribution": {
    "avgMs": 3067,
    "p50Ms": 1200,
    "p90Ms": 4200,
    "p99Ms": 9000,
    "maxMs": 11000
  },
  "slowTraceFindings": [
    {
      "traceId": "trace_xxx",
      "rootLatencyMs": 8900,
      "slowestSpan": {
        "serviceName": "downstream_service",
        "interfaceName": "SomeService.query",
        "latencyMs": 7600,
        "success": true
      }
    }
  ],
  "downstreamLatencyAggregation": [
    {
      "serviceName": "downstream_service",
      "interfaceName": "SomeService.query",
      "slowTraceHitCount": 12,
      "slowTraceShare": 0.6,
      "avgLatencyMs": 6500,
      "p99LatencyMs": 9000
    }
  ],
  "metricAnomalies": [
    {
      "resourceType": "DCS",
      "resourceId": "dcs_xxx",
      "metric": "requestP99Latency",
      "value": 1200,
      "baselineP99": 100,
      "severity": "HIGH"
    }
  ],
  "ruleFindings": [
    {
      "type": "DOWNSTREAM_RPC_LATENCY",
      "confidence": 0.78,
      "evidence": "same downstream interface is slowest span in 60% slow traces"
    }
  ]
}
```

### 11.5 时延告警输出建议

必须给出：

1. 是平均时延整体升高，还是 P99 尾延迟升高。
2. 慢点更可能在当前服务还是下游。
3. 最可疑的下游服务/接口。
4. 是否存在数据库、DCS、MQ 指标异常。
5. 是否存在容器资源、GC、实例状态异常。
6. 是否存在请求量突增。
7. 建议优先查看的 traceId、服务、接口、资源实例。

## 12. LLM 告警分析设计

### 12.1 LLM 定位

LLM 是综合分析和报告生成组件，不是事实来源。

LLM 可以做：

1. 综合多源证据形成根因假设。
2. 将技术证据转化为值班人员可读结论。
3. 给出排查优先级。
4. 根据 errorMsg 判断业务 errorCode 未屏蔽的可能性。
5. 判断多项异常之间的关联性。

LLM 不可以做：

1. 编造不存在的指标。
2. 编造不存在的 trace、errorCode、日志。
3. 直接执行处置动作。
4. 在证据不足时输出确定性结论。
5. 绕过规则预分析直接修改最终结果。

### 12.2 LlmClient 抽象

```java
public interface LlmClient {
    LlmResponse complete(LlmRequest request);
}
```

```java
public record LlmRequest(
    String model,
    List<LlmMessage> messages,
    double temperature,
    int maxOutputTokens,
    Duration timeout,
    String responseFormat
) {}
```

```java
public record LlmResponse(
    String content,
    String model,
    int inputTokens,
    int outputTokens,
    Duration latency,
    String finishReason
) {}
```

适配实现：

```text
OpenAiCompatibleLlmClient
InternalLlmClient
LocalModelLlmClient
MockLlmClient
```

### 12.3 PromptBuilder

```java
public interface PromptBuilder {
    LlmRequest build(AlertCategory category, CompressedEvidence evidence);
}
```

不同告警类别使用不同 Prompt：

```text
RpcErrorRatePromptBuilder
RpcLatencyPromptBuilder
```

### 12.4 通用 System Prompt

```text
你是电商微服务 AIOps 告警分析助手。

你只能基于用户提供的结构化证据进行分析，不能编造任何不存在的指标、日志、trace、服务名、接口名、errorCode 或资源实例。
如果证据不足，必须明确说明“不确定”。

你的任务是：
1. 判断告警影响和优先级。
2. 给出最多 5 个根因假设，并按置信度降序排列。
3. 每个根因假设必须引用 evidenceIds。
4. 给出可执行的人工排查建议，但不能建议系统已经自动执行任何动作。
5. 对错误率告警，需要特别根据 errorMsg 判断是否可能是业务 errorCode 未屏蔽；请求量较小时只能说明影响范围可能有限，不能判定为误报或跳过分析。
6. 对时延告警，需要特别判断慢点在当前服务、下游 RPC、数据库、DCS、MQ、容器资源还是 GC。

输出必须是合法 JSON，必须符合给定 schema。
不要输出 Markdown，不要输出 JSON 之外的任何文本。
```

### 12.5 错误率告警 User Prompt 模板

```text
请分析以下 RPC 接口错误率告警。

分析重点：
1. 是否为真实系统故障。
2. 请求量规模是否说明当前告警暂不表现为大范围故障。
3. 是否为业务校验 errorCode 未屏蔽，判断依据必须来自 errorMsg，而不是 errorCode 字面值。
4. 失败是否集中在某个 errorCode、某类 errorMsg、少量 uid 或少量 trace。
5. 是否存在下游 RPC、数据库、DCS、MQ 或容器资源异常。
6. 给出人工排查建议和 errorCode 屏蔽建议，但不要执行任何动作。

证据 JSON：
{{compressedEvidenceJson}}

请严格输出符合 schema 的 JSON。
```

### 12.6 时延告警 User Prompt 模板

```text
请分析以下 RPC 接口时延告警。

分析重点：
1. 区分平均时延升高和 P99 尾延迟升高。
2. 判断慢点更可能在当前服务还是下游服务。
3. 如果慢点在下游，请指出最可疑的 serviceName、interfaceName 和 evidenceIds。
4. 判断数据库、DCS、MQ、容器 CPU、内存、GC、实例状态、CSE 使用率是否与时延升高相关。
5. 判断请求量突增是否可能导致时延升高。
6. 给出人工排查建议，但不要执行任何动作。

证据 JSON：
{{compressedEvidenceJson}}

请严格输出符合 schema 的 JSON。
```

### 12.7 LLM 输出 JSON Schema

```json
{
  "type": "object",
  "required": [
    "summary",
    "impact",
    "rootCauses",
    "recommendations",
    "overallConfidence",
    "insufficientEvidence"
  ],
  "properties": {
    "summary": {
      "type": "string",
      "description": "一句话总结告警原因和处理优先级"
    },
    "impact": {
      "type": "object",
      "required": ["level", "description"],
      "properties": {
        "level": {
          "type": "string",
          "enum": ["LOW", "MEDIUM", "HIGH", "UNKNOWN"]
        },
        "description": {
          "type": "string"
        }
      }
    },
    "rootCauses": {
      "type": "array",
      "maxItems": 5,
      "items": {
        "type": "object",
        "required": ["causeType", "description", "confidence", "evidenceIds", "reasoning"],
        "properties": {
          "causeType": {
            "type": "string"
          },
          "description": {
            "type": "string"
          },
          "confidence": {
            "type": "number",
            "minimum": 0,
            "maximum": 1
          },
          "evidenceIds": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "reasoning": {
            "type": "string"
          }
        }
      }
    },
    "recommendations": {
      "type": "array",
      "maxItems": 8,
      "items": {
        "type": "object",
        "required": ["priority", "actionType", "description", "target"],
        "properties": {
          "priority": {
            "type": "string",
            "enum": ["P0", "P1", "P2", "P3"]
          },
          "actionType": {
            "type": "string",
            "enum": [
              "CHECK_TRACE",
              "CHECK_LOG",
              "CHECK_METRIC",
              "CHECK_DOWNSTREAM",
              "CHECK_DATABASE",
              "CHECK_DCS",
              "CHECK_MQ",
              "EVALUATE_ERROR_CODE_MASKING",
              "TUNE_ALERT_RULE",
              "MANUAL_CONFIRMATION"
            ]
          },
          "description": {
            "type": "string"
          },
          "target": {
            "type": "string"
          }
        }
      }
    },
    "overallConfidence": {
      "type": "number",
      "minimum": 0,
      "maximum": 1
    },
    "insufficientEvidence": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  }
}
```

说明：该 Schema 是 LLM 原始输出 Schema。系统持久化和接口返回时，需要在外层补充 `analysisMode`、`degraded`、`degradedReason`、`llmUsed`、`llmStatus`；并为 LLM 生成的 rootCause 补充 `source=LLM`，为规则降级生成的 rootCause 补充 `source=RULE_ENGINE`。

### 12.8 输出校验

`LlmOutputValidator` 必须做以下校验：

1. JSON 可解析。
2. 符合 Schema。
3. `confidence` 在 0 到 1 之间。
4. `rootCauses[*].evidenceIds` 都存在于证据包。
5. `causeType` 属于系统支持的类型；未知类型映射为 `UNKNOWN`。
6. 输出中不得出现证据包不存在的 serviceName、interfaceName、traceId、errorCode、resourceId。
7. 如果校验失败，最多重试一次，重试 Prompt 中加入格式错误说明。
8. 重试仍失败时，任务降级为 `DEGRADED_SUCCESS`，调用 `RuleBasedFallbackAnalyzer` 返回规则降级分析结果。

### 12.9 证据压缩策略

目标：控制 LLM 输入规模，同时保留关键证据。

规则：

1. 原始接口日志不全量送入 LLM，只送聚合结果和代表性样本。
2. errorCode 最多保留 `max-error-code-size` 条，同时保留每个 errorCode 对应的代表性 errorMsg。
3. uid 分布最多保留 `max-uid-size` 条。按照公司规定 uid 不属于敏感数据，无需脱敏，但仍不应输出与分析无关的用户资料。
4. 慢 trace 最多保留 `max-slow-trace-sample-size` 条，该数量必须配置化。
5. 每条 trace 只保留关键 span：根 span、最慢 span、失败 span、与告警服务相关 span。
6. 运行日志最多 50 条，先按 ERROR、WARN 排序，再按 trace 相关性排序。
7. 每条运行日志 message 最多截断 `runtime-log-message-max-chars` 字符，该长度必须配置化。
8. 指标只保留告警窗口、基线、异常点、峰值和趋势，不传完整高频时间序列。
9. 每条证据生成稳定 evidenceId，例如 `metric.container.cpu.max.1`、`trace.slow.3`。

### 12.10 LLM 失败降级

如果配置 `aiops.llm.enabled=false`，系统不应进入 `LLM_ANALYZING`，而是直接使用规则分析结果生成最终结论：`analysisMode=RULE_BASED_ONLY`、`degraded=false`、`llmUsed=false`、`llmStatus=DISABLED`。

降级场景：

1. 模型接口超时。
2. 模型接口返回错误。
3. 输出不是合法 JSON。
4. 输出 Schema 校验失败。
5. 输出引用不存在的证据。

降级输出：

1. `analysisMode=RULE_BASED_DEGRADED`。
2. `llmUsed=false`，`llmStatus` 记录具体失败原因，例如 `TIMEOUT`、`UNAVAILABLE`、`INVALID_JSON`、`SCHEMA_VALIDATION_FAILED`。
3. `degraded=true`，`degradedReason` 记录“LLM 不可用，已切换到规则降级分析”。
4. rootCauses、recommendations、summary 均由规则降级策略生成，不允许只返回空泛错误信息。
5. task 状态为 `DEGRADED_SUCCESS`。

### 12.11 基于规则的降级分析策略

当大模型不可用时，系统必须执行一套确定性的规则降级策略，尽量判断问题位置，并在返回结果中明确标注该结论不是 LLM 生成，而是规则引擎降级生成。

#### 12.11.1 触发条件

触发规则降级策略的场景：

1. LLM 服务不可达。
2. LLM 调用超时。
3. LLM 服务返回 5xx、限流或鉴权异常。
4. LLM 输出不是合法 JSON。
5. LLM 输出不符合 Schema。
6. LLM 输出引用了不存在的 evidenceId。
7. LLM 熔断器处于打开状态。

#### 12.11.2 核心接口

```java
public interface RuleBasedFallbackAnalyzer {
    AnalysisResult analyze(AnalysisContext context, EvidenceBundle evidence, RuleAnalysisEvidence ruleEvidence, LlmFailure failure);
}
```

```java
public record LlmFailure(
    String status,
    String message,
    Instant failedAt
) {}
```

降级分析器应复用规则预分析阶段已经产生的聚合结果，不重复拉取数据。

#### 12.11.3 输出要求

规则降级分析输出必须与正常 LLM 分析结果同构：

```json
{
  "analysisMode": "RULE_BASED_DEGRADED",
  "degraded": true,
  "degradedReason": "LLM 调用超时，已使用规则降级策略生成结论。",
  "llmUsed": false,
  "llmStatus": "TIMEOUT",
  "summary": "【规则降级分析】接口错误率升高，失败集中在单一 errorCode，相关 errorMsg 呈业务校验语义，当前未发现容器或中间件异常。",
  "rootCauses": [
    {
      "causeType": "BUSINESS_ERROR_CODE_NOT_MASKED",
      "source": "RULE_ENGINE",
      "confidence": 0.72,
      "evidenceIds": ["error.top.1", "trace.failure.1"],
      "description": "失败集中在单一 errorCode，相关 errorMsg 呈业务校验语义，疑似业务校验失败未屏蔽。",
      "reasoning": "Top errorCode 占比超过 80%，同 errorCode 的 errorMsg 匹配业务校验关键词，且 trace 和资源指标未显示系统异常。"
    }
  ]
}
```

展示层或 IM 模块必须保留 `analysisMode`、`degraded`、`degradedReason`，避免值班人员误以为该结论来自 LLM 综合分析。

#### 12.11.4 规则评分模型

规则降级策略使用证据打分，不做自由文本推理。每个候选根因初始分为 0，根据证据累加分数，最后映射到置信度。

建议映射：

```text
score >= 80 -> confidence = 0.90
score >= 60 -> confidence = 0.75
score >= 40 -> confidence = 0.55
score >= 20 -> confidence = 0.35
score < 20  -> confidence = 0.20
```

候选根因最多返回 5 个，按 confidence 降序排列。若最高置信度低于 0.4，则主结论应为 `UNKNOWN`，并输出缺失证据和下一步排查建议。

#### 12.11.5 错误率告警降级规则

候选根因和评分建议：

| 根因类型 | 加分条件 |
| --- | --- |
| `BUSINESS_ERROR_CODE_NOT_MASKED` | Top errorCode 占比 >= 80% 加 30；同 errorCode 的 errorMsg 匹配业务校验关键词加 25；失败集中在少量 uid 加 10；trace 无下游失败加 15；资源指标无异常加 10 |
| `DOWNSTREAM_RPC_FAILURE` | 多个失败 trace 的首个失败 span 指向同一下游接口加 35；下游接口 success=false 加 25；下游错误码与当前错误码相关加 10；下游运行日志存在 ERROR 加 15 |
| `DATABASE_RESOURCE_PRESSURE` | DB CPU/连接数/锁等待/写时延异常加 20；慢 SQL 或慢日志增加加 25；trace 或运行日志出现 SQL/DB 关键词加 20 |
| `DCS_LATENCY_OR_FAILURE` | DCS 客户端失败次数上升加 25；DCS P99 或平均时延异常加 20；运行日志出现 Redis/DCS/cache timeout 加 20 |
| `CONTAINER_CPU_PRESSURE` | 当前服务容器 CPU 平均或最大值超过阈值加 25；异常集中在单实例加 15；同时 CSE 使用率高加 10 |
| `GC_PRESSURE` | FullGC > 0 加 30；YoungGC 突增加 15；内存使用率高加 15；时序与告警窗口重合加 10 |
| `LOW_TRAFFIC_LIMITED_IMPACT` | 请求量低于 `limitedImpactRequestThreshold` 加 30；失败 uid 数较少加 10；该根因只表达影响范围，不作为故障根因排第一，除非无其它有效根因 |

错误率降级 summary 生成规则：

1. 如果最高根因为业务 errorCode：说明“疑似业务校验失败未屏蔽”，并附 Top errorCode 和代表性 errorMsg。
2. 如果最高根因为下游失败：说明“失败可能由下游接口返回失败传导”，并附下游 service/interface。
3. 如果资源类根因分数最高：说明“当前服务或依赖资源存在异常，可能导致接口失败”。
4. 如果只有 `LOW_TRAFFIC_LIMITED_IMPACT` 分数较高：说明“当前请求量较小，暂不表现为大范围故障，但仍需确认失败原因”。

#### 12.11.6 时延告警降级规则

候选根因和评分建议：

| 根因类型 | 加分条件 |
| --- | --- |
| `DOWNSTREAM_RPC_LATENCY` | 同一下游接口在 >= 50% 慢 trace 中为最慢 span 加 35；该下游平均耗时超过当前接口耗时 50% 加 20；慢 trace 与正常 trace 对比差异明显加 15 |
| `DATABASE_SLOW_QUERY` | 慢 SQL 次数或慢日志明显上升加 25；DB 写时延/锁等待/连接数异常加 20；trace 或运行日志出现 SQL/DAO/DB timeout 加 20 |
| `DCS_LATENCY_OR_FAILURE` | DCS P99 或平均时延异常加 25；DCS 慢查询比例异常加 20；DCS 客户端失败次数上升加 15；日志出现 Redis/DCS/cache timeout 加 15 |
| `CONTAINER_CPU_PRESSURE` | 当前服务 CPU 高加 25；单实例 CPU 明显高于其他实例加 15；CSE 使用率高加 15 |
| `CONTAINER_MEMORY_PRESSURE` | 内存使用率高加 20；内存持续上升加 10；伴随 GC 异常加 15 |
| `GC_PRESSURE` | FullGC > 0 加 35；YoungGC 突增加 15；GC 时间窗口与慢请求重合加 15 |
| `INSTANCE_STARTING_OR_FAILED` | 启动中或启动失败实例数量 > 0 加 30；异常实例与慢请求实例重合加 20 |
| `TRAFFIC_SPIKE` | 当前请求量 > 前一天同时间段基线 P99 * 2 加 25；同时 CPU/CSE/下游耗时升高加 20 |

时延降级 summary 生成规则：

1. 如果下游慢分数最高：说明“慢点疑似集中在下游 RPC”，并附最可疑下游接口。
2. 如果 DB/DCS/MQ 分数最高：说明“依赖中间件指标异常与时延窗口重合”。
3. 如果容器或 GC 分数最高：说明“当前服务自身资源压力可能导致时延升高”。
4. 如果所有候选分数都低：说明“规则降级策略未能定位明确根因”，并列出最需要补充的证据。

#### 12.11.7 降级策略优先级

当多个根因同时满足时，按以下优先级修正排序：

1. Trace 中直接定位到的下游失败或下游慢点优先于资源类推断。
2. 与告警时间窗口重合的资源异常优先于非重合异常。
3. 多个 trace 共同指向同一服务/接口优先于单条 trace 证据。
4. 错误率告警中，业务 errorCode 未屏蔽优先于请求量影响范围判断。
5. 请求量较小只影响 impact 判断，不应覆盖真实根因判断。

#### 12.11.8 降级策略建议生成

规则降级策略必须生成 recommendations：

1. 每个高置信根因至少生成一条排查建议。
2. 建议必须包含 target，例如 traceId、serviceName、interfaceName、dbInstanceId、dcsInstanceId、errorCode。
3. 如果结论不确定，建议优先补充证据，而不是输出笼统“人工排查”。
4. 所有建议均为人工动作，不执行自动处置。

#### 12.11.9 降级策略版本化

规则降级策略需要版本化，便于回放评估和线上问题复盘：

```text
ruleEngineVersion = "fallback-rules-v1"
```

`ruleEngineVersion` 应写入 `result_json`，也可以作为单独字段存储。

## 13. MySQL 表设计

### 13.1 aiops_alert_event

```sql
CREATE TABLE aiops_alert_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    alert_id VARCHAR(128) NOT NULL COMMENT '外部告警ID，例如 WiseEye Alert ID',
    category VARCHAR(64) NOT NULL COMMENT '系统识别后的告警类型',
    raw_category VARCHAR(128) COMMENT '原始告警Category字段',
    status VARCHAR(64) COMMENT '原始告警状态，例如 OPEN/CLEAR',
    severity VARCHAR(64) COMMENT '原始告警级别',
    service_name VARCHAR(256) COMMENT '告警服务名',
    interface_name VARCHAR(512) COMMENT '告警接口名',
    cluster_name VARCHAR(128) COMMENT '集群名',
    site VARCHAR(128) COMMENT '站点',
    tenant VARCHAR(128) COMMENT '租户',
    application_name VARCHAR(128) COMMENT '应用名',
    alert_name VARCHAR(512) COMMENT '告警名称',
    metric_name VARCHAR(256) COMMENT '指标名',
    metric_occur_time DATETIME(3) COMMENT '指标触发时间，优先取Details中的发生时间',
    received_at DATETIME(3) NOT NULL COMMENT '本系统收到告警的时间',
    trigger_values_json JSON COMMENT '结构化触发值，对应AlertTriggerValues',
    raw_text MEDIUMTEXT NOT NULL COMMENT '原始告警全文',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_alert_id (alert_id),
    KEY idx_service_interface_time (service_name, interface_name(255), metric_occur_time),
    KEY idx_cluster_category_time (cluster_name, category, metric_occur_time),
    KEY idx_metric_time (metric_name, metric_occur_time),
    KEY idx_received_at (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件表，保存Webhook原始告警和解析后的结构化字段';
```

### 13.2 aiops_analysis_task

```sql
CREATE TABLE aiops_analysis_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '关联告警ID',
    category VARCHAR(64) NOT NULL COMMENT '告警类型',
    dedupe_key VARCHAR(1024) NOT NULL COMMENT '去重键：category|serviceName|interfaceName|cluster',
    task_status VARCHAR(64) NOT NULL COMMENT 'AnalysisJobStatus状态机状态',
    current_step VARCHAR(128) COMMENT '当前执行步骤，例如 collect_provider.metric',
    status_reason TEXT COMMENT '当前状态说明，记录等待、降级或失败原因',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '任务级重试次数',
    state_version BIGINT NOT NULL DEFAULT 0 COMMENT '状态版本号，用于乐观锁更新',
    failure_reason TEXT COMMENT '失败原因',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    started_at DATETIME(3) COMMENT '开始执行时间',
    finished_at DATETIME(3) COMMENT '结束时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_status_created_at (task_status, created_at),
    KEY idx_dedupe_status_time (dedupe_key(255), task_status, created_at),
    KEY idx_dedupe_created_at (dedupe_key(255), created_at),
    KEY idx_category_status_time (category, task_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务表，使用显式状态机管理AnalysisJob生命周期';
```

### 13.3 aiops_analysis_result

```sql
CREATE TABLE aiops_analysis_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    category VARCHAR(64) NOT NULL COMMENT '告警类型',
    analysis_mode VARCHAR(64) NOT NULL COMMENT 'LLM_ASSISTED/RULE_BASED_DEGRADED/RULE_BASED_ONLY',
    summary TEXT NOT NULL COMMENT '分析摘要',
    impact_level VARCHAR(64) COMMENT '影响等级 LOW/MEDIUM/HIGH/UNKNOWN',
    impact_description TEXT COMMENT '影响说明',
    overall_confidence DECIMAL(5,4) COMMENT '整体置信度，0到1',
    llm_used TINYINT(1) NOT NULL COMMENT '是否使用LLM',
    llm_status VARCHAR(64) COMMENT 'LLM调用状态',
    degraded TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为降级结果',
    degraded_by_provider_failure TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否因Provider失败导致降级或降低置信度',
    degraded_reason TEXT COMMENT '降级原因',
    rule_engine_version VARCHAR(128) COMMENT '规则降级策略版本',
    result_json JSON NOT NULL COMMENT '完整分析结果JSON',
    generated_at DATETIME(3) NOT NULL COMMENT '结果生成时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_alert_category (alert_id, category),
    KEY idx_analysis_mode_generated_at (analysis_mode, generated_at),
    KEY idx_category_generated_at (category, generated_at),
    KEY idx_generated_at (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析结果表，保存面向接口和IM输出的最终结论';
```

### 13.4 aiops_evidence_bundle

```sql
CREATE TABLE aiops_evidence_bundle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    evidence_type VARCHAR(64) NOT NULL COMMENT '证据类型，例如 raw/compressed/rule',
    evidence_json JSON NOT NULL COMMENT '证据包JSON',
    expire_at DATETIME(3) NOT NULL COMMENT '过期清理时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_task_evidence_type (task_id, evidence_type),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据包表，保存原始证据、压缩证据和规则证据';
```

`expire_at` 默认 `created_at + 30 days`。

### 13.5 aiops_provider_call_log

```sql
CREATE TABLE aiops_provider_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    provider_name VARCHAR(128) NOT NULL COMMENT 'Provider名称',
    provider_status VARCHAR(64) NOT NULL COMMENT 'ProviderStatus状态',
    critical TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否关键Provider',
    latency_ms BIGINT COMMENT '调用耗时毫秒',
    error_code VARCHAR(128) COMMENT '错误码',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_provider_status_time (provider_name, provider_status, created_at),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Provider调用日志表，用于记录数据源调用失败、超时和耗时';
```

### 13.6 aiops_llm_call_log

```sql
CREATE TABLE aiops_llm_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    provider VARCHAR(128) COMMENT 'LLM供应方',
    model_name VARCHAR(128) COMMENT '模型名称',
    request_json JSON COMMENT 'LLM请求JSON',
    response_text MEDIUMTEXT COMMENT 'LLM原始响应文本',
    input_tokens INT COMMENT '输入token数',
    output_tokens INT COMMENT '输出token数',
    latency_ms BIGINT COMMENT '调用耗时毫秒',
    call_status VARCHAR(64) NOT NULL COMMENT '调用状态 SUCCESS/TIMEOUT/FAILED等',
    error_message TEXT COMMENT '错误信息',
    expire_at DATETIME(3) NOT NULL COMMENT '过期清理时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_alert_id (alert_id),
    KEY idx_call_status_created_at (call_status, created_at),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM调用日志表，用于审计、排障和成本统计';
```

### 13.7 aiops_duplicate_alert_link

```sql
CREATE TABLE aiops_duplicate_alert_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    source_alert_id VARCHAR(128) NOT NULL COMMENT '被合并的告警ID',
    target_task_id VARCHAR(128) NOT NULL COMMENT '命中的已有分析任务ID',
    dedupe_key VARCHAR(1024) NOT NULL COMMENT '去重键',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_source_alert_id (source_alert_id),
    KEY idx_target_task_id (target_task_id),
    KEY idx_dedupe_key_time (dedupe_key(255), created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重复告警关联表，记录5分钟去重合并关系';
```

### 13.8 aiops_resource_mapping

```sql
CREATE TABLE aiops_resource_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    service_name VARCHAR(256) NOT NULL COMMENT '服务名',
    cluster_name VARCHAR(128) NOT NULL COMMENT '集群名',
    resource_type VARCHAR(64) NOT NULL COMMENT '资源类型：DATABASE/DCS/MQ',
    resource_id VARCHAR(256) NOT NULL COMMENT '资源实例ID',
    resource_name VARCHAR(256) COMMENT '资源实例名称',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    metadata_json JSON COMMENT '扩展信息，例如所属环境、备注、连接标识',
    created_by VARCHAR(128) COMMENT '创建人',
    updated_by VARCHAR(128) COMMENT '更新人',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_service_cluster_resource (service_name, cluster_name, resource_type, resource_id),
    KEY idx_service_cluster_enabled (service_name, cluster_name, enabled),
    KEY idx_resource_type_id (resource_type, resource_id),
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务资源映射表，预置service+cluster到DB/DCS/MQ实例的关系';
```

说明：一期数据通过初始化脚本或人工导入预置。未来任务是开放该表的增删改查接口，并增加操作审计。

### 13.9 aiops_analysis_job_state_log

```sql
CREATE TABLE aiops_analysis_job_state_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    task_id VARCHAR(128) NOT NULL COMMENT '分析任务ID',
    alert_id VARCHAR(128) NOT NULL COMMENT '告警ID',
    from_status VARCHAR(64) COMMENT '变更前状态',
    to_status VARCHAR(64) NOT NULL COMMENT '变更后状态',
    step_name VARCHAR(128) COMMENT '状态对应的执行步骤',
    reason TEXT COMMENT '状态变更原因',
    state_version BIGINT NOT NULL COMMENT '变更后的状态版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    KEY idx_task_created_at (task_id, created_at),
    KEY idx_alert_id (alert_id),
    KEY idx_to_status_created_at (to_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务状态流转日志，用于审计、排障和耗时统计';
```

## 14. 分析任务生命周期

AnalysisJob 必须通过显式状态机推进。每次状态切换都要：

1. 更新 `aiops_analysis_task.task_status/current_step/status_reason/state_version/updated_at`。
2. 插入 `aiops_analysis_job_state_log`。
3. 使用 `task_id + state_version` 乐观锁更新，避免异步任务重复执行导致状态回退。

状态流转：

Webhook 同步接入阶段先完成告警解析、分类、保存 `aiops_alert_event`、计算 dedupeKey 和创建 `aiops_analysis_task`。只有任务创建成功后，AnalysisJob 才进入以下状态机。

| 顺序 | 状态 | 执行动作 | 成功后 | 失败后 |
| --- | --- | --- | --- | --- |
| 1 | `PENDING` | 异步执行器领取任务，初始化执行上下文 | `RUNNING` | `FAILED` |
| 2 | `RUNNING` | 初始化 AnalysisContext，选择策略 | `COLLECTING_EVIDENCE` | `FAILED` |
| 3 | `COLLECTING_EVIDENCE` | 并行调用 Provider，收集指标、日志、Trace、基线、资源映射 | `RULE_ANALYZING` | Provider 失败按 8.0 的默认口径处理；能生成降级结果时进入后续状态，不能解析告警关键字段才 `FAILED` |
| 4 | `RULE_ANALYZING` | 执行错误率/时延规则预分析 | `COMPRESSING_EVIDENCE` | `FAILED` |
| 5 | `COMPRESSING_EVIDENCE` | 生成压缩证据包并保存 evidence bundle | LLM 启用时进入 `LLM_ANALYZING`，LLM 关闭时进入 `GENERATING_RESULT` 并生成 `RULE_BASED_ONLY` 结果 | `FAILED` |
| 6 | `LLM_ANALYZING` | 调用 LLM，校验 JSON 和 evidenceId | `GENERATING_RESULT` | `RULE_FALLBACK_ANALYZING` |
| 7 | `RULE_FALLBACK_ANALYZING` | 执行规则降级策略 | `GENERATING_RESULT` | `FAILED` |
| 8 | `GENERATING_RESULT` | 合并结论、保存结果 | `SUCCESS` 或 `DEGRADED_SUCCESS` | `FAILED` |

去重和历史参考：

1. 如果 5 分钟内命中相同 dedupeKey 的任务，新告警不创建新的执行流程，写入 `aiops_duplicate_alert_link`，返回已有 taskId。
2. 如果 5 分钟外存在历史已完成结果，不影响本次状态机执行，只在结果中附加 `historicalReference`。

Provider 状态处理：

1. 每个 Provider 调用都要生成 `ProviderResult`，记录成功、失败、超时或跳过。
2. 每个 Provider 调用结果写入 `aiops_provider_call_log`，用于定位数据源慢、超时和失败。
3. 非关键 Provider 失败时，任务继续执行，但最终状态应为 `DEGRADED_SUCCESS`，并在结果中标注 `degradedByProviderFailure=true`。
4. 关键 Provider 失败时，按 8.0 的默认口径生成规则降级结果；只有无法解析告警关键字段或状态机异常时，任务进入 `FAILED`。
5. Provider 并行调用完成后再统一进入 `RULE_ANALYZING`，避免某个慢 Provider 阻塞所有无依赖数据源。

失败处理：

1. Provider 部分失败：记录 missingEvidence，根据 8.0 的 Provider 关键性矩阵决定继续或规则降级。
2. 关键字段缺失：任务失败，例如无法解析 serviceName 或 interfaceName。
3. LLM 失败：执行规则降级策略，生成带 `RULE_BASED_DEGRADED` 标记的降级分析结果，任务状态为 `DEGRADED_SUCCESS`。
4. 未支持告警类型：任务失败或返回 `UNSUPPORTED_CATEGORY`。

## 15. 扩展机制

新增告警类型时需要：

1. 新增 `AlertCategory` 枚举。
2. 在 `AlertClassifier` 增加分类规则。
3. 实现新的 `AnalysisStrategy`。
4. 增加对应 RuleAnalyzer。
5. 增加对应 PromptBuilder。
6. 如果需要，增加 Provider 查询方法。
7. 增加测试用例和样例告警。

策略接口：

```java
public interface AnalysisStrategy {
    AlertCategory supportedCategory();

    AnalysisResult analyze(AnalysisContext context);
}
```

注册方式：

```java
@Component
public class AnalysisStrategyRegistry {
    private final Map<AlertCategory, AnalysisStrategy> strategies;

    public AnalysisStrategy get(AlertCategory category) {
        AnalysisStrategy strategy = strategies.get(category);
        if (strategy == null) {
            throw new UnsupportedAlertCategoryException(category);
        }
        return strategy;
    }
}
```

## 16. 配置项

建议配置：

```yaml
aiops:
  analysis:
    # 5分钟内相同 dedupeKey 的告警合并到同一个分析任务，避免重复调用 Provider 和 LLM。
    dedupe-window: PT5M

    # 5分钟外的同 dedupeKey 历史结果参考窗口；只作为值班参考，不阻止本次分析。
    historical-reference-lookback: P1D

    # 原始证据包保留天数，到期后可由定时任务清理。
    evidence-retention-days: 30

    # LLM 请求和响应日志保留天数，到期后可清理以控制存储成本。
    llm-log-retention-days: 30

    # 单个分析任务的目标超时时间，超过后应进入失败或降级流程。
    async-timeout: PT3M

    # 只用于判断“影响范围可能有限”，不能用于过滤或跳过告警。
    limited-impact-request-threshold: 10

    # 基线默认取前一天同时间段，避免查询最近7天导致慢查询或无数据。
    baseline-offset-days: 1

    # 基线对比窗口长度，例如告警发生时间前后各取一段窗口。
    baseline-compare-duration: PT10M

    # 单次接口日志查询最大条数。
    max-interface-log-limit: 2000

    # 错误率告警中失败 trace 抽样上限。
    max-trace-sample-size: 20

    # 时延告警中慢 trace 抽样上限。
    max-slow-trace-sample-size: 20

    # 运行日志最多保留条数。
    max-runtime-log-size: 50

    # 每条运行日志 message 最大字符数，超出后截断再送入 LLM。
    runtime-log-message-max-chars: 500

    # errorCode 聚合 TopN 上限。
    max-error-code-size: 10

    # uid 分布 TopN 上限；uid 按公司规定不属于敏感数据，无需脱敏。
    max-uid-size: 10

  provider:
    # Provider 单次调用超时，超过后标记 TIMEOUT。
    timeout: PT10S

    # Provider 失败后的短重试次数；重试后仍失败则进入 Provider 降级方案。
    retry-times: 1

    # Provider 短重试间隔。
    retry-interval: PT1S

    # 是否启用无依赖 Provider 并行调用。
    parallel-enabled: true

    # 非关键 Provider 失败时是否继续分析并降低置信度。
    continue-on-non-critical-failure: true

  llm:
    # 是否启用LLM。为false时直接走规则分析，analysisMode=RULE_BASED_ONLY，不视为异常降级。
    enabled: true

    # LLM 供应方适配器，可切换 openai-compatible/internal/local/mock。
    provider: openai-compatible

    # 具体模型名称。
    model: default-model

    # 输出随机性，告警分析建议保持低温度。
    temperature: 0.1

    # LLM 最大输出 token，避免超长响应。
    max-output-tokens: 4000

    # LLM 调用超时。
    timeout: PT45S

    # LLM 不可用时是否启用规则降级分析。
    fallback-enabled: true

    # 规则降级策略版本，写入分析结果用于回放和审计。
    fallback-rule-engine-version: fallback-rules-v1

  thresholds:
    # 容器CPU高水位阈值。
    container-cpu-high: 0.80

    # 容器内存高水位阈值。
    container-memory-high: 0.85

    # CSE/RPC框架资源使用率高水位阈值。
    cse-usage-high: 0.80

    # 数据库CPU高水位阈值。
    db-cpu-high: 0.80

    # 数据库连接数使用率高水位阈值。
    db-connection-high: 0.80

    # DCS CPU高水位阈值。
    dcs-cpu-high: 0.80

    # DCS内存高水位阈值。
    dcs-memory-high: 0.85
```

## 17. 测试策略

### 17.1 单元测试

必须覆盖：

1. 告警文本解析：错误率样例、时延样例、字段缺失样例。
2. 告警分类规则。
3. dedupeKey 生成和 5 分钟去重。
4. errorCode 聚合和请求量影响范围判断。
5. 时延分布统计和慢 trace 聚合。
6. spanId/parentSpanId 构建调用树。
7. 指标异常检测。
8. LLM 输出 Schema 校验。
9. LLM evidenceId 引用校验。
10. RuleBasedFallbackAnalyzer 在 LLM 不可用时生成同构结果，并标注 `analysisMode=RULE_BASED_DEGRADED`。
11. AnalysisJob 状态机合法流转和非法流转拦截。
12. ProviderResult 聚合、并行调用和非关键 Provider 失败降级。

### 17.2 集成测试

使用 Mock Provider 和 Mock LlmClient 覆盖：

1. RPC 错误率请求量较小但仍完整分析。
2. RPC 错误率业务 errorCode 未屏蔽。
3. RPC 错误率下游失败。
4. RPC 时延下游慢。
5. RPC 时延数据库慢 SQL。
6. RPC 时延 DCS P99 升高。
7. LLM 超时降级，并返回规则降级策略生成的根因、建议和降级标记。
8. Provider 部分失败仍能输出缺失证据说明。
9. 5 分钟外同 dedupeKey 历史结果能作为 historicalReference 返回。
10. ResourceMappingProvider 能从预置数据库表读取 DB/DCS/MQ 映射。

### 17.3 验收标准

1. Webhook 接口能接收两个样例告警并创建任务。
2. 错误率样例能解析出 `ams_front`、`vmall-ams.PrizeActivityService.queryPrizeResult`、`cluster1`、`AIOps@rate`。
3. 时延样例能解析出 `vmall_ingresscache_ica`、接口名、`cluster2`、`AIOps@delay`。
4. 相同 dedupeKey 5 分钟内只创建一个分析任务。
5. 相同 dedupeKey 5 分钟外的历史结果能返回 alertId、taskId 和摘要，且不阻止本次分析。
6. LLM 失败时仍能返回规则降级分析结果，且 `analysisMode=RULE_BASED_DEGRADED`、`degraded=true`、`llmUsed=false`。
7. 非关键 Provider 失败时能返回 `missingEvidence` 和 `degradedByProviderFailure=true`。
8. 所有最终结论都有 evidenceId。
9. 不存在自动执行处置动作的代码路径。

## 18. 开发任务拆分

### 18.1 第一阶段：骨架和存储

1. 初始化 Spring Boot 项目依赖。
2. 建立 MySQL 表和实体。
3. 实现 Webhook Controller。
4. 实现 AlertParser。
5. 实现 AlertClassifier。
6. 实现任务创建、去重、查询接口。
7. 实现 AnalysisJob 状态机和状态流转日志。
8. 实现异步任务执行框架。
9. 导入预置 ResourceMapping 数据。

### 18.2 第二阶段：Provider 抽象和 Mock

1. 定义 MetricProvider。
2. 定义 InterfaceLogProvider。
3. 定义 TraceProvider。
4. 定义 RuntimeLogProvider。
5. 定义 BaselineProvider。
6. 定义 ResourceMappingProvider。
7. 实现 Provider 并行调用编排和 ProviderResult 包装。
8. 实现 Provider 调用日志和 Provider 失败降级方案。
9. 提供 Mock 实现用于本地开发和测试。

### 18.3 第三阶段：规则分析

1. 实现 ErrorRateRuleAnalyzer。
2. 实现 LatencyRuleAnalyzer。
3. 实现 MetricAnomalyDetector。
4. 实现 TraceTopologyAnalyzer。
5. 实现 EvidenceCompressor。
6. 实现 RuleBasedFallbackAnalyzer。
7. 输出无 LLM 版本和 LLM 降级版本的分析结果。

### 18.4 第四阶段：LLM 集成

1. 实现 LlmClient 抽象。
2. 实现 OpenAI-compatible 适配。
3. 实现 PromptBuilder。
4. 实现 JSON Schema 校验。
5. 实现 evidenceId 引用校验。
6. 实现失败重试和降级逻辑。
7. 接入 RuleBasedFallbackAnalyzer，确保 LLM 不可用时仍输出带降级标记的分析结果。
8. 保存 LLM 调用日志。

### 18.5 第五阶段：内网适配和调优

1. 适配真实监控指标 HTTP 接口。
2. 适配真实接口日志查询接口。
3. 适配真实 Trace 查询接口。
4. 适配真实运行日志查询接口。
5. 适配真实基线查询接口。
6. 适配 ResourceMapping 初始化数据导入流程。
7. 使用线上历史告警回放评估效果。
8. 调整阈值、采样数量、Prompt 和规则权重。

### 18.6 后续任务：资源映射维护接口

1. 开放 ResourceMapping 增删改查接口。
2. 增加资源映射变更审计。
3. 支持按 serviceName、cluster、resourceType 查询映射。
4. 支持资源映射灰度启停，避免错误映射影响分析结果。

## 19. 系统建议

### 19.1 告警规则建议

前置告警系统已经完成过滤时，本系统不应再增加最小请求量拦截逻辑。请求数较低的场景仍然需要完整分析，但在结论中应明确影响范围，例如“当前请求总数较少，暂不表现为大范围故障”，并结合失败用户数、接口重要性和 errorCode 分布给出处理优先级。

### 19.2 errorCode 治理建议

建立 errorCode 治理闭环：

1. 自动识别疑似业务 errorCode 未屏蔽。
2. 屏蔽建议必须基于 errorCode 对应的 errorMsg 和 trace/资源证据，不根据 errorCode 字面值判断。
3. 输出屏蔽建议，不自动修改。
4. 人工确认后进入屏蔽配置。
5. 记录每次建议是否被采纳。
6. 用采纳结果优化后续规则。

### 19.3 Trace 能力建议

现阶段可以基于 spanId 和 parentSpanId 推断调用关系。后续建议补充标准调用树接口，直接返回每个 span 的开始时间、结束时间、耗时、服务、接口、状态和错误信息，这会显著提升时延告警定位准确率。

### 19.4 变更数据建议

建议接入发布、配置变更、扩容缩容、限流降级开关变更记录。很多线上告警与最近 30-60 分钟变更强相关，这类数据对根因分析价值很高。

### 19.5 反馈闭环建议

建议在分析结果中提供人工反馈字段：

```text
ACCEPTED
PARTIALLY_ACCEPTED
WRONG_ROOT_CAUSE
INSUFFICIENT_EVIDENCE
```

后续可以用反馈数据评估 Prompt、规则和阈值质量。

### 19.6 可信度建议

面向值班人员展示时，不要只展示单一结论。建议固定展示：

1. 最可能原因。
2. 证据。
3. 置信度。
4. 备选原因。
5. 还缺什么证据。
6. 下一步排查动作。

这样可以避免 LLM 结论被误认为确定事实。

### 19.7 安全建议

即使当前日志已脱敏，仍建议在送入 LLM 前增加二次脱敏过滤器，至少过滤手机号、邮箱、token、cookie、身份证号、银行卡号、精确地址等敏感模式。uid 按公司规定不属于敏感数据，可以直接进入证据包，但不应输出与告警分析无关的用户资料。

## 20. 示例最终分析结果

### 20.1 错误率告警示例输出

```json
{
  "analysisMode": "LLM_ASSISTED",
  "degraded": false,
  "degradedByProviderFailure": false,
  "degradedReason": null,
  "llmUsed": true,
  "llmStatus": "SUCCESS",
  "summary": "该错误率告警请求量较小，暂不表现为大范围故障；失败集中在单一 errorCode，相关 errorMsg 呈业务校验语义，暂未发现容器或中间件异常，疑似业务校验失败未屏蔽。",
  "impact": {
    "level": "LOW",
    "description": "告警窗口请求数较低，对整体用户影响可能有限，但需要结合 errorMsg 确认 errorCode 是否应纳入屏蔽。"
  },
  "rootCauses": [
    {
      "causeType": "LOW_TRAFFIC_LIMITED_IMPACT",
      "description": "告警窗口请求数较少，当前影响范围可能有限，但仍需完整分析失败原因。",
      "confidence": 0.85,
      "evidenceIds": ["alert.request.count", "rule.limited_impact"],
      "source": "MERGED",
      "reasoning": "请求总数为 3，当前样本量较小，暂不表现为大范围故障；但该告警已经由前置系统过滤，不能因此跳过分析。"
    },
    {
      "causeType": "BUSINESS_ERROR_CODE_NOT_MASKED",
      "description": "失败集中在单一 errorCode，相关 errorMsg 呈业务校验语义，可能属于应屏蔽但未屏蔽的业务校验失败。",
      "confidence": 0.68,
      "evidenceIds": ["error.top.1", "trace.failure.1"],
      "source": "MERGED",
      "reasoning": "Top errorCode 占比高，代表性 errorMsg 符合业务校验语义，未观察到下游失败和资源异常。"
    }
  ],
  "recommendations": [
    {
      "priority": "P1",
      "actionType": "EVALUATE_ERROR_CODE_MASKING",
      "description": "根据 Top errorCode 对应的 errorMsg 确认是否属于正常业务校验失败，如确认则加入错误率告警屏蔽配置。",
      "target": "ACTIVITY_NOT_ELIGIBLE"
    },
    {
      "priority": "P2",
      "actionType": "TUNE_ALERT_RULE",
      "description": "结合请求量、失败用户数和接口业务重要性确认处理优先级，不能仅因请求量较小忽略该告警。",
      "target": "CP_all_all_sevice_failrate_over70_ams"
    }
  ],
  "overallConfidence": 0.78,
  "missingEvidence": [],
  "insufficientEvidence": []
}
```

### 20.2 时延告警示例输出

```json
{
  "analysisMode": "LLM_ASSISTED",
  "degraded": false,
  "degradedByProviderFailure": false,
  "degradedReason": null,
  "llmUsed": true,
  "llmStatus": "SUCCESS",
  "summary": "该时延告警表现为平均时延持续升高，慢请求样本中下游接口耗时占比较高，疑似下游服务或缓存访问变慢。",
  "impact": {
    "level": "MEDIUM",
    "description": "告警窗口请求量为 49，平均时延超过 3 秒，可能影响接口消费链路处理效率。"
  },
  "rootCauses": [
    {
      "causeType": "DOWNSTREAM_RPC_LATENCY",
      "description": "慢请求中最慢 span 多次集中在同一下游接口。",
      "confidence": 0.76,
      "evidenceIds": ["trace.slow.aggregation.1", "trace.slow.3"],
      "source": "MERGED",
      "reasoning": "超过半数慢 trace 的最慢 span 指向同一下游接口，且当前服务容器指标未见明显异常。"
    },
    {
      "causeType": "DCS_LATENCY_OR_FAILURE",
      "description": "缓存请求 P99 时延和慢查询比例在告警窗口升高，可能放大接口耗时。",
      "confidence": 0.62,
      "evidenceIds": ["metric.dcs.p99.1", "metric.dcs.slow_query.1"],
      "source": "MERGED",
      "reasoning": "DCS 指标异常时间与接口时延告警窗口重合。"
    }
  ],
  "recommendations": [
    {
      "priority": "P1",
      "actionType": "CHECK_TRACE",
      "description": "优先查看慢 trace 中最慢 span 对应的下游接口耗时和日志。",
      "target": "trace.slow.aggregation.1"
    },
    {
      "priority": "P1",
      "actionType": "CHECK_DCS",
      "description": "检查告警窗口内 DCS P99 时延、慢查询和客户端失败。",
      "target": "dcs_instance_id"
    }
  ],
  "overallConfidence": 0.72,
  "missingEvidence": [],
  "insufficientEvidence": ["缺少直接的标准调用树接口，当前调用关系由 spanId 和 parentSpanId 推断。"]
}
```

## 21. 附录：原始告警示例

### 21.1 RPC 接口错误率告警原文

```text
WiseEye Alarm:
Status: OPEN
Severity: Major
Host: CP_all_all_sevice_failrate_over70_ams:{"servicename":"ams_front","interfacename":"vmall-ams.PrizeActivityService.queryPrizeResult","cluster":"cluster1"}
Category: AIOps@rate
Details: CP_all_all_sevice_failrate_over70_ams 1、告警名称：CP域服务接口错误率连续1分钟大于70%
2、告警对象：服务名称：ams_front,接口：vmall-ams.PrizeActivityService.queryPrizeResult,集群：cluster1
3、告警信息：请求总数=3.0,失败率=100%, 发生时间=2026-04-20T18:09:00
4、诊断信息：chat analysis vmall-service-errors-delimit-analysis serviceName='ams_front' interfaceName='vmall-ams.PrizeActivityService.queryPrizeResult' occurTime='2026-04-20T18:09:00'AlertName: CP域服务接口错误率连续1分钟大于70%,Site: cn_product_default,MetricName: CP_all_all_sevice_failrate_over70_ams,Category: AIOps@rate
IP Address: null
Occur Time: 2026-04-20 18:13:40+08:00
Clear Time: null
Template: unknown
Handler: mWX1336861,w00836539
Alert ID: 521600500
Tenant: 电商
Application: 电商业务
Service: VmallActivityService
Site: cn_product_default
```

### 21.2 RPC 接口时延告警原文

```text
WiseEye Alarm:
Status: OPEN
Severity: Major
Host: BP_ingresscache_service_delay_avg:{"servicename":"vmall_ingresscache_ica","interfacename":"persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2","cluster":"cluster2"}
Category: AIOps@delay
Details: BP_ingresscache_service_delay_avg
1、告警名称：ingresscache接口平均时延超3s持续5分钟
2、告警对象：服务名称vmall_ingresscache_ica，接口名称为persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2，集群：cluster2
3、告警信息：平均时延=3067, 告警发生时间=2026-04-21T10:33:00, 请求总数=49.0
4、诊断命令：chat analysis vmall-service-latency-delimit-analysis serviceName='vmall_ingresscache_ica' interfaceName='persistent://WiseCloudDMQService/WiseCloudDMQService/MQTopic_PMS.refreshAppProduct_cluster2' occurTime='2026-04-21T10:33:00'
AlertName: ingresscache接口平均时延超3s持续5分钟,Site: cn_product_default,MetricName: BP_ingresscache_service_delay_avg,Category: AIOps@delay
IP Address: null
Occur Time: 2026-04-21 10:38:31+08:00
Clear Time: null
Template: unknown
Handler: yWX1352312,y00666716
Alert ID: 521932116
Tenant: 电商
Application: 电商业务
Service: VmallOpenGWService
Site: cn_product_default
```

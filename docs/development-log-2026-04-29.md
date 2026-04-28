# AIOps 告警自动分析系统开发日志
日期：2026-04-29

## 范围说明

本次实现真实 LLM 接入——DeepSeek，并强化 `OpenAiCompatibleLlmClient` 使其具备生产可用性。

约束执行口径：
1. 不实现真实内网 Provider，继续复用现有 Provider 接口与 Mock。
2. 不修改与 LLM 客户端无关的业务模块。
3. 保持 JDK 21 兼容。
4. API Key 通过环境变量注入，不写入配置文件。

## 背景

此前阶段 8 已实现 LLM 集成骨架（`LlmClient` 抽象、`MockLlmClient`、`OpenAiCompatibleLlmClient` 骨架、`PromptBuilder`、输出校验），但默认运行路径是 `mock` 提供者。code-review-2026-04-29.md 后续建议第 2 条要求接入真实大模型 DeepSeek。

DeepSeek API 与 OpenAI 完全兼容，使用相同的 `/v1/chat/completions` 端点和 Bearer Token 认证，无需修改协议层。

## 本次完成内容

### 1. 强化 OpenAiCompatibleLlmClient

原骨架存在两个问题：没有设置 HTTP 超时、API 错误响应未解析。

本次修复：

- **HTTP 超时控制**：使用 JDK 21 `java.net.http.HttpClient` 设置 connect timeout（10 秒），通过 `JdkClientHttpRequestFactory` 将 `LlmRequest.timeout()` 映射为 read timeout
- **API 错误消息提取**：解析 API 返回的 `{"error": {"message": "..."}}` JSON 结构，将错误详情写入异常消息，便于排障
- **调用日志**：成功调用记录 model / tokens / latency / finishReason 到 SLF4J
- **移除 RestClient.Builder 注入**：改为在 `complete()` 方法内自行构建 `RestClient`，确保每次调用使用独立配置

涉及文件：
- `src/main/java/com/company/aiops/llm/client/openai/OpenAiCompatibleLlmClient.java`

### 2. 切换默认配置到 DeepSeek

修改 `application.yml`：

```yaml
aiops:
  llm:
    provider: openai-compatible   # 原 mock
    model: deepseek-chat          # 原 default-model
    base-url: ${AIOPS_LLM_BASE_URL:https://api.deepseek.com}  # 原空
```

- `api-key` 仍为 `${AIOPS_LLM_API_KEY:}`，默认空。空 API Key 时客户端会抛出 `UNAVAILABLE`，触发规则降级，不会因缺 key 导致启动失败
- `response_format: json_object` DeepSeek 完全支持
- 现有 System Prompt 已包含 "JSON" 关键词，满足 DeepSeek json_object 模式的前置要求

涉及文件：
- `src/main/resources/application.yml`

### 3. 测试适配

因默认 Provider 从 `mock` 改为 `openai-compatible`，需修复以下测试：

- **ApplicationSmokeTests**：`contextLoadsWithExpectedDefaults()` 断言从 `"mock"` 更新为 `"openai-compatible"`
- **AnalysisReplayIntegrationTests**：增加 `@SpringBootTest(properties = "aiops.llm.provider=mock")`，确保回放测试继续使用 Mock LLM
- **AnalysisJobStateMachineIntegrationTests**：同上，增加 `properties = "aiops.llm.provider=mock"`

这些测试使用 `MockLlmTokens`（如 `TIMEOUT`、`INVALID_JSON`）来模拟特定的 LLM 失败场景，必须在 mock 提供者下运行。

无需改动的测试：
- `RuleOnlyReplayIntegrationTests`：已有 `aiops.llm.enabled=false`，LLM 完全关闭
- `AlertIngressIntegrationTests`：只测试解析和去重，不触发异步分析
- `AnalysisQueryControllerIntegrationTests`：只测试查询接口，直接写 DB

涉及文件：
- `src/test/java/com/company/aiops/system/ApplicationSmokeTests.java`
- `src/test/java/com/company/aiops/analysis/replay/AnalysisReplayIntegrationTests.java`
- `src/test/java/com/company/aiops/analysis/orchestrator/state/AnalysisJobStateMachineIntegrationTests.java`

### 4. 新增 OpenAiCompatibleLlmClient 单元测试

新增 4 个测试用例：

1. `throwsUnavailableWhenBaseUrlIsBlank` — baseUrl 为空时抛出 UNAVAILABLE
2. `throwsUnavailableWhenApiKeyIsBlank` — apiKey 为空时抛出 UNAVAILABLE
3. `throwsUnavailableWhenBothBaseUrlAndApiKeyAreNull` — 两者均为 null 时抛出 UNAVAILABLE
4. `usesDeepSeekModelNameFromConfiguration` — 验证配置绑定正确传递 deepseek-chat 模型名

这些测试不发起真实 HTTP 请求，只验证配置校验路径。

涉及文件：
- `src/test/java/com/company/aiops/llm/client/openai/OpenAiCompatibleLlmClientTests.java`（新增）

## 测试与验证

验证情况：

1. 执行 `mvn -q -DskipTests test-compile`，通过。
2. 执行纯单元测试（不含需 MySQL 的集成测试），38 个测试全部通过，其中包含新增的 4 个 `OpenAiCompatibleLlmClientTests`。

## 使用方式

启动前设置环境变量：

```powershell
$env:AIOPS_LLM_API_KEY = "sk-your-deepseek-api-key"
```

然后正常启动应用。系统将对每条告警调用 DeepSeek `deepseek-chat` 模型生成分析结果。

若未设置 API Key，LLM 调用将返回 `UNAVAILABLE`，任务自动降级到规则引擎（`RULE_BASED_DEGRADED`），不影响系统可用性。

## 当前注意事项

1. 真实 LLM 已接入，但真实 Provider（监控指标、日志、Trace 等数据源）仍为 Mock。端到端回放仍依赖本地样例数据。
2. `OpenAiCompatibleLlmClient` 已支持任意 OpenAI-compatible 提供者（DeepSeek、OpenAI、Moonshot 等），只需修改 `aiops.llm.base-url` 和 `aiops.llm.model`。
3. 全量集成测试（27 个）仍依赖本地 MySQL；重启机器后需先按 `LOCAL_MYSQL.md` 启动 `127.0.0.1:3307` 实例。
4. API Key 为空时服务可正常启动，但 LLM 路径不可用；查询接口返回的结果会标注 `analysisMode=RULE_BASED_DEGRADED` 和 `degradedReason` 说明降级原因。

---

## 补充记录：Prompt 调优与真实 LLM 端到端验证

### 范围说明

在完成 DeepSeek 接入配置后，首次运行真实 LLM 调用发现 DeepSeek 输出的 JSON 结构与系统 Schema 不完全匹配，需要调优 Prompt 以提高格式遵循率。

### 问题发现

首次运行 `DeepSeekLlmIntegrationTest` 时，3 个测试全部因为 LLM 输出校验失败而进入 `DEGRADED_SUCCESS`。查询 `aiops_llm_call_log` 发现两类结构问题：

1. **`impact` 字段类型错误**：LLM 输出 `"impact": "影响范围有限..."`（字符串），而 Schema 要求 `{"level": "...", "description": "..."}`（对象）
2. **字段名错误**：LLM 输出 `"rootCauseHypotheses"` 而非 `"rootCauses"`，`"priority"` 作为顶层字段而非在 `recommendations[*]` 内

根因：DeepSeek 模型对自然语言描述的 JSON Schema 遵循度不如显式的 JSON 模板示例。

### 修复：Prompt 增强

在 `RpcErrorRatePromptBuilder` 和 `RpcLatencyPromptBuilder` 的 System Prompt 中增加：

1. **显式 JSON 输出模板**：列出完整的 JSON 结构示例，标注每个字段的类型
2. **causeType 枚举列表**：列出全部 15 个有效值
3. **actionType 枚举列表**：列出全部 10 个有效值（CHECK_TRACE / CHECK_LOG / CHECK_METRIC 等）
4. **priority 枚举约束**：明确 P0-P3 四个级别
5. **关键约束强调**："impact 必须是包含 level 和 description 的对象，不能是字符串"

同时优化了重试时的错误提示——在 validation error 基础上，追加当前证据包中所有有效 evidenceId 列表，并特别提醒 "impact 必须是对象"、"字段名必须是 rootCauses"。

涉及文件：
- `src/main/java/com/company/aiops/llm/prompt/RpcErrorRatePromptBuilder.java`
- `src/main/java/com/company/aiops/llm/prompt/RpcLatencyPromptBuilder.java`

### 新增 DeepSeek 端到端集成测试

新增 `DeepSeekLlmIntegrationTest`，包含 3 个测试用例：

| 测试 | 描述 |
|---|---|
| `errorRateAlertWithRealDeepSeekLlmProducesLlmAssistedResult` | 错误率告警 + Mock Provider 数据 + 真实 DeepSeek → 验证返回 `LLM_ASSISTED` |
| `latencyAlertWithRealDeepSeekLlmProducesLlmAssistedResult` | 时延告警 + Mock Provider 数据 + 真实 DeepSeek → 验证下游慢点识别 |
| `llmCallLogIsPersistedAfterSuccessfulDeepSeekCall` | 验证 LLM 调用日志正确写入 `aiops_llm_call_log` |

测试特点：
- 使用 `@Tag("llm")` 标记，可通过 `-Dgroups='!llm'` 排除以跳过真实 API 调用
- 通过 `@SpringBootTest(properties)` 注入 API Key，不写入源码
- 超时设为 60 秒（`waitForTerminalTaskLongTimeout`），应对真实 API 延迟
- 每次测试前自动清理数据库（继承 `AbstractAiopsIntegrationTestSupport`）

涉及文件：
- `src/test/java/com/company/aiops/analysis/replay/DeepSeekLlmIntegrationTest.java`（新增）

### 验证结果

Prompt 调优后，3 个 DeepSeek 测试全部通过：

**错误率告警：**
- 1 次调用成功，无需重试
- 输入 3,328 tokens，输出 749 tokens，延迟 9.5s
- Top 根因：`BUSINESS_ERROR_CODE_NOT_MASKED` (conf=0.9)
- 整体置信度：0.85，analysisMode：`LLM_ASSISTED`

**时延告警：**
- 1 次调用成功，无需重试
- 输入 3,380 tokens，输出 897 tokens，延迟 11.4s
- Top 根因：`DOWNSTREAM_RPC_LATENCY` (conf=0.85)
- 整体置信度：0.75，analysisMode：`LLM_ASSISTED`

**LLM 调用日志：**
- `aiops_llm_call_log` 写入正常，`call_status=SUCCESS`，`provider=openai-compatible`

### 实际运行模型

配置的 `model: deepseek-chat`，DeepSeek 服务端自动路由到 `deepseek-v4-flash`。实际请求/响应数据见开发日志末尾的补充说明。

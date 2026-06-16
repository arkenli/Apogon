# 服务级 Prompt 模板实现计划

> **给自动化执行者：**必须使用 `subagent-driven-development`（推荐）或 `executing-plans` 按任务逐项执行。步骤使用复选框语法跟踪。

**目标：**新增数据库维护的微服务自定义 Prompt 模板，让 LLM 分析按 `serviceName + alertCategory` 替换分析指令，同时保留代码内固定 JSON 输出约束和校验修复提示。

**架构：**新增 `aiops_service_prompt_template` 表和 JDBC Repository 读取启用模板；在 `llm.prompt` 包内增加 resolver，并让现有 `RpcErrorRatePromptBuilder`、`RpcLatencyPromptBuilder` 在构造消息时先查服务模板。数据库模板只替换分析说明，不拥有最终 JSON schema、枚举约束、evidenceId 约束和 retry 修复提示。

**技术栈：**Java 21、Spring Boot 3.2、JdbcTemplate、Flyway、JUnit 5、AssertJ、Mockito。

---

## 文件结构

- 新增：`src/main/resources/db/migration/V4__create_service_prompt_template.sql`
  - 创建 `aiops_service_prompt_template` 表。
- 新增：`src/main/java/com/company/aiops/llm/prompt/model/ServicePromptTemplateRecord.java`
  - 表行模型。
- 新增：`src/main/java/com/company/aiops/persistence/repository/ServicePromptTemplateRepository.java`
  - 查询启用模板的 Repository 接口。
- 新增：`src/main/java/com/company/aiops/persistence/repository/jdbc/JdbcServicePromptTemplateRepository.java`
  - JdbcTemplate 实现。
- 新增：`src/main/java/com/company/aiops/llm/prompt/ServicePromptTemplateResolver.java`
  - Builder 使用的模板解析接口，包含 `none()` 默认实现。
- 新增：`src/main/java/com/company/aiops/llm/prompt/DatabaseServicePromptTemplateResolver.java`
  - 基于 Repository 的 Spring Bean 实现。
- 修改：`src/main/java/com/company/aiops/llm/prompt/RpcErrorRatePromptBuilder.java`
  - 拆分可替换分析说明和固定 JSON 契约，接入 resolver。
- 修改：`src/main/java/com/company/aiops/llm/prompt/RpcLatencyPromptBuilder.java`
  - 同上。
- 修改：`src/test/java/com/company/aiops/llm/prompt/RpcErrorRatePromptBuilderTests.java`
  - 增加服务模板命中、模板未命中、retry 修复提示测试。
- 新增：`src/test/java/com/company/aiops/llm/prompt/RpcLatencyPromptBuilderTests.java`
  - 增加时延 builder 服务模板命中测试。
- 新增：`src/test/java/com/company/aiops/llm/prompt/DatabaseServicePromptTemplateResolverTests.java`
  - 增加 blank serviceName 和 Repository 委托测试。
- 新增：`src/test/java/com/company/aiops/persistence/repository/jdbc/JdbcServicePromptTemplateRepositoryIntegrationTests.java`
  - 验证只读取启用且匹配的模板。

## 任务 1：写服务模板命中 Prompt 的失败测试

**文件：**
- 修改：`src/test/java/com/company/aiops/llm/prompt/RpcErrorRatePromptBuilderTests.java`
- 新增：`src/test/java/com/company/aiops/llm/prompt/RpcLatencyPromptBuilderTests.java`

- [ ] **步骤 1：在错误率 builder 测试中加入模板命中用例**

在 `RpcErrorRatePromptBuilderTests` 中新增 import：

```java
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;

import java.util.Optional;
```

新增测试：

```java
@Test
void buildMessagesUsesServiceTemplateButKeepsFixedJsonContract() {
    RpcErrorRatePromptBuilder builder = new RpcErrorRatePromptBuilder(
            new JsonMapper(new ObjectMapper().findAndRegisterModules()),
            (serviceName, category) -> Optional.of(new ServicePromptTemplateRecord(
                    1L,
                    "demo-service",
                    AlertCategory.RPC_ERROR_RATE,
                    "你是 ams_front 专属告警分析助手，只按订单域语义分析。",
                    "请按 ams_front 的订单活动链路分析错误率告警。",
                    true,
                    "v1",
                    "{\"owner\":\"order-team\"}",
                    "seed",
                    "seed",
                    Instant.parse("2026-06-16T00:00:00Z"),
                    Instant.parse("2026-06-16T00:00:00Z")
            ))
    );

    String combinedPrompt = String.join("\n", builder.buildMessages(compressedEvidence(), null).stream()
            .map(message -> message.content())
            .toList());

    assertThat(combinedPrompt).contains("你是 ams_front 专属告警分析助手");
    assertThat(combinedPrompt).contains("请按 ams_front 的订单活动链路分析错误率告警");
    assertThat(combinedPrompt).contains("\"rootCauses\"");
    assertThat(combinedPrompt).contains("causeType 必须是以下之一");
    assertThat(combinedPrompt).contains("evidenceIds 必须引用证据包中存在的 evidenceId");
    assertThat(combinedPrompt).contains("请严格按照 system prompt 中定义的 JSON 格式输出");
}
```

- [ ] **步骤 2：在错误率 builder 测试中加入 retry 修复提示用例**

新增测试：

```java
@Test
void buildMessagesKeepsValidationRepairPromptWhenTemplateIsUsed() {
    RpcErrorRatePromptBuilder builder = new RpcErrorRatePromptBuilder(
            new JsonMapper(new ObjectMapper().findAndRegisterModules()),
            (serviceName, category) -> Optional.of(new ServicePromptTemplateRecord(
                    1L,
                    "demo-service",
                    AlertCategory.RPC_ERROR_RATE,
                    "服务专属 system prompt",
                    "服务专属 user prompt",
                    true,
                    "v1",
                    null,
                    "seed",
                    "seed",
                    Instant.parse("2026-06-16T00:00:00Z"),
                    Instant.parse("2026-06-16T00:00:00Z")
            ))
    );

    List<String> contents = builder.buildMessages(compressedEvidence(), "impact must be object").stream()
            .map(message -> message.content())
            .toList();

    assertThat(contents).hasSize(3);
    assertThat(contents.get(2)).contains("上一次输出校验失败");
    assertThat(contents.get(2)).contains("impact must be object");
    assertThat(contents.get(2)).contains("alert.request.count:request count");
    assertThat(contents.get(2)).contains("请仅返回合法 JSON");
}
```

- [ ] **步骤 3：在错误率 builder 测试中加入模板未命中用例**

新增测试：

```java
@Test
void buildMessagesFallsBackToDefaultPromptWhenTemplateIsMissing() {
    RpcErrorRatePromptBuilder builder = new RpcErrorRatePromptBuilder(
            new JsonMapper(new ObjectMapper().findAndRegisterModules()),
            ServicePromptTemplateResolver.none()
    );

    String combinedPrompt = String.join("\n", builder.buildMessages(compressedEvidence(), null).stream()
            .map(message -> message.content())
            .toList());

    assertThat(combinedPrompt).contains("你是电商微服务 AIOps 告警分析助手");
    assertThat(combinedPrompt).contains("请分析以下 RPC 接口错误率告警");
    assertThat(combinedPrompt).contains("系统内部错误");
}
```

- [ ] **步骤 4：新增时延 builder 模板命中测试文件**

创建 `RpcLatencyPromptBuilderTests.java`：

```java
package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.common.json.JsonMapper;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RpcLatencyPromptBuilderTests {

    @Test
    void buildMessagesUsesServiceTemplateButKeepsFixedJsonContract() {
        RpcLatencyPromptBuilder builder = new RpcLatencyPromptBuilder(
                new JsonMapper(new ObjectMapper().findAndRegisterModules()),
                (serviceName, category) -> Optional.of(new ServicePromptTemplateRecord(
                        2L,
                        "latency-service",
                        AlertCategory.RPC_LATENCY,
                        "你是 latency-service 专属时延分析助手。",
                        "请优先分析缓存、CSE 和下游支付链路。",
                        true,
                        "v1",
                        null,
                        "seed",
                        "seed",
                        Instant.parse("2026-06-16T00:00:00Z"),
                        Instant.parse("2026-06-16T00:00:00Z")
                ))
        );

        String combinedPrompt = String.join("\n", builder.buildMessages(compressedEvidence(), null).stream()
                .map(message -> message.content())
                .toList());

        assertThat(combinedPrompt).contains("你是 latency-service 专属时延分析助手");
        assertThat(combinedPrompt).contains("请优先分析缓存、CSE 和下游支付链路");
        assertThat(combinedPrompt).contains("\"recommendations\"");
        assertThat(combinedPrompt).contains("priority 必须是 P0 / P1 / P2 / P3");
        assertThat(combinedPrompt).contains("不要输出 Markdown，不要输出 JSON 之外的任何文本");
    }

    private CompressedEvidenceBundle compressedEvidence() {
        Instant occurTime = Instant.parse("2026-04-21T02:33:00Z");
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.RPC_LATENCY,
                        "latency-service",
                        "demo.Interface.query",
                        "cluster1",
                        occurTime,
                        "alert.request.count",
                        100.0,
                        "alert.fail.rate",
                        null,
                        "alert.avg.latency",
                        1200L,
                        "alert.p99.latency",
                        3200L
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null, "baseline.request.count", "baseline.fail.rate", "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(true, null, "interface.request.count", 100, "interface.failure.rate", 0, 0.0, "interface.latency.profile", 1200L, 1800L, 3200L, 4100L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(true, null, ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty("latency-service", "cluster1"),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(new EvidenceReference("alert.p99.latency", "ALERT", "p99 latency")),
                List.of(),
                List.of()
        );
    }
}
```

- [ ] **步骤 5：运行窄测试，确认失败**

运行：

```bash
mvn -Dtest=RpcErrorRatePromptBuilderTests,RpcLatencyPromptBuilderTests test
```

预期：失败，原因是 `ServicePromptTemplateRecord`、`ServicePromptTemplateResolver` 或新的 builder 构造函数尚不存在。

## 任务 2：实现模板模型、resolver 和 builder 接入

**文件：**
- 新增：`src/main/java/com/company/aiops/llm/prompt/model/ServicePromptTemplateRecord.java`
- 新增：`src/main/java/com/company/aiops/llm/prompt/ServicePromptTemplateResolver.java`
- 修改：`src/main/java/com/company/aiops/llm/prompt/RpcErrorRatePromptBuilder.java`
- 修改：`src/main/java/com/company/aiops/llm/prompt/RpcLatencyPromptBuilder.java`

- [ ] **步骤 1：新增模板记录模型**

创建 `ServicePromptTemplateRecord.java`：

```java
package com.company.aiops.llm.prompt.model;

import com.company.aiops.alert.model.AlertCategory;

import java.time.Instant;

/**
 * One stored row from aiops_service_prompt_template.
 */
public record ServicePromptTemplateRecord(
        Long id,
        String serviceName,
        AlertCategory alertCategory,
        String systemPrompt,
        String userPrompt,
        boolean enabled,
        String version,
        String metadataJson,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
```

- [ ] **步骤 2：新增 resolver 接口**

创建 `ServicePromptTemplateResolver.java`：

```java
package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;

import java.util.Optional;

@FunctionalInterface
public interface ServicePromptTemplateResolver {

    Optional<ServicePromptTemplateRecord> resolve(String serviceName, AlertCategory alertCategory);

    static ServicePromptTemplateResolver none() {
        return (serviceName, alertCategory) -> Optional.empty();
    }
}
```

- [ ] **步骤 3：重构错误率 builder**

在 `RpcErrorRatePromptBuilder` 中：

- 引入 `ServicePromptTemplateResolver` 字段。
- 将现有 `SYSTEM_PROMPT` 拆为 `DEFAULT_SYSTEM_INSTRUCTIONS` 和 `FIXED_OUTPUT_CONTRACT`。
- 将现有 user 文本拆为 `DEFAULT_USER_INSTRUCTIONS`。
- 保留单参数构造函数给现有单元测试使用，新增 Spring 使用的双参数构造函数并标注 `@Autowired`。
- `buildMessages` 中使用 `compressedEvidence.alert().serviceName()` 查询模板。

关键实现形态：

```java
private static final String DEFAULT_SYSTEM_INSTRUCTIONS = """
        你是电商微服务 AIOps 告警分析助手。

        你只能基于用户提供的结构化证据进行分析，不能编造任何不存在的指标、日志、trace、服务名、接口名、errorCode 或资源实例。
        如果证据不足，必须明确说明"不确定"。

        你的任务是：
        1. 判断告警影响和优先级。
        2. 给出最多 5 个根因假设，并按置信度降序排列。
        3. 每个根因假设必须引用 evidenceIds。
        4. 给出可执行的人工排查建议，但不能建议系统已经自动执行任何动作。
        5. 对错误率告警，需要特别根据 errorMsg 判断是否可能是业务 errorCode 未屏蔽；如果同一 errorCode 的代表性 errorMsg 为“%s”等系统异常语义，且日志、trace 或其他证据里没有明确表明这是正常业务校验导致的直接证据，则应优先按需要处理的代码问题或系统故障分析。仅出现业务功能名、业务场景词或 feature 描述，不足以判定为正常业务校验。请求量较小时只能说明影响范围可能有限，不能判定为误报或跳过分析。
        """.formatted(INTERNAL_ERROR_MESSAGE);

private static final String FIXED_OUTPUT_CONTRACT = """
        你必须输出如下格式的 JSON 对象：

        {
          "summary": "一句话总结告警原因和处理优先级",
          "impact": {
            "level": "LOW 或 MEDIUM 或 HIGH 或 UNKNOWN",
            "description": "详细影响说明"
          },
          "rootCauses": [
            {
              "causeType": "根因类型枚举值",
              "description": "根因描述",
              "confidence": 0.85,
              "evidenceIds": ["证据ID1", "证据ID2"],
              "reasoning": "推理过程说明"
            }
          ],
          "recommendations": [
            {
              "priority": "P0 或 P1 或 P2 或 P3",
              "actionType": "建议动作类型",
              "description": "建议描述",
              "target": "目标对象"
            }
          ],
          "overallConfidence": 0.78,
          "insufficientEvidence": ["证据不足项1", "证据不足项2"]
        }

        重要约束：
        - impact 必须是包含 level 和 description 的对象，不能是字符串
        - causeType 必须是以下之一：BUSINESS_ERROR_CODE_NOT_MASKED, LOW_TRAFFIC_LIMITED_IMPACT, DOWNSTREAM_RPC_FAILURE, DOWNSTREAM_RPC_LATENCY, DATABASE_SLOW_QUERY, DATABASE_RESOURCE_PRESSURE, DCS_LATENCY_OR_FAILURE, MQ_BACKLOG_OR_LATENCY, CONTAINER_CPU_PRESSURE, CONTAINER_MEMORY_PRESSURE, GC_PRESSURE, INSTANCE_STARTING_OR_FAILED, SINGLE_INSTANCE_ABNORMAL, TRAFFIC_SPIKE, UNKNOWN
        - evidenceIds 必须引用证据包中存在的 evidenceId
        - priority 必须是 P0 / P1 / P2 / P3
        - actionType 必须是以下之一：CHECK_TRACE, CHECK_LOG, CHECK_METRIC, CHECK_DOWNSTREAM, CHECK_DATABASE, CHECK_DCS, CHECK_MQ, EVALUATE_ERROR_CODE_MASKING, TUNE_ALERT_RULE, MANUAL_CONFIRMATION
        - overallConfidence 必须是 0 到 1 之间的数字

        不要输出 Markdown，不要输出 JSON 之外的任何文本。
        """;
```

组装方法：

```java
private String systemPrompt(ServicePromptTemplateRecord template) {
    String instructions = template == null ? DEFAULT_SYSTEM_INSTRUCTIONS : template.systemPrompt();
    return instructions.strip() + "\n\n" + FIXED_OUTPUT_CONTRACT;
}

private String userPrompt(ServicePromptTemplateRecord template, CompressedEvidenceBundle compressedEvidence) {
    String instructions = template == null ? DEFAULT_USER_INSTRUCTIONS : template.userPrompt();
    return """
            %s

            证据 JSON：
            %s

            请严格按照 system prompt 中定义的 JSON 格式输出，不要输出任何其他文本。
            """.formatted(instructions.strip(), jsonMapper.toJson(compressedEvidence));
}

private ServicePromptTemplateRecord resolveTemplate(CompressedEvidenceBundle compressedEvidence) {
    if (compressedEvidence == null || compressedEvidence.alert() == null) {
        return null;
    }
    return templateResolver.resolve(compressedEvidence.alert().serviceName(), supportedCategory())
            .orElse(null);
}
```

- [ ] **步骤 4：按同样结构重构时延 builder**

`RpcLatencyPromptBuilder` 使用相同字段和方法结构，默认分析说明保留当前时延 prompt 的内容，固定输出契约与错误率 builder 一致。

- [ ] **步骤 5：运行窄测试，确认通过**

运行：

```bash
mvn -Dtest=RpcErrorRatePromptBuilderTests,RpcLatencyPromptBuilderTests test
```

预期：测试通过。

## 任务 3：写数据库 Repository 和 resolver 的失败测试

**文件：**
- 新增：`src/test/java/com/company/aiops/llm/prompt/DatabaseServicePromptTemplateResolverTests.java`
- 新增：`src/test/java/com/company/aiops/persistence/repository/jdbc/JdbcServicePromptTemplateRepositoryIntegrationTests.java`

- [ ] **步骤 1：新增 resolver 单元测试**

创建 `DatabaseServicePromptTemplateResolverTests.java`：

```java
package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseServicePromptTemplateResolverTests {

    @Test
    void resolveReturnsEmptyForBlankServiceName() {
        ServicePromptTemplateRepository repository = mock(ServicePromptTemplateRepository.class);
        DatabaseServicePromptTemplateResolver resolver = new DatabaseServicePromptTemplateResolver(repository);

        assertThat(resolver.resolve("  ", AlertCategory.RPC_ERROR_RATE)).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void resolveDelegatesToRepositoryForNonBlankServiceName() {
        ServicePromptTemplateRepository repository = mock(ServicePromptTemplateRepository.class);
        ServicePromptTemplateRecord record = new ServicePromptTemplateRecord(
                1L,
                "ams_front",
                AlertCategory.RPC_ERROR_RATE,
                "system",
                "user",
                true,
                "v1",
                null,
                "seed",
                "seed",
                Instant.parse("2026-06-16T00:00:00Z"),
                Instant.parse("2026-06-16T00:00:00Z")
        );
        when(repository.findEnabledByServiceNameAndAlertCategory("ams_front", AlertCategory.RPC_ERROR_RATE))
                .thenReturn(Optional.of(record));
        DatabaseServicePromptTemplateResolver resolver = new DatabaseServicePromptTemplateResolver(repository);

        assertThat(resolver.resolve("  ams_front  ", AlertCategory.RPC_ERROR_RATE)).contains(record);

        verify(repository).findEnabledByServiceNameAndAlertCategory("ams_front", AlertCategory.RPC_ERROR_RATE);
    }
}
```

- [ ] **步骤 2：新增 Repository 集成测试**

创建 `JdbcServicePromptTemplateRepositoryIntegrationTests.java`：

```java
package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JdbcServicePromptTemplateRepositoryIntegrationTests {

    private static final String SERVICE_NAME = "it-service-prompt";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServicePromptTemplateRepository repository;

    @BeforeEach
    void cleanTemplates() {
        jdbcTemplate.update("DELETE FROM aiops_service_prompt_template WHERE service_name = ?", SERVICE_NAME);
    }

    @Test
    void findEnabledByServiceNameAndAlertCategoryReturnsEnabledMatchingTemplate() {
        insertTemplate(AlertCategory.RPC_ERROR_RATE, true, "错误率 system", "错误率 user");
        insertTemplate(AlertCategory.RPC_LATENCY, true, "时延 system", "时延 user");

        Optional<ServicePromptTemplateRecord> record = repository.findEnabledByServiceNameAndAlertCategory(
                SERVICE_NAME,
                AlertCategory.RPC_ERROR_RATE
        );

        assertThat(record).isPresent();
        assertThat(record.get().serviceName()).isEqualTo(SERVICE_NAME);
        assertThat(record.get().alertCategory()).isEqualTo(AlertCategory.RPC_ERROR_RATE);
        assertThat(record.get().systemPrompt()).isEqualTo("错误率 system");
        assertThat(record.get().userPrompt()).isEqualTo("错误率 user");
        assertThat(record.get().enabled()).isTrue();
    }

    @Test
    void findEnabledByServiceNameAndAlertCategoryIgnoresDisabledTemplate() {
        insertTemplate(AlertCategory.RPC_ERROR_RATE, false, "disabled system", "disabled user");

        Optional<ServicePromptTemplateRecord> record = repository.findEnabledByServiceNameAndAlertCategory(
                SERVICE_NAME,
                AlertCategory.RPC_ERROR_RATE
        );

        assertThat(record).isEmpty();
    }

    private void insertTemplate(
            AlertCategory category,
            boolean enabled,
            String systemPrompt,
            String userPrompt
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_service_prompt_template (
                            service_name,
                            alert_category,
                            system_prompt,
                            user_prompt,
                            enabled,
                            version,
                            metadata_json,
                            created_by,
                            updated_by,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """,
                SERVICE_NAME,
                category.name(),
                systemPrompt,
                userPrompt,
                enabled,
                "v1",
                "{\"seed\":\"integration-test\"}",
                "integration-test",
                "integration-test"
        );
    }
}
```

- [ ] **步骤 3：运行新测试，确认失败**

运行：

```bash
mvn -Dtest=DatabaseServicePromptTemplateResolverTests,JdbcServicePromptTemplateRepositoryIntegrationTests test
```

预期：失败，原因是 Repository、resolver 实现或数据库表尚不存在。

## 任务 4：实现数据库迁移、Repository 和数据库 resolver

**文件：**
- 新增：`src/main/resources/db/migration/V4__create_service_prompt_template.sql`
- 新增：`src/main/java/com/company/aiops/persistence/repository/ServicePromptTemplateRepository.java`
- 新增：`src/main/java/com/company/aiops/persistence/repository/jdbc/JdbcServicePromptTemplateRepository.java`
- 新增：`src/main/java/com/company/aiops/llm/prompt/DatabaseServicePromptTemplateResolver.java`

- [ ] **步骤 1：新增 Flyway migration**

创建 `V4__create_service_prompt_template.sql`：

```sql
CREATE TABLE aiops_service_prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Auto-increment primary key',
    service_name VARCHAR(256) NOT NULL COMMENT 'Service name from the alert',
    alert_category VARCHAR(64) NOT NULL COMMENT 'AlertCategory name',
    system_prompt MEDIUMTEXT NOT NULL COMMENT 'Replaceable system analysis instructions',
    user_prompt MEDIUMTEXT NOT NULL COMMENT 'Replaceable user analysis instructions',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Whether this prompt template is enabled',
    version VARCHAR(128) COMMENT 'Template version label',
    metadata_json JSON COMMENT 'Optional metadata such as owner or rollout notes',
    created_by VARCHAR(128) COMMENT 'Creator',
    updated_by VARCHAR(128) COMMENT 'Last updater',
    created_at DATETIME(3) NOT NULL COMMENT 'Creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'Last update time',
    UNIQUE KEY uk_service_prompt_template (service_name, alert_category),
    KEY idx_service_category_enabled (service_name, alert_category, enabled),
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Service-specific LLM prompt template table';
```

- [ ] **步骤 2：新增 Repository 接口**

创建 `ServicePromptTemplateRepository.java`：

```java
package com.company.aiops.persistence.repository;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;

import java.util.Optional;

/**
 * Reads service-specific prompt templates used by LLM prompt builders.
 */
public interface ServicePromptTemplateRepository {

    Optional<ServicePromptTemplateRecord> findEnabledByServiceNameAndAlertCategory(
            String serviceName,
            AlertCategory alertCategory
    );
}
```

- [ ] **步骤 3：新增 JDBC Repository 实现**

创建 `JdbcServicePromptTemplateRepository.java`：

```java
package com.company.aiops.persistence.repository.jdbc;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for service-specific LLM prompt templates.
 */
@Repository
public class JdbcServicePromptTemplateRepository implements ServicePromptTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcServicePromptTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ServicePromptTemplateRecord> findEnabledByServiceNameAndAlertCategory(
            String serviceName,
            AlertCategory alertCategory
    ) {
        List<ServicePromptTemplateRecord> records = jdbcTemplate.query(
                """
                        SELECT id, service_name, alert_category, system_prompt, user_prompt,
                               enabled, version, metadata_json, created_by, updated_by,
                               created_at, updated_at
                        FROM aiops_service_prompt_template
                        WHERE service_name = ? AND alert_category = ? AND enabled = 1
                        """,
                this::mapRecord,
                serviceName,
                alertCategory.name()
        );
        return records.stream().findFirst();
    }

    private ServicePromptTemplateRecord mapRecord(ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ServicePromptTemplateRecord(
                rs.getLong("id"),
                rs.getString("service_name"),
                AlertCategory.valueOf(rs.getString("alert_category")),
                rs.getString("system_prompt"),
                rs.getString("user_prompt"),
                rs.getBoolean("enabled"),
                rs.getString("version"),
                rs.getString("metadata_json"),
                rs.getString("created_by"),
                rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
```

- [ ] **步骤 4：新增数据库 resolver**

创建 `DatabaseServicePromptTemplateResolver.java`：

```java
package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.llm.prompt.model.ServicePromptTemplateRecord;
import com.company.aiops.persistence.repository.ServicePromptTemplateRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DatabaseServicePromptTemplateResolver implements ServicePromptTemplateResolver {

    private final ServicePromptTemplateRepository repository;

    public DatabaseServicePromptTemplateResolver(ServicePromptTemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ServicePromptTemplateRecord> resolve(String serviceName, AlertCategory alertCategory) {
        if (!hasText(serviceName) || alertCategory == null) {
            return Optional.empty();
        }
        return repository.findEnabledByServiceNameAndAlertCategory(serviceName.trim(), alertCategory);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **步骤 5：运行 Repository 和 resolver 测试，确认通过**

运行：

```bash
mvn -Dtest=DatabaseServicePromptTemplateResolverTests,JdbcServicePromptTemplateRepositoryIntegrationTests test
```

预期：测试通过。

## 任务 5：回归服务层和全量验证

**文件：**
- 修改：`src/test/java/com/company/aiops/llm/service/DefaultLlmAnalysisServiceTests.java`

- [ ] **步骤 1：修复现有服务测试构造器调用**

如果 `DefaultLlmAnalysisServiceTests` 仍使用单参数 builder 构造函数，可以保留不改；如果实现时删除了单参数构造函数，则改为：

```java
List<PromptBuilder> promptBuilders = List.of(
        new RpcErrorRatePromptBuilder(jsonMapper, ServicePromptTemplateResolver.none()),
        new RpcLatencyPromptBuilder(jsonMapper, ServicePromptTemplateResolver.none())
);
```

计划推荐保留单参数构造函数，因此这一步通常不需要改动。

- [ ] **步骤 2：运行 LLM 相关窄测试**

运行：

```bash
mvn -Dtest=RpcErrorRatePromptBuilderTests,RpcLatencyPromptBuilderTests,DatabaseServicePromptTemplateResolverTests,DefaultLlmAnalysisServiceTests test
```

预期：测试通过。

- [ ] **步骤 3：运行 Repository 集成测试**

运行：

```bash
mvn -Dtest=JdbcServicePromptTemplateRepositoryIntegrationTests test
```

预期：测试通过。

- [ ] **步骤 4：运行全量测试**

运行：

```bash
mvn test
```

预期：全部测试通过。

- [ ] **步骤 5：检查 Git 状态**

运行：

```bash
git status --short
```

预期：只包含本功能相关文件，以及用户原本未跟踪的 `Daumard_1990_Affaire_amour_affection_mariage_bourgeois_XIXe_OCR.pdf`。

- [ ] **步骤 6：提交实现**

运行：

```bash
git add src/main/resources/db/migration/V4__create_service_prompt_template.sql src/main/java/com/company/aiops/llm/prompt src/main/java/com/company/aiops/persistence/repository src/test/java/com/company/aiops/llm/prompt src/test/java/com/company/aiops/persistence/repository/jdbc
git commit -m "feat: support service prompt templates"
```

预期：实现提交创建在 `codex/service-prompt-template-db` 分支。

## 自检

- 设计覆盖：数据库表、启用模板查询、`serviceName + alertCategory` 选择、默认 fallback、固定 JSON 契约、retry 修复提示、测试验证均已有任务覆盖。
- 占位扫描：本文档没有未定需求、空步骤或要求执行者自行补全的内容。
- 类型一致性：计划中统一使用 `ServicePromptTemplateRecord`、`ServicePromptTemplateRepository`、`ServicePromptTemplateResolver`、`DatabaseServicePromptTemplateResolver` 命名。

# AIOps 告警自动分析系统开发日志

日期：2026-04-23  
本次提交：`021154b 实现 AIOps 告警接入与存储骨架`

## 已完成范围

已完成 `aiops-alert-analysis-development-plan.md` 中阶段 0、阶段 1、阶段 2 的主要内容。

### 阶段 0：项目初始化

1. 将 Maven 空项目整理为 Spring Boot 后端项目。
2. 建立基础包结构：
   - `com.company.aiops.alert`
   - `com.company.aiops.analysis`
   - `com.company.aiops.provider`
   - `com.company.aiops.llm`
   - `com.company.aiops.persistence`
   - `com.company.aiops.common`
3. 新增统一 API 响应模型 `ApiResponse`。
4. 新增统一异常模型和 `GlobalExceptionHandler`。
5. 新增健康检查接口：`GET /api/v1/health`。
6. 新增 `application.yml`，写入设计文档第 16 章配置项。

### 阶段 1：数据库与领域模型

1. 引入 `spring-boot-starter-jdbc`、Flyway、MySQL Driver。
2. 使用 Flyway 创建 9 张核心表：
   - `aiops_alert_event`
   - `aiops_analysis_task`
   - `aiops_analysis_result`
   - `aiops_evidence_bundle`
   - `aiops_provider_call_log`
   - `aiops_llm_call_log`
   - `aiops_duplicate_alert_link`
   - `aiops_resource_mapping`
   - `aiops_analysis_job_state_log`
3. 新增 ResourceMapping 初始化数据。
4. 实现核心枚举和 DTO/record：
   - `AlertCategory`
   - `AnalysisJobStatus`
   - `AnalysisMode`
   - `ProviderStatus`
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
5. 实现基础 JDBC Repository：
   - `AlertEventRepository`
   - `AnalysisTaskRepository`
   - `AnalysisResultRepository`
   - `DuplicateAlertLinkRepository`
   - `ResourceMappingRepository`
6. 实现 `ResourceMappingProvider`，从 MySQL 的 `aiops_resource_mapping` 读取预置映射。

### 阶段 2：告警接入、解析、分类和去重

1. 实现 Webhook 接口：`POST /api/v1/alerts/webhook`。
2. 实现 `AlertParser`，当前实现类为 `WiseEyeAlertParser`。
3. 支持解析 WiseEye 原始告警中的：
   - `Alert ID`
   - `Category`
   - `Host` JSON
   - `Details` 中的请求量、失败率、平均时延、发生时间
   - `Occur Time`
4. 实现 `AlertClassifier`：
   - `AIOps@rate` -> `RPC_ERROR_RATE`
   - `AIOps@delay` -> `RPC_LATENCY`
   - metricName fallback 分类
5. 实现 dedupeKey：

```text
category|serviceName|interfaceName|cluster
```

6. 实现 5 分钟内重复告警合并：
   - 不创建新任务
   - 返回已有 `taskId`
   - 写入 `aiops_duplicate_alert_link`
7. 实现 5 分钟外历史参考：
   - 不阻止本次任务创建
   - 返回最近历史完成结果的 `historicalReference`
8. 新告警会创建 `PENDING` 状态的 `AnalysisJob`。

## 本地 MySQL

本地测试 MySQL 已安装并初始化。详细连接方式记录在本地忽略文件 `LOCAL_MYSQL.md` 中。

关键连接信息：

```text
host: 127.0.0.1
port: 3307
database: aiops_alert_analysis
username: aiops_test
password: aiops_test_123
```

JDBC URL：

```text
jdbc:mysql://127.0.0.1:3307/aiops_alert_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

如果机器重启，先启动 MySQL：

```powershell
& C:\aiops-mysql\start-aiops-mysql.ps1
```

## 验证情况

已运行：

```powershell
mvn test
```

结果：通过。  
当前测试数：12 个。

测试覆盖：

1. Spring Boot 上下文启动。
2. 健康检查接口。
3. 配置绑定。
4. 枚举序列化。
5. JSON 字段序列化/反序列化。
6. Flyway 建表。
7. ResourceMappingProvider 从数据库读取预置映射。
8. 错误率告警样例解析。
9. 时延告警样例解析。
10. Webhook 新告警创建 `PENDING` 任务。
11. 5 分钟内重复告警合并。
12. 5 分钟外历史参考返回。

## 当前注意事项

1. Webhook 返回结构使用阶段 0 的统一响应模型，即 `ApiResponse<AlertWebhookResponse>`。
2. 阶段 2 只创建 `PENDING` 任务，不启动实际异步分析流程。
3. 真实 Provider、规则分析、LLM 调用尚未实现。
4. 测试依赖本地 MySQL 运行在 `127.0.0.1:3307`。
5. `LOCAL_MYSQL.md`、`.local-mysql/`、`target/` 已加入 `.gitignore`，不应提交。

## 下一步建议

下一阶段应实现阶段 3：`AnalysisJob` 状态机。

建议优先做：

1. 实现 `AnalysisJobStateMachine`。
2. 定义合法状态流转。
3. 每次状态切换更新 `aiops_analysis_task`：
   - `task_status`
   - `current_step`
   - `status_reason`
   - `state_version`
   - `updated_at`
4. 每次状态切换写入 `aiops_analysis_job_state_log`。
5. 使用 `task_id + state_version` 做乐观锁更新。
6. 增加 `AnalysisJobWorker` 异步任务执行入口。
7. 增加状态机正常流转、LLM 失败降级流转、非法流转拒绝的测试。

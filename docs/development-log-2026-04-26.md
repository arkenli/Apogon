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

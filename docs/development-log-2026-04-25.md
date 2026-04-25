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

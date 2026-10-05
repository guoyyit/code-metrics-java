# 增量分析、持久化与恢复语义

## 增量键与复用条件

每个源文件的度量结果以文件为粒度写入 `<storage-dir>/incremental/`，缓存条目包含：

- 文件相对路径；
- 文件内容 SHA-256；
- 规则配置哈希（`GET /api/rules/hash`）；
- 分析器版本；
- 完整 `FileMetrics`。

仅当四元组 `(路径, 内容哈希, 配置哈希, 分析器版本)` 全部相同时才复用缓存：

- 文件内容变化（任意编辑，包括仅空白变化）→ 重新解析与度量；
- 规则阈值/开关/范围变化 → 配置哈希变化 → 全部缓存失效重算；
- 升级分析器（度量口径变化）→ 版本变化 → 全部缓存失效重算。

## 一致性保证

- 增量分析对未变更文件直接复用**全量分析产出的同一口径的 `FileMetrics` 记录**，
  因此未变更文件的度量在两种模式下逐项相等（测试
  `incrementalAndFullAnalysisAreItemWiseIdentical` 以对象相等断言）。
- 规则评估是无状态的：增量模式下对复用的度量重新跑一遍规则（而非缓存规则结果），
  因此启用豁免后即使度量来自缓存，命中结果也会正确带上豁免标记。
- 文件扫描顺序按相对路径排序，命中结果按
  `(路径, 起始行, 规则 ID, 指纹)` 排序，相同输入产生确定、可复现的输出。

## 失败隔离

单文件解析失败不抛异常中断批次，而是产生一条 `FileFailure`：

- `PARSE_ERROR`：语法错误，`detail` 带 `[行号]` 与解析器信息；
- `PARSE_EXCEPTION`：解析期运行时异常；
- `ANALYSIS_EXCEPTION`：度量/规则阶段异常。

报告的 `failures` 数组与 `failedFiles` 汇总将失败文件与成功文件显式区分。

## 报告持久化（原子发布）

报告按版本写入 `<storage-dir>/reports/<revision>.json`（另附
`<revision>.json.status` 状态文件）。写入流程：

1. 先把完整报告序列化到 `<report-id>.staging/` 暂存目录；
2. 成功后将 `report.json` 与 `status` **原子 move** 到正式位置；
3. 删除暂存目录。

只有全部文件处理完成、报告对象构建完毕后才会发布，因此**不存在“部分可见”的报告**。

## 报告 id 的确定性与幂等重跑

报告 id 形如 `RPT-<12 位十六进制>`，由以下三项的 SHA-256 摘要确定性派生：

1. 提交标识 `revision`；
2. 规则配置哈希 `configHash`（规则开关、阈值、范围、路径过滤的完整 JSON 摘要）；
3. 代码内容指纹：扫描到的全部源文件按相对路径排序后的
   `(相对路径, 文件内容 SHA-256)` 拼接摘要。

下列因素**均不参与** id 计算，因此不会改变报告 id：

- 运行时刻（开始/结束时间）、执行顺序、线程并发调度、进程重启；
- 全量/增量执行模式（二者业务结果逐项一致，报告 id 必然一致）；
- 工作根目录的绝对路径（仅相对路径与文件内容入指纹）；
- 增量缓存复用数等运行元数据。

revision、代码内容、规则配置三者中任意一项变化，报告 id 都会变化。

幂等语义：

- 同一提交重复分析时**按 revision 覆盖同一文件**，历史报告列表中同一提交始终只有一条记录；
- 覆盖旧结果时报告 id 保持不变（id 只取决于输入，不随重跑时间变化）；
- 分析中断后重新提交同一批次，暂存目录在启动时被清理，重跑得到与完整运行一致的报告 id；
- 并发对同一提交提交多次分析时，服务按报告 id 串行化同一批次，各次返回的 id 与业务结果
  完全一致，不产生重复记录。

## 崩溃恢复

`ReportRepository` 启动时清理所有残留的 `*.staging` 目录（中断的批次）：

- 已原子发布的报告在重启后仍可按版本读取；
- 未完成批次的暂存被清除，不会被任何查询看到；
- 豁免状态、审计日志、规则配置与增量缓存均独立持久化，重启后继续生效。

## 豁免（误报抑制）

- 豁免键为命中结果的稳定指纹
  `SHA256(ruleId | path | typeQualifiedName # methodSignature)`，**不含行号**，
  因此邻近行增删后豁免仍跟随该逻辑命中持续生效。
- 命中结果以 `fingerprint` 字段暴露该指纹；用 `POST /api/suppressions` 录入
  （规则、指纹、路径、原因、操作人）。
- 豁免持久化在 `suppressions.json`，后续所有分析自动应用，命中带
  `suppressed=true` 与 `suppressionId`，但仍保留在结果中（仅不计入 active 数）。
- `DELETE /api/suppressions/{id}` 撤销豁免（保留记录、写 `revokedAt`）。
- 每次授予/撤销都向 `audit/suppressions.log` 追加一行 JSON 审计记录。

## 基线对比

`POST /api/compare` 对两份已保存报告做对比：

- 坏味道按稳定指纹对齐，输出 `added`（目标有、基线无）与 `removed`（基线有、目标无），
  已豁免命中不参与增删集合；
- 度量趋势按稳定标识对齐，仅保留非零差异，覆盖 `METHOD`/`TYPE`/`REPORT` 三个作用域：
  - 方法按 `类型全限定名 + "#" + 方法名` 对齐（**不含形参签名**），因此给方法新增一个
    参数会体现为同一条方法趋势 `parameterCount: N -> N+1`，方向 `UP`，而不是“旧方法消除 +
    新方法出现”；
  - 类型按全限定类型名对齐；
  - 报告级汇总按固定的结构度量键对齐。
- 每条变化包含 `baselineValue`、`targetValue`、`delta = target - baseline` 与
  `direction`（`delta > 0` 为 `UP`，`delta < 0` 为 `DOWN`，零差异条目不输出）。
- 指标变化列表**只包含代码结构度量**。属于结构度量的报告级字段为：
  `sourceLinesOfCode`、`totalMethods`、`totalParameters`、`totalComplexity`、
  `maxComplexity`、`totalFanOut`；方法级为 `linesOfCode`、`cyclomaticComplexity`、
  `parameterCount`、`maxNestingDepth`；类型级为 `linesOfCode`、`methodCount`、
  `weightedMethodComplexity`、`fanOut`。
- 下列运行元数据**不会**作为指标变化条目输出，但仍原样保留在对比结果两侧的
  `baseline.values` / `target.values` 汇总快照中：`totalFiles`、`analyzedFiles`、
  `failedFiles`、`reusedFiles`、`incremental`、`activeFindings`、`suppressedFindings`。
- 变化条目按 `(scope, metric, qualifiedName)` 稳定排序；同一份基线与目标重复对比，
  结果逐项一致且条目顺序稳定（测试以对象相等断言）。

## 验证方法

通过 `mvn test` 运行的自动化用例直接覆盖上述保证：

- `DeterministicReportIdTest`
  - `repeatedRunsReturnIdenticalReportId`：同一提交重复运行，报告 id 与业务内容一致；
  - `fullAndIncrementalRunsShareReportId`：全量与增量运行报告 id 一致；
  - `reportIdChangesWhenAnyInputChanges`：revision 或代码任一变化即换 id，根路径变化不换；
  - `duplicateSubmissionsAreIdempotent`：重复提交覆盖同一条记录，id 保持不变；
  - `concurrentDuplicateSubmissionsReturnOneConsistentResult`：6 路并发同一提交得到同一 id；
  - `resubmissionAfterSimulatedInterruptKeepsReportId`：模拟重启清理暂存后重跑，id 不变。
- `MetricTrendComparisonTest`
  - `metricDeltasCaptureDirectionAndValueAcrossAllScopes`：受控源码变更下，方法级
    `parameterCount` 加一且 `UP`，类型级与报告级 `fanOut` 减一且 `DOWN`；
  - `runMetadataNeverAppearsInMetricDeltas`：运行元数据不进入指标变化列表，且仍保留在
    两侧汇总快照中；
  - `repeatedComparisonsAreItemWiseAndOrderIdentical`：相同输入重复对比逐项一致、顺序稳定。

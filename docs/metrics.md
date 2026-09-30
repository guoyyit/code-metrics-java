# 结构度量与坏味道检测说明

本服务对 Java 源码进行解析（JavaParser，语言级别 Java 17），从方法级与类型级两个层级
计算结构度量，依据可配置规则判定坏味道，并以不可变快照形式持久化，支持增量分析、
豁免、基线对比、崩溃恢复与并发执行。

## 1. 度量定义与计算口径

所有位置均为 1 基行号，`path` 为相对分析根目录的 POSIX 风格路径。

### 1.1 方法级度量

| 度量 | 字段 | 计算口径 |
| --- | --- | --- |
| 物理行数 | `loc` | 方法起始行到结束行（含两端），`endLine - beginLine + 1`。 |
| 有效代码行 | `sloc` | 在方法行范围内，去掉空行与纯注释行（`//`、`/*`、`*` 开头）后的行数。 |
| 圈复杂度 | `complexity` | McCabe 复杂度，起点 1；每出现一个判定点加 1：`if/else if`、`for`、增强 `for`、`while`、`do-while`、`catch`、三元表达式 `?:`、`switch` 的每个 `case`（含 `switch` 表达式的每个带标签分支）。`default` 分支不计。**嵌套类型与 lambda 表达式构成独立作用域，其内部判定点不计入外层方法。** |
| 参数数量 | `parameterCount` | 方法/构造器形参个数。 |

抽象方法、无方法体的接口与 native 方法不参与方法度量；构造器按方法同样计算。

### 1.2 类型级度量

| 度量 | 字段 | 计算口径 |
| --- | --- | --- |
| 物理行数 / 有效行 | `loc` / `sloc` | 同方法口径，范围为整个类型声明。 |
| 方法/构造器/字段数 | `methodCount` / `constructorCount` / `fieldCount` | 直接声明成员计数；一行多变量声明按变量个数累计字段数。 |
| 加权方法数 | `wmc` | 类型内各方法（含构造器）圈复杂度之和。 |
| 扇出耦合 | `fanOut` | 类型源码中引用到的**不同的非 JDK 类型简单名**个数。来源包含字段类型、方法返回值/参数/异常、方法体与构造器体内出现的类型，以及 `new` 的对象类型；包含泛型实参，递归展开。排除：原始类型、JDK 类型（显式 `java./javax./jdk./sun.` 导入，或常见 `java.lang` 与 JDK 集合/IO/NIO 等内置类型白名单）以及类型自身；重复类型名只计一次。 |
| 继承深度 | `dit` | 类型直接声明的父类型数量：类为 `extends` 子句中的父类数（0/1），接口为 `extends` 的父接口数；`implements` 接口计入耦合但不计入 DIT。 |
| 响应集 | `rfc` | 类型自身方法/构造器名，加上其方法体内可见的方法调用名（去重）后的集合大小（RFC 的静态近似，按方法名而非全限定签名去重）。 |

### 1.3 文件级聚合

`FileMetrics` 汇总该文件：`loc/sloc`（按整文件行计算）、类型数、方法数、
总复杂度、最大/平均方法复杂度、扇出合计与最大值。快照摘要再聚合为
总 LOC/SLOC、文件数、解析成功/失败数、坏味道总数/生效数/豁免数。

> 说明：JDK 类型识别采用「显式导入 + 内置白名单」的静态近似，未做全项目符号解析，
> 因此同包未导入的自定义类型不会被误判为 JDK 类型，但 JDK 白名单之外的极冷门
> `java.*` 类型（非显式导入时）可能被计入耦合。阈值可按需调整。

## 2. 坏味道规则

规则标识、默认阈值与口径：

| 规则 ID | 层级 | 度量 | 默认阈值 | 判定 |
| --- | --- | --- | --- | --- |
| `LONG_METHOD` | 方法 | `loc` | 50 | 物理行数 `>` 阈值 |
| `COMPLEX_METHOD` | 方法 | `complexity` | 10 | 复杂度 `>=` 阈值 |
| `LONG_PARAMETER_LIST` | 方法 | `parameterCount` | 5 | 参数数 `>=` 阈值 |
| `LARGE_CLASS` | 类型 | `loc` | 300 | 物理行数 `>` 阈值 |
| `GOD_CLASS` | 类型 | `wmc` | 40 | WMC `>=` 阈值 |
| `HIGH_COUPLING` | 类型 | `fanOut` | 15 | 扇出 `>` 阈值 |

每条命中（`Finding`）包含：稳定身份 `id`（`相对路径#类型限定名[#方法签名]#规则ID`）、
规则、层级、文件、类型/方法、起止行、度量名、实测值、阈值、人类可读触发原因，
以及豁免标记 `suppressed` 与 `exemptionId`。

### 2.1 可配置项

每条规则可配置：

- `enabled`：开关；
- `threshold`：阈值；
- `include` / `exclude`：作用范围，ant 风格 glob（如 `**/service/**`、`**/dto/**`）。
  `include` 为空表示作用于全部文件；先 include 后 exclude。

配置持久化在 `<storageDir>/rules.json`。规则配置与豁免集合共同构成「配置指纹」，
参与快照身份与增量基线匹配（见第 3 节）。

## 3. 增量分析语义

- 文件身份为相对路径，内容身份为文件字节的 SHA-256。
- 增量分析以某个基线快照为参照：
  - **未变更文件**：路径存在、基线状态为 `OK`、SHA-256 相同 → 直接沿用基线结果（度量与命中），不重新解析；
  - **变更文件**：新增、内容变化或基线中为 `PARSE_ERROR` 的文件 → 重新解析与判定（给解析失败的文件在后续运行中重试的机会）。
- 配置（规则 + 豁免）发生变化时，旧快照的配置指纹不再匹配，服务自动退回全量分析，
  避免把旧配置下的命中误复用。
- **一致性保证**：对同一棵源码树、同一配置，增量分析与全量分析的逐文件结果
  （`results` 的 JSON 表示）完全一致；仅汇总中的 `incremental/baselineId/changedFileCount`
  等执行元信息可能不同。该性质由 `AnalysisIntegrationTest` 校验。

### 3.1 快照身份、确定性与可复现

快照 ID 为 `snap-` + 对「revision + 全部文件(路径:sha256)清单 + 配置指纹」的
SHA-256 前 16 位。相同输入始终产生相同 ID 与字节内容（JSON 属性名排序、缩进输出），
因此对比结果确定且可复现。

`revision` 优先取请求参数，否则尝试 `git rev-parse HEAD`，均不可用时为 `working-tree`。

## 4. 豁免

- 豁免维度：`ruleId`（空=所有规则）、`filePatterns`（ant glob，必填）、
  `typePattern`、`methodPattern`（支持 `*`/`?` 通配）。
- 豁免持久化于 `exemptions.json`，在后续所有分析中持续生效；命中不会被删除，
  而是标记 `suppressed=true` 并记录 `exemptionId`，保证历史可追溯。
- 每次创建/撤销都向 `exemption-events.log`（JSON 数组）追加审计事件，
  含事件 ID、动作、操作者、原因、时间戳与豁免快照。

## 5. 并发、内存与失败隔离

- 多文件并行解析，默认并行度为 CPU 核数（可通过请求 `parallelism` 与
  `codemetrics.default-parallelism` 调整）。
- 内存上限通过 `MemoryLimiter` 实现：按 `max(1, 文件字节×4)` 估算许可字节，
  超过 `memoryLimitBytes` 的任务排队等待，大文件会迫使在途任务先完成释放许可。
- 每次分析使用**方法内局部状态**与独立线程池/内存闸；并发触发多批分析时
  中间结果互不共享，最终只通过各自的不可变快照体现，不发生串扰。
- 单文件解析失败被捕获为 `status=PARSE_ERROR` 且 `errorMessage` 以
  `PARSE_ERROR:`（语法问题）或 `ANALYSIS_FAILURE:`（其他异常）开头，
  不中断同批次其他文件；摘要中以 `parseErrorCount` 区分。

## 6. 持久化与崩溃恢复

存储目录默认 `.codemetrics-data`（可配）：

```
<storageDir>/
  snapshots/<snapshotId>.json   # 不可变快照，临时文件 *.tmp-* + 原子改名提交
  rules.json                    # 规则配置（原子写）
  exemptions.json               # 豁免集合（原子写）
  exemption-events.log          # 豁免审计事件（JSON 数组，原子写）
```

- 快照先写到 `*.tmp-<线程ID>`，再原子 `move` 为正式文件：崩溃只会留下临时文件，
  不会出现部分可见的快照。
- 服务启动时清理遗留的 `*.tmp-*`；已提交快照重启后可直接加载（崩溃恢复），
  并可作为增量基线继续使用。

## 7. REST 接口

基础路径 `/api`：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/analyses` | 触发分析（全量/增量），返回快照 |
| GET | `/snapshots` | 快照列表（摘要，按时间倒序） |
| GET | `/snapshots/{id}` | 快照详情（逐文件度量与命中） |
| GET | `/snapshots/{id}/findings?ruleId=&suppressed=` | 查询命中（可过滤） |
| GET | `/comparisons?base=&target=` | 基线对比：新增/消除/持续命中与度量差 |
| GET/PUT | `/rules`、`/rules/{ruleId}` | 查看/更新规则配置 |
| POST | `/rules/reset` | 恢复内建默认配置 |
| GET/POST | `/exemptions` | 查看/创建豁免 |
| DELETE | `/exemptions/{id}?reason=&operator=` | 撤销豁免 |
| GET | `/exemptions/events` | 豁免审计事件 |

`AnalysisRequest` 字段：`rootPath`（默认当前工作目录）、`revision`、`label`、
`baselineId`、`incremental`、`parallelism`、`memoryLimitBytes`、`includes`、`excludes`。
`incremental` 为空时：存在配置指纹匹配的历史快照即自动增量，否则全量。

## 8. 命令行自分析

启动时设置 JVM 系统属性可直接分析本仓库并打印摘要，用于验收：

```
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dcodemetrics.self-analyze=true -Dserver.port=18080"
```

配置项（`application.properties` / 系统属性）：

- `codemetrics.storage-dir`
- `codemetrics.default-parallelism`
- `codemetrics.default-memory-limit-bytes`
- `codemetrics.default-includes[*]` / `codemetrics.default-excludes[*]`
- `codemetrics.self-analyze=true`

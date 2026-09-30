# code-metrics-java

面向 Java 源码的**结构度量计算与坏味道检测**服务（Spring Boot 3 + JavaParser）。
支持方法级/类型级度量、可配置规则、误报豁免、按版本保存结果、基线趋势对比、
增量分析、多文件并行与内存上限、崩溃恢复。

## 快速开始

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests
java -Dcodemetrics.storage-dir=target/run-data -jar target/code-metrics-java-1.0-SNAPSHOT.jar
```

对本仓库自身做一次全量分析：

```bash
curl -s -X POST http://localhost:8080/api/analyze \
  -H 'Content-Type: application/json' \
  -d '{"rootPath":".","revision":"v1","incremental":false}'
```

`revision` 省略时自动取当前 git `HEAD` 短哈希（无 git 时为 `WORKING`）。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/analyze` | 触发分析（`rootPath`、`revision`、`incremental`） |
| GET | `/api/reports/{revision}` | 按版本读取已保存报告 |
| GET | `/api/reports/latest` | 最近一份报告 |
| GET | `/api/reports?limit=N` | 历史报告列表 |
| POST | `/api/compare` | 基线对比（`baselineRevision`、`targetRevision`） |
| GET | `/api/rules` | 当前规则配置（阈值/开关/严重级/作用范围） |
| PUT | `/api/rules` | 更新规则配置（原子持久化 + 历史归档） |
| GET | `/api/rules/hash` | 当前配置哈希 |
| GET | `/api/suppressions` | 豁免列表（含已撤销记录） |
| POST | `/api/suppressions` | 录入豁免（按 finding 的 `fingerprint`） |
| DELETE | `/api/suppressions/{id}?revokedBy=...` | 撤销豁免（留痕） |

典型流程：分析 → 从结果中取某条 finding 的 `fingerprint` → 录入豁免 →
再次分析该命中变为 `suppressed`；保存两个版本后调用 `/api/compare` 查看
新增/消除坏味道与度量趋势。

## 文档

- 度量与规则的精确定义：[docs/metrics.md](docs/metrics.md)
- 规则配置、作用范围、内存上限：[docs/configuration.md](docs/configuration.md)
- 增量语义、持久化、崩溃恢复、豁免、基线对比：[docs/incremental.md](docs/incremental.md)

## 测试

```bash
mvn test
```

覆盖：

- 度量计算的确定性与复杂度/嵌套口径（`MetricsCalculatorTest`）；
- 增量与全量逐项一致、仅重算变更文件、解析失败隔离、6 路并发无串扰
  （`AnalyzerIntegrationTest`）；
- 豁免持久化生效/撤销、基线新增/消除与趋势、对比可复现
  （`SuppressionAndBaselineTest`）；
- 崩溃后暂存清理、已完成报告与增量缓存存活（`RecoveryTest`）。

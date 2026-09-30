# code-metrics-java

面向 Java 源码的结构度量与坏味道检测服务（Spring Boot 3 / Java 17，基于 JavaParser）。
支持方法级与类型级度量、可配置坏味道规则、误报豁免与审计、按版本保存快照、
基线趋势对比、增量分析、多文件并行与内存上限、失败隔离以及崩溃恢复。

完整口径与语义见 [docs/metrics.md](docs/metrics.md)。

## 快速开始

```bash
# 运行测试（含增量一致性、豁免、基线对比、并发、恢复、自仓库分析）
mvn test

# 启动服务
mvn spring-boot:run

# 对本仓库自身做一次分析并打印摘要
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dcodemetrics.self-analyze=true -Dserver.port=18080"
```

## 主要接口

```bash
curl -X POST localhost:8080/api/analyses \
  -H 'Content-Type: application/json' \
  -d '{"rootPath":".","incremental":false}'

curl localhost:8080/api/snapshots
curl "localhost:8080/api/comparisons?base=<基线ID>&target=<目标ID>"
curl -X PUT localhost:8080/api/rules/GOD_CLASS \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":"GOD_CLASS","enabled":true,"threshold":30}'
curl -X POST localhost:8080/api/exemptions \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":"LONG_METHOD","filePatterns":["**/generated/**"],"reason":"生成代码"}'
```

## 覆盖的度量与规则

- 方法级：`loc`、`sloc`、`complexity`（McCabe，跳过嵌套类型/lambda）、`parameterCount`
- 类型级：`loc`、`sloc`、`methodCount`、`constructorCount`、`fieldCount`、`wmc`、
  `fanOut`（去重非 JDK 类型）、`dit`、`rfc`
- 规则：`LONG_METHOD`、`COMPLEX_METHOD`、`LONG_PARAMETER_LIST`、
  `LARGE_CLASS`、`GOD_CLASS`、`HIGH_COUPLING`（阈值/开关/作用范围均可配置）

## 关键保证

- 未变更文件沿用基线结果，且增量结果与全量逐项一致（配置变化自动回退全量）。
- 快照 ID 由「版本 + 文件 sha256 清单 + 配置指纹」决定，结果确定、可复现。
- 快照采用「临时文件 + 原子改名」提交，崩溃不留部分结果，重启可恢复。
- 豁免持久化、持续生效，命中只标记不删除，变更全程审计。

# 规则配置与作用范围

规则配置由 `RuleConfig` 描述，存储在
`<storage-dir>/rules.json`，结构：

```json
{
  "version": "1",
  "rules": {
    "LONG_METHOD": {
      "enabled": true,
      "severity": "MAJOR",
      "scope": "METHOD",
      "threshold": 30.0,
      "pathPatterns": [],
      "reasonTemplate": "方法 {type}#{method} 行数 {value} 超过阈值 {threshold}"
    }
  }
}
```

- `enabled`：规则开关，关闭后不再产生任何命中。
- `threshold`：阈值，命中条件为实测值严格大于阈值。
- `severity`：命中结果的严重级标签（`MINOR`/`MAJOR`/`CRITICAL` 等，可自由填写）。
- `scope`：规则作用级别（`METHOD`/`TYPE`），仅用于标识与校验。
- `pathPatterns`：路径作用范围。为空表示对全部文件生效；非空时仅对匹配任一
  glob 的文件评估。支持 `*`（单层路径段内任意字符）与 `**/`（任意目录前缀），
  例如 `src/main/java/**`、`**/model/*.java`。
- `reasonTemplate`：自定义原因模板，占位符 `{rule}`、`{type}`、`{method}`、
  `{value}`、`{threshold}` 会被替换；为 null 时使用内置中文模板。

每次通过 `PUT /api/rules` 接受的配置都会：

1. 与内置规则做归一化合并（缺失字段回退默认值）；
2. 原子写回 `rules.json`；
3. 在 `<storage-dir>/config-history/` 归档一份带时间戳的副本，保证历史可追溯。

配置内容的 SHA-256 摘要可通过 `GET /api/rules/hash` 获取；该摘要同时参与
增量缓存键与报告记录，规则变更会使旧缓存失效。

## 应用配置

`application.properties` 支持：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `codemetrics.storage-dir` | `data/codemetrics` | 持久化根目录 |
| `codemetrics.parallelism` | `4` | 单批次内并行分析文件的线程数 |
| `codemetrics.memory-budget-bytes` | `67108864`（64 MiB） | 在途文件分析的内存许可上限 |
| `codemetrics.analyzer-version` | `1.0.0` | 分析器版本，参与缓存键 |

## 内存上限语义

`MemoryLimiter` 用公平信号量按“文件字节数 × 4”的估算值加权授权：

- 超过剩余预算的文件会阻塞排队，直到其他文件完成释放，从而避免同时载入过多大文件；
- 大于总预算的单个文件按“独占全部预算”串行执行，不会死锁；
- 许可仅在**单次分析流水线**持有，`finally` 中释放；多个并发批次共享同一预算，
  但彼此的输入集合、结果集合、线程池完全独立，不共享任何分析状态。

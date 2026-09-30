# 结构度量定义

本文件说明 code-metrics-java 对 Java 源码计算的每一项结构度量的精确口径。解析器为
[JavaParser 3.26](https://javaparser.org/)（JAVA_17 语法级别），逐文件独立解析，
不做跨文件符号解析，因此结果只依赖单文件文本内容，确定性强、可复现。

## 通用约定

- 行号为 1 起始，来自语法树 token 的位置。
- `LOC`（行数跨度）= 声明结束行 - 起始行 + 1，包含注解、签名、花括号与空行。
- 文件级 `totalLines` = 文件文本按 `\n` 切分得到的行数。
- 文件级 `SLOC`（source lines of code）= 非空且去除空白后不以 `//`、`/*`、`*`
  开头的行；这是基于行的启发式口径，不做完整词法注释剥离。
- 嵌套类型（成员内部类、静态嵌套类）会被递归纳入所属编译单元；匿名/局部类、
  Lambda、嵌套方法构成“独立可执行单元”，其语句/复杂度不计入外层方法。

## 方法级度量（含构造器）

| 度量 | 字段 | 口径 |
| --- | --- | --- |
| 方法行数 | `linesOfCode` | 方法声明节点的行跨度（end - begin + 1）。 |
| 语句数 | `statements` | 方法体内语句 AST 节点数量；纯花括号 `BlockStmt` 不计数，`if/for/...` 等具体语句各计 1。 |
| 圈复杂度 | `cyclomaticComplexity` | McCabe 口径：起点 1，每遇到以下结构 +1：`if`、`for`、增强 `for`、`while`、`do-while`、`catch`、三元 `?:`、布尔 `&&`、布尔 `||`；经典 `switch` 每个非 `default` 的 `case` 标签 +1。 |
| 最大嵌套深度 | `maxNestingDepth` | 控制流块嵌套的最深层数；`if/for/foreach/while/do/catch` 的体 +1 层，`else` 本身不另计层。 |
| 参数数量 | `parameterCount` | 方法/构造器形参表中的参数个数。 |

## 类型级度量

| 度量 | 字段 | 口径 |
| --- | --- | --- |
| 类型行数 | `linesOfCode` | 类型声明节点的行跨度。 |
| 方法数 | `methodCount` | 直接声明在该类型上的方法与构造器数量（不含继承、不含嵌套类型自身的方法）。 |
| 字段数 | `fieldCount` | 字段声明中的变量声明符总数（一行多变量按多个计）。 |
| 加权方法复杂度 | `weightedMethodComplexity`（WMC） | 类型直接声明的全部方法/构造器圈复杂度之和。 |
| 扇出 | `fanOut` | 类型内引用到的**不同外部类型**数量，详见下文。 |

### fan-out（耦合程度）口径

在类型声明子树内收集类型引用出现位置：

- 普通类型名（声明、形参、返回类型、局部变量、泛型实参等的 `ClassOrInterfaceType`）；
- `new X(...)` 的对象创建类型；
- `instanceof X` 的类型。

每个引用的名称解析规则：

1. 去掉泛型部分（`Map<String, String>` 取 `Map`）。
2. 全限定名（含 `.`）直接采用；
3. 命中本文件 `import` 的单类型导入时，采用其全限定名；
4. 否则按“同包类型”处理为 `<package>.<SimpleName>`；
5. `java.lang.*` 常用类型（`String`、`Object`、`Integer` 等，代码中以集合白名单为准）
   与非大写开头的名称不计入；
6. 类型自身以及同一编译单元内声明的嵌套类型不计入。

最终按去重后的类型全限定名计数。这是一个无需类路径即可稳定计算的结构化耦合代理指标。

## 坏味道规则

| 规则 ID | 作用级别 | 度量 | 默认阈值 | 默认严重级 | 命中条件 |
| --- | --- | --- | --- | --- | --- |
| `LONG_METHOD` | METHOD | `linesOfCode` | 30 | MAJOR | 方法行数 > 阈值 |
| `COMPLEX_METHOD` | METHOD | `cyclomaticComplexity` | 10 | CRITICAL | 圈复杂度 > 阈值 |
| `MANY_PARAMETERS` | METHOD | `parameterCount` | 4 | MINOR | 参数数 > 阈值 |
| `DEEP_NESTING` | METHOD | `maxNestingDepth` | 3 | MAJOR | 最大嵌套深度 > 阈值 |
| `GOD_CLASS` | TYPE | `weightedMethodComplexity` | 40 | CRITICAL | WMC > 阈值 |
| `HIGH_FAN_OUT` | TYPE | `fanOut` | 10 | MAJOR | 扇出 > 阈值 |
| `LARGE_TYPE` | TYPE | `linesOfCode` | 200 | MAJOR | 类型行数 > 阈值 |

判定一律采用“**严格大于**阈值”：阈值 N 表示“N 及以内可接受”。
每条命中结果包含：规则 ID、严重级、文件路径、`SourceLocation`（类型、方法签名、起止行）、
度量名、实测值、阈值、中文触发原因、豁免标记与豁免 ID。

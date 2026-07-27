# Kotlin Tree-sitter 高亮契约

本文档定义 AndroidCodeStudio 的 Kotlin Tree-sitter 高亮约定。它是
`highlights.scm` 与各配色方案中 `kotlin.json` 之间的稳定接口。

## 维护边界

Kotlin 高亮由三层组成：

1. **AST 基线**：fwcd `tree-sitter-kotlin` 的 `grammar.js` 和生成的
   `node-types.json`；
2. **项目高亮语义**：本目录的 `highlights.scm`；
3. **配色映射**：`editor/schemes/<scheme>/kotlin.json`。

上游 `queries/highlights.scm` 用于跟踪 AST 与新语法，但不能直接覆盖本项目的
`highlights.scm`。本项目需要保留声明/调用、属性作用域等更细的语义，并避免上游
query 中捕获整个父节点所造成的范围过宽问题。

所有内置 Kotlin 配色方案必须提供相同的 style key。`kotlin.json` 应映射到语义色
令牌（例如 `@keyword`），而不是在 query 中绑定具体颜色。下表中的十六进制值仅是
AndroidIDE Default 浅色主题的当前示例，不属于稳定接口。

## 稳定 highlights capture

| Capture | Kotlin 语义 | 语义色令牌 | Default 示例 | 范围约定 |
|---|---|---|---|---|
| `@identifier` | 无更具体分类的普通标识符 | `@onSurface` | `#1E1B19` | 只捕获标识符 |
| `@keyword` | Kotlin 关键字 | `@keyword` | `#d32f2f` | 只捕获关键字 token，不捕获表达式或声明父节点 |
| `@label` | 标签 | `@keyword` | `#d32f2f` | 捕获 label 节点 |
| `@type.qualifier` | 类型/声明修饰符 | `@keyword` | `#d32f2f` | 仅用于需要与普通 keyword 区分配置的修饰符 |
| `@type` | 用户类型 | `@type` | `#1976d2` | 捕获类型标识符，不包含 nullable 标记 |
| `@type.builtin` | Kotlin 内建类型 | `@type` | `#1976d2` | 仅捕获内建类型标识符；当前附加 bold 样式 |
| `@function.declaration` | 函数声明名 | `@func.decl` | `#2196f3` | 只捕获函数名 |
| `@function.invocation` | 函数调用名 | `@func.call` | `#2196f3` | 只捕获被调用的名称 |
| `@function.builtin` | 内建函数或访问器 | `@func.call` | `#2196f3` | 当前附加 italic 样式 |
| `@constructor` | 类型构造调用或构造器语义名称 | `@kt.constructor` | `#2196f3` | 不得捕获完整构造器声明或参数列表 |
| `@parameter` | 普通函数/lambda 参数名 | `@variable` | `#ba68c8` | 只捕获参数名 |
| `@property.local` | 局部属性名 | `@onSurface` | `#1E1B19` | 只捕获属性名 |
| `@property.top_level` | 顶层属性名 | `@kt.property` | `#ff6f00` | 只捕获属性名 |
| `@property.class` | 类属性、构造器属性及成员访问名 | `@kt.property` | `#ff6f00` | 只捕获名称，不捕获完整声明 |
| `@variable.builtin` | `this`、`super`、`it`、`field` 等特殊变量 | `@keyword` | `#d32f2f` | 捕获对应表达式或标识符 |
| `@constant` | 枚举项或按约定识别的常量 | `@constant` | `#ff6f00` | 只捕获常量名 |
| `@constant.builtin` | `true`、`false`、`null` | `@keyword` | `#d32f2f` | 只捕获 literal/token |
| `@number` | 整数、浮点数及其他数值字面量 | `@number` | `#558b2f` | 捕获完整数值字面量 |
| `@string` | 字符和字符串字面量 | `@string` | `#558b2f` | 捕获字面量范围 |
| `@string.regex` | 正则表达式字符串 | `@string` | `#558b2f` | 禁用普通代码补全 |
| `@string.escape` | 字符串转义 | `@kt.string.esc` | `#2196f3` | 只捕获转义序列，并覆盖父字符串样式 |
| `@comment` | 行注释和多行注释 | `@comment` | `#9e9e9e` | 当前附加 italic 样式 |
| `@attribute` | 注解及注解标记 | `@attribute` | `#827717` | 按 query 规则捕获注解名称或标记 |
| `@operator` | 运算符与普通分隔符 | `@operator` | `#1976d2` | 优先捕获单个 token |
| `@bracket` | `()`、`[]`、`{}` | `@onSurface` | `#1E1B19` | 只捕获括号 token |
| `@punctuation.special` | nullable `?`、字符串插值边界等特殊标点 | `@kt.punctuation.special` | `#d32f2f` | 只捕获特殊标点，并可覆盖父类型/字符串样式 |
| `@preproc` | Kotlin 脚本 shebang | `@kt.preproc` | `#9e9e9e` | 当前附加 italic、bold 样式 |
| `@none` | 明确取消某段继承高亮 | 无 | 基础文本色 | 仅用于有意阻止父捕获影响子节点 |

## nullable 类型约定

nullable 类型必须保留类型与标点的独立语义：

```kotlin
Category?
```

期望为：

- `Category`：`@type`；
- `?`：`@punctuation.special`。

禁止使用下面的宽范围规则：

```scm
(nullable_type) @punctuation.special
```

当前 grammar 提供 named `quest` 节点，应使用：

```scm
(nullable_type
  (quest) @punctuation.special)
```

## 属性作用域约定

属性名称必须按声明位置区分：

```text
函数/语句块内属性 -> @property.local
source_file 直接属性 -> @property.top_level
class_body 属性      -> @property.class
val/var 构造器参数   -> @property.class
```

普通函数参数仍使用 `@parameter`，不能因位于构造器参数列表中就捕获整个参数节点。

## 函数与构造器约定

- 函数声明名称使用 `@function.declaration`；
- 函数调用名称使用 `@function.invocation`；
- 类型构造调用名称使用 `@constructor`；
- `constructor`、`this`、`super` 等 Kotlin 关键字使用 `@keyword`；
- 禁止用 `@constructor` 或 `@keyword` 捕获完整 `primary_constructor`、
  `secondary_constructor` 或 `constructor_delegation_call`。

自 Tree-sitter Kotlin artifact `0.1.3` 起，不可见 external
`_primary_constructor_keyword` 通过 named alias 暴露为 query-visible
`primary_constructor_keyword`。主项目使用以下规则精确捕获显式主构造器关键字：

```scm
(primary_constructor
  (primary_constructor_keyword) @keyword)
```

不得用下面的宽范围规则绕过或替代该规则：

```scm
(primary_constructor) @constructor
```

否则参数、类型和括号会被整体误染。

## 上游 capture 转换约定

同步 fwcd/upstream query 时，应按语法上下文转换，而不是全局替换：

| Upstream capture | AndroidCodeStudio 适配 |
|---|---|
| `@variable` | 普通名称用 `@identifier`；参数和属性按上下文细分 |
| `@property` | 按声明位置改为 `@property.local`、`.top_level` 或 `.class` |
| `@function` | 声明位置用 `@function.declaration`，调用位置用 `@function.invocation` |
| `@boolean` | `@constant.builtin` |
| `@conditional` | `@keyword`，只捕获关键字 token |
| `@repeat` | `@keyword`，只捕获关键字 token |
| `@exception` | `@keyword`，只捕获关键字 token |
| `@include` | import 关键字使用 `@keyword` |
| `@namespace` | 包路径默认使用 `@identifier`，`package` token 使用 `@keyword` |
| `@punctuation.bracket` | `@bracket` |
| `@punctuation.delimiter` | `@operator` |
| `@float` | `@number` |
| `@character` | `@string` |

## 范围不变量

为避免一次 capture 覆盖大量不同语义的文本，维护 query 时必须遵守：

1. 关键字 capture 只覆盖关键字 token；
2. 名称 capture 只覆盖名称节点；
3. 标点 capture 只覆盖标点节点；
4. 不用父声明/父表达式替代不可查询的子 token；
5. 同一文本若有多条规则，具体语义规则应覆盖普通 `@identifier`；
6. AndroidCodeStudio 的 `LineSpansGenerator` 会让更窄的嵌套 capture 覆盖父 capture，
   并在子范围结束后恢复父样式；
7. 完全相同范围的 capture 保留 query 顺序优先级，因此 `@function.builtin` 应先于普通
   `@function.invocation`，属性/函数/常量等具体规则应先于 `@identifier`；类型上下文
   顺序应为注解、构造调用、内建类型、普通类型；
8. `@identifier` 与普通 `@type` fallback 应集中放在 query 尾部；
9. 新规则必须同时满足当前 grammar 的 node type 与父子结构，否则 TSQuery 会以
   `NodeType` 或 `Structure` 错误拒绝整个 query。

特别禁止以下类型的规则：

```scm
(primary_constructor) @constructor
(jump_expression) @keyword
(nullable_type) @punctuation.special
```

## 嵌套 capture 与字符串子范围

`LineSpansGenerator` 会把视觉 capture 转为区间并进行分段合成：更窄的嵌套 capture
覆盖父 capture，子区间结束后恢复父样式。因此完整 `(string_literal) @string` 内的
`@string.escape`、插值 `@punctuation.special` 和 `@none` 可以独立生效。

完全相同范围的 capture 仍保留 query 顺序优先级。例如正则字符串与普通字符串起点
和范围相同，应继续将 `@string.regex` 规则放在普通 `@string` fallback 之前。

区间合成会保留 `TsSpanFactory` 为单个 capture 生成的内部 span 边界和扩展属性，
例如字符串中的十六进制颜色背景。

## locals query 的边界

`locals.scm` 的 capture（例如 `@definition.function`、`@definition.parameter`、
`@scope`）服务于局部定义、引用和作用域分析，不属于视觉配色契约。

`TsLanguageSpec` 会将 `locals.scm` 与 `highlights.scm` 拼接为一个 TSQuery，因此：

- 两份 query 都必须与同一个 Kotlin grammar 版本兼容；
- 诊断中的 query offset 是拼接后的 UTF-8 字节偏移；
- 修改 highlights 时也不能忽略 locals 的 AST 兼容性。

## 同步流程

升级 `tree-sitter-kotlin` 时：

1. 对比 upstream `grammar.js`、`node-types.json` 和 `queries/highlights.scm`；
2. 识别新增、删除或改名的 query-visible 节点/token；
3. 将 upstream 新规则转换为本文档规定的 capture；
4. 保留 AndroidCodeStudio 的属性作用域、函数声明/调用等附加语义；
5. 审核所有 capture 的范围，禁止用父节点替代不可见 token；
6. 确认所有 capture 都存在于每套 `kotlin.json`，或可通过点号前缀回退到已定义样式；
7. 构建并在实际编辑器中验证关键词、声明、调用、属性、构造器、nullable 类型和字符串插值。

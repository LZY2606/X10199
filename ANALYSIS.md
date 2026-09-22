# java-petitparser 执行与图结构分析

本文以当前 commit 的源码为准，所有结论都标注 `文件:行号`，并由
`petitparser-core/src/test` 下新增的记录型（instrumented）测试佐证。测试只新增
test 作用域代码，未改动任何 combinator 的实现或语义。

## 0. 线索语法

分析贯穿下面的最小语法（`ParserGraphAnalysisTest.MiniGrammar`，
petitparser-core/src/test/java/org/petitparser/tools/ParserGraphAnalysisTest.java:39），
它同时包含递归规则、choice、repeat、lookahead、action 和命名 production：

```java
def("start",  ref("value").end());
def("value",  ref("list").or(ref("number")));                 // choice
def("list",   of('[').and()                                    // lookahead
    .seq(of('[')).seq(ref("value").star()).seq(of(']'))        // 递归 + repeat
    .pick(2));
def("number", digit().plus().flatten());                       // repeat
action("number", (Function<String, Integer>) Integer::parseInt); // action
```

`[1[23]4]` 解析为 `[1, [23], 4]`（testGrammarParses）。resolve 完成后全图共
15 个不同 parser 节点（testMirrorTerminatesOnCyclicGraph）。

## 1. 从 definition 到 parser graph：reference 何时解析

- `ref(name)` 只是创建一个 `Reference` 占位节点（GrammarDefinition.java:42-44）。
  它是 `Parser` 的私有子类，但**不能参与解析也不能被复制**：`parseOn` 抛
  `UnsupportedOperationException`（GrammarDefinition.java:161-164），`copy` 同样
  （GrammarDefinition.java:166-169）。所以任何 `Reference` 必须在 build 阶段被替换掉。
- `def` 拒绝重名 production（GrammarDefinition.java:50-52）；`action(name, fn)`
  等价于 `redef(name, p -> p.map(fn))`（GrammarDefinition.java:82-84），即在原
  production 外包一层 `ActionParser`。
- `build(name)` 触发 `resolve(new Reference(name))`（GrammarDefinition.java:96-98）。
  `resolve` 用 `todo`/`seen` 做迭代式遍历（GrammarDefinition.java:100-120）：每当某个
  节点的 child 是 `Reference`，就 `dereference` 得到真实 parser，并调用
  `parent.replace(child, referenced)` 原地改写父节点（GrammarDefinition.java:108-111）。
  `replace` 是按**身份（`==`）**比较的：DelegateParser.java:29-34、
  ListParser.java:22-29。
- `dereference`（GrammarDefinition.java:122-144）还会展开“ref 链”（某个 production
  本身就是 `ref` 的情况），并把结果缓存在以 `Reference` 为键的 `mapping` 里。
  `Reference` 的 `equals`/`hashCode` 按名字（GrammarDefinition.java:172-186），因此
  所有 `ref("value")` 解析到**同一个实例**——这是图共享的来源，不是展开。
- 递归语法能正常 resolve：`seen` 集合（GrammarDefinition.java:104, 113-116）保证
  遍历在成环的图上终止。`value` 同时被 `start` 和 `list` 内部的 `star` 引用，
  测试断言两条路径到达的是**同一个** `ChoiceParser` 实例
  （testResolveSharesRecursiveProduction，`assertSame`）。

### 失败模式

- **未解决引用**：`build("missing")` 在 `Reference.resolve()` 处抛
  `IllegalStateException("Unknown parser reference: missing")`
  （GrammarDefinition.java:154-159；testUnknownReferenceFailsAtBuildTime）。
- **ref 环**（`a -> b -> a`）：`dereference` 在展开链时检测重复，抛
  `IllegalStateException("Recursive references detected: ...")`
  （GrammarDefinition.java:131-135；testRecursiveReferenceChainFailsAtBuildTime）。
- **左递归**（`expr -> expr '+' ... | digit`）：resolve **不会**报错——引用被替换成
  choice 自身，得到一个自环图；真正的失败发生在**解析时**：`ChoiceParser.parseOn`
  无条件先进入第一个分支（ChoiceParser.java:33-34），无限递归直到
  `StackOverflowError`（testLeftRecursionBuildsButLoopsAtParseTime）。当前实现没有
  左递归检测，这一点不应参照其他语言的 PetitParser 版本臆测。

## 2. 图的身份、复制与替换

- **`copy()` 全部是浅拷贝**：`ChoiceParser.copy` 复制数组但共享子节点
  （ChoiceParser.java:65-68）；`DelegateParser.copy` 直接复用 delegate
  （DelegateParser.java:42-44）。testCopyIsShallow 断言拷贝的子节点与原图
  `assertSame`。
- **`Mirror` 遍历不展开**：迭代器用 `seen` 集合按身份去重（Mirror.java:55, 68-80），
  环上每个节点只访问一次。
- **`Mirror.transform` 复制但保持共享**：先对每个可达节点做
  `mapping.put(parser, transformer.apply(parser.copy()))`（Mirror.java:96-99），
  再把每个拷贝的 child 用 `replace` 重接到对应拷贝上（Mirror.java:102-112）。
  因为 `mapping` 以原节点身份为键，原图中被两处引用的子图在拷贝中仍是**一个**
  共享实例——不是完全展开成树
  （testMirrorTransformCopiesButPreservesSharing：拷贝后 `star` 的 delegate 与序列
  首元素仍是同一实例）。结构相等由 `isEqualTo` 判定（Parser.java:471-485），
  它能处理环。
- `Tracer.on` 是同一机制的应用：用 `Mirror.transform` 给每个节点包一层
  `callCC` 来记录 ENTER/EXIT（Tracer.java:21-37）。本文的 instrumented parser
  与 `getChildren`/`replace` 协议兼容，同样可以被 mirror。

## 3. Optimizer 对 parser identity 的影响

`Optimizer.transform` 把注册的 transformer 组合后交给 `Mirror.transform`
（Optimizer.java:62-67），因此**默认情况下输出图的节点都是新拷贝**，不能假设
identity 不变。但有两个重要例外/细节：

- **`removeDelegates` 可能“还回”原图实例**：它只对**精确类**是 `DelegateParser`
  或 `SettableParser` 的节点解包（`class.equals`，Optimizer.java:32-40），
  `AndParser`、`NotParser`、`GrammarParser` 等子类**不会**被移除
  （testOptimizerRemoveDelegatesOnlyMatchesExactClasses）。又因为 `copy()` 是浅拷贝，
  解包后返回的是拷贝的 child——即**原图中的那个实例**
  （testOptimizerRemoveDelegatesExposesOriginalChild 对优化结果与原 delegate
  `assertSame`）。所以 optimizer 不保证“全新身份”。
- **`removeDuplicates` 增加共享**：用 `isEqualTo` 找结构等价的节点并合并
  （Optimizer.java:45-57），优化后两个原本独立的 `digit()` 变成同一实例
  （testOptimizerRemoveDuplicatesMergesEqualParsers）。
- 语义保持不变：优化后的 MiniGrammar 与原图接受同样的输入集合
  （testOptimizedGrammarParsesSameLanguage）。

## 4. `parseOn` 与 `fastParseOn` 的真实契约

`parseOn`（Parser.java:50）在不可变 `Context` 上工作，返回 `Success`/`Failure`
（Context.java:48-79）；`fastParseOn`（Parser.java:67-70）只返回新位置或 `-1`，
默认实现是**用 `parseOn` 模拟**（会分配 `Context`）。`parse()` 走慢路径
（Parser.java:75-77），`accept()`/`matches*` 走快路径（Parser.java:82-84）。
注意 `DelegateParser` 没有覆盖 `fastParseOn`，会落回默认模拟；`SettableParser`
（SettableParser.java:40-42）和 `GrammarParser`（GrammarParser.java:19-21）则显式
转发（testFastParseOnDefaultFallsBackToSlowPath 用事件证明默认实现确实走了慢路径）。

### Choice：回滚

- 慢路径（ChoiceParser.java:31-43）：每个分支都在**同一个输入 context** 上尝试；
  分支失败返回的 `Failure` 携带其内部失败位置（SequenceParser 原样透传，
  SequenceParser.java:26-28）；第一个成功的分支立即返回（ChoiceParser.java:39）；
  全部失败时用 `failureJoiner` 两两合并（ChoiceParser.java:36-37）。
- 快路径（ChoiceParser.java:46-55）：同样的有序回滚，但失败只是 `-1`，
  **没有位置、没有 message、完全绕过 failureJoiner**。
- 记录证明（testChoiceRollsBackOnSlowPath/FastPath，`abx|ac` 对 `"ac"`）：
  慢路径事件为 `ENTER alt1 @0 → EXIT alt1 @1 failure → ENTER alt2 @0 →
  EXIT alt2 @2 success`——第二分支确实从 0 重新进入；快路径事件序列相同，但失败
  出口只记录 `-1`。

### Repeat（possessive）：终止

- 慢路径（PossessiveRepeatingParser.java:21-41）：`min` 循环内 delegate 失败则**整体
  失败**并透传该失败（24-31）；进入贪婪循环后，delegate 第一次失败就**吞掉这次
  失败**并以已收集元素成功返回（32-39，成功在 35 行构造）。它是“盲”的：不回头看
  后续输入、不回滚。
- 快路径（PossessiveRepeatingParser.java:44-64）结构相同，失败时返回当前位置
  （57-58）。
- 记录证明（testRepeatTerminatesOnSlowPath，`digit*` 对 `"12a"`）：delegate 在
  0、1 成功，在 2 被**多进入一次**并失败，repeater 随后在 2 成功，值为 `['1','2']`。

### Lookahead：不消费

- `AndParser` 成功时返回**原 context** 上的 success（AndParser.java:21），值是
  delegate 的值但位置不变；快路径返回原 `position`（AndParser.java:28-31）。
- `NotParser` 反转成败，成功值为 `null`、位置不变（NotParser.java:23-30）。
- 记录证明（testAndPredicateDoesNotConsume/testNotPredicateDoesNotConsume）：
  `ENTER and @0 → EXIT and @0 success`，进入与退出位置相同。

### Action：副作用

- 慢路径只在成功时应用函数（ActionParser.java:38-39）。
- 快路径（ActionParser.java:46-50）：**纯函数（`map`）直接跳过不执行**，只有
  `hasSideEffects`（即 `mapWithSideEffects`，Parser.java:408-410）才回退到慢路径
  保证执行。也就是说 `accept()` 永远不会运行 `map` 的函数。
- 记录证明（testActionRunsOnSlowPathOnly）：同一 parser，`parse` 后函数调用列表为
  `["ran"]` 且退出事件带 `action` 标记；`fastParseOn` 后调用列表为空、事件无
  `action` 标记。testActionWithSideEffectsRunsOnBothPaths 证明
  `mapWithSideEffects` 两条路径都执行。

## 5. Failure joiner：最终选哪个失败

`ChoiceParser` 保留第一个失败，之后逐个用 `failureJoiner.apply(累计, 新失败)` 合并
（ChoiceParser.java:36-37）；`or(...)` 默认是 `SelectLast`（Parser.java:254-256，
ChoiceParser.java:18-20）。四个内建 joiner（FailureJoiner.java）：

| joiner | 实现 | 对 `"abz"`（分支 `abx`/`aby`/`aq`，失败位置 2/2/1）的结果 |
|---|---|---|
| `SelectFirst`（FailureJoiner.java:16-21） | 总取第一个 | @2 `'x' expected` |
| `SelectLast`（FailureJoiner.java:26-31，默认） | 总取最后一个 | @1 `'q' expected` |
| `SelectFarthest`（FailureJoiner.java:37-42） | 取位置最远；`<=`（40 行）使平局偏向**后**面的分支 | @2 `'y' expected` |
| `SelectFarthestJoined`（FailureJoiner.java:48-68） | 最远优先，同位置用 `" OR "` 拼接 message（60-67） | @2 `'x' expected OR 'y' expected` |

注意 `Failure` 本身只存 buffer/position/message（Failure.java:10-13），**不记录出自
哪个 parser**；胜出失败的来源 parser 由 instrumented 事件确认（`EXIT x @2 failure`、
`EXIT y @2 failure`、`EXIT q @1 failure`，testFailureJoinerSelectFarthest 断言了完整
事件序列）。默认 `SelectLast` 因此可能报告一个**更靠前**的位置——例如 MiniGrammar
解析 `"[1"` 时，`list` 分支已在位置 2 失败，但最终报告的是 `number` 分支在位置 0 的
`digit expected`。快路径完全不经过 joiner（ChoiceParser.java:46-55）。

## 6. Zero-width parser 的 repeat：当前实现是无限循环

`PossessiveRepeatingParser` 的两个循环（慢：PossessiveRepeatingParser.java:32-39；
快：PossessiveRepeatingParser.java:55-62）**都没有“位置必须前进”的检查**。
`EpsilonParser` 两条路径都在原位置成功（EpsilonParser.java:13-20）。因此：

- `epsilon().repeat(0, 3)`：有 `max` 上界，正常终止，返回 `[null, null, null]`、
  位置 0（testZeroWidthRepeatBoundedTerminates）。
- `epsilon().star()`（无界）：**既不终止也不报错，无限循环**，直到 delegate 失败
  为止。testZeroWidthRepeatUnboundedLoopsForever 在守护线程里跑快路径（不分配内存），
  观察到计数器持续增长、线程始终存活；把 delegate 置为失败后，repeater 立即以
  位置 0 **成功**结束。testZeroWidthRepeatUnboundedOnlyStopsWhenDelegateFails 用
  “成功 999 次后失败”的零宽 parser 证明慢路径同样如此：结果是含 999 个元素的成功。
- `GreedyRepeatingParser`（GreedyRepeatingParser.java:36-43）与
  `LazyRepeatingParser`（LazyRepeatingParser.java:33-48）同样没有零宽保护。
  这里如实记录现状；不要按其他语言版本的 PetitParser（其中有实现会抛错）推测。

## 7. Instrumented test support

- `petitparser-core/src/test/java/org/petitparser/parser/actions/InstrumentedParser.java`
  —— 仅测试用途的 `DelegateParser` 包装器，在慢/快两条路径上记录 ENTER/EXIT、
  输入位置、成功/失败，以及 `ActionParser` delegate 是否真的执行了函数（放在
  `org.petitparser.parser.actions` 包内是为了读取 protected 的 `hasSideEffects`，
  见 ActionParser.java:21）。快路径失败时位置字段记录原始返回值 `-1`。
- `petitparser-core/src/test/java/org/petitparser/parser/actions/InstrumentedParserTest.java`
  —— 第 4、5、6 节全部结论的事件级证明，多个测试断言**完整事件序列**，因此改动
  choice/repeat 的内部条件（如循环边界、回滚位置）会立即被这些测试区分出来。
- `petitparser-core/src/test/java/org/petitparser/tools/ParserGraphAnalysisTest.java`
  —— 第 1、2、3 节的图结构与 identity 证明。

验证命令：`mvn -q -DskipTests package`（编译）与 `mvn -q test`（完整测试）。

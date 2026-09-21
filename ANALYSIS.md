# ANALYSIS.md — java-petitparser: graph resolution, identity, and the two execution paths

All file:line references are against the current commit `e101bd7`
(`e101bd766a13b43164aec4b38d8d9e35fe4fc064`). The claims below are exercised by
the recording tests in
`petitparser-core/src/test/java/org/petitparser/AnalysisRecordingTest.java`,
which use the test-only parser
`petitparser-core/src/test/java/org/petitparser/InstrumentedParser.java`. That
parser logs, for every activation, the event (ENTER / EXIT_OK / EXIT_FAILURE /
ACTION), which entry point was used (`parseOn` → `P`, `fastParseOn` → `F`),
the input position on entry and exit, and whether the action function actually
ran. No production parser was modified.

Reproduce:

- `mvn -q -DskipTests package`
- `mvn -q test` (includes `AnalysisRecordingTest`)

## 1. The running example

`AnalysisRecordingTest.RecorderGrammar` is the smallest grammar that still
contains every feature this document traces:

```
start   = ref("items").end()
items   = ref("item").seq(ref("items")).or(ref("item"))   // recursive rule, choice
item    = and(ref("mark")).seq(ref("mark"))               // lookahead
            .seq(ref("digits")).or(ref("letter"))         // choice
digits  = ref("digit").star()                             // possessive repeat
mark    = 'x' with a pure action                          // named production + action
digit   = '1'
letter  = 'a'
```

The successful sample input is `x1x1`; the failing sample is `x!`. A second,
independent choice built directly from three instrumented parsers (failing at
positions 0, 2 and 4) is used for the failure-joiner section.

## 2. Definition resolve: identity, copying and replacement

### 2.1 References are placeholders that are only resolved at build time

`GrammarDefinition.ref(name)` does not look anything up; it constructs a
private `Reference` node holding only the name
(`petitparser-core/src/main/java/org/petitparser/tools/GrammarDefinition.java:42-44`).
A `Reference` is a parser that cannot run or be copied: `parseOn` throws
`UnsupportedOperationException("References cannot be parsed.")`
(`GrammarDefinition.java:161-164`) and `copy()` throws the same kind of error
(`GrammarDefinition.java:166-169`). Resolution therefore has to happen before
any parsing.

`build()` is `resolve(new Reference(name))`
(`GrammarDefinition.java:89-98`). The resolve algorithm
(`GrammarDefinition.java:100-120`) walks the graph reachable from the start
reference and, for every child that `instanceof Reference`, calls
`dereference(...)` and then `parent.replace(child, referenced)`
(`GrammarDefinition.java:108-111`). Replacement is a physical rewrite of the
parent's edge — `ListParser.replace` writes into the `parsers[]` array
(`petitparser-core/src/main/java/org/petitparser/parser/combinators/ListParser.java:21-29`)
and `DelegateParser.replace` reassigns the `delegate` field
(`petitparser-core/src/main/java/org/petitparser/parser/combinators/DelegateParser.java:28-34`).
The default `Parser.replace` does nothing
(`petitparser-core/src/main/java/org/petitparser/parser/Parser.java:527-529`).

Two consequences, both asserted by `testResolveReturnsStoredParserIdentity`:

1. **No copy is made.** `dereference` ultimately returns
   `parsers.get(name)` — the exact instance that was stored by `def`
   (`GrammarDefinition.java:154-159`). For a grammar whose start production is
   a single leaf, `build()` returns that leaf instance itself.
2. **The stored graph is mutated in place.** Because the replaced edges live
   inside the stored productions, reference edges held by a stored combinator
   are physically rewritten on the first build. A production whose value is
   itself a bare `Reference` (as in the two-production grammar below) has no
   parent edge to rewrite, so that value remains a `Reference`; a second
   `build()` simply re-chains through `dereference` and maps the chain to the
   same terminal parser (`GrammarDefinition.java:122-144`). Either way repeated
   builds return the same root identity, as asserted by
   `testResolveReturnsStoredParserIdentity`.

The traversal deduplicates nodes with a `seen` set (`GrammarDefinition.java:104`,
`GrammarDefinition.java:113-116`), which is what makes a recursive graph
traversable once references are gone, and it is also why a shared production is
resolved once and pointed to from many parents rather than expanded.

### 2.2 When resolution fails

Three distinct failure modes exist; none of them are parse-time failures.

- **Unknown reference.** `Reference.resolve()` throws
  `IllegalStateException("Unknown parser reference: " + name)` when the name was
  never `def`-ed (`GrammarDefinition.java:154-157`). This covers both an
  unknown start production and an unknown reference nested inside a reachable
  production (the walk dereferences every `Reference` it meets). Covered by
  `testUnknownReferenceFailsAtBuildTime`; the existing
  `GrammarDefinitionTest.testUnknownReferenceInside/Outside` exercise the same
  path.
- **Bare reference cycle.** `dereference` keeps following a chain while the
  resolved parser is itself a `Reference`, recording the chain in `references`
  (`GrammarDefinition.java:126-138`). Revisiting a reference already on that
  chain throws `IllegalStateException("Recursive references detected: ...")`
  (`GrammarDefinition.java:131-135`). So a rule defined purely as
  `a -> ref("b"), b -> ref("a")` is rejected at build time
  (`testBareReferenceCycleFailsAtBuildTime`).
- **Duplicate / missing definitions.** Defining a name twice throws
  `"Duplicate production"` (`GrammarDefinition.java:49-54`); `redef`/`action`
  on an unknown name throw `"Undefined production"`
  (`GrammarDefinition.java:60-84`).

### 2.3 Left recursion is *not* detected

The cycle check in §2.2 only follows chains that consist entirely of
`Reference` nodes. A genuine left-recursive production — one whose parser graph
begins with an edge back to itself through a real combinator — is a perfectly
legal graph and survives resolution. The standard construction is a
`SettableParser` whose delegate is set to itself (or to a graph containing
itself): `SettableParser.undefined(...)` starts as a delegate to a
`FailureParser` (`petitparser-core/src/main/java/org/petitparser/parser/combinators/SettableParser.java:15-25`),
and `set(...)` overwrites the delegate (`SettableParser.java:54-56`).

`testGenuineLeftRecursionOverflowsAtRuntime` builds `settable.set(settable)`
and shows that neither `parse` nor `fastParseOn` raises a grammar error:
`DelegateParser.parseOn` calls `delegate.parseOn(context)` unconditionally
(`DelegateParser.java:23-26`) and `SettableParser.fastParseOn` calls
`delegate.fastParseOn(...)` (`SettableParser.java:39-42`), so activation
descends through the same node until the JVM throws `StackOverflowError`. There
is no memoization, no recursion-guard and no "left recursion detected" signal
anywhere in `Parser`, `DelegateParser`, `SettableParser` or
`GrammarDefinition` at this commit. Right recursion terminates only because
each recursive activation consumes input until a terminal fails (as in
`items`).

### 2.4 `copy()` is shallow; the mirror copies nodes but preserves sharing

`Parser.copy()` is documented as a "shallow copy"
(`Parser.java:459-462`). Every built-in implementation constructs one new
parent while reusing the existing child instances verbatim, e.g.
`SequenceParser.copy` (`petitparser-core/src/main/java/org/petitparser/parser/combinators/SequenceParser.java:53-56`),
`ChoiceParser.copy` (`petitparser-core/src/main/java/org/petitparser/parser/combinators/ChoiceParser.java:64-68`)
and `DelegateParser.copy` (`DelegateParser.java:41-44`).
`testCopyIsShallowAndKeepsSharedChildren` builds `shared.seq(shared)` and shows
that after `copy()` the two parents differ (`!=`) but both children are still
the same `shared` leaf. `copy()` never deep-expands a shared subgraph.

`Mirror` is the reflective view of the reachable graph
(`petitparser-core/src/main/java/org/petitparser/utils/Mirror.java:23-36`). Its
iterator is identity-deduplicated: each parser instance is visited once even
if many edges point to it (`Mirror.java:52-81`), which is why
`Mirror.of(parser.seq(parser))` reports two nodes, not three (see the existing
`MirrorTest.testDuplicatedElementsIteration`).

`Mirror.transform(transformer)` is the graph-rewriting primitive
(`Mirror.java:95-114`):

1. it visits every distinct source node once and stores
   `source -> transformer.apply(source.copy())` in a map (`Mirror.java:96-99`);
2. it then walks the copied nodes and, for each child present in the map,
   rewrites the edge to the copied node (`Mirror.java:102-106`); children not
   in the map are left as-is (`Mirror.java:107-110`).

Because the map is keyed by source identity, **a shared source child maps to
exactly one copied child**. `testMirrorTransformCopiesEachSharedNodeOnce`
asserts that `shared.seq(shared)` (two distinct identities) becomes a fresh
two-node graph whose two children are `==`. A recursive graph is preserved as a
recursive graph with a real back-edge rather than unrolled:
`testMirrorTransformPreservesRecursiveBackEdge` builds
`eps.seq(settable)` with `settable.set(seq)` (three distinct identities) and
shows the transform result still contains exactly three nodes. So the
copy/mirror contract is "one fresh node per distinct source node, edges
rewired" — sharing is preserved and cycles remain cycles; it is neither a
fully shared pass-through nor a full tree expansion.

The source graph handed to a transform is not modified: all copying happens on
the `copy()` outputs before edges are rewired (`Mirror.java:98`).

### 2.5 The optimizer rewrites nodes, and identity changes follow the transformer

`Optimizer.transform` composes the registered transformers and applies them
through `Mirror.of(parser).transform(...)`
(`petitparser-core/src/main/java/org/petitparser/utils/Optimizer.java:62-67`).
Remember from §2.4 that the mirror first calls `parser.copy()` and only then
applies the transformer (`Mirror.java:98`), so every surviving node is at least
shallow-copied.

- **`removeDelegates()`** (`Optimizer.java:32-40`) collapses chains of nodes
  whose *exact* class is `DelegateParser` or `SettableParser` by repeatedly
  taking their first child (`Optimizer.java:34-37`). The exact-class test is
  significant: subclasses such as `ActionParser`, `AndParser`,
  `OptionalParser`, `RepeatingParser` and `FlattenParser` all extend
  `DelegateParser` but are **not** stripped. When the transformer returns a
  node that the mirror itself produced (for example it unwraps through an
  instrumented leaf and returns the copied leaf), identities in the result are
  fresh copies; when the transformer keeps returning a reachable original
  node, that original instance can surface in the optimized graph.
  `testRemoveDelegatesKeepsLeafButDropsWrappers` records the current behavior
  for `leaf.settable().settable()`: the optimized root is the same instance as
  the original `leaf` (`assertSame`), the two wrappers are gone (node count
  drops), and the input graph is still intact. This is a direct consequence of
  the transformer returning `parser.getChildren().get(0)`
  (`Optimizer.java:36`) combined with the leaf already being present as a
  mapped value in the mirror fix-up pass (`Mirror.java:100-106`). In other
  words the optimizer does not promise "all new objects"; identity sharing with
  the source is observable.
- **`removeDuplicates()`** (`Optimizer.java:45-57`) keeps a set of nodes seen
  so far and, for each new node that is structurally equal
  (`Parser.isEqualTo`, `Parser.java:471-514`) to an earlier one but not
  identical, returns the earlier instance (`Optimizer.java:48-54`). This
  deliberately *introduces* sharing: two independently-built but equal leaves
  collapse to one identity in the optimized graph
  (`testRemoveDuplicatesMergesStructurallyEqualCopies`, mirroring the existing
  `OptimizerTest.testRemoveDuplicates`). Equality is structural and recursive;
  note it compares classes, `hasEqualProperties` and children
  (`Parser.java:483-484`), so two action parsers wrapping distinct lambda
  functions are not duplicates — `ActionParser.hasEqualProperties` requires the
  functions to be equal
  (`petitparser-core/src/main/java/org/petitparser/parser/actions/ActionParser.java:52-57`).

`Tracer.on` and `Profiler.on` are pure graph transforms too. The tracer maps
every copied node to a `ContinuationParser` around it
(`petitparser-core/src/main/java/org/petitparser/utils/Tracer.java:21-37`), so
the traced parser is a fully copied, fully wrapped graph and the source parser
is untouched; `testTracerBuildsACopiedGraphAndLeavesOriginalIntact` asserts
both that ENTER/EXIT events arrive and that the original graph still parses
independently. One subtlety used later: `ContinuationParser` does not override
`fastParseOn`, so on the fast path it falls through to the base emulation in
`Parser.fastParseOn` (`Parser.java:67-70`) — i.e. wrapping with `callCC`
silently routes the fast path through `parseOn`.

## 3. The two execution paths

`parseOn(Context)` returns a `Result` (`Success` or `Failure`,
`petitparser-core/src/main/java/org/petitparser/parser/Parser.java:50`).
`fastParseOn(String, int)` returns a position, with `-1` meaning failure; the
default implementation just emulates the slow path
(`Parser.java:67-70`). Beyond that surface difference the contracts of the
combinators differ in ways that matter; the subsections below go through each.

### 3.1 Choice: restart position, rollback, and which failure survives

Slow path (`ChoiceParser.parseOn`,
`petitparser-core/src/main/java/org/petitparser/parser/combinators/ChoiceParser.java:30-43`):
every alternative is invoked with the **same incoming `context`**
(`ChoiceParser.java:34`). On failure the failed result is folded into an
accumulator through the `FailureJoiner` (`ChoiceParser.java:35-37`); on the
first success that result is returned immediately (`ChoiceParser.java:38-40`).
Because contexts are immutable (`Context` holds `final` fields,
`petitparser-core/src/main/java/org/petitparser/context/Context.java:11-27`)
"rollback" needs no state: the next branch simply starts again at the original
position. Anything a losing branch did besides returning a `Failure` is **not**
undone — notably action side effects (see §3.4).

The default joiner is `FailureJoiner.SelectLast`
(`Parser.java:254-255`, `ChoiceParser.java:18-20`). The four built-in joiners
(`petitparser-core/src/main/java/org/petitparser/utils/FailureJoiner.java`) are
pure functions of the two `Failure`s: `SelectFirst` keeps the first
(`FailureJoiner.java:16-21`), `SelectLast` keeps the second
(`FailureJoiner.java:26-31`), `SelectFarthest` prefers the larger position and
keeps the second on ties (`FailureJoiner.java:37-42`), and
`SelectFarthestJoined` prefers the larger position but, at the same position,
constructs a new failure whose message is the concatenation
`first.message + joiner + second.message`
(`FailureJoiner.java:48-68`). A `Failure` carries only buffer, position and
message (`petitparser-core/src/main/java/org/petitparser/context/Failure.java:6-18`);
it does not identify the parser that produced it, so the joiner can only
choose by position/message. To answer "which parser won the join" the tests
correlate via the event log (a `JoinerSpy` records the paired positions) and
via each instrumented parser's distinct message.

`testSelectLastKeepsLastBranchFailure`, `testSelectFarthestPicksHighestPosition`,
`testSelectFirstKeepsFirstBranchFailure` and
`testSelectFarthestJoinedTiesAtSamePosition` build three branches that fail at
0/2/4 and verify:

| joiner | resulting position | resulting message |
|---|---|---|
| `SelectFirst` | 0 | `near-msg` |
| `SelectLast` | 4 | `far-msg` (join pairs `0vs2`, `2vs4`) |
| `SelectFarthest` | 4 | `far-msg` |
| `SelectFarthestJoined`, tie at 0 | 0 | `alpha OR beta` |

Fast path (`ChoiceParser.fastParseOn`, `ChoiceParser.java:45-55`): the loop
calls each alternative with the same `position` and returns the first
non-negative result (`ChoiceParser.java:49-52`); if all return `-1` it returns
`-1` (`ChoiceParser.java:54`). Position-only information means the
`FailureJoiner` is not consulted at all — there are no `Failure` objects to
join. `testFastPathNeverInvokesFailureJoiner` asserts the spy's join list is
empty even though all three branches fail.

The recursion example shows real backtracking in the traces
(`testSuccessCallGraphSlowPath` / `...FastPath` on `x1x1`): after parsing item
`x1` at 0-2, the nested `items` tries to continue at position 4, runs
`mark`/`letter` (both fail there twice — once for each level of the recursive
choice), unwinds to position 2, fails that continuation the same way, and the
outer choice finally accepts the single-item branch ending at 4. Every losing
branch re-enters its first parser at the branch's own start position, exactly
as the code at `ChoiceParser.java:34` dictates.

### 3.2 Sequence

Slow path (`SequenceParser.parseOn`,
`petitparser-core/src/main/java/org/petitparser/parser/combinators/SequenceParser.java:20-33`)
threads `current` through the elements and returns the first failure as-is,
without any joining (`SequenceParser.java:26-28`); success wraps the collected
values into a list (`SequenceParser.java:32`). The fast path
(`SequenceParser.java:36-44`) threads the integer position and returns the
first negative result unchanged — note it returns the raw `-1`, so the
position at which the failing element failed is not propagated on this path.

### 3.3 Repeat: termination and the zero-width hazard

Possessive repeat is two loops
(`petitparser-core/src/main/java/org/petitparser/parser/repeating/PossessiveRepeatingParser.java`).

Slow path: the first loop enforces `min` and returns the failure if the
delegate cannot provide enough repetitions
(`PossessiveRepeatingParser.java:24-31`); the second loop runs while
`max == UNBOUNDED || size < max`, and when the delegate fails it returns
**success** with the elements collected so far at the last current context
(`PossessiveRepeatingParser.java:32-39`). The bounded `n` case falls out of the
same loop: after `max` successes the loop condition is false and it returns
(`PossessiveRepeatingParser.java:40`).

Fast path (`PossessiveRepeatingParser.java:44-64`) has the same shape: below
`min`, a failing delegate returns its `-1` (`PossessiveRepeatingParser.java:47-54`);
above `min`, a failing delegate returns `current` (success at the current
position) (`PossessiveRepeatingParser.java:55-62`).

There is **no progress check** in either loop: termination relies solely on the
delegate eventually failing. A delegate that succeeds without consuming input
therefore produces different behavior depending on the bound, and this is
verified rather than assumed:

- **Bounded repeat terminates normally.** `epsilon.repeat(0,3)` on `""`
  succeeds with a 3-element list, and it activates the delegate only **3**
  times (`testBoundedZeroWidthRepeatTerminates`): the loop condition
  (`count < max`) is evaluated before each activation
  (`PossessiveRepeatingParser.java:55`), so there is no fourth, "discovering"
  call — termination is decided by the counter, not by a failure.
- **Unbounded `star()` of a zero-width parser loops forever.** With
  `epsilon.star()` the condition `max == UNBOUNDED` is always true and the
  delegate never fails, so `elements.add`/`current = result` repeats at the
  same position without end; the slow path grows an `ArrayList`
  (`PossessiveRepeatingParser.java:23`, `:37`) and the fast path spins at the
  same `int`. `testUnboundedZeroWidthStarLoopsForeverSlowPath/FastPath` bound
  the spin with an instrumented cutoff: the 257th activation at position 0
  throws the test-only `CutoffException` on **both** paths, proving the loop
  neither terminates by itself nor reports an error — it spins.
- **`plus()`** satisfies `min == 1` after the first zero-width success and then
  enters the same unbounded spin (`testZeroWidthPlusLoopsAfterSatisfyingMinimum`).

The non-blind variants share the hazard in different forms:

- `LazyRepeatingParser` loops `limit` then delegate
  (`petitparser-core/src/main/java/org/petitparser/parser/repeating/LazyRepeatingParser.java:63-78`);
  if the limit keeps failing and the delegate is zero-width, neither side
  advances the position and it spins
  (`testLazyZeroWidthStarLoopsWhenLimitKeepsFailing`). With `max` bounded the
  `count >= max` guard returns `-1`/the limiter failure
  (`LazyRepeatingParser.java:68-70`), so bounded lazy repeats can terminate.
- `GreedyRepeatingParser` first consumes maximally (a zero-width delegate spins
  in the consume loop at `GreedyRepeatingParser.java:74-81`) and otherwise
  backtracks over saved positions
  (`petitparser-core/src/main/java/org/petitparser/parser/repeating/GreedyRepeatingParser.java:82-96`);
  `testGreedyZeroWidthStarLoopsWhenLimitNeverMatches` records the spin.
- The same assumption — "a successful trim parser eventually fails or moves" —
  appears in the trimming parser's `consume` helper, an unconditional
  `for(;;)` that returns only when `fastParseOn < 0`
  (`petitparser-core/src/main/java/org/petitparser/parser/actions/TrimmingParser.java:55-63`).
  A zero-width left trimmer loops before the delegate is ever consulted
  (`testZeroWidthTrimmerLoopsForever`).

This is the behavior of *this* Java implementation at this commit: there is no
"zero-width repetition" guard, no exception, and no forced termination for the
unbounded cases.

### 3.4 Lookahead and action contracts

**And-predicate** (`petitparser-core/src/main/java/org/petitparser/parser/combinators/AndParser.java`):
on the slow path it runs the delegate and, on success, returns a fresh success
built from the **original** context — `context.success(result.get())` at
`AndParser.java:20-21` — so the value is the delegate's value but the position
is the entry position; delegate failure is propagated unchanged
(`AndParser.java:22-24`). On the fast path success returns the incoming
`position`, failure returns `-1` (`AndParser.java:28-31`). The delegate really
runs — the lookahead is not a memoized guess — so a side-effecting action
inside the lookahead executes even though nothing is consumed. The running
grammar's `and(mark)` shows this directly: on `x1x1` the slow trace contains
`mark>P@0, mark#P1, mark=P1` (lookahead action runs, reaches 1) immediately
followed by `mark>P@0, ...` (the real mark starts again at 0), and
`testLookaheadRunsActionButConsumesNothing` asserts the side effect fired while
the and-parser's resulting position is 0 on both paths.

**Not-predicate** (`petitparser-core/src/main/java/org/petitparser/parser/combinators/NotParser.java`):
slow path inverts the result: delegate failure becomes
`context.success(null)` at the entry position (`NotParser.java:25-26`),
delegate success becomes a failure carrying the configured `message` at the
entry position (`NotParser.java:27-29`); fast path maps negative delegate
result to `position` and non-negative to `-1` (`NotParser.java:33-36`). In
both predicates the delegate consumes nothing from the caller's point of view.

**Optional** (`OptionalParser.java:22-35`) returns the delegate's success or
else `context.success(otherwise)` (default `null`), and its fast path returns
the delegate position on success or the unchanged `position` on failure — it
can never return `-1`.

**Actions.** `ActionParser.parseOn` runs the delegate, and on success applies
`function.apply(result.get())` to build the new success value
(`petitparser-core/src/main/java/org/petitparser/parser/actions/ActionParser.java:36-43`);
on failure it returns the failure untouched, so the function never runs
(`ActionParser.java:40-42`). The fast path is where the real contract lives
(`ActionParser.java:45-50`):

- a **pure** action (`new ActionParser(delegate, fn)`, the result of
  `Parser.map`, `Parser.java:398-400`) skips the function entirely and returns
  `delegate.fastParseOn(...)` (`ActionParser.java:48-49`) — the transformed
  value is irrelevant because only positions are returned;
- an action declared with side effects (`Parser.mapWithSideEffects`,
  `Parser.java:408-410`, `hasSideEffects == true`) calls
  `super.fastParseOn(...)`, i.e. the base emulation that routes through
  `parseOn` (`Parser.java:67-70`), guaranteeing the function runs
  (`ActionParser.java:48`).

`testActionOnlyRunsOnSlowPathSuccess`,
`testSideEffectActionForcesSlowPath` and
`testActionNeverRunsOnFailure` pin each clause: ACTION events appear exactly
once per successful slow-path parse, a side-effecting action emits
slow-path-tagged events even when entered through `fastParseOn`, and failure
yields zero ACTION events on both paths. In the grammar trace for `x1x1` the
slow path records six `mark#` ACTION events while the fast path records none,
although the leaf activation/backtracking order is otherwise identical.

Side effects interact with ordered choice: `ChoiceParser` retries branches but
does not compensate for actions they performed.
`testChoiceBacktracksButDoesNotRollbackSideEffects` constructs a first branch
that consumes a character, runs a side-effecting action (via
`mapWithSideEffects` semantics), and then fails three characters deep; the
second branch restarts at position 0 and wins. The side effect remains
observable (`["ran"]`) after the successful parse — backtracking restores the
input position, not external state. Note the asymmetry on the fast path: such a
branch is forced through `parseOn` precisely by `ActionParser.java:48`, so a
choice assembled from side-effecting actions loses the fast path's allocation
savings while preserving order and restart-position semantics. `matches()` and
`matchesSkipping()` rely on this on purpose: they build
`mapWithSideEffects(...)` pipelines and drive them with `fastParseOn`
(`Parser.java:89-106`).

`FlattenParser` is worth noting as a deliberate cross-path caller: when given a
failure message it implements `parseOn` *via* `delegate.fastParseOn` and then
manufactures either a substring success or a failure with its own `message`
(`petitparser-core/src/main/java/org/petitparser/parser/actions/FlattenParser.java:35-45`),
discarding the delegate's original message on that path.

### 3.5 Reading the recorded call graph

The complete slow-path and fast-path leaf traces for `x1x1` and `x!` are
embedded verbatim in
`testSuccessCallGraphSlowPath`, `testSuccessCallGraphFastPath` and
`testFailureCallGraphBothPaths`. They simultaneously demonstrate: (a) and-
predicate resets to the entry position; (b) possessive `star` stops on the
first non-matching character (at 2 and at 4) and reports success to its
parent; (c) every choice retry re-enters at the branch start position, so the
same instrumented leaf is visited at both 4 and 2 while backtracking; (d) on
`x!` the grammar itself succeeds with one item at position 1 and it is the
trailing end-of-input parser
(`petitparser-core/src/main/java/org/petitparser/parser/combinators/EndOfInputParser.java:21-29`,
attached by `Parser.end`, `Parser.java:380-382`) that supplies the final
failure `"end of input expected"` at position 1 — a concrete case where the
failure reported by the top-level parse is not produced by any choice branch;
and (e) the two paths visit leaves in the same order but differ in ACTION
events and path tags.

## 4. Summary of the contracts

- References resolve only during `build()`, by physically replacing edges in
  the stored productions; `build()` returns stored instances and a second
  `build()` returns the same already-resolved graph. Unknown names and bare
  reference cycles throw at build time; genuine left recursion through real
  combinators is accepted and only fails with `StackOverflowError` at runtime.
- `copy()` is shallow and shares children; `Mirror.transform` makes exactly one
  fresh node per distinct source node, preserves sharing and preserves cycles
  (no infinite expansion). Optimizer outputs can nevertheless be `==` to
  reachable source nodes when a transformer returns one (`removeDelegates`),
  and `removeDuplicates` intentionally adds sharing; structural equality never
  treats two distinct lambdas as equal.
- Choice restarts each alternative at the same immutable start context, folds
  slow-path failures with a `FailureJoiner` (default `SelectLast`), and
  bypasses the joiner on the fast path; side effects of losing branches are not
  rolled back. Repeat terminates on delegate failure or on the `max` counter,
  has no zero-width guard, and spins forever for unbounded zero-width
  delegates (and for lazy/greedy/trimmer loops whose limit never matches);
  bounded zero-width repeats terminate. Lookahead fully activates its delegate
  (actions included) but reports the entry position. Pure actions are skipped
  on the fast path; side-effecting actions force the slow path through
  `Parser.fastParseOn`'s default emulation.

## 5. Test-support files (test scope only)

- `petitparser-core/src/test/java/org/petitparser/InstrumentedParser.java` —
  configurable leaf logging ENTER/EXIT/ACTION with start/end positions and a
  P/F path tag; factories for characters, epsilon, failures at arbitrary
  positions, and a zero-width epsilon with an activation cutoff used to bound
  otherwise-infinite repeat loops. It mirrors the `ActionParser` fast/slow
  contract rather than changing any production parser.
- `petitparser-core/src/test/java/org/petitparser/AnalysisRecordingTest.java` —
  31 tests asserting the exact recorded call graphs, joiner selections,
  identity behavior, recursion behavior and zero-width outcomes. The ordered
  trace assertions are sensitive to internal choice/repeat conditions: flipping
  the success test in `ChoiceParser.fastParseOn` or the failure return in the
  unbounded loop of `PossessiveRepeatingParser.fastParseOn` makes
  `testSuccessCallGraphFastPath` and `testFailureCallGraphBothPaths` fail.

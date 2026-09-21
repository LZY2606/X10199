package org.petitparser;

import org.junit.Test;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.primitive.EpsilonParser;
import org.petitparser.tools.GrammarDefinition;
import org.petitparser.utils.FailureJoiner;
import org.petitparser.utils.Mirror;
import org.petitparser.utils.Optimizer;
import org.petitparser.utils.Tracer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Recording tests backing {@code ANALYSIS.md}. All recorded call graphs are
 * produced with {@link InstrumentedParser}, a test-only parser that logs enter,
 * exit, input position, success/failure and action execution on both execution
 * paths.
 */
public class AnalysisRecordingTest {

  private static List<String> list(String... values) {
    return Arrays.asList(values);
  }

  private static int countNodes(Parser parser) {
    int count = 0;
    for (Parser each : Mirror.of(parser)) {
      count++;
    }
    return count;
  }

  /**
   * The minimal grammar used as the running example in {@code ANALYSIS.md}:
   *
   * <pre>
   * start   = ref("items").end()
   * items   = ref("item").seq(ref("items")).or(ref("item"))   // recursion
   * item    = and(ref("mark")).seq(ref("mark"))               // lookahead
   *             .seq(ref("digits")).or(ref("letter"))         // choice
   * digits  = ref("digit").star()                             // repeat
   * </pre>
   *
   * <p>{@code mark} carries a pure action (recorded as ACTION events).
   */
  static final class RecorderGrammar extends GrammarDefinition {
    final InstrumentedParser.Log log = new InstrumentedParser.Log();
    final InstrumentedParser mark =
        InstrumentedParser.character("mark", 'x', log).withAction();
    final InstrumentedParser digit =
        InstrumentedParser.character("digit", '1', log);
    final InstrumentedParser letter =
        InstrumentedParser.character("letter", 'a', log);

    RecorderGrammar() {
      def("mark", mark);
      def("digit", digit);
      def("letter", letter);
      def("digits", ref("digit").star());
      def("item",
          ref("mark").and().seq(ref("mark")).seq(ref("digits"))
              .or(ref("letter")));
      def("items", ref("item").seq(ref("items")).or(ref("item")));
      def("start", ref("items").end());
    }

    Parser start() {
      return build("start");
    }
  }

  // ------------------------------------------------------------------
  // parseOn vs fastParseOn on the recursive example grammar
  // ------------------------------------------------------------------

  @Test
  public void testSuccessCallGraphSlowPath() {
    RecorderGrammar grammar = new RecorderGrammar();
    Parser start = grammar.start();
    Result result = start.parse("x1x1");
    assertTrue(result.isSuccess());
    assertEquals(4, result.getPosition());
    // The order exposes: and-predicate runs the delegate and then resets the
    // position; star retries after consuming and terminates on failure; the
    // recursive items choice backtracks at positions 4 and 2.
    assertEquals(list(
        "mark>P@0", "mark#P1", "mark=P1",
        "mark>P@0", "mark#P1", "mark=P1",
        "digit>P@1", "digit=P2",
        "digit>P@2", "digit!P2['1' expected]",
        "mark>P@2", "mark#P3", "mark=P3",
        "mark>P@2", "mark#P3", "mark=P3",
        "digit>P@3", "digit=P4",
        "digit>P@4", "digit!P4['1' expected]",
        "mark>P@4", "mark!P4['x' expected]",
        "letter>P@4", "letter!P4['a' expected]",
        "mark>P@4", "mark!P4['x' expected]",
        "letter>P@4", "letter!P4['a' expected]",
        "mark>P@2", "mark#P3", "mark=P3",
        "mark>P@2", "mark#P3", "mark=P3",
        "digit>P@3", "digit=P4",
        "digit>P@4", "digit!P4['1' expected]"),
        grammar.log.trace());
    assertEquals(6, grammar.log.count(InstrumentedParser.Event.Kind.ACTION));
  }

  @Test
  public void testSuccessCallGraphFastPath() {
    RecorderGrammar grammar = new RecorderGrammar();
    Parser start = grammar.start();
    assertEquals(4, start.fastParseOn("x1x1", 0));
    // Same leaf activation order and same backtracking as the slow path, but
    // every event is tagged F and there are no ACTION events: pure actions are
    // skipped on the fast path.
    assertEquals(list(
        "mark>F@0", "mark=F1",
        "mark>F@0", "mark=F1",
        "digit>F@1", "digit=F2",
        "digit>F@2", "digit!F2['1' expected]",
        "mark>F@2", "mark=F3",
        "mark>F@2", "mark=F3",
        "digit>F@3", "digit=F4",
        "digit>F@4", "digit!F4['1' expected]",
        "mark>F@4", "mark!F4['x' expected]",
        "letter>F@4", "letter!F4['a' expected]",
        "mark>F@4", "mark!F4['x' expected]",
        "letter>F@4", "letter!F4['a' expected]",
        "mark>F@2", "mark=F3",
        "mark>F@2", "mark=F3",
        "digit>F@3", "digit=F4",
        "digit>F@4", "digit!F4['1' expected]"),
        grammar.log.trace());
    assertEquals(0, grammar.log.count(InstrumentedParser.Event.Kind.ACTION));
  }

  @Test
  public void testFailureCallGraphBothPaths() {
    RecorderGrammar grammar = new RecorderGrammar();
    Parser start = grammar.start();

    Result result = start.parse("x!");
    assertTrue(result.isFailure());
    // digits fails at 1; the second choice of items restarts at 1 (mark then
    // letter both fail there); items falls back to the single-item branch which
    // succeeds; the trailing end-of-input parser then fails at position 1.
    assertEquals(list(
        "mark>P@0", "mark#P1", "mark=P1",
        "mark>P@0", "mark#P1", "mark=P1",
        "digit>P@1", "digit!P1['1' expected]",
        "mark>P@1", "mark!P1['x' expected]",
        "letter>P@1", "letter!P1['a' expected]",
        "mark>P@1", "mark!P1['x' expected]",
        "letter>P@1", "letter!P1['a' expected]",
        "mark>P@0", "mark#P1", "mark=P1",
        "mark>P@0", "mark#P1", "mark=P1",
        "digit>P@1", "digit!P1['1' expected]"),
        grammar.log.trace());
    assertEquals(1, result.getPosition());
    assertEquals("end of input expected", result.getMessage());

    grammar.log.clear();
    assertEquals(-1, start.fastParseOn("x!", 0));
    assertEquals(list(
        "mark>F@0", "mark=F1",
        "mark>F@0", "mark=F1",
        "digit>F@1", "digit!F1['1' expected]",
        "mark>F@1", "mark!F1['x' expected]",
        "letter>F@1", "letter!F1['a' expected]",
        "mark>F@1", "mark!F1['x' expected]",
        "letter>F@1", "letter!F1['a' expected]",
        "mark>F@0", "mark=F1",
        "mark>F@0", "mark=F1",
        "digit>F@1", "digit!F1['1' expected]"),
        grammar.log.trace());
  }

  // ------------------------------------------------------------------
  // Failure joiner: three branches failing at different remote positions
  // ------------------------------------------------------------------

  private static final class JoinerSpy implements FailureJoiner {
    private final FailureJoiner delegate;
    final List<String> joins = new ArrayList<>();

    JoinerSpy(FailureJoiner delegate) {
      this.delegate = delegate;
    }

    @Override
    public Failure apply(Failure first, Failure second) {
      joins.add(first.getPosition() + "vs" + second.getPosition());
      return delegate.apply(first, second);
    }
  }

  private Parser threeBranches(
      InstrumentedParser.Log log, FailureJoiner joiner) {
    InstrumentedParser near =
        InstrumentedParser.failing("near", "near-msg", log);
    InstrumentedParser mid =
        InstrumentedParser.failingAfter("mid", 2, "mid-msg", log);
    InstrumentedParser far =
        InstrumentedParser.failingAfter("far", 4, "far-msg", log);
    return new org.petitparser.parser.combinators.ChoiceParser(
        joiner, near, mid, far);
  }

  @Test
  public void testSelectLastKeepsLastBranchFailure() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    JoinerSpy spy = new JoinerSpy(new FailureJoiner.SelectLast());
    Failure failure = (Failure) threeBranches(log, spy).parse("abcdef");
    assertEquals(4, failure.getPosition());
    assertEquals("far-msg", failure.getMessage());
    assertEquals(list("0vs2", "2vs4"), spy.joins);
    // All branches were attempted from position 0; choice rollback does not
    // move the shared start position.
    assertEquals(list(
        "near>P@0", "near!P0[near-msg]",
        "mid>P@0", "mid!P2[mid-msg]",
        "far>P@0", "far!P4[far-msg]"), log.trace());
  }

  @Test
  public void testSelectFarthestPicksHighestPosition() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    Failure failure = (Failure) threeBranches(log,
        new FailureJoiner.SelectFarthest()).parse("abcdef");
    assertEquals(4, failure.getPosition());
    assertEquals("far-msg", failure.getMessage());
  }

  @Test
  public void testSelectFirstKeepsFirstBranchFailure() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    Failure failure = (Failure) threeBranches(log,
        new FailureJoiner.SelectFirst()).parse("abcdef");
    assertEquals(0, failure.getPosition());
    assertEquals("near-msg", failure.getMessage());
  }

  @Test
  public void testSelectFarthestJoinedTiesAtSamePosition() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser a = InstrumentedParser.failing("a", "alpha", log);
    InstrumentedParser b = InstrumentedParser.failing("b", "beta", log);
    Failure failure = (Failure) new org.petitparser.parser.combinators
        .ChoiceParser(new FailureJoiner.SelectFarthestJoined(), a, b)
        .parse("z");
    assertEquals(0, failure.getPosition());
    assertEquals("alpha OR beta", failure.getMessage());
  }

  @Test
  public void testFastPathNeverInvokesFailureJoiner() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    JoinerSpy spy = new JoinerSpy(new FailureJoiner.SelectFarthest());
    int position = threeBranches(log, spy).fastParseOn("abcdef", 0);
    assertEquals(-1, position);
    assertTrue(spy.joins.isEmpty());
  }

  // ------------------------------------------------------------------
  // Action contract: on which path and when does the function run
  // ------------------------------------------------------------------

  @Test
  public void testActionOnlyRunsOnSlowPathSuccess() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser leaf = InstrumentedParser.epsilon("leaf", log)
        .withAction();

    Result result = leaf.parse("");
    assertTrue(result.isSuccess());
    assertEquals(1, log.count(InstrumentedParser.Event.Kind.ACTION));
    assertEquals(list("leaf>P@0", "leaf#P0", "leaf=P0"), log.trace());

    log.clear();
    assertEquals(0, leaf.fastParseOn("", 0));
    // Pure action is skipped; fast path returns the delegate position.
    assertEquals(0, log.count(InstrumentedParser.Event.Kind.ACTION));
    assertEquals(list("leaf>F@0", "leaf=F0"), log.trace());
  }

  @Test
  public void testSideEffectActionForcesSlowPath() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser leaf = InstrumentedParser.epsilon("leaf", log)
        .withAction(true);

    assertEquals(0, leaf.fastParseOn("", 0));
    assertEquals(1, log.count(InstrumentedParser.Event.Kind.ACTION));
    // Events are tagged P even though the entry point was fastParseOn, because
    // the fast path delegates to parseOn.
    assertEquals(list("leaf>P@0", "leaf#P0", "leaf=P0"), log.trace());
  }

  @Test
  public void testActionNeverRunsOnFailure() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser leaf =
        InstrumentedParser.failing("leaf", "boom", log).withAction();

    assertTrue(leaf.parse("").isFailure());
    assertEquals(-1, leaf.fastParseOn("", 0));
    assertEquals(0, log.count(InstrumentedParser.Event.Kind.ACTION));
  }

  @Test
  public void testChoiceBacktrackDoesNotRollbackSideEffects() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    List<String> sideEffects = new ArrayList<>();
    InstrumentedParser consumed = InstrumentedParser
        .character("consumed", 'a', log)
        .withAction(value -> {
          sideEffects.add("ran");
          return value;
        }, true);
    InstrumentedParser winner =
        InstrumentedParser.character("winner", 'a', log);
    Parser choice = consumed.seq(InstrumentedParser
        .failingAfter("deep", 3, "deep failure", log)).or(winner);

    Result result = choice.parse("a");
    assertTrue(result.isSuccess());
    assertEquals(1, result.getPosition());
    // The first branch consumed 'a', ran its side effect, then failed deep in
    // the input; the second branch restarted at 0 and won. The side effect
    // remains observable: there is no rollback.
    assertEquals(list("ran"), sideEffects);
    assertEquals(list(
        "consumed>P@0", "consumed#P1", "consumed=P1",
        "deep>P@1", "deep!P4[deep failure]",
        "winner>P@0", "winner=P1"), log.trace());
  }

  @Test
  public void testLookaheadRunsActionButConsumesNothing() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    List<String> sideEffects = new ArrayList<>();
    InstrumentedParser leaf = InstrumentedParser.character("leaf", 'a', log)
        .withAction(value -> {
          sideEffects.add("peeked");
          return value;
        }, true);
    Parser andParser = leaf.and();

    Result result = andParser.parse("a");
    assertTrue(result.isSuccess());
    assertEquals(0, result.getPosition());
    assertEquals(list("peeked"), sideEffects);

    log.clear();
    sideEffects.clear();
    int fast = andParser.fastParseOn("a", 0);
    assertEquals(0, fast);
    assertEquals(list("peeked"), sideEffects);
  }

  // ------------------------------------------------------------------
  // Zero-width repeat: terminate, report error, or loop forever?
  // ------------------------------------------------------------------

  @Test
  public void testBoundedZeroWidthRepeatTerminates() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser epsilon = InstrumentedParser.epsilon("eps", log);

    Result result = epsilon.repeat(0, 3).parse("");
    assertTrue(result.isSuccess());
    assertEquals(3, log.count(InstrumentedParser.Event.Kind.ENTER));
    assertEquals(3, ((List<?>) result.get()).size());

    log.clear();
    assertEquals(0, epsilon.repeat(0, 3).fastParseOn("", 0));
    assertEquals(3, log.count(InstrumentedParser.Event.Kind.ENTER));
  }

  @Test
  public void testUnboundedZeroWidthStarLoopsForeverSlowPath() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser epsilon =
        InstrumentedParser.epsilonWithCutoff("eps", 256, log);
    try {
      epsilon.star().parse("");
      fail("Expected the instrumented cutoff to fire");
    } catch (InstrumentedParser.CutoffException error) {
      // The repeat never stops on its own: the delegate keeps succeeding at
      // the same position and the loop adds elements forever.
      assertEquals(257, error.activations);
      assertEquals(0, error.position);
    }
  }

  @Test
  public void testUnboundedZeroWidthStarLoopsForeverFastPath() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser epsilon =
        InstrumentedParser.epsilonWithCutoff("eps", 256, log);
    try {
      epsilon.star().fastParseOn("", 0);
      fail("Expected the instrumented cutoff to fire");
    } catch (InstrumentedParser.CutoffException error) {
      assertEquals(257, error.activations);
      assertEquals(0, error.position);
    }
  }

  @Test
  public void testZeroWidthPlusLoopsAfterSatisfyingMinimum() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser epsilon =
        InstrumentedParser.epsilonWithCutoff("eps", 256, log);
    try {
      epsilon.plus().fastParseOn("", 0);
      fail("Expected the instrumented cutoff to fire");
    } catch (InstrumentedParser.CutoffException error) {
      // One iteration satisfies min == 1, then the unbounded loop spins.
      assertEquals(257, error.activations);
    }
  }

  @Test
  public void testLazyZeroWidthStarLoopsWhenLimitKeepsFailing() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser epsilon =
        InstrumentedParser.epsilonWithCutoff("eps", 128, log);
    InstrumentedParser never =
        InstrumentedParser.failing("never", "never matches", log);
    try {
      epsilon.starLazy(never).fastParseOn("", 0);
      fail("Expected the instrumented cutoff to fire");
    } catch (InstrumentedParser.CutoffException error) {
      // limit keeps failing and the zero-width delegate keeps "consuming", so
      // the lazy loop never advances either.
      assertEquals(129, error.activations);
    }
  }

  @Test
  public void testGreedyZeroWidthStarLoopsWhenLimitNeverMatches() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser epsilon =
        InstrumentedParser.epsilonWithCutoff("eps", 128, log);
    InstrumentedParser never =
        InstrumentedParser.failing("never", "never matches", log);
    try {
      epsilon.starGreedy(never).fastParseOn("", 0);
      fail("Expected the instrumented cutoff to fire");
    } catch (InstrumentedParser.CutoffException error) {
      assertEquals(129, error.activations);
    }
  }

  @Test
  public void testZeroWidthTrimmerLoopsForever() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser left =
        InstrumentedParser.epsilonWithCutoff("left", 64, log);
    Parser parser = of('a').trim(left, new EpsilonParser());
    try {
      parser.fastParseOn("a", 0);
      fail("Expected the instrumented cutoff to fire");
    } catch (InstrumentedParser.CutoffException error) {
      assertEquals(65, error.activations);
    }
  }


  // ------------------------------------------------------------------
  // Definition resolve: when references resolve, cycles, left recursion
  // ------------------------------------------------------------------

  static final class PlainGrammar extends GrammarDefinition {
    final InstrumentedParser.Log log = new InstrumentedParser.Log();
    final InstrumentedParser leaf =
        InstrumentedParser.character("leaf", 'a', log);

    PlainGrammar() {
      def("start", ref("leaf"));
      def("leaf", leaf);
    }
  }

  @Test
  public void testResolveReturnsStoredParserIdentity() {
    PlainGrammar grammar = new PlainGrammar();
    Parser built = grammar.build();
    // build() returns the exact parser instance stored in the definition; it
    // is resolved in place rather than copied.
    assertSame(grammar.leaf, built);
    // Building again returns the same graph: references were replaced inside
    // the stored production on the first build.
    assertSame(built, grammar.build());
  }

  static final class UnknownGrammar extends GrammarDefinition {
    UnknownGrammar() {
      def("start", ref("missing"));
    }
  }

  @Test
  public void testUnknownReferenceFailsAtBuildTime() {
    // A dangling reference is never parsed; resolve throws before any input.
    try {
      new UnknownGrammar().build();
      fail();
    } catch (IllegalStateException error) {
      assertEquals("Unknown parser reference: missing", error.getMessage());
    }
  }

  static final class BareCycleGrammar extends GrammarDefinition {
    BareCycleGrammar() {
      def("start", ref("a"));
      def("a", ref("b"));
      def("b", ref("a"));
    }
  }

  @Test
  public void testBareReferenceCycleFailsAtBuildTime() {
    try {
      new BareCycleGrammar().build();
      fail();
    } catch (IllegalStateException error) {
      assertTrue(error.getMessage().startsWith("Recursive references detected"));
    }
  }

  @Test
  public void testGenuineLeftRecursionOverflowsAtRuntime() {
    // A production that begins with a reference to itself is a legal graph
    // (settable back-edge); nothing rejects it at build time, so the parser
    // recurses into itself until the stack overflows on both paths.
    SettableParser settable = SettableParser.undefined("left recursive");
    settable.set(settable);
    try {
      settable.parse("x");
      fail();
    } catch (StackOverflowError expected) {
      // documented runtime behavior
    }
    try {
      settable.fastParseOn("x", 0);
      fail();
    } catch (StackOverflowError expected) {
      // documented runtime behavior
    }
  }

  // ------------------------------------------------------------------
  // copy() is shallow; Mirror.transform preserves shared subgraphs
  // ------------------------------------------------------------------

  @Test
  public void testCopyIsShallowAndKeepsSharedChildren() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser shared =
        InstrumentedParser.character("shared", 'a', log);
    Parser sequence = shared.seq(shared);

    Parser copy = sequence.copy();
    assertNotSame(sequence, copy);
    // The two parents are different nodes, but both children are still the
    // very same shared leaf: copy() never deep-expands.
    assertSame(shared, copy.getChildren().get(0));
    assertSame(shared, copy.getChildren().get(1));
  }

  @Test
  public void testMirrorTransformCopiesEachSharedNodeOnce() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser shared =
        InstrumentedParser.character("shared", 'a', log);
    Parser sequence = shared.seq(shared);

    Parser transformed = Mirror.of(sequence).transform(Parser::copy);
    assertNotSame(sequence, transformed);
    // Two distinct node identities in the source yield two in the copy; the
    // duplicated leaf collapses to a single copied leaf.
    assertEquals(2, countNodes(sequence));
    assertEquals(2, countNodes(transformed));
    assertSame(transformed.getChildren().get(0),
        transformed.getChildren().get(1));
  }

  @Test
  public void testMirrorTransformPreservesRecursiveBackEdge() {
    SettableParser settable = SettableParser.undefined();
    Parser seq = InstrumentedParser.epsilon("eps",
        new InstrumentedParser.Log()).seq(settable);
    settable.set(seq);
    assertEquals(3, countNodes(settable));

    Parser copy = Mirror.of(settable).transform(Parser::copy);
    // The copied graph is still a one-node-per-source-node graph with a real
    // back-edge, not an infinite expansion.
    assertEquals(3, countNodes(copy));
  }

  // ------------------------------------------------------------------
  // Optimizer rewrites and their effect on parser identity
  // ------------------------------------------------------------------

  @Test
  public void testRemoveDelegatesKeepsLeafButDropsWrappers() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser leaf =
        InstrumentedParser.character("leaf", 'a', log);
    Parser input = leaf.settable().settable();
    int sourceNodes = countNodes(input);

    Parser output = new Optimizer().removeDelegates().transform(input);
    // The wrappers disappear and the leaf surfaces as the root. Because the
    // removeDelegates transformer returns that reachable node itself (rather
    // than a copy), the optimized root is the very same instance as the
    // original leaf; the source graph is not modified by the transform.
    assertSame(leaf, output);
    assertTrue(output.isEqualTo(leaf));
    assertTrue(countNodes(output) < sourceNodes);
    assertSame(leaf, input.getChildren().get(0).getChildren().get(0));
  }

  @Test
  public void testIdentityTransformProducesAllFreshCopies() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser leaf =
        InstrumentedParser.character("leaf", 'a', log);
    Parser input = leaf.settable();
    Parser output = Mirror.of(input).transform(Parser::copy);
    // With an identity transformer every node is a fresh shallow copy.
    assertNotSame(input, output);
    assertNotSame(leaf, output.getChildren().get(0));
  }

  @Test
  public void testRemoveDuplicatesMergesStructurallyEqualCopies() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    Parser first = InstrumentedParser.character("same", 'a', log);
    Parser second = InstrumentedParser.character("same", 'a',
        new InstrumentedParser.Log());
    assertFalse(first == second);
    Parser input = first.seq(second);

    Parser output = new Optimizer().removeDuplicates().transform(input);
    assertSame(output.getChildren().get(0), output.getChildren().get(1));
  }

  @Test
  public void testTracerBuildsACopiedGraphAndLeavesOriginalIntact() {
    InstrumentedParser.Log log = new InstrumentedParser.Log();
    InstrumentedParser leaf = InstrumentedParser.character("leaf", 'a', log);
    Parser original = leaf;

    List<String> trace = new ArrayList<>();
    Parser traced = Tracer.on(original, event -> trace.add(event.type.name()));
    assertNotSame(original, traced);
    assertTrue(traced.parse("a").isSuccess());
    assertFalse(trace.isEmpty());
    assertTrue(trace.contains("ENTER"));
    assertTrue(trace.contains("EXIT"));
    // The original graph is untouched and still parses on its own.
    assertTrue(original.parse("a").isSuccess());
  }
}

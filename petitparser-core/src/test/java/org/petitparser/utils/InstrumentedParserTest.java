package org.petitparser.utils;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;
import org.petitparser.tools.GrammarDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.lowerCase;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Tests {@link InstrumentedParser} and documents the exact execution semantics
 * of the parser graph built from a {@link GrammarDefinition}. The expected
 * event sequences in this file are referenced from {@code ANALYSIS.md}.
 */
public class InstrumentedParserTest {

  /**
   * Minimal grammar with a recursive rule (expression), a choice (expression
   * and term), a repeat (number), a lookahead (and in term), actions (pick and
   * parseInt) and named productions.
   */
  static class ExpressionGrammar extends GrammarDefinition {
    ExpressionGrammar() {
      def("start", ref("expression").end());
      def("expression",
          ref("term").seq(of('+').seq(ref("expression"))).or(ref("term")));
      def("term",
          of('(').and().seq(of('(')).seq(ref("expression")).seq(of(')')).pick(2)
              .or(ref("number")));
      def("number", digit().plus().flatten());
      action("number", (Function<String, Integer>) Integer::parseInt);
    }
  }

  static class UnknownReferenceGrammar extends GrammarDefinition {
    UnknownReferenceGrammar() {
      def("start", ref("missing"));
    }
  }

  static class DirectRecursiveReferenceGrammar extends GrammarDefinition {
    DirectRecursiveReferenceGrammar() {
      def("start", ref("start"));
    }
  }

  static class IndirectRecursiveReferenceGrammar extends GrammarDefinition {
    IndirectRecursiveReferenceGrammar() {
      def("start", ref("a"));
      def("a", ref("b"));
      def("b", ref("a"));
    }
  }

  static class LeftRecursiveGrammar extends GrammarDefinition {
    LeftRecursiveGrammar() {
      def("start", ref("expression"));
      def("expression", ref("expression").seq(of('+')).or(digit()));
    }
  }

  private static List<String> render(List<InstrumentedParser.Event> events) {
    return events.stream().map(InstrumentedParser.Event::toString)
        .collect(Collectors.toList());
  }

  private static Parser instrumentedExpression(List<InstrumentedParser.Event> log) {
    return InstrumentedParser.instrument(new ExpressionGrammar().build(), log);
  }

  // ------------------------------------------------------------------
  // GrammarDefinition: resolution, identity, and failure modes.
  // ------------------------------------------------------------------

  @Test
  public void testBuildResolvesInPlaceAndSharesProductions() {
    ExpressionGrammar grammar = new ExpressionGrammar();
    Parser first = grammar.build();
    Parser second = grammar.build();
    assertSame("build() does not copy, repeated builds return the same root",
        first, second);
    assertFalse("no unresolved references remain", Mirror.of(first).stream()
        .anyMatch(parser -> parser.getClass().getSimpleName().equals("Reference")));
    // start = pick(0) of [expression, end]; navigate to the expression choice.
    Parser expression = first.getChildren().get(0).getChildren().get(0);
    Parser sequence = expression.getChildren().get(0);
    Parser termInSequence = sequence.getChildren().get(0);
    Parser termAlternative = expression.getChildren().get(1);
    assertSame("both references to 'term' resolve to the same instance",
        termInSequence, termAlternative);
  }

  @Test
  public void testUnknownReferenceFailsAtBuildTime() {
    try {
      new UnknownReferenceGrammar().build();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException exception) {
      assertEquals("Unknown parser reference: missing", exception.getMessage());
    }
  }

  @Test
  public void testRecursiveReferencesFailAtBuildTime() {
    try {
      new DirectRecursiveReferenceGrammar().build();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException exception) {
      assertEquals("Recursive references detected: start",
          exception.getMessage());
    }
    try {
      new IndirectRecursiveReferenceGrammar().build();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException exception) {
      assertEquals("Recursive references detected: start, a, b",
          exception.getMessage());
    }
  }

  @Test
  public void testStructuralLeftRecursionOverflowsTheStack() {
    Parser parser = new LeftRecursiveGrammar().build();
    try {
      parser.parse("1");
      fail("Expected StackOverflowError");
    } catch (StackOverflowError expected) {
      // Structural left recursion is not detected at build time; the
      // mutually recursive parseOn calls never make progress.
    }
  }

  // ------------------------------------------------------------------
  // Copy, mirror, and optimizer: identity of the parser graph.
  // ------------------------------------------------------------------

  @Test
  public void testCopyIsShallow() {
    Parser original = digit().star();
    Parser copy = original.copy();
    assertNotSame(original, copy);
    assertSame("copy() keeps the original children",
        original.getChildren().get(0), copy.getChildren().get(0));
  }

  @Test
  public void testMirrorTransformPreservesSharingAndCycles() {
    Parser shared = digit();
    Parser root = shared.seq(shared);
    Parser copy = Mirror.of(root).transform(Function.identity());
    assertNotSame(root, copy);
    assertNotSame(shared, copy.getChildren().get(0));
    assertSame("a shared child is copied once and stays shared",
        copy.getChildren().get(0), copy.getChildren().get(1));

    SettableParser knot = SettableParser.undefined();
    knot.set(knot.seq(digit()));
    Parser knotCopy = Mirror.of(knot).transform(Function.identity());
    assertSame("a cycle is copied as a cycle",
        knotCopy, knotCopy.getChildren().get(0).getChildren().get(0));
  }

  @Test
  public void testOptimizerRewritesIdentity() {
    Parser input = lowerCase().settable();
    Parser optimized = new Optimizer().removeDelegates().transform(input);
    assertEquals(CharacterParser.class, optimized.getClass());
    assertSame("removeDelegates unwraps to the original (shared) child, " +
        "because copy() is shallow", input.getChildren().get(0), optimized);

    Parser identical = new Optimizer().transform(input);
    assertNotSame("even the identity transformation copies the graph",
        input, identical);

    Parser duplicates = lowerCase().seq(lowerCase());
    Parser deduplicated = new Optimizer().removeDuplicates().transform(duplicates);
    assertNotSame(duplicates.getChildren().get(0), duplicates.getChildren().get(1));
    assertSame("structurally equal parsers are merged into one instance",
        deduplicated.getChildren().get(0), deduplicated.getChildren().get(1));
  }

  // ------------------------------------------------------------------
  // parseOn (slow path) versus fastParseOn (fast path): full traces.
  // ------------------------------------------------------------------

  @Test
  public void testSlowTraceOnSuccess() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Result result = instrumentedExpression(log).parse("1+2");
    assertTrue(result.isSuccess());
    assertEquals(3, result.getPosition());
    assertEquals(Arrays.asList(1, Arrays.asList('+', 2)), result.get());
    List<String> expected = Arrays.asList(
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW ChoiceParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW ChoiceParser @0",
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW AndParser @0",
        "ENTER SLOW CharacterParser['(' expected] @0",
        "EXIT SLOW CharacterParser['(' expected] @0 failure",
        "EXIT SLOW AndParser @0 failure",
        "EXIT SLOW SequenceParser @0 failure",
        "EXIT SLOW ActionParser @0 failure",
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW FlattenParser @0",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @0",
        "ENTER SLOW CharacterParser[digit expected] @0",
        "EXIT SLOW CharacterParser[digit expected] @1 success",
        "ENTER SLOW CharacterParser[digit expected] @1",
        "EXIT SLOW CharacterParser[digit expected] @1 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @1 success",
        "EXIT SLOW FlattenParser @1 success",
        "ACTION SLOW ActionParser",
        "EXIT SLOW ActionParser @1 success",
        "EXIT SLOW ChoiceParser @1 success",
        "ENTER SLOW SequenceParser @1",
        "ENTER SLOW CharacterParser['+' expected] @1",
        "EXIT SLOW CharacterParser['+' expected] @2 success",
        "ENTER SLOW ChoiceParser @2",
        "ENTER SLOW SequenceParser @2",
        "ENTER SLOW ChoiceParser @2",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW SequenceParser @2",
        "ENTER SLOW AndParser @2",
        "ENTER SLOW CharacterParser['(' expected] @2",
        "EXIT SLOW CharacterParser['(' expected] @2 failure",
        "EXIT SLOW AndParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "EXIT SLOW ActionParser @2 failure",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW FlattenParser @2",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @2",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @3 success",
        "ENTER SLOW CharacterParser[digit expected] @3",
        "EXIT SLOW CharacterParser[digit expected] @3 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @3 success",
        "EXIT SLOW FlattenParser @3 success",
        "ACTION SLOW ActionParser",
        "EXIT SLOW ActionParser @3 success",
        "EXIT SLOW ChoiceParser @3 success",
        "ENTER SLOW SequenceParser @3",
        "ENTER SLOW CharacterParser['+' expected] @3",
        "EXIT SLOW CharacterParser['+' expected] @3 failure",
        "EXIT SLOW SequenceParser @3 failure",
        "EXIT SLOW SequenceParser @3 failure",
        "ENTER SLOW ChoiceParser @2",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW SequenceParser @2",
        "ENTER SLOW AndParser @2",
        "ENTER SLOW CharacterParser['(' expected] @2",
        "EXIT SLOW CharacterParser['(' expected] @2 failure",
        "EXIT SLOW AndParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "EXIT SLOW ActionParser @2 failure",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW FlattenParser @2",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @2",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @3 success",
        "ENTER SLOW CharacterParser[digit expected] @3",
        "EXIT SLOW CharacterParser[digit expected] @3 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @3 success",
        "EXIT SLOW FlattenParser @3 success",
        "ACTION SLOW ActionParser",
        "EXIT SLOW ActionParser @3 success",
        "EXIT SLOW ChoiceParser @3 success",
        "EXIT SLOW ChoiceParser @3 success",
        "EXIT SLOW SequenceParser @3 success",
        "EXIT SLOW SequenceParser @3 success",
        "EXIT SLOW ChoiceParser @3 success",
        "ENTER SLOW EndOfInputParser[end of input expected] @3",
        "EXIT SLOW EndOfInputParser[end of input expected] @3 success",
        "EXIT SLOW SequenceParser @3 success",
        "ACTION SLOW ActionParser",
        "EXIT SLOW ActionParser @3 success");
    assertEquals(expected, render(log));
  }

  @Test
  public void testFastTraceOnSuccess() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = instrumentedExpression(log);
    assertEquals(3, parser.fastParseOn("1+2", 0));
    assertTrue("no actions are executed on the fast path",
        log.stream().noneMatch(event -> event.kind == InstrumentedParser.Event.Kind.ACTION));
    List<String> expected = Arrays.asList(
        "ENTER FAST ActionParser @0",
        "ENTER FAST SequenceParser @0",
        "ENTER FAST ChoiceParser @0",
        "ENTER FAST SequenceParser @0",
        "ENTER FAST ChoiceParser @0",
        "ENTER FAST ActionParser @0",
        "ENTER FAST SequenceParser @0",
        "ENTER FAST AndParser @0",
        "ENTER FAST CharacterParser['(' expected] @0",
        "EXIT FAST CharacterParser['(' expected] @-1 failure",
        "EXIT FAST AndParser @-1 failure",
        "EXIT FAST SequenceParser @-1 failure",
        "EXIT FAST ActionParser @-1 failure",
        "ENTER FAST ActionParser @0",
        "ENTER FAST FlattenParser @0",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @0",
        "ENTER SLOW CharacterParser[digit expected] @0",
        "EXIT SLOW CharacterParser[digit expected] @1 success",
        "ENTER SLOW CharacterParser[digit expected] @1",
        "EXIT SLOW CharacterParser[digit expected] @1 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @1 success",
        "EXIT FAST FlattenParser @1 success",
        "EXIT FAST ActionParser @1 success",
        "EXIT FAST ChoiceParser @1 success",
        "ENTER FAST SequenceParser @1",
        "ENTER FAST CharacterParser['+' expected] @1",
        "EXIT FAST CharacterParser['+' expected] @2 success",
        "ENTER FAST ChoiceParser @2",
        "ENTER FAST SequenceParser @2",
        "ENTER FAST ChoiceParser @2",
        "ENTER FAST ActionParser @2",
        "ENTER FAST SequenceParser @2",
        "ENTER FAST AndParser @2",
        "ENTER FAST CharacterParser['(' expected] @2",
        "EXIT FAST CharacterParser['(' expected] @-1 failure",
        "EXIT FAST AndParser @-1 failure",
        "EXIT FAST SequenceParser @-1 failure",
        "EXIT FAST ActionParser @-1 failure",
        "ENTER FAST ActionParser @2",
        "ENTER FAST FlattenParser @2",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @2",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @3 success",
        "ENTER SLOW CharacterParser[digit expected] @3",
        "EXIT SLOW CharacterParser[digit expected] @3 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @3 success",
        "EXIT FAST FlattenParser @3 success",
        "EXIT FAST ActionParser @3 success",
        "EXIT FAST ChoiceParser @3 success",
        "ENTER FAST SequenceParser @3",
        "ENTER FAST CharacterParser['+' expected] @3",
        "EXIT FAST CharacterParser['+' expected] @-1 failure",
        "EXIT FAST SequenceParser @-1 failure",
        "EXIT FAST SequenceParser @-1 failure",
        "ENTER FAST ChoiceParser @2",
        "ENTER FAST ActionParser @2",
        "ENTER FAST SequenceParser @2",
        "ENTER FAST AndParser @2",
        "ENTER FAST CharacterParser['(' expected] @2",
        "EXIT FAST CharacterParser['(' expected] @-1 failure",
        "EXIT FAST AndParser @-1 failure",
        "EXIT FAST SequenceParser @-1 failure",
        "EXIT FAST ActionParser @-1 failure",
        "ENTER FAST ActionParser @2",
        "ENTER FAST FlattenParser @2",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @2",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @3 success",
        "ENTER SLOW CharacterParser[digit expected] @3",
        "EXIT SLOW CharacterParser[digit expected] @3 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @3 success",
        "EXIT FAST FlattenParser @3 success",
        "EXIT FAST ActionParser @3 success",
        "EXIT FAST ChoiceParser @3 success",
        "EXIT FAST ChoiceParser @3 success",
        "EXIT FAST SequenceParser @3 success",
        "EXIT FAST SequenceParser @3 success",
        "EXIT FAST ChoiceParser @3 success",
        "ENTER FAST EndOfInputParser[end of input expected] @3",
        "EXIT FAST EndOfInputParser[end of input expected] @3 success",
        "EXIT FAST SequenceParser @3 success",
        "EXIT FAST ActionParser @3 success");
    assertEquals(expected, render(log));
    log.clear();
    assertTrue(parser.accept("1+2"));
    assertEquals("accept() reparses through the fast path",
        expected, render(log));
  }

  @Test
  public void testSlowTraceOnFailure() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = instrumentedExpression(log);
    Result result = parser.parse("1+");
    assertTrue(result.isFailure());
    assertEquals("the deep failure at position 2 is discarded by the " +
        "successful second alternative of the choice", 1, result.getPosition());
    assertEquals("end of input expected", result.getMessage());
    List<String> expected = Arrays.asList(
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW ChoiceParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW ChoiceParser @0",
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW AndParser @0",
        "ENTER SLOW CharacterParser['(' expected] @0",
        "EXIT SLOW CharacterParser['(' expected] @0 failure",
        "EXIT SLOW AndParser @0 failure",
        "EXIT SLOW SequenceParser @0 failure",
        "EXIT SLOW ActionParser @0 failure",
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW FlattenParser @0",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @0",
        "ENTER SLOW CharacterParser[digit expected] @0",
        "EXIT SLOW CharacterParser[digit expected] @1 success",
        "ENTER SLOW CharacterParser[digit expected] @1",
        "EXIT SLOW CharacterParser[digit expected] @1 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @1 success",
        "EXIT SLOW FlattenParser @1 success",
        "ACTION SLOW ActionParser",
        "EXIT SLOW ActionParser @1 success",
        "EXIT SLOW ChoiceParser @1 success",
        "ENTER SLOW SequenceParser @1",
        "ENTER SLOW CharacterParser['+' expected] @1",
        "EXIT SLOW CharacterParser['+' expected] @2 success",
        "ENTER SLOW ChoiceParser @2",
        "ENTER SLOW SequenceParser @2",
        "ENTER SLOW ChoiceParser @2",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW SequenceParser @2",
        "ENTER SLOW AndParser @2",
        "ENTER SLOW CharacterParser['(' expected] @2",
        "EXIT SLOW CharacterParser['(' expected] @2 failure",
        "EXIT SLOW AndParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "EXIT SLOW ActionParser @2 failure",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW FlattenParser @2",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @2",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @2 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @2 failure",
        "EXIT SLOW FlattenParser @2 failure",
        "EXIT SLOW ActionParser @2 failure",
        "EXIT SLOW ChoiceParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "ENTER SLOW ChoiceParser @2",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW SequenceParser @2",
        "ENTER SLOW AndParser @2",
        "ENTER SLOW CharacterParser['(' expected] @2",
        "EXIT SLOW CharacterParser['(' expected] @2 failure",
        "EXIT SLOW AndParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "EXIT SLOW ActionParser @2 failure",
        "ENTER SLOW ActionParser @2",
        "ENTER SLOW FlattenParser @2",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @2",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @2 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @2 failure",
        "EXIT SLOW FlattenParser @2 failure",
        "EXIT SLOW ActionParser @2 failure",
        "EXIT SLOW ChoiceParser @2 failure",
        "EXIT SLOW ChoiceParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "EXIT SLOW SequenceParser @2 failure",
        "ENTER SLOW ChoiceParser @0",
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW SequenceParser @0",
        "ENTER SLOW AndParser @0",
        "ENTER SLOW CharacterParser['(' expected] @0",
        "EXIT SLOW CharacterParser['(' expected] @0 failure",
        "EXIT SLOW AndParser @0 failure",
        "EXIT SLOW SequenceParser @0 failure",
        "EXIT SLOW ActionParser @0 failure",
        "ENTER SLOW ActionParser @0",
        "ENTER SLOW FlattenParser @0",
        "ENTER SLOW PossessiveRepeatingParser[1..*] @0",
        "ENTER SLOW CharacterParser[digit expected] @0",
        "EXIT SLOW CharacterParser[digit expected] @1 success",
        "ENTER SLOW CharacterParser[digit expected] @1",
        "EXIT SLOW CharacterParser[digit expected] @1 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @1 success",
        "EXIT SLOW FlattenParser @1 success",
        "ACTION SLOW ActionParser",
        "EXIT SLOW ActionParser @1 success",
        "EXIT SLOW ChoiceParser @1 success",
        "EXIT SLOW ChoiceParser @1 success",
        "ENTER SLOW EndOfInputParser[end of input expected] @1",
        "EXIT SLOW EndOfInputParser[end of input expected] @1 failure",
        "EXIT SLOW SequenceParser @1 failure",
        "EXIT SLOW ActionParser @1 failure");
    assertEquals(expected, render(log));
    assertEquals("the fast path only signals failure, no position", -1,
        instrumentedExpression(new ArrayList<>()).fastParseOn("1+", 0));
  }

  // ------------------------------------------------------------------
  // Choice: rollback and failure joining.
  // ------------------------------------------------------------------

  private static Parser failingBranches(FailureJoiner joiner) {
    Parser branch1 = of('a').seq(of('b')).seq(of('c'));
    Parser branch2 = of('a').seq(of('b')).seq(of('d'));
    Parser branch3 = of('a').seq(of('x'));
    return branch1.or(joiner, branch2, branch3);
  }

  @Test
  public void testChoiceRollsBackToTheSameContext() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(
        failingBranches(new FailureJoiner.SelectLast()), log);
    Result result = parser.parse("abx");
    assertTrue(result.isFailure());
    assertEquals(1, result.getPosition());
    assertEquals("'x' expected", result.getMessage());
    List<InstrumentedParser.Event> enters = log.stream()
        .filter(event -> event.kind == InstrumentedParser.Event.Kind.ENTER)
        .filter(event -> event.getDescription()
            .equals("CharacterParser['a' expected]"))
        .collect(Collectors.toList());
    assertEquals("each of the three branches restarts at position 0",
        Arrays.asList(0, 0, 0),
        enters.stream().map(event -> event.position).collect(Collectors.toList()));
    assertTrue("the selected failure originates from the last branch",
        log.stream().anyMatch(event -> event.kind == InstrumentedParser.Event.Kind.EXIT
            && !event.success && event.position == 1
            && event.getDescription().equals("CharacterParser['x' expected]")));
  }

  @Test
  public void testFailureJoiners() {
    Result selectLast = failingBranches(new FailureJoiner.SelectLast()).parse("abx");
    assertEquals(1, selectLast.getPosition());
    assertEquals("'x' expected", selectLast.getMessage());

    Result selectFirst = failingBranches(new FailureJoiner.SelectFirst()).parse("abx");
    assertEquals(2, selectFirst.getPosition());
    assertEquals("'c' expected", selectFirst.getMessage());

    Result selectFarthest = failingBranches(new FailureJoiner.SelectFarthest()).parse("abx");
    assertEquals(2, selectFarthest.getPosition());
    assertEquals("on a tie the later failure wins", "'d' expected",
        selectFarthest.getMessage());

    Result selectJoined = failingBranches(new FailureJoiner.SelectFarthestJoined()).parse("abx");
    assertEquals(2, selectJoined.getPosition());
    assertEquals("'c' expected OR 'd' expected", selectJoined.getMessage());
  }

  // ------------------------------------------------------------------
  // Repeat: termination and zero-width delegates.
  // ------------------------------------------------------------------

  @Test
  public void testRepeatTerminatesOnFirstFailure() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(digit().plus(), log);
    Result result = parser.parse("123");
    assertTrue(result.isSuccess());
    assertEquals(3, result.getPosition());
    assertEquals(Arrays.asList(
        "ENTER SLOW PossessiveRepeatingParser[1..*] @0",
        "ENTER SLOW CharacterParser[digit expected] @0",
        "EXIT SLOW CharacterParser[digit expected] @1 success",
        "ENTER SLOW CharacterParser[digit expected] @1",
        "EXIT SLOW CharacterParser[digit expected] @2 success",
        "ENTER SLOW CharacterParser[digit expected] @2",
        "EXIT SLOW CharacterParser[digit expected] @3 success",
        "ENTER SLOW CharacterParser[digit expected] @3",
        "EXIT SLOW CharacterParser[digit expected] @3 failure",
        "EXIT SLOW PossessiveRepeatingParser[1..*] @3 success"),
        render(log));
  }

  @Test
  public void testRepeatPropagatesFailureBelowMinimum() {
    Result result = digit().repeat(2, 3).parse("1x");
    assertTrue(result.isFailure());
    assertEquals(1, result.getPosition());
    assertEquals("digit expected", result.getMessage());
  }

  /**
   * A zero-width parser that succeeds without consuming input. It throws
   * {@link SentinelException} after {@code limit} invocations so that an
   * otherwise unbounded loop becomes observable in a test.
   */
  static class SentinelException extends RuntimeException {
  }

  private static Parser sentinelZeroWidth(int limit) {
    return new Parser() {
      int count = 0;

      @Override
      public Result parseOn(Context context) {
        if (++count > limit) {
          throw new SentinelException();
        }
        return context.success(null);
      }

      @Override
      public Parser copy() {
        return this;
      }
    };
  }

  private static void assertLoopsForever(Parser parser, String input) {
    try {
      parser.parse(input);
      fail("Expected the repeat to loop forever");
    } catch (SentinelException expected) {
      // The delegate kept succeeding without consuming input and the
      // repeating parser never terminated on its own.
    }
    try {
      parser.fastParseOn(input, 0);
      fail("Expected the repeat to loop forever");
    } catch (SentinelException expected) {
      // Same behavior on the fast path.
    }
  }

  @Test
  public void testZeroWidthRepeatLoopsForever() {
    assertLoopsForever(sentinelZeroWidth(1000).star(), "");
    assertLoopsForever(sentinelZeroWidth(1000).starGreedy(of('!')), "");
    assertLoopsForever(sentinelZeroWidth(1000).starLazy(of('!')), "");
  }

  // ------------------------------------------------------------------
  // Lookahead: succeeds or fails without consuming input.
  // ------------------------------------------------------------------

  @Test
  public void testAndParserDoesNotConsume() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(of('a').and(), log);
    Result result = parser.parse("a");
    assertTrue(result.isSuccess());
    assertEquals(0, result.getPosition());
    assertEquals(Arrays.asList(
        "ENTER SLOW AndParser @0",
        "ENTER SLOW CharacterParser['a' expected] @0",
        "EXIT SLOW CharacterParser['a' expected] @1 success",
        "EXIT SLOW AndParser @0 success"),
        render(log));
  }

  @Test
  public void testNotParserDoesNotConsume() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(of('a').not(), log);
    Result success = parser.parse("b");
    assertTrue(success.isSuccess());
    assertEquals(0, success.getPosition());
    Result failure = parser.parse("a");
    assertTrue(failure.isFailure());
    assertEquals(0, failure.getPosition());
    assertEquals("unexpected", failure.getMessage());
    assertEquals(Arrays.asList(
        "ENTER SLOW NotParser[unexpected] @0",
        "ENTER SLOW CharacterParser['a' expected] @0",
        "EXIT SLOW CharacterParser['a' expected] @0 failure",
        "EXIT SLOW NotParser[unexpected] @0 success",
        "ENTER SLOW NotParser[unexpected] @0",
        "ENTER SLOW CharacterParser['a' expected] @0",
        "EXIT SLOW CharacterParser['a' expected] @1 success",
        "EXIT SLOW NotParser[unexpected] @0 failure"),
        render(log));
  }

  // ------------------------------------------------------------------
  // Actions: side-effect contract on both execution paths.
  // ------------------------------------------------------------------

  @Test
  public void testPlainActionIsSkippedOnFastPath() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(
        digit().map(value -> value), log);
    assertTrue(parser.parse("7").isSuccess());
    assertEquals(1, log.stream()
        .filter(event -> event.kind == InstrumentedParser.Event.Kind.ACTION).count());
    log.clear();
    assertEquals(1, parser.fastParseOn("7", 0));
    assertEquals("plain map actions are not executed on the fast path",
        Arrays.asList(
            "ENTER FAST ActionParser @0",
            "ENTER FAST CharacterParser[digit expected] @0",
            "EXIT FAST CharacterParser[digit expected] @1 success",
            "EXIT FAST ActionParser @1 success"),
        render(log));
  }

  @Test
  public void testSideEffectActionFallsBackToSlowPath() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(
        digit().mapWithSideEffects(value -> value), log);
    assertEquals(1, parser.fastParseOn("7", 0));
    assertEquals("a side-effecting action forces the slow path, even when " +
        "invoked through fastParseOn",
        Arrays.asList(
            "ENTER FAST ActionParser @0",
            "ENTER SLOW CharacterParser[digit expected] @0",
            "EXIT SLOW CharacterParser[digit expected] @1 success",
            "ACTION SLOW ActionParser",
            "EXIT FAST ActionParser @1 success"),
        render(log));
  }

  // ------------------------------------------------------------------
  // Trimming: uses the fast path internally, even on the slow path.
  // ------------------------------------------------------------------

  @Test
  public void testTrimmingUsesFastPathInternally() {
    List<InstrumentedParser.Event> log = new ArrayList<>();
    Parser parser = InstrumentedParser.instrument(of('a').trim(), log);
    Result result = parser.parse(" a ");
    assertTrue(result.isSuccess());
    assertEquals(3, result.getPosition());
    assertEquals(Arrays.asList(
        "ENTER SLOW TrimmingParser @0",
        "ENTER FAST CharacterParser[whitespace expected] @0",
        "EXIT FAST CharacterParser[whitespace expected] @1 success",
        "ENTER FAST CharacterParser[whitespace expected] @1",
        "EXIT FAST CharacterParser[whitespace expected] @-1 failure",
        "ENTER SLOW CharacterParser['a' expected] @1",
        "EXIT SLOW CharacterParser['a' expected] @2 success",
        "ENTER FAST CharacterParser[whitespace expected] @2",
        "EXIT FAST CharacterParser[whitespace expected] @3 success",
        "ENTER FAST CharacterParser[whitespace expected] @3",
        "EXIT FAST CharacterParser[whitespace expected] @-1 failure",
        "EXIT SLOW TrimmingParser @3 success"),
        render(log));
  }
}

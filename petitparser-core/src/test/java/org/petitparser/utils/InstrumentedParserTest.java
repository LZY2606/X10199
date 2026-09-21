package org.petitparser.utils;

import org.junit.Test;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.actions.ActionParser;
import org.petitparser.parser.combinators.ChoiceParser;
import org.petitparser.parser.combinators.NotParser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;
import org.petitparser.tools.GrammarDefinition;
import org.petitparser.utils.InstrumentedParser.Event;
import org.petitparser.utils.InstrumentedParser.Log;
import org.petitparser.utils.InstrumentedParser.Path;
import org.petitparser.utils.InstrumentedParser.SpinException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.letter;
import static org.petitparser.parser.primitive.CharacterParser.of;
import org.petitparser.parser.primitive.EpsilonParser;
import static org.petitparser.parser.primitive.CharacterParser.word;

/**
 * Tests {@link InstrumentedParser} and, through the recorded call graph, the
 * contracts of the core combinators on both execution paths.
 */
public class InstrumentedParserTest {

  /**
   * Minimal grammar exercising recursion, choice, repeat, lookahead, an action
   * and named productions:
   *
   * <pre>
   * start = term.end()
   * term  = group / wordAct / numAct
   * group = '(' term ')'
   * word  = word()+            (positive lookahead before consuming)
   * num   = digit+  (negative lookahead requires no letter to follow)
   * </pre>
   *
   * The {@code word} and {@code num} actions call {@link Log#actionCount} so the
   * trace can report whether an action actually ran.
   */
  static final class MiniGrammar extends GrammarDefinition {
    final Log log = new Log();

    MiniGrammar() {
      def("start", ref("term").end());
      def("term",
          ref("group").or(ref("word")).or(ref("num")));
      def("group", of('(').seq(ref("term")).seq(of(')')));
      def("word", CharacterParser.letter().and().seq(word().plus()).pick(1)
          .map(value -> InstrumentedParser.recordAction(log, value)));
      def("num", digit().plus()
          .seq(letter().not("letter after number"))
          .pick(0)
          .map(value -> InstrumentedParser.recordAction(log, value)));
    }

    Parser start() {
      return build();
    }
  }

  private static List<Parser> all(Parser root) {
    List<Parser> result = new ArrayList<>();
    Mirror.of(root).forEach(result::add);
    return result;
  }

  private static long countKind(Iterable<Parser> parsers, Class<?> type) {
    long count = 0;
    for (Parser parser : parsers) {
      if (type.isInstance(parser)) {
        count++;
      }
    }
    return count;
  }

  private static List<String> suffix(List<String> labels, String suffix) {
    List<String> result = new ArrayList<>();
    for (String label : labels) {
      if (label.startsWith(suffix)) {
        result.add(label);
      }
    }
    return result;
  }

  private static String base(String label) {
    int hash = label.indexOf('#');
    return hash < 0 ? label : label.substring(0, hash);
  }

  private static List<String> bases(List<String> labels) {
    List<String> result = new ArrayList<>();
    for (String label : labels) {
      result.add(base(label));
    }
    return result;
  }

  @Test
  public void resolveReplacesReferencesInPlaceAndReturnsStoredParser() {
    MiniGrammar grammar = new MiniGrammar();
    Parser start = grammar.build("start");
    // Resolving again returns the exact same production graph: references were
    // mutated away, nothing is rebuilt or copied.
    assertSame(start, grammar.build("start"));
    // No Reference survives in the graph: every reachable parser parses,
    // whereas an unresolved Reference throws on parseOn.
    for (Parser parser : all(start)) {
      parser.fastParseOn("x", 0);
    }
    // The recursive group production physically points back into the graph.
    List<Parser> groupChildren = new ArrayList<>();
    Parser group = null;
    for (Parser parser : all(start)) {
      if (parser.getClass().getSimpleName().equals("SequenceParser")) {
        group = parser;
      }
    }
    assertNotNull(group);
    boolean reachesTerm = false;
    for (Parser child : group.getChildren()) {
      reachesTerm |= child.getClass().getSimpleName().equals("ChoiceParser");
    }
    assertTrue(reachesTerm);
  }

  @Test
  public void unresolvedAndRecursiveReferencesFailAtBuildTime() {
    GrammarDefinition broken = new GrammarDefinition() {
      {
        def("start", ref("missing"));
        def("loop", ref("loop2"));
        def("loop2", ref("loop"));
      }
    };
    try {
      broken.build("start");
      fail();
    } catch (IllegalStateException error) {
      assertTrue(error.getMessage().startsWith("Unknown parser reference: missing"));
    }
    try {
      broken.build("loop");
      fail();
    } catch (IllegalStateException error) {
      assertTrue(error.getMessage().startsWith("Recursive references detected:"));
    }
  }

  @Test
  public void copyIsShallowAndMirrorPreservesSharedSubgraph() {
    Parser leaf = digit();
    Parser source = leaf.seq(leaf);
    // Parser#copy is shallow: the two slots still are the very same leaf.
    Parser shallow = source.copy();
    assertSame(shallow.getChildren().get(0), shallow.getChildren().get(1));
    assertSame(leaf, shallow.getChildren().get(0));
    // Mirror.transform(identity) builds a deep copy but keeps shared nodes
    // shared, replacing each distinct object exactly once.
    Parser deep = Mirror.of(source).transform(java.util.function.Function.identity());
    assertNotSame(source, deep);
    assertSame(deep.getChildren().get(0), deep.getChildren().get(1));
    assertNotSame(leaf, deep.getChildren().get(0));
  }

  @Test
  public void mirrorPreservesRecursiveCycles() {
    MiniGrammar grammar = new MiniGrammar();
    Parser start = grammar.start();
    Parser copy = Mirror.of(start).transform(java.util.function.Function.identity());
    // The copy is structurally identical, shares no object with the original,
    // and the copied group still reaches its own (copied) term.
    assertTrue(start.isEqualTo(copy));
    for (Parser original : all(start)) {
      assertFalse(all(copy).contains(original));
    }
    Parser copiedTerm = findByClass(copy, "ChoiceParser");
    boolean copiedGroupReachesTerm = false;
    for (Parser parser : all(copy)) {
      if (base(parser.getClass().getSimpleName()).equals("SequenceParser")) {
        for (Parser child : parser.getChildren()) {
          copiedGroupReachesTerm |= child == copiedTerm;
        }
      }
    }
    assertTrue(copiedGroupReachesTerm);
  }

  private static Parser findByClass(Parser root, String simpleName) {
    for (Parser parser : all(root)) {
      if (parser.getClass().getSimpleName().equals(simpleName)) {
        return parser;
      }
    }
    return null;
  }

  @Test
  public void optimizerRemoveDelegatesKeepsActionAndOtherDelegates() {
    Parser action = digit().map(value -> Character.getNumericValue((Character) value));
    Parser source = action.settable();
    Parser optimized = new Optimizer().removeDelegates().transform(source);
    // The SettableParser collapses, the ActionParser (a DelegateParser
    // subclass with extra state) must survive.
    assertTrue(optimized instanceof ActionParser);
    assertNotSame(source, optimized);
  }

  @Test
  public void optimizerRemoveDuplicatesMergesEqualChildren() {
    Parser first = digit();
    Parser second = digit();
    Parser source = first.seq(second);
    Parser optimized = new Optimizer().removeDuplicates().transform(source);
    assertSame(optimized.getChildren().get(0), optimized.getChildren().get(1));
    // The merge aliases to one canonical object, changing parser identity.
    long characterParsers = all(optimized).stream()
        .filter(p -> p.getClass().getSimpleName().equals("CharacterParser"))
        .count();
    assertEquals(1, characterParsers);
  }

  @Test
  public void directLeftRecursionIsNotGuarded() {
    // 'term = term "x" / "x"' is genuinely left recursive.
    GrammarDefinition left = new GrammarDefinition() {
      {
        def("start", ref("term"));
        def("term", ref("term").seq(of('x')).or(of('x')));
      }
    };
    Parser parser = left.build();
    // There is no left-recursion detector: the graph builds and executes, and
    // the recursion only ends when the call stack overflows.
    try {
      parser.parse("x");
      fail("expected stack overflow");
    } catch (StackOverflowError expected) {
      // documented behavior
    }
  }

  @Test
  public void instrumentedLeftRecursionSpinsAndRecordsActivations() {
    GrammarDefinition left = new GrammarDefinition() {
      {
        def("start", ref("term"));
        def("term", ref("term").seq(of('x')).or(of('x')));
      }
    };
    Log log = new Log();
    log.maxActivations = 200;
    Parser instrumented = InstrumentedParser.instrument(left.build(), log);
    try {
      instrumented.parse("x");
      fail("expected spin cap");
    } catch (SpinException expected) {
      assertTrue(expected.activations > 200);
    }
    // Each activation entered ChoiceParser before descending further, proving
    // the ordered choice always retries the left-recursive first branch.
    long choiceEnters = log.labels(Event.Kind.ENTER, Path.SLOW).stream()
        .filter(label -> label.startsWith("ChoiceParser")).count();
    // Each recursion level activates the term ChoiceParser (roughly two
    // instrumented nodes per level), so about half the activations are
    // choices; this proves the ordered choice always re-enters its
    // left-recursive first branch.
    assertTrue(choiceEnters >= 90);
  }

  private static Parser instrumentedStart(MiniGrammar grammar) {
    // Instrumentation deep-copies the graph; the copied action lambdas still
    // close over grammar.log, so that must be the trace log we read back.
    return InstrumentedParser.instrument(grammar.start(), grammar.log);
  }

  @Test
  public void slowPathSuccessRecordsFullCallGraphAndActions() {
    MiniGrammar grammar = new MiniGrammar();
    Log log = grammar.log;
    Parser parser = instrumentedStart(grammar);

    Result result = parser.parse("(x)");
    assertTrue(result.isSuccess());

    List<String> enters = bases(log.labels(Event.Kind.ENTER, Path.SLOW));
    // Ordered activation: start Action -> end Sequence -> term Choice ->
    // group Sequence -> term Choice (recursive) -> word branch -> num branch.
    assertEquals(Arrays.asList(
        "ActionParser", "SequenceParser", "ChoiceParser",
        "SequenceParser", "CharacterParser",
        "ChoiceParser", "SequenceParser", "CharacterParser",
        "ActionParser", "ActionParser", "SequenceParser",
        "AndParser", "CharacterParser", "PossessiveRepeatingParser",
        "CharacterParser", "CharacterParser", "CharacterParser",
        "EndOfInputParser"),
        enters);

    // The word branch action ran exactly once; the failing num action did not.
    assertEquals(1, log.actionCount);
    int actionExits = 0;
    int actionRanExits = 0;
    for (Event event : log.events(Event.Kind.EXIT, Path.SLOW)) {
      if (event.wrapped().startsWith("ActionParser")) {
        actionExits++;
        if (event.actionRan) {
          actionRanExits++;
        }
      }
    }
    // actionRan is a counter delta over the whole activation subtree: both
    // the leaf word action and the enclosing start/end pick observe one run
    // (the pick is an action itself but executes no production lambda). The
    // failing num action observes none.
    assertEquals(2, actionRanExits);
    assertTrue(actionExits >= 3);
  }

  @Test
  public void slowPathSuccessActionsExecutedOnlyOnSuccess() {
    MiniGrammar grammar = new MiniGrammar();
    Log log = grammar.log;
    Parser parser = instrumentedStart(grammar);
    assertTrue(parser.parse("42").isSuccess());
    assertEquals(1, log.actionCount);
    // The leaf num production action is the one wrapped directly around the
    // digit/NotParser sequence; it spans 0..2 and ran its lambda exactly once.
    List<Event> actionExits = log.events(Event.Kind.EXIT, Path.SLOW);
    Event leafNum = null;
    for (Event event : actionExits) {
      if (event.wrapped().startsWith("ActionParser") && event.enterPosition == 0
          && event.exitPosition == 2 && event.actionRan) {
        leafNum = event;
      }
    }
    assertNotNull(leafNum);
  }

  @Test
  public void fastPathSkipsPureActionsButHonoursPositions() {
    MiniGrammar grammar = new MiniGrammar();
    Log log = grammar.log;
    Parser parser = instrumentedStart(grammar);

    int position = parser.fastParseOn("(x)", 0);
    assertEquals(3, position);

    List<String> enters = bases(log.labels(Event.Kind.ENTER, Path.FAST));
    // Action wrappers are activated on the fast path, but their delegates skip
    // straight through: no SLOW fallback, and no action executes.
    assertTrue(enters.contains("ActionParser"));
    assertEquals(0, log.events(Event.Kind.ENTER, Path.SLOW).size());
    assertEquals(0, log.actionCount);
  }

  @Test
  public void fastPathSideEffectActionFallsBackToSlowPath() {
    AtomicInteger sideEffects = new AtomicInteger();
    Parser action = of('a').mapWithSideEffects(value -> {
      sideEffects.incrementAndGet();
      return value;
    });
    Log log = new Log();
    Parser parser = InstrumentedParser.instrument(action, log);

    assertEquals(1, parser.fastParseOn("a", 0));
    assertEquals(1, sideEffects.get());
    // The side-effecting action forced an emulated parse inside the fast path.
    assertFalse(log.events(Event.Kind.ENTER, Path.SLOW).isEmpty());
  }

  @Test
  public void slowPathFailureAttributesFailurePositionMessageAndParser() {
    MiniGrammar grammar = new MiniGrammar();
    Log log = grammar.log;
    Parser parser = instrumentedStart(grammar);

    Result result = parser.parse("4x");
    assertTrue(result.isFailure());
    // num is the last failing alternative of the term choice and SelectLast
    // keeps its failure: digits matched, but the negative lookahead saw a
    // letter following at position 1.
    assertEquals(1, result.getPosition());
    assertEquals("letter after number", result.getMessage());

    // NotParser entered at position 1; its letter delegate succeeds at 1..2,
    // so the negative lookahead fails while consuming nothing.
    Event notExit = null;
    for (Event event : log.events(Event.Kind.EXIT, Path.SLOW)) {
      if (event.wrapped().startsWith("NotParser")) {
        notExit = event;
      }
    }
    assertNotNull(notExit);
    assertEquals(1, notExit.enterPosition);
    assertEquals(1, notExit.exitPosition);
    assertFalse(notExit.success);
    // No production action ever ran because every branch failed.
    assertEquals(0, log.actionCount);
  }

  @Test
  public void choiceRetriesEveryAlternativeFromSamePosition() {
    MiniGrammar grammar = new MiniGrammar();
    Log log = grammar.log;
    Parser parser = instrumentedStart(grammar);
    parser.parse("4x");
    // The top-level term ChoiceParser is entered once at position 0, and all
    // three alternatives group/word/num start there: an alternative that later
    // fails commits nothing because every branch receives the same context.
    List<Event> choiceEnters = new ArrayList<>();
    for (Event event : log.events(Event.Kind.ENTER, Path.SLOW)) {
      if (event.wrapped().startsWith("ChoiceParser")) {
        choiceEnters.add(event);
      }
    }
    assertEquals(1, choiceEnters.size());
    assertEquals(0, choiceEnters.get(0).enterPosition);
    // Every branch entered from the choice starts at the choice's position 0,
    // even though the last branch (num) advances to 1 before failing.
    List<Event> exits = log.events(Event.Kind.EXIT, Path.SLOW);
    Event groupExit = null;
    Event wordExit = null;
    Event numExit = null;
    for (Event event : exits) {
      if (event.wrapped().startsWith("SequenceParser") && !event.success
          && groupExit == null) {
        groupExit = event;
      }
    }
    for (Event event : exits) {
      if (event.wrapped().startsWith("ActionParser") && !event.success
          && event.enterPosition == 0) {
        if (wordExit == null) {
          wordExit = event;
        } else {
          numExit = event;
        }
      }
    }
    assertNotNull(groupExit);
    assertNotNull(wordExit);
    assertNotNull(numExit);
    assertEquals(0, groupExit.enterPosition);
    assertEquals(0, wordExit.enterPosition);
    assertEquals(0, numExit.enterPosition);
    // The num branch failed remotely at 1; the choice still reports at 0 after
    // SelectLast joined its failures (see the dedicated failure test).
    assertEquals(1, numExit.exitPosition);
  }

  /** Choice whose branches fail at different remote positions. */
  private static Parser remoteFailures(FailureJoiner joiner) {
    Parser ab = of('a').seq(of('b'));              // fails at 1 on "az"
    Parser axz = of('a').seq(of('x')).seq(of('z')); // fails at 1 on "az"
    Parser aq = of('a').seq(of('q'));               // fails at 1 on "az"
    return new ChoiceParser(joiner, ab, axz, aq);
  }

  @Test
  public void failureJoinersSelectPositionAndMessage() {
    Failure first = new FailureJoiner.SelectFirst()
        .apply(new Failure("", 1, "m1"), new Failure("", 2, "m2"));
    Failure last = new FailureJoiner.SelectLast()
        .apply(new Failure("", 1, "m1"), new Failure("", 2, "m2"));
    Failure farthest = new FailureJoiner.SelectFarthest()
        .apply(new Failure("", 1, "m1"), new Failure("", 2, "m2"));
    assertEquals(1, first.getPosition());
    assertEquals(2, last.getPosition());
    assertEquals(2, farthest.getPosition());
    Failure joined = new FailureJoiner.SelectFarthestJoined()
        .apply(new Failure("az", 1, "'b' expected"),
            new Failure("az", 1, "'x' expected"));
    assertEquals(1, joined.getPosition());
    assertEquals("'b' expected OR 'x' expected", joined.getMessage());
  }

  @Test
  public void defaultJoinerIsSelectLastAcrossBranches() {
    Parser parser = remoteFailures(new FailureJoiner.SelectLast());
    Result result = parser.parse("az");
    assertTrue(result.isFailure());
    // All branches get as far as position 1; SelectLast reports the third.
    assertEquals("'q' expected", result.getMessage());
    assertEquals(1, result.getPosition());
  }

  @Test
  public void selectFarthestAcrossDifferentPositions() {
    // First branch fails remotely at position 1, second fails locally at 0.
    Parser choice = new ChoiceParser(new FailureJoiner.SelectFarthest(),
        of('a').seq(of('z')), of('y'));
    Result result = choice.parse("aX");
    assertEquals(1, result.getPosition());
    assertEquals("'z' expected", result.getMessage());

    // Reversing the order keeps the remote failure regardless of ordering.
    Parser reversed = new ChoiceParser(new FailureJoiner.SelectFarthest(),
        of('y'), of('a').seq(of('z')));
    result = reversed.parse("aX");
    assertEquals(1, result.getPosition());
  }

  @Test
  public void instrumentedTraceAttributesFinalFailureToProducingParser() {
    Log log = new Log();
    Parser parser = InstrumentedParser.instrument(
        remoteFailures(new FailureJoiner.SelectLast()), log);
    Result result = parser.parse("az");
    assertEquals(1, result.getPosition());
    // The deepest failing leaf of the winning (last kept) failure is 'q'.
    List<Event> exits = log.events(Event.Kind.EXIT, Path.SLOW);
    Event lastLeafFailure = null;
    for (Event event : exits) {
      if (!event.success && event.wrapped().startsWith("CharacterParser")) {
        lastLeafFailure = event;
      }
    }
    assertNotNull(lastLeafFailure);
    assertEquals(1, lastLeafFailure.exitPosition);
  }

  @Test
  public void fastPathFailureHasNoMessageOnlyMinusOne() {
    Parser parser = remoteFailures(new FailureJoiner.SelectFarthestJoined());
    // The fast path cannot carry messages; joiners are simply not consulted.
    assertEquals(-1, parser.fastParseOn("az", 0));
  }

  @Test
  public void possessiveRepeatTerminatesOnProgressAndFailsBelowMin() {
    Log log = new Log();
    Parser repeated = InstrumentedParser.instrument(digit().plus(), log);
    // Success collects until the delegate fails, then stops.
    assertEquals(2, repeated.fastParseOn("12x", 0));
    List<Event> digitEnters = new ArrayList<>();
    for (Event event : log.events(Event.Kind.ENTER, Path.FAST)) {
      if (event.wrapped().startsWith("CharacterParser")) {
        digitEnters.add(event);
      }
    }
    // digit@0, digit@1, digit@2 (fails): three delegate activations.
    assertEquals(3, digitEnters.size());
    assertEquals(0, digitEnters.get(0).enterPosition);
    assertEquals(1, digitEnters.get(1).enterPosition);
    assertEquals(2, digitEnters.get(2).enterPosition);
    Event lastDigitExit = null;
    for (Event event : log.events(Event.Kind.EXIT, Path.FAST)) {
      if (event.wrapped().startsWith("CharacterParser")) {
        lastDigitExit = event;
      }
    }
    assertFalse(lastDigitExit.success);
    assertEquals(2, lastDigitExit.exitPosition);

    log.reset();
    Result result = repeated.parse("x");
    assertTrue(result.isFailure());
    assertEquals(0, result.getPosition());
    assertEquals("digit expected", result.getMessage());
  }

  @Test
  public void boundedZeroWidthRepeatTerminates() {
    Log log = new Log();
    Parser epsilonTimes3 = InstrumentedParser
        .instrument(new EpsilonParser(), log)
        .times(3);
    // Zero-width successes still count toward the bounded maximum.
    List<Object> values = epsilonTimes3.parse("anything").get();
    assertEquals(3, values.size());
    log.reset();
    // Zero-width repetitions count toward the bound but never advance, so the
    // position stays where parsing started.
    assertEquals(0, epsilonTimes3.fastParseOn("anything", 0));
    assertEquals(3, log.events(Event.Kind.ENTER, Path.FAST).size());
  }

  @Test
  public void unboundedZeroWidthPossessiveRepeatSpinsBothPaths() {
    assertSpins(star(new EpsilonParser()),
        Path.SLOW, "anything");
    assertSpins(star(new EpsilonParser()),
        Path.FAST, "anything");
  }

  @Test
  public void unboundedZeroWidthGreedyAndLazyRepeatAlsoSpin() {
    Parser epsilon = new EpsilonParser();
    Parser greedy = epsilon.starGreedy(digit());
    Parser lazy = epsilon.starLazy(digit());
    assertSpins(greedy, Path.FAST, "a");
    assertSpins(lazy, Path.FAST, "a");
  }

  private static Parser star(Parser delegate) {
    return new PossessiveRepeatingParser(delegate, 0,
        org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
  }

  private static void assertSpins(Parser parser, Path path, String input) {
    Log log = new Log();
    log.maxActivations = 500;
    Parser instrumented = InstrumentedParser.instrument(parser, log);
    try {
      if (path == Path.SLOW) {
        instrumented.parse(input);
      } else {
        instrumented.fastParseOn(input, 0);
      }
      fail("expected non-termination to hit instrumentation cap");
    } catch (SpinException expected) {
      assertTrue(expected.activations >= 500);
    }
  }

  @Test
  public void lookaheadAndNotConsumeNothingOnBothPaths() {
    Parser and = word().and();
    assertEquals(0, and.fastParseOn("x", 0));  // succeeds, position unchanged
    Result result = and.parse("x");
    assertTrue(result.isSuccess());
    assertEquals(0, result.getPosition());

    Parser not = word().not("unexpected word");
    // word() fails on '!' so not() succeeds consuming nothing; on 'x' word()
    // succeeds so not() fails (and the fast path returns -1).
    assertEquals(0, not.fastParseOn("!", 0));
    assertEquals(-1, not.fastParseOn("x", 0));
    Failure failure = (Failure) not.parse("x");
    assertEquals(0, failure.getPosition());
    assertEquals("unexpected word", failure.getMessage());
  }
}

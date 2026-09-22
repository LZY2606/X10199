package org.petitparser.parser.actions;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.actions.InstrumentedParser.Event;
import org.petitparser.parser.actions.InstrumentedParser.Path;
import org.petitparser.parser.primitive.EpsilonParser;
import org.petitparser.utils.FailureJoiner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Tests {@link InstrumentedParser} and uses it to pin down the execution
 * contracts documented in {@code ANALYSIS.md}: choice backtracking, repeat
 * termination, lookahead, action side-effects, failure joining, and
 * zero-width repetition.
 */
public class InstrumentedParserTest {

  private static List<String> strings(List<Event> events) {
    return events.stream().map(Event::toString).collect(Collectors.toList());
  }

  @Test
  public void testChoiceRollsBackOnSlowPath() {
    List<Event> events = new ArrayList<>();
    Parser choice = InstrumentedParser
        .of("alt1", of('a').seq(of('b')), events)
        .or(InstrumentedParser.of("alt2", of('a').seq(of('c')), events));
    Result result = choice.parse("ac");
    assertTrue(result.isSuccess());
    assertEquals(2, result.getPosition());
    // The first alternative consumes 'a' and fails at position 1; the second
    // alternative is re-entered at the original position 0.
    assertEquals(Arrays.asList(
        "SLOW ENTER alt1 @0",
        "SLOW EXIT alt1 @1 failure",
        "SLOW ENTER alt2 @0",
        "SLOW EXIT alt2 @2 success"), strings(events));
  }

  @Test
  public void testChoiceRollsBackOnFastPath() {
    List<Event> events = new ArrayList<>();
    Parser choice = InstrumentedParser
        .of("alt1", of('a').seq(of('b')), events)
        .or(InstrumentedParser.of("alt2", of('a').seq(of('c')), events));
    assertEquals(2, choice.fastParseOn("ac", 0));
    // Same backtracking, but a failure carries no position, only -1.
    assertEquals(Arrays.asList(
        "FAST ENTER alt1 @0",
        "FAST EXIT alt1 @-1 failure",
        "FAST ENTER alt2 @0",
        "FAST EXIT alt2 @2 success"), strings(events));
  }

  @Test
  public void testRepeatTerminatesOnFirstFailure() {
    List<Event> events = new ArrayList<>();
    Parser star = InstrumentedParser.of("digit", digit(), events).star();
    Result result = star.parse("12a");
    assertTrue(result.isSuccess());
    assertEquals(2, result.getPosition());
    assertEquals(Arrays.asList('1', '2'), result.get());
    // The delegate is entered once more after the last success; its failure
    // terminates the loop and the repeater succeeds with what it has.
    assertEquals(Arrays.asList(
        "SLOW ENTER digit @0",
        "SLOW EXIT digit @1 success",
        "SLOW ENTER digit @1",
        "SLOW EXIT digit @2 success",
        "SLOW ENTER digit @2",
        "SLOW EXIT digit @2 failure"), strings(events));
  }

  @Test
  public void testRepeatTerminatesOnFastPath() {
    List<Event> events = new ArrayList<>();
    Parser star = InstrumentedParser.of("digit", digit(), events).star();
    assertEquals(2, star.fastParseOn("12a", 0));
    assertEquals(Arrays.asList(
        "FAST ENTER digit @0",
        "FAST EXIT digit @1 success",
        "FAST ENTER digit @1",
        "FAST EXIT digit @2 success",
        "FAST ENTER digit @2",
        "FAST EXIT digit @-1 failure"), strings(events));
  }

  @Test
  public void testAndPredicateDoesNotConsume() {
    List<Event> events = new ArrayList<>();
    Parser parser = InstrumentedParser.of("and", of('a').and(), events)
        .seq(of('a')).pick(1);
    Result result = parser.parse("a");
    assertTrue(result.isSuccess());
    assertEquals(Character.valueOf('a'), result.get());
    // The and-predicate succeeds at position 0 and returns position 0.
    assertEquals(Arrays.asList(
        "SLOW ENTER and @0",
        "SLOW EXIT and @0 success"), strings(events));
  }

  @Test
  public void testNotPredicateDoesNotConsume() {
    List<Event> events = new ArrayList<>();
    Parser parser = InstrumentedParser.of("not", of('b').not(), events)
        .seq(of('a')).pick(1);
    Result result = parser.parse("a");
    assertTrue(result.isSuccess());
    assertEquals(Character.valueOf('a'), result.get());
    assertEquals(Arrays.asList(
        "SLOW ENTER not @0",
        "SLOW EXIT not @0 success"), strings(events));
  }

  @Test
  public void testActionRunsOnSlowPathOnly() {
    List<Event> events = new ArrayList<>();
    List<String> calls = new ArrayList<>();
    Parser parser = InstrumentedParser.of("map", of('a').map(character -> {
      calls.add("ran");
      return character;
    }), events);
    assertTrue(parser.parse("a").isSuccess());
    assertEquals(Arrays.asList("ran"), calls);
    assertEquals(Arrays.asList(
        "SLOW ENTER map @0",
        "SLOW EXIT map @1 success action"), strings(events));

    calls.clear();
    events.clear();
    assertEquals(1, parser.fastParseOn("a", 0));
    // A side-effect free action is skipped on the fast path.
    assertEquals(Arrays.asList(), calls);
    assertEquals(Arrays.asList(
        "FAST ENTER map @0",
        "FAST EXIT map @1 success"), strings(events));
  }

  @Test
  public void testActionWithSideEffectsRunsOnBothPaths() {
    List<Event> events = new ArrayList<>();
    List<String> calls = new ArrayList<>();
    Parser parser = InstrumentedParser.of("map",
        of('a').mapWithSideEffects(character -> {
          calls.add("ran");
          return character;
        }), events);
    assertTrue(parser.parse("a").isSuccess());
    assertEquals(1, parser.fastParseOn("a", 0));
    assertEquals(Arrays.asList("ran", "ran"), calls);
    assertEquals(Arrays.asList(
        "SLOW ENTER map @0",
        "SLOW EXIT map @1 success action",
        "FAST ENTER map @0",
        "FAST EXIT map @1 success action"), strings(events));
  }

  // A branch that consumes two characters and then fails at the
  // instrumented leaf parser.
  private Parser branch(char prefix1, char prefix2, String label, char leaf,
      List<Event> events) {
    return of(prefix1).seq(of(prefix2))
        .seq(InstrumentedParser.of(label, of(leaf), events));
  }

  // A branch that consumes one character and then fails at the instrumented
  // leaf parser.
  private Parser branch(char prefix, String label, char leaf,
      List<Event> events) {
    return of(prefix).seq(InstrumentedParser.of(label, of(leaf), events));
  }

  @Test
  public void testFailureJoinerSelectLastIsDefault() {
    List<Event> events = new ArrayList<>();
    Parser choice = branch('a', 'b', "x", 'x', events)
        .or(branch('a', 'b', "y", 'y', events),
            branch('a', "q", 'q', events));
    Result result = choice.parse("abz");
    assertTrue(result.isFailure());
    // The default joiner reports the failure of the last alternative, even
    // though other alternatives failed farther down the input.
    assertEquals(1, result.getPosition());
    assertEquals("'q' expected", result.getMessage());
  }

  @Test
  public void testFailureJoinerSelectFarthest() {
    List<Event> events = new ArrayList<>();
    Parser choice = branch('a', 'b', "x", 'x', events)
        .or(new FailureJoiner.SelectFarthest(),
            branch('a', 'b', "y", 'y', events),
            branch('a', "q", 'q', events));
    Result result = choice.parse("abz");
    assertTrue(result.isFailure());
    // All three branches fail; the farthest failure wins, ties are resolved
    // in favor of the later alternative.
    assertEquals(2, result.getPosition());
    assertEquals("'y' expected", result.getMessage());
    // The recorded events identify the parsers that produced the failures:
    // 'x' at 2, 'y' at 2, and 'q' at 1.
    assertEquals(Arrays.asList(
        "SLOW ENTER x @2",
        "SLOW EXIT x @2 failure",
        "SLOW ENTER y @2",
        "SLOW EXIT y @2 failure",
        "SLOW ENTER q @1",
        "SLOW EXIT q @1 failure"), strings(events));
  }

  @Test
  public void testFailureJoinerSelectFarthestJoined() {
    List<Event> events = new ArrayList<>();
    Parser choice = branch('a', 'b', "x", 'x', events)
        .or(new FailureJoiner.SelectFarthestJoined(),
            branch('a', 'b', "y", 'y', events),
            branch('a', "q", 'q', events));
    Result result = choice.parse("abz");
    assertTrue(result.isFailure());
    // Failures at the same farthest position get their messages joined.
    assertEquals(2, result.getPosition());
    assertEquals("'x' expected OR 'y' expected", result.getMessage());
  }

  @Test
  public void testFailureJoinerSelectFirst() {
    List<Event> events = new ArrayList<>();
    Parser choice = branch('a', 'b', "x", 'x', events)
        .or(new FailureJoiner.SelectFirst(),
            branch('a', 'b', "y", 'y', events),
            branch('a', "q", 'q', events));
    Result result = choice.parse("abz");
    assertTrue(result.isFailure());
    assertEquals(2, result.getPosition());
    assertEquals("'x' expected", result.getMessage());
  }

  @Test
  public void testZeroWidthRepeatBoundedTerminates() {
    Result result = new EpsilonParser().repeat(0, 3).parse("abc");
    assertTrue(result.isSuccess());
    assertEquals(0, result.getPosition());
    assertEquals(Arrays.asList(null, null, null), result.get());
  }

  @Test
  public void testZeroWidthRepeatUnboundedOnlyStopsWhenDelegateFails() {
    // A zero-width parser that succeeds 999 times and then fails.
    AtomicInteger count = new AtomicInteger();
    Parser zeroWidth = new Parser() {
      @Override
      public Result parseOn(Context context) {
        return count.incrementAndGet() < 1000 ?
            context.success(null) : context.failure("stop");
      }

      @Override
      public Parser copy() {
        return this;
      }
    };
    Result result = zeroWidth.star().parse("");
    // The repeater has no zero-width guard: it keeps going until the
    // delegate fails, then succeeds with everything collected so far.
    assertTrue(result.isSuccess());
    assertEquals(0, result.getPosition());
    assertEquals(999, ((List<?>) result.get()).size());
    assertEquals(1000, count.get());
  }

  @Test
  public void testZeroWidthRepeatUnboundedLoopsForever()
      throws InterruptedException {
    // A zero-width parser that succeeds until told to stop.
    AtomicBoolean proceed = new AtomicBoolean(true);
    AtomicInteger count = new AtomicInteger();
    Parser zeroWidth = new Parser() {
      @Override
      public Result parseOn(Context context) {
        return proceed.get() ?
            context.success(null) : context.failure("stop");
      }

      @Override
      public int fastParseOn(String buffer, int position) {
        count.incrementAndGet();
        return proceed.get() ? position : -1;
      }

      @Override
      public Parser copy() {
        return this;
      }
    };
    Parser star = zeroWidth.star();
    AtomicInteger accepted = new AtomicInteger(-2);
    // Use the fast path: it allocates nothing, so the loop cannot run out of
    // memory while we observe it.
    Thread thread = new Thread(
        () -> accepted.set(star.fastParseOn("", 0)));
    thread.setDaemon(true);
    thread.start();
    Thread.sleep(50);
    int sample1 = count.get();
    Thread.sleep(50);
    int sample2 = count.get();
    // Neither terminates nor reports an error: the loop is still spinning.
    assertTrue(thread.isAlive());
    assertTrue(sample2 > sample1);
    // Once the delegate fails, the repeater terminates and succeeds.
    proceed.set(false);
    thread.join(5000);
    assertFalse(thread.isAlive());
    assertEquals(0, accepted.get());
  }

  @Test
  public void testFastParseOnDefaultFallsBackToSlowPath() {
    // DelegateParser does not override fastParseOn, so the default
    // implementation in Parser emulates it through parseOn.
    List<Event> events = new ArrayList<>();
    Parser parser = new org.petitparser.parser.combinators.DelegateParser(
        InstrumentedParser.of("inner", of('a'), events)) {
    };
    assertEquals(1, parser.fastParseOn("a", 0));
    assertEquals(Arrays.asList(
        "SLOW ENTER inner @0",
        "SLOW EXIT inner @1 success"), strings(events));
  }
}

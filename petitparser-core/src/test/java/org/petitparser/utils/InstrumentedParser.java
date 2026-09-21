package org.petitparser.utils;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Test-only parser that records every activation of both execution paths.
 *
 * <p>Each {@link #parseOn(Context)} emits a pair of
 * {@link Kind#SLOW_ENTER}/{@link Kind#SLOW_EXIT} events, each
 * {@link #fastParseOn(String, int)} a pair of
 * {@link Kind#FAST_ENTER}/{@link Kind#FAST_EXIT}. Every event captures the
 * parser identity, the input position and the outcome. Action executions of
 * wrappers built with {@code mapWithSideEffects} can be recorded with
 * {@link #recordAction()} into the same log.
 *
 * <p>The parser is not part of the production library: it exists only to verify
 * the call graphs described in {@code ANALYSIS.md}. It does not change any
 * combinator; {@link InstrumentedLoopException} is thrown solely by this test
 * double after {@link #maxActivations} activations so that a non-terminating
 * repetition can be observed without hanging the test run.
 */
public class InstrumentedParser extends Parser {

  /** Recorded activation. */
  public static final class Event {

    public enum Kind {
      SLOW_ENTER, SLOW_EXIT, FAST_ENTER, FAST_EXIT, ACTION
    }

    public final Kind kind;
    public final Parser parser;
    public final int position;
    public final boolean success;

    private Event(Kind kind, Parser parser, int position, boolean success) {
      this.kind = kind;
      this.parser = parser;
      this.position = position;
      this.success = success;
    }

    static Event enter(Kind kind, Parser parser, int position) {
      return new Event(kind, parser, position, true);
    }

    static Event exit(Kind kind, Parser parser, int position, boolean success) {
      return new Event(kind, parser, position, success);
    }

    /** Records that a production action function was invoked. */
    public static Event action() {
      return new Event(Kind.ACTION, null, -1, true);
    }

    @Override
    public String toString() {
      return kind + "@" + position + (success ? "+" : "-");
    }
  }

  /** Thrown by the test double when an activation cap is exceeded. */
  public static final class InstrumentedLoopException extends RuntimeException {

    public final int activations;
    public final int position;

    InstrumentedLoopException(int activations, int position) {
      super("InstrumentedParser did not terminate after " + activations
          + " activations at position " + position);
      this.activations = activations;
      this.position = position;
    }
  }

  public final String name;
  public final List<Event> events = new ArrayList<>();

  private final BiPredicate<String, Integer> matcher;
  private final String message;
  private final int consume;
  private int maxActivations = Integer.MAX_VALUE;
  private int slowActivations;
  private int fastActivations;

  public InstrumentedParser(String name, BiPredicate<String, Integer> matcher,
      String message) {
    this(name, matcher, message, 1);
  }

  public InstrumentedParser(String name, BiPredicate<String, Integer> matcher,
      String message, int consume) {
    this.name = name;
    this.matcher = matcher;
    this.message = message;
    this.consume = consume;
  }

  /** Caps the activations of a single parse run, used to expose endless loops. */
  public InstrumentedParser maxActivations(int maxActivations) {
    this.maxActivations = maxActivations;
    return this;
  }

  public void recordAction() {
    events.add(Event.action());
  }

  public List<Event> events() {
    return List.copyOf(events);
  }

  public void reset() {
    events.clear();
    slowActivations = 0;
    fastActivations = 0;
  }

  @Override
  public Result parseOn(Context context) {
    int position = context.getPosition();
    events.add(Event.enter(Event.Kind.SLOW_ENTER, this, position));
    if (++slowActivations > maxActivations) {
      throw new InstrumentedLoopException(slowActivations, position);
    }
    if (matcher.test(context.getBuffer(), position)) {
      Result result = context.success(
          context.getBuffer().substring(position, position + consume),
          position + consume);
      events.add(
          Event.exit(Event.Kind.SLOW_EXIT, this, position + consume, true));
      return result;
    }
    Result result = context.failure(message);
    events.add(Event.exit(Event.Kind.SLOW_EXIT, this, position, false));
    return result;
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    events.add(Event.enter(Event.Kind.FAST_ENTER, this, position));
    if (++fastActivations > maxActivations) {
      throw new InstrumentedLoopException(fastActivations, position);
    }
    if (matcher.test(buffer, position)) {
      events.add(Event.exit(Event.Kind.FAST_EXIT, this,
          position + consume, true));
      return position + consume;
    }
    events.add(Event.exit(Event.Kind.FAST_EXIT, this, position, false));
    return -1;
  }

  @Override
  protected boolean hasEqualProperties(Parser other) {
    InstrumentedParser that = (InstrumentedParser) other;
    return consume == that.consume
        && java.util.Objects.equals(name, that.name)
        && java.util.Objects.equals(message, that.message);
  }

  @Override
  public InstrumentedParser copy() {
    InstrumentedParser copy =
        new InstrumentedParser(name, matcher, message, consume);
    copy.maxActivations = maxActivations;
    return copy;
  }

  @Override
  public String toString() {
    return "InstrumentedParser[" + name + "]";
  }
}

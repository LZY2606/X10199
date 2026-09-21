package org.petitparser.utils;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A test-only parser that records its activations into a shared {@link Event}
 * log: when it is entered, when it exits (and with what outcome), and whether
 * an optional action was executed.
 *
 * <p>The parser deliberately does <em>not</em> override {@code fastParseOn}.
 * It therefore inherits the default implementation of {@link
 * Parser#fastParseOn(String, int)}, which falls back to {@link
 * Parser#parseOn(Context)}. This makes the instrumentation observable on both
 * execution paths, at the cost of forcing the slow path below this node.
 */
public class InstrumentedParser extends DelegateParser {

  /**
   * The kind of a recorded event.
   */
  public enum Kind {
    ENTER, EXIT, ACTION
  }

  /**
   * A single recorded event.
   */
  public static class Event {

    public final Kind kind;
    public final String name;
    public final int position;
    public final boolean success;

    private Event(Kind kind, String name, int position, boolean success) {
      this.kind = kind;
      this.name = name;
      this.position = position;
      this.success = success;
    }

    public static Event enter(String name, int position) {
      return new Event(Kind.ENTER, name, position, true);
    }

    public static Event exit(String name, int position, boolean success) {
      return new Event(Kind.EXIT, name, position, success);
    }

    public static Event action(String name, int position) {
      return new Event(Kind.ACTION, name, position, true);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      Event event = (Event) other;
      return kind == event.kind && position == event.position &&
          success == event.success && Objects.equals(name, event.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, name, position, success);
    }

    @Override
    public String toString() {
      return kind + "(" + name + "@" + position +
          (kind == Kind.EXIT ? ", " + (success ? "success" : "failure") : "") +
          ")";
    }
  }

  private final String name;
  private final List<Event> log;
  private final Function<Object, Object> action;

  /**
   * Wraps {@code delegate} so that entering and exiting it is recorded under
   * {@code name} into {@code log}.
   */
  public static InstrumentedParser of(
      String name, Parser delegate, List<Event> log) {
    return new InstrumentedParser(name, delegate, log, null);
  }

  /**
   * Wraps {@code delegate} like {@link #of}, and additionally applies {@code
   * action} to successful results, recording its execution as an {@link
   * Kind#ACTION} event.
   */
  public static InstrumentedParser action(String name,
      Function<Object, Object> action, Parser delegate, List<Event> log) {
    return new InstrumentedParser(name, delegate, log, action);
  }

  private InstrumentedParser(String name, Parser delegate, List<Event> log,
      Function<Object, Object> action) {
    super(delegate);
    this.name = Objects.requireNonNull(name);
    this.log = Objects.requireNonNull(log);
    this.action = action;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Result parseOn(Context context) {
    log.add(Event.enter(name, context.getPosition()));
    Result result = delegate.parseOn(context);
    if (result.isSuccess() && action != null) {
      result = result.success(action.apply(result.get()));
      log.add(Event.action(name, result.getPosition()));
    }
    log.add(Event.exit(name, result.getPosition(), result.isSuccess()));
    return result;
  }

  @Override
  public InstrumentedParser copy() {
    return new InstrumentedParser(name, delegate, log, action);
  }

  @Override
  public String toString() {
    return super.toString() + "[" + name + "]";
  }
}

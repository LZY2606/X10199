package org.petitparser.parser.actions;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.List;
import java.util.Objects;

/**
 * A test-only parser that wraps a delegate and records every activation on
 * both execution paths ({@link Parser#parseOn(Context)} and {@link
 * Parser#fastParseOn(String, int)}).
 *
 * <p>For each activation two {@link Event}s are appended to the shared event
 * list: one when the parser is entered (with the input position), and one
 * when it returns (with the result position, the success flag, and whether an
 * {@link ActionParser} delegate actually executed its function).
 *
 * <p>This class lives in this package so it can read the protected {@code
 * hasSideEffects} flag of {@link ActionParser}; it does not change any
 * production code.
 */
public class InstrumentedParser extends DelegateParser {

  /** The execution path an event was recorded on. */
  public enum Path {
    SLOW, FAST
  }

  /** A single recorded activation or return of an instrumented parser. */
  public static class Event {

    /** The label of the instrumented parser. */
    public final String name;

    /** The execution path this event was recorded on. */
    public final Path path;

    /** {@code true} when the parser is entered, {@code false} on return. */
    public final boolean enter;

    /**
     * The input position on enter; on exit the position of the returned
     * result. On the fast path a failure is reported as {@code -1}, the raw
     * return value of {@link Parser#fastParseOn(String, int)}.
     */
    public final int position;

    /** On exit events: {@code true} if the activation succeeded. */
    public final boolean success;

    /** On exit events: {@code true} if an {@link ActionParser} delegate
     * actually executed its function on this activation. */
    public final boolean action;

    private Event(String name, Path path, boolean enter, int position,
        boolean success, boolean action) {
      this.name = name;
      this.path = path;
      this.enter = enter;
      this.position = position;
      this.success = success;
      this.action = action;
    }

    static Event enter(String name, Path path, int position) {
      return new Event(name, path, true, position, false, false);
    }

    static Event exit(String name, Path path, int position, boolean success,
        boolean action) {
      return new Event(name, path, false, position, success, action);
    }

    @Override
    public String toString() {
      if (enter) {
        return path + " ENTER " + name + " @" + position;
      } else {
        return path + " EXIT " + name + " @" + position +
            (success ? " success" : " failure") + (action ? " action" : "");
      }
    }
  }

  private final String name;
  private final List<Event> events;

  public InstrumentedParser(String name, Parser delegate, List<Event> events) {
    super(delegate);
    this.name = Objects.requireNonNull(name, "Undefined name");
    this.events = Objects.requireNonNull(events, "Undefined event list");
  }

  public static InstrumentedParser of(
      String name, Parser delegate, List<Event> events) {
    return new InstrumentedParser(name, delegate, events);
  }

  @Override
  public Result parseOn(Context context) {
    events.add(Event.enter(name, Path.SLOW, context.getPosition()));
    Result result = delegate.parseOn(context);
    events.add(Event.exit(name, Path.SLOW, result.getPosition(),
        result.isSuccess(),
        result.isSuccess() && delegate instanceof ActionParser));
    return result;
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    events.add(Event.enter(name, Path.FAST, position));
    int result = delegate.fastParseOn(buffer, position);
    events.add(Event.exit(name, Path.FAST, result, result >= 0,
        result >= 0 && delegate instanceof ActionParser &&
            ((ActionParser<?, ?>) delegate).hasSideEffects));
    return result;
  }

  @Override
  public InstrumentedParser copy() {
    return new InstrumentedParser(name, delegate, events);
  }

  @Override
  public String toString() {
    return super.toString() + "[" + name + "]";
  }
}

package org.petitparser.utils;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Test-only parser that records a trace of both execution paths.
 *
 * <p>Every activation (enter) and return (exit) of {@link #parseOn(Context)}
 * and {@link #fastParseOn(String, int)} is appended to a shared {@link Log}.
 * The log additionally counts how often a production action ran, so that a
 * trace can distinguish the contract of {@code parseOn} (actions always run on
 * success) from {@code fastParseOn} (actions are skipped unless they declare
 * side-effects).
 *
 * <p>A maximum number of activations can be configured to turn an otherwise
 * non-terminating parse, such as a repeating parser whose delegate succeeds
 * without consuming input, into a deterministic {@link SpinException}. This
 * must not be confused with a semantic guard: the exception originates solely
 * from this test harness.
 */
public class InstrumentedParser extends DelegateParser {

  /** Which primitive method produced an event. */
  public enum Path { SLOW, FAST }

  /**
   * Raised when an instrumented parser is activated more times than allowed by
   * {@link Log#maxActivations}. Used by tests to prove that a parser never
   * terminates on its own.
   */
  public static class SpinException extends RuntimeException {
    public final int activations;

    SpinException(int activations) {
      super("Instrumented parser did not terminate after " + activations +
          " activations");
      this.activations = activations;
    }
  }

  /** A single recorded enter or exit event. */
  public static final class Event {
    public enum Kind { ENTER, EXIT }

    public final Kind kind;
    public final Path path;
    public final InstrumentedParser parser;
    public final int enterPosition;
    public final int exitPosition;
    public final boolean success;
    public final boolean actionRan;

    private Event(Kind kind, Path path, InstrumentedParser parser,
        int enterPosition, int exitPosition, boolean success,
        boolean actionRan) {
      this.kind = kind;
      this.path = path;
      this.parser = parser;
      this.enterPosition = enterPosition;
      this.exitPosition = exitPosition;
      this.success = success;
      this.actionRan = actionRan;
    }

    /** Simple class name of the wrapped, production parser. */
    public String wrapped() {
      return parser.label();
    }
  }

  /** Shared trace and counters. */
  public static final class Log {
    public final List<Event> events = new ArrayList<>();
    public int actionCount;
    public int maxActivations = Integer.MAX_VALUE;
    private int activations;
    private int nextId;

    public void reset() {
      events.clear();
      actionCount = 0;
      activations = 0;
    }

    /** Events matching a kind and execution path, in recorded order. */
    public List<Event> events(Event.Kind kind, Path path) {
      List<Event> result = new ArrayList<>();
      for (Event event : events) {
        if (event.kind == kind && event.path == path) {
          result.add(event);
        }
      }
      return result;
    }

    /** Wrapped parser names of matching events, in recorded order. */
    public List<String> labels(Event.Kind kind, Path path) {
      List<String> result = new ArrayList<>();
      for (Event event : events(kind, path)) {
        result.add(event.wrapped());
      }
      return result;
    }

    private void activate() {
      activations++;
      if (activations > maxActivations) {
        throw new SpinException(activations);
      }
    }
  }

  private final Log log;
  private final int id;

  private InstrumentedParser(Parser delegate, Log log, int id) {
    super(delegate);
    this.log = log;
    this.id = id;
  }

  public InstrumentedParser(Parser delegate, Log log) {
    this(delegate, log, log.nextId++);
  }

  /**
   * Returns a transformed copy of {@code source} where every reachable parser
   * is wrapped in an {@link InstrumentedParser} that reports to {@code log}.
   * The source graph itself is left untouched.
   */
  public static Parser instrument(Parser source, Log log) {
    return Mirror.of(source).transform(
        (Function<Parser, Parser>) parser ->
            new InstrumentedParser(parser, log, log.nextId++));
  }

  /** Human readable name used in traces. */
  public String label() {
    return delegate.getClass().getSimpleName() + "#" + id;
  }

  @Override
  public Result parseOn(Context context) {
    enter(Path.SLOW, context.getPosition());
    int before = log.actionCount;
    Result result = delegate.parseOn(context);
    boolean actionRan = log.actionCount != before;
    exit(Path.SLOW, context.getPosition(), result.getPosition(),
        result.isSuccess(), actionRan);
    return result;
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    enter(Path.FAST, position);
    int before = log.actionCount;
    int result = delegate.fastParseOn(buffer, position);
    boolean actionRan = log.actionCount != before;
    exit(Path.FAST, position, result < 0 ? position : result, result >= 0,
        actionRan);
    return result;
  }

  private void enter(Path path, int position) {
    log.activate();
    log.events.add(new Event(Event.Kind.ENTER, path, this, position, position,
        false, false));
  }

  private void exit(Path path, int enterPosition, int exitPosition,
      boolean success, boolean actionRan) {
    log.events.add(new Event(Event.Kind.EXIT, path, this, enterPosition,
        exitPosition, success, actionRan));
  }

  @Override
  public InstrumentedParser copy() {
    return new InstrumentedParser(delegate, log, id);
  }

  /** Action callback to be used by traced production actions. */
  public static <T> T recordAction(Log log, T value) {
    log.actionCount++;
    return value;
  }
}

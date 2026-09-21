package org.petitparser;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Test-only parser that records the activation protocol of every
 * {@link #parseOn(Context)} and {@link #fastParseOn(String, int)} invocation.
 *
 * <p>The parser is a configurable leaf: an outcome function decides whether it
 * succeeds (and where) or fails (at which position with which message). It can
 * optionally carry an action that mirrors the
 * {@code org.petitparser.parser.actions.ActionParser} contract: the action runs
 * on every successful {@code parseOn}, but on the fast path only when it is
 * declared to have side-effects, in which case {@code fastParseOn} falls back to
 * the slow path exactly like the production action parser does.
 */
public class InstrumentedParser extends Parser {

  /** Raised by cutoff parsers to stop an otherwise non-terminating loop. */
  public static final class CutoffException extends RuntimeException {
    public final int activations;
    public final int position;

    CutoffException(String name, int activations, int position) {
      super("Instrumentation cutoff of '" + name + "' after " + activations
          + " activations at position " + position);
      this.activations = activations;
      this.position = position;
    }
  }

  /** Result of the configurable leaf behavior. */
  public static final class Outcome {
    public final boolean success;
    public final int position;
    public final String message;

    private Outcome(boolean success, int position, String message) {
      this.success = success;
      this.position = position;
      this.message = message;
    }

    public static Outcome success(int end) {
      return new Outcome(true, end, null);
    }

    public static Outcome failure(int position, String message) {
      return new Outcome(false, position, message);
    }
  }

  /** A single recorded activation event. */
  public static final class Event {
    public enum Kind { ENTER, EXIT_OK, EXIT_FAILURE, ACTION }

    public final String parser;
    public final boolean fastPath;
    public final Kind kind;
    public final int start;
    public final int end;
    public final String message;

    Event(String parser, boolean fastPath, Kind kind, int start, int end,
        String message) {
      this.parser = parser;
      this.fastPath = fastPath;
      this.kind = kind;
      this.start = start;
      this.end = end;
      this.message = message;
    }

    @Override
    public String toString() {
      String path = fastPath ? "F" : "P";
      switch (kind) {
        case ENTER:
          return parser + ">" + path + "@" + start;
        case EXIT_OK:
          return parser + "=" + path + end;
        case EXIT_FAILURE:
          return parser + "!" + path + end + "[" + message + "]";
        case ACTION:
          return parser + "#" + path + end;
        default:
          throw new IllegalStateException();
      }
    }
  }

  /** Shared ordered log of events. */
  public static final class Log {
    public final List<Event> events = new ArrayList<>();

    public List<String> trace() {
      List<String> result = new ArrayList<>();
      for (Event event : events) {
        result.add(event.toString());
      }
      return result;
    }

    public int count(Event.Kind kind) {
      int count = 0;
      for (Event event : events) {
        if (event.kind == kind) {
          count++;
        }
      }
      return count;
    }

    public void clear() {
      events.clear();
    }
  }

  private final String name;
  private final Log log;
  private final BiFunction<String, Integer, Outcome> behavior;
  private Function<Object, Object> action;
  private boolean actionSideEffects;

  public InstrumentedParser(
      String name, Log log, BiFunction<String, Integer, Outcome> behavior) {
    this.name = Objects.requireNonNull(name);
    this.log = Objects.requireNonNull(log);
    this.behavior = Objects.requireNonNull(behavior);
  }

  public String getName() {
    return name;
  }

  /** Attaches an action executed on successful slow-path parses. */
  public InstrumentedParser withAction() {
    return withAction(value -> name + ":" + value, false);
  }

  /** Attaches an action and declares whether it has side-effects. */
  public InstrumentedParser withAction(boolean sideEffects) {
    return withAction(value -> name + ":" + value, sideEffects);
  }

  /** Attaches a custom action and declares whether it has side-effects. */
  public InstrumentedParser withAction(
      Function<Object, Object> function, boolean sideEffects) {
    this.action = Objects.requireNonNull(function);
    this.actionSideEffects = sideEffects;
    return this;
  }

  @Override
  public Result parseOn(Context context) {
    String buffer = context.getBuffer();
    int position = context.getPosition();
    log.events.add(new Event(name, false, Event.Kind.ENTER, position, position,
        null));
    Outcome outcome = behavior.apply(buffer, position);
    if (outcome.success) {
      Object value = null;
      if (action != null) {
        log.events.add(new Event(name, false, Event.Kind.ACTION, position,
            outcome.position, null));
        value = action.apply(null);
      }
      log.events.add(new Event(name, false, Event.Kind.EXIT_OK, position,
          outcome.position, null));
      return context.success(value, outcome.position);
    }
    log.events.add(new Event(name, false, Event.Kind.EXIT_FAILURE, position,
        outcome.position, outcome.message));
    return context.failure(outcome.message, outcome.position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    if (action != null && actionSideEffects) {
      Result result = parseOn(new Context(buffer, position));
      return result.isSuccess() ? result.getPosition() : -1;
    }
    log.events.add(new Event(name, true, Event.Kind.ENTER, position, position,
        null));
    Outcome outcome = behavior.apply(buffer, position);
    if (outcome.success) {
      log.events.add(new Event(name, true, Event.Kind.EXIT_OK, position,
          outcome.position, null));
      return outcome.position;
    }
    log.events.add(new Event(name, true, Event.Kind.EXIT_FAILURE, position,
        outcome.position, outcome.message));
    return -1;
  }

  @Override
  protected boolean hasEqualProperties(Parser other) {
    return super.hasEqualProperties(other)
        && Objects.equals(name, ((InstrumentedParser) other).name);
  }

  @Override
  public InstrumentedParser copy() {
    InstrumentedParser copy = new InstrumentedParser(name, log, behavior);
    copy.action = action;
    copy.actionSideEffects = actionSideEffects;
    return copy;
  }

  @Override
  public String toString() {
    return "Instrumented[" + name + "]";
  }

  /** Parses a single fixed character. */
  public static InstrumentedParser character(String name, char character,
      Log log) {
    String message = "'" + character + "' expected";
    return new InstrumentedParser(name, log, (buffer, position) -> {
      if (position < buffer.length() && buffer.charAt(position) == character) {
        return Outcome.success(position + 1);
      }
      return Outcome.failure(position, message);
    });
  }

  /** Always succeeds at the same position without consuming input. */
  public static InstrumentedParser epsilon(String name, Log log) {
    return new InstrumentedParser(name, log,
        (buffer, position) -> Outcome.success(position));
  }

  /** Always fails at its entry position. */
  public static InstrumentedParser failing(String name, String message,
      Log log) {
    return new InstrumentedParser(name, log,
        (buffer, position) -> Outcome.failure(position, message));
  }

  /** Always fails {@code advance} positions beyond its entry position. */
  public static InstrumentedParser failingAfter(String name, int advance,
      String message, Log log) {
    return new InstrumentedParser(name, log,
        (buffer, position) -> Outcome.failure(position + advance, message));
  }

  /**
   * Zero-width success that throws {@link CutoffException} after
   * {@code cutoff} activations, to bound non-terminating repeat loops.
   */
  public static InstrumentedParser epsilonWithCutoff(String name, int cutoff,
      Log log) {
    int[] activations = new int[]{0};
    return new InstrumentedParser(name, log, (buffer, position) -> {
      activations[0]++;
      if (activations[0] > cutoff) {
        throw new CutoffException(name, activations[0], position);
      }
      return Outcome.success(position);
    });
  }
}

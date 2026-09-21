package org.petitparser.utils;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.actions.ActionParser;
import org.petitparser.parser.actions.ActionParserInstrumentation;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.List;
import java.util.Objects;

/**
 * A test-only parser that wraps another parser and records every activation
 * and return on both the slow ({@link Parser#parseOn(Context)}) and the fast
 * ({@link Parser#fastParseOn(String, int)}) execution path.
 *
 * <p>Use {@link #instrument(Parser, List)} to create an instrumented copy of
 * a complete parser graph. The copy preserves the sharing and cycles of the
 * original graph (it is built with {@link Mirror#transform}), and additionally
 * wraps the function of every {@link ActionParser} so that action executions
 * are recorded as well.
 */
public class InstrumentedParser extends DelegateParser {

  /**
   * The execution path an event was recorded on.
   */
  public enum Path {
    SLOW, FAST
  }

  /**
   * A single recorded event.
   */
  public static class Event {

    /**
     * The kind of the event.
     */
    public enum Kind {
      ENTER, EXIT, ACTION
    }

    public final Kind kind;
    public final Path path;
    public final InstrumentedParser parser;
    public final int position;
    public final boolean success;

    private Event(Kind kind, Path path, InstrumentedParser parser,
        int position, boolean success) {
      this.kind = kind;
      this.path = path;
      this.parser = parser;
      this.position = position;
      this.success = success;
    }

    static Event enter(Path path, InstrumentedParser parser, int position) {
      return new Event(Kind.ENTER, path, parser, position, false);
    }

    static Event exit(Path path, InstrumentedParser parser, int position,
        boolean success) {
      return new Event(Kind.EXIT, path, parser, position, success);
    }

    static Event action(InstrumentedParser parser) {
      return new Event(Kind.ACTION, Path.SLOW, parser, -1, true);
    }

    /**
     * Returns a human readable description of the wrapped parser.
     */
    public String getDescription() {
      return parser.getDelegate().toString();
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(kind).append(' ').append(path).append(' ')
          .append(getDescription());
      if (kind != Kind.ACTION) {
        builder.append(" @").append(position);
      }
      if (kind == Kind.EXIT) {
        builder.append(success ? " success" : " failure");
      }
      return builder.toString();
    }
  }

  private final List<Event> log;

  public InstrumentedParser(Parser delegate, List<Event> log) {
    super(delegate);
    this.log = Objects.requireNonNull(log, "Undefined event log");
  }

  /**
   * Returns the wrapped parser.
   */
  public Parser getDelegate() {
    return delegate;
  }

  @Override
  public Result parseOn(Context context) {
    log.add(Event.enter(Path.SLOW, this, context.getPosition()));
    Result result = delegate.parseOn(context);
    log.add(Event.exit(Path.SLOW, this, result.getPosition(),
        result.isSuccess()));
    return result;
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    log.add(Event.enter(Path.FAST, this, position));
    int result = delegate.fastParseOn(buffer, position);
    log.add(Event.exit(Path.FAST, this, result, result >= 0));
    return result;
  }

  @Override
  public InstrumentedParser copy() {
    return new InstrumentedParser(delegate, log);
  }

  /**
   * Returns an instrumented copy of the parser graph rooted at {@code root}
   * that records its events into {@code log}.
   */
  public static Parser instrument(Parser root, List<Event> log) {
    return Mirror.of(root).transform(parser -> wrap(parser, log));
  }

  private static Parser wrap(Parser parser, List<Event> log) {
    if (parser instanceof ActionParser) {
      InstrumentedParser[] holder = new InstrumentedParser[1];
      ActionParser<?, ?> recording = ActionParserInstrumentation.record(
          (ActionParser<?, ?>) parser,
          () -> log.add(Event.action(holder[0])));
      InstrumentedParser wrapper = new InstrumentedParser(recording, log);
      holder[0] = wrapper;
      return wrapper;
    }
    return new InstrumentedParser(parser, log);
  }
}

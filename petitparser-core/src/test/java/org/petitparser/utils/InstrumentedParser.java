package org.petitparser.utils;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.List;
import java.util.function.Function;

/**
 * Test-only parser that records every activation of its delegate into a
 * shared string log. Used to verify the call graphs documented in
 * {@code ANALYSIS.md}.
 *
 * <p>Each activation produces two entries:
 * <ul>
 * <li>{@code > name @position} when the parser is entered, and</li>
 * <li>{@code < name @position success|failure} when it returns.</li>
 * </ul>
 *
 * <p>On the {@link #parseOn(Context)} path the exit position is the position
 * of the returned {@link Result} (for failures the position where the failure
 * is reported). On the {@link #fastParseOn(String, int)} path the exit
 * position is the raw integer result, so failures show up as {@code @-1}.
 */
public class InstrumentedParser extends DelegateParser {

  /**
   * Returns a copy of the parser graph reachable from {@code root} where
   * every parser is wrapped in an {@link InstrumentedParser} logging to
   * {@code log}. Parsers are named by their {@code toString()}.
   */
  public static Parser wrap(Parser root, List<String> log) {
    return wrap(root, log, Parser::toString);
  }

  /**
   * Returns a copy of the parser graph reachable from {@code root} where
   * every parser is wrapped in an {@link InstrumentedParser} logging to
   * {@code log}, using {@code namer} to name the parsers.
   */
  public static Parser wrap(
      Parser root, List<String> log, Function<Parser, String> namer) {
    return Mirror.of(root).transform(
        parser -> new InstrumentedParser(parser, namer.apply(parser), log));
  }

  private final String name;
  private final List<String> log;

  public InstrumentedParser(Parser delegate, String name, List<String> log) {
    super(delegate);
    this.name = name;
    this.log = log;
  }

  @Override
  public Result parseOn(Context context) {
    log.add("> " + name + " @" + context.getPosition());
    Result result = delegate.parseOn(context);
    log.add("< " + name + " @" + result.getPosition() +
        (result.isSuccess() ? " success" : " failure"));
    return result;
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    log.add("> " + name + " @" + position);
    int result = delegate.fastParseOn(buffer, position);
    log.add("< " + name + " @" + result +
        (result < 0 ? " failure" : " success"));
    return result;
  }

  @Override
  public InstrumentedParser copy() {
    return new InstrumentedParser(delegate, name, log);
  }

  @Override
  public String toString() {
    return name;
  }
}

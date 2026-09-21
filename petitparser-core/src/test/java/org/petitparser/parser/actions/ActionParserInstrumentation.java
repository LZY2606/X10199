package org.petitparser.parser.actions;

import java.util.function.Function;

/**
 * Test-only helper that wraps the function of an {@link ActionParser} with a
 * recorder, preserving the delegate, the original function, and the
 * side-effect flag. Lives in this package to access the protected state of
 * {@link ActionParser} without reflection.
 */
public final class ActionParserInstrumentation {

  private ActionParserInstrumentation() {
  }

  /**
   * Returns a copy of {@code parser} that runs {@code recorder} whenever the
   * action function of {@code parser} is executed.
   */
  public static <T, R> ActionParser<T, R> record(
      ActionParser<T, R> parser, Runnable recorder) {
    Function<T, R> function = parser.function;
    return new ActionParser<>(parser.getChildren().get(0), value -> {
      recorder.run();
      return function.apply(value);
    }, parser.hasSideEffects);
  }
}

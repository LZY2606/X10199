package org.petitparser.utils;

import org.junit.Test;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.ChoiceParser;
import org.petitparser.parser.combinators.DelegateParser;
import org.petitparser.parser.combinators.SequenceParser;
import org.petitparser.parser.primitive.EpsilonParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;
import org.petitparser.tools.GrammarDefinition;
import org.petitparser.utils.InstrumentedParser.Event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Recorded-evidence tests for {@code ANALYSIS.md}. Every assertion pins the
 * activation sequence observed by {@link InstrumentedParser}; changing an
 * internal condition of a choice/repeat combinator makes these recordings
 * diverge.
 */
public class AnalysisInstrumentationTest {

  /**
   * Minimal grammar with recursion, choice, repeat, lookahead, action and
   * named productions:
   * <pre>
   * start  = list.end
   * list   = (word ',' list) / word
   * word   = &amp;letter letter+ flatten [action: toUpperCase]
   * letter = instrumented leaf
   * </pre>
   */
  static final class MiniDefinition extends GrammarDefinition {

    final InstrumentedParser letter = new InstrumentedParser("letter",
        (buffer, position) -> position < buffer.length()
            && Character.isLetter(buffer.charAt(position)),
        "letter expected");

    MiniDefinition() {
      def("start", ref("list").end());
      def("list", ref("word").seq(of(',')).seq(ref("list"))
          .or(ref("word")));
      def("word", ref("letter").and().seq(ref("letter").plus())
          .flatten()
          .map((String value) -> {
            letter.recordAction();
            return value.toUpperCase();
          }));
      def("letter", letter);
    }
  }

  private static List<Event> enters(List<Event> events) {
    List<Event> filtered = new ArrayList<>();
    for (Event event : events) {
      if (event.kind == Event.Kind.SLOW_ENTER
          || event.kind == Event.Kind.FAST_ENTER) {
        filtered.add(event);
      }
    }
    return filtered;
  }

  private static List<Event> exits(List<Event> events) {
    List<Event> filtered = new ArrayList<>();
    for (Event event : events) {
      if (event.kind == Event.Kind.SLOW_EXIT
          || event.kind == Event.Kind.FAST_EXIT) {
        filtered.add(event);
      }
    }
    return filtered;
  }

  private static List<Integer> positionsOf(List<Event> events) {
    List<Integer> positions = new ArrayList<>();
    for (Event event : events) {
      positions.add(event.position);
    }
    return positions;
  }

  private static List<Boolean> successesOf(List<Event> events) {
    List<Boolean> flags = new ArrayList<>();
    for (Event event : events) {
      flags.add(event.success);
    }
    return flags;
  }

  private static long actionCount(InstrumentedParser parser) {
    return parser.events.stream()
        .filter(event -> event.kind == Event.Kind.ACTION).count();
  }

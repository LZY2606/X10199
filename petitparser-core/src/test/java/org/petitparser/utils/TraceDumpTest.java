package org.petitparser.utils;

import org.junit.Test;
import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.tools.GrammarDefinition;

import java.util.ArrayList;
import java.util.List;

public class TraceDumpTest {

  static class ListGrammar extends GrammarDefinition {
    ListGrammar(List<String> actions, boolean sideEffects) {
      def("start", ref("expr").end());
      def("expr", ref("term")
          .seq(CharacterParser.of('+').seq(ref("term")).star()));
      def("term", CharacterParser.of('(').seq(ref("expr"))
          .seq(CharacterParser.of(')')).pick(1)
          .or(ref("number")));
      Parser digits = CharacterParser.digit().plus().flatten();
      Parser guarded = digits.seq(CharacterParser.digit().not()).pick(0);
      def("number", sideEffects
          ? guarded.mapWithSideEffects(value -> {
            actions.add("action:" + value);
            return value;
          })
          : guarded.map(value -> {
            actions.add("action:" + value);
            return value;
          }));
    }
  }

  @Test
  public void dump() {
    List<String> log = new ArrayList<>();
    Parser parser = new ListGrammar(log, true).build();

    Parser wrapped = InstrumentedParser.wrap(parser, log);
    System.out.println("=== slow 1+2 ===");
    System.out.println(wrapped.parse("1+2"));
    log.forEach(System.out::println);
    log.clear();
    System.out.println("=== fast 1+2 ===");
    System.out.println(wrapped.fastParseOn("1+2", 0));
    log.forEach(System.out::println);
    log.clear();
    System.out.println("=== slow 1+ ===");
    System.out.println(wrapped.parse("1+"));
    log.forEach(System.out::println);
    log.clear();
    System.out.println("=== fast 1+ ===");
    System.out.println(wrapped.fastParseOn("1+", 0));
    log.forEach(System.out::println);
    log.clear();
    System.out.println("=== slow (1) ===");
    System.out.println(wrapped.parse("(1)"));
    log.forEach(System.out::println);
  }
}

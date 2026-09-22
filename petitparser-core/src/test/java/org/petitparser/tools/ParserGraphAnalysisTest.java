package org.petitparser.tools;

import org.junit.Test;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.actions.ActionParser;
import org.petitparser.parser.combinators.AndParser;
import org.petitparser.parser.combinators.ChoiceParser;
import org.petitparser.parser.combinators.DelegateParser;
import org.petitparser.parser.combinators.SequenceParser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.repeating.PossessiveRepeatingParser;
import org.petitparser.utils.Mirror;
import org.petitparser.utils.Optimizer;

import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Tests that pin down the parser-graph semantics documented in {@code
 * ANALYSIS.md}: reference resolution, identity, copying, mirroring, and
 * optimization.
 */
public class ParserGraphAnalysisTest {

  /**
   * The minimal grammar used throughout ANALYSIS.md: recursive ('value' is
   * used by 'start' and inside 'list'), a choice, a repetition, a lookahead,
   * an action, and named productions.
   */
  static class MiniGrammar extends GrammarDefinition {
    MiniGrammar() {
      def("start", ref("value").end());
      def("value", ref("list").or(ref("number")));
      def("list", of('[').and()
          .seq(of('['))
          .seq(ref("value").star())
          .seq(of(']'))
          .pick(2));
      def("number", digit().plus().flatten());
      action("number", (Function<String, Integer>) Integer::parseInt);
    }
  }

  @Test
  public void testGrammarParses() {
    Parser parser = new MiniGrammar().build();
    Result result = parser.parse("[1[23]4]");
    assertTrue(result.isSuccess());
    assertEquals(java.util.Arrays.asList(1, java.util.Arrays.asList(23), 4),
        result.get());
  }

  @Test
  public void testResolveSharesRecursiveProduction() {
    Parser start = new MiniGrammar().build();
    // start = (value.end()).pick(0): ActionParser -> SequenceParser.
    Parser sequence = start.getChildren().get(0);
    Parser value = sequence.getChildren().get(0);
    assertTrue(value instanceof ChoiceParser);
    // value = list.or(number): the first alternative is the list production.
    Parser list = value.getChildren().get(0);
    assertTrue(list instanceof ActionParser);
    // list = (...).pick(2): the third sequence element is value.star().
    Parser listSequence = list.getChildren().get(0);
    Parser star = listSequence.getChildren().get(2);
    assertTrue(star instanceof PossessiveRepeatingParser);
    // The recursion closes the cycle: the star's delegate is the very same
    // ChoiceParser instance that 'start' refers to. The graph is shared, not
    // unfolded.
    assertSame(value, star.getChildren().get(0));
  }

  @Test
  public void testMirrorTerminatesOnCyclicGraph() {
    Parser start = new MiniGrammar().build();
    // Each reachable parser is visited exactly once, despite the cycle.
    assertEquals(15, Mirror.of(start).stream().count());
    assertEquals(15, Mirror.of(start).stream().distinct().count());
  }

  @Test
  public void testUnknownReferenceFailsAtBuildTime() {
    IllegalStateException exception = assertThrows(
        IllegalStateException.class, () -> new MiniGrammar().build("missing"));
    assertEquals("Unknown parser reference: missing", exception.getMessage());
  }

  @Test
  public void testRecursiveReferenceChainFailsAtBuildTime() {
    class Recursive extends GrammarDefinition {
      Recursive() {
        def("a", ref("b"));
        def("b", ref("a"));
      }
    }
    IllegalStateException exception = assertThrows(
        IllegalStateException.class, () -> new Recursive().build("a"));
    assertTrue(exception.getMessage()
        .startsWith("Recursive references detected: "));
  }

  @Test
  public void testLeftRecursionBuildsButLoopsAtParseTime() {
    class LeftRecursive extends GrammarDefinition {
      LeftRecursive() {
        def("start", ref("expr"));
        def("expr", ref("expr").seq(of('+')).or(digit()));
      }
    }
    // Resolution terminates: the reference is replaced by the choice itself,
    // producing a cyclic graph.
    Parser parser = new LeftRecursive().build();
    // Parsing recurses without bound and blows the stack.
    assertThrows(StackOverflowError.class, () -> parser.parse("1"));
  }

  @Test
  public void testCopyIsShallow() {
    ChoiceParser choice = of('a').or(of('b'));
    Parser copy = choice.copy();
    assertNotSame(choice, copy);
    assertTrue(choice.isEqualTo(copy));
    // The children are shared between the original and the copy.
    assertSame(choice.getChildren().get(0), copy.getChildren().get(0));
    assertSame(choice.getChildren().get(1), copy.getChildren().get(1));
  }

  @Test
  public void testMirrorTransformCopiesButPreservesSharing() {
    Parser shared = digit();
    Parser root = shared.seq(shared.star());
    Parser transformed = Mirror.of(root).transform(Function.identity());
    assertNotSame(root, transformed);
    assertTrue(root.isEqualTo(transformed));
    // The parser used twice in the original is still a single shared
    // instance in the copy: transform does not unfold shared subgraphs.
    Parser copiedShared = transformed.getChildren().get(0);
    Parser copiedStar = transformed.getChildren().get(1);
    assertNotSame(shared, copiedShared);
    assertSame(copiedShared, copiedStar.getChildren().get(0));
  }

  @Test
  public void testOptimizerRemoveDelegatesExposesOriginalChild() {
    Parser delegate = digit();
    Parser optimized =
        new Optimizer().removeDelegates().transform(SettableParser.with(delegate));
    // copy() is shallow, so unwrapping the copied settable yields the
    // original child instance, not a copy.
    assertSame(delegate, optimized);
  }

  @Test
  public void testOptimizerRemoveDelegatesOnlyMatchesExactClasses() {
    Parser and = digit().and();
    Parser optimized = new Optimizer().removeDelegates().transform(and);
    // AndParser extends DelegateParser but is not removed: the transformer
    // compares classes with equals, not instanceof.
    assertTrue(optimized instanceof AndParser);
    assertNotSame(and, optimized);
  }

  @Test
  public void testOptimizerRemoveDuplicatesMergesEqualParsers() {
    Parser root = digit().seq(digit());
    Parser optimized = new Optimizer().removeDuplicates().transform(root);
    assertTrue(optimized instanceof SequenceParser);
    // Two structurally equal but distinct parsers become one shared instance.
    assertSame(optimized.getChildren().get(0), optimized.getChildren().get(1));
  }

  @Test
  public void testOptimizedGrammarParsesSameLanguage() {
    Parser original = new MiniGrammar().build();
    Parser optimized = new Optimizer()
        .removeDelegates()
        .removeDuplicates()
        .transform(original);
    for (String input : new String[] {"1", "[1]", "[1[23]4]", "[1", "[x]"}) {
      assertEquals(input, original.accept(input), optimized.accept(input));
    }
    assertFalse(Mirror.of(optimized).stream()
        .anyMatch(parser -> parser.getClass().equals(DelegateParser.class) ||
            parser.getClass().equals(SettableParser.class)));
  }
}

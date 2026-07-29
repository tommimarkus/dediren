package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins {@code accessibility.lang} / {@code accessibility.dir} onto the SVG root as {@code xml:lang}
 * / {@code direction}.
 *
 * <p>Two properties matter and they pull against each other. The attributes must appear when the
 * policy declares them — otherwise the accessible name ships untagged, which is the defect this
 * lane exists to fix. And they must be absent, not defaulted, when it does not: every checked-in
 * golden was recorded before these keys existed, so a renderer that emitted {@code xml:lang="en"}
 * by default would silently relabel every untagged diagram as English. The absent-case assertions
 * below are the ones that would catch that, so they are written against the raw root markup rather
 * than a parsed view of it.
 *
 * <p>Both root emitters are covered. The generic document renderer and the UML sequence renderer
 * open their own {@code <svg>} start-tags independently, so a fix applied to one lane and not the
 * other is exactly the drift this suite is here to catch.
 */
final class SvgRootLanguageTest {

  private static Stream<Arguments> bothLanes() {
    return Stream.of(
        Arguments.of("document", "basic", "default-svg", null),
        Arguments.of("sequence", "uml-sequence-basic", "uml-svg", "uml-sequence-basic"));
  }

  @ParameterizedTest(name = "{0} lane emits xml:lang and direction when the policy declares them")
  @MethodSource("bothLanes")
  void emitsLanguageAndDirectionOnRoot(String lane, String layout, String policy, String metadata)
      throws Exception {
    String svg = renderWithAccessibility(layout, policy, metadata, "ar-EG", "rtl");

    String root = rootTag(svg);
    assertThat(root).describedAs("%s lane root", lane).contains("xml:lang=\"ar-EG\"");
    assertThat(root).describedAs("%s lane root", lane).contains("direction=\"rtl\"");
  }

  @ParameterizedTest(name = "{0} lane omits both attributes when the policy declares neither")
  @MethodSource("bothLanes")
  void omitsBothWhenPolicyIsSilent(String lane, String layout, String policy, String metadata)
      throws Exception {
    String svg =
        RenderTestSupport.renderFixtures(layoutPath(layout), policyPath(policy), meta(metadata));

    String root = rootTag(svg);
    assertThat(root)
        .describedAs("%s lane must not default a language onto an untagged policy", lane)
        .doesNotContain("xml:lang");
    assertThat(root)
        .describedAs("%s lane must not default a base direction onto an untagged policy", lane)
        .doesNotContain("direction=");
  }

  @Test
  void emitsEachAttributeIndependently() throws Exception {
    String langOnly = rootTag(renderWithAccessibility("basic", "default-svg", null, "fi", null));
    assertThat(langOnly).contains("xml:lang=\"fi\"").doesNotContain("direction=");

    String dirOnly = rootTag(renderWithAccessibility("basic", "default-svg", null, null, "ltr"));
    assertThat(dirOnly).contains("direction=\"ltr\"").doesNotContain("xml:lang");
  }

  /**
   * The attributes ride the root, not a descendant: {@code direction} is inherited by every text
   * element below it, so placing it deeper would leave part of the diagram in the wrong base
   * direction.
   */
  @Test
  void attributesLandOnTheRootElementItself() throws Exception {
    String svg = renderWithAccessibility("basic", "default-svg", null, "he", "rtl");

    assertThat(svg.indexOf("xml:lang=\"he\"")).isLessThan(svg.indexOf('>'));
    assertThat(svg.indexOf("direction=\"rtl\"")).isLessThan(svg.indexOf('>'));
  }

  private static String renderWithAccessibility(
      String layout, String policy, String metadata, String lang, String dir) throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(layoutPath(layout), policyPath(policy), meta(metadata));
    ObjectNode policyNode = (ObjectNode) input.get("policy");
    ObjectNode accessibility =
        policyNode.has("accessibility") && policyNode.get("accessibility").isObject()
            ? (ObjectNode) policyNode.get("accessibility")
            : policyNode.putObject("accessibility");
    if (lang != null) {
      accessibility.put("lang", lang);
    }
    if (dir != null) {
      accessibility.put("dir", dir);
    }
    return RenderTestSupport.render(input);
  }

  /** The opening {@code <svg ...>} start-tag only, so descendant markup cannot satisfy a match. */
  private static String rootTag(String svg) {
    int close = svg.indexOf('>');
    assertThat(close).describedAs("rendered SVG has no root start-tag").isPositive();
    return svg.substring(0, close + 1);
  }

  private static String layoutPath(String name) {
    return "fixtures/layout-result/" + name + ".json";
  }

  private static String policyPath(String name) {
    return "fixtures/render-policy/" + name + ".json";
  }

  private static String meta(String name) {
    return name == null ? null : "fixtures/render-metadata/" + name + ".json";
  }
}

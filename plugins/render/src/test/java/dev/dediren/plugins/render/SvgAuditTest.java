package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;

/**
 * Render-layer audit: parses each emitted SVG back and checks it against reality — well-formedness,
 * id uniqueness, {@code url(#…)} reference resolution, finite coordinates, viewBox containment, and
 * {@code textLength} pin correctness. These are the invariants the layout-geometry quality signals
 * cannot see, because they run on the abstract layout the renderer produced its own SVG from.
 */
class SvgAuditTest {

  // --- The audit has teeth: each check must fire on a crafted violation. ---

  @Test
  void rejectsIllFormedSvg() {
    assertThatThrownBy(() -> SvgAudit.parse("<svg><rect></svg>"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("well-formed");
  }

  @Test
  void detectsDuplicateIds() {
    Document document =
        SvgAudit.parse("<svg viewBox=\"0 0 10 10\"><rect id=\"m\"/><rect id=\"m\"/></svg>");
    assertThatThrownBy(() -> SvgAudit.assertUniqueIds(document))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void detectsDanglingUrlReference() {
    Document document =
        SvgAudit.parse("<svg viewBox=\"0 0 10 10\"><path marker-end=\"url(#missing)\"/></svg>");
    assertThatThrownBy(() -> SvgAudit.assertReferencesResolve(document))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("missing");
  }

  @Test
  void detectsNonFiniteCoordinate() {
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 10 10\"><rect x=\"Infinity\" y=\"0\" width=\"1\" height=\"1\"/></svg>");
    assertThatThrownBy(() -> SvgAudit.assertFiniteGeometry(document))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("non-finite");
  }

  @Test
  void detectsShapePaintedOutsideViewBox() {
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 10 10\"><rect x=\"100\" y=\"0\" width=\"5\" height=\"5\"/></svg>");
    assertThatThrownBy(() -> SvgAudit.assertGeometryWithinViewBox(document, 1.0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("outside the emitted viewBox");
  }

  @Test
  void markerChildrenAreExemptFromViewBoxContainment() {
    // Marker geometry lives in the marker's own coordinate space, so a marker path at x=9 is not an
    // escape even when the viewBox is tiny.
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 10 10\"><marker id=\"a\"><path d=\"M 9 3\"/>"
                + "<rect x=\"0\" y=\"0\" width=\"9\" height=\"6\"/></marker></svg>");
    SvgAudit.assertGeometryWithinViewBox(document, 1.0);
  }

  // --- Every real render passes the full structural audit. ---

  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#all")
  void everyRenderedFixtureIsStructurallySound(
      String name, String layout, String policy, String metadata) throws Exception {
    SvgAudit.auditStructure(RenderTestSupport.renderFixtures(layout, policy, metadata));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#all")
  void everyPinnedLabelWidthMatchesRealFontMetrics(
      String name, String layout, String policy, String metadata) throws Exception {
    // ASCII labels: the AFM-based estimate must track real sans-serif metrics closely. Non-ASCII
    // strings the measuring font cannot display are skipped inside the check (their full-em width
    // is
    // verified deterministically in SvgTextWidthTest).
    SvgAudit.assertTextWidthPinsMatchRealFont(
        SvgAudit.parse(RenderTestSupport.renderFixtures(layout, policy, metadata)), 0.85, 1.15);
  }
}

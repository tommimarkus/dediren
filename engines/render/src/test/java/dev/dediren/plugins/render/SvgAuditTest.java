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

  @Test
  void detectsPinNarrowerThanRealFontWidth() {
    // A textLength pinned far below what the font actually renders squeezes the glyphs. The real
    // Liberation-Sans width of "client" at 10px is ~24 units, ~2.4x this pin — well past the
    // ceiling
    // — so the oracle must fire. Guards that tightening the band did not cost it its teeth.
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 100 100\"><text font-size=\"10\" textLength=\"10\">client</text></svg>");
    assertThatThrownBy(() -> SvgAudit.assertTextWidthPinsMatchRealFont(document, 0.85, 1.06))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("diverging from real font metrics");
  }

  @Test
  void acceptsPinMatchingRealFontWidth() {
    // The same label pinned at the width the estimate reserves (~24 units) must pass: the bundled
    // measuring font agrees with the AFM estimate to within a fraction of a percent.
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 100 100\"><text font-size=\"10\" textLength=\"24\">client</text></svg>");
    SvgAudit.assertTextWidthPinsMatchRealFont(document, 0.85, 1.06);
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
    // Measured against the bundled Arial-metric-compatible font, ASCII pins sit at ~1.0 (observed
    // 1.000-1.003 across every fixture), so the ceiling is snug at 1.06 — tight enough to catch a
    // wide glyph under-reserved at the 0.6em fallback (ratio ~1.67) yet loose enough to absorb
    // sub-pixel metric noise.
    // The floor stays at 0.85 to tolerate that same 0.6em approximation for narrow non-ASCII Latin
    // the font can display. Non-ASCII the font cannot display is skipped inside the check (its
    // full-em width is verified deterministically in SvgTextWidthTest).
    SvgAudit.assertTextWidthPinsMatchRealFont(
        SvgAudit.parse(RenderTestSupport.renderFixtures(layout, policy, metadata)), 0.85, 1.06);
  }
}

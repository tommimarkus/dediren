package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SvgPaintAuditCorpusTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("allRenderScenarios")
  void allCuratedRenderScenariosClearTheLayeredPaintOracle(
      String name, String layout, String policy, String metadata) throws Exception {
    String svg = RenderTestSupport.renderFixtures(layout, policy, metadata);
    SvgPaintAudit.Report report = SvgPaintAudit.audit(svg);

    assertThat(report.violations()).describedAs("%s: %s", name, report.violations()).isEmpty();
    // The label rules above are gated on measurable text: a run the oracle cannot measure (an
    // uncovered glyph in the pinned font, a text filter) is reported as an advisory and silently
    // stops being checked, so an empty violation list would keep passing while the corpus quietly
    // lost coverage. Nothing in the shipped corpus is unmeasurable today; assert that, so a
    // regression that makes something unmeasurable fails here instead of disarming the oracle.
    assertThat(report.advisories()).describedAs("%s: %s", name, report.advisories()).isEmpty();
  }

  @Test
  void adversarialPaintFixtureIsLegalAndReportsUnsupportedGlyphsWithoutHostFallback()
      throws Exception {
    String svg =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="520" height="260" viewBox="0 0 520 260">
          <rect width="520" height="260" fill="#ffffff"/>
          <defs>
            <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto">
              <path d="M 1 1 L 9 5 L 1 9" fill="none" stroke="#000000" stroke-width="1.5"/>
            </marker>
          </defs>
          <g font-family="Liberation Sans" font-size="14">
            <g data-dediren-group-id="outer">
              <rect x="10" y="10" width="500" height="240" fill="none" stroke="#000000"/>
              <text x="18" y="27">Nested labels</text>
            </g>
            <g data-dediren-group-id="inner">
              <rect x="30" y="40" width="460" height="190" fill="none" stroke="#000000"/>
              <text x="38" y="57">Inner</text>
            </g>
            <g data-dediren-node-id="ascii"><rect data-dediren-node-shape="rectangle" x="50" y="75" width="180" height="45" fill="#ffffff" stroke="#000000"/>
              <text x="140" y="97" text-anchor="middle" dominant-baseline="middle" textLength="155" lengthAdjust="spacing">long_ASCII-0123456789</text></g>
            <g data-dediren-node-id="unicode"><rect data-dediren-node-shape="rectangle" x="290" y="75" width="170" height="90" fill="#ffffff" stroke="#000000"/>
              <text x="375" y="95" text-anchor="middle" dominant-baseline="middle">漢字</text>
              <text x="375" y="125" text-anchor="middle" dominant-baseline="middle">🙂</text></g>
            <g data-dediren-node-id="rtl-supported">
              <text x="115" y="150" text-anchor="middle" direction="rtl">שלום</text>
            </g>
            <g data-dediren-node-id="combining-supported">
              <text x="215" y="150" text-anchor="middle">é</text>
            </g>
            <g data-dediren-edge-id="boundary-marker"><path d="M 75 200 L 475 200" fill="none" stroke="#000000" marker-end="url(#arrow)"/>
              <text x="275" y="190" text-anchor="middle" fill="none" stroke="#ffffff" stroke-width="2">edge label</text>
              <text x="275" y="190" text-anchor="middle">edge label</text></g>
          </g>
        </svg>
        """;

    SvgPaintAudit.Report report = SvgPaintAudit.audit(svg);
    assertThat(report.violations()).isEmpty();
    assertThat(report.advisories())
        .filteredOn(advisory -> advisory.code().equals("font_missing"))
        .extracting(SvgPaintAudit.Violation::semanticIds)
        .contains(List.of("node:unicode"));
    assertThat(report.semanticBounds().get("node:rtl-supported").hasPositiveArea()).isTrue();
    assertThat(report.semanticBounds().get("node:combining-supported").hasPositiveArea()).isTrue();
    assertThat(report.advisories())
        .noneMatch(
            advisory ->
                advisory.code().equals("font_missing")
                    && (advisory.semanticIds().contains("node:rtl-supported")
                        || advisory.semanticIds().contains("node:combining-supported")));
  }

  @Test
  void translationAndUniformScalingTransformSemanticBoundsPredictably() throws Exception {
    String body =
        "<g data-dediren-node-id=\"n\"><rect data-dediren-node-shape=\"rectangle\" x=\"20\""
            + " y=\"30\" width=\"50\" height=\"30\" fill=\"#ffffff\" stroke=\"#000000\"/></g>";
    SvgPaintAudit.Bounds base =
        SvgPaintAudit.audit(svg(300, 240, body)).semanticBounds().get("node:n");
    SvgPaintAudit.Bounds translated =
        SvgPaintAudit.audit(svg(300, 240, "<g transform=\"translate(40 25)\">" + body + "</g>"))
            .semanticBounds()
            .get("node:n");
    SvgPaintAudit.Bounds scaled =
        SvgPaintAudit.audit(svg(300, 240, "<g transform=\"scale(2)\">" + body + "</g>"))
            .semanticBounds()
            .get("node:n");

    assertThat(translated.x() - base.x()).isCloseTo(40, within(0.01));
    assertThat(translated.y() - base.y()).isCloseTo(25, within(0.01));
    assertThat(scaled.x()).isCloseTo(base.x() * 2, within(0.01));
    assertThat(scaled.y()).isCloseTo(base.y() * 2, within(0.01));
    assertThat(scaled.width()).isCloseTo(base.width() * 2, within(0.01));
    assertThat(scaled.height()).isCloseTo(base.height() * 2, within(0.01));
  }

  @Test
  void lightAndDarkBuiltInThemesDoNotMovePaintGeometry() throws Exception {
    String light =
        RenderTestSupport.renderFixtures(
            "fixtures/layout-result/pipeline-rich.json",
            "fixtures/render-policy/default-svg.json",
            null);
    String dark =
        RenderTestSupport.renderFixtures(
            "fixtures/layout-result/pipeline-rich.json",
            "fixtures/render-policy/dark-svg.json",
            null);

    Map<String, SvgPaintAudit.Bounds> lightBounds = SvgPaintAudit.audit(light).geometryBounds();
    Map<String, SvgPaintAudit.Bounds> darkBounds = SvgPaintAudit.audit(dark).geometryBounds();
    assertThat(darkBounds).isEqualTo(lightBounds);
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }

  private static Stream<Arguments> allRenderScenarios() {
    // The dark theme rides the same oracle as every other scenario, so the built-in dark palette's
    // contrast baselines and label paint are measured on real render output, not only on the
    // synthetic fixtures in SvgPaintAuditContrastTest and the goldens in RasterGoldenTest.
    return Stream.concat(RenderScenarios.all(), RenderScenarios.darkTheme());
  }

  private static String svg(int width, int height, String body) {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""
        + width
        + "\" height=\""
        + height
        + "\" viewBox=\"0 0 "
        + width
        + " "
        + height
        + "\"><rect width=\""
        + width
        + "\" height=\""
        + height
        + "\" fill=\"#ffffff\"/><g font-family=\"Liberation Sans\" font-size=\"14\">"
        + body
        + "</g></svg>";
  }
}

package dev.dediren.plugins.render;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The geometry oracle: every scenario's rendered SVG is compared byte-for-byte against a checked-in
 * golden.
 *
 * <p>The rest of the render suite asserts <em>structure</em> — that an SVG comes out, that it
 * carries node content, that a given element exists. None of it asserts <em>geometry</em>, and the
 * gallery test says so itself ("a light smoke gate ... not a geometry oracle"). The cost of that
 * gap is on the record: {@code Geometry.labelBox} and {@code UmlSequenceRenderer.labelWidth} sized
 * text with a flat {@code length * fontSize * 0.56} — the estimator {@code Svg}'s own comment
 * condemns for over-stating narrow glyphs (#25) and under-measuring CJK by ~40% (#39) — and fixing
 * it moved the emitted geometry without a single test failing, in either direction. Wrong output
 * was invisible, and so was the correction.
 *
 * <p>A byte-comparison is safe here because the render is deterministic: {@link
 * RenderDeterminismTest} independently proves the same input renders byte-identically across runs.
 * So a diff in this test means the <em>output changed</em>, never that the test is flaky.
 *
 * <p>This does not replace property assertions — it complements them. A golden catches unintended
 * movement but cannot say what should be true; {@link
 * dev.dediren.plugins.render.svg.LabelBoxMeasurementTest} pins the properties the flat estimator
 * got wrong (CJK wider than same-length ASCII) without freezing pixel values. Goldens catch drift;
 * properties explain intent.
 *
 * <p><strong>Regenerating.</strong> When a change deliberately moves the output, re-baseline with
 * {@code ./scripts/regen-render-goldens.sh} (or {@code ./mvnw -pl engines/render -am test
 * -Dtest=RenderGoldenTest -Ddediren.render.regenerate-goldens=true}) and <em>read the diff</em> —
 * it is the change you are shipping to every consumer's diagrams. Regeneration is deliberately
 * opt-in: a golden that silently rewrites itself is not an oracle.
 */
class RenderGoldenTest {

  private static final String REGENERATE = "dediren.render.regenerate-goldens";
  private static final String GOLDEN_DIR = "engines/render/src/test/resources/golden";

  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#all")
  void renderedSvgIsByteIdenticalToItsGolden(
      String name, String layout, String policy, String metadata) throws Exception {
    String rendered = RenderTestSupport.renderFixtures(layout, policy, metadata);
    Path golden = goldenPath(name, policy);

    if (Boolean.getBoolean(REGENERATE)) {
      Files.createDirectories(golden.getParent());
      Files.writeString(golden, rendered, UTF_8);
      return;
    }

    if (!Files.exists(golden)) {
      fail(
          "No golden for scenario '%s'. If this scenario is new, create it with"
              + " ./scripts/regen-render-goldens.sh and commit %s",
          name, golden);
    }

    assertThat(rendered)
        .as(
            "%s renders differently than its golden (%s). If the change is deliberate, re-baseline"
                + " with ./scripts/regen-render-goldens.sh and review the diff — it is the geometry"
                + " change every consumer's diagrams will see.",
            name, golden)
        .isEqualTo(Files.readString(golden, UTF_8));
  }

  /** Scenario names repeat across policies (basic renders under both default-svg and rich-svg). */
  private static Path goldenPath(String name, String policy) {
    String policyName = Path.of(policy).getFileName().toString().replace(".json", "");
    return RenderTestSupport.workspaceRoot()
        .resolve(GOLDEN_DIR)
        .resolve(name + "__" + policyName + ".svg");
  }
}

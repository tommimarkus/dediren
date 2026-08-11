package dev.dediren.plugins.render;

import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Shared {@code @MethodSource} providers for the render-plugin test suites: each scenario is a
 * {@code (name, layoutPath, policyPath, metadataPath)} tuple over the checked-in fixtures. Keeps
 * the audit, determinism, and appearance suites from each maintaining their own copy of the fixture
 * matrix. {@code standard()} excludes the UML sequence renderer, which has its own element
 * structure and paint order.
 */
final class RenderScenarios {
  private RenderScenarios() {}

  static Stream<Arguments> standard() {
    return Stream.of(
        scenario("basic", "default-svg", null),
        scenario("basic", "rich-svg", null),
        scenario("pipeline-rich", "default-svg", null),
        scenario("archimate-oef-basic", "archimate-svg", "archimate-basic"),
        scenario("uml-basic", "uml-svg", "uml-basic"),
        scenario("uml-activity", "uml-svg", "uml-activity"),
        scenario("uml-data", "uml-svg", "uml-data"),
        scenario("uml-complex-class", "uml-svg", "uml-complex-class"),
        scenario("uml-component-basic", "uml-svg", "uml-component-basic"),
        scenario("uml-deployment-basic", "uml-svg", "uml-deployment-basic"),
        scenario("uml-state-machine-basic", "uml-svg", "uml-state-machine-basic"),
        scenario("uml-use-case-basic", "uml-svg", "uml-use-case-basic"));
  }

  static Stream<Arguments> sequence() {
    return Stream.of(
        scenario("uml-sequence-basic", "uml-svg", "uml-sequence-basic"),
        scenario("uml-sequence-fragments", "uml-svg", "uml-sequence-fragments"),
        scenario("uml-sequence-self-message", "uml-svg", "uml-sequence-self-message"),
        scenario("uml-sequence-lifecycle", "uml-svg", "uml-sequence-lifecycle"));
  }

  static Stream<Arguments> all() {
    return Stream.concat(standard(), sequence());
  }

  /**
   * The built-in dark theme over the notation-free layouts. Every other scenario above pairs a
   * layout with the policy for its own semantic profile, so the dark policy — which declares no
   * {@code semantic_profile} — can only be paired with the layouts that need none. Pairing it with
   * a UML or ArchiMate layout would drop the notation the layout was sized for (see {@code
   * DEDIREN_RENDER_METADATA_PROFILE_NOT_APPLIED}), which is a different scenario than exercising
   * the dark palette.
   */
  static Stream<Arguments> darkTheme() {
    return Stream.of(
        scenario("basic-dark", "basic", "dark-svg", null),
        scenario("pipeline-rich-dark", "pipeline-rich", "dark-svg", null));
  }

  private static Arguments scenario(String layout, String policy, String metadata) {
    return scenario(layout, layout, policy, metadata);
  }

  private static Arguments scenario(String name, String layout, String policy, String metadata) {
    return Arguments.of(
        name,
        "fixtures/layout-result/" + layout + ".json",
        "fixtures/render-policy/" + policy + ".json",
        metadata == null ? null : "fixtures/render-metadata/" + metadata + ".json");
  }
}

package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * Determinism guard: the same layout renders to a byte-identical SVG every time. Golden-snapshot
 * and stable-diff review both depend on this, and nothing else asserts it — a stray {@code HashMap}
 * iteration or time/identity dependency in the renderer would otherwise surface only as review
 * noise. Layout-side determinism is guarded separately in the ELK plugin.
 */
class RenderDeterminismTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#all")
  void renderIsByteIdenticalAcrossRuns(String name, String layout, String policy, String metadata)
      throws Exception {
    ObjectNode input = RenderTestSupport.fixtureInput(layout, policy, metadata);

    String first = RenderTestSupport.render(input);
    String second = RenderTestSupport.render(input);

    assertThat(second).isEqualTo(first);
  }
}

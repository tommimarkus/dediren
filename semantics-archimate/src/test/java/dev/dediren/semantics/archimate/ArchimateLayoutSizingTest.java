package dev.dediren.semantics.archimate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.source.SourceNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchimateLayoutSizingTest {
  @Test
  void relationshipConnectorsUseCompactSizeHints() {
    SourceNode connector = new SourceNode("junction", "AndJunction", "And", Map.of());

    assertThat(ArchimateLayoutSizing.widthHint(connector)).isEqualTo(28.0);
    assertThat(ArchimateLayoutSizing.heightHint(connector)).isEqualTo(28.0);
  }

  @Test
  void orJunctionConnectorAlsoUsesCompactSizeHints() {
    SourceNode connector = new SourceNode("junction", "OrJunction", "Or", Map.of());

    assertThat(ArchimateLayoutSizing.widthHint(connector)).isEqualTo(28.0);
    assertThat(ArchimateLayoutSizing.heightHint(connector)).isEqualTo(28.0);
  }

  @Test
  void sizesNodesToFitLabelAndCornerIcon() {
    SourceNode shortLabel = new SourceNode("short", "ApplicationComponent", "API", Map.of());
    SourceNode longLabel =
        new SourceNode(
            "long",
            "ApplicationComponent",
            "Application Collaboration Service Component",
            Map.of());

    // width  = roundUp(max(3*8.7 + 2*34, 160), 10) = roundUp(94.1, 10) -> floored by min = 160.0
    // height = roundUp(max(1*18 + 28, 80), 10) = 80.0
    assertThat(ArchimateLayoutSizing.widthHint(shortLabel)).isEqualTo(160.0);
    assertThat(ArchimateLayoutSizing.heightHint(shortLabel)).isEqualTo(80.0);

    // width  = roundUp(max(13*8.7 + 68, 160), 10) = roundUp(181.1, 10) = 190.0
    // height = roundUp(max(4*18 + 28, 80), 10) = 100.0
    assertThat(ArchimateLayoutSizing.widthHint(longLabel)).isEqualTo(190.0);
    assertThat(ArchimateLayoutSizing.heightHint(longLabel)).isEqualTo(100.0);
  }
}

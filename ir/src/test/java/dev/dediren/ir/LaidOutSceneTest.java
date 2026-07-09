package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LaidOutSceneTest {
  @Test
  void placedNodeCarriesGeometryRoleAndOrigin() {
    PlacedNode n =
        new PlacedNode(
            "n1", "n1", "p1", 1, 2, 30, 40, "N1", "lifeline", new SourcePointer("/nodes/0"));
    assertThat(n.x()).isEqualTo(1);
    assertThat(n.role()).isEqualTo("lifeline");
    assertThat(n.origin().value()).isEqualTo("/nodes/0");
  }

  @Test
  void sceneDefaultsEmptyCollections() {
    LaidOutScene scene = new LaidOutScene("v1", null, null, null, null);
    assertThat(scene.nodes()).isEmpty();
    assertThat(scene.edges()).isEmpty();
    assertThat(scene.groups()).isEmpty();
    assertThat(scene.warnings()).isEmpty();
  }
}

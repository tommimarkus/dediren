package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SceneGraphTest {
  @Test
  void sceneNodeCarriesOrigin() {
    SceneNode node =
        new SceneNode("n1", "N1", "n1", 10.0, 10.0, "lifeline", null, null, SourcePointers.node(0));
    assertThat(node.origin().value()).isEqualTo("/nodes/0");
  }

  @Test
  void sceneGraphDefaultsEmptyCollections() {
    SceneGraph graph = new SceneGraph("view-1", null, null, null, null, null);
    assertThat(graph.nodes()).isEmpty();
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.groups()).isEmpty();
    assertThat(graph.constraints()).isEmpty();
  }
}

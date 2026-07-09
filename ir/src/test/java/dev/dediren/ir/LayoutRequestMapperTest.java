package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEdgePriority;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutLayerConstraint;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutRequestMapperTest {
  @Test
  void mapsSceneNodesAndStampsProvenanceAndVersion() {
    SceneGraph graph =
        new SceneGraph(
            "view-1",
            List.of(
                new SceneNode(
                    "n1", "N1", "n1", 10.0, 10.0, "lifeline", null, null, SourcePointers.node(2))),
            List.of(
                new SceneEdge(
                    "e1", "n1", "n2", "", "e1", "flow", null, SourcePointers.relationship(0))),
            List.of(),
            List.of(),
            null);

    LayoutRequest request = LayoutRequestMapper.toRequest(graph);

    assertThat(request.layoutRequestSchemaVersion())
        .isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    assertThat(request.viewId()).isEqualTo("view-1");
    assertThat(request.nodes().get(0).sourcePointer()).isEqualTo("/nodes/2");
    assertThat(request.nodes().get(0).sourceId()).isEqualTo("n1");
    assertThat(request.nodes().get(0).role()).isEqualTo("lifeline");
    assertThat(request.edges().get(0).sourcePointer()).isEqualTo("/relationships/0");
    assertThat(request.edges().get(0).sourceId()).isEqualTo("e1");
  }

  // LayoutRequestMapper.java:29-32's group-mapping branch (graph.groups().stream().map(...))
  // is only reached when a SceneGraph carries a non-empty groups list; the test above always
  // passes List.of(), so that branch was untested. A regression that dropped a field in the
  // LayoutGroup construction (id/label/members/provenance) would not fail any existing test.
  @Test
  void mapsNonEmptySceneGroupsToLayoutGroups() {
    GroupProvenance provenance = GroupProvenance.semanticBacked("group-source-1");
    SceneGraph graph =
        new SceneGraph(
            "view-1",
            List.of(
                new SceneNode(
                    "n1", "N1", "n1", 10.0, 10.0, null, null, null, SourcePointers.node(0)),
                new SceneNode(
                    "n2", "N2", "n2", 10.0, 10.0, null, null, null, SourcePointers.node(1))),
            List.of(),
            List.of(new SceneGroup("g1", "Group One", List.of("n1", "n2"), provenance)),
            List.of(),
            null);

    LayoutRequest request = LayoutRequestMapper.toRequest(graph);

    assertThat(request.groups()).hasSize(1);
    LayoutGroup mappedGroup = request.groups().get(0);
    assertThat(mappedGroup.id()).isEqualTo("g1");
    assertThat(mappedGroup.label()).isEqualTo("Group One");
    assertThat(mappedGroup.members()).containsExactly("n1", "n2");
    assertThat(mappedGroup.provenance()).isEqualTo(provenance);
    assertThat(mappedGroup.provenance().semanticSourceId()).isEqualTo("group-source-1");
  }

  @Test
  void toSceneGraphThenToRequestIsIdentity() {
    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "v1",
            List.of(
                new LayoutNode(
                    "n1",
                    "N1",
                    "src-n1",
                    160.0,
                    80.0,
                    "lifeline",
                    2,
                    LayoutLayerConstraint.FIRST,
                    "/nodes/0")),
            List.of(
                new LayoutEdge(
                    "e1",
                    "n1",
                    "n1",
                    "E1",
                    "src-e1",
                    "Message",
                    new LayoutEdgePriority(5, 2, 8),
                    "/relationships/0")),
            List.of(new LayoutGroup("g1", "G1", List.of("n1"), GroupProvenance.visualOnlyGroup())),
            List.of(new LayoutConstraint("c1", "uml.sequence.lifeline-order", List.of("n1"))),
            new LayoutPreferences(LayoutDirection.DOWN, null, null, null));

    assertThat(LayoutRequestMapper.toRequest(LayoutRequestMapper.toSceneGraph(request)))
        .isEqualTo(request);
  }
}

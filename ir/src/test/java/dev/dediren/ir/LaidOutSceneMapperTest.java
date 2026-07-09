package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class LaidOutSceneMapperTest {

  private LayoutResult buildResult() {
    LaidOutNode node =
        new LaidOutNode("n1", "src-n1", "p1", 10.0, 20.0, 30.0, 40.0, "N1", "lifeline", "/nodes/0");
    LaidOutEdge edge =
        new LaidOutEdge(
            "e1",
            "n1",
            "n2",
            "src-e1",
            "p1",
            List.of("hint-1"),
            List.of(new Point(0, 0), new Point(10, 10)),
            "E1",
            "/relationships/0");
    LaidOutGroup group =
        new LaidOutGroup(
            "g1",
            "src-g1",
            "p1",
            GroupProvenance.semanticBacked("group-source-1"),
            1.0,
            2.0,
            3.0,
            4.0,
            List.of("n1"),
            "G1");
    Diagnostic warning = new Diagnostic("W1", DiagnosticSeverity.WARNING, "careful", "/nodes/0");
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        "view-1",
        List.of(node),
        List.of(edge),
        List.of(group),
        List.of(warning));
  }

  @Test
  void toSceneWrapsSourcePointerIntoOrigin() {
    LayoutResult result = buildResult();

    LaidOutScene scene = LaidOutSceneMapper.toScene(result);

    assertThat(scene.viewId()).isEqualTo("view-1");
    assertThat(scene.nodes().get(0).origin().value()).isEqualTo("/nodes/0");
    assertThat(scene.edges().get(0).origin().value()).isEqualTo("/relationships/0");
    assertThat(scene.warnings()).hasSize(1);
    assertThat(scene.warnings().get(0).code()).isEqualTo("W1");
  }

  @Test
  void roundTripPreservesGeometryIdsRoleAndSourcePointer() {
    LayoutResult result = buildResult();

    LayoutResult roundTripped = LaidOutSceneMapper.toResult(LaidOutSceneMapper.toScene(result));

    assertThat(roundTripped.layoutResultSchemaVersion())
        .isEqualTo(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION);
    assertThat(roundTripped.viewId()).isEqualTo(result.viewId());

    LaidOutNode originalNode = result.nodes().get(0);
    LaidOutNode roundTrippedNode = roundTripped.nodes().get(0);
    assertThat(roundTrippedNode.id()).isEqualTo(originalNode.id());
    assertThat(roundTrippedNode.sourceId()).isEqualTo(originalNode.sourceId());
    assertThat(roundTrippedNode.projectionId()).isEqualTo(originalNode.projectionId());
    assertThat(roundTrippedNode.x()).isEqualTo(originalNode.x());
    assertThat(roundTrippedNode.y()).isEqualTo(originalNode.y());
    assertThat(roundTrippedNode.width()).isEqualTo(originalNode.width());
    assertThat(roundTrippedNode.height()).isEqualTo(originalNode.height());
    assertThat(roundTrippedNode.label()).isEqualTo(originalNode.label());
    assertThat(roundTrippedNode.role()).isEqualTo(originalNode.role());
    assertThat(roundTrippedNode.sourcePointer()).isEqualTo(originalNode.sourcePointer());

    LaidOutEdge originalEdge = result.edges().get(0);
    LaidOutEdge roundTrippedEdge = roundTripped.edges().get(0);
    assertThat(roundTrippedEdge.id()).isEqualTo(originalEdge.id());
    assertThat(roundTrippedEdge.source()).isEqualTo(originalEdge.source());
    assertThat(roundTrippedEdge.target()).isEqualTo(originalEdge.target());
    assertThat(roundTrippedEdge.sourceId()).isEqualTo(originalEdge.sourceId());
    assertThat(roundTrippedEdge.projectionId()).isEqualTo(originalEdge.projectionId());
    assertThat(roundTrippedEdge.routingHints()).isEqualTo(originalEdge.routingHints());
    assertThat(roundTrippedEdge.points()).isEqualTo(originalEdge.points());
    assertThat(roundTrippedEdge.label()).isEqualTo(originalEdge.label());
    assertThat(roundTrippedEdge.sourcePointer()).isEqualTo(originalEdge.sourcePointer());

    LaidOutGroup originalGroup = result.groups().get(0);
    LaidOutGroup roundTrippedGroup = roundTripped.groups().get(0);
    assertThat(roundTrippedGroup.id()).isEqualTo(originalGroup.id());
    assertThat(roundTrippedGroup.sourceId()).isEqualTo(originalGroup.sourceId());
    assertThat(roundTrippedGroup.projectionId()).isEqualTo(originalGroup.projectionId());
    assertThat(roundTrippedGroup.provenance()).isEqualTo(originalGroup.provenance());
    assertThat(roundTrippedGroup.x()).isEqualTo(originalGroup.x());
    assertThat(roundTrippedGroup.y()).isEqualTo(originalGroup.y());
    assertThat(roundTrippedGroup.width()).isEqualTo(originalGroup.width());
    assertThat(roundTrippedGroup.height()).isEqualTo(originalGroup.height());
    assertThat(roundTrippedGroup.members()).isEqualTo(originalGroup.members());
    assertThat(roundTrippedGroup.label()).isEqualTo(originalGroup.label());

    assertThat(roundTripped.warnings()).isEqualTo(result.warnings());
  }

  @Test
  void toSceneHandlesNullSourcePointer() {
    LaidOutNode node =
        new LaidOutNode("n1", "n1", "p1", 0.0, 0.0, 1.0, 1.0, "N1", "lifeline", null);
    LayoutResult result =
        new LayoutResult(
            ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
            "view-1",
            List.of(node),
            List.of(),
            List.of(),
            List.of());

    LaidOutScene scene = LaidOutSceneMapper.toScene(result);

    assertThat(scene.nodes().get(0).origin()).isNull();
  }

  @Test
  void toResultHandlesNullOrigin() {
    PlacedNode node =
        new PlacedNode("n1", "src-n1", "p1", 0.0, 0.0, 1.0, 1.0, "N1", "lifeline", null);
    RoutedEdge edge =
        new RoutedEdge(
            "e1",
            "n1",
            "n2",
            "src-e1",
            "p1",
            List.of("hint-1"),
            List.of(new Point(0, 0)),
            "E1",
            null);
    LaidOutScene scene =
        new LaidOutScene("view-1", List.of(node), List.of(edge), List.of(), List.of());

    LayoutResult result = LaidOutSceneMapper.toResult(scene);

    assertThat(result.nodes().get(0).sourcePointer()).isNull();
    assertThat(result.edges().get(0).sourcePointer()).isNull();
  }
}

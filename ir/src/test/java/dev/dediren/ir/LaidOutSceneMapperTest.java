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
    // Whole-record equality: a future field added to the records but missed by the mapper
    // fails here structurally, instead of passing a hand-enumerated field list silently.
    assertThat(roundTripped).isEqualTo(result);
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

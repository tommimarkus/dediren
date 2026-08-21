package dev.dediren.plugins.asciirender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.PlacedGroup;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.RoutedEdge;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Golden-string tests for {@link AsciiRenderEngine}. Tests 1-2 pin the exact character grid for a
 * two-node, one-edge scene by hand-simulating {@link CoordinateGrid}'s anchor quantization (8 x
 * units/col, 16 y units/row): node "a" spans x[0,24] y[0,48], node "b" spans x[40,64] y[0,48], and
 * the connecting edge runs (24,24) -> (40,24). Every other test targets one renderer behavior
 * (group border, edge-label placement, truncation, diagonal approximation, sequence-view warning,
 * invalid policy, empty scene) with scene geometry sized to make that behavior unambiguous.
 */
class AsciiRenderEngineTest {
  private final AsciiRenderEngine engine = new AsciiRenderEngine();

  @Test
  void rendersTwoNodesAndAnEdgeInUnicode() throws Exception {
    LaidOutScene scene = twoNodeScene();

    String content = render(scene, minimalPolicy(), null);

    assertThat(content)
        .isEqualTo(
            """
            ┌──┐ ┌──┐
            │  │ │  │
            │A ├▶┤B │
            │  │ │  │
            └──┘ └──┘
            """);
  }

  @Test
  void rendersTwoNodesAndAnEdgeInAscii() throws Exception {
    LaidOutScene scene = twoNodeScene();

    String content = render(scene, policyWithCharset("ascii"), null);

    assertThat(content)
        .isEqualTo(
            """
            +--+ +--+
            |  | |  |
            |A +>+B |
            |  | |  |
            +--+ +--+
            """);
  }

  @Test
  void groupBorderEmbedsItsLabelInTheTopBorder() throws Exception {
    PlacedNode a = node("a", 0, 0, 24, 48, "A");
    PlacedNode b = node("b", 40, 0, 24, 48, "B");
    PlacedGroup g = group("g", -8, -16, 80, 80, List.of("a", "b"), "G");
    LaidOutScene scene = new LaidOutScene("v", List.of(a, b), List.of(), List.of(g), List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);

    String content = render(scene, minimalPolicy(), null);
    String[] lines = content.split("\n", -1);

    int topRow = grid.rowOf(-16);
    int leftCol = grid.colOf(-8);
    int rightCol = grid.colOf(72);
    assertThat(lines[topRow].charAt(leftCol)).isEqualTo('┌');
    assertThat(lines[topRow].charAt(rightCol)).isEqualTo('┐');
    assertThat(lines[topRow].substring(leftCol + 2)).startsWith(" G ");
  }

  @Test
  void edgeLabelIsPlacedAboveItsLongestHorizontalSegment() throws Exception {
    RoutedEdge labeled = edge("e1", "s", "t", "hi", new Point(0, 50), new Point(200, 50));
    RoutedEdge spacer = edge("e2", "x", "y", "", new Point(500, 0), new Point(500, 20));
    LaidOutScene scene =
        new LaidOutScene("v", List.of(), List.of(labeled, spacer), List.of(), List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);

    String content = render(scene, minimalPolicy(), null);
    String[] lines = content.split("\n", -1);

    int labelRow = grid.rowOf(50) - 1;
    int midCol = (grid.colOf(0) + grid.colOf(200)) / 2;
    int startCol = midCol - "hi".length() / 2;
    assertThat(lines[labelRow].substring(startCol, startCol + 2)).isEqualTo("hi");
  }

  @Test
  void edgeLabelIsDroppedWithADiagnosticWhenBothCandidateRowsAreClipped() throws Exception {
    // A single-row canvas: the edge's only y-anchor puts its segment at row 0, so both the "above"
    // (row -1) and "below" (row 1) candidates are clipped at the canvas edge, which the spec
    // treats as a collision on both sides.
    RoutedEdge labeled = edge("e1", "s", "t", "unplaceable", new Point(0, 0), new Point(200, 0));
    LaidOutScene scene = new LaidOutScene("v", List.of(), List.of(labeled), List.of(), List.of());

    EngineResult<?> result = engine.render(scene, minimalPolicy(), null);

    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_ASCII_EDGE_LABEL_DROPPED");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.path()).isEqualTo("edges[e1].label");
            });
  }

  @Test
  void nodeLabelLongerThanTheWrapCapIsTruncatedWithADiagnostic() throws Exception {
    String longLabel = String.join(" ", Collections.nCopies(20, "a".repeat(32)));
    PlacedNode node = node("n", 0, 0, 10, 10, longLabel);
    LaidOutScene scene = new LaidOutScene("v", List.of(node), List.of(), List.of(), List.of());

    EngineResult<?> result = engine.render(scene, minimalPolicy(), null);

    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_ASCII_LABEL_TRUNCATED");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.path()).isEqualTo("nodes[n].label");
            });
    assertThat(asciiContent(result)).contains("…");
  }

  @Test
  void diagonalEdgeIsApproximatedAsAnLRouteExactlyOnce() throws Exception {
    RoutedEdge diagonal = edge("e1", "s", "t", "", new Point(0, 0), new Point(100, 100));
    LaidOutScene scene = new LaidOutScene("v", List.of(), List.of(diagonal), List.of(), List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);

    EngineResult<?> result = engine.render(scene, minimalPolicy(), null);

    List<Diagnostic> approximated =
        result.diagnostics().stream()
            .filter(d -> d.code().equals("DEDIREN_ASCII_EDGE_APPROXIMATED"))
            .toList();
    assertThat(approximated).hasSize(1);
    assertThat(approximated.get(0).severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(approximated.get(0).path()).isEqualTo("edges[e1]");

    String content = asciiContent(result);
    String[] lines = content.split("\n", -1);
    int startRow = grid.rowOf(0);
    int startCol = grid.colOf(0);
    int endRow = grid.rowOf(100);
    int endCol = grid.colOf(100);
    assertThat(lines[startRow].charAt(startCol)).isNotEqualTo(' ');
    assertThat(lines[endRow].charAt(endCol)).isNotEqualTo(' ');
  }

  @Test
  void sequenceShapedMetadataWarnsAndStillRendersNonEmptyOutput() throws Exception {
    RenderMetadata metadata =
        new RenderMetadata(
            "render-metadata.schema.v1",
            "uml",
            Map.of("a", new RenderMetadataSelector("Lifeline", "a", null)),
            Map.of(),
            Map.of());
    LaidOutScene scene = twoNodeScene();

    EngineResult<?> result = engine.render(scene, minimalPolicy(), metadata);

    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_ASCII_SEQUENCE_VIEW_GENERIC");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.path()).isEqualTo("$");
            });
    assertThat(asciiContent(result)).isNotEmpty();
  }

  @Test
  void nullMetadataPublishesNoSequenceViewWarning() throws Exception {
    LaidOutScene scene = twoNodeScene();

    EngineResult<?> result = engine.render(scene, minimalPolicy(), null);

    assertThat(result.diagnostics())
        .noneMatch(diagnostic -> diagnostic.code().equals("DEDIREN_ASCII_SEQUENCE_VIEW_GENERIC"));
  }

  @Test
  void anUnknownCharsetFailsPolicyBindingWithAsciiPolicyInvalid() {
    LaidOutScene scene = new LaidOutScene("v", List.of(), List.of(), List.of(), List.of());
    ObjectNode policy = policyWithCharset("bogus");

    EngineException failure =
        assertThrows(EngineException.class, () -> engine.render(scene, policy, null));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_ASCII_POLICY_INVALID");
  }

  @Test
  void emptySceneRendersAnEmptyTextArtifact() throws Exception {
    LaidOutScene scene = new LaidOutScene("v", List.of(), List.of(), List.of(), List.of());

    String content = render(scene, minimalPolicy(), null);

    assertThat(content).isEmpty();
  }

  private static LaidOutScene twoNodeScene() {
    PlacedNode a = node("a", 0, 0, 24, 48, "A");
    PlacedNode b = node("b", 40, 0, 24, 48, "B");
    RoutedEdge e = edge("ab", "a", "b", "", new Point(24, 24), new Point(40, 24));
    return new LaidOutScene("v", List.of(a, b), List.of(e), List.of(), List.of());
  }

  private String render(LaidOutScene scene, ObjectNode policy, RenderMetadata metadata)
      throws Exception {
    return asciiContent(engine.render(scene, policy, metadata));
  }

  private static String asciiContent(EngineResult<?> result) {
    var renderResult = (RenderResult) result.value();
    return renderResult.artifacts().get(0).content();
  }

  private static PlacedNode node(String id, double x, double y, double w, double h, String label) {
    return new PlacedNode(id, id, id, x, y, w, h, label, "node", null);
  }

  private static PlacedGroup group(
      String id, double x, double y, double w, double h, List<String> members, String label) {
    return new PlacedGroup(id, id, id, null, x, y, w, h, members, label);
  }

  private static RoutedEdge edge(
      String id, String source, String target, String label, Point... points) {
    return new RoutedEdge(id, source, target, id, id, List.of(), List.of(points), label, null);
  }

  private static ObjectNode minimalPolicy() {
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    policy.put("render_policy_schema_version", ContractVersions.RENDER_POLICY_SCHEMA_VERSION);
    ObjectNode page = policy.putObject("page");
    page.put("width", 800);
    page.put("height", 600);
    ObjectNode margin = policy.putObject("margin");
    margin.put("top", 0);
    margin.put("right", 0);
    margin.put("bottom", 0);
    margin.put("left", 0);
    return policy;
  }

  private static ObjectNode policyWithCharset(String charset) {
    ObjectNode policy = minimalPolicy();
    policy.putObject("text").put("charset", charset);
    return policy;
  }
}

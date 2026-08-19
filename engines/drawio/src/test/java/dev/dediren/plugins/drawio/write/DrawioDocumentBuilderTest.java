package dev.dediren.plugins.drawio.write;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.DrawioExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutNodeRole;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.plugins.drawio.mx.MxCell;
import dev.dediren.plugins.drawio.mx.MxDiagram;
import dev.dediren.plugins.drawio.mx.MxPoint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the {@code (SourceDocument, LayoutResult)} → {@code MxFile} mapping: where geometry
 * comes from, how identity is carried, and what the builder discloses instead of failing.
 */
class DrawioDocumentBuilderTest {

  private static final DrawioExportPolicy POLICY =
      new DrawioExportPolicy(ContractVersions.DRAWIO_EXPORT_POLICY_SCHEMA_VERSION, "Main");

  private static SourceDocument source(
      List<SourceNode> nodes, List<SourceRelationship> relationships) {
    return new SourceDocument(
        "model.schema.v1",
        List.of(),
        List.of(),
        nodes,
        relationships,
        Map.of(
            "generic-graph",
            JsonSupport.readTree(
                """
                {
                  "semantic_profile": "archimate",
                  "views": [{ "id": "main", "label": "Main", "kind": "archimate" }]
                }
                """)));
  }

  private static LayoutResult layout(
      List<LaidOutNode> nodes, List<LaidOutEdge> edges, List<LaidOutGroup> groups) {
    return new LayoutResult("layout-result.schema.v1", "main", nodes, edges, groups, List.of());
  }

  private static MxDiagram page(DrawioDocumentBuilder.Document document) {
    return document.file().diagrams().get(0);
  }

  private static MxCell cellCarrying(DrawioDocumentBuilder.Document document, String dedirenId) {
    return page(document).cells().stream()
        .filter(cell -> cell.object() != null)
        .filter(cell -> dedirenId.equals(cell.object().attributes().get(DrawioIdentity.ID)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no cell carries dedirenId '" + dedirenId + "'"));
  }

  private static Optional<Diagnostic> diagnostic(
      DrawioDocumentBuilder.Document document, DiagnosticCode code) {
    return document.diagnostics().stream()
        .filter(candidate -> candidate.code().equals(code.code()))
        .findFirst();
  }

  private static SourceNode node(String id, String type) {
    return new SourceNode(id, type, id, Map.of());
  }

  private static LaidOutNode laidOut(String id, double x, double y, double width, double height) {
    return new LaidOutNode(id, id, null, x, y, width, height, id);
  }

  // ---------------------------------------------------------------- geometry

  @Test
  void takesNodeGeometryStraightFromTheLayoutResult() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("orders", "ApplicationComponent")), List.of()),
            layout(List.of(laidOut("orders", 12, 24.5, 160, 80)), List.of(), List.of()),
            POLICY);

    var geometry = cellCarrying(document, "orders").geometry();
    assertThat(geometry.x()).isEqualTo(12);
    assertThat(geometry.y()).isEqualTo(24.5);
    assertThat(geometry.width()).isEqualTo(160);
    assertThat(geometry.height()).isEqualTo(80);
  }

  @Test
  void keepsOnlyTheInteriorWaypointsOfAnEdgeRouteAndParentsTheEdgeToTheLayer() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(node("a", "ApplicationComponent"), node("b", "ApplicationService")),
                List.of(new SourceRelationship("a-serves-b", "Serving", "a", "b", "calls", Map.of()))),
            layout(
                List.of(laidOut("a", 12, 12, 160, 80), laidOut("b", 530, 166, 160, 80)),
                List.of(
                    new LaidOutEdge(
                        "a-serves-b",
                        "a",
                        "b",
                        "a-serves-b",
                        null,
                        List.of(),
                        List.of(
                            new Point(172, 52),
                            new Point(370, 52),
                            new Point(370, 206),
                            new Point(529, 206)),
                        "calls")),
                List.of()),
            POLICY);

    MxCell edge = cellCarrying(document, "a-serves-b");
    // The first and last route points sit on the two shapes' perimeters; draw.io recomputes those
    // from the attached cells, and re-emitting them leaves stale bends the moment a node moves.
    assertThat(edge.geometry().points())
        .containsExactly(new MxPoint(370, 52), new MxPoint(370, 206));
    // Waypoints are interpreted relative to the edge's own parent, so an edge always rides the
    // layer even when both endpoints are inside a container.
    assertThat(edge.parent()).isEqualTo("1");
    assertThat(edge.edge()).isTrue();
  }

  // ---------------------------------------------------------------- containment

  @Test
  void reParentsGroupMembersAndRebasesTheirGeometryOnTheContainerOrigin() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("web-app", "ApplicationComponent")), List.of()),
            layout(
                List.of(laidOut("web-app", 290, 36, 160, 80)),
                List.of(),
                List.of(
                    new LaidOutGroup(
                        "application-services",
                        "application-services",
                        null,
                        GroupProvenance.semanticBacked("application-services"),
                        265,
                        12,
                        699,
                        388,
                        List.of("web-app"),
                        "Application Services"))),
            POLICY);

    MxCell container = cellCarrying(document, "application-services");
    assertThat(container.geometry().x()).isEqualTo(265);
    assertThat(container.geometry().y()).isEqualTo(12);

    MxCell member = cellCarrying(document, "web-app");
    assertThat(member.parent()).isEqualTo(container.id());
    // mxGraph reads a child's geometry against its parent's origin; the layout result's
    // coordinates are absolute, so the container origin has to come off or draw.io draws the
    // member 265 to the right of where it was laid out.
    assertThat(member.geometry().x()).isEqualTo(25);
    assertThat(member.geometry().y()).isEqualTo(24);
  }

  @Test
  void nestsAGroupInsideAnotherGroupWhenTheMemberListNamesIt() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("api", "ApplicationComponent")), List.of()),
            layout(
                List.of(laidOut("api", 710, 416, 180, 96)),
                List.of(),
                List.of(
                    new LaidOutGroup(
                        "outer",
                        "pkg-orders",
                        null,
                        GroupProvenance.semanticBacked("pkg-orders"),
                        12,
                        12,
                        942,
                        736,
                        List.of("inner"),
                        "Orders"),
                    new LaidOutGroup(
                        "inner",
                        null,
                        null,
                        GroupProvenance.visualOnlyGroup(),
                        581,
                        380,
                        341,
                        268,
                        List.of("api"),
                        "Order API"))),
            POLICY);

    MxCell outer = cellCarrying(document, "outer");
    MxCell inner = cellCarrying(document, "inner");
    MxCell api = cellCarrying(document, "api");

    assertThat(outer.parent()).isEqualTo("1");
    assertThat(inner.parent()).isEqualTo(outer.id());
    assertThat(inner.geometry().x()).isEqualTo(569);
    assertThat(inner.geometry().y()).isEqualTo(368);
    assertThat(api.parent()).isEqualTo(inner.id());
    assertThat(api.geometry().x()).isEqualTo(129);
    assertThat(api.geometry().y()).isEqualTo(36);
  }

  @Test
  void recordsTheProvenanceOfEveryGroupAsAGroupRole() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(), List.of()),
            layout(
                List.of(),
                List.of(),
                List.of(
                    new LaidOutGroup(
                        "semantic",
                        "pkg",
                        null,
                        GroupProvenance.semanticBacked("pkg"),
                        0,
                        0,
                        10,
                        10,
                        List.of(),
                        "Semantic"),
                    new LaidOutGroup(
                        "visual",
                        null,
                        null,
                        GroupProvenance.visualOnlyGroup(),
                        0,
                        0,
                        10,
                        10,
                        List.of(),
                        "Visual"))),
            POLICY);

    assertThat(cellCarrying(document, "semantic").object().attributes())
        .containsEntry(DrawioIdentity.TYPE, DrawioIdentity.GROUP_TYPE)
        .containsEntry(DrawioIdentity.GROUP_ROLE, DrawioIdentity.GROUP_ROLE_SEMANTIC)
        .containsEntry(DrawioIdentity.SEMANTIC_SOURCE_ID, "pkg");
    assertThat(cellCarrying(document, "visual").object().attributes())
        .containsEntry(DrawioIdentity.GROUP_ROLE, DrawioIdentity.GROUP_ROLE_VISUAL)
        .doesNotContainKey(DrawioIdentity.SEMANTIC_SOURCE_ID);
  }

  // ---------------------------------------------------------------- identity

  @Test
  void keysEdgeEndpointsByDedirenIdSoTheySurviveTheEditorReassigningCellIds() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(node("A node", "ApplicationComponent"), node("B node", "ApplicationService")),
                List.of(
                    new SourceRelationship("r 1", "Serving", "A node", "B node", null, Map.of()))),
            layout(
                List.of(laidOut("A node", 0, 0, 10, 10), laidOut("B node", 40, 0, 10, 10)),
                List.of(
                    new LaidOutEdge(
                        "r 1", "A node", "B node", "r 1", null, List.of(), List.of(), null)),
                List.of()),
            POLICY);

    MxCell edge = cellCarrying(document, "r 1");
    // The mxCell ids are slugged, so they are demonstrably not the dediren ids here.
    assertThat(edge.source()).isNotEqualTo("A node");
    assertThat(edge.object().attributes())
        .containsEntry(DrawioIdentity.SOURCE, "A node")
        .containsEntry(DrawioIdentity.TARGET, "B node")
        .containsEntry(DrawioIdentity.TYPE, "Serving");
    assertThat(edge.source()).isEqualTo(cellCarrying(document, "A node").id());
    assertThat(edge.target()).isEqualTo(cellCarrying(document, "B node").id());
  }

  @Test
  void carriesTheExactSourceTypeOnEveryElement() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("orders", "ApplicationComponent")), List.of()),
            layout(List.of(laidOut("orders", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    assertThat(cellCarrying(document, "orders").object().attributes())
        .containsEntry(DrawioIdentity.ID, "orders")
        .containsEntry(DrawioIdentity.TYPE, "ApplicationComponent")
        .containsEntry(DrawioIdentity.SEMANTIC_SOURCE_ID, "orders");
  }

  // ---------------------------------------------------------------- the metadata cell

  @Test
  void ridesTheViewMetadataOnAHiddenCellRatherThanOnTheFileOrPageElement() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("orders", "ApplicationComponent")), List.of()),
            layout(List.of(laidOut("orders", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    MxCell metadata =
        page(document).cells().stream()
            .filter(cell -> cell.object() != null)
            .filter(
                cell ->
                    DrawioIdentity.VIEW_TYPE.equals(
                        cell.object().attributes().get(DrawioIdentity.TYPE)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no dediren.view metadata cell"));

    // The editor rewrites <mxfile> and <diagram> but round-trips unknown <object> attributes
    // verbatim — that round-trip is the whole mechanism behind draw.io's Edit Data feature.
    assertThat(metadata.object().attributes())
        .containsEntry(DrawioIdentity.VIEW_ID, "main")
        .containsEntry(DrawioIdentity.VIEW_KIND, "archimate")
        .containsEntry(DrawioIdentity.SEMANTIC_PROFILE, "archimate")
        .containsEntry(DrawioIdentity.MODEL_SCHEMA_VERSION, "model.schema.v1");
    assertThat(metadata.visible()).isFalse();
    assertThat(metadata.object().attributes()).doesNotContainKey(DrawioIdentity.ID);
  }

  @Test
  void emitsTheMetadataCellEvenForAViewTheSourceDeclaresNothingAbout() {
    var document =
        DrawioDocumentBuilder.build(
            new SourceDocument("model.schema.v1", List.of(), List.of(), List.of(), List.of(), Map.of()),
            layout(List.of(), List.of(), List.of()),
            POLICY);

    assertThat(page(document).cells())
        .filteredOn(cell -> cell.object() != null)
        .hasSize(1);
  }

  // ---------------------------------------------------------------- disclosure

  @Test
  void warnsAndFallsBackWhenNoDrawioShapeCoversTheElementType() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("order", "Class")), List.of()),
            layout(List.of(laidOut("order", 0, 0, 220, 120)), List.of(), List.of()),
            POLICY);

    MxCell cell = cellCarrying(document, "order");
    assertThat(cell.style()).contains("whiteSpace=wrap").doesNotContain("mxgraph.archimate3");
    // dedirenType still records the exact type, so a re-import is lossless regardless.
    assertThat(cell.object().attributes()).containsEntry(DrawioIdentity.TYPE, "Class");

    Diagnostic warning =
        diagnostic(document, DiagnosticCode.DRAWIO_SHAPE_UNMAPPED).orElseThrow();
    assertThat(warning.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(warning.message()).contains("Class").contains(DrawioIdentity.TYPE);
  }

  @Test
  void warnsWhenALaidOutElementReferencesNoSourceElement() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(), List.of()),
            layout(List.of(laidOut("ghost", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    Diagnostic warning =
        diagnostic(document, DiagnosticCode.DRAWIO_LAYOUT_REFERENCE_MISSING).orElseThrow();
    assertThat(warning.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(warning.message()).contains("ghost");
    // Degrading, not failing: the element is still drawn so the page is not silently short one box.
    assertThat(cellCarrying(document, "ghost")).isNotNull();
  }

  @Test
  void namesTheUmlBehaviourOrnamentsItDidNotDraw() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("actor", "Class"), node("call", "Class")), List.of()),
            layout(
                List.of(
                    new LaidOutNode(
                        "actor", "actor", null, 0, 0, 10, 300, "Actor", LayoutNodeRole.LIFELINE),
                    new LaidOutNode(
                        "call", "call", null, 0, 40, 10, 20, "call", LayoutNodeRole.EXECUTION)),
                List.of(),
                List.of()),
            POLICY);

    Diagnostic omitted =
        diagnostic(document, DiagnosticCode.DRAWIO_ORNAMENT_OMITTED).orElseThrow();
    assertThat(omitted.severity()).isEqualTo(DiagnosticSeverity.INFO);
    assertThat(omitted.message()).contains("lifeline").contains("execution");
  }

  @Test
  void staysSilentAboutOrnamentsForAViewThatHasNone() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("orders", "ApplicationComponent")), List.of()),
            layout(List.of(laidOut("orders", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_ORNAMENT_OMITTED)).isEmpty();
  }

  // ---------------------------------------------------------------- style sourcing

  @Test
  void leavesTheThreeSemanticallyColouredElementTypesToTheShapeTableAlone() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(
                    node("and", "AndJunction"),
                    node("or", "OrJunction"),
                    node("box", "Grouping"),
                    node("comp", "ApplicationComponent")),
                List.of()),
            layout(
                List.of(
                    laidOut("and", 0, 0, 10, 10),
                    laidOut("or", 20, 0, 10, 10),
                    laidOut("box", 40, 0, 100, 100),
                    laidOut("comp", 160, 0, 160, 80)),
                List.of(),
                List.of()),
            POLICY);

    // Fill is the only thing distinguishing the two junctions, and Grouping is always unfilled;
    // appending the palette's fill would win the last-key-wins style parse and destroy both.
    assertThat(cellCarrying(document, "and").style()).isEqualTo(shapeStyle("AndJunction"));
    assertThat(cellCarrying(document, "or").style()).isEqualTo(shapeStyle("OrJunction"));
    assertThat(cellCarrying(document, "box").style()).isEqualTo(shapeStyle("Grouping"));
    assertThat(cellCarrying(document, "comp").style())
        .startsWith(shapeStyle("ApplicationComponent"))
        .contains("fillColor=");
  }

  private static String shapeStyle(String elementType) {
    return dev.dediren.plugins.drawio.style.DrawioShapes.shapeFor(elementType).style();
  }
}

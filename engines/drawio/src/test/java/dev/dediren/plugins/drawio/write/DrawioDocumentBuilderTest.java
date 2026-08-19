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
    return source(nodes, relationships, "archimate");
  }

  private static SourceDocument source(
      List<SourceNode> nodes, List<SourceRelationship> relationships, String viewKind) {
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
                  "views": [{ "id": "main", "label": "Main", "kind": "%s" }]
                }
                """
                    .formatted(viewKind))));
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
                List.of(
                    new SourceRelationship("a-serves-b", "Serving", "a", "b", "calls", Map.of()))),
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
            source(List.of(node("pkg", "Grouping")), List.of()),
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

  /**
   * A semantic boundary stands for a real element, and the element is often not laid out as a box
   * of its own — a UML package drawn only as its boundary is the standard shape. The reference
   * alone is therefore not enough: the exported file has to carry the element, or Dediren's own
   * artifact names an id that nothing in it declares and cannot be re-imported.
   */
  @Test
  void aSemanticBoundaryCarriesTheElementItStandsForEvenWhenNothingLaysItOut() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(
                    new SourceNode("pkg-orders", "Package", "Orders", Map.of()),
                    node("component-order-api", "Component")),
                List.of()),
            layout(
                List.of(laidOut("component-order-api", 20, 20, 160, 80)),
                List.of(),
                List.of(
                    new LaidOutGroup(
                        "orders-package-boundary",
                        "pkg-orders",
                        null,
                        GroupProvenance.semanticBacked("pkg-orders"),
                        0,
                        0,
                        400,
                        300,
                        List.of("component-order-api"),
                        "Orders"))),
            POLICY);

    assertThat(cellCarrying(document, "orders-package-boundary").object().attributes())
        .containsEntry(DrawioIdentity.SEMANTIC_SOURCE_ID, "pkg-orders")
        .containsEntry(DrawioIdentity.SEMANTIC_SOURCE_TYPE, "Package")
        .containsEntry(DrawioIdentity.SEMANTIC_SOURCE_LABEL, "Orders");
  }

  /**
   * {@code SceneProjection} gives a semantic-boundary group that declares no {@code
   * semantic_source_id} a provenance naming the group itself, and the layout result carries that
   * back verbatim. Writing it out would manufacture a reference to an element the model does not
   * have: the file re-imports green and the next command rejects the model. The group stays
   * semantic — that is its declared role — and simply names nothing.
   */
  @Test
  void aSemanticGroupBackedByNoSourceElementNamesNothingRatherThanItself() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("web-app", "ApplicationComponent")), List.of()),
            layout(
                List.of(laidOut("web-app", 20, 20, 160, 80)),
                List.of(),
                List.of(
                    new LaidOutGroup(
                        "application-services",
                        "application-services",
                        null,
                        GroupProvenance.semanticBacked("application-services"),
                        0,
                        0,
                        400,
                        300,
                        List.of("web-app"),
                        "Application Services"))),
            POLICY);

    assertThat(cellCarrying(document, "application-services").object().attributes())
        .containsEntry(DrawioIdentity.GROUP_ROLE, DrawioIdentity.GROUP_ROLE_SEMANTIC)
        .doesNotContainKey(DrawioIdentity.SEMANTIC_SOURCE_ID)
        .doesNotContainKey(DrawioIdentity.SEMANTIC_SOURCE_TYPE);
    // Silently, because this is the ordinary shape of a boundary that declares no source — not a
    // broken reference. The broken one is the next test.
    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_LAYOUT_REFERENCE_MISSING)).isEmpty();
  }

  /** A boundary naming some <em>other</em> element the model does not declare is a stale layout. */
  @Test
  void aSemanticBoundaryNamingAnUndeclaredElementIsDisclosed() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("web-app", "ApplicationComponent")), List.of()),
            layout(
                List.of(laidOut("web-app", 20, 20, 160, 80)),
                List.of(),
                List.of(
                    new LaidOutGroup(
                        "orders-package-boundary",
                        "pkg-orders",
                        null,
                        GroupProvenance.semanticBacked("pkg-orders"),
                        0,
                        0,
                        400,
                        300,
                        List.of("web-app"),
                        "Orders"))),
            POLICY);

    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_LAYOUT_REFERENCE_MISSING))
        .hasValueSatisfying(
            reported -> assertThat(reported.message()).contains("pkg-orders", "does not declare"));
    assertThat(cellCarrying(document, "orders-package-boundary").object().attributes())
        .doesNotContainKey(DrawioIdentity.SEMANTIC_SOURCE_ID);
  }

  // ---------------------------------------------------------------- model properties

  /**
   * A Message without {@code properties.uml.sequence} is not a Message: {@code
   * UmlSequenceValidation} rejects it outright. mxGraph has nowhere to put element properties, so
   * the ordering rides the wrapper as a custom attribute — the same mechanism the identity
   * vocabulary uses, and one draw.io preserves through an editing session.
   */
  @Test
  void carriesAMessageOrderingOnTheEdgeWrapper() {
    var document =
        DrawioDocumentBuilder.build(
            umlSequenceSource(),
            layout(
                List.of(laidOut("customer", 0, 0, 10, 10), laidOut("service", 60, 0, 10, 10)),
                List.of(
                    new LaidOutEdge(
                        "m1",
                        "customer",
                        "service",
                        "m1",
                        null,
                        List.of(),
                        List.of(),
                        "placeOrder")),
                List.of()),
            POLICY);

    assertThat(cellCarrying(document, "m1").object().attributes())
        .containsEntry(DrawioIdentity.UML_SEQUENCE, "3");
  }

  /**
   * The other half of the same defect, and the worse one: everything under {@code properties} used
   * to be dropped, in silence at first and then with a warning. It is carried now — on the hidden
   * metadata cell, keyed by element id, in one attribute rather than one per element — and the
   * warning that named the losses has nothing left to name.
   *
   * <p>Keys are sorted, and that is load-bearing rather than tidy: it is what makes the attribute a
   * function of the model's content instead of the order a layout result happened to list its
   * elements in, and therefore what makes {@code export → import → export} byte-identical.
   */
  @Test
  void carriesTheModelPropertiesMxGraphHasNoPlaceForOnTheMetadataCell() {
    var document =
        DrawioDocumentBuilder.build(
            umlSequenceSource(),
            layout(
                List.of(laidOut("customer", 0, 0, 10, 10), laidOut("service", 60, 0, 10, 10)),
                List.of(
                    new LaidOutEdge(
                        "m1",
                        "customer",
                        "service",
                        "m1",
                        null,
                        List.of(),
                        List.of(),
                        "placeOrder")),
                List.of()),
            POLICY);

    String carried =
        metadataCell(document).object().attributes().get(DrawioIdentity.ELEMENT_PROPERTIES);
    assertThat(carried).contains("interaction").contains("message_sort");
    var keys = new java.util.ArrayList<String>();
    JsonSupport.readTree(carried).propertyNames().forEach(keys::add);
    assertThat(keys)
        .describedAs("sorted, so the attribute does not depend on layout-result ordering")
        .isSorted();
    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_PROPERTIES_DROPPED))
        .describedAs("and nothing is left to declare lost")
        .isEmpty();
  }

  /** A model with no element properties says nothing, so the warning keeps its signal. */
  @Test
  void saysNothingAboutPropertiesWhenTheModelCarriesNone() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("orders", "ApplicationComponent")), List.of()),
            layout(List.of(laidOut("orders", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_PROPERTIES_DROPPED)).isEmpty();
  }

  // ---------------------------------------------------------------- view metadata

  /**
   * Layout preferences decide the geometry, so a view that loses them comes back drawn differently:
   * same graph, different picture, and no structural comparison can see it. They ride the hidden
   * metadata cell as the model's own JSON.
   */
  @Test
  void carriesTheViewsLayoutPreferencesOnTheMetadataCell() {
    var document =
        DrawioDocumentBuilder.build(
            sourceWithView(
                """
                {
                  "semantic_profile": "archimate",
                  "views": [{
                    "id": "main", "label": "Main", "kind": "archimate",
                    "layout_preferences": {
                      "direction": "down", "density": "spacious",
                      "routing": { "style": "orthogonal", "endpoint_merging": "off" }
                    }
                  }]
                }
                """),
            layout(List.of(laidOut("orders", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    assertThat(metadataCell(document).object().attributes())
        .hasEntrySatisfying(
            DrawioIdentity.LAYOUT_PREFERENCES,
            json ->
                assertThat(json)
                    .contains("\"direction\":\"down\"")
                    .contains("\"density\":\"spacious\"")
                    .contains("\"endpoint_merging\":\"off\""));
  }

  /**
   * The <em>effective</em> kind and profile, not only the declared ones. A view that leaves {@code
   * kind} implicit still has one — the importer materializes {@code generic} — so omitting it made
   * a second export differ from the first over Dediren's own file.
   */
  @Test
  void writesTheEffectiveViewKindAndProfileWhenTheModelLeavesThemImplicit() {
    var document =
        DrawioDocumentBuilder.build(
            sourceWithView("{ \"views\": [{ \"id\": \"main\", \"label\": \"Main\" }] }"),
            layout(List.of(laidOut("orders", 0, 0, 10, 10)), List.of(), List.of()),
            POLICY);

    assertThat(metadataCell(document).object().attributes())
        .containsEntry(DrawioIdentity.VIEW_KIND, "generic")
        .containsEntry(DrawioIdentity.SEMANTIC_PROFILE, "generic-graph");
  }

  private static SourceDocument sourceWithView(String pluginJson) {
    return new SourceDocument(
        "model.schema.v1",
        List.of(),
        List.of(),
        List.of(node("orders", "ApplicationComponent")),
        List.of(),
        Map.of("generic-graph", JsonSupport.readTree(pluginJson)));
  }

  private static MxCell metadataCell(DrawioDocumentBuilder.Document document) {
    return page(document).cells().stream()
        .filter(cell -> cell.object() != null)
        .filter(
            cell ->
                DrawioIdentity.VIEW_TYPE.equals(
                    cell.object().attributes().get(DrawioIdentity.TYPE)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no dediren.view metadata cell"));
  }

  // ---------------------------------------------------------------- labels

  /**
   * Every cell this export writes is styled {@code html=1}, and an HTML label collapses a newline
   * to a space. A raw newline in an XML attribute does not even survive that far: attribute-value
   * normalization (XML 1.0 §3.3.3) turns it into a space before draw.io ever sees it, so the break
   * was lost from the model too. The importer decodes {@code <br>}; this is the other half.
   */
  @Test
  void encodesALineBreakInALabelAsMarkupDrawioActuallyRenders() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(
                    new SourceNode("ingest", "ApplicationComponent", "Ingest\nGateway", Map.of())),
                List.of()),
            layout(
                List.of(new LaidOutNode("ingest", "ingest", null, 0, 0, 10, 10, "Ingest\nGateway")),
                List.of(),
                List.of()),
            POLICY);

    MxCell cell = cellCarrying(document, "ingest");
    assertThat(cell.object().attributes()).containsEntry("label", "Ingest<br>Gateway");
    assertThat(cell.value()).isEqualTo("Ingest<br>Gateway");
  }

  private static SourceDocument umlSequenceSource() {
    return source(
        List.of(
            new SourceNode(
                "customer",
                "Lifeline",
                "Customer",
                Map.of("uml", JsonSupport.readTree("{\"interaction\": \"i1\"}"))),
            new SourceNode(
                "service",
                "Lifeline",
                "Service",
                Map.of("uml", JsonSupport.readTree("{\"interaction\": \"i1\"}")))),
        List.of(
            new SourceRelationship(
                "m1",
                "Message",
                "customer",
                "service",
                "placeOrder",
                Map.of(
                    "uml",
                    JsonSupport.readTree(
                        "{\"interaction\": \"i1\", \"sequence\": 3, \"message_sort\":"
                            + " \"synchCall\"}")))),
        "uml-sequence");
  }

  // ---------------------------------------------------------------- identity

  @Test
  void keysEdgeEndpointsByDedirenIdSoTheySurviveTheEditorReassigningCellIds() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(
                    node("A node", "ApplicationComponent"), node("B node", "ApplicationService")),
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
            new SourceDocument(
                "model.schema.v1", List.of(), List.of(), List.of(), List.of(), Map.of()),
            layout(List.of(), List.of(), List.of()),
            POLICY);

    assertThat(page(document).cells()).filteredOn(cell -> cell.object() != null).hasSize(1);
  }

  // ---------------------------------------------------------------- disclosure

  @Test
  void warnsAndFallsBackWhenNoDrawioShapeCoversTheElementType() {
    // A type name neither vocabulary declares. This used to be spelled "Class", which stopped
    // being an unmapped type the moment the UML shape table landed — the warning now fires only
    // for a type that is genuinely in neither table, which is what it was always meant to mean.
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("order", "NotARealElementType")), List.of()),
            layout(List.of(laidOut("order", 0, 0, 220, 120)), List.of(), List.of()),
            POLICY);

    MxCell cell = cellCarrying(document, "order");
    assertThat(cell.style()).contains("whiteSpace=wrap").doesNotContain("mxgraph.archimate3");
    // dedirenType still records the exact type, so a re-import is lossless regardless.
    assertThat(cell.object().attributes())
        .containsEntry(DrawioIdentity.TYPE, "NotARealElementType");

    Diagnostic warning = diagnostic(document, DiagnosticCode.DRAWIO_SHAPE_UNMAPPED).orElseThrow();
    assertThat(warning.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(warning.message()).contains("NotARealElementType").contains(DrawioIdentity.TYPE);
  }

  @Test
  void staysSilentAboutShapesForAUmlTypeTheUmlTableNowCovers() {
    // The counterpart to the test above: a Class is drawn deliberately, not fallen back to, so it
    // must not warn. Without this, mapping UML could regress to a silent fallback unnoticed.
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("order", "Class")), List.of(), "uml-class"),
            layout(List.of(laidOut("order", 0, 0, 220, 120)), List.of(), List.of()),
            POLICY);

    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_SHAPE_UNMAPPED)).isEmpty();
    assertThat(cellCarrying(document, "order").object().attributes())
        .containsEntry(DrawioIdentity.TYPE, "Class");
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

    Diagnostic omitted = diagnostic(document, DiagnosticCode.DRAWIO_ORNAMENT_OMITTED).orElseThrow();
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

  // ---------------------------------------------------------------- notation selection

  @Test
  void drawsAUmlViewFromTheUmlShapeTableAndWithoutTheArchimateLayerPalette() {
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("who", "Actor"), node("uc", "UseCase")), List.of(), "uml-use-case"),
            layout(
                List.of(laidOut("who", 0, 0, 30, 60), laidOut("uc", 60, 0, 140, 70)),
                List.of(),
                List.of()),
            POLICY);

    assertThat(cellCarrying(document, "who").style()).isEqualTo(umlShapeStyle("Actor"));
    assertThat(cellCarrying(document, "uc").style()).isEqualTo(umlShapeStyle("UseCase"));
    // The palette is the ArchiMate layer palette; a UML cell has no layer to colour by.
    assertThat(cellCarrying(document, "who").style()).doesNotContain("fillColor=");
  }

  @Test
  void resolvesTheThreeSharedTypeNamesByTheViewsDeclaredKind() {
    // Node is declared by both vocabularies. Nothing about the element itself says which is meant,
    // so the view kind is the whole of the evidence — and getting it wrong draws a deployment node
    // as an ArchiMate technology node.
    var technology =
        DrawioDocumentBuilder.build(
            source(List.of(node("host", "Node")), List.of(), "archimate"),
            layout(List.of(laidOut("host", 0, 0, 150, 75)), List.of(), List.of()),
            POLICY);
    assertThat(cellCarrying(technology, "host").style())
        .startsWith(shapeStyle("Node"))
        .contains("mxgraph.archimate3");

    var deployment =
        DrawioDocumentBuilder.build(
            source(List.of(node("host", "Node")), List.of(), "uml-deployment"),
            layout(List.of(laidOut("host", 0, 0, 140, 80)), List.of(), List.of()),
            POLICY);
    assertThat(cellCarrying(deployment, "host").style())
        .isEqualTo(umlShapeStyle("Node"))
        .contains("shape=cube;");
  }

  @Test
  void drawsATypeOnlyTheOtherVocabularyCoversRatherThanFallingBack() {
    // A generic view carrying UML content still gets UML shapes: only the three shared names need
    // the view kind to disambiguate them.
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("who", "Actor")), List.of(), "generic"),
            layout(List.of(laidOut("who", 0, 0, 30, 60)), List.of(), List.of()),
            POLICY);

    assertThat(cellCarrying(document, "who").style()).isEqualTo(umlShapeStyle("Actor"));
    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_SHAPE_UNMAPPED)).isEmpty();
  }

  // ---------------------------------------------------------------- relationship notation

  @Test
  void drawsEachRelationshipTypeInItsOwnNotation() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(node("a", "ApplicationComponent"), node("b", "ApplicationComponent")),
                List.of(
                    new SourceRelationship("owns", "Composition", "a", "b", null, Map.of()),
                    new SourceRelationship("serves", "Serving", "a", "b", null, Map.of()))),
            layout(
                List.of(laidOut("a", 0, 0, 150, 75), laidOut("b", 300, 0, 150, 75)),
                List.of(
                    new LaidOutEdge("owns", "a", "b", "owns", null, List.of(), List.of(), null),
                    new LaidOutEdge(
                        "serves", "a", "b", "serves", null, List.of(), List.of(), null)),
                List.of()),
            POLICY);

    // The composition diamond belongs at the source end; at the target it states containment the
    // other way round.
    assertThat(cellCarrying(document, "owns").style())
        .contains("startArrow=diamondThin;")
        .contains("startFill=1;")
        .contains("endArrow=none;");
    assertThat(cellCarrying(document, "serves").style()).contains("endArrow=open;");
    // Whatever the notation, the route stays the one the layout computed.
    assertThat(cellCarrying(document, "owns").style()).startsWith("edgeStyle=none;");
  }

  @Test
  void readsRelationshipNotationAgainstTheViewsOwnVocabulary() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(node("base", "Class"), node("derived", "Class")),
                List.of(
                    new SourceRelationship(
                        "isa", "Generalization", "derived", "base", null, Map.of())),
                "uml-class"),
            layout(
                List.of(laidOut("base", 0, 0, 160, 80), laidOut("derived", 300, 0, 160, 80)),
                List.of(
                    new LaidOutEdge(
                        "isa", "derived", "base", "isa", null, List.of(), List.of(), null)),
                List.of()),
            POLICY);

    assertThat(cellCarrying(document, "isa").style())
        .contains("endArrow=block;")
        .contains("endFill=0;");
    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_SHAPE_UNMAPPED)).isEmpty();
  }

  @Test
  void declaresARelationshipTypeItHasNoNotationFor() {
    var document =
        DrawioDocumentBuilder.build(
            source(
                List.of(node("a", "ApplicationComponent"), node("b", "ApplicationComponent")),
                List.of(new SourceRelationship("odd", "Generalization", "a", "b", null, Map.of()))),
            layout(
                List.of(laidOut("a", 0, 0, 150, 75), laidOut("b", 300, 0, 150, 75)),
                List.of(new LaidOutEdge("odd", "a", "b", "odd", null, List.of(), List.of(), null)),
                List.of()),
            POLICY);

    // Generalization is a UML relationship; an ArchiMate view has no notation for it.
    Diagnostic warning = diagnostic(document, DiagnosticCode.DRAWIO_SHAPE_UNMAPPED).orElseThrow();
    assertThat(warning.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(warning.message()).contains("Generalization").contains("odd");
    assertThat(warning.message())
        .as("the message has to say the round trip survives, or it reads as data loss")
        .contains(DrawioIdentity.TYPE)
        .contains("lossless");
    // Degrading, not failing: the edge is still drawn, as a plain directed line.
    assertThat(cellCarrying(document, "odd").style()).contains("endArrow=block;");
  }

  @Test
  void staysSilentAboutOrnamentsForAViewWhoseOnlyBehaviourNodeIsTheInteractionFrame() {
    // The interaction frame is drawn, so a view with nothing else behavioural in it has no
    // ornament to declare. It used to report missing combined-fragment frames on every sequence
    // export, including models with no combined fragment anywhere.
    var document =
        DrawioDocumentBuilder.build(
            source(List.of(node("seq", "Interaction")), List.of(), "uml-sequence"),
            layout(
                List.of(
                    new LaidOutNode(
                        "seq",
                        "seq",
                        null,
                        0,
                        0,
                        400,
                        300,
                        "Place Order",
                        LayoutNodeRole.INTERACTION)),
                List.of(),
                List.of()),
            POLICY);

    assertThat(diagnostic(document, DiagnosticCode.DRAWIO_ORNAMENT_OMITTED)).isEmpty();
    assertThat(cellCarrying(document, "seq").style()).contains("shape=umlFrame;");
  }

  private static String shapeStyle(String elementType) {
    return dev.dediren.plugins.drawio.style.DrawioShapes.shapeFor(elementType).style();
  }

  private static String umlShapeStyle(String elementType) {
    return dev.dediren.plugins.drawio.style.DrawioUmlShapes.shapeFor(elementType).style();
  }
}

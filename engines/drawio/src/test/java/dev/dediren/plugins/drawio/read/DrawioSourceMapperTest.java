package dev.dediren.plugins.drawio.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.plugins.drawio.mx.MxReader;
import dev.dediren.testsupport.TestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the draw.io → {@code SourceDocument} mapper: the two identity paths, what it
 * declines, what it drops with a warning, and what it discards silently.
 */
class DrawioSourceMapperTest {

  // ---------------------------------------------------------------- foreign path: shape

  @Test
  void foreignDocumentAlwaysProducesTheGenericGraphProfile() throws Exception {
    SourceDocument document =
        map(
            page(
                "Architecture",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="a" value="Alpha" style="rounded=1;" vertex="1" parent="1"/>
                <mxCell id="b" value="Beta" style="rounded=1;" vertex="1" parent="1"/>
                <mxCell id="e" value="calls" style="html=1;" edge="1" parent="1" source="a" target="b"/>
                """))
            .document();

    assertThat(document.modelSchemaVersion()).isEqualTo("model.schema.v1");
    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::type, SourceNode::label)
        .containsExactly(tuple("a", "generic.node", "Alpha"), tuple("b", "generic.node", "Beta"));
    assertThat(document.relationships())
        .extracting(
            SourceRelationship::id,
            SourceRelationship::type,
            SourceRelationship::source,
            SourceRelationship::target,
            SourceRelationship::label)
        .containsExactly(tuple("e", "generic.link", "a", "b", "calls"));
    assertThat(plugin(document).semanticProfile())
        .isEqualTo(GenericGraphSemanticProfile.GENERIC_GRAPH);
    assertThat(views(document))
        .extracting(GenericGraphView::id, GenericGraphView::label, GenericGraphView::kind)
        .containsExactly(tuple("main", "Architecture", GenericGraphViewKind.GENERIC));
  }

  @Test
  void firstPageIsAlwaysMainAndNoLaterPageMayTakeIt() throws Exception {
    SourceDocument document =
        map(
            document(
                page("main", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"),
                page("main", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"),
                page(null, "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>")))
            .document();

    assertThat(views(document)).extracting(GenericGraphView::id).containsExactly("main", "main-2",
        "page-3");
  }

  @Test
  void cellIdsAreNormalisedAndStayUniqueAcrossPages() throws Exception {
    SourceDocument document =
        map(
            document(
                page("One", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                    + "<mxCell id=\"shared\" value=\"A\" vertex=\"1\" parent=\"1\"/>"),
                page("Two", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                    + "<mxCell id=\"shared\" value=\"B\" vertex=\"1\" parent=\"1\"/>"
                    + "<mxCell id=\"bad id\" value=\"C\" vertex=\"1\" parent=\"1\"/>")))
            .document();

    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::label)
        .containsExactly(tuple("shared", "A"), tuple("shared-2", "B"), tuple("bad-id", "C"));
    assertThat(document.nodes().get(1).properties().get("drawio").get("original_id").asString())
        .isEqualTo("shared");
  }

  @Test
  void aStrayDedirenTypeInAForeignFileSuppliesAnIdButNotAType() throws Exception {
    SourceDocument document =
        map(
            page(
                "Foreign",
                "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                    + "<object id=\"c1\" label=\"Ledger\" dedirenId=\"svc.ledger\""
                    + " dedirenType=\"ApplicationComponent\">"
                    + "<mxCell style=\"rounded=0;\" vertex=\"1\" parent=\"1\"/></object>"))
            .document();

    // No dediren.view metadata cell means no declared profile, so an ArchiMate-looking type has
    // nothing to be valid under; the id is profile-neutral and still stands.
    assertThat(plugin(document).semanticProfile())
        .isEqualTo(GenericGraphSemanticProfile.GENERIC_GRAPH);
    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::type)
        .containsExactly(tuple("svc.ledger", "generic.node"));
  }

  // ---------------------------------------------------------------- containment

  @Test
  void nestedContainersBecomeNestedGroupsRatherThanBeingFlattened() throws Exception {
    SourceDocument document =
        map(
            page(
                "Nested",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="outer" value="Outer" vertex="1" parent="1"/>
                <mxCell id="middle" value="Middle" vertex="1" parent="outer"/>
                <mxCell id="leaf" value="Leaf" vertex="1" parent="middle"/>
                <mxCell id="sibling" value="Sibling" vertex="1" parent="outer"/>
                """))
            .document();

    // Only the childless vertices are nodes; the two containers are groups.
    assertThat(document.nodes()).extracting(SourceNode::id).containsExactly("leaf", "sibling");
    assertThat(groups(document))
        .extracting(GenericGraphViewGroup::id, GenericGraphViewGroup::members)
        .containsExactly(
            tuple("outer", List.of("middle", "sibling")), tuple("middle", List.of("leaf")));
    assertThat(groups(document))
        .extracting(GenericGraphViewGroup::role)
        .containsOnly(GenericGraphViewGroupRole.LAYOUT_ONLY);
  }

  @Test
  void aSwimlaneIsAContainerBecauseOfItsChildrenNotItsStyle() throws Exception {
    SourceDocument document =
        map(
            page(
                "Pools",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="pool" value="Pool" style="swimlane;html=1;" vertex="1" parent="1"/>
                <mxCell id="task" value="Task" style="swimlane;html=1;" vertex="1" parent="pool"/>
                """))
            .document();

    // The empty swimlane child is a node despite its container-ish style; the populated one is a
    // group despite the identical style.
    assertThat(document.nodes()).extracting(SourceNode::id).containsExactly("task");
    assertThat(groups(document)).extracting(GenericGraphViewGroup::id).containsExactly("pool");
  }

  // ---------------------------------------------------------------- layers and hidden cells

  @Test
  void layersAreFlattenedIntoOnePageAndNamedInAWarning() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(
            page(
                "Layered",
                """
                <mxCell id="0" />
                <mxCell id="1" value="Background" parent="0" />
                <mxCell id="2" value="Foreground" parent="0" />
                <mxCell id="a" value="Alpha" vertex="1" parent="1"/>
                <mxCell id="b" value="Beta" vertex="1" parent="2"/>
                """));

    // Both layers' cells land side by side in the one view, with no group fabricated for either.
    assertThat(result.document().nodes()).extracting(SourceNode::id).containsExactly("a", "b");
    assertThat(groups(result.document())).isEmpty();
    Diagnostic flattened = diagnostic(result, DiagnosticCode.DRAWIO_LAYERS_FLATTENED);
    assertThat(flattened.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(flattened.message()).contains("Background").contains("Foreground");
  }

  @Test
  void aSingleDefaultLayerIsNotReportedAsFlattening() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(page("Plain", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
            + "<mxCell id=\"a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"));

    assertThat(codes(result)).doesNotContain(DiagnosticCode.DRAWIO_LAYERS_FLATTENED.code());
  }

  @Test
  void hiddenCellsAndHiddenLayersAreSkippedAndCounted() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(
            page(
                "Hidden",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="2" parent="0" visible="0" />
                <mxCell id="shown" value="Shown" vertex="1" parent="1"/>
                <mxCell id="draft" value="Draft" vertex="1" parent="1" visible="0"/>
                <mxCell id="child-of-draft" value="Child" vertex="1" parent="draft"/>
                <mxCell id="on-hidden-layer" value="Layered" vertex="1" parent="2"/>
                """));

    assertThat(result.document().nodes()).extracting(SourceNode::id).containsExactly("shown");
    Diagnostic skipped = diagnostic(result, DiagnosticCode.DRAWIO_CELLS_SKIPPED);
    assertThat(skipped.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(skipped.message())
        .contains("draft")
        .contains("child-of-draft")
        .contains("on-hidden-layer");
  }

  @Test
  void aContainerWhoseChildrenAreAllHiddenBecomesANodeNotAnEmptyGroup() throws Exception {
    SourceDocument document =
        map(
            page(
                "Emptied",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="box" value="Box" vertex="1" parent="1"/>
                <mxCell id="ghost" value="Ghost" vertex="1" parent="box" visible="0"/>
                """))
            .document();

    assertThat(document.nodes()).extracting(SourceNode::id).containsExactly("box");
    assertThat(groups(document)).isEmpty();
  }

  // ---------------------------------------------------------------- labels

  @Test
  void everyBrSpellingBecomesANewlineAndNothingElseDoes() throws Exception {
    SourceDocument document =
        map(
            page(
                "Labels",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="a" value="one&lt;br&gt;two&lt;br/&gt;three&lt;br /&gt;four&lt;BR&gt;five" style="html=1;" vertex="1" parent="1"/>
                <mxCell id="b" value="kept &lt;brx&gt; literal" vertex="1" parent="1"/>
                """))
            .document();

    assertThat(document.nodes().get(0).label()).isEqualTo("one\ntwo\nthree\nfour\nfive");
    // html=1 is absent on b, so the non-<br> angle brackets are plain text and survive verbatim.
    assertThat(document.nodes().get(1).label()).isEqualTo("kept <brx> literal");
  }

  @Test
  void anEdgeLabelChildSuppliesTheLabelOnlyWhenTheEdgeHasNone() throws Exception {
    SourceDocument document =
        map(
            page(
                "Edges",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="a" value="A" vertex="1" parent="1"/>
                <mxCell id="b" value="B" vertex="1" parent="1"/>
                <mxCell id="bare" edge="1" parent="1" source="a" target="b"/>
                <mxCell id="bare-label" value="from child" style="edgeLabel;html=1;" vertex="1" parent="bare"/>
                <mxCell id="titled" value="from edge" edge="1" parent="1" source="a" target="b"/>
                <mxCell id="titled-label" value="ignored" style="edgeLabel;html=1;" vertex="1" parent="titled"/>
                """))
            .document();

    // Neither label cell became a node.
    assertThat(document.nodes()).extracting(SourceNode::id).containsExactly("a", "b");
    assertThat(document.relationships())
        .extracting(SourceRelationship::id, SourceRelationship::label)
        .containsExactly(tuple("bare", "from child"), tuple("titled", "from edge"));
  }

  // ---------------------------------------------------------------- dangling edges

  @Test
  void everyUnresolvableEdgeIsDroppedWithAWarningRatherThanFailingTheImport() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(
            page(
                "Dangling",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="a" value="A" vertex="1" parent="1"/>
                <mxCell id="box" value="Box" vertex="1" parent="1"/>
                <mxCell id="inner" value="Inner" vertex="1" parent="box"/>
                <mxCell id="good" edge="1" parent="1" source="a" target="inner"/>
                <mxCell id="no-target" edge="1" parent="1" source="a"/>
                <mxCell id="ghost-target" edge="1" parent="1" source="a" target="nobody"/>
                <mxCell id="to-group" edge="1" parent="1" source="a" target="box"/>
                <mxCell id="to-edge" edge="1" parent="1" source="a" target="good"/>
                <mxCell id="floating" edge="1" parent="1"/>
                """));

    assertThat(result.document().relationships())
        .extracting(SourceRelationship::id)
        .containsExactly("good");
    Diagnostic skipped = diagnostic(result, DiagnosticCode.DRAWIO_CELLS_SKIPPED);
    assertThat(skipped.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(skipped.message())
        .contains("no-target")
        .contains("ghost-target")
        .contains("to-group")
        .contains("to-edge")
        .contains("floating");
  }

  // ---------------------------------------------------------------- declined constructs

  @Test
  void declinesAnImageShape() {
    assertDeclined(
        "<mxCell id=\"a\" style=\"shape=image;html=1;\" vertex=\"1\" parent=\"1\"/>", "image");
  }

  @Test
  void declinesAnImageUrl() {
    assertDeclined(
        "<mxCell id=\"a\" style=\"html=1;image=https://example.invalid/logo.png;\""
            + " vertex=\"1\" parent=\"1\"/>",
        "image");
  }

  @Test
  void declinesAnImageBackgroundUrl() {
    assertDeclined(
        "<mxCell id=\"a\" style=\"html=1;imageBackground=https://example.invalid/bg.png;\""
            + " vertex=\"1\" parent=\"1\"/>",
        "imageBackground");
  }

  @Test
  void declinesLabelHtmlBeyondTheBrFamily() {
    assertDeclined(
        "<mxCell id=\"a\" value=\"&lt;b&gt;bold&lt;/b&gt;\" style=\"html=1;\""
            + " vertex=\"1\" parent=\"1\"/>",
        "HTML");
  }

  @Test
  void declinesALinkAttributeOnAWrapper() {
    assertDeclined(
        "<UserObject label=\"Docs\" link=\"https://example.invalid/\" id=\"a\">"
            + "<mxCell style=\"rounded=1;\" vertex=\"1\" parent=\"1\"/></UserObject>",
        "link");
  }

  @Test
  void declinesAnEmbeddedCustomStencilPayload() {
    assertDeclined(
        "<mxCell id=\"a\" style=\"shape=stencil(vVPBaoQwEP2aXAuaWuixat1Ll0IFz1kzq6E"
            + "xkWRc7d83asRdKAt7KZ2BzHszb17yhrC0GnpplTQ96oGwd8LSzhi8VLbPQCliaW0FYUXE2C4qHul2Z43"
            + "1uJfPFvtHFhbCkNiRgUyPRPWkOBBSPtA9wUOhk1BqoJc);\" vertex=\"1\" parent=\"1\"/>",
        "stencil");
  }

  @Test
  void declinesANonEdgeLabelVertexParentedToAnEdge() {
    assertDeclined(
        "<mxCell id=\"a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"
            + "<mxCell id=\"b\" value=\"B\" vertex=\"1\" parent=\"1\"/>"
            + "<mxCell id=\"wire\" edge=\"1\" parent=\"1\" source=\"a\" target=\"b\"/>"
            + "<mxCell id=\"rider\" value=\"R\" style=\"rounded=1;\" vertex=\"1\" parent=\"wire\"/>",
        "rider");
  }

  @Test
  void aHiddenCellIsNotInspectedForDeclinedConstructs() throws Exception {
    // A hidden cell is not in the model at all, so there is nothing in it to refuse.
    SourceDocument document =
        map(
            page(
                "HiddenImage",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="a" value="A" vertex="1" parent="1"/>
                <mxCell id="logo" style="shape=image;image=https://example.invalid/x.png;" vertex="1" parent="1" visible="0"/>
                """))
            .document();

    assertThat(document.nodes()).extracting(SourceNode::id).containsExactly("a");
  }

  // ---------------------------------------------------------------- discarded hints

  @Test
  void discardedGeometryStyleAndRoutingKeysAreNamedWithCounts() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(
            page(
                "Hints",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <object id="a" label="A" tooltip="hover text">
                  <mxCell style="rounded=1;fillColor=#dae8fc;" vertex="1" parent="1">
                    <mxGeometry x="10" y="20" width="120" height="60" as="geometry" />
                  </mxCell>
                </object>
                <mxCell id="b" value="B" style="rounded=1;fillColor=#d5e8d4;" vertex="1" parent="1">
                  <mxGeometry x="200" y="20" width="120" height="60" as="geometry" />
                </mxCell>
                <mxCell id="e" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b">
                  <mxGeometry relative="1" as="geometry">
                    <Array as="points"><mxPoint x="180" y="50" /></Array>
                  </mxGeometry>
                </mxCell>
                """));

    Diagnostic ignored = diagnostic(result, DiagnosticCode.DRAWIO_HINT_IGNORED);
    assertThat(ignored.severity()).isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(ignored.message())
        .contains("rounded (2)")
        .contains("fillColor (2)")
        .contains("edgeStyle (1)")
        .contains("tooltip (1)")
        .contains("mxGeometry (3)")
        .contains("waypoints (1)");
    // Discarding geometry is contractual: sourceNode has no x/y/width/height to carry it.
    assertThat(result.document().nodes().get(0).properties()).doesNotContainKey("geometry");
  }

  @Test
  void aDocumentWithNothingToDiscardCarriesNoHintDiagnostic() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(page("Clean", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
            + "<mxCell id=\"a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"));

    assertThat(codes(result)).doesNotContain(DiagnosticCode.DRAWIO_HINT_IGNORED.code());
  }

  // ---------------------------------------------------------------- stencil suggestions

  @Test
  void recognisedStencilsAreRecordedAndSuggestedButNeverPromoted() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(MxReader.read(fixture("fixtures/drawio/archimate-stencil-evidence.drawio")));

    // The profile stays generic-graph and every element keeps a generic type.
    assertThat(plugin(result.document()).semanticProfile())
        .isEqualTo(GenericGraphSemanticProfile.GENERIC_GRAPH);
    assertThat(result.document().nodes())
        .extracting(SourceNode::type)
        .containsOnly("generic.node");

    SourceNode goal =
        result.document().nodes().stream()
            .filter(node -> node.id().endsWith("-3"))
            .findFirst()
            .orElseThrow();
    assertThat(goal.properties().get("drawio").get("stencil").get("style").asString())
        .isEqualTo("mxgraph.archimate3.goal");
    assertThat(goal.properties().get("drawio").get("stencil").get("suggested_type").asString())
        .isEqualTo("Goal");

    Diagnostic inferred = diagnostic(result, DiagnosticCode.DRAWIO_KIND_INFERRED);
    assertThat(inferred.severity()).isEqualTo(DiagnosticSeverity.INFO);
    assertThat(inferred.message()).contains("semantic_profile").contains("archimate");
  }

  @Test
  void anAmbiguousStencilIsRecordedWithoutASuggestion() throws Exception {
    SourceDocument document =
        map(
            page(
                "Ambiguous",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="svc" value="Service" style="html=1;shape=mxgraph.archimate3.application;appType=serv;archiType=rounded;" vertex="1" parent="1"/>
                """))
            .document();

    // appType=serv is shared by the business, application, and technology service types, so no
    // suggestion can be made without guessing the layer.
    var stencil = document.nodes().get(0).properties().get("drawio").get("stencil");
    assertThat(stencil.get("style").asString())
        .isEqualTo("mxgraph.archimate3.application;appType=serv;archiType=rounded");
    assertThat(stencil.has("suggested_type")).isFalse();
  }

  @Test
  void aDocumentWithNoRecognisedStencilCarriesNoKindDiagnostic() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(page("Plain", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
            + "<mxCell id=\"a\" value=\"A\" style=\"rounded=1;\" vertex=\"1\" parent=\"1\"/>"));

    assertThat(codes(result)).doesNotContain(DiagnosticCode.DRAWIO_KIND_INFERRED.code());
  }

  // ---------------------------------------------------------------- round trip

  @Test
  void aRoundTrippedFileRecoversIdsTypesGroupsAndEndpointsVerbatim() throws Exception {
    DrawioSourceMapper.MappingResult result =
        map(
            page(
                "Ledger",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <object id="meta" dedirenType="dediren.view" dedirenViewId="ledger" dedirenViewKind="archimate" dedirenSemanticProfile="archimate" dedirenModelSchemaVersion="model.schema.v1">
                  <mxCell style="text;html=1;" vertex="1" parent="1" visible="0"/>
                </object>
                <object id="c1" label="Zone" dedirenId="zone.trusted" dedirenType="dediren.group" dedirenGroupRole="semantic" dedirenSemanticSourceId="svc.ledger">
                  <mxCell style="rounded=0;" vertex="1" parent="1"/>
                </object>
                <object id="c2" label="Ledger" dedirenId="svc.ledger" dedirenType="ApplicationComponent">
                  <mxCell style="rounded=0;" vertex="1" parent="c1"/>
                </object>
                <object id="c3" label="Gateway" dedirenId="svc.gateway" dedirenType="ApplicationComponent">
                  <mxCell style="rounded=0;" vertex="1" parent="1"/>
                </object>
                <object id="c4" label="posts" dedirenId="rel.posts" dedirenType="Serving" dedirenSource="svc.gateway" dedirenTarget="svc.ledger">
                  <mxCell style="html=1;" edge="1" parent="1" source="c3" target="c2"/>
                </object>
                """));

    SourceDocument document = result.document();
    assertThat(plugin(document).semanticProfile()).isEqualTo(GenericGraphSemanticProfile.ARCHIMATE);
    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::type, SourceNode::label)
        .containsExactly(
            tuple("svc.ledger", "ApplicationComponent", "Ledger"),
            tuple("svc.gateway", "ApplicationComponent", "Gateway"));
    assertThat(document.relationships())
        .extracting(
            SourceRelationship::id,
            SourceRelationship::type,
            SourceRelationship::source,
            SourceRelationship::target)
        .containsExactly(tuple("rel.posts", "Serving", "svc.gateway", "svc.ledger"));

    GenericGraphView view = views(document).get(0);
    assertThat(view.id()).isEqualTo("ledger");
    assertThat(view.kind()).isEqualTo(GenericGraphViewKind.ARCHIMATE);
    assertThat(view.groups())
        .extracting(
            GenericGraphViewGroup::id,
            GenericGraphViewGroup::role,
            GenericGraphViewGroup::semanticSourceId)
        .containsExactly(
            tuple(
                "zone.trusted",
                GenericGraphViewGroupRole.SEMANTIC_BOUNDARY,
                "svc.ledger"));
    // The hidden metadata cell is consumed, never emitted, and never counted as a skipped cell.
    assertThat(codes(result)).doesNotContain(DiagnosticCode.DRAWIO_CELLS_SKIPPED.code());
  }

  @Test
  void theExportLanesOwnGoldenArtifactReImportsToTheModelItWasWrittenFrom() throws Exception {
    // The one end-to-end proof that the two halves of the draw.io lane agree. Its input is the
    // export lane's committed golden, so a failure here is a divergence between DrawioIdentity's
    // producer (engines/drawio/.../write) and this consumer, not a defect in either alone.
    DrawioSourceMapper.MappingResult result =
        map(fixture("engines/drawio/src/test/resources/golden/archimate-basic.drawio"));

    SourceDocument document = result.document();
    assertThat(plugin(document).semanticProfile()).isEqualTo(GenericGraphSemanticProfile.ARCHIMATE);
    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::type, SourceNode::label)
        .containsExactly(
            tuple("orders-component", "ApplicationComponent", "Orders Component"),
            tuple("orders-service", "ApplicationService", "Orders Service"));
    assertThat(document.relationships())
        .extracting(
            SourceRelationship::id,
            SourceRelationship::type,
            SourceRelationship::source,
            SourceRelationship::target)
        .containsExactly(
            tuple(
                "orders-realizes-service", "Realization", "orders-component", "orders-service"));
    assertThat(views(document))
        .extracting(GenericGraphView::id, GenericGraphView::kind)
        .containsExactly(tuple("main", GenericGraphViewKind.ARCHIMATE));
    // Nothing was skipped or dropped: a round trip loses only appearance.
    assertThat(codes(result))
        .containsExactly(DiagnosticCode.DRAWIO_HINT_IGNORED.code());
  }

  @Test
  void anEndpointNamingASemanticBoundaryGroupRetargetsToItsSourceNode() throws Exception {
    SourceDocument document =
        map(
            page(
                "Boundary",
                """
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <object id="meta" dedirenType="dediren.view" dedirenViewId="main" dedirenViewKind="archimate" dedirenSemanticProfile="archimate" dedirenModelSchemaVersion="model.schema.v1">
                  <mxCell style="text;html=1;" vertex="1" parent="1" visible="0"/>
                </object>
                <object id="c1" label="Zone" dedirenId="zone" dedirenType="dediren.group" dedirenGroupRole="semantic" dedirenSemanticSourceId="svc.ledger">
                  <mxCell style="rounded=0;" vertex="1" parent="1"/>
                </object>
                <object id="c2" label="Ledger" dedirenId="svc.ledger" dedirenType="ApplicationComponent">
                  <mxCell style="rounded=0;" vertex="1" parent="c1"/>
                </object>
                <object id="c3" label="Gateway" dedirenId="svc.gateway" dedirenType="ApplicationComponent">
                  <mxCell style="rounded=0;" vertex="1" parent="1"/>
                </object>
                <object id="c4" label="serves" dedirenId="rel.serves" dedirenType="Serving" dedirenSource="svc.gateway" dedirenTarget="zone">
                  <mxCell style="html=1;" edge="1" parent="1" source="c3" target="c1"/>
                </object>
                """))
            .document();

    assertThat(document.relationships())
        .extracting(SourceRelationship::source, SourceRelationship::target)
        .containsExactly(tuple("svc.gateway", "svc.ledger"));
  }

  @Test
  void oneElementOnTwoRoundTrippedPagesBecomesOneNodeInTwoViews() throws Exception {
    SourceDocument document =
        map(
            document(
                roundTripPage("One", "one", "generic", "svc.ledger", "Ledger"),
                roundTripPage("Two", "two", "generic", "svc.ledger", "Ledger")))
            .document();

    assertThat(document.nodes()).extracting(SourceNode::id).containsExactly("svc.ledger");
    assertThat(views(document))
        .extracting(GenericGraphView::id, GenericGraphView::nodes)
        .containsExactly(tuple("one", List.of("svc.ledger")), tuple("two", List.of("svc.ledger")));
  }

  @Test
  void aRoundTrippedContainerThatLostItsChildrenDoesNotInheritTheGroupMarkerAsAType()
      throws Exception {
    SourceDocument document =
        map(
            page(
                "Emptied",
                "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                    + metadataCell("main", "generic", "generic-graph")
                    + "<object id=\"c1\" label=\"Zone\" dedirenId=\"zone\""
                    + " dedirenType=\"dediren.group\" dedirenGroupRole=\"visual\">"
                    + "<mxCell style=\"rounded=0;\" vertex=\"1\" parent=\"1\"/></object>"
                    + "<object id=\"c2\" label=\"Gone\" dedirenId=\"svc.gone\""
                    + " dedirenType=\"ApplicationComponent\">"
                    + "<mxCell style=\"rounded=0;\" vertex=\"1\" parent=\"c1\""
                    + " visible=\"0\"/></object>"))
            .document();

    // dediren.group marks a container, not a model type, so the emptied container falls back.
    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::type)
        .containsExactly(tuple("zone", "generic.node"));
    assertThat(groups(document)).isEmpty();
  }

  @Test
  void rejectsARoundTripIdThatViolatesThePublishedIdPattern() {
    assertRoundTripInvalid(
        roundTripCell("c1", "not a valid id", "ApplicationComponent", "Ledger"),
        "not a valid id");
  }

  @Test
  void rejectsADuplicateDedirenIdOnOnePage() {
    assertRoundTripInvalid(
        roundTripCell("c1", "svc.ledger", "ApplicationComponent", "Ledger")
            + roundTripCell("c2", "svc.ledger", "ApplicationComponent", "Ledger"),
        "twice");
  }

  @Test
  void rejectsConflictingTypeForOneIdAcrossPages() throws Exception {
    String source =
        document(
            roundTripPage("One", "one", "generic", "svc.ledger", "Ledger"),
            roundTripPage("Two", "two", "generic", "svc.ledger", "Ledger")
                .replace("dedirenType=\"ApplicationComponent\"", "dedirenType=\"Node\""));

    assertThatThrownBy(() -> DrawioSourceMapper.map(MxReader.read(source)))
        .isInstanceOf(EngineException.class)
        .satisfies(error -> assertThat(code((EngineException) error))
            .isEqualTo(DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code()));
  }

  @Test
  void rejectsAnEndpointNamingNoDedirenId() {
    assertRoundTripInvalid(
        roundTripCell("c1", "svc.ledger", "ApplicationComponent", "Ledger")
            + "<object id=\"e\" label=\"x\" dedirenId=\"rel.x\" dedirenType=\"Serving\""
            + " dedirenSource=\"svc.ledger\" dedirenTarget=\"svc.nobody\">"
            + "<mxCell style=\"html=1;\" edge=\"1\" parent=\"1\"/></object>",
        "svc.nobody");
  }

  @Test
  void rejectsAnUnknownSemanticProfile() {
    assertThatThrownBy(
            () ->
                DrawioSourceMapper.map(
                    MxReader.read(
                        page(
                            "Bad",
                            "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                                + metadataCell("main", "generic", "sysml")))))
        .isInstanceOf(EngineException.class)
        .satisfies(error -> assertThat(code((EngineException) error))
            .isEqualTo(DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code()));
  }

  @Test
  void rejectsAnUnknownViewKind() {
    assertThatThrownBy(
            () ->
                DrawioSourceMapper.map(
                    MxReader.read(
                        page(
                            "Bad",
                            "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                                + metadataCell("main", "uml-fishbone", "uml")))))
        .isInstanceOf(EngineException.class)
        .satisfies(error -> assertThat(code((EngineException) error))
            .isEqualTo(DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code()));
  }

  @Test
  void aHandAddedCellOnARoundTrippedPageStillImportsWithASynthesisedId() throws Exception {
    SourceDocument document =
        map(
            page(
                "Mixed",
                "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                    + metadataCell("main", "generic", "generic-graph")
                    + roundTripCell("c1", "svc.ledger", "ApplicationComponent", "Ledger")
                    + "<mxCell id=\"scratch\" value=\"Scratch\" vertex=\"1\" parent=\"1\"/>"))
            .document();

    assertThat(document.nodes())
        .extracting(SourceNode::id, SourceNode::type)
        .containsExactly(
            tuple("svc.ledger", "ApplicationComponent"), tuple("scratch", "generic.node"));
  }

  // ---------------------------------------------------------------- ceilings

  @Test
  void refusesMoreProducedElementsThanTheImporterCeilingAllows() throws Exception {
    StringBuilder cells = new StringBuilder("<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>");
    for (int index = 0; index < 4; index++) {
      cells
          .append("<mxCell id=\"n")
          .append(index)
          .append("\" value=\"n\" vertex=\"1\" parent=\"1\"/>");
    }
    var file = MxReader.read(page("Big", cells.toString()));

    assertThat(DrawioSourceMapper.map(file, 4).document().nodes()).hasSize(4);
    assertThatThrownBy(() -> DrawioSourceMapper.map(file, 3))
        .isInstanceOf(EngineException.class)
        .satisfies(error -> assertThat(code((EngineException) error))
            .isEqualTo(DiagnosticCode.DRAWIO_ELEMENT_LIMIT_EXCEEDED.code()));
  }

  @Test
  void groupsCountAgainstTheProducedElementCeiling() throws Exception {
    var file =
        MxReader.read(
            page(
                "Grouped",
                "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                    + "<mxCell id=\"box\" value=\"Box\" vertex=\"1\" parent=\"1\"/>"
                    + "<mxCell id=\"leaf\" value=\"Leaf\" vertex=\"1\" parent=\"box\"/>"));

    // One node plus one group is two produced elements, not one.
    assertThatThrownBy(() -> DrawioSourceMapper.map(file, 1))
        .isInstanceOf(EngineException.class)
        .satisfies(error -> assertThat(code((EngineException) error))
            .isEqualTo(DiagnosticCode.DRAWIO_ELEMENT_LIMIT_EXCEEDED.code()));
  }

  // ---------------------------------------------------------------- helpers

  private static DrawioSourceMapper.MappingResult map(String source) throws EngineException {
    return DrawioSourceMapper.map(MxReader.read(source));
  }

  private static DrawioSourceMapper.MappingResult map(dev.dediren.plugins.drawio.mx.MxFile file)
      throws EngineException {
    return DrawioSourceMapper.map(file);
  }

  private static void assertDeclined(String cells, String messageFragment) {
    String source =
        page("Refused", "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>" + cells);
    assertThatThrownBy(() -> DrawioSourceMapper.map(MxReader.read(source)))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException failure = (EngineException) error;
              assertThat(code(failure))
                  .isEqualTo(DiagnosticCode.DRAWIO_UNSUPPORTED_CONSTRUCT.code());
              assertThat(failure.exitCode()).isEqualTo(2);
              assertThat(failure.diagnostics().get(0).message()).contains(messageFragment);
            });
  }

  private static void assertRoundTripInvalid(String cells, String messageFragment) {
    String source =
        page(
            "RoundTrip",
            "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + metadataCell("main", "generic", "generic-graph")
                + cells);
    assertThatThrownBy(() -> DrawioSourceMapper.map(MxReader.read(source)))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException failure = (EngineException) error;
              assertThat(code(failure)).isEqualTo(DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code());
              assertThat(failure.exitCode()).isEqualTo(2);
              assertThat(failure.diagnostics().get(0).message()).contains(messageFragment);
            });
  }

  private static String code(EngineException failure) {
    return failure.diagnostics().get(0).code();
  }

  private static String metadataCell(String viewId, String viewKind, String profile) {
    return "<object id=\"meta\" dedirenType=\"dediren.view\" dedirenViewId=\""
        + viewId
        + "\" dedirenViewKind=\""
        + viewKind
        + "\" dedirenSemanticProfile=\""
        + profile
        + "\" dedirenModelSchemaVersion=\"model.schema.v1\">"
        + "<mxCell style=\"text;html=1;\" vertex=\"1\" parent=\"1\" visible=\"0\"/></object>";
  }

  private static String roundTripCell(
      String cellId, String dedirenId, String type, String label) {
    return "<object id=\""
        + cellId
        + "\" label=\""
        + label
        + "\" dedirenId=\""
        + dedirenId
        + "\" dedirenType=\""
        + type
        + "\"><mxCell style=\"rounded=0;\" vertex=\"1\" parent=\"1\"/></object>";
  }

  private static String roundTripPage(
      String pageName, String viewId, String viewKind, String dedirenId, String label) {
    return page(
        pageName,
        "<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
            + metadataCell(viewId, viewKind, "generic-graph")
            + roundTripCell("c1", dedirenId, "ApplicationComponent", label));
  }

  private static String page(String name, String cells) {
    return document(pageElement(name, cells));
  }

  private static String pageElement(String name, String cells) {
    return "<diagram id=\"p-"
        + (name == null ? "unnamed" : name)
        + "\""
        + (name == null ? "" : " name=\"" + name + "\"")
        + "><mxGraphModel><root>"
        + cells
        + "</root></mxGraphModel></diagram>";
  }

  private static String document(String... pages) {
    StringBuilder document = new StringBuilder("<mxfile host=\"app.diagrams.net\">");
    for (String page : pages) {
      document.append(page.startsWith("<mxfile") ? unwrap(page) : page);
    }
    return document.append("</mxfile>").toString();
  }

  private static String unwrap(String singlePageDocument) {
    int start = singlePageDocument.indexOf('>') + 1;
    return singlePageDocument.substring(start, singlePageDocument.lastIndexOf("</mxfile>"));
  }

  private static GenericGraphPluginData plugin(SourceDocument document) {
    return JsonSupport.objectMapper()
        .treeToValue(document.plugins().get("generic-graph"), GenericGraphPluginData.class);
  }

  private static List<GenericGraphView> views(SourceDocument document) {
    return plugin(document).views();
  }

  private static List<GenericGraphViewGroup> groups(SourceDocument document) {
    return views(document).get(0).groups();
  }

  private static Diagnostic diagnostic(
      DrawioSourceMapper.MappingResult result, DiagnosticCode code) {
    Optional<Diagnostic> found =
        result.diagnostics().stream()
            .filter(diagnostic -> diagnostic.code().equals(code.code()))
            .findFirst();
    assertThat(found).as("diagnostic %s", code.code()).isPresent();
    return found.orElseThrow();
  }

  private static List<String> codes(DrawioSourceMapper.MappingResult result) {
    return result.diagnostics().stream().map(Diagnostic::code).toList();
  }

  private static String fixture(String relativePath) throws IOException {
    return Files.readString(TestSupport.workspaceRoot().resolve(relativePath));
  }
}

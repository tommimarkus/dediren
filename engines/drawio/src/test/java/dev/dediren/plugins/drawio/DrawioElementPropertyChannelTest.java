package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.plugins.drawio.mx.MxCell;
import dev.dediren.plugins.drawio.mx.MxReader;
import dev.dediren.plugins.drawio.write.DrawioIdentity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The element-property channel: a per-element {@code properties} map carried on the hidden {@code
 * dediren.view} metadata cell, keyed by element id.
 *
 * <h2>Why the metadata cell and not the element's own wrapper</h2>
 *
 * <p>mxGraph has one extension point, the {@code <object>} wrapper's attribute set, and draw.io
 * shows a wrapper's attributes to a human in its Edit Data dialog. Putting a model's property tree
 * on the element wrapper would therefore put raw model JSON in front of anyone who right-clicks a
 * shape, which is why a general {@code dedirenProperties} attribute was declined. The hidden
 * metadata cell has the same round-trip guarantee — draw.io preserves unknown {@code <object>}
 * attributes verbatim — and no editing surface, and {@link DrawioIdentity#LAYOUT_PREFERENCES}
 * already proved it carries the model's own JSON through an editing session. This is that mechanism
 * applied to the one remaining lossy channel.
 *
 * <h2>What it closes</h2>
 *
 * <p>Six of the repository's layout fixtures re-imported into a model {@code project} rejects,
 * because a required UML ownership property ({@code Port.component}, {@code
 * ExtensionPoint.use_case}, {@code Transition.region}, {@code ExecutionSpecification.covered}) had
 * nowhere to ride; two more stayed valid and moved, because the notation layer sizes a Class from
 * {@code uml.attributes}/{@code uml.operations}. Those are one defect seen twice, and this is its
 * fix.
 */
class DrawioElementPropertyChannelTest {

  private final DrawioExportEngine exporter = new DrawioExportEngine();
  private final DrawioImportEngine importer = new DrawioImportEngine();

  private static final JsonNode POLICY =
      JsonSupport.readTree(
          """
          {
            "drawio_export_policy_schema_version": "%s",
            "diagram_name": "Main"
          }
          """
              .formatted(ContractVersions.DRAWIO_EXPORT_POLICY_SCHEMA_VERSION));

  private static SourceNode node(String id, String type, String umlProperties) {
    return new SourceNode(
        id,
        type,
        id,
        umlProperties == null ? Map.of() : Map.of("uml", JsonSupport.readTree(umlProperties)));
  }

  /** A component with a port: the smallest model with a required ownership property. */
  private static SourceDocument componentSource() {
    return new SourceDocument(
        "model.schema.v1",
        List.of(),
        List.of(),
        List.of(
            node("checkout", "Component", null),
            node("checkout-port", "Port", "{\"component\": \"checkout\"}")),
        // Typed Message so the export also writes the visible dedirenUmlSequence attribute the
        // precedence test below edits; the sibling `kind` is an ordinary property with no channel
        // of its own, and is here to prove one survives.
        List.of(
            new SourceRelationship(
                "checkout-owns-port",
                "Message",
                "checkout",
                "checkout-port",
                "",
                Map.of("uml", JsonSupport.readTree("{\"sequence\": 9, \"kind\": \"ownership\"}")))),
        Map.of(
            "generic-graph",
            JsonSupport.readTree(
                """
                {
                  "semantic_profile": "uml",
                  "views": [
                    {
                      "id": "main",
                      "label": "Main",
                      "kind": "uml-component",
                      "nodes": ["checkout", "checkout-port"],
                      "relationships": ["checkout-owns-port"]
                    }
                  ]
                }
                """)));
  }

  private static LayoutResult componentLayout() {
    return new LayoutResult(
        "layout-result.schema.v1",
        "main",
        List.of(
            new LaidOutNode("checkout", "checkout", null, 12, 12, 160, 80, "checkout"),
            new LaidOutNode(
                "checkout-port", "checkout-port", null, 160, 40, 20, 20, "checkout-port")),
        List.of(
            new LaidOutEdge(
                "checkout-owns-port",
                "checkout",
                "checkout-port",
                "checkout-owns-port",
                null,
                List.of(),
                List.of(),
                "")),
        List.of(),
        List.of());
  }

  private EngineResult<ExportResult> exportOf(SourceDocument source, LayoutResult layout)
      throws Exception {
    return exporter.export(
        new ExportRequest(ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION, source, layout, POLICY),
        Map.of(),
        Path.of("").toAbsolutePath());
  }

  private String export(SourceDocument source, LayoutResult layout) throws Exception {
    return exportOf(source, layout).value().content();
  }

  private static JsonNode propertiesOf(SourceDocument document, String elementId) {
    for (SourceNode candidate : document.nodes()) {
      if (candidate.id().equals(elementId)) {
        return JsonSupport.objectMapper().valueToTree(candidate.properties());
      }
    }
    for (SourceRelationship candidate : document.relationships()) {
      if (candidate.id().equals(elementId)) {
        return JsonSupport.objectMapper().valueToTree(candidate.properties());
      }
    }
    throw new AssertionError("no element '" + elementId + "' in the re-imported document");
  }

  // ---------------------------------------------------------------- the channel itself

  @Test
  void carriesElementPropertiesOnTheHiddenMetadataCellAndNeverOnAnElementWrapper()
      throws Exception {
    List<MxCell> cells =
        MxReader.read(export(componentSource(), componentLayout())).diagrams().get(0).cells();

    List<MxCell> carrying =
        cells.stream()
            .filter(cell -> cell.object() != null)
            .filter(
                cell -> cell.object().attributes().containsKey(DrawioIdentity.ELEMENT_PROPERTIES))
            .toList();
    assertThat(carrying).hasSize(1);
    assertThat(carrying.get(0).object().attributes())
        .describedAs("only the hidden metadata cell, which Edit Data never shows")
        .containsEntry(DrawioIdentity.TYPE, DrawioIdentity.VIEW_TYPE);
    assertThat(carrying.get(0).visible()).isFalse();
  }

  @Test
  void restoresARequiredOwnershipPropertyThroughTheRoundTrip() throws Exception {
    EngineResult<SourceDocument> reimported =
        importer.importSource(export(componentSource(), componentLayout()));

    assertThat(propertiesOf(reimported.value(), "checkout-port").at("/uml/component").asString())
        .describedAs("Port.component is what six fixtures were rejected for losing")
        .isEqualTo("checkout");
  }

  @Test
  void restoresARelationshipPropertyThroughTheRoundTrip() throws Exception {
    EngineResult<SourceDocument> reimported =
        importer.importSource(export(componentSource(), componentLayout()));

    assertThat(propertiesOf(reimported.value(), "checkout-owns-port").at("/uml/kind").asString())
        .isEqualTo("ownership");
  }

  /**
   * The element a semantic boundary stands for is a document node the file draws no box for; it is
   * rebuilt from the container's {@code dedirenSemanticSource*} attributes. Its properties have to
   * ride the same channel, or the reconstruction is a differently-shaped element.
   */
  @Test
  void restoresThePropertiesOfAnElementDrawnOnlyAsASemanticBoundary() throws Exception {
    SourceDocument source =
        new SourceDocument(
            "model.schema.v1",
            List.of(),
            List.of(),
            List.of(
                node("region", "Region", "{\"state_machine\": \"lifecycle\"}"),
                node("draft", "State", null)),
            List.of(),
            Map.of(
                "generic-graph",
                JsonSupport.readTree(
                    """
                    {
                      "semantic_profile": "uml",
                      "views": [{ "id": "main", "label": "Main", "kind": "uml-state-machine" }]
                    }
                    """)));
    LayoutResult layout =
        new LayoutResult(
            "layout-result.schema.v1",
            "main",
            List.of(new LaidOutNode("draft", "draft", null, 20, 20, 80, 40, "draft")),
            List.of(),
            List.of(
                new LaidOutGroup(
                    "region-frame",
                    "region",
                    "region-frame",
                    GroupProvenance.semanticBacked("region"),
                    0,
                    0,
                    200,
                    120,
                    List.of("draft"),
                    "Region")),
            List.of());

    EngineResult<SourceDocument> reimported = importer.importSource(export(source, layout));

    assertThat(propertiesOf(reimported.value(), "region").at("/uml/state_machine").asString())
        .isEqualTo("lifecycle");
  }

  /**
   * The whole point of the channel: an export that carries every property has nothing left to
   * declare lost, so the warning has to stop firing rather than become noise a reader skips.
   */
  @Test
  void saysNothingAboutDroppedPropertiesOnceTheyAreAllCarried() throws Exception {
    assertThat(exportOf(componentSource(), componentLayout()).diagnostics())
        .extracting(Diagnostic::code)
        .doesNotContain(DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code());
  }

  /**
   * A visible attribute a human can read and edit outranks dediren's own hidden bookkeeping: {@code
   * dedirenUmlSequence} sits on the edge wrapper in Edit Data, and silently overruling what someone
   * typed there is the worst kind of surprise. The two agree unless a human changed one.
   */
  @Test
  void theVisibleMessageOrderingAttributeOutranksTheHiddenPropertyMap() throws Exception {
    String drawio = export(componentSource(), componentLayout());
    assertThat(drawio).contains(DrawioIdentity.UML_SEQUENCE + "=\"9\"");
    String edited =
        drawio.replace(
            DrawioIdentity.UML_SEQUENCE + "=\"9\"", DrawioIdentity.UML_SEQUENCE + "=\"4\"");

    EngineResult<SourceDocument> reimported = importer.importSource(edited);

    assertThat(propertiesOf(reimported.value(), "checkout-owns-port").at("/uml/sequence").asInt())
        .isEqualTo(4);
  }

  // ---------------------------------------------------------------- hostile input

  /**
   * The attribute is a new attacker-controlled path into a model's {@code properties}, so it is
   * refused atomically rather than skipped. Importing the page without the properties is the exact
   * failure this channel closes — a file that imports green and is rejected by the next command —
   * so a map that cannot be read is not a map that can be ignored.
   */
  @Test
  void refusesAnUnreadablePropertyMapRatherThanImportingWithoutIt() {
    assertThatThrownBy(() -> importer.importSource(pageCarrying("not json at all")))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error ->
                assertThat(((EngineException) error).diagnostics().get(0).code())
                    .isEqualTo(DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code()));
  }

  /**
   * 64 KiB of {@code [} is well inside the attribute ceiling and would recurse a naive reader off
   * the stack. The model's own mapper carries Jackson's nesting guard, and this pins that the guard
   * is reached through <em>this</em> path and surfaces as a published diagnostic rather than an
   * {@code Error}.
   */
  @Test
  void refusesADeeplyNestedPropertyMapAtTheParsersOwnNestingGuard() {
    String nested = "{\"n\":{\"uml\":" + "[".repeat(600) + "]".repeat(600) + "}}";

    assertThatThrownBy(() -> importer.importSource(pageCarrying(nested)))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error ->
                assertThat(((EngineException) error).diagnostics().get(0).message())
                    .contains("nesting depth"));
  }

  /** One minimal round-tripped page whose metadata cell carries {@code json} verbatim. */
  private static String pageCarrying(String json) {
    return """
        <mxfile host="app.diagrams.net"><diagram id="p" name="P"><mxGraphModel><root>
        <mxCell id="0"/><mxCell id="1" parent="0"/>
        <object dedirenType="dediren.view" dedirenViewId="main" %s="%s" id="dediren-view">\
        <mxCell style="text;" vertex="1" parent="1" visible="0"/></object>
        <object dedirenId="n" dedirenType="Class" label="N" id="n">\
        <mxCell style="rounded=0;" vertex="1" parent="1"/></object>
        </root></mxGraphModel></diagram></mxfile>
        """
        .formatted(
            DrawioIdentity.ELEMENT_PROPERTIES,
            json.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"));
  }

  /**
   * The channel is bounded by the ceiling every other attribute is: {@code MxReader} refuses an
   * attribute value over {@link DrawioLimits#MAX_TOKEN_BYTES}, so an export writing a larger one
   * would produce a file dediren itself could not re-import. It degrades instead — the file still
   * opens, and the export says what it could not carry.
   */
  @Test
  void dropsTheChannelAndSaysSoRatherThanWritingAFileItCouldNotReadBack() throws Exception {
    String enormous = "x".repeat(DrawioLimits.MAX_TOKEN_BYTES);
    SourceDocument source =
        new SourceDocument(
            "model.schema.v1",
            List.of(),
            List.of(),
            List.of(node("checkout", "Component", "{\"note\": \"" + enormous + "\"}")),
            List.of(),
            Map.of(
                "generic-graph",
                JsonSupport.readTree(
                    """
                    {
                      "semantic_profile": "uml",
                      "views": [{ "id": "main", "label": "Main", "kind": "uml-component" }]
                    }
                    """)));
    LayoutResult layout =
        new LayoutResult(
            "layout-result.schema.v1",
            "main",
            List.of(new LaidOutNode("checkout", "checkout", null, 0, 0, 10, 10, "checkout")),
            List.of(),
            List.of(),
            List.of());

    EngineResult<ExportResult> exported = exportOf(source, layout);

    assertThat(exported.value().content()).doesNotContain(DrawioIdentity.ELEMENT_PROPERTIES);
    assertThat(exported.diagnostics())
        .extracting(Diagnostic::code)
        .contains(DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code());
    assertThat(importer.importSource(exported.value().content()).value().nodes()).hasSize(1);
  }
}

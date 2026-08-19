package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.plugins.drawio.mx.MxCell;
import dev.dediren.plugins.drawio.mx.MxDiagram;
import dev.dediren.plugins.drawio.mx.MxFile;
import dev.dediren.plugins.drawio.mx.MxReader;
import dev.dediren.plugins.drawio.write.DrawioIdentity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The whole-model lane: {@link DrawioExportEngine#exportModel}, one {@code .drawio} carrying every
 * supplied laid-out view.
 *
 * <h2>Why draw.io opts in where UML/XMI trails</h2>
 *
 * <p>{@code ExportEngine.exportModel} is an opt-in because a notation has to have somewhere to put
 * a second view. draw.io does: the format is natively multi-page, and a page is a first-class
 * container with its own cell id space. A whole model is therefore one {@code <mxfile>} with one
 * {@code <diagram>} per view — not a merged graph, which is what forces the XMI lane's
 * class-family restriction. Nothing about a page collides with another page, so the aggregate is
 * the per-view builder run once per view.
 *
 * <p>The reverse direction already existed: {@link
 * dev.dediren.plugins.drawio.read.DrawioSourceMapper} has read multi-page documents from the start,
 * one view per page. This is the half that produces them.
 */
class WholeModelDrawioExportTest {

  private final DrawioExportEngine engine = new DrawioExportEngine();
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

  private static SourceDocument source() {
    return new SourceDocument(
        "model.schema.v1",
        List.of(),
        List.of(),
        List.of(
            new SourceNode("orders", "ApplicationComponent", "Orders", Map.of()),
            new SourceNode("billing", "ApplicationComponent", "Billing", Map.of()),
            new SourceNode(
                "ledger",
                "ApplicationService",
                "Ledger",
                Map.of("acme", JsonSupport.readTree("{\"owner\": \"finance\"}")))),
        List.of(
            new SourceRelationship(
                "orders-serves-billing", "Serving", "orders", "billing", "serves", Map.of())),
        Map.of(
            "generic-graph",
            JsonSupport.readTree(
                """
                {
                  "semantic_profile": "archimate",
                  "views": [
                    {
                      "id": "main",
                      "label": "Context",
                      "kind": "archimate",
                      "nodes": ["orders", "billing"],
                      "relationships": ["orders-serves-billing"]
                    },
                    {
                      "id": "finance",
                      "label": "Finance",
                      "kind": "archimate",
                      "nodes": ["ledger"]
                    }
                  ]
                }
                """)));
  }

  private static LayoutResult mainLayout() {
    return new LayoutResult(
        "layout-result.schema.v1",
        "main",
        List.of(
            new LaidOutNode("orders", "orders", null, 12, 12, 160, 80, "Orders"),
            new LaidOutNode("billing", "billing", null, 220, 12, 160, 80, "Billing")),
        List.of(
            new LaidOutEdge(
                "orders-serves-billing",
                "orders",
                "billing",
                "orders-serves-billing",
                null,
                List.of(),
                List.of(),
                "serves")),
        List.of(),
        List.of());
  }

  private static LayoutResult financeLayout() {
    return new LayoutResult(
        "layout-result.schema.v1",
        "finance",
        List.of(new LaidOutNode("ledger", "ledger", null, 12, 12, 160, 80, "Ledger")),
        List.of(),
        List.of(),
        List.of());
  }

  private EngineResult<ExportResult> aggregate(
      SourceDocument source, List<ModelExportRequest.ViewLayout> views) throws EngineException {
    return engine
        .exportModel(
            new ModelExportRequest(source, views, POLICY), Map.of(), Path.of("").toAbsolutePath())
        .orElseThrow(() -> new AssertionError("the draw.io lane opts in to exportModel"));
  }

  private EngineResult<ExportResult> bothViews() throws EngineException {
    return aggregate(
        source(),
        List.of(
            new ModelExportRequest.ViewLayout("main", mainLayout()),
            new ModelExportRequest.ViewLayout("finance", financeLayout())));
  }

  private static MxCell metadataCell(MxDiagram page) {
    return page.cells().stream()
        .filter(cell -> cell.object() != null)
        .filter(
            cell ->
                DrawioIdentity.VIEW_TYPE.equals(
                    cell.object().attributes().get(DrawioIdentity.TYPE)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("page '" + page.name() + "' has no metadata cell"));
  }

  // ---------------------------------------------------------------- page composition

  @Test
  void writesOnePagePerSuppliedViewInBuildOrder() throws Exception {
    MxFile file = MxReader.read(bothViews().value().content());

    assertThat(file.diagrams())
        .describedAs("the record's javadoc requires build order, deterministically")
        .extracting(MxDiagram::id)
        .containsExactly("main", "finance");
  }

  @Test
  void namesEachPageFromItsViewLabel() throws Exception {
    MxFile file = MxReader.read(bothViews().value().content());

    assertThat(file.diagrams()).extracting(MxDiagram::name).containsExactly("Context", "Finance");
  }

  /**
   * Two views may carry the same label, and the policy's single {@code diagram_name} is the
   * fallback for every view that declares none — so the aggregate has two independent ways to want
   * one page name twice. A draw.io document with two identically named pages is legal but
   * unnavigable, and it also collapses the re-imported view labels.
   */
  @Test
  void dedupesAPageNameTwoViewsWouldOtherwiseShare() throws Exception {
    SourceDocument source =
        new SourceDocument(
            "model.schema.v1",
            List.of(),
            List.of(),
            List.of(new SourceNode("orders", "ApplicationComponent", "Orders", Map.of())),
            List.of(),
            Map.of(
                "generic-graph",
                JsonSupport.readTree(
                    """
                    {
                      "semantic_profile": "archimate",
                      "views": [
                        { "id": "main", "label": "Shared", "kind": "archimate" },
                        { "id": "second", "label": "Shared", "kind": "archimate" }
                      ]
                    }
                    """)));
    LayoutResult first =
        new LayoutResult(
            "layout-result.schema.v1",
            "main",
            List.of(new LaidOutNode("orders", "orders", null, 0, 0, 10, 10, "Orders")),
            List.of(),
            List.of(),
            List.of());
    LayoutResult second =
        new LayoutResult(
            "layout-result.schema.v1", "second", List.of(), List.of(), List.of(), List.of());

    MxFile file =
        MxReader.read(
            aggregate(
                    source,
                    List.of(
                        new ModelExportRequest.ViewLayout("main", first),
                        new ModelExportRequest.ViewLayout("second", second)))
                .value()
                .content());

    assertThat(file.diagrams()).extracting(MxDiagram::name).containsExactly("Shared", "Shared-2");
  }

  /**
   * The first page keeps the view id {@code main} verbatim rather than being renumbered into it.
   * {@code DrawioSourceMapper} materializes {@code main} for a page-one that declares no view id,
   * and {@code CoreCommands.renderImportedMain} projects exactly that id, so a model that has a
   * {@code main} view must round-trip through the aggregate as {@code main} and not as
   * {@code main-2}.
   */
  @Test
  void theFirstPageKeepsTheViewIdMainWhenTheModelHasOne() throws Exception {
    MxFile file = MxReader.read(bothViews().value().content());

    assertThat(metadataCell(file.diagrams().get(0)).object().attributes())
        .containsEntry(DrawioIdentity.VIEW_ID, "main");
    assertThat(file.diagrams().get(0).id()).isEqualTo("main");
  }

  @Test
  void givesEveryPageItsOwnMetadataCellSoTheAggregateReimportsAsAMultiViewModel() throws Exception {
    MxFile file = MxReader.read(bothViews().value().content());

    assertThat(file.diagrams())
        .allSatisfy(page -> assertThat(metadataCell(page)).isNotNull())
        .extracting(page -> metadataCell(page).object().attributes().get(DrawioIdentity.VIEW_ID))
        .containsExactly("main", "finance");

    SourceDocument reimported = importer.importSource(bothViews().value().content()).value();
    JsonNode views = JsonSupport.objectMapper()
        .valueToTree(reimported.plugins().get("generic-graph")).get("views");
    assertThat(views).hasSize(2);
    assertThat(views.get(0).get("id").asString()).isEqualTo("main");
    assertThat(views.get(1).get("id").asString()).isEqualTo("finance");
  }

  /**
   * Each page carries only its own elements' properties. The alternative — every page carrying the
   * whole model's — would put the same blob on every page, grow the file with the square of the
   * model, and make the elements a page draws unreadable from the page itself.
   */
  @Test
  void eachPagesPropertyMapCoversThatPageOnly() throws Exception {
    MxFile file = MxReader.read(bothViews().value().content());

    Map<String, String> first = metadataCell(file.diagrams().get(0)).object().attributes();
    Map<String, String> second = metadataCell(file.diagrams().get(1)).object().attributes();
    assertThat(first)
        .describedAs("page one draws nothing with properties, so it carries no map at all")
        .doesNotContainKey(DrawioIdentity.ELEMENT_PROPERTIES);
    assertThat(second.get(DrawioIdentity.ELEMENT_PROPERTIES)).contains("ledger").contains("finance");
  }

  // ---------------------------------------------------------------- diagnostics

  /**
   * A per-page warning about the model as a whole would otherwise be repeated once per page, which
   * is how a fifty-view aggregate buries every other diagnostic in the envelope. Page-specific
   * messages name their element and stay distinct, so they survive.
   */
  @Test
  void doesNotRepeatTheSameWholeModelWarningOncePerPage() throws Exception {
    SourceDocument source =
        new SourceDocument(
            "model.schema.v1",
            List.of(),
            List.of(),
            List.of(new SourceNode("thing", "NotAnArchimateType", "Thing", Map.of())),
            List.of(),
            Map.of(
                "generic-graph",
                JsonSupport.readTree(
                    """
                    {
                      "semantic_profile": "archimate",
                      "views": [
                        { "id": "main", "label": "One", "kind": "archimate" },
                        { "id": "two", "label": "Two", "kind": "archimate" }
                      ]
                    }
                    """)));
    LayoutResult onlyView =
        new LayoutResult(
            "layout-result.schema.v1",
            "main",
            List.of(new LaidOutNode("thing", "thing", null, 0, 0, 10, 10, "Thing")),
            List.of(),
            List.of(),
            List.of());
    LayoutResult sameAgain =
        new LayoutResult(
            "layout-result.schema.v1",
            "two",
            List.of(new LaidOutNode("thing", "thing", null, 0, 0, 10, 10, "Thing")),
            List.of(),
            List.of(),
            List.of());

    EngineResult<ExportResult> exported =
        aggregate(
            source,
            List.of(
                new ModelExportRequest.ViewLayout("main", onlyView),
                new ModelExportRequest.ViewLayout("two", sameAgain)));

    assertThat(exported.diagnostics())
        .filteredOn(
            diagnostic -> diagnostic.code().equals(DiagnosticCode.DRAWIO_SHAPE_UNMAPPED.code()))
        .describedAs("the identical warning both pages raise is reported once: %s", exported)
        .hasSize(1);
  }

  @Test
  void keepsADiagnosticThatDiffersBetweenPages() throws Exception {
    LayoutResult danglingOnSecondPage =
        new LayoutResult(
            "layout-result.schema.v1",
            "finance",
            List.of(new LaidOutNode("ghost", "ghost", null, 0, 0, 10, 10, "Ghost")),
            List.of(),
            List.of(),
            List.of());

    EngineResult<ExportResult> exported =
        aggregate(
            source(),
            List.of(
                new ModelExportRequest.ViewLayout("main", mainLayout()),
                new ModelExportRequest.ViewLayout("finance", danglingOnSecondPage)));

    assertThat(exported.diagnostics())
        .extracting(Diagnostic::code)
        .contains(DiagnosticCode.DRAWIO_LAYOUT_REFERENCE_MISSING.code());
  }

  // ---------------------------------------------------------------- opt-out and policy

  @Test
  void producesNoAggregateWhenTheDriverSuppliesNoViews() throws Exception {
    Optional<EngineResult<ExportResult>> exported =
        engine.exportModel(
            new ModelExportRequest(source(), List.of(), POLICY),
            Map.of(),
            Path.of("").toAbsolutePath());

    assertThat(exported).isEmpty();
  }

  @Test
  void rejectsAnUnreadablePolicyTheSameWayTheSingleViewLaneDoes() {
    assertThatThrownBy(
            () ->
                engine.exportModel(
                    new ModelExportRequest(
                        source(),
                        List.of(new ModelExportRequest.ViewLayout("main", mainLayout())),
                        JsonSupport.readTree("{\"diagram_name\": \"Main\"}")),
                    Map.of(),
                    Path.of("").toAbsolutePath()))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error ->
                assertThat(((EngineException) error).diagnostics().get(0).code())
                    .isEqualTo(DiagnosticCode.DRAWIO_POLICY_INVALID.code()));
  }

  @Test
  void declaresTheSameArtifactKindTheSingleViewLaneDoes() throws Exception {
    assertThat(bothViews().value().artifactKind()).isEqualTo(DrawioExportEngine.ARTIFACT_KIND);
  }
}

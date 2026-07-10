package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.build.BuildResult;
import dev.dediren.contracts.build.BuildViewOutcome;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.RoutedEdge;
import dev.dediren.ir.SceneGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Drives the in-memory build over fake {@code engine-api} engines — no processes, no plugin
 * testbed. The fakes exercise the driver's orchestration (view enumeration, lane selection, stage
 * chaining, diagnostics aggregation, artifact writing, {@code --emit} seam) while the real {@code
 * SourceValidator}, {@code CoreCommands} stage paths, and {@code LayoutQuality} validation stay in
 * the loop, so the test pins the driver, not the fakes.
 */
class BuildCommandTest {
  private static final String SOURCE =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} },
          { "id": "api", "type": "generic.component", "label": "API", "properties": {} }
        ],
        "relationships": [
          {
            "id": "client-calls-api",
            "type": "generic.calls",
            "source": "client",
            "target": "api",
            "label": "calls",
            "properties": {}
          }
        ],
        "plugins": {
          "generic-graph": {
            "views": [
              {
                "id": "overview",
                "label": "Overview",
                "nodes": ["client", "api"],
                "relationships": ["client-calls-api"]
              },
              {
                "id": "detail",
                "label": "Detail",
                "nodes": ["client", "api"],
                "relationships": ["client-calls-api"]
              }
            ]
          }
        }
      }
      """;

  @TempDir Path out;

  @Test
  void rendersEveryViewInModelOrderAndWritesArtifacts() throws Exception {
    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines());

    assertThat(outcome.exitCode()).isZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.buildResultSchemaVersion())
        .isEqualTo(ContractVersions.BUILD_RESULT_SCHEMA_VERSION);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(result.views())
        .extracting(BuildViewOutcome::viewId)
        .containsExactly("overview", "detail");
    assertThat(result.views())
        .allSatisfy(view -> assertThat(view.status()).isEqualTo(EnvelopeStatus.OK));

    BuildViewOutcome overview = result.views().getFirst();
    assertThat(overview.artifacts()).hasSize(1);
    assertThat(overview.artifacts().getFirst().artifactKind()).isEqualTo("svg");
    assertThat(overview.artifacts().getFirst().path()).isEqualTo("overview/diagram.svg");
    assertThat(Files.readString(out.resolve("overview/diagram.svg"))).isEqualTo("<svg/>");
    assertThat(Files.readString(out.resolve("detail/diagram.svg"))).isEqualTo("<svg/>");
  }

  @Test
  void explicitViewSelectionControlsOrderAndSubset() throws Exception {
    BuildRequest request =
        new BuildRequest(
            SOURCE, null, List.of("detail"), "{}", null, null, Set.of(), out, Map.of());

    BuildResult result = buildResult(BuildCommand.run(request, engines()));

    assertThat(result.views()).extracting(BuildViewOutcome::viewId).containsExactly("detail");
  }

  @Test
  void runsBothExportLanesPerView() throws Exception {
    BuildRequest request =
        new BuildRequest(SOURCE, null, List.of(), null, "{}", "{}", Set.of(), out, Map.of());

    PluginRunOutcome outcome = BuildCommand.run(request, engines());

    assertThat(outcome.exitCode()).isZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.OK);
    BuildViewOutcome overview = result.views().getFirst();
    assertThat(overview.artifacts())
        .extracting(a -> a.artifactKind() + " -> " + a.path())
        .containsExactly("archimate+xml -> overview/oef.xml", "uml+xml -> overview/xmi.xml");
    assertThat(Files.readString(out.resolve("overview/oef.xml"))).isEqualTo("<oef/>");
    assertThat(Files.readString(out.resolve("detail/xmi.xml"))).isEqualTo("<xmi/>");
  }

  @Test
  void qualityWarningDowngradesViewAndAggregateButKeepsArtifacts() throws Exception {
    Engines engines = enginesWith(Set.of(), Set.of("detail"));

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertThat(outcome.exitCode()).isZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.views().getFirst().status()).isEqualTo(EnvelopeStatus.OK);

    BuildViewOutcome detail = result.views().getLast();
    assertThat(detail.status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(detail.artifacts()).hasSize(1);
    assertThat(detail.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_LAYOUT_QUALITY_WARNING");
            });
  }

  @Test
  void failingViewDoesNotAbortOthersAndYieldsAggregateError() throws Exception {
    Engines engines = enginesWith(Set.of("detail"), Set.of());

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertThat(outcome.exitCode()).isNotZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.ERROR);

    BuildViewOutcome overview = result.views().getFirst();
    assertThat(overview.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(overview.artifacts()).hasSize(1);

    BuildViewOutcome detail = result.views().getLast();
    assertThat(detail.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(detail.artifacts()).isEmpty();
    assertThat(detail.diagnostics())
        .extracting(Diagnostic::code)
        .contains("DEDIREN_FAKE_LAYOUT_FAILED");
    // The healthy view's artifact is still on disk.
    assertThat(Files.exists(out.resolve("overview/diagram.svg"))).isTrue();
  }

  @Test
  void unresolvableExplicitViewIsAPerViewErrorAndDoesNotAbortOthers() throws Exception {
    // A real semantics engine (GenericGraphEngine) surfaces an unknown --views entry as an
    // UncheckedIOException structural failure, not an EngineException; the fake reproduces that
    // exact observable so the driver's catch (not a fake-only code path) is what's pinned here.
    BuildRequest request =
        new BuildRequest(
            SOURCE,
            null,
            List.of("overview", "no-such-view"),
            "{}",
            null,
            null,
            Set.of(),
            out,
            Map.of());

    PluginRunOutcome outcome = BuildCommand.run(request, engines());

    assertThat(outcome.exitCode()).isEqualTo(2);
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.ERROR);

    BuildViewOutcome overview = result.views().getFirst();
    assertThat(overview.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(overview.artifacts()).hasSize(1);
    assertThat(Files.exists(out.resolve("overview/diagram.svg"))).isTrue();

    BuildViewOutcome missing = result.views().getLast();
    assertThat(missing.viewId()).isEqualTo("no-such-view");
    assertThat(missing.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(missing.artifacts()).isEmpty();
    assertThat(missing.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
              // Exact equality (not contains) discriminates the getCause() != null ternary in
              // runStage: the cleaned cause message, not the wrapped UncheckedIOException's
              // "java.io.IOException: ..." message. A mutant that took error.getMessage() would
              // fail this assertion because that message starts with the exception class name.
              assertThat(diagnostic.message()).isEqualTo("missing generic-graph view no-such-view");
            });
  }

  @Test
  void emitWritesStageEnvelopesUnderView() throws Exception {
    BuildRequest request =
        new BuildRequest(
            SOURCE,
            null,
            List.of(),
            "{}",
            null,
            null,
            Set.of("layout-request", "layout-result", "render-metadata"),
            out,
            Map.of());

    BuildCommand.run(request, engines());

    for (String stage : List.of("layout-request", "layout-result", "render-metadata")) {
      Path emitted = out.resolve("overview/" + stage + ".json");
      assertThat(Files.exists(emitted)).describedAs(stage).isTrue();
      JsonNode envelope = JsonSupport.objectMapper().readTree(Files.readString(emitted));
      assertThat(envelope.get("status").asText()).isEqualTo("ok");
      assertThat(envelope.has("data")).isTrue();
    }
  }

  @Test
  void emitStageEnvelopesAreByteIdenticalToStandaloneCommands() throws Exception {
    // The --emit seam must persist the exact bytes each standalone stage command prints, so a
    // consumer cannot tell the in-memory build apart from `dediren project`/`dediren layout`.
    Engines engines = engines();
    BuildRequest request =
        new BuildRequest(
            SOURCE,
            null,
            List.of("overview"),
            "{}",
            null,
            null,
            Set.of("layout-request", "layout-result", "render-metadata"),
            out,
            Map.of());

    BuildCommand.run(request, engines);

    String layoutRequest =
        CoreCommands.projectCommand(
                "generic-graph", "layout-request", "overview", SOURCE, null, Map.of(), engines)
            .stdout();
    assertThat(Files.readString(out.resolve("overview/layout-request.json")))
        .isEqualTo(layoutRequest);

    String layoutResult =
        CoreCommands.layoutCommand("elk-layout", layoutRequest, Map.of(), engines).stdout();
    assertThat(Files.readString(out.resolve("overview/layout-result.json")))
        .isEqualTo(layoutResult);

    String renderMetadata =
        CoreCommands.projectCommand(
                "generic-graph", "render-metadata", "overview", SOURCE, null, Map.of(), engines)
            .stdout();
    assertThat(Files.readString(out.resolve("overview/render-metadata.json")))
        .isEqualTo(renderMetadata);
  }

  @Test
  void missingLaneIsARejectedInputError() throws Exception {
    BuildRequest request =
        new BuildRequest(SOURCE, null, List.of(), null, null, null, Set.of(), out, Map.of());

    PluginRunOutcome outcome = BuildCommand.run(request, engines());

    assertThat(outcome.exitCode()).isEqualTo(2);
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(result.views()).isEmpty();
    assertThat(result.diagnostics())
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void invalidSourceIsARejectedInputError() throws Exception {
    BuildRequest request =
        new BuildRequest(
            "{\"nope\":true}", null, List.of(), "{}", null, null, Set.of(), out, Map.of());

    PluginRunOutcome outcome = BuildCommand.run(request, engines());

    assertThat(outcome.exitCode()).isEqualTo(2);
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(result.views()).isEmpty();
    assertThat(result.diagnostics()).isNotEmpty();
  }

  @Test
  void qualityValidationFailureIsAPerViewErrorAndDoesNotAbortOthers() throws Exception {
    // The layout stage succeeds but emits a route with no points, so the real LayoutQuality
    // validation (still in the loop) fails the view at the layout-quality-validation stage.
    Engines engines = new FakeEngines().qualityFailingViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertFailingViewDidNotAbortHealthyOverview(
        outcome, "svg", "DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY");
  }

  @Test
  void renderMetadataFailureIsAPerViewErrorAndDoesNotAbortOthers() throws Exception {
    Engines engines = new FakeEngines().metadataFailingViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertFailingViewDidNotAbortHealthyOverview(outcome, "svg", "DEDIREN_FAKE_METADATA_FAILED");
  }

  @Test
  void renderFailureIsAPerViewErrorAndDoesNotAbortOthers() throws Exception {
    Engines engines = new FakeEngines().renderFailingViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertFailingViewDidNotAbortHealthyOverview(outcome, "svg", "DEDIREN_FAKE_RENDER_FAILED");
  }

  @Test
  void oefExportFailureIsAPerViewErrorAndDoesNotAbortOthers() throws Exception {
    Engines engines = new FakeEngines().oefFailingViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(oefOnlyRequest(), engines);

    assertFailingViewDidNotAbortHealthyOverview(
        outcome, "archimate+xml", "DEDIREN_FAKE_EXPORT_FAILED");
  }

  @Test
  void xmiExportFailureIsAPerViewErrorAndDoesNotAbortOthers() throws Exception {
    Engines engines = new FakeEngines().xmiFailingViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(xmiOnlyRequest(), engines);

    assertFailingViewDidNotAbortHealthyOverview(outcome, "uml+xml", "DEDIREN_FAKE_EXPORT_FAILED");
  }

  @Test
  void layoutRequestStageWarningDowngradesViewToWarning() throws Exception {
    // Pins `warning |= layoutRequest.warning()`: the only warning enters at the layout-request
    // stage, so a `|=` -> `&=` mutation would leave the view OK instead of WARNING.
    Engines engines = new FakeEngines().layoutRequestWarningViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertOnlyDetailWarned(outcome, "svg", "DEDIREN_FAKE_LAYOUT_REQUEST_WARNING");
  }

  @Test
  void layoutStageWarningDowngradesViewToWarning() throws Exception {
    // Pins `warning |= layout.warning()`: the only warning enters at the layout stage envelope.
    Engines engines = new FakeEngines().layoutStageWarningViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertOnlyDetailWarned(outcome, "svg", "DEDIREN_FAKE_LAYOUT_STAGE_WARNING");
  }

  @Test
  void oefExportStageWarningDowngradesViewToWarning() throws Exception {
    // Pins `warning |= oef.warning()`: the OEF lane runs and its stage is the sole warning source.
    Engines engines = new FakeEngines().oefWarningViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(oefOnlyRequest(), engines);

    assertOnlyDetailWarned(outcome, "archimate+xml", "DEDIREN_FAKE_EXPORT_WARNING");
  }

  @Test
  void xmiExportStageWarningDowngradesViewToWarning() throws Exception {
    // Pins `warning |= xmi.warning()`: the XMI lane runs and its stage is the sole warning source.
    Engines engines = new FakeEngines().xmiWarningViews(Set.of("detail")).build();

    PluginRunOutcome outcome = BuildCommand.run(xmiOnlyRequest(), engines);

    assertOnlyDetailWarned(outcome, "uml+xml", "DEDIREN_FAKE_EXPORT_WARNING");
  }

  @Test
  void sourceWithoutGenericGraphSectionBuildsNoViews() throws Exception {
    // selectViews' empty-view path: no explicit --views and no plugins.generic-graph section, so
    // the build has nothing to render and reports an OK, exit-0, empty-views result.
    BuildRequest request =
        new BuildRequest(
            SOURCE_WITHOUT_VIEWS, null, List.of(), "{}", null, null, Set.of(), out, Map.of());

    PluginRunOutcome outcome = BuildCommand.run(request, engines());

    assertThat(outcome.exitCode()).isZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(result.views()).isEmpty();
    assertThat(result.diagnostics()).isEmpty();
  }

  @Test
  void exportMediaSuffixSelectsFileExtension() throws Exception {
    // exportExtension keys the file extension off the media suffix after the last '+'. First-party
    // engines only emit "+xml", so a third-party-style "<id>+json" pins the switch's "json" arm and
    // the substring offset (a +1 -> -1 mutation would land on a different suffix and extension).
    Engines engines =
        Engines.of(
            List.of(new FakeSemanticsEngine(Set.of(), Set.of())),
            List.of(new FakeLayoutEngine(Set.of(), Set.of(), Set.of(), Set.of())),
            List.of(new FakeRenderEngine(Set.of())),
            List.of(
                new FakeExportEngine("archimate-oef", "stats+json", "{}", Set.of(), Set.of()),
                new FakeExportEngine("uml-xmi", "uml+xml", "<xmi/>", Set.of(), Set.of())));

    PluginRunOutcome outcome = BuildCommand.run(oefOnlyRequest(), engines);

    assertThat(outcome.exitCode()).isZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    BuildViewOutcome overview = result.views().getFirst();
    assertThat(overview.artifacts().getFirst().artifactKind()).isEqualTo("stats+json");
    assertThat(overview.artifacts().getFirst().path()).isEqualTo("overview/oef.json");
    assertThat(Files.readString(out.resolve("overview/oef.json"))).isEqualTo("{}");
  }

  @Test
  void structuredStageExceptionIsFoldedPerViewNotThrown() throws Exception {
    // A stage that raises a structured PluginExecutionException (here the render engine id resolves
    // to no engine) is folded into the view by runStage's catch rather than aborting the build, so
    // the driver still returns a per-view error outcome.
    Engines engines =
        Engines.of(
            List.of(new FakeSemanticsEngine(Set.of(), Set.of())),
            List.of(new FakeLayoutEngine(Set.of(), Set.of(), Set.of(), Set.of())),
            List.of(),
            List.of());

    PluginRunOutcome outcome = BuildCommand.run(renderOnlyRequest(), engines);

    assertThat(outcome.exitCode()).isNotZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(result.views())
        .allSatisfy(view -> assertThat(view.status()).isEqualTo(EnvelopeStatus.ERROR));
    assertThat(result.views().getFirst().diagnostics())
        .extracting(Diagnostic::code)
        .contains("DEDIREN_PLUGIN_UNKNOWN");
  }

  // --- helpers ---------------------------------------------------------------------------------

  // A schema-valid source with no plugins.generic-graph section, so selectViews returns no views.
  private static final String SOURCE_WITHOUT_VIEWS =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} }
        ],
        "relationships": [],
        "plugins": {}
      }
      """;

  private BuildRequest renderOnlyRequest() {
    return new BuildRequest(SOURCE, null, List.of(), "{}", null, null, Set.of(), out, Map.of());
  }

  private BuildRequest oefOnlyRequest() {
    return new BuildRequest(SOURCE, null, List.of(), null, "{}", null, Set.of(), out, Map.of());
  }

  private BuildRequest xmiOnlyRequest() {
    return new BuildRequest(SOURCE, null, List.of(), null, null, "{}", Set.of(), out, Map.of());
  }

  // Asserts a single failing view ("detail") did not abort the healthy first view ("overview"):
  // the build errors and exits non-zero, overview still produces its lane artifact, and detail
  // errors with no artifacts and the expected diagnostic code.
  private void assertFailingViewDidNotAbortHealthyOverview(
      PluginRunOutcome outcome, String overviewArtifactKind, String detailDiagnosticCode)
      throws Exception {
    assertThat(outcome.exitCode()).isNotZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.ERROR);

    BuildViewOutcome overview = result.views().getFirst();
    assertThat(overview.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(overview.artifacts()).hasSize(1);
    assertThat(overview.artifacts().getFirst().artifactKind()).isEqualTo(overviewArtifactKind);

    BuildViewOutcome detail = result.views().getLast();
    assertThat(detail.viewId()).isEqualTo("detail");
    assertThat(detail.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(detail.artifacts()).isEmpty();
    assertThat(detail.diagnostics()).extracting(Diagnostic::code).contains(detailDiagnosticCode);
  }

  // Asserts only "detail" warned (overview stays OK): the build rolls up to WARNING with exit 0,
  // detail keeps its lane artifact, and carries the expected warning diagnostic. A `warning |=` ->
  // `warning &=` mutation on the pinned stage would leave detail OK and fail this assertion.
  private void assertOnlyDetailWarned(
      PluginRunOutcome outcome, String detailArtifactKind, String detailDiagnosticCode)
      throws Exception {
    assertThat(outcome.exitCode()).isZero();
    BuildResult result = buildResult(outcome);
    assertSchemaValid(outcome);
    assertThat(result.status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.views().getFirst().status()).isEqualTo(EnvelopeStatus.OK);

    BuildViewOutcome detail = result.views().getLast();
    assertThat(detail.viewId()).isEqualTo("detail");
    assertThat(detail.status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(detail.artifacts()).hasSize(1);
    assertThat(detail.artifacts().getFirst().artifactKind()).isEqualTo(detailArtifactKind);
    assertThat(detail.diagnostics()).extracting(Diagnostic::code).contains(detailDiagnosticCode);
  }

  private static Engines engines() {
    return new FakeEngines().build();
  }

  private static Engines enginesWith(Set<String> failingViews, Set<String> warningViews) {
    return new FakeEngines()
        .layoutFailingViews(failingViews)
        .qualityWarningViews(warningViews)
        .build();
  }

  // Configures the fake engine registry: each knob names the views that fail or warn at a specific
  // build stage, so a test can target one stage's error/warning aggregation in isolation.
  private static final class FakeEngines {
    private Set<String> layoutRequestWarningViews = Set.of();
    private Set<String> metadataFailingViews = Set.of();
    private Set<String> layoutFailingViews = Set.of();
    private Set<String> layoutStageWarningViews = Set.of();
    private Set<String> qualityWarningViews = Set.of();
    private Set<String> qualityFailingViews = Set.of();
    private Set<String> renderFailingViews = Set.of();
    private Set<String> oefFailingViews = Set.of();
    private Set<String> oefWarningViews = Set.of();
    private Set<String> xmiFailingViews = Set.of();
    private Set<String> xmiWarningViews = Set.of();

    FakeEngines layoutRequestWarningViews(Set<String> views) {
      this.layoutRequestWarningViews = views;
      return this;
    }

    FakeEngines metadataFailingViews(Set<String> views) {
      this.metadataFailingViews = views;
      return this;
    }

    FakeEngines layoutFailingViews(Set<String> views) {
      this.layoutFailingViews = views;
      return this;
    }

    FakeEngines layoutStageWarningViews(Set<String> views) {
      this.layoutStageWarningViews = views;
      return this;
    }

    FakeEngines qualityWarningViews(Set<String> views) {
      this.qualityWarningViews = views;
      return this;
    }

    FakeEngines qualityFailingViews(Set<String> views) {
      this.qualityFailingViews = views;
      return this;
    }

    FakeEngines renderFailingViews(Set<String> views) {
      this.renderFailingViews = views;
      return this;
    }

    FakeEngines oefFailingViews(Set<String> views) {
      this.oefFailingViews = views;
      return this;
    }

    FakeEngines oefWarningViews(Set<String> views) {
      this.oefWarningViews = views;
      return this;
    }

    FakeEngines xmiFailingViews(Set<String> views) {
      this.xmiFailingViews = views;
      return this;
    }

    FakeEngines xmiWarningViews(Set<String> views) {
      this.xmiWarningViews = views;
      return this;
    }

    Engines build() {
      return Engines.of(
          List.of(new FakeSemanticsEngine(layoutRequestWarningViews, metadataFailingViews)),
          List.of(
              new FakeLayoutEngine(
                  layoutFailingViews,
                  qualityWarningViews,
                  layoutStageWarningViews,
                  qualityFailingViews)),
          List.of(new FakeRenderEngine(renderFailingViews)),
          List.of(
              new FakeExportEngine(
                  "archimate-oef", "archimate+xml", "<oef/>", oefFailingViews, oefWarningViews),
              new FakeExportEngine(
                  "uml-xmi", "uml+xml", "<xmi/>", xmiFailingViews, xmiWarningViews)));
    }
  }

  private static EngineException fakeFailure(String code, String message) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, null)), 3);
  }

  private static List<Diagnostic> warningIf(
      Set<String> views, String view, String code, String message) {
    return views.contains(view)
        ? List.of(new Diagnostic(code, DiagnosticSeverity.WARNING, message, null))
        : List.of();
  }

  private static BuildResult buildResult(PluginRunOutcome outcome) {
    return JsonSupport.readValue(outcome.stdout(), BuildResult.class);
  }

  private static void assertSchemaValid(PluginRunOutcome outcome) {
    JsonNode document = JsonSupport.objectMapper().readTree(outcome.stdout());
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/build-result.schema.json", document);
    assertThat(errors).describedAs("build-result schema validity: %s", outcome.stdout()).isEmpty();
  }

  private record FakeSemanticsEngine(
      Set<String> layoutRequestWarningViews, Set<String> metadataFailingViews)
      implements SemanticsEngine {
    @Override
    public String id() {
      return "generic-graph";
    }

    @Override
    public EngineResult<dev.dediren.contracts.layout.SemanticValidationResult> validate(
        SourceDocument source, String profile) {
      throw new UnsupportedOperationException("build driver does not call semantic-validate");
    }

    @Override
    public EngineResult<SceneGraph> projectScene(SourceDocument source, String view) {
      requireKnownView(view);
      LayoutRequest layoutRequest =
          new LayoutRequest(
              ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
              view,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null);
      return new EngineResult<>(
          LayoutRequestMapper.toSceneGraph(layoutRequest),
          warningIf(
              layoutRequestWarningViews,
              view,
              "DEDIREN_FAKE_LAYOUT_REQUEST_WARNING",
              "layout-request warning"));
    }

    @Override
    public EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view)
        throws EngineException {
      requireKnownView(view);
      if (metadataFailingViews.contains(view)) {
        throw fakeFailure("DEDIREN_FAKE_METADATA_FAILED", "render-metadata blew up");
      }
      return new EngineResult<>(
          new RenderMetadata(
              ContractVersions.RENDER_METADATA_SCHEMA_VERSION,
              "generic-graph",
              Map.of(),
              Map.of(),
              Map.of()),
          List.of());
    }

    // Reproduces GenericGraphEngine's real observable for an unresolvable view: a raw
    // UncheckedIOException structural failure, not an EngineException.
    private static void requireKnownView(String view) {
      if (!Set.of("overview", "detail").contains(view)) {
        throw new java.io.UncheckedIOException(
            new java.io.IOException("missing generic-graph view " + view));
      }
    }
  }

  private record FakeLayoutEngine(
      Set<String> failingViews,
      Set<String> qualityWarningViews,
      Set<String> layoutStageWarningViews,
      Set<String> qualityFailingViews)
      implements LayoutEngine {
    @Override
    public String id() {
      return "elk-layout";
    }

    @Override
    public SceneGraph parseRequest(byte[] input) {
      LayoutRequest request =
          JsonSupport.objectMapper()
              .treeToValue(JsonSupport.objectMapper().readTree(input), LayoutRequest.class);
      return LayoutRequestMapper.toSceneGraph(request);
    }

    @Override
    public EngineResult<LaidOutScene> layout(SceneGraph scene) throws EngineException {
      String view = scene.viewId();
      if (failingViews.contains(view)) {
        throw fakeFailure("DEDIREN_FAKE_LAYOUT_FAILED", "layout blew up");
      }
      if (qualityFailingViews.contains(view)) {
        // A layout that clears the layout stage but fails LayoutQuality.validateLayoutDiagnostics:
        // an edge with no route points -> DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY (ERROR).
        return new EngineResult<>(
            new LaidOutScene(
                view,
                List.of(),
                List.of(
                    new RoutedEdge(
                        "bad-edge", "client", "api", null, null, null, null, null, null)),
                List.of(),
                List.of()),
            List.of());
      }
      // qualityWarningViews embeds an upstream warning in the LaidOutScene so the real quality
      // validation degrades to WARNING; layoutStageWarningViews warns on the layout stage envelope.
      List<Diagnostic> embeddedWarnings =
          warningIf(qualityWarningViews, view, "DEDIREN_FAKE_UPSTREAM", "upstream warning");
      List<Diagnostic> stageWarnings =
          warningIf(
              layoutStageWarningViews,
              view,
              "DEDIREN_FAKE_LAYOUT_STAGE_WARNING",
              "layout stage warning");
      return new EngineResult<>(
          new LaidOutScene(view, List.of(), List.of(), List.of(), embeddedWarnings), stageWarnings);
    }
  }

  private record FakeRenderEngine(Set<String> failingViews) implements RenderEngine {
    @Override
    public String id() {
      return "render";
    }

    @Override
    public EngineResult<RenderResult> render(
        LaidOutScene scene, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException {
      if (failingViews.contains(scene.viewId())) {
        throw fakeFailure("DEDIREN_FAKE_RENDER_FAILED", "render blew up");
      }
      return new EngineResult<>(
          new RenderResult(
              ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
              List.of(new RenderArtifact("svg", "<svg/>"))),
          List.of());
    }
  }

  private record FakeExportEngine(
      String id,
      String artifactKind,
      String content,
      Set<String> failingViews,
      Set<String> warningViews)
      implements ExportEngine {
    @Override
    public EngineResult<ExportResult> export(
        dev.dediren.contracts.export.ExportRequest request,
        Map<String, String> env,
        Path productRoot)
        throws EngineException {
      String view = request.layoutResult().viewId();
      if (failingViews.contains(view)) {
        throw fakeFailure("DEDIREN_FAKE_EXPORT_FAILED", "export blew up");
      }
      return new EngineResult<>(
          new ExportResult(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, artifactKind, content),
          warningIf(warningViews, view, "DEDIREN_FAKE_EXPORT_WARNING", "export warning"));
    }
  }
}

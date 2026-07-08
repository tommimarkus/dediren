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
import dev.dediren.contracts.layout.LayoutResult;
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
              assertThat(diagnostic.message()).contains("no-such-view");
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

  // --- helpers ---------------------------------------------------------------------------------

  private BuildRequest renderOnlyRequest() {
    return new BuildRequest(SOURCE, null, List.of(), "{}", null, null, Set.of(), out, Map.of());
  }

  private static Engines engines() {
    return enginesWith(Set.of(), Set.of());
  }

  private static Engines enginesWith(Set<String> failingViews, Set<String> warningViews) {
    return Engines.of(
        List.of(new FakeSemanticsEngine()),
        List.of(new FakeLayoutEngine(failingViews, warningViews)),
        List.of(new FakeRenderEngine()),
        List.of(
            new FakeExportEngine("archimate-oef", "archimate+xml", "<oef/>"),
            new FakeExportEngine("uml-xmi", "uml+xml", "<xmi/>")));
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

  private static final class FakeSemanticsEngine implements SemanticsEngine {
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
    public EngineResult<LayoutRequest> projectLayoutRequest(SourceDocument source, String view) {
      requireKnownView(view);
      return new EngineResult<>(
          new LayoutRequest(
              ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
              view,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null),
          List.of());
    }

    @Override
    public EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view) {
      requireKnownView(view);
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

  private record FakeLayoutEngine(Set<String> failingViews, Set<String> warningViews)
      implements LayoutEngine {
    @Override
    public String id() {
      return "elk-layout";
    }

    @Override
    public LayoutRequest parseRequest(byte[] input) {
      return JsonSupport.objectMapper()
          .treeToValue(JsonSupport.objectMapper().readTree(input), LayoutRequest.class);
    }

    @Override
    public EngineResult<LayoutResult> layout(LayoutRequest request) throws EngineException {
      String view = request.viewId();
      if (failingViews.contains(view)) {
        throw new EngineException(
            List.of(
                new Diagnostic(
                    "DEDIREN_FAKE_LAYOUT_FAILED",
                    DiagnosticSeverity.ERROR,
                    "layout blew up",
                    null)),
            3);
      }
      List<Diagnostic> warnings =
          warningViews.contains(view)
              ? List.of(
                  new Diagnostic(
                      "DEDIREN_FAKE_UPSTREAM",
                      DiagnosticSeverity.WARNING,
                      "upstream warning",
                      null))
              : List.of();
      return new EngineResult<>(
          new LayoutResult(
              ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
              view,
              List.of(),
              List.of(),
              List.of(),
              warnings),
          List.of());
    }
  }

  private static final class FakeRenderEngine implements RenderEngine {
    @Override
    public String id() {
      return "render";
    }

    @Override
    public EngineResult<RenderResult> render(
        LayoutResult layout, JsonNode policy, RenderMetadata metadataOrNull) {
      return new EngineResult<>(
          new RenderResult(
              ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
              List.of(new RenderArtifact("svg", "<svg/>"))),
          List.of());
    }
  }

  private record FakeExportEngine(String id, String artifactKind, String content)
      implements ExportEngine {
    @Override
    public EngineResult<ExportResult> export(
        dev.dediren.contracts.export.ExportRequest request,
        Map<String, String> env,
        Path productRoot) {
      return new EngineResult<>(
          new ExportResult(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, artifactKind, content),
          List.of());
    }
  }
}

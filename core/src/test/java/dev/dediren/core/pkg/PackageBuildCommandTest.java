package dev.dediren.core.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.pkg.PackageBuildResult;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Drives the in-memory package build over fake engines — like {@code BuildCommandTest} but
 * exercising package concerns: declared-path materialization, per-view accessibility injection into
 * the render policy, view- vs model-target export routing, and one enveloped result. The real
 * {@code SourceValidator}, schema validation, {@code PackageValidator}, and layout-quality
 * validation stay in the loop.
 */
class PackageBuildCommandTest {

  private static final String MODEL =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [ { "id": "a", "type": "generic.component", "label": "A", "properties": {} } ],
        "relationships": [],
        "plugins": {}
      }
      """;

  private static final String RENDER_POLICY =
      """
      {
        "render_policy_schema_version": "render-policy.schema.v3",
        "page": { "width": 100, "height": 100 },
        "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 }
      }
      """;

  private static final String OEF_POLICY =
      """
      {
        "oef_export_policy_schema_version": "oef-export-policy.schema.v1",
        "model_identifier": "m", "model_name": "M",
        "view_identifier": "v", "view_name": "V", "viewpoint": "vp"
      }
      """;

  @Test
  void buildsAViewAndExportToDeclaredPaths(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "presentation": { "title": "Main View", "question": "How?" },
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ],
          "exports": [
            { "id": "oef", "view": "main", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/main.oef.xml" }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("ok");
    PackageBuildResult result = result(outcome);
    assertThat(result.status().wire()).isEqualTo("ok");
    assertThat(result.views()).hasSize(1);
    assertThat(result.views().getFirst().artifacts())
        .containsEntry("diagram", "generated/svg/main.svg");
    assertThat(result.exports().getFirst().artifact()).isEqualTo("generated/export/main.oef.xml");
    assertThat(Files.exists(dir.resolve("generated/svg/main.svg"))).isTrue();
    assertThat(Files.readString(dir.resolve("generated/export/main.oef.xml")))
        .contains("view=\"main\"");
  }

  @Test
  void foldsPerViewPresentationIntoTheRenderedDiagram(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "presentation": { "title": "Injected Title" },
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("ok");
    // The fake renderer echoes the effective policy's accessibility.title into the SVG, so the
    // written diagram proves the view's presentation reached the render lane.
    assertThat(Files.readString(dir.resolve("generated/svg/main.svg"))).contains("Injected Title");
  }

  @Test
  void writesOptionalRenderMetadataAndLayoutWhenDeclared(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": {
                "diagram": "generated/svg/main.svg",
                "render_metadata": "generated/render-metadata/main.json",
                "layout": "generated/layout/main.json" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("ok");
    Map<String, String> artifacts = result(outcome).views().getFirst().artifacts();
    assertThat(artifacts).containsKeys("diagram", "render_metadata", "layout");
    assertThat(Files.exists(dir.resolve("generated/render-metadata/main.json"))).isTrue();
    assertThat(Files.exists(dir.resolve("generated/layout/main.json"))).isTrue();
    // The emitted stage files are the unwrapped payloads, not command envelopes.
    assertThat(Files.readString(dir.resolve("generated/render-metadata/main.json")))
        .contains("render_metadata_schema_version")
        .doesNotContain("envelope_schema_version");
  }

  @Test
  void routesAModelTargetExportThroughTheAggregateLane(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    Files.writeString(dir.resolve("model-uml.json"), MODEL);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [
            { "id": "arch", "source": "model.json" },
            { "id": "uml", "source": "model-uml.json" }
          ],
          "views": [
            { "id": "a", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/a.svg" } },
            { "id": "b", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/b.svg" } }
          ],
          "exports": [
            { "id": "arch-oef", "model": "arch", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/arch.oef.xml" }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("ok");
    // The aggregate lane received every view of the model, in order.
    assertThat(Files.readString(dir.resolve("generated/export/arch.oef.xml")))
        .contains("views=\"a,b\"");
    assertThat(result(outcome).exports().getFirst().model()).isEqualTo("arch");
  }

  @Test
  void schemaInvalidPackageIsRejectedBeforeExecution(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg = "{ \"package_schema_version\": \"package.schema.v1\", \"views\": [] }";

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(result(outcome).views()).isEmpty();
  }

  @Test
  void unknownModelReferenceIsRejected(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "ghost", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(result(outcome).diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_PACKAGE_MODEL_UNKNOWN"));
  }

  @Test
  void aFailingViewDoesNotAbortTheOthers(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "boom", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/boom.svg" } },
            { "id": "ok", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/ok.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false, Set.of("boom"));

    assertThat(status(outcome)).isEqualTo("error");
    PackageBuildResult result = result(outcome);
    assertThat(result.views()).hasSize(2);
    assertThat(
            result.views().stream()
                .filter(v -> v.viewId().equals("ok"))
                .findFirst()
                .orElseThrow()
                .status()
                .wire())
        .isEqualTo("ok");
    // The healthy view still materialized despite the sibling's failure.
    assertThat(Files.exists(dir.resolve("generated/svg/ok.svg"))).isTrue();
  }

  @Test
  void noExportSuppressesTheExportLane(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ],
          "exports": [
            { "id": "oef", "view": "main", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/main.oef.xml" }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), true);

    assertThat(status(outcome)).isEqualTo("ok");
    assertThat(result(outcome).exports()).isEmpty();
    assertThat(Files.exists(dir.resolve("generated/export/main.oef.xml"))).isFalse();
  }

  // --- harness ----------------------------------------------------------------------------------

  private static void writeInputs(Path dir) throws Exception {
    Files.writeString(dir.resolve("model.json"), MODEL);
    Files.writeString(dir.resolve("render-policy.json"), RENDER_POLICY);
    Files.writeString(dir.resolve("export-policy.json"), OEF_POLICY);
  }

  private static EngineRunOutcome run(String pkg, Path dir, List<String> views, boolean noExport)
      throws EngineExecutionException {
    return run(pkg, dir, views, noExport, Set.of());
  }

  private static EngineRunOutcome run(
      String pkg, Path dir, List<String> views, boolean noExport, Set<String> renderFailingViews)
      throws EngineExecutionException {
    Engines engines =
        Engines.of(
            List.of(new FakeSemanticsEngine()),
            List.of(new FakeLayoutEngine()),
            List.of(new FakeRenderEngine(renderFailingViews)),
            List.of(
                new FakeExportEngine("archimate-oef", "archimate-oef+xml"),
                new FakeExportEngine("uml-xmi", "uml-xmi+xml")));
    return PackageBuildCommand.run(
        new PackageBuildRequest(pkg, dir, dir, Map.of(), views, noExport), engines);
  }

  private static String status(EngineRunOutcome outcome) {
    return JsonSupport.objectMapper().readTree(outcome.stdout()).get("status").asText();
  }

  private static PackageBuildResult result(EngineRunOutcome outcome) {
    return JsonSupport.objectMapper()
        .treeToValue(
            JsonSupport.objectMapper().readTree(outcome.stdout()).get("data"),
            PackageBuildResult.class);
  }

  private record FakeSemanticsEngine() implements SemanticsEngine {
    @Override
    public String id() {
      return "generic-graph";
    }

    @Override
    public EngineResult<dev.dediren.contracts.layout.SemanticValidationResult> validate(
        SourceDocument source, String profile) {
      throw new UnsupportedOperationException("package build does not call semantic-validate");
    }

    @Override
    public EngineResult<SceneGraph> projectScene(SourceDocument source, String view) {
      LayoutRequest request =
          new LayoutRequest(
              ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
              view,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null);
      return new EngineResult<>(LayoutRequestMapper.toSceneGraph(request), List.of());
    }

    @Override
    public EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view) {
      return new EngineResult<>(
          new RenderMetadata(
              ContractVersions.RENDER_METADATA_SCHEMA_VERSION,
              "generic-graph",
              Map.of(),
              Map.of(),
              Map.of()),
          List.of());
    }
  }

  private record FakeLayoutEngine() implements LayoutEngine {
    @Override
    public String id() {
      return "elk-layout";
    }

    @Override
    public SceneGraph parseRequest(byte[] input) {
      return LayoutRequestMapper.toSceneGraph(
          JsonSupport.objectMapper()
              .treeToValue(JsonSupport.objectMapper().readTree(input), LayoutRequest.class));
    }

    @Override
    public EngineResult<LaidOutScene> layout(SceneGraph scene) {
      return new EngineResult<>(
          new LaidOutScene(scene.viewId(), List.of(), List.of(), List.of(), List.of()), List.of());
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
        throw new EngineException(
            List.of(
                new dev.dediren.contracts.Diagnostic(
                    "DEDIREN_FAKE_RENDER_FAILED",
                    dev.dediren.contracts.DiagnosticSeverity.ERROR,
                    "render blew up",
                    null)),
            3);
      }
      String title = policy.path("accessibility").path("title").asText("");
      return new EngineResult<>(
          new RenderResult(
              ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
              List.of(new RenderArtifact("svg", "<svg>" + title + "</svg>"))),
          List.of());
    }
  }

  private record FakeExportEngine(String id, String artifactKind) implements ExportEngine {
    @Override
    public EngineResult<ExportResult> export(
        dev.dediren.contracts.export.ExportRequest request, Map<String, String> env, Path root) {
      return new EngineResult<>(
          new ExportResult(
              ContractVersions.EXPORT_RESULT_SCHEMA_VERSION,
              artifactKind,
              "<export view=\"" + request.layoutResult().viewId() + "\"/>"),
          List.of());
    }

    @Override
    public Optional<EngineResult<ExportResult>> exportModel(
        ModelExportRequest request, Map<String, String> env, Path root) {
      if (request.views().isEmpty()) {
        return Optional.empty();
      }
      String views =
          request.views().stream()
              .map(ModelExportRequest.ViewLayout::viewId)
              .reduce((left, right) -> left + "," + right)
              .orElse("");
      return Optional.of(
          new EngineResult<>(
              new ExportResult(
                  ContractVersions.EXPORT_RESULT_SCHEMA_VERSION,
                  artifactKind,
                  "<model views=\"" + views + "\"/>"),
              List.of()));
    }
  }
}

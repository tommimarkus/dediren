package dev.dediren.core.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.pkg.PackageBuildResult;
import dev.dediren.contracts.pkg.PackageExportOutcome;
import dev.dediren.contracts.pkg.PackageViewOutcome;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.analysis.Provenance;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.schema.SchemaValidator;
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

  private static final String RENDER_POLICY_WITH_A11Y =
      """
      {
        "render_policy_schema_version": "render-policy.schema.v3",
        "page": { "width": 100, "height": 100 },
        "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 },
        "accessibility": { "title": "Policy Title" }
      }
      """;

  // A prior-version policy the SchemaVersionGate rejects as OUTDATED, attaching the composed
  // migration path; the render engine never sees it.
  private static final String STALE_RENDER_POLICY =
      """
      {
        "render_policy_schema_version": "render-policy.schema.v2",
        "page": { "width": 100, "height": 100 },
        "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 }
      }
      """;

  // A stub policy carrying the current schema version; the fake export engines ignore the body.
  private static final String XMI_POLICY =
      "{\"uml_xmi_export_policy_schema_version\":\"uml-xmi-export-policy.schema.v1\"}";

  // Two views spanning two families: the classifier kind (uml-class) is in the class family the
  // uml-xmi model aggregate covers; uml-activity is not.
  private static final String CLASS_AND_ACTIVITY_MODEL =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "a", "type": "generic.component", "label": "A", "properties": {} },
          { "id": "b", "type": "generic.component", "label": "B", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
          "generic-graph": {
            "views": [
              { "id": "class-view", "label": "Class", "kind": "uml-class",
                "nodes": ["a", "b"], "relationships": [] },
              { "id": "activity-view", "label": "Activity", "kind": "uml-activity",
                "nodes": ["a", "b"], "relationships": [] }
            ]
          }
        }
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

  @Test
  void viewsFilterBuildsOnlyTheSelectedViews(@TempDir Path dir) throws Exception {
    writeInputs(dir);

    EngineRunOutcome outcome = run(twoViewPackage(), dir, List.of("a"), false);

    assertThat(status(outcome)).isEqualTo("ok");
    assertThat(result(outcome).views()).extracting(PackageViewOutcome::viewId).containsExactly("a");
    assertThat(Files.exists(dir.resolve("generated/svg/a.svg"))).isTrue();
    assertThat(Files.exists(dir.resolve("generated/svg/b.svg"))).isFalse();
  }

  @Test
  void anUnknownViewInTheFilterIsRejected(@TempDir Path dir) throws Exception {
    writeInputs(dir);

    EngineRunOutcome outcome = run(twoViewPackage(), dir, List.of("ghost"), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(result(outcome).diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_PACKAGE_VIEW_UNKNOWN"));
  }

  @Test
  void anExportTargetingAViewThatFailedToBuildIsReported(@TempDir Path dir) throws Exception {
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

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false, Set.of("main"));

    assertThat(status(outcome)).isEqualTo("error");
    PackageExportOutcome export = result(outcome).exports().getFirst();
    assertThat(export.status().wire()).isEqualTo("error");
    assertThat(export.diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_PACKAGE_VIEW_UNKNOWN"));
    assertThat(Files.exists(dir.resolve("generated/export/main.oef.xml"))).isFalse();
  }

  @Test
  void aModelTargetExportWithNoBuiltViewsFails(@TempDir Path dir) throws Exception {
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
            { "id": "agg", "model": "arch", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/arch.oef.xml" }
          ]
        }
        """;

    // The model's only view fails to build, so the aggregate lane receives zero views.
    EngineRunOutcome outcome = run(pkg, dir, List.of(), false, Set.of("main"));

    assertThat(status(outcome)).isEqualTo("error");
    PackageExportOutcome export = result(outcome).exports().getFirst();
    assertThat(export.status().wire()).isEqualTo("error");
    assertThat(export.diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_ENGINE_FAILED"));
  }

  @Test
  void aStageWarningRollsUpToPackageWarningStatus(@TempDir Path dir) throws Exception {
    writeInputs(dir);

    EngineRunOutcome outcome =
        run(singleViewPackage(), dir, List.of(), false, Set.of(), Set.of("main"));

    assertThat(status(outcome)).isEqualTo("warning");
    assertThat(outcome.exitCode()).isZero();
    assertThat(result(outcome).views().getFirst().status().wire()).isEqualTo("warning");
  }

  @Test
  void policyAccessibilityWinsOverViewPresentation(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    Files.writeString(dir.resolve("render-policy-a11y.json"), RENDER_POLICY_WITH_A11Y);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy-a11y.json",
              "presentation": { "title": "Injected Title" },
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("ok");
    // The policy already set accessibility.title, so the presentation must NOT override it.
    assertThat(Files.readString(dir.resolve("generated/svg/main.svg")))
        .contains("Policy Title")
        .doesNotContain("Injected Title");
  }

  @Test
  void anEscapingDeclaredOutputPathIsRejectedAndNeverWritten(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "../escape.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(result(outcome).views().getFirst().diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID"));
    assertThat(Files.exists(dir.getParent().resolve("escape.svg"))).isFalse();
  }

  @Test
  void anEscapingDeclaredModelSourceIsRejected(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    // A real file that exists OUTSIDE the confinement root — the resolve succeeds, the confinement
    // check rejects it (a true escape, not merely a not-found).
    Files.writeString(dir.getParent().resolve("outside-model.json"), MODEL);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "../outside-model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(result(outcome).views().getFirst().diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID"));
  }

  @Test
  void confinementIsEnforcedAgainstTheConfinementRootNotBaseDir(@TempDir Path root)
      throws Exception {
    // MCP-shape: baseDir (the package dir) is a subdirectory of the confinement root. A path that
    // escapes baseDir but stays inside the confinement root is allowed — proving the boundary is
    // the
    // confinement root, not baseDir.
    Path pkgDir = Files.createDirectories(root.resolve("pkg"));
    Files.writeString(pkgDir.resolve("model.json"), MODEL);
    Files.writeString(pkgDir.resolve("render-policy.json"), RENDER_POLICY);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "../root-level.svg" } }
          ]
        }
        """;

    EngineRunOutcome outcome =
        PackageBuildCommand.run(
            new PackageBuildRequest(pkg, pkgDir, root, Map.of(), List.of(), false),
            engines(Set.of(), Set.of()));

    assertThat(status(outcome)).describedAs(outcome.stdout()).isEqualTo("ok");
    assertThat(Files.exists(root.resolve("root-level.svg"))).isTrue();
    assertThat(Files.exists(pkgDir.resolve("root-level.svg"))).isFalse();
  }

  @Test
  void anOversizedModelSourceIsRejectedByTheInputCeiling(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    // A sparse file just over the 64 MiB ceiling: BoundedReads rejects on Files.size before any
    // read, so this is fast and never allocates the file — the regression guard for the OOM vector.
    try (java.io.RandomAccessFile raf =
        new java.io.RandomAccessFile(dir.resolve("model.json").toFile(), "rw")) {
      raf.setLength(64L * 1024 * 1024 + 1);
    }

    EngineRunOutcome outcome = run(singleViewPackage(), dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(result(outcome).views().getFirst().diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_INPUT_FILE_TOO_LARGE"));
  }

  @Test
  void stampsDiagramAndExportArtifactsWithProvenance(@TempDir Path dir) throws Exception {
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
          ],
          "exports": [
            { "id": "view-oef", "view": "main", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/main.oef.xml" },
            { "id": "model-oef", "model": "arch", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/arch.oef.xml" }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).describedAs(outcome.stdout()).isEqualTo("ok");
    // The diagram carries the same in-root <metadata> stamp the twin's render lane injects, with
    // the view id and the render-policy hash extractable from it.
    String svg = Files.readString(dir.resolve("generated/svg/main.svg"));
    JsonNode svgStamp = Provenance.extract(svg).orElseThrow();
    assertThat(svg).contains("<metadata id=\"dediren-provenance\">");
    assertThat(svgStamp.path("view_id").asText()).isEqualTo("main");
    assertThat(svgStamp.path("model_sha256").asText()).isNotEmpty();
    assertThat(svgStamp.path("render_policy_sha256").asText()).isNotEmpty();
    // Exports gain the twin's leading provenance comment: the view id for a view-scoped export,
    // the literal "model" for a whole-model aggregate.
    String viewExport = Files.readString(dir.resolve("generated/export/main.oef.xml"));
    assertThat(viewExport).startsWith("<!-- dediren-provenance ");
    JsonNode viewStamp = Provenance.extract(viewExport).orElseThrow();
    assertThat(viewStamp.path("view_id").asText()).isEqualTo("main");
    assertThat(viewStamp.path("oef_policy_sha256").asText()).isNotEmpty();
    JsonNode modelStamp =
        Provenance.extract(Files.readString(dir.resolve("generated/export/arch.oef.xml")))
            .orElseThrow();
    assertThat(modelStamp.path("view_id").asText()).isEqualTo("model");
    assertThat(modelStamp.path("model_sha256").asText())
        .isEqualTo(svgStamp.path("model_sha256").asText());
    // The JSON side outputs stay unstamped, exactly like the twin's --emit stage files.
    assertThat(Files.readString(dir.resolve("generated/layout/main.json")))
        .doesNotContain("dediren-provenance");
    assertThat(Files.readString(dir.resolve("generated/render-metadata/main.json")))
        .doesNotContain("dediren-provenance");
  }

  @Test
  void modelScopedXmiExportCoversOnlyClassFamilyViews(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    Files.writeString(dir.resolve("model-kinds.json"), CLASS_AND_ACTIVITY_MODEL);
    Files.writeString(dir.resolve("xmi-policy.json"), XMI_POLICY);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "uml", "source": "model-kinds.json" } ],
          "views": [
            { "id": "class-view", "model": "uml", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/class-view.svg" } },
            { "id": "activity-view", "model": "uml", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/activity-view.svg" } }
          ],
          "exports": [
            { "id": "uml-agg", "model": "uml", "lane": "uml-xmi",
              "policy": "xmi-policy.json", "output": "generated/export/model.uml.xml" },
            { "id": "oef-agg", "model": "uml", "lane": "archimate-oef",
              "policy": "export-policy.json", "output": "generated/export/model.oef.xml" }
          ]
        }
        """;

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).describedAs(outcome.stdout()).isEqualTo("ok");
    // The class-family gate (the twin's CLASS_FAMILY_KINDS) feeds only classifier-kind views to
    // the uml-xmi model aggregate; the OEF aggregate still receives every view of the model.
    assertThat(Files.readString(dir.resolve("generated/export/model.uml.xml")))
        .contains("views=\"class-view\"")
        .doesNotContain("activity-view");
    assertThat(Files.readString(dir.resolve("generated/export/model.oef.xml")))
        .contains("views=\"class-view,activity-view\"");
  }

  @Test
  void staleRenderPolicyCarriesMigrationAndResultObeysItsSchema(@TempDir Path dir)
      throws Exception {
    writeInputs(dir);
    Files.writeString(dir.resolve("render-policy.json"), STALE_RENDER_POLICY);

    EngineRunOutcome outcome = run(singleViewPackage(), dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    assertThat(outcome.exitCode()).isEqualTo(2);
    JsonNode data = JsonSupport.objectMapper().readTree(outcome.stdout()).get("data");
    // The gate's OUTDATED diagnostic keeps its machine-readable migration payload end to end.
    JsonNode migration = null;
    for (JsonNode diagnostic : data.at("/views/0/diagnostics")) {
      if ("DEDIREN_SCHEMA_VERSION_OUTDATED".equals(diagnostic.path("code").asText())) {
        migration = diagnostic.path("migration");
      }
    }
    assertThat(migration).describedAs(outcome.stdout()).isNotNull();
    assertThat(migration.isMissingNode()).describedAs(outcome.stdout()).isFalse();
    assertThat(migration.path("from").asText()).isEqualTo("render-policy.schema.v2");
    assertThat(migration.path("to").asText()).isEqualTo("render-policy.schema.v3");
    assertThat(migration.path("operations")).isNotEmpty();
    // The emitted result must satisfy its own published schema, migration payload included.
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/package-build-result.schema.json", data);
    assertThat(errors).describedAs(outcome.stdout()).isEmpty();
  }

  @Test
  void artifactWriteFailureIsAnInputError(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    // A directory squatting on the declared diagram path makes the write itself fail.
    Files.createDirectories(dir.resolve("generated/svg/main.svg"));

    EngineRunOutcome outcome = run(singleViewPackage(), dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    // COMMAND_IO_FAILED + INPUT_ERROR, mirroring the twin's artifact-write mapping.
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(result(outcome).views().getFirst().diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_COMMAND_IO_FAILED"));
  }

  @Test
  void unreadableRenderPolicyIsAnInputError(@TempDir Path dir) throws Exception {
    writeInputs(dir);
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "policy-dir",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;
    // The policy path resolves (it exists) but cannot be read as a file.
    Files.createDirectories(dir.resolve("policy-dir"));

    EngineRunOutcome outcome = run(pkg, dir, List.of(), false);

    assertThat(status(outcome)).isEqualTo("error");
    // COMMAND_IO_FAILED + INPUT_ERROR, matching the model-read lane and the CLI's policy mapping.
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(result(outcome).views().getFirst().diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_COMMAND_IO_FAILED"));
  }

  // --- harness ----------------------------------------------------------------------------------

  private static void writeInputs(Path dir) throws Exception {
    Files.writeString(dir.resolve("model.json"), MODEL);
    Files.writeString(dir.resolve("render-policy.json"), RENDER_POLICY);
    Files.writeString(dir.resolve("export-policy.json"), OEF_POLICY);
  }

  private static EngineRunOutcome run(String pkg, Path dir, List<String> views, boolean noExport)
      throws EngineExecutionException {
    return run(pkg, dir, views, noExport, Set.of(), Set.of());
  }

  private static EngineRunOutcome run(
      String pkg, Path dir, List<String> views, boolean noExport, Set<String> renderFailingViews)
      throws EngineExecutionException {
    return run(pkg, dir, views, noExport, renderFailingViews, Set.of());
  }

  private static EngineRunOutcome run(
      String pkg,
      Path dir,
      List<String> views,
      boolean noExport,
      Set<String> renderFailingViews,
      Set<String> layoutWarningViews)
      throws EngineExecutionException {
    return PackageBuildCommand.run(
        new PackageBuildRequest(pkg, dir, dir, Map.of(), views, noExport),
        engines(renderFailingViews, layoutWarningViews));
  }

  private static Engines engines(Set<String> renderFailingViews, Set<String> layoutWarningViews) {
    return Engines.of(
        List.of(new FakeSemanticsEngine()),
        List.of(new FakeLayoutEngine(layoutWarningViews)),
        List.of(new FakeRenderEngine(renderFailingViews)),
        List.of(
            new FakeExportEngine("archimate-oef", "archimate-oef+xml"),
            new FakeExportEngine("uml-xmi", "uml-xmi+xml")));
  }

  private static String singleViewPackage() {
    return """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;
  }

  private static String twoViewPackage() {
    return """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "a", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/a.svg" } },
            { "id": "b", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/b.svg" } }
          ]
        }
        """;
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

  private record FakeLayoutEngine(java.util.Set<String> warningViews) implements LayoutEngine {
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
      java.util.List<dev.dediren.contracts.Diagnostic> diagnostics =
          warningViews.contains(scene.viewId())
              ? List.of(
                  new dev.dediren.contracts.Diagnostic(
                      "DEDIREN_FAKE_LAYOUT_WARNING",
                      dev.dediren.contracts.DiagnosticSeverity.WARNING,
                      "layout warning",
                      null))
              : List.of();
      return new EngineResult<>(
          new LaidOutScene(scene.viewId(), List.of(), List.of(), List.of(), List.of()),
          diagnostics);
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

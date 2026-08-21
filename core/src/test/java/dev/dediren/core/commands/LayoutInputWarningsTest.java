package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.RenderEngine;
import dev.dediren.ir.LaidOutScene;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The warn-first layout-input lane.
 *
 * <p>Two things are pinned here. First, the two structural checks (duplicate id, non-positive
 * extent) live on the WARNING lane: they degrade the envelope to {@code warning} and leave the exit
 * code at 0, rather than joining the hard lane where {@code validate-layout} and {@code build}
 * would start rejecting layouts they accept today. Second, {@code render} — the only lane that
 * takes a caller-supplied layout result and turns it into an artifact — now reports the hard lane's
 * findings too, downgraded to {@code warning}, and still produces the artifact. The last test is
 * the one that protects the existing render goldens: a clean layout must stay {@code ok} with no
 * diagnostics at all.
 */
class LayoutInputWarningsTest {

  @Test
  void validateLayoutWarnsOnDuplicateIdsWithoutFailing() {
    // Separated geometry on purpose: the duplicate id, not an incidental overlap, must be what
    // degrades the envelope.
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
            { "id": "a", "source_id": "a2", "projection_id": "a2", "x": 400.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A again" }
          ],
          "edges": [],
          "groups": [],
          "warnings": []
        }
        """;

    ValidationResult result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.envelope().diagnostics())
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_LAYOUT_DUPLICATE_ID");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.message()).contains("'a'");
              assertThat(diagnostic.path()).isEqualTo("$.nodes[?(@.id=='a')]");
            });
  }

  @Test
  void validateLayoutWarnsOnNonPositiveExtentsWithoutFailing() {
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "collapsed", "source_id": "collapsed", "projection_id": "collapsed", "x": 0.0, "y": 0.0, "width": 0.0, "height": 80.0, "label": "Collapsed" },
            { "id": "inverted", "source_id": "inverted", "projection_id": "inverted", "x": 400.0, "y": 0.0, "width": 100.0, "height": -20.0, "label": "Inverted" }
          ],
          "edges": [],
          "groups": [
            { "id": "flat", "source_id": "flat", "projection_id": "flat", "x": 0.0, "y": 200.0, "width": 300.0, "height": 0.0, "members": [], "label": "Flat" }
          ],
          "warnings": []
        }
        """;

    ValidationResult result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    // The group case is here rather than in its own test because the group extent loop is written
    // separately from the node one (own message, own null sourcePointer) rather than sharing a
    // helper — so a copy-paste slip in it would otherwise go uncaught.
    assertThat(result.envelope().diagnostics())
        .hasSize(3)
        .allSatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_LAYOUT_NON_POSITIVE_EXTENT");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
            })
        .extracting(Diagnostic::path)
        .containsExactly("$.nodes[0]", "$.nodes[1]", "$.groups[0]");
  }

  @Test
  void validateLayoutWarnsOnDuplicateEdgeIds() {
    // The third id space. Nodes and groups are covered above; edges run through the same helper
    // but are the space the SVG marker ids are minted from, so the call site is pinned too.
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 60.0, "label": "A" },
            { "id": "b", "source_id": "b", "projection_id": "b", "x": 300.0, "y": 0.0, "width": 100.0, "height": 60.0, "label": "B" }
          ],
          "edges": [
            { "id": "flow", "source": "a", "target": "b", "source_id": "flow", "projection_id": "flow", "points": [ { "x": 100.0, "y": 30.0 }, { "x": 300.0, "y": 30.0 } ], "label": "" },
            { "id": "flow", "source": "b", "target": "a", "source_id": "flow", "projection_id": "flow", "points": [ { "x": 300.0, "y": 50.0 }, { "x": 100.0, "y": 50.0 } ], "label": "" }
          ],
          "groups": [],
          "warnings": []
        }
        """;

    ValidationResult result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.envelope().diagnostics())
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_LAYOUT_DUPLICATE_ID");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.path()).isEqualTo("$.edges[?(@.id=='flow')]");
            });
  }

  @Test
  void validateLayoutWarnsOnDuplicateGroupIdSeparatelyFromNodes() {
    // Ids are compared within a space, not across them: the node and the group both called
    // "shared" are not a collision, while the repeated group id is.
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "shared", "source_id": "shared", "projection_id": "shared", "x": 40.0, "y": 40.0, "width": 100.0, "height": 80.0, "label": "Member" }
          ],
          "edges": [],
          "groups": [
            { "id": "shared", "source_id": "g1", "projection_id": "g1", "x": 0.0, "y": 0.0, "width": 200.0, "height": 200.0, "members": ["shared"], "label": null },
            { "id": "shared", "source_id": "g2", "projection_id": "g2", "x": 400.0, "y": 0.0, "width": 200.0, "height": 200.0, "members": [], "label": null }
          ],
          "warnings": []
        }
        """;

    ValidationResult result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.envelope().diagnostics())
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_LAYOUT_DUPLICATE_ID");
              assertThat(diagnostic.path()).isEqualTo("$.groups[?(@.id=='shared')]");
            });
  }

  @Test
  void renderWarnsOnNonFiniteGeometryAndStillProducesTheSvg() throws Exception {
    // 1e999 is exactly how this reaches the product: JSON has no Infinity literal, but a magnitude
    // this large widens to Double.POSITIVE_INFINITY on parse, and the renderer then writes
    // width="Infinity" into the SVG. Accepted consequence of the warn-first decision — the
    // artifact is still produced, now with the warning attached.
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 1e999, "height": 80.0, "label": "A" }
          ],
          "edges": [],
          "groups": [],
          "warnings": []
        }
        """;

    EngineRunOutcome outcome =
        CoreCommands.renderCommand("render", POLICY, null, layout, Map.of(), renderEngines());

    JsonNode envelope = envelope(outcome);
    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY");
    assertThat(envelope.at("/diagnostics/0/severity").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("$.nodes[0]");
    assertThat(envelope.at("/data/artifacts/0/artifact_kind").asText()).isEqualTo("svg+xml");
    assertThat(envelope.at("/data/artifacts/0/content").asText()).contains("<svg");
  }

  @Test
  void renderWarnsOnDuplicateIdsAndStillProducesTheSvg() throws Exception {
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
            { "id": "a", "source_id": "a2", "projection_id": "a2", "x": 400.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A again" }
          ],
          "edges": [],
          "groups": [],
          "warnings": []
        }
        """;

    EngineRunOutcome outcome =
        CoreCommands.renderCommand("render", POLICY, null, layout, Map.of(), renderEngines());

    JsonNode envelope = envelope(outcome);
    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_DUPLICATE_ID");
    assertThat(envelope.at("/data/artifacts/0/content").asText()).contains("<svg");
  }

  @Test
  void renderKeepsOkEnvelopeAndNoDiagnosticsForACleanLayout() throws Exception {
    // The regression that protects every existing render golden and envelope assertion: the new
    // input checks must add nothing at all to a clean layout's envelope.
    String layout =
        """
        {
          "layout_result_schema_version": "layout-result.schema.v2",
          "view_id": "main",
          "nodes": [
            { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
            { "id": "b", "source_id": "b", "projection_id": "b", "x": 300.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "B" }
          ],
          "edges": [
            {
              "id": "a-to-b",
              "source": "a",
              "target": "b",
              "source_id": "a-to-b",
              "projection_id": "a-to-b",
              "points": [
                { "x": 100.0, "y": 40.0 },
                { "x": 300.0, "y": 40.0 }
              ],
              "label": "calls"
            }
          ],
          "groups": [],
          "warnings": []
        }
        """;

    EngineRunOutcome outcome =
        CoreCommands.renderCommand("render", POLICY, null, layout, Map.of(), renderEngines());

    JsonNode envelope = envelope(outcome);
    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    // Absent or empty — an ok envelope must carry no diagnostic either way.
    assertThat(envelope.at("/diagnostics").size()).isZero();
    assertThat(envelope.at("/data/artifacts/0/content").asText()).contains("<svg");
  }

  @Test
  void renderMergesInputDiagnosticsAheadOfTheEngineOwn() throws Exception {
    // The only test where BOTH sides of EngineDispatch#merge are non-empty. Everywhere else the
    // stub renderer returns no diagnostics of its own, so the merge degenerates to a copy and the
    // documented input-first ordering would survive an accidental swap of the two addAll calls.
    // Ordering is contract, not cosmetics: callers read diagnostics[0].
    EngineRunOutcome outcome =
        CoreCommands.renderCommand(
            "render",
            POLICY,
            null,
            DUPLICATE_ID_LAYOUT,
            Map.of(),
            Engines.of(List.of(), List.of(), List.of(new DiagnosingRenderEngine()), List.of()));

    JsonNode envelope = envelope(outcome);
    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics").size()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_DUPLICATE_ID");
    assertThat(envelope.at("/diagnostics/1/code").asText()).isEqualTo("DEDIREN_ENGINE_SAYS_SO");
  }

  private static final String POLICY =
      "{ \"render_policy_schema_version\": \""
          + ContractVersions.RENDER_POLICY_SCHEMA_VERSION
          + "\" }";

  private static final String DUPLICATE_ID_LAYOUT =
      """
      {
        "layout_result_schema_version": "layout-result.schema.v2",
        "view_id": "main",
        "nodes": [
          { "id": "same", "source_id": "same", "projection_id": "same", "x": 0.0, "y": 0.0, "width": 100.0, "height": 60.0, "label": "One" },
          { "id": "same", "source_id": "same", "projection_id": "same", "x": 300.0, "y": 0.0, "width": 100.0, "height": 60.0, "label": "Two" }
        ],
        "edges": [],
        "groups": [],
        "warnings": []
      }
      """;

  private static Engines renderEngines() {
    return Engines.of(List.of(), List.of(), List.of(new StubRenderEngine()), List.of());
  }

  private static JsonNode envelope(EngineRunOutcome outcome) {
    return JsonSupport.objectMapper().readTree(outcome.stdout());
  }

  /**
   * A renderer that always succeeds with one SVG artifact and no diagnostics of its own. That is
   * what makes these assertions unambiguous: every diagnostic on the envelope came from the layout
   * input lane, and the artifact proves the render still happened. (The real SVG engine lives
   * behind {@code engine-api} and cannot be reached from {@code core}.)
   */
  private record StubRenderEngine() implements RenderEngine {
    @Override
    public String id() {
      return "render";
    }

    @Override
    public EngineResult<RenderResult> render(
        LaidOutScene layout, JsonNode policy, RenderMetadata metadataOrNull) {
      return new EngineResult<>(
          new RenderResult(
              ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
              List.of(new RenderArtifact("svg+xml", "<svg role=\"img\"></svg>"))),
          List.of());
    }
  }

  /** Succeeds, but with a diagnostic of its own — the other half of the merge. */
  private record DiagnosingRenderEngine() implements RenderEngine {
    @Override
    public String id() {
      return "render";
    }

    @Override
    public EngineResult<RenderResult> render(
        LaidOutScene layout, JsonNode policy, RenderMetadata metadataOrNull) {
      return new EngineResult<>(
          new RenderResult(
              ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
              List.of(new RenderArtifact("svg+xml", "<svg role=\"img\"></svg>"))),
          List.of(
              new Diagnostic(
                  "DEDIREN_ENGINE_SAYS_SO",
                  DiagnosticSeverity.WARNING,
                  "engine-owned diagnostic",
                  "$.policy")));
    }
  }
}

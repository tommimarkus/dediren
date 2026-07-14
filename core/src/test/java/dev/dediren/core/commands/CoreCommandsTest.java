package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreCommandsTest {

  @Test
  void semanticValidateCommandRejectsInvalidSourceBeforeEngineLookup() throws Exception {
    // Source validation runs before engine resolution, so an invalid source is reported as its own
    // diagnostics even when the requested engine id does not exist in the registry.
    String input =
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "missing-source",
                      "type": "Association",
                      "source": "missing",
                      "target": "api",
                      "label": "invalid",
                      "properties": {}
                    }
                  ],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """;

    EngineRunOutcome outcome =
        CoreCommands.semanticValidateCommand(
            "missing-engine", "archimate", input, null, Map.of(), emptyEngines());

    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(outcome.stdout()).contains("DEDIREN_DANGLING_ENDPOINT");
  }

  @Test
  void validateLayoutCommandSurfacesQualityWarningAtEnvelopeLevel() throws Exception {
    String layout =
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v2",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
                    { "id": "b", "source_id": "b", "projection_id": "b", "x": 50.0, "y": 20.0, "width": 100.0, "height": 80.0, "label": "B" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """;

    var result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.envelope().diagnostics())
        .extracting(Diagnostic::code)
        .containsOnly("DEDIREN_LAYOUT_QUALITY_WARNING");
    assertThat(result.envelope().data().at("/status").asText()).isEqualTo("warning");
    assertThat(result.envelope().data().at("/overlap_count").asInt()).isEqualTo(1);
  }

  @Test
  void validateLayoutCommandKeepsOkEnvelopeForCleanLayout() throws Exception {
    String layout =
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v2",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """;

    var result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(result.envelope().diagnostics()).isEmpty();
  }

  @Test
  void validateLayoutAcceptsAValidSequenceLayoutsInvariants() {
    // Regression guard (Plan B P5, Task 6): a geometrically valid UML-sequence layout — two
    // lifelines, three messages whose route-point endpoints sit exactly on the lifeline center-x
    // axes and whose y strictly increases, no interaction frame — must still yield the "ok"
    // verdict once SequenceInvariants is wired into the hard-error lane. Mirrors
    // fixtures/layout-result/uml-sequence-validatable.json (also exercised end-to-end by
    // CliLayoutRenderCommandTest#validateLayoutAcceptsSequenceLifelineMessageEndpoints).
    var customer = lifeline("customer", 100.0, 100.0); // center-x = 170
    var service = lifeline("service", 520.0, 104.0); // center-x = 590
    var m1 = message("m1", "customer", "service", 170.0, 180.0, 590.0, 180.0);
    var m2 = message("m2", "service", "customer", 590.0, 220.0, 170.0, 220.0);
    var m3 = message("m3", "service", "customer", 590.0, 260.0, 170.0, 260.0);
    LayoutResult result = layoutResult(List.of(customer, service), List.of(m1, m2, m3));

    ValidationResult validation = CoreCommands.validateLayout(result);

    assertThat(validation.exitCode()).isZero();
    assertThat(validation.envelope().status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(validation.envelope().diagnostics()).isEmpty();
  }

  @Test
  void validateLayoutRejectsAMessageEndpointOffTheLifelineAxis() {
    // Negative case (Plan B P5, Task 6): the message's first route point sits on the SOURCE
    // lifeline's box perimeter (left edge, y inside the box) rather than its center-x axis. The
    // pre-existing LayoutQuality hard-error lane accepts this endpoint outright (on-perimeter is
    // its own acceptance branch, independent of the lifeline-axis branch), so this specifically
    // proves the new SequenceInvariants wiring catches a violation the old lane does not: without
    // it, this layout would wrongly validate as "ok".
    var customer = lifeline("customer", 100.0, 100.0); // box: x[100,240] y[100,148], center-x 170
    var service = lifeline("service", 520.0, 100.0); // center-x = 590
    var offAxisMessage =
        new LaidOutEdge(
            "m1",
            "customer",
            "service",
            "m1",
            "m1",
            List.of(),
            List.of(new Point(100.0, 120.0), new Point(590.0, 120.0)),
            "placeOrder",
            "/edges/0");
    LayoutResult result = layoutResult(List.of(customer, service), List.of(offAxisMessage));

    ValidationResult validation = CoreCommands.validateLayout(result);

    assertThat(validation.exitCode()).isEqualTo(2);
    assertThat(validation.envelope().status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(validation.envelope().diagnostics())
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED");
    Diagnostic diagnostic = validation.envelope().diagnostics().get(0);
    assertThat(diagnostic.message()).contains("message_endpoints_on_lifeline_axis").contains("m1");
    assertThat(diagnostic.path()).isEqualTo("$.edges[0]");
    assertThat(diagnostic.sourcePointer()).isEqualTo("/edges/0");
  }

  @Test
  void validateLayoutRejectsALifelineOutsideTheInteractionFrame() {
    // Negative case (Plan B P5 audit fix): an "interaction" role node (the frame) whose bbox does
    // NOT enclose one of the "lifeline" nodes. No message edges at all, so this isolates
    // SequenceInvariants#interactionFrameEnclosesLifelines from the other two invariants. The
    // violating element is the LIFELINE NODE (not an edge), so this is also the only test that
    // exercises CoreCommands#elementPath's node-id branch ($.nodes[N]) -- every other invariant
    // test in this file violates on an edge, giving that branch no coverage.
    var frame =
        new LaidOutNode("frame", "frame", "frame", 0.0, 0.0, 300.0, 300.0, "frame", "interaction");
    var customer = lifeline("customer", 50.0, 50.0); // box [50,190]x[50,98]: inside the frame
    var service = lifeline("service", 1000.0, 1000.0); // box [1000,1140]x[1000,1048]: outside it
    LayoutResult result = layoutResult(List.of(frame, customer, service), List.of());

    ValidationResult validation = CoreCommands.validateLayout(result);

    assertThat(validation.exitCode()).isEqualTo(2);
    assertThat(validation.envelope().status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(validation.envelope().diagnostics())
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED");
    Diagnostic diagnostic = validation.envelope().diagnostics().get(0);
    assertThat(diagnostic.message())
        .contains("interaction_frame_encloses_lifelines")
        .contains("service");
    assertThat(diagnostic.path()).isEqualTo("$.nodes[2]");
  }

  @Test
  void validateLayoutRejectsMessagesWhoseYIsNotStrictlyIncreasing() {
    // Negative case (Plan B P5 audit fix): two messages, each with axis-compliant endpoints, whose
    // representative y-values are non-increasing in scene order (220 then 180), so this isolates
    // SequenceInvariants#messageYStrictlyIncreasing from the axis and frame invariants.
    var customer = lifeline("customer", 100.0, 100.0); // center-x = 170
    var service = lifeline("service", 520.0, 104.0); // center-x = 590
    var m1 = message("m1", "customer", "service", 170.0, 220.0, 590.0, 220.0);
    var m2 = message("m2", "service", "customer", 590.0, 180.0, 170.0, 180.0); // y decreases
    LayoutResult result = layoutResult(List.of(customer, service), List.of(m1, m2));

    ValidationResult validation = CoreCommands.validateLayout(result);

    assertThat(validation.exitCode()).isEqualTo(2);
    assertThat(validation.envelope().status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(validation.envelope().diagnostics())
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED");
    Diagnostic diagnostic = validation.envelope().diagnostics().get(0);
    assertThat(diagnostic.message()).contains("message_y_strictly_increasing").contains("m2");
    assertThat(diagnostic.path()).isEqualTo("$.edges[1]");
  }

  private static LaidOutNode lifeline(String id, double x, double y) {
    return new LaidOutNode(id, id, id, x, y, 140.0, 48.0, id, "lifeline");
  }

  private static LaidOutEdge message(
      String id,
      String source,
      String target,
      double firstX,
      double firstY,
      double lastX,
      double lastY) {
    return new LaidOutEdge(
        id,
        source,
        target,
        id,
        id,
        List.of(),
        List.of(new Point(firstX, firstY), new Point(lastX, lastY)),
        id);
  }

  private static LayoutResult layoutResult(List<LaidOutNode> nodes, List<LaidOutEdge> edges) {
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        "sequence-view",
        nodes,
        edges,
        List.of(),
        List.of());
  }

  @Test
  void layoutCommandRunsThroughTheBoundEngine() throws Exception {
    Engines engines =
        Engines.of(List.of(), List.of(new FakeLayoutEngine("fake-layout")), List.of(), List.of());
    String request =
        """
                {
                  "layout_request_schema_version": "layout-request.schema.v2",
                  "view_id": "main",
                  "nodes": [],
                  "edges": [],
                  "groups": [],
                  "constraints": []
                }
                """;

    EngineRunOutcome outcome =
        CoreCommands.layoutCommand("fake-layout", request, Map.of(), engines);

    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"layout_result_schema_version\"");
  }

  @Test
  void layoutCommandUnwrapsAPipedStageEnvelopeBeforeTheEngineParses() throws Exception {
    // Chained-workflow convenience: the previous stage's command envelope is accepted directly;
    // its .data payload is what reaches the engine's parse entry point.
    FakeLayoutEngine engine = new FakeLayoutEngine("fake-layout");
    Engines engines = Engines.of(List.of(), List.of(engine), List.of(), List.of());
    String enveloped =
        """
                {
                  "envelope_schema_version": "envelope.schema.v1",
                  "status": "ok",
                  "data": {
                    "layout_request_schema_version": "layout-request.schema.v2",
                    "view_id": "piped-view",
                    "nodes": [],
                    "edges": [],
                    "groups": [],
                    "constraints": []
                  },
                  "diagnostics": []
                }
                """;

    EngineRunOutcome outcome =
        CoreCommands.layoutCommand("fake-layout", enveloped, Map.of(), engines);

    assertThat(outcome.exitCode()).isZero();
    assertThat(engine.lastParsedViewId).isEqualTo("piped-view");
  }

  // A schema-valid source with no plugins.generic-graph section -- exportCommand only needs a
  // source that clears SourceValidator, not one with any particular view.
  private static final String MINIMAL_SOURCE =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "api", "type": "generic.component", "label": "API", "properties": {} }
        ],
        "relationships": [],
        "plugins": {}
      }
      """;

  private static final String MINIMAL_LAYOUT =
      """
      {
        "layout_result_schema_version": "layout-result.schema.v2",
        "view_id": "main",
        "nodes": [
          { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" }
        ],
        "edges": [],
        "groups": [],
        "warnings": []
      }
      """;

  @Test
  void renderCommandRejectsAStalePolicyBeforeEngineLookup() {
    // Task 4's binding constraint applies to every gated call site, not just build: the standalone
    // render command must reject a stale policy before ever resolving an engine. An engine registry
    // with no render engine at all proves the rejection cannot be riding on a successful lookup.
    String stalePolicy = "{\"render_policy_schema_version\":\"render-policy.schema.v2\"}";

    assertThatThrownBy(
            () ->
                CoreCommands.renderCommand(
                    "nonexistent-render-engine",
                    stalePolicy,
                    null,
                    MINIMAL_LAYOUT,
                    Map.of(),
                    emptyEngines()))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED"));
  }

  @Test
  void exportCommandRejectsAStalePolicyBeforeEngineLookup() {
    // Mirrors renderCommandRejectsAStalePolicyBeforeEngineLookup for the standalone export command:
    // a known export engine id ("archimate-oef") whose policy carries a version this build does not
    // recognize is rejected before EngineDispatch.requireEngine ever runs.
    String unknownVersionOefPolicy =
        "{\"oef_export_policy_schema_version\":\"oef-export-policy.schema.v0\"}";

    assertThatThrownBy(
            () ->
                CoreCommands.exportCommand(
                    "archimate-oef",
                    unknownVersionOefPolicy,
                    MINIMAL_SOURCE,
                    null,
                    MINIMAL_LAYOUT,
                    Map.of(),
                    emptyEngines()))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN"));
  }

  @Test
  void exportCommandUnknownEngineIdStillYieldsPluginUnknownNotAVersionDiagnostic() {
    // exportPolicyFamily returns Optional.empty() for an engine id that is neither "archimate-oef"
    // nor "uml-xmi", specifically so an unknown export engine id still reaches requireEngine and
    // raises DEDIREN_PLUGIN_UNKNOWN instead of being swallowed by the version gate. The policy here
    // carries no version field at all -- it would fail the gate if the gate ran -- so this proves
    // the gate is genuinely skipped for an unrecognized engine id, not merely that this particular
    // policy happens to pass it.
    String versionlessPolicy = "{}";

    assertThatThrownBy(
            () ->
                CoreCommands.exportCommand(
                    "totally-unknown-engine",
                    versionlessPolicy,
                    MINIMAL_SOURCE,
                    null,
                    MINIMAL_LAYOUT,
                    Map.of(),
                    emptyEngines()))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_PLUGIN_UNKNOWN"));
  }

  private static Engines emptyEngines() {
    return Engines.of(List.of(), List.of(), List.of(), List.of());
  }

  private static final class FakeLayoutEngine implements LayoutEngine {
    private final String id;
    private String lastParsedViewId;

    FakeLayoutEngine(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public SceneGraph parseRequest(byte[] input) {
      LayoutRequest request = JsonSupport.objectMapper().readValue(input, LayoutRequest.class);
      SceneGraph scene = LayoutRequestMapper.toSceneGraph(request);
      lastParsedViewId = scene.viewId();
      return scene;
    }

    @Override
    public EngineResult<LaidOutScene> layout(SceneGraph scene) {
      return new EngineResult<>(
          new LaidOutScene(scene.viewId(), List.of(), List.of(), List.of(), List.of()), List.of());
    }
  }
}

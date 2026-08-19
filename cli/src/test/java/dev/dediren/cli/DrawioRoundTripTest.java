package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.testsupport.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end {@code drawio_in → import → export → drawio_out} coverage for the draw.io lane, driven
 * through the real CLI ({@link Main#executeForTesting}) and therefore through the real {@link
 * EngineWiring} registry, real projection, and real ELK layout.
 *
 * <h2>Why this lives in {@code cli} and not in {@code engines/drawio}</h2>
 *
 * <p>A round trip is not two engine calls: it is {@code import → project → layout → export}, and
 * layout is ELK. Engines are pairwise independent, so {@code engines/drawio} cannot depend
 * on {@code engines/elk-layout}; an in-module version would have to fake the layout stage and would prove
 * nothing about the composed path. {@code cli} test scope is the only place every engine is on the
 * classpath at once.
 *
 * <h2>The equivalence relation</h2>
 *
 * <p>{@code ≈} is {@link DrawioEquivalence}, which documents exactly what survives and why each
 * exclusion is contractual rather than convenient. Read that class before changing an assertion
 * here: relaxing the relation to make a failure go away is how this test becomes worthless.
 *
 * <h2>Two round trips</h2>
 *
 * <ol>
 *   <li>A <strong>Dediren-authored</strong> input, generated here by exporting a checked-in source
 *       model and its layout fixture. This is the high-fidelity path, and the only one that proves
 *       the {@code <object dedirenType=…>} identity mechanism carries types and group roles.
 *   <li>A <strong>foreign</strong> input, hand-authored at {@code
 *       fixtures/drawio/roundtrip-foreign-nested.drawio}, which Dediren never produced. Only
 *       structure survives, and the type degradation is asserted rather than skipped.
 * </ol>
 */
class DrawioRoundTripTest {

  @TempDir Path temp;

  /**
   * The Dediren-authored seed, and the choice is not arbitrary.
   *
   * <p>It is a grouped view whose group's {@code semantic_source_id} ({@code pkg-orders}) is also
   * laid out as a node, which is what lets the exported file re-import at all. Verified by sweeping
   * all seventeen checked-in (source, layout) fixture pairs through {@code export → import}: the
   * six whose semantic-boundary group stands for an element that is <em>not</em> also a laid-out node —
   * {@code uml-component-basic}, {@code uml-complex-class}, the three state-machine fixtures and
   * {@code uml-use-case-basic} — fail re-import outright with {@code
   * DEDIREN_DRAWIO_ROUND_TRIP_INVALID}, and {@code pipeline-rich} and the three sequence fixtures
   * import green but produce a model the very next command rejects. Those are lane defects reported
   * to the lane owner, not properties of this test; nesting is therefore proved on the foreign
   * fixture below, whose containers are layout-only and do round-trip.
   */
  private static final String SOURCE_FIXTURE = "fixtures/source/valid-uml-basic.json";

  private static final String LAYOUT_FIXTURE = "fixtures/layout-result/uml-basic.json";
  private static final String FOREIGN_FIXTURE = "fixtures/drawio/roundtrip-foreign-nested.drawio";
  private static final String EXPORT_POLICY = "fixtures/export-policy/default-drawio.json";

  // ---------------------------------------------------------------- round trip 1: Dediren-authored

  /**
   * The high-fidelity path. Every fact in the relation — ids, labels, edge topology, containment,
   * {@code dedirenType}, and the group's role and semantic source — has to come back unchanged,
   * because a Dediren-authored file carries the identity vocabulary that makes them recoverable.
   */
  @Test
  void dedirenAuthoredRoundTripPreservesIdentityTypesTopologyAndGrouping() throws Exception {
    String in = exportDrawio(path(SOURCE_FIXTURE), path(LAYOUT_FIXTURE));
    RoundTrip trip = roundTrip(in, "in");

    assertThat(DrawioEquivalence.withTypedIdentity(trip.out()))
        .describedAs(
            "drawio_out must be equivalent to drawio_in\nin:\n%s\nout:\n%s", in, trip.out())
        .isEqualTo(DrawioEquivalence.withTypedIdentity(in));

    // The relation is only worth as much as the facts inside it, so pin the ones that matter
    // concretely: a relation over an empty structure would compare equal to itself.
    var structure = DrawioEquivalence.withTypedIdentity(trip.out());
    assertThat(structure.nodes())
        .containsOnlyKeys("pkg-orders", "class-order", "class-order-line", "enum-order-status");
    assertThat(structure.nodes().get("class-order").type()).isEqualTo("Class");
    assertThat(structure.nodes().get("enum-order-status").type()).isEqualTo("Enumeration");
    assertThat(structure.nodes().get("class-order").label()).isEqualTo("Order");
    assertThat(structure.groups()).containsOnlyKeys("orders-package-boundary");
    assertThat(structure.groups().get("orders-package-boundary").groupRole()).isEqualTo("semantic");
    assertThat(structure.groups().get("orders-package-boundary").semanticSourceId())
        .isEqualTo("pkg-orders");
    assertThat(structure.nodes().get("class-order").container())
        .describedAs("group membership is carried by each member's container")
        .isEqualTo("orders-package-boundary");
    assertThat(structure.edges())
        .extracting(DrawioEquivalence.EdgeFacts::id, DrawioEquivalence.EdgeFacts::source,
            DrawioEquivalence.EdgeFacts::target, DrawioEquivalence.EdgeFacts::type)
        .containsExactlyInAnyOrder(
            tuple(
                "order-has-lines", "class-order", "class-order-line", "Composition"),
            tuple(
                "order-status-dependency", "class-order", "enum-order-status", "Dependency"));
  }

  /**
   * The accepted lossiness, asserted rather than tolerated.
   *
   * <p>{@code DEDIREN_DRAWIO_HINT_IGNORED} fires here even though the input is Dediren's own
   * output,
   * because geometry and style are discarded on <em>every</em> import by contract. That is current,
   * verified behaviour and this test pins it; whether an unconditional warning on Dediren's own
   * artifact is useful signal is a separate question recorded for the lane owner, not something to
   * settle by writing the opposite assertion.
   */
  @Test
  void dedirenAuthoredImportDisclosesOnlyTheContractualLossiness() throws Exception {
    String in = exportDrawio(path(SOURCE_FIXTURE), path(LAYOUT_FIXTURE));

    JsonNode envelope =
        stage("import", "--plugin", "drawio", "--input", write("in.drawio", in).toString());

    assertThat(codes(envelope))
        .describedAs("no cell may be skipped, no edge dropped, and no layer flattened")
        .doesNotContain(
            DiagnosticCode.DRAWIO_CELLS_SKIPPED.code(),
            DiagnosticCode.DRAWIO_LAYERS_FLATTENED.code(),
            DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code())
        .describedAs("a stencil is never inferred when dedirenType already says what this is")
        .doesNotContain(DiagnosticCode.DRAWIO_KIND_INFERRED.code())
        .contains(DiagnosticCode.DRAWIO_HINT_IGNORED.code());
  }

  // ---------------------------------------------------------------- round trip 2: foreign

  /**
   * The foreign path. Structure survives — ids, labels (including a {@code <br>} inside one), edge
   * topology, and a group nested inside another group — while every type degrades to the generic
   * vocabulary, which is asserted here rather than skipped.
   */
  @Test
  void foreignRoundTripKeepsStructureAndDegradesTypesToGeneric() throws Exception {
    String in = Files.readString(path(FOREIGN_FIXTURE), StandardCharsets.UTF_8);
    RoundTrip trip = roundTrip(in, "foreign");

    assertThat(DrawioEquivalence.structureOnly(trip.out()))
        .describedAs("structure must survive a foreign round trip\nin:\n%s\nout:\n%s", in,
            trip.out())
        .isEqualTo(DrawioEquivalence.structureOnly(in));

    var structure = DrawioEquivalence.withTypedIdentity(trip.out());
    assertThat(structure.nodes()).containsOnlyKeys("ledger", "ingest", "console", "client");
    // Three assertions, not one, because the relation alone cannot see this: DrawioEquivalence
    // decodes <br> on both sides (that is what a label *means*), so an importer that stopped
    // decoding would carry the markup through as literal text and still compare equal. The
    // decode has to be pinned where it actually happens — at the import boundary, in the model —
    // and the artifact has to be shown free of residual markup.
    assertThat(structure.nodes().get("ingest").label())
        .describedAs("a <br> in a hand-authored label becomes a newline and stays one")
        .isEqualTo("Ingest\nGateway");
    assertThat(trip.model().at("/nodes/1/label").asString())
        .describedAs("the imported model must hold a newline, not the <br> markup that encoded it")
        .isEqualTo("Ingest\nGateway");
    assertThat(trip.out())
        .describedAs("no <br> markup, escaped or raw, may survive into the exported label")
        .doesNotContain("&lt;br", "<br>", "<br/>", "<br />");
    assertThat(structure.groups()).containsOnlyKeys("platform", "core");
    assertThat(structure.groups().get("core").container())
        .describedAs("a group inside a group keeps its nesting")
        .isEqualTo("platform");
    assertThat(structure.nodes().get("ledger").container()).isEqualTo("core");
    assertThat(structure.nodes().get("console").container()).isEqualTo("platform");
    assertThat(structure.nodes().get("client").container()).isNull();

    // The degradation, asserted positively: a hand-authored file carries no recoverable type, so
    // every node comes back generic and no ArchiMate or UML stencil is invented for it.
    assertThat(structure.nodes().values())
        .allSatisfy(node -> assertThat(node.type()).isEqualTo("generic.node"));
    assertThat(structure.edges())
        .allSatisfy(edge -> assertThat(edge.type()).isEqualTo("generic.link"));
    assertThat(trip.out()).doesNotContain("mxgraph.archimate3", "shape=umlFrame", "umlLifeline");

    // The imported model itself: generic-graph, never promoted to a notation profile.
    assertThat(trip.model().at("/plugins/generic-graph/semantic_profile").asString())
        .isEqualTo("generic-graph");
    assertThat(trip.model().at("/plugins/generic-graph/views/0/kind").asString())
        .isEqualTo("generic");
  }

  /** The foreign input's discarded presentation is disclosed, and its structure is not. */
  @Test
  void foreignImportDisclosesDiscardedPresentationAndDropsNothingElse() throws Exception {
    String in = Files.readString(path(FOREIGN_FIXTURE), StandardCharsets.UTF_8);

    JsonNode envelope =
        stage(
            "import", "--plugin", "drawio", "--input", write("foreign.drawio", in).toString());

    assertThat(codes(envelope))
        .contains(DiagnosticCode.DRAWIO_HINT_IGNORED.code())
        .describedAs("this fixture carries no hidden cell, dangling edge, or layer stack")
        .doesNotContain(
            DiagnosticCode.DRAWIO_CELLS_SKIPPED.code(),
            DiagnosticCode.DRAWIO_LAYERS_FLATTENED.code());
  }

  /**
   * Exporting a generic-graph model discloses that no draw.io shape covers its types, rather than
   * silently drawing a rectangle and calling it fidelity.
   */
  @Test
  void foreignExportDisclosesThatGenericTypesHaveNoShape() throws Exception {
    String in = Files.readString(path(FOREIGN_FIXTURE), StandardCharsets.UTF_8);
    RoundTrip trip = roundTrip(in, "foreign");

    assertThat(trip.exportDiagnostics()).contains(DiagnosticCode.DRAWIO_SHAPE_UNMAPPED.code());
  }

  // ---------------------------------------------------------------- fixed point

  /**
   * A second round trip must change nothing. {@code drawio_out} is Dediren-authored with stable
   * geometry, so the bar here is the strongest one available: byte identity, not the relation. A
   * failure means the lane has a drift the relation is too coarse to see — for example an id or a
   * cell-ordering rule that is not idempotent — and should be read, not baselined away.
   */
  @Test
  void secondRoundTripReachesAByteIdenticalFixedPoint() throws Exception {
    String in = exportDrawio(path(SOURCE_FIXTURE), path(LAYOUT_FIXTURE));
    RoundTrip first = roundTrip(in, "in");
    RoundTrip second = roundTrip(first.out(), "out1");

    assertThat(second.out()).isEqualTo(first.out());
  }

  /**
   * The same fixed point from the foreign side. The first export normalizes a hand-authored file
   * into Dediren's own vocabulary; from there the lane must be stationary, nesting included.
   */
  @Test
  void foreignRoundTripReachesAFixedPointOnceNormalized() throws Exception {
    String in = Files.readString(path(FOREIGN_FIXTURE), StandardCharsets.UTF_8);
    RoundTrip first = roundTrip(in, "foreign");
    RoundTrip second = roundTrip(first.out(), "foreign-out1");

    assertThat(second.out()).isEqualTo(first.out());
    assertThat(DrawioEquivalence.withTypedIdentity(second.out()))
        .isEqualTo(DrawioEquivalence.withTypedIdentity(first.out()));
  }

  // ---------------------------------------------------------------- the pipeline

  /** One full {@code import → project → layout → export} pass and everything it disclosed. */
  private record RoundTrip(JsonNode model, String out, List<String> exportDiagnostics) {}

  private RoundTrip roundTrip(String drawio, String label) throws Exception {
    Path inputFile = write(label + ".drawio", drawio);
    JsonNode imported = stage("import", "--plugin", "drawio", "--input", inputFile.toString());
    JsonNode model = imported.get("data");
    Path modelFile = writeJson(label + "-model.json", model);
    String viewId = model.at("/plugins/generic-graph/views/0/id").asString();

    JsonNode request =
        stage(
            "project",
            "--plugin",
            "generic-graph",
            "--target",
            "layout-request",
            "--view",
            viewId,
            "--input",
            modelFile.toString());
    Path requestFile = writeJson(label + "-layout-request.json", request.get("data"));

    JsonNode laidOut =
        stage("layout", "--plugin", "elk-layout", "--input", requestFile.toString());
    Path layoutFile = writeJson(label + "-layout-result.json", laidOut.get("data"));

    JsonNode exported =
        stage(
            "export",
            "--plugin",
            "drawio",
            "--policy",
            path(EXPORT_POLICY).toString(),
            "--source",
            modelFile.toString(),
            "--layout",
            layoutFile.toString());
    return new RoundTrip(model, exported.at("/data/content").asString(), codes(exported));
  }

  /** The seed for the Dediren-authored trip: Dediren's own export of a checked-in fixture pair. */
  private String exportDrawio(Path source, Path layout) throws Exception {
    JsonNode exported =
        stage(
            "export",
            "--plugin",
            "drawio",
            "--policy",
            path(EXPORT_POLICY).toString(),
            "--source",
            source.toString(),
            "--layout",
            layout.toString());
    return exported.at("/data/content").asString();
  }

  /** Runs one CLI stage and returns its envelope, failing loudly on a non-zero exit. */
  private static JsonNode stage(String... arguments) throws Exception {
    CliResult result = Main.executeForTesting(arguments, "");
    assertThat(result.exitCode())
        .describedAs("dediren %s\n%s", String.join(" ", arguments), result.stdout())
        .isZero();
    return JsonSupport.objectMapper().readTree(result.stdout());
  }

  private static List<String> codes(JsonNode envelope) {
    List<String> codes = new ArrayList<>();
    envelope.path("diagnostics").forEach(node -> codes.add(node.path("code").asString()));
    return codes;
  }

  private Path write(String name, String content) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private Path writeJson(String name, JsonNode data) throws Exception {
    return write(name, JsonSupport.objectMapper().writeValueAsString(data));
  }

  private static Path path(String relative) {
    return TestSupport.workspaceRoot().resolve(relative);
  }
}

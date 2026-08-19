package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.plugins.drawio.DrawioExportEngine;
import dev.dediren.testsupport.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end {@code drawio_in → import → export → drawio_out} coverage for the draw.io lane, driven
 * through the real CLI ({@link Main#executeForTesting}) and therefore through the real {@link
 * EngineWiring} registry, real projection, and real ELK layout.
 *
 * <h2>Why this lives in {@code cli} and not in {@code engines/drawio}</h2>
 *
 * <p>A round trip is not two engine calls: it is {@code import → project → layout → export}, and
 * layout is ELK. Engines are pairwise independent, so {@code engines/drawio} cannot depend on
 * {@code engines/elk-layout}; an in-module version would have to fake the layout stage and would
 * prove nothing about the composed path. {@code cli} test scope is the only place every engine is
 * on the classpath at once.
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
   * The Dediren-authored seed: the smallest grouped, typed view in the fixture set.
   *
   * <p>Sweeping all seventeen checked-in (source, layout) pairs through the whole pipeline is what
   * chose the other seeds used below, and the sweep is worth recording because it is the only
   * measurement of what the lane can actually re-import. All seventeen now complete {@code export →
   * import → project}, and sixteen reach a byte-identical fixed point; the one that does not is
   * {@link #NOT_YET_A_FIXED_POINT}, and its cause is not a property this export drops.
   */
  private static final String SOURCE_FIXTURE = "fixtures/source/valid-uml-basic.json";

  private static final String LAYOUT_FIXTURE = "fixtures/layout-result/uml-basic.json";

  /**
   * The seed for the boundary case that used to fail re-import outright: two semantic-boundary
   * groups whose packages are declared by the model but laid out by nothing, which is
   * contract-legal precisely because {@code semantic_source_id} resolves against the document's
   * nodes rather than the view's.
   */
  private static final String UNLAID_SOURCE_FIXTURE = "fixtures/source/valid-uml-complex.json";

  private static final String UNLAID_LAYOUT_FIXTURE =
      "fixtures/layout-result/uml-complex-class.json";

  /** The one Dediren-authored fixture with a semantic boundary inside another semantic boundary. */
  private static final String NESTED_SOURCE_FIXTURE =
      "fixtures/source/valid-uml-component-basic.json";

  private static final String NESTED_LAYOUT_FIXTURE =
      "fixtures/layout-result/uml-component-basic.json";

  /** A view whose groups declare no {@code semantic_source_id} at all. */
  private static final String UNSOURCED_GROUP_SOURCE_FIXTURE =
      "fixtures/source/valid-pipeline-rich.json";

  private static final String UNSOURCED_GROUP_LAYOUT_FIXTURE =
      "fixtures/layout-result/pipeline-rich.json";

  /** A view whose Messages are invalid without an ordering the format has nowhere to keep. */
  private static final String SEQUENCE_SOURCE_FIXTURE =
      "fixtures/source/valid-uml-sequence-basic.json";

  private static final String SEQUENCE_LAYOUT_FIXTURE =
      "fixtures/layout-result/uml-sequence-basic.json";

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
        .extracting(
            DrawioEquivalence.EdgeFacts::id,
            DrawioEquivalence.EdgeFacts::source,
            DrawioEquivalence.EdgeFacts::target,
            DrawioEquivalence.EdgeFacts::type)
        .containsExactlyInAnyOrder(
            tuple("order-has-lines", "class-order", "class-order-line", "Composition"),
            tuple("order-status-dependency", "class-order", "enum-order-status", "Dependency"));
  }

  /**
   * Importing Dediren's own export discloses nothing, and that is the assertion.
   *
   * <p>{@code DEDIREN_DRAWIO_HINT_IGNORED} used to fire here. The keys it named were the exporter's
   * own — geometry it took from this layout result and a style it computed from this model — so
   * nothing was lost and there was nothing for a reader to do. A warning that fires on every single
   * import, Dediren's own artifact included, carries no signal and trains its reader past the ones
   * that do. The foreign trip below still gets it, which is what keeps this assertion from being a
   * hole rather than a fix.
   */
  @Test
  void dedirenAuthoredImportDisclosesNothingBecauseNothingIsLost() throws Exception {
    String in = exportDrawio(path(SOURCE_FIXTURE), path(LAYOUT_FIXTURE));

    JsonNode envelope =
        stage("import", "--plugin", "drawio", "--input", write("in.drawio", in).toString());

    assertThat(codes(envelope))
        .describedAs("nothing is skipped, dropped, flattened, inferred, or merely discarded")
        .isEmpty();
  }

  // ------------------------------------------- round trip 1b: the boundary cases that used to fail

  /**
   * A semantic boundary standing for an element nothing lays out.
   *
   * <p>This is the shape that used to make Dediren's own export unusable: {@code
   * complex-class-view} declares two package boundaries whose packages are model nodes but not view
   * nodes, which is legal — {@code SemanticsRouterEngine} and {@code SceneProjection} both resolve
   * {@code semantic_source_id} against {@code source.nodes()} — and the export wrote the id without
   * the element, so re-import failed with {@code DEDIREN_DRAWIO_ROUND_TRIP_INVALID}. The export
   * carries the element now, and the whole pipeline runs.
   */
  @Test
  void dedirenAuthoredBoundaryCarriesAPackageNothingLaysOut() throws Exception {
    String in = exportDrawio(path(UNLAID_SOURCE_FIXTURE), path(UNLAID_LAYOUT_FIXTURE));
    RoundTrip trip = roundTrip(in, "unlaid");

    assertThat(DrawioEquivalence.withTypedIdentity(trip.out()))
        .describedAs("in:\n%s\nout:\n%s", in, trip.out())
        .isEqualTo(DrawioEquivalence.withTypedIdentity(in));

    var structure = DrawioEquivalence.withTypedIdentity(trip.out());
    assertThat(structure.groups())
        .containsOnlyKeys("commerce-package-boundary", "fulfillment-package-boundary");
    assertThat(structure.groups().get("commerce-package-boundary").semanticSourceId())
        .isEqualTo("pkg-commerce");
    // The relation cannot see this: the package has no cell on either side, so a boundary that
    // came back naming a package the model no longer declares would still compare equal. The
    // restored *element* has to be pinned in the model, with the type it had.
    assertThat(nodeType(trip.model(), "pkg-commerce")).isEqualTo("Package");
    assertThat(nodeType(trip.model(), "pkg-fulfillment")).isEqualTo("Package");
    assertThat(trip.model().at("/plugins/generic-graph/views/0/nodes"))
        .describedAs("the view lays out the boundary, not a second box for the same package")
        .noneSatisfy(node -> assertThat(node.asString()).isEqualTo("pkg-commerce"));
  }

  /**
   * A semantic boundary inside another semantic boundary, on the Dediren-authored path.
   *
   * <p>Nesting used to be provable only on the foreign fixture, whose containers are layout-only,
   * so the path that carries a group role and a semantic source through two levels was untested.
   * {@code component-view} nests two component boundaries inside a package boundary and the package
   * is laid out by nothing, which is both halves of the defect at once.
   *
   * <p>The trailing assertion used to be the honest part: this model could not complete the
   * pipeline, because {@code Port.component} is required and mxGraph had nowhere to keep it. It has
   * somewhere now — the hidden metadata cell's element-property map — so the assertion is the
   * closure of that residual rather than its record, and it fails if the property stops making the
   * crossing.
   */
  @Test
  void dedirenAuthoredNestedBoundariesRoundTripWithTheirUnlaidPackage() throws Exception {
    String in = exportDrawio(path(NESTED_SOURCE_FIXTURE), path(NESTED_LAYOUT_FIXTURE));

    JsonNode imported =
        stage("import", "--plugin", "drawio", "--input", write("nested.drawio", in).toString());
    JsonNode model = imported.get("data");

    var structure = DrawioEquivalence.withTypedIdentity(in);
    assertThat(structure.groups())
        .containsOnlyKeys(
            "orders-package-boundary", "order-api-boundary", "payment-adapter-boundary");
    assertThat(structure.groups().get("order-api-boundary").container())
        .describedAs("a semantic boundary nested inside another semantic boundary")
        .isEqualTo("orders-package-boundary");
    assertThat(structure.groups().get("order-api-boundary").semanticSourceId())
        .isEqualTo("component-order-api");
    assertThat(structure.nodes().get("port-rest-api").container()).isEqualTo("order-api-boundary");

    assertThat(groupIds(model))
        .containsExactly(
            "orders-package-boundary", "order-api-boundary", "payment-adapter-boundary");
    assertThat(nodeType(model, "pkg-orders")).isEqualTo("Package");

    Path modelFile = writeJson("nested-model.json", model);
    CliResult projected =
        Main.executeForTesting(
            new String[] {
              "project",
              "--plugin",
              "generic-graph",
              "--target",
              "layout-request",
              "--view",
              "component-view",
              "--input",
              modelFile.toString()
            },
            "");
    assertThat(projected.exitCode())
        .describedAs(
            "Port.component rode the metadata cell and the model is valid again: %s",
            projected.stdout())
        .isZero();
    assertThat(model.at("/nodes").toString())
        .describedAs("and it came back as the model's own property, not as a reconstruction")
        .contains("\"component\":\"component-order-api\"");
    assertThat(exportCodes(path(NESTED_SOURCE_FIXTURE), path(NESTED_LAYOUT_FIXTURE)))
        .describedAs("so the export has nothing left to declare lost")
        .doesNotContain(DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code());
  }

  /**
   * A semantic boundary that declares no source element at all.
   *
   * <p>{@code SceneProjection} gives such a group a provenance naming the group's own id, and the
   * export used to write that fallback out as a {@code dedirenSemanticSourceId}. It resolved on
   * re-import — against an index that held groups as well as nodes — and then the next command
   * rejected the model with {@code DEDIREN_GENERIC_GRAPH_GROUP_SEMANTIC_SOURCE_UNKNOWN}, which is
   * precisely the green-import-then-unusable-model failure the mapper exists to prevent.
   */
  @Test
  void dedirenAuthoredGroupWithNoSourceElementNeverManufacturesOne() throws Exception {
    String in =
        exportDrawio(path(UNSOURCED_GROUP_SOURCE_FIXTURE), path(UNSOURCED_GROUP_LAYOUT_FIXTURE));
    RoundTrip trip = roundTrip(in, "unsourced");

    assertThat(DrawioEquivalence.withTypedIdentity(trip.out()))
        .describedAs("in:\n%s\nout:\n%s", in, trip.out())
        .isEqualTo(DrawioEquivalence.withTypedIdentity(in));

    var structure = DrawioEquivalence.withTypedIdentity(trip.out());
    assertThat(structure.groups())
        .containsOnlyKeys("application-services", "external-dependencies");
    assertThat(structure.groups().get("application-services").groupRole()).isEqualTo("semantic");
    assertThat(structure.groups().get("application-services").semanticSourceId())
        .describedAs("a boundary that stands for nothing says so, rather than naming itself")
        .isNull();
    JsonNode declaredSource =
        trip.model().at("/plugins/generic-graph/views/0/groups/0/semantic_source_id");
    assertThat(declaredSource.isMissingNode() || declaredSource.isNull())
        .describedAs(
            "the imported model must not carry a self-reference either: %s", declaredSource)
        .isTrue();
  }

  /**
   * A UML Message's ordering, which the model is invalid without.
   *
   * <p>{@code UmlSequenceValidation.validateMessageProperties} rejects a Message that declares no
   * {@code properties.uml.sequence}, so an export that dropped it produced a file that re-imported
   * green and failed {@code project} with {@code DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID}. It
   * rides the edge's {@code <object>} wrapper now, which is a channel draw.io preserves through an
   * editing session.
   */
  @Test
  void dedirenAuthoredSequenceKeepsItsMessageOrdering() throws Exception {
    String in = exportDrawio(path(SEQUENCE_SOURCE_FIXTURE), path(SEQUENCE_LAYOUT_FIXTURE));
    RoundTrip trip = roundTrip(in, "sequence");

    assertThat(DrawioEquivalence.withTypedIdentity(trip.out()))
        .describedAs("in:\n%s\nout:\n%s", in, trip.out())
        .isEqualTo(DrawioEquivalence.withTypedIdentity(in));
    assertThat(messageSequences(trip.model()))
        .describedAs("every Message comes back with the ordering it was exported with")
        .containsExactly(1, 2, 3);
    // The other half of the same defect: everything else under properties used to be dropped, and
    // rides the hidden metadata cell now, so there is nothing left for the export to declare lost.
    assertThat(exportCodes(path(SEQUENCE_SOURCE_FIXTURE), path(SEQUENCE_LAYOUT_FIXTURE)))
        .doesNotContain(DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code());
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
        .describedAs(
            "structure must survive a foreign round trip\nin:\n%s\nout:\n%s", in, trip.out())
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
    // The export has to put the break back as markup, and the relation cannot see that it did:
    // DrawioEquivalence decodes <br> on both sides. Two things are wrong with writing the newline
    // through instead. draw.io styles every cell html=1 and an HTML label collapses whitespace, so
    // the break would not render; and XML attribute-value normalization (XML 1.0 §3.3.3) replaces
    // a literal newline in an attribute with a space, so the break did not even survive to the
    // next import — it came back as "Ingest Gateway". Both halves are pinned on the artifact.
    assertThat(trip.out())
        .describedAs("the break is re-encoded as the markup it was decoded from")
        .contains("&lt;br&gt;");
    assertThat(trip.out())
        .describedAs("and never as a raw newline inside an attribute value")
        .doesNotContain("label=\"Ingest\nGateway\"");
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
        stage("import", "--plugin", "drawio", "--input", write("foreign.drawio", in).toString());

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

  // ---------------------------------------------------------------- export-anchored fixed point

  /**
   * The sharpest instrument in this file: inside Dediren-authored {@code .drawio} space the round
   * trip must be the <em>identity</em>.
   *
   * <pre>
   *   source + view --project--&gt;--layout--&gt;--export--&gt; d1
   *   d1            --import--&gt;                        source'
   *   source' + view'--project--&gt;--layout--&gt;--export--&gt; d2
   *   assert d1 == d2, byte for byte
   * </pre>
   *
   * <p>Export is a retraction: everything it writes, the importer reads, and a second pass has
   * nothing left to change. Byte equality is a strictly stronger bar than {@link
   * DrawioEquivalence}, which excludes geometry, style and document order by design — so this
   * catches faults the relation is blind to. A dropped {@code layout_preferences} block, for
   * example, produces an identical structure laid out at different coordinates; {@code ≈} passes
   * and this fails.
   *
   * <p><strong>{@code d1} comes from the live pipeline, never from {@code
   * fixtures/layout-result/}.</strong> A checked-in layout fixture ages against ELK, and a test
   * that fails on fixture staleness rather than on a round-trip fault is a test that gets muted.
   * Both halves run through the same ELK in the same JVM.
   *
   * <p>The corpus is {@link LayoutFixtureRegenerator#mappings()} — the repository's own list of
   * (source, view) pairs — rather than a second copy of it here, so a new fixture is swept without
   * anyone remembering to add it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtureCorpus")
  void everyFixtureReachesAnExportAnchoredFixedPoint(String name, String sourceFile, String viewId)
      throws Exception {
    Path source = path("fixtures/source/" + sourceFile);
    String first = pipeline(source, viewId, name + "-1");

    JsonNode imported =
        stage("import", "--plugin", "drawio", "--input", write(name + ".drawio", first).toString());
    Path reimported = writeJson(name + "-model.json", imported.get("data"));
    String reimportedView = imported.at("/data/plugins/generic-graph/views/0/id").asString();
    String second = pipeline(reimported, reimportedView, name + "-2");

    assertThat(second)
        .describedAs(
            "%s: export must be idempotent, and it is not.\n%s",
            name, DrawioEquivalence.explainDifference(first, second))
        .isEqualTo(first);
  }

  /** How a fixture that cannot reach the fixed point fails, so the exclusion can be re-checked. */
  private enum Residual {
    /** The second export succeeds and draws the same graph at different coordinates. */
    LAYS_OUT_DIFFERENTLY
  }

  /**
   * The one fixture that does not reach the fixed point, and why it is not a property this export
   * drops.
   *
   * <p><strong>What used to be here.</strong> Eight fixtures failed, all for one reason: mxGraph had
   * nowhere to keep an element's {@code properties}. Six became invalid on re-import — a required
   * UML ownership property ({@code Port.component}, {@code ExtensionPoint.use_case}, {@code
   * Transition.region}, {@code ExecutionSpecification.covered}) was gone, so {@code project}
   * rejected the model before a second export could happen — and two stayed valid and moved,
   * because {@code UmlNotationSemantics} sizes a Class box from {@code uml.attributes}/{@code
   * uml.operations}. All eight are closed by {@link
   * dev.dediren.plugins.drawio.write.DrawioIdentity#ELEMENT_PROPERTIES}, a per-element property map
   * on the hidden metadata cell.
   *
   * <p><strong>What is left, and why it is a different defect.</strong> {@code
   * uml-sequence-fragments} declares ten {@code CombinedFragment}/{@code InteractionOperand}
   * elements in {@code views[].nodes}, and the layout result lays out none of them: the notation
   * layer consumes them to size the interaction frame and emits no box. A {@code .drawio} is a
   * picture of a layout result, so an element with no geometry has no cell, and an element with no
   * cell is not in the file at all — its properties included. The re-imported view therefore
   * declares five nodes where the original declared fifteen, and the frame comes back 848×440
   * instead of 848×760. Carrying more properties cannot reach it; what is missing is the element,
   * not its properties, and supplying one would mean either inventing geometry the exporter
   * deliberately never invents or adding a second identity channel for view members no page draws.
   * That is a separate decision and is not taken here.
   *
   * <p>Excluding it is not a weakening of the assertion — {@link
   * #everyFixtureStillExcludedFromTheFixedPointStillNeedsToBe} re-measures the entry and fails when
   * it starts passing, so the list can only shrink deliberately.
   */
  private static final Map<String, Residual> NOT_YET_A_FIXED_POINT =
      Map.of("uml-sequence-fragments.json", Residual.LAYS_OUT_DIFFERENTLY);

  static List<Arguments> fixtureCorpus() {
    return corpus(mapping -> !NOT_YET_A_FIXED_POINT.containsKey(mapping.fixtureName()));
  }

  static List<Arguments> excludedCorpus() {
    return corpus(mapping -> NOT_YET_A_FIXED_POINT.containsKey(mapping.fixtureName()));
  }

  private static List<Arguments> corpus(
      java.util.function.Predicate<LayoutFixtureRegenerator.FixtureMapping> wanted) {
    return LayoutFixtureRegenerator.mappings().stream()
        .filter(wanted)
        .map(
            mapping ->
                Arguments.of(
                    mapping.fixtureName().replace(".json", ""),
                    mapping.sourceFileName(),
                    mapping.viewId()))
        .toList();
  }

  /**
   * An exclusion that has quietly started working is a lie in the test file, so every one of them
   * is re-measured. This is what makes {@link #NOT_YET_A_FIXED_POINT} a record of a known defect
   * rather than a list of tests someone turned off.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("excludedCorpus")
  void everyFixtureStillExcludedFromTheFixedPointStillNeedsToBe(
      String name, String sourceFile, String viewId) throws Exception {
    assertThat(NOT_YET_A_FIXED_POINT.get(name + ".json"))
        .describedAs(
            "one residual mode is recorded and re-measured below; a second mode needs its own"
                + " measurement rather than this one")
        .isEqualTo(Residual.LAYS_OUT_DIFFERENTLY);
    Path source = path("fixtures/source/" + sourceFile);
    String first = pipeline(source, viewId, name + "-x1");

    JsonNode imported =
        stage(
            "import", "--plugin", "drawio", "--input", write(name + "-x.drawio", first).toString());
    Path reimported = writeJson(name + "-x-model.json", imported.get("data"));
    String reimportedView = imported.at("/data/plugins/generic-graph/views/0/id").asString();

    CliResult projected =
        Main.executeForTesting(
            new String[] {
              "project",
              "--plugin",
              "generic-graph",
              "--target",
              "layout-request",
              "--view",
              reimportedView,
              "--input",
              reimported.toString()
            },
            "");
    assertThat(projected.exitCode())
        .describedAs("%s is recorded as laying out differently, not as failing to project", name)
        .isZero();
    String second = pipeline(reimported, reimportedView, name + "-x2");
    assertThat(second)
        .describedAs(
            "%s now reaches the fixed point, so remove it from NOT_YET_A_FIXED_POINT", name)
        .isNotEqualTo(first);
  }

  /**
   * {@code project → layout → export} for one model and view, entirely through the live engines.
   */
  private String pipeline(Path model, String viewId, String tag) throws Exception {
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
            model.toString());
    Path requestFile = writeJson(tag + "-request.json", request.get("data"));
    JsonNode laidOut = stage("layout", "--plugin", "elk-layout", "--input", requestFile.toString());
    Path layoutFile = writeJson(tag + "-layout.json", laidOut.get("data"));
    JsonNode exported =
        stage(
            "export",
            "--plugin",
            "drawio",
            "--policy",
            path(EXPORT_POLICY).toString(),
            "--source",
            model.toString(),
            "--layout",
            layoutFile.toString());
    return exported.at("/data/content").asString();
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

  /**
   * The whole-model aggregate's own fixed point, through the same real ELK the per-view lane uses.
   *
   * <p>Three views of one model become one three-page {@code .drawio}; that file re-imports as a
   * three-view model; laying those three views out and composing them again must produce the same
   * bytes. The aggregate can fail in ways no single page can — a page id or page name that collides
   * with an earlier page's, a view whose id is not restored so page one silently becomes {@code
   * main}, an element drawn on two pages whose properties are read from whichever page happened to
   * be scanned first — and every one of them shows up here as drifting bytes.
   *
   * <p>{@link dev.dediren.plugins.drawio.DrawioExportEngine#exportModel} is called directly rather
   * than through a command, because no command drives a whole-model draw.io export yet: {@code
   * BuildCommand} composes aggregates for the OEF and UML/XMI lanes only. The engine's opt-in is
   * implemented and covered; wiring a driver to it is a separate change.
   */
  @Test
  void theWholeModelAggregateReachesAByteIdenticalFixedPoint() throws Exception {
    List<String> views = List.of("class-view", "data-view", "activity-view");
    String first = aggregate(path(SOURCE_FIXTURE), views, "agg1");

    Path model =
        writeJson(
            "agg-model.json",
            stage("import", "--plugin", "drawio", "--input", write("agg.drawio", first).toString())
                .get("data"));
    List<String> restored = new ArrayList<>();
    JsonSupport.readTree(Files.readString(model, StandardCharsets.UTF_8))
        .at("/plugins/generic-graph/views")
        .forEach(view -> restored.add(view.path("id").asString()));
    assertThat(restored)
        .describedAs("one page per view, each keeping its own id, in build order")
        .isEqualTo(views);

    assertThat(aggregate(model, restored, "agg2")).isEqualTo(first);
  }

  /** One whole-model export of {@code views}, each laid out by the real ELK first. */
  private String aggregate(Path model, List<String> views, String label) throws Exception {
    var laidOut = new ArrayList<ModelExportRequest.ViewLayout>();
    for (String view : views) {
      JsonNode request =
          stage(
              "project",
              "--plugin",
              "generic-graph",
              "--target",
              "layout-request",
              "--view",
              view,
              "--input",
              model.toString());
      Path requestFile = writeJson(label + "-" + view + "-request.json", request.get("data"));
      JsonNode result =
          stage("layout", "--plugin", "elk-layout", "--input", requestFile.toString());
      laidOut.add(
          new ModelExportRequest.ViewLayout(
              view,
              JsonSupport.objectMapper().treeToValue(result.get("data"), LayoutResult.class)));
    }
    return new DrawioExportEngine()
        .exportModel(
            new ModelExportRequest(
                JsonSupport.objectMapper()
                    .readValue(
                        Files.readString(model, StandardCharsets.UTF_8), SourceDocument.class),
                laidOut,
                JsonSupport.readTree(
                    Files.readString(path(EXPORT_POLICY), StandardCharsets.UTF_8))),
            Map.of(),
            Path.of("").toAbsolutePath())
        .orElseThrow(() -> new AssertionError("the draw.io lane opts in to exportModel"))
        .value()
        .content();
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

    JsonNode laidOut = stage("layout", "--plugin", "elk-layout", "--input", requestFile.toString());
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

  /** The type the imported model gives one node id, or {@code null} when it declares none. */
  private static String nodeType(JsonNode model, String nodeId) {
    for (JsonNode node : model.path("nodes")) {
      if (nodeId.equals(node.path("id").asString())) {
        return node.path("type").asString();
      }
    }
    return null;
  }

  private static List<String> groupIds(JsonNode model) {
    List<String> ids = new ArrayList<>();
    model
        .at("/plugins/generic-graph/views/0/groups")
        .forEach(group -> ids.add(group.path("id").asString()));
    return ids;
  }

  /** Every Message's restored {@code properties.uml.sequence}, in relationship order. */
  private static List<Integer> messageSequences(JsonNode model) {
    List<Integer> orderings = new ArrayList<>();
    model
        .path("relationships")
        .forEach(
            relationship -> {
              JsonNode sequence = relationship.at("/properties/uml/sequence");
              if (!sequence.isMissingNode()) {
                orderings.add(sequence.asInt());
              }
            });
    return orderings;
  }

  /** The diagnostics one export emits, for the disclosure assertions. */
  private List<String> exportCodes(Path source, Path layout) throws Exception {
    return codes(
        stage(
            "export",
            "--plugin",
            "drawio",
            "--policy",
            path(EXPORT_POLICY).toString(),
            "--source",
            source.toString(),
            "--layout",
            layout.toString()));
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

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
   * measurement of what the lane can actually re-import. Eleven of the seventeen now complete
   * {@code export → import → project}. The six that do not — {@code uml-component-basic}, {@code
   * uml-use-case-basic}, {@code uml-sequence-lifecycle} and the three state-machine fixtures — all
   * stop at the same place and for one reason: a required UML ownership property ({@code
   * Port.component}, {@code ExtensionPoint.use_case}, {@code Transition.region}, {@code
   * ExecutionSpecification.covered}) that mxGraph has nowhere to keep. The export now names each
   * one in a {@code DEDIREN_DRAWIO_PROPERTIES_DROPPED} warning as it writes the file, and {@link
   * #dedirenAuthoredNestedBoundariesRoundTripWithTheirUnlaidPackage} pins that residual so it stays
   * visible rather than latent.
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
   * <p>The trailing assertion is the honest part: this model still cannot complete the pipeline,
   * because {@code Port.component} is required and mxGraph has nowhere to keep it. That is
   * disclosed on export rather than discovered later, and pinned here so it stays a known residual
   * instead of a latent one.
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
        .describedAs("the known residual: a required UML ownership property has nowhere to ride")
        .isEqualTo(3);
    assertThat(projected.stdout()).contains("Port.component");
    assertThat(exportCodes(path(NESTED_SOURCE_FIXTURE), path(NESTED_LAYOUT_FIXTURE)))
        .describedAs("and the export said so, at the moment it wrote the file")
        .contains(DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code());
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
    // The other half of the same defect: everything else under properties is still dropped, and
    // the export now says which, instead of leaving it to be discovered a command later.
    assertThat(exportCodes(path(SEQUENCE_SOURCE_FIXTURE), path(SEQUENCE_LAYOUT_FIXTURE)))
        .contains(DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code());
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
    /** {@code project} rejects the re-imported model, so there is no second export at all. */
    PROJECT_REJECTS_THE_REIMPORTED_MODEL,
    /** The second export succeeds and draws the same graph at different coordinates. */
    LAYS_OUT_DIFFERENTLY
  }

  /**
   * The fixtures that do not reach the fixed point, and every one of them for the same reason.
   *
   * <p><strong>One root cause, two symptoms.</strong> mxGraph has nowhere to keep an element's
   * {@code properties}, and this export carries exactly one of them ({@code uml.sequence}, on the
   * edge wrapper, because a Message is invalid without it). Everything else is dropped, which shows
   * up in two ways. Six models become invalid: a required UML ownership property — {@code
   * Port.component}, {@code ExtensionPoint.use_case}, {@code Transition.region}, {@code
   * ExecutionSpecification.covered} — is gone, so {@code project} rejects the re-imported model
   * before a second export can happen. Two more stay valid and move: {@code UmlNotationSemantics}
   * sizes a Class box from {@code uml.attributes}/{@code uml.operations} and builds a sequence
   * fragment's layout intents from {@code uml.covered}, so without them ELK draws the same graph at
   * different coordinates ({@code class-customer} 300×190 → 220×120; {@code m1@46.0} → {@code m1}).
   *
   * <p>Excluding them here is not a weakening of the assertion — {@link
   * #everyFixtureStillExcludedFromTheFixedPointStillNeedsToBe} re-measures every entry and fails
   * when one starts passing, so the list can only shrink deliberately. The general remedy is a
   * property channel on the {@code <object>} wrapper, which is a product decision (it puts model
   * JSON in front of a user in draw.io's Edit Data dialog) and is not taken here.
   */
  private static final Map<String, Residual> NOT_YET_A_FIXED_POINT =
      Map.of(
          "uml-component-basic.json", Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL,
          "uml-use-case-basic.json", Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL,
          "uml-sequence-lifecycle.json", Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL,
          "uml-state-machine-basic.json", Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL,
          "uml-state-machine-two-node-cycle.json", Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL,
          "uml-state-machine-multi-cycle.json", Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL,
          "uml-complex-class.json", Residual.LAYS_OUT_DIFFERENTLY,
          "uml-sequence-fragments.json", Residual.LAYS_OUT_DIFFERENTLY);

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
    Residual residual = NOT_YET_A_FIXED_POINT.get(name + ".json");
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
    if (residual == Residual.PROJECT_REJECTS_THE_REIMPORTED_MODEL) {
      assertThat(projected.exitCode())
          .describedAs(
              "%s now projects cleanly, so it no longer belongs in NOT_YET_A_FIXED_POINT: %s",
              name, projected.stdout())
          .isNotZero();
      return;
    }

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

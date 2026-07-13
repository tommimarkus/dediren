package dev.dediren.semantics.uml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.ir.Axis;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import dev.dediren.ir.LayoutIntent.StemSpan;
import dev.dediren.semantics.uml.SequenceConstraint.DestructionAnchor;
import dev.dediren.semantics.uml.SequenceConstraint.ExecutionSpan;
import dev.dediren.semantics.uml.SequenceConstraint.FragmentOpen;
import dev.dediren.semantics.uml.SequenceConstraint.LifelineOrder;
import dev.dediren.semantics.uml.SequenceConstraint.MessageOrder;
import dev.dediren.semantics.uml.SequenceConstraint.OperandOpen;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class UmlSequenceConstraintsTest {

  @Test
  void sequenceConstraintsReturnsEmptyForNonSequenceViewKinds() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-uml-basic.json");
    GenericGraphView view = viewOf(source, "class-view");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view)).isEmpty();
  }

  @Test
  void buildsTypedLifelineAndMessageOrderConstraints() throws Exception {
    SourceDocument source = sequenceFixtureWithReorderedMessagesForConstraints();
    GenericGraphView view = viewOf(source, "sequence-view");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service")),
            new MessageOrder(List.of("m2", "m1", "m3")));
  }

  // Message ordering keys on the declared uml.sequence value as a BigInteger, so a value beyond
  // Long.MAX_VALUE must still sort last rather than overflow.
  @Test
  void buildsTypedMessageOrderWithLargeIntegralSequenceValues() throws Exception {
    SourceDocument source = sequenceFixtureWithLargeSequenceForConstraints();
    GenericGraphView view = viewOf(source, "sequence-view");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service")),
            new MessageOrder(List.of("m3", "m2", "m1")));
  }

  @Test
  void buildsTypedFragmentAndOperandOpenConstraints() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-uml-sequence-fragments.json");
    GenericGraphView view = viewOf(source, "sequence-fragments-view");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service", "inventory", "payment")),
            new MessageOrder(
                List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10", "m11", "m12")),
            new FragmentOpen(List.of("m1", "m5", "m7", "m9")),
            new OperandOpen(List.of("m3", "m11")));
  }

  @Test
  void buildsExecutionSpanAndDestructionAnchorConstraintsFromCoveredAndMessageIds()
      throws Exception {
    SourceDocument source = executionAndDestructionSource();
    GenericGraphView view = executionAndDestructionView();

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service", "worker")),
            new MessageOrder(List.of("m1", "m2", "m3", "m4")),
            new ExecutionSpan("exec-1", "service", "m1", "m4"),
            new DestructionAnchor("destroy-1", "worker", "m3"));
  }

  // Robustness: an ExecutionSpecification missing covered/start/finish must be skipped rather than
  // crash layout — it simply contributes no ExecutionSpan constraint.
  @Test
  void skipsExecutionSpecificationMissingCoveredStartOrFinish() throws Exception {
    SourceNode malformedExec =
        new SourceNode("exec-bad", "ExecutionSpecification", "Bad Exec", Map.of("uml", emptyUml()));
    SourceDocument source = twoLifelineSourceWithExtraNode(malformedExec);
    GenericGraphView view = twoLifelineViewWithExtraNode("exec-bad");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service")), new MessageOrder(List.of("m1")));
  }

  // Robustness: covered/start/finish that don't resolve to a selected lifeline/message id (e.g. a
  // typo'd or removed id) must not crash layout either.
  @Test
  void skipsExecutionSpecificationWhoseCoveredStartOrFinishDontResolve() throws Exception {
    SourceNode danglingExec =
        new SourceNode(
            "exec-bad",
            "ExecutionSpecification",
            "Bad Exec",
            Map.of(
                "uml", umlOf("{\"covered\":\"ghost\",\"start\":\"nope\",\"finish\":\"nope2\"}")));
    SourceDocument source = twoLifelineSourceWithExtraNode(danglingExec);
    GenericGraphView view = twoLifelineViewWithExtraNode("exec-bad");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service")), new MessageOrder(List.of("m1")));
  }

  // Robustness: a destruction whose covered doesn't resolve to a selected lifeline must not crash
  // layout — skip it rather than emit a StemSpan anchored to nothing.
  @Test
  void skipsDestructionOccurrenceWithUnresolvableCovered() throws Exception {
    SourceNode danglingDestruction =
        new SourceNode(
            "destroy-bad",
            "DestructionOccurrenceSpecification",
            "Bad Destroy",
            Map.of("uml", umlOf("{\"covered\":\"ghost\"}")));
    SourceDocument source = twoLifelineSourceWithExtraNode(danglingDestruction);
    GenericGraphView view = twoLifelineViewWithExtraNode("destroy-bad");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service")), new MessageOrder(List.of("m1")));
  }

  // The orphan case: a destruction no message targets still gets a deterministic anchor (Task 3
  // places it below the last row), rather than being dumped at the layout origin.
  @Test
  void emitsDestructionAnchorWithNullAnchorMessageForAnOrphanDestruction() throws Exception {
    SourceNode orphanDestruction =
        new SourceNode(
            "destroy-orphan",
            "DestructionOccurrenceSpecification",
            "Orphan Destroy",
            Map.of("uml", umlOf("{\"covered\":\"service\"}")));
    SourceDocument source = twoLifelineSourceWithExtraNode(orphanDestruction);
    GenericGraphView view = twoLifelineViewWithExtraNode("destroy-orphan");

    assertThat(UmlSequenceConstraints.sequenceConstraints(source, view))
        .containsExactly(
            new LifelineOrder(List.of("customer", "service")),
            new MessageOrder(List.of("m1")),
            new DestructionAnchor("destroy-orphan", "service", null));
  }

  @Test
  void loweringMapsExecutionSpanToAStemSpanOnTheCoveredLifelineAcrossStartAndFinish() {
    var intents =
        UmlSequenceConstraints.lower(List.of(new ExecutionSpan("exec-1", "service", "m1", "m4")));

    assertThat(intents).containsExactly(new StemSpan("exec-1", "service", "m1", "m4"));
  }

  @Test
  void loweringMapsDestructionAnchorToADegenerateStemSpanAtTheAnchorMessage() {
    var intents =
        UmlSequenceConstraints.lower(List.of(new DestructionAnchor("destroy-1", "worker", "m3")));

    assertThat(intents).containsExactly(new StemSpan("destroy-1", "worker", "m3", "m3"));
  }

  // Documents the orphan convention: empty from/to means "below the last row" (Task 3's job to
  // interpret), rather than null or a sentinel id that could collide with a real message id.
  @Test
  void loweringMapsOrphanDestructionAnchorToAStemSpanWithEmptyFromAndTo() {
    var intents =
        UmlSequenceConstraints.lower(
            List.of(new DestructionAnchor("destroy-orphan", "service", null)));

    assertThat(intents).containsExactly(new StemSpan("destroy-orphan", "service", "", ""));
  }

  @Test
  void loweringPlacesLifelineColumnsHeadBandAndMessageGaps() {
    var intents =
        UmlSequenceConstraints.lower(
            List.of(
                new LifelineOrder(List.of("customer", "service")),
                new MessageOrder(List.of("m1", "m2", "m3")),
                new FragmentOpen(List.of("m2")),
                new OperandOpen(List.of("m3"))));

    assertThat(intents)
        .containsExactly(
            new OrderedBand(
                Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
            new OrderedBand(
                Axis.Y,
                List.of(
                    new BandMember("m1", 0.0),
                    new BandMember("m2", 46.0),
                    new BandMember("m3", 68.0))));
  }

  @Test
  void loweringPrefersFragmentOpenGapWhenMessageIsInBothSets() {
    // Pins the precedence observed in elk's SequenceLayoutConstraints#normalizedMessageYSlots
    // (checks fragmentOpenIds before operandOpenIds): fragment-open wins when a message id is in
    // both sets.
    var intents =
        UmlSequenceConstraints.lower(
            List.of(
                new MessageOrder(List.of("m1")),
                new FragmentOpen(List.of("m1")),
                new OperandOpen(List.of("m1"))));

    assertThat(intents)
        .containsExactly(new OrderedBand(Axis.Y, List.of(new BandMember("m1", 46.0))));
  }

  private static GenericGraphView viewOf(SourceDocument source, String viewId) {
    return pluginData(source).views().stream()
        .filter(view -> view.id().equals(viewId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected generic-graph view " + viewId));
  }

  private static GenericGraphPluginData pluginData(SourceDocument source) {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
  }

  // Mirrors the old GenericGraphPluginTest#sequenceFixtureWithReorderedMessagesForConstraints:
  // reassigns m1/m2's declared uml.sequence and reorders the relationship array itself, so the
  // resulting message-order constraint proves ordering follows the declared sequence value, not
  // source declaration order.
  private static SourceDocument sequenceFixtureWithReorderedMessagesForConstraints()
      throws Exception {
    JsonNode source =
        JsonSupport.objectMapper()
            .readTree(fixtureText("fixtures/source/valid-uml-sequence-basic.json"));
    var relationships = (ArrayNode) source.get("relationships");
    JsonNode m1 = relationships.get(0).deepCopy();
    JsonNode m2 = relationships.get(1).deepCopy();
    JsonNode m3 = relationships.get(2).deepCopy();

    ((ObjectNode) m1.at("/properties/uml")).put("sequence", 2);
    ((ObjectNode) m2.at("/properties/uml")).put("sequence", 1);
    relationships.removeAll();
    relationships.add(m3);
    relationships.add(m2);
    relationships.add(m1);

    return JsonSupport.objectMapper().treeToValue(source, SourceDocument.class);
  }

  private static SourceDocument sequenceFixtureWithLargeSequenceForConstraints() throws Exception {
    JsonNode source =
        JsonSupport.objectMapper()
            .readTree(fixtureText("fixtures/source/valid-uml-sequence-basic.json"));
    var relationships = (ArrayNode) source.get("relationships");
    JsonNode m1 = relationships.get(0).deepCopy();
    JsonNode m2 = relationships.get(1).deepCopy();
    JsonNode m3 = relationships.get(2).deepCopy();

    ((ObjectNode) m1.at("/properties/uml"))
        .set(
            "sequence",
            JsonSupport.objectMapper()
                .getNodeFactory()
                .numberNode(new BigInteger("9223372036854775808")));
    ((ObjectNode) m2.at("/properties/uml")).put("sequence", 2);
    ((ObjectNode) m3.at("/properties/uml")).put("sequence", 1);
    relationships.removeAll();
    relationships.add(m3);
    relationships.add(m2);
    relationships.add(m1);

    return JsonSupport.objectMapper().treeToValue(source, SourceDocument.class);
  }

  // Minimal in-memory source (no fixture file involved, per the byte-stability guard: no existing
  // fixture has these node types, and this task must not add one) exercising the primary
  // ExecutionSpecification/DestructionOccurrenceSpecification scenario from the design doc: an
  // activation bar on "service" spanning m1..m4, and a destruction of "worker" anchored by the
  // delete-message m3.
  private static SourceDocument executionAndDestructionSource() throws Exception {
    var nodes =
        List.of(
            new SourceNode("customer", "Lifeline", "Customer", Map.of()),
            new SourceNode("service", "Lifeline", "Order Service", Map.of()),
            new SourceNode("worker", "Lifeline", "Worker", Map.of()),
            new SourceNode(
                "exec-1",
                "ExecutionSpecification",
                "Handle Order",
                Map.of(
                    "uml", umlOf("{\"covered\":\"service\",\"start\":\"m1\",\"finish\":\"m4\"}"))),
            new SourceNode(
                "destroy-1",
                "DestructionOccurrenceSpecification",
                "Worker Destroyed",
                Map.of("uml", umlOf("{\"covered\":\"worker\"}"))));
    var relationships =
        List.of(
            message("m1", "customer", "service", 1),
            message("m2", "service", "worker", 2),
            message("m3", "service", "destroy-1", 3),
            message("m4", "service", "customer", 4));
    return new SourceDocument(null, null, null, nodes, relationships, null);
  }

  private static GenericGraphView executionAndDestructionView() {
    return new GenericGraphView(
        "sequence-view",
        "Sequence",
        GenericGraphViewKind.UML_SEQUENCE,
        List.of("customer", "service", "worker", "exec-1", "destroy-1"),
        List.of("m1", "m2", "m3", "m4"),
        null,
        null);
  }

  // A two-lifeline, one-message base document plus one extra node under test, used by the
  // malformed/unresolvable/orphan robustness cases so each test only varies the one node that
  // matters.
  private static SourceDocument twoLifelineSourceWithExtraNode(SourceNode extra) throws Exception {
    var nodes =
        List.of(
            new SourceNode("customer", "Lifeline", "Customer", Map.of()),
            new SourceNode("service", "Lifeline", "Order Service", Map.of()),
            extra);
    var relationships = List.of(message("m1", "customer", "service", 1));
    return new SourceDocument(null, null, null, nodes, relationships, null);
  }

  private static GenericGraphView twoLifelineViewWithExtraNode(String extraNodeId) {
    return new GenericGraphView(
        "sequence-view",
        "Sequence",
        GenericGraphViewKind.UML_SEQUENCE,
        List.of("customer", "service", extraNodeId),
        List.of("m1"),
        null,
        null);
  }

  private static SourceRelationship message(String id, String source, String target, int sequence)
      throws Exception {
    return new SourceRelationship(
        id, "Message", source, target, id, Map.of("uml", umlOf("{\"sequence\":" + sequence + "}")));
  }

  private static JsonNode umlOf(String json) throws Exception {
    return JsonSupport.objectMapper().readTree(json);
  }

  private static JsonNode emptyUml() throws Exception {
    return umlOf("{}");
  }

  private static SourceDocument fixture(String path) throws Exception {
    return JsonSupport.objectMapper().readValue(fixtureText(path), SourceDocument.class);
  }

  private static String fixtureText(String path) throws Exception {
    return Files.readString(workspaceRoot().resolve(path));
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

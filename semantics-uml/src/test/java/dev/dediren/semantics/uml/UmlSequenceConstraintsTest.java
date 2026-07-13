package dev.dediren.semantics.uml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.Axis;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import dev.dediren.semantics.uml.SequenceConstraint.FragmentOpen;
import dev.dediren.semantics.uml.SequenceConstraint.LifelineOrder;
import dev.dediren.semantics.uml.SequenceConstraint.MessageOrder;
import dev.dediren.semantics.uml.SequenceConstraint.OperandOpen;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class UmlSequenceConstraintsTest {

  @Test
  void returnsEmptyForNonSequenceViewKinds() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-uml-basic.json");
    GenericGraphView view = viewOf(source, "class-view");

    assertThat(UmlSequenceConstraints.of(source, view)).isEmpty();
  }

  @Test
  void projectsLifelineAndMessageOrderConstraints() throws Exception {
    SourceDocument source = sequenceFixtureWithReorderedMessagesForConstraints();
    GenericGraphView view = viewOf(source, "sequence-view");

    List<LayoutConstraint> constraints = UmlSequenceConstraints.of(source, view);
    LayoutConstraint lifelineOrder = constraintOf(constraints, "uml.sequence.lifeline-order");
    LayoutConstraint messageOrder = constraintOf(constraints, "uml.sequence.message-order");

    assertThat(constraints)
        .extracting(LayoutConstraint::kind)
        .containsExactly("uml.sequence.lifeline-order", "uml.sequence.message-order");
    assertThat(lifelineOrder.id()).isEqualTo("sequence-view.uml.sequence.lifeline-order");
    assertThat(lifelineOrder.subjects()).containsExactly("customer", "service");
    assertThat(messageOrder.id()).isEqualTo("sequence-view.uml.sequence.message-order");
    assertThat(messageOrder.subjects()).containsExactly("m2", "m1", "m3");
  }

  @Test
  void projectsMessageOrderWithLargeIntegralSequenceValues() throws Exception {
    SourceDocument source = sequenceFixtureWithLargeSequenceForConstraints();
    GenericGraphView view = viewOf(source, "sequence-view");

    List<LayoutConstraint> constraints = UmlSequenceConstraints.of(source, view);

    assertThat(constraintOf(constraints, "uml.sequence.message-order").subjects())
        .containsExactly("m3", "m2", "m1");
  }

  @Test
  void projectsFragmentAndOperandOpenConstraintsForSequenceFragments() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-uml-sequence-fragments.json");
    GenericGraphView view = viewOf(source, "sequence-fragments-view");

    List<LayoutConstraint> constraints = UmlSequenceConstraints.of(source, view);

    assertThat(constraints)
        .extracting(LayoutConstraint::kind)
        .containsExactly(
            "uml.sequence.lifeline-order",
            "uml.sequence.message-order",
            "uml.sequence.fragment-open",
            "uml.sequence.operand-open");
    assertThat(constraintOf(constraints, "uml.sequence.fragment-open").subjects())
        .containsExactlyInAnyOrder("m1", "m5", "m7", "m9");
    assertThat(constraintOf(constraints, "uml.sequence.operand-open").subjects())
        .containsExactlyInAnyOrder("m3", "m11");
  }

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

  private static LayoutConstraint constraintOf(List<LayoutConstraint> constraints, String kind) {
    return constraints.stream()
        .filter(constraint -> constraint.kind().equals(kind))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected layout constraint " + kind));
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

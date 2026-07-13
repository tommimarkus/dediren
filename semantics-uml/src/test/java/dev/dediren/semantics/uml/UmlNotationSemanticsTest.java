package dev.dediren.semantics.uml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class UmlNotationSemanticsTest {
  private final UmlNotationSemantics notation = new UmlNotationSemantics();

  @Test
  void layoutRoleIsLifelineOrInteractionForSequenceTypesOnly() {
    assertThat(notation.layoutRole("Lifeline")).isEqualTo("lifeline");
    assertThat(notation.layoutRole("Interaction")).isEqualTo("interaction");
    assertThat(notation.layoutRole("Class")).isNull();
  }

  @Test
  void sizingDelegatesToUmlLayoutSizing() {
    SourceNode lifeline = new SourceNode("customer", "Lifeline", "Customer", Map.of());
    SourceNode classifier = new SourceNode("order", "Class", "Order", Map.of());

    assertThat(notation.widthHint(lifeline)).isEqualTo(UmlLayoutSizing.widthHint(lifeline));
    assertThat(notation.heightHint(lifeline)).isEqualTo(UmlLayoutSizing.heightHint(lifeline));
    assertThat(notation.widthHint(classifier)).isEqualTo(UmlLayoutSizing.widthHint(classifier));
    assertThat(notation.heightHint(classifier)).isEqualTo(UmlLayoutSizing.heightHint(classifier));
  }

  // Mirrors the old GenericGraphPluginTest#excludesSequenceFragmentSemanticNodesFromLayoutRequest:
  // CombinedFragment/InteractionOperand are notation-only chrome that must not become scene nodes,
  // but only inside a uml-sequence view; every other node type and view kind keeps every node.
  @Test
  void isSourceOnlyNodeExcludesCombinedFragmentAndInteractionOperandOnlyInSequenceViews()
      throws Exception {
    SourceDocument fragments = fixture("fixtures/source/valid-uml-sequence-fragments.json");
    GenericGraphView sequenceFragmentsView = viewOf(fragments, "sequence-fragments-view");

    assertThat(
            notation.isSourceOnlyNode(
                sequenceFragmentsView, nodeById(fragments, "cf-availability")))
        .isTrue();
    assertThat(notation.isSourceOnlyNode(sequenceFragmentsView, nodeById(fragments, "op-in-stock")))
        .isTrue();
    assertThat(notation.isSourceOnlyNode(sequenceFragmentsView, nodeById(fragments, "customer")))
        .isFalse();

    SourceDocument classModel = fixture("fixtures/source/valid-uml-basic.json");
    GenericGraphView classView = viewOf(classModel, "class-view");
    assertThat(notation.isSourceOnlyNode(classView, nodeById(classModel, "class-order"))).isFalse();
  }

  @Test
  void layoutIntentsDelegatesToLowering() throws Exception {
    SourceDocument sequence = fixture("fixtures/source/valid-uml-sequence-basic.json");
    GenericGraphView sequenceView = viewOf(sequence, "sequence-view");

    assertThat(notation.layoutIntents(sequence, sequenceView))
        .isEqualTo(
            UmlSequenceConstraints.lower(
                UmlSequenceConstraints.sequenceConstraints(sequence, sequenceView)));

    SourceDocument classModel = fixture("fixtures/source/valid-uml-basic.json");
    GenericGraphView classView = viewOf(classModel, "class-view");
    assertThat(notation.layoutIntents(classModel, classView)).isEmpty();
  }

  @Test
  void nodeRenderPropertiesReturnsTheUmlSubtreeAcrossViewKinds() throws Exception {
    SourceDocument classModel = fixture("fixtures/source/valid-uml-basic.json");
    SourceDocument stateMachine = fixture("fixtures/source/valid-uml-state-machine-basic.json");
    SourceDocument useCase = fixture("fixtures/source/valid-uml-use-case-basic.json");
    SourceDocument component = fixture("fixtures/source/valid-uml-component-basic.json");
    SourceDocument deployment = fixture("fixtures/source/valid-uml-deployment-basic.json");
    SourceDocument fragments = fixture("fixtures/source/valid-uml-sequence-fragments.json");

    assertThat(
            notation
                .nodeRenderProperties(nodeById(classModel, "class-order"))
                .at("/attributes/0/name")
                .asText())
        .isEqualTo("id");
    assertThat(
            notation
                .nodeRenderProperties(nodeById(classModel, "enum-order-status"))
                .at("/literals/1")
                .asText())
        .isEqualTo("Submitted");
    assertThat(
            notation
                .nodeRenderProperties(nodeById(stateMachine, "payment-choice"))
                .at("/kind")
                .asText())
        .isEqualTo("choice");
    assertThat(
            notation.nodeRenderProperties(nodeById(useCase, "place-order")).at("/subject").asText())
        .isEqualTo("order-service");
    assertThat(
            notation
                .nodeRenderProperties(nodeById(useCase, "payment-extension"))
                .at("/use_case")
                .asText())
        .isEqualTo("place-order");
    assertThat(
            notation
                .nodeRenderProperties(nodeById(component, "port-rest-api"))
                .at("/component")
                .asText())
        .isEqualTo("component-order-api");
    assertThat(
            notation
                .nodeRenderProperties(nodeById(deployment, "ee-orders-runtime"))
                .at("/node")
                .asText())
        .isEqualTo("device-prod-node");
    assertThat(
            notation
                .nodeRenderProperties(nodeById(fragments, "cf-availability"))
                .at("/operator")
                .asText())
        .isEqualTo("alt");
    assertThat(
            notation.nodeRenderProperties(nodeById(fragments, "op-in-stock")).at("/guard").asText())
        .isEqualTo("inStock");
  }

  @Test
  void edgeRenderPropertiesReturnsTheUmlSubtreeAcrossViewKinds() throws Exception {
    SourceDocument classModel = fixture("fixtures/source/valid-uml-basic.json");
    SourceDocument stateMachine = fixture("fixtures/source/valid-uml-state-machine-basic.json");
    SourceDocument useCase = fixture("fixtures/source/valid-uml-use-case-basic.json");
    SourceDocument sequence = fixture("fixtures/source/valid-uml-sequence-basic.json");

    assertThat(
            notation
                .edgeRenderProperties(relationshipById(classModel, "order-has-lines"))
                .at("/source_multiplicity")
                .asText())
        .isEqualTo("1");
    assertThat(
            notation
                .edgeRenderProperties(relationshipById(classModel, "order-has-lines"))
                .at("/target_multiplicity")
                .asText())
        .isEqualTo("1..*");
    assertThat(
            notation
                .edgeRenderProperties(relationshipById(stateMachine, "t-approve"))
                .at("/guard")
                .asText())
        .isEqualTo("paymentAuthorized");
    assertThat(
            notation
                .edgeRenderProperties(relationshipById(useCase, "extend-discount"))
                .at("/extension_point")
                .asText())
        .isEqualTo("payment-extension");
    assertThat(
            notation.edgeRenderProperties(relationshipById(sequence, "m1")).at("/sequence").asInt())
        .isEqualTo(1);
    assertThat(
            notation
                .edgeRenderProperties(relationshipById(sequence, "m1"))
                .at("/message_sort")
                .asText())
        .isEqualTo("synchCall");
  }

  @Test
  void validatesValidUmlSource() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-uml-basic.json");

    assertThatCode(() -> notation.validate(source, pluginData(source))).doesNotThrowAnyException();
  }

  @Test
  void rejectsInvalidUmlRelationshipEndpoint() throws Exception {
    JsonNode raw =
        JsonSupport.objectMapper().readTree(fixtureText("fixtures/source/valid-uml-basic.json"));
    ((ObjectNode) raw.at("/relationships/2")).put("type", "Composition");
    SourceDocument source = JsonSupport.objectMapper().treeToValue(raw, SourceDocument.class);

    assertThatThrownBy(() -> notation.validate(source, pluginData(source)))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
            });
  }

  private static SourceNode nodeById(SourceDocument source, String id) {
    return source.nodes().stream()
        .filter(node -> node.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected source node " + id));
  }

  private static SourceRelationship relationshipById(SourceDocument source, String id) {
    return source.relationships().stream()
        .filter(relationship -> relationship.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected source relationship " + id));
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

package dev.dediren.semantics.uml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class UmlLayoutSizingTest {

  @Test
  void classifierSizingUsesCompartmentText() throws Exception {
    JsonNode uml =
        JsonSupport.objectMapper()
            .readTree(
                """
                {
                  "attributes": [
                    {"visibility": "private", "name": "a", "type": "B"},
                    {"visibility": "private", "name": "c", "type": "D"},
                    {"visibility": "private", "name": "e", "type": "F"}
                  ],
                  "operations": [
                    {"visibility": "public", "name": "g", "return_type": "H"},
                    {"visibility": "public", "name": "i", "return_type": "J"}
                  ]
                }
                """);
    // 26-char label dominates width; 3 attrs + 2 ops push height above the 120 floor.
    SourceNode classifier =
        new SourceNode("repository", "Class", "CustomerRepositoryGatewayA", Map.of("uml", uml));

    // width  = roundUp(max(26*8 + 32, 220), 20) = roundUp(240, 20) = 240.0
    // height = roundUp(max(28 + (3*14+8) + (2*14+8) + 14, 120), 10) = roundUp(128, 10) = 130.0
    assertThat(UmlLayoutSizing.widthHint(classifier)).isEqualTo(240.0);
    assertThat(UmlLayoutSizing.heightHint(classifier)).isEqualTo(130.0);
  }

  @Test
  void compactActivityNodesUseA32x32Hint() {
    assertSize(node("InitialNode"), 32.0, 32.0);
    assertSize(node("ActivityFinalNode"), 32.0, 32.0);
    assertSize(node("DecisionNode"), 32.0, 32.0);
  }

  @Test
  void sequenceDiagramNodesUseFixedGeometry() {
    assertSize(node("Interaction"), 360.0, 260.0);
    assertSize(node("Lifeline"), 140.0, 48.0);
    assertSize(node("ExecutionSpecification"), 16.0, 72.0);
    assertSize(node("Gate"), 24.0, 24.0);
    assertSize(node("DestructionOccurrenceSpecification"), 24.0, 24.0);
  }

  @Test
  void stateMachineNodesUseFixedGeometry() {
    assertSize(node("State"), 150.0, 72.0);
    assertSize(node("Pseudostate"), 36.0, 36.0);
    assertSize(node("FinalState"), 36.0, 36.0);
  }

  @Test
  void useCaseNodesUseFixedGeometry() {
    assertSize(node("Actor"), 80.0, 120.0);
    assertSize(node("UseCase"), 160.0, 72.0);
    assertSize(node("ExtensionPoint"), 140.0, 40.0);
  }

  @Test
  void componentNodesUseFixedGeometry() {
    assertSize(node("Component"), 180.0, 96.0);
    assertSize(node("Port"), 32.0, 32.0);
  }

  @Test
  void deploymentNodesUseFixedGeometry() {
    assertSize(node("Device"), 200.0, 120.0);
    assertSize(node("Node"), 200.0, 120.0);
    assertSize(node("ExecutionEnvironment"), 180.0, 96.0);
    assertSize(node("Artifact"), 150.0, 70.0);
    assertSize(node("DeploymentSpecification"), 190.0, 70.0);
  }

  @Test
  void unmatchedUmlNodeTypesFallBackToTheSharedFloor() {
    assertSize(node("Package"), 160.0, 80.0);
  }

  @Test
  void projectsUmlStructuralSizeHintsFromCompartments() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-uml-complex.json");

    assertSize(nodeById(source, "class-order"), 300.0, 190.0);
    assertThat(UmlLayoutSizing.heightHint(nodeById(source, "class-shipment"))).isEqualTo(130.0);
    assertSize(nodeById(source, "interface-payment-gateway"), 380.0, 120.0);
  }

  private static void assertSize(SourceNode node, double width, double height) {
    assertThat(UmlLayoutSizing.widthHint(node)).isEqualTo(width);
    assertThat(UmlLayoutSizing.heightHint(node)).isEqualTo(height);
  }

  private static SourceNode node(String type) {
    return new SourceNode(type, type, "", Map.of());
  }

  private static SourceNode nodeById(SourceDocument source, String id) {
    return source.nodes().stream()
        .filter(node -> node.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected source node " + id));
  }

  private static SourceDocument fixture(String path) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(path)), SourceDocument.class);
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

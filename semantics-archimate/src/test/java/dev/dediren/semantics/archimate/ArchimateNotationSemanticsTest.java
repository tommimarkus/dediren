package dev.dediren.semantics.archimate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchimateNotationSemanticsTest {
  private final ArchimateNotationSemantics notation = new ArchimateNotationSemantics();

  @Test
  void layoutRoleIsJunctionOnlyForRelationshipConnectorTypes() {
    assertThat(notation.layoutRole("AndJunction")).isEqualTo("junction");
    assertThat(notation.layoutRole("OrJunction")).isEqualTo("junction");
    assertThat(notation.layoutRole("ApplicationComponent")).isNull();
  }

  @Test
  void sizingDelegatesToArchimateLayoutSizing() {
    SourceNode connector = new SourceNode("junction", "AndJunction", "And", Map.of());
    SourceNode component = new SourceNode("api", "ApplicationComponent", "API", Map.of());

    assertThat(notation.widthHint(connector)).isEqualTo(ArchimateLayoutSizing.widthHint(connector));
    assertThat(notation.heightHint(connector))
        .isEqualTo(ArchimateLayoutSizing.heightHint(connector));
    assertThat(notation.widthHint(component)).isEqualTo(ArchimateLayoutSizing.widthHint(component));
    assertThat(notation.heightHint(component))
        .isEqualTo(ArchimateLayoutSizing.heightHint(component));
  }

  @Test
  void filtersNothingHasNoConstraintsAndNoRenderSelectors() {
    SourceNode node = new SourceNode("n", "ApplicationComponent", "N", Map.of());
    assertThat(notation.isSourceOnlyNode(null, node)).isFalse();
    assertThat(notation.layoutConstraints(null, null)).isEmpty();
    assertThat(notation.nodeRenderProperties(node)).isNull();
    assertThat(notation.edgeRenderProperties(null)).isNull();
  }

  @Test
  void validatesValidArchimateSource() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-archimate-oef.json");

    assertThatCode(() -> notation.validate(source, null)).doesNotThrowAnyException();
  }

  @Test
  void projectsJunctionRoleOntoArchimateNodes() throws Exception {
    SourceDocument source = fixture("fixtures/source/valid-archimate-junction.json");

    assertThat(notation.layoutRole(typeOf(source, "fulfillment-junction"))).isEqualTo("junction");
    assertThat(notation.layoutRole(typeOf(source, "approval-junction"))).isEqualTo("junction");
    assertThat(notation.layoutRole(typeOf(source, "order-intake"))).isNull();
  }

  @Test
  void rejectsUnknownArchimateElementType() {
    SourceDocument source =
        document(
            List.of(node("orders-component", "TechnologyNode", "Orders Component")), List.of());

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().message()).contains("TechnologyNode");
            });
  }

  @Test
  void rejectsJunctionElementTypeItself() {
    SourceDocument source =
        document(List.of(node("orders-component", "Junction", "Orders Component")), List.of());

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().message()).contains("Junction");
            });
  }

  @Test
  void rejectsUnknownArchimateRelationshipType() {
    SourceDocument source =
        document(
            List.of(
                node("orders-component", "ApplicationComponent", "Orders Component"),
                node("orders-service", "ApplicationService", "Orders Service")),
            List.of(
                relationship(
                    "orders-realizes-service",
                    "ConnectsTo",
                    "orders-component",
                    "orders-service")));

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().message()).contains("ConnectsTo");
            });
  }

  // Old GenericGraphPluginTest asserted the identical code/message from this input via both the
  // "validate" command and the "project" (render-metadata) command, because both call sites ran
  // the same GenericGraphEngine.validateArchimateSource. Here that collapses into one test: the
  // single validate() hook backs both.
  @Test
  void rejectsInvalidArchimateRelationshipEndpointTypes() {
    SourceDocument source =
        document(
            List.of(
                node("orders-component", "ApplicationService", "Orders Component"),
                node("orders-service", "ApplicationComponent", "Orders Service")),
            List.of(
                relationship(
                    "orders-realizes-service",
                    "Realization",
                    "orders-component",
                    "orders-service")));

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().message())
                  .contains("ApplicationService", "Realization", "ApplicationComponent");
            });
  }

  @Test
  void rejectsArchimateJunctionWithMixedRelationshipTypes() {
    SourceDocument source =
        junctionSource(
            "Flow",
            "Serving",
            "api",
            "ApplicationComponent",
            "junction",
            "orders",
            "ApplicationService");

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED");
              assertThat(error.diagnostics().getFirst().message())
                  .contains("junction", "Flow", "Serving");
            });
  }

  @Test
  void rejectsArchimateJunctionWithInvalidEffectiveEndpoint() {
    SourceDocument source =
        junctionSource(
            "Realization",
            "Realization",
            "service",
            "ApplicationService",
            "junction",
            "component",
            "ApplicationComponent");

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().message())
                  .contains("ApplicationService", "Realization", "ApplicationComponent");
            });
  }

  @Test
  void rejectsArchimateJunctionWithoutIncomingAndOutgoingRelationships() {
    SourceDocument source =
        document(
            List.of(
                node("api", "ApplicationComponent", "API"), node("junction", "AndJunction", "")),
            List.of(relationship("api-to-junction", "Flow", "api", "junction")));

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE");
              assertThat(error.diagnostics().getFirst().message())
                  .contains("at least one incoming", "at least one outgoing");
            });
  }

  @Test
  void rejectsArchimateJunctionChainWithInvalidEffectiveEndpoint() {
    SourceDocument source =
        document(
            List.of(
                node("service", "ApplicationService", "Service"),
                node("join", "AndJunction", ""),
                node("split", "AndJunction", ""),
                node("component", "ApplicationComponent", "Component")),
            List.of(
                relationship("service-to-join", "Realization", "service", "join"),
                relationship("join-to-split", "Realization", "join", "split"),
                relationship("split-to-component", "Realization", "split", "component")));

    assertThatThrownBy(() -> notation.validate(source, null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().message())
                  .contains("ApplicationService", "Realization", "ApplicationComponent");
            });
  }

  @Test
  void allowsArchimateJunctionContainmentRelationship() {
    SourceDocument source =
        document(
            List.of(
                node("group", "Grouping", "Group"),
                node("api", "ApplicationComponent", "API"),
                node("junction", "AndJunction", ""),
                node("orders", "ApplicationService", "Orders")),
            List.of(
                relationship("group-contains-junction", "Composition", "group", "junction"),
                relationship("api-to-junction", "Flow", "api", "junction"),
                relationship("junction-to-orders", "Flow", "junction", "orders")));

    assertThatCode(() -> notation.validate(source, null)).doesNotThrowAnyException();
  }

  private static String typeOf(SourceDocument source, String nodeId) {
    return source.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected source node " + nodeId))
        .type();
  }

  // Mirrors the old GenericGraphPluginTest#archimateJunctionSource helper: a source-to-junction
  // relationship of incomingType, a junction-to-target relationship of outgoingType, three nodes.
  private static SourceDocument junctionSource(
      String incomingType,
      String outgoingType,
      String sourceId,
      String sourceType,
      String junctionId,
      String targetId,
      String targetType) {
    return document(
        List.of(
            node(sourceId, sourceType, "Source"),
            node(junctionId, "AndJunction", ""),
            node(targetId, targetType, "Target")),
        List.of(
            relationship("source-to-junction", incomingType, sourceId, junctionId),
            relationship("junction-to-target", outgoingType, junctionId, targetId)));
  }

  private static SourceDocument document(
      List<SourceNode> nodes, List<SourceRelationship> relationships) {
    return new SourceDocument("model.schema.v1", null, null, nodes, relationships, null);
  }

  private static SourceNode node(String id, String type, String label) {
    return new SourceNode(id, type, label, Map.of());
  }

  private static SourceRelationship relationship(
      String id, String type, String source, String target) {
    return new SourceRelationship(id, type, source, target, "", Map.of());
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

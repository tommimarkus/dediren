package dev.dediren.plugins.umlxmi.build;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoverageTest {

  private static SourceNode node(String id, String type) {
    return new SourceNode(id, type, id, Map.of());
  }

  private static SourceRelationship relationship(String id, String type) {
    return new SourceRelationship(id, type, "a", "b", id, Map.of());
  }

  @Test
  void reportsInSequenceAndDeploymentContentAsOmittedWhenOutOfScope() {
    // A class view is exported from a model that also spans a sequence Interaction and a deployment
    // node: only the class content is in scope, so the rest must be declared omitted (issue #32).
    List<SourceNode> nodes =
        List.of(
            node("class-order", "Class"),
            node("interaction-checkout", "Interaction"),
            node("lifeline-customer", "Lifeline"),
            node("lifeline-service", "Lifeline"),
            node("device-host", "Device"),
            node("artifact-war", "Artifact"));
    List<SourceRelationship> relationships =
        List.of(
            relationship("m1", "Message"),
            relationship("m2", "Message"),
            relationship("deploy-1", "Deployment"));
    ExportScope scope = new ExportScope(Set.of("class-order"), Set.of());

    Coverage coverage =
        Coverage.compute(nodes, relationships, scope, Set.of("class-order"), Set.of());

    assertThat(coverage.hasOmissions()).isTrue();
    assertThat(coverage.hasUnrepresentedInView()).isFalse();
    assertThat(coverage.representedNodes()).isEqualTo(1);
    assertThat(coverage.omittedNodes()).isEqualTo(5);
    assertThat(coverage.omittedNodeTypes())
        .containsEntry("Interaction", 1)
        .containsEntry("Lifeline", 2)
        .containsEntry("Device", 1)
        .containsEntry("Artifact", 1)
        .doesNotContainKey("Class");
    assertThat(coverage.omittedRelationships()).isEqualTo(3);
    assertThat(coverage.omittedRelationshipTypes())
        .containsEntry("Message", 2)
        .containsEntry("Deployment", 1);
    // Deterministic, machine-friendly histogram rendering, sorted by type.
    assertThat(Coverage.describe(coverage.omittedRelationshipTypes()))
        .isEqualTo("Deployment=1, Message=2");
  }

  @Test
  void reportsNoOmissionsWhenEverySourceElementIsInScope() {
    List<SourceNode> nodes = List.of(node("class-order", "Class"), node("class-line", "Class"));
    List<SourceRelationship> relationships = List.of(relationship("has-line", "Composition"));
    ExportScope scope = new ExportScope(Set.of("class-order", "class-line"), Set.of("has-line"));

    Coverage coverage =
        Coverage.compute(
            nodes, relationships, scope, Set.of("class-order", "class-line"), Set.of("has-line"));

    assertThat(coverage.hasOmissions()).isFalse();
    assertThat(coverage.hasUnrepresentedInView()).isFalse();
    assertThat(coverage.omittedNodes()).isZero();
    assertThat(coverage.omittedRelationships()).isZero();
    assertThat(coverage.representedNodes()).isEqualTo(2);
    assertThat(coverage.representedRelationships()).isEqualTo(1);
  }

  @Test
  void separatesInViewContentTheWritersDidNotEmitFromOutOfViewOmissions() {
    // A Generalization is selected in the view but (hypothetically) not emitted: it is neither
    // represented nor out-of-view, so it is reported as an in-view fidelity gap, not an omission.
    List<SourceNode> nodes = List.of(node("class-order", "Class"), node("class-base", "Class"));
    List<SourceRelationship> relationships = List.of(relationship("g1", "Generalization"));
    ExportScope scope = new ExportScope(Set.of("class-order", "class-base"), Set.of("g1"));

    Coverage coverage =
        Coverage.compute(
            nodes, relationships, scope, Set.of("class-order", "class-base"), Set.of());

    assertThat(coverage.hasOmissions()).isFalse();
    assertThat(coverage.hasUnrepresentedInView()).isTrue();
    assertThat(coverage.unrepresentedInViewRelationships()).isEqualTo(1);
    assertThat(coverage.unrepresentedInViewRelationshipTypes()).containsEntry("Generalization", 1);
    assertThat(coverage.representedNodes()).isEqualTo(2);
  }
}

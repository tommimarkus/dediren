package dev.dediren.plugins.umlxmi.write.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the endpoint predicate that keeps class-diagram emission from claiming relationships owned
 * by the use-case, component, or deployment writers. Without it those relationships would be
 * double-emitted (once as a spurious class {@code uml:Association}/{@code uml:Dependency}).
 */
class ClassRelationshipWriterTest {

  private static Map<String, SourceNode> nodes(SourceNode... nodes) {
    var byId = new java.util.HashMap<String, SourceNode>();
    for (SourceNode node : nodes) {
      byId.put(node.id(), node);
    }
    return byId;
  }

  private static SourceNode node(String id, String type) {
    return new SourceNode(id, type, id, Map.of());
  }

  private static SourceRelationship relationship(String type, String source, String target) {
    return new SourceRelationship("r", type, source, target, "r", Map.of());
  }

  @Test
  void treatsRelationshipsBetweenClassifiersAsClassRelationships() {
    var byId =
        nodes(
            node("c1", "Class"),
            node("c2", "Class"),
            node("i1", "Interface"),
            node("d1", "DataType"),
            node("e1", "Enumeration"));

    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Association", "c1", "c2"), byId))
        .isTrue();
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Composition", "c1", "d1"), byId))
        .isTrue();
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Aggregation", "c1", "c2"), byId))
        .isTrue();
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Dependency", "c1", "e1"), byId))
        .isTrue();
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Realization", "c1", "i1"), byId))
        .isTrue();
  }

  @Test
  void rejectsRelationshipsOwnedByOtherWriters() {
    var byId =
        nodes(
            node("c1", "Class"),
            node("actor", "Actor"),
            node("uc", "UseCase"),
            node("comp", "Component"),
            node("life", "Lifeline"));

    // Use-case actor association and component-endpoint dependency belong to their own writers.
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Association", "actor", "uc"), byId))
        .isFalse();
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Dependency", "comp", "c1"), byId))
        .isFalse();
    // A non-class-diagram relationship type is never a class relationship even between classifiers.
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Message", "life", "life"), byId))
        .isFalse();
    // Unknown endpoints (missing from the model) are not classifiers.
    assertThat(
            ClassRelationshipWriter.isClassRelationship(
                relationship("Association", "c1", "missing"), byId))
        .isFalse();
  }
}

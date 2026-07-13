package dev.dediren.semantics.archimate;

import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateJunctionValidationException;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.archimate.JunctionValidationNode;
import dev.dediren.archimate.JunctionValidationRelationship;
import dev.dediren.contracts.layout.LayoutNodeRole;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.ir.LayoutIntent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The ArchiMate notation: element/relationship/junction legality against {@link Archimate},
 * junction layout role, and label-fit sizing via {@link ArchimateLayoutSizing}. Relocated verbatim
 * from the old single generic-graph {@code GenericGraphEngine}'s ArchiMate branch (Plan B P3):
 * {@code validate} runs the exact same {@code validateArchimateSourceTypes} + {@code
 * validateArchimateJunctionSemantics} pair the old engine ran identically from both its {@code
 * validate()} command and its projection path, so a single hook here covers both call sites without
 * behavior drift. ArchiMate contributes no layout constraints and no render-metadata selectors; the
 * base loop in {@code semantics-graph} owns those defaults for a notation with nothing to add.
 */
public final class ArchimateNotationSemantics implements NotationSemantics {

  @Override
  public void validate(SourceDocument source, GenericGraphPluginData pluginData)
      throws EngineException {
    try {
      validateArchimateSourceTypes(source);
      validateArchimateJunctionSemantics(source);
    } catch (ArchimateTypeValidationException error) {
      throw EngineException.semanticFailure(error.code(), error.message(), error.path());
    } catch (ArchimateJunctionValidationException error) {
      throw EngineException.semanticFailure(error.code(), error.message(), error.path());
    }
  }

  @Override
  public String layoutRole(String sourceType) {
    return Archimate.isRelationshipConnectorType(sourceType) ? LayoutNodeRole.JUNCTION : null;
  }

  @Override
  public double widthHint(SourceNode node) {
    return ArchimateLayoutSizing.widthHint(node);
  }

  @Override
  public double heightHint(SourceNode node) {
    return ArchimateLayoutSizing.heightHint(node);
  }

  @Override
  public boolean isSourceOnlyNode(GenericGraphView view, SourceNode node) {
    return false;
  }

  @Override
  public List<LayoutIntent> layoutIntents(SourceDocument source, GenericGraphView view) {
    return List.of();
  }

  @Override
  public JsonNode nodeRenderProperties(SourceNode node) {
    return null;
  }

  @Override
  public JsonNode edgeRenderProperties(SourceRelationship relationship) {
    return null;
  }

  private static void validateArchimateSourceTypes(SourceDocument source)
      throws ArchimateTypeValidationException {
    var nodeTypes = new LinkedHashMap<String, String>();
    for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
      SourceNode node = source.nodes().get(nodeIndex);
      Archimate.validateElementType(node.type(), "$.nodes[" + nodeIndex + "].type");
      nodeTypes.put(node.id(), node.type());
    }
    for (int relationshipIndex = 0;
        relationshipIndex < source.relationships().size();
        relationshipIndex++) {
      SourceRelationship relationship = source.relationships().get(relationshipIndex);
      Archimate.validateRelationshipType(
          relationship.type(), "$.relationships[" + relationshipIndex + "].type");
      String sourceType = nodeTypes.get(relationship.source());
      String targetType = nodeTypes.get(relationship.target());
      if (sourceType == null || targetType == null) {
        continue;
      }
      Archimate.validateRelationshipEndpointTypes(
          relationship.type(),
          sourceType,
          targetType,
          "$.relationships[" + relationshipIndex + "]");
    }
  }

  private static void validateArchimateJunctionSemantics(SourceDocument source)
      throws ArchimateJunctionValidationException {
    var nodes = new ArrayList<JunctionValidationNode>();
    for (int index = 0; index < source.nodes().size(); index++) {
      SourceNode node = source.nodes().get(index);
      nodes.add(new JunctionValidationNode(node.id(), node.type(), "$.nodes[" + index + "]"));
    }
    var relationships =
        source.relationships().stream()
            .map(
                relationship ->
                    new JunctionValidationRelationship(
                        relationship.type(), relationship.source(), relationship.target()))
            .toList();
    Archimate.validateJunctionRelationshipSemantics(nodes, relationships);
  }
}

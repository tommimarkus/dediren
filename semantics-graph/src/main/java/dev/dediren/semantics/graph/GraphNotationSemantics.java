package dev.dediren.semantics.graph;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.ir.LayoutIntent;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The base/plain graph profile: neutral defaults for every {@link NotationSemantics} decision. It
 * imposes no notation legality, assigns no layout role, sizes every node at the base 160x80, keeps
 * all source nodes, contributes no layout constraints, and exposes no render selectors. These are
 * exactly the base-path outcomes the old single generic-graph projection produced for a source with
 * no (or the {@code generic-graph}) semantic profile.
 */
public final class GraphNotationSemantics implements NotationSemantics {

  @Override
  public void validate(SourceDocument source, GenericGraphPluginData pluginData) {
    // The plain graph profile imposes no notation legality rules.
  }

  // Preserves the old generic-graph base loop's profile-independent lifeline/interaction role
  // for byte-stability: GenericGraphProjection.layoutRole returned "lifeline"/"interaction" for
  // these two source types regardless of semantic profile. The "junction" role stays
  // archimate-owned (see ArchimateNotationSemantics); UML sources get lifeline/interaction via
  // UmlNotationSemantics.
  @Override
  public String layoutRole(String sourceType) {
    if ("Lifeline".equals(sourceType)) {
      return "lifeline";
    }
    if ("Interaction".equals(sourceType)) {
      return "interaction";
    }
    return null;
  }

  @Override
  public double widthHint(SourceNode node) {
    return 160.0;
  }

  @Override
  public double heightHint(SourceNode node) {
    return 80.0;
  }

  @Override
  public boolean isSourceOnlyNode(GenericGraphView view, SourceNode node) {
    return false;
  }

  @Override
  public List<LayoutConstraint> layoutConstraints(SourceDocument source, GenericGraphView view) {
    return List.of();
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
}

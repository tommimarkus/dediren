package dev.dediren.semantics.graph;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.ir.SceneGraph;
import dev.dediren.ir.SceneGroup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The shared, backend-neutral projection loop: it turns a selected {@link GenericGraphView} into a
 * pre-layout {@link SceneGraph} or a render-metadata input, delegating every notation-specific
 * decision to a {@link NotationSemantics}. The projection owns the whole {@code SceneGraph} (nodes,
 * edges, groups, constraints, preferences) directly; mapping to the {@code layout-request} wire
 * record happens at the caller's edge (Plan B P4), not here.
 */
final class SceneProjection {
  private SceneProjection() {}

  static RenderMetadata projectRenderMetadata(
      SourceDocument source, GenericGraphView selectedView, NotationSemantics notation)
      throws IOException {
    var nodes = new LinkedHashMap<String, RenderMetadataSelector>();
    for (String id : selectedView.nodes()) {
      SourceNode sourceNode =
          source.nodes().stream()
              .filter(node -> node.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IOException("view references missing node " + id));
      nodes.put(
          sourceNode.id(),
          new RenderMetadataSelector(
              sourceNode.type(), sourceNode.id(), notation.nodeRenderProperties(sourceNode)));
    }

    var edges = new LinkedHashMap<String, RenderMetadataSelector>();
    for (String id : selectedView.relationships()) {
      SourceRelationship relationship =
          source.relationships().stream()
              .filter(candidate -> candidate.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IOException("view references missing relationship " + id));
      edges.put(
          relationship.id(),
          new RenderMetadataSelector(
              relationship.type(), relationship.id(), notation.edgeRenderProperties(relationship)));
    }

    var groups = new LinkedHashMap<String, RenderMetadataSelector>();
    for (GenericGraphViewGroup group : selectedView.groups()) {
      if (group.role() != GenericGraphViewGroupRole.SEMANTIC_BOUNDARY
          || group.semanticSourceId() == null) {
        continue;
      }
      SourceNode sourceNode =
          source.nodes().stream()
              .filter(node -> node.id().equals(group.semanticSourceId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IOException(
                          "group "
                              + group.id()
                              + " references missing semantic source "
                              + group.semanticSourceId()));
      groups.put(group.id(), new RenderMetadataSelector(sourceNode.type(), sourceNode.id(), null));
    }

    return new RenderMetadata(
        ContractVersions.RENDER_METADATA_SCHEMA_VERSION,
        SemanticProfiles.wireName(SemanticProfiles.sourceSemanticProfile(pluginData(source))),
        nodes,
        edges,
        groups);
  }

  /**
   * Builds the complete pre-layout {@link SceneGraph} for the selected view: nodes, edges, groups,
   * constraints (the single injection point is {@link NotationSemantics#layoutIntents}), and
   * preferences. The projection owns the whole {@code SceneGraph} directly; callers map it to a
   * {@code layout-request} at their own edge via {@code LayoutRequestMapper.toRequest} (the CLI
   * standalone {@code project} command does this so the wire stays byte-identical).
   */
  static SceneGraph projectScene(
      SourceDocument source, GenericGraphView selectedView, NotationSemantics notation)
      throws IOException {
    var sceneNodes = new ArrayList<dev.dediren.ir.SceneNode>();
    var sourceNodeOrder = source.nodes();
    for (String id : selectedView.nodes()) {
      int sourceIndex = indexOfNode(sourceNodeOrder, id);
      SourceNode sourceNode = sourceNodeOrder.get(sourceIndex);
      if (notation.isSourceOnlyNode(selectedView, sourceNode)) {
        continue;
      }
      sceneNodes.add(
          new dev.dediren.ir.SceneNode(
              sourceNode.id(),
              sourceNode.label(),
              sourceNode.id(),
              notation.widthHint(sourceNode),
              notation.heightHint(sourceNode),
              notation.layoutRole(sourceNode.type()),
              sourceNode.partition(),
              sourceNode.layerConstraint(),
              dev.dediren.ir.SourcePointers.node(sourceIndex)));
    }

    var sceneEdges = new ArrayList<dev.dediren.ir.SceneEdge>();
    var sourceRelationshipOrder = source.relationships();
    for (String id : selectedView.relationships()) {
      int sourceIndex = indexOfRelationship(sourceRelationshipOrder, id);
      SourceRelationship relationship = sourceRelationshipOrder.get(sourceIndex);
      sceneEdges.add(
          new dev.dediren.ir.SceneEdge(
              relationship.id(),
              relationship.source(),
              relationship.target(),
              relationship.label(),
              relationship.id(),
              relationship.type(),
              relationship.priority(),
              dev.dediren.ir.SourcePointers.relationship(sourceIndex)));
    }

    var selectedNodeIds = new LinkedHashSet<>(selectedView.nodes());
    var selectedGroupIds =
        selectedView.groups().stream()
            .map(GenericGraphViewGroup::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var sourceNodeIds =
        source.nodes().stream().map(SourceNode::id).collect(java.util.stream.Collectors.toSet());
    var emittedSceneNodeIds =
        sceneNodes.stream()
            .map(dev.dediren.ir.SceneNode::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var sceneGroups = new ArrayList<SceneGroup>();
    for (GenericGraphViewGroup group : selectedView.groups()) {
      for (String member : group.members()) {
        if (!selectedNodeIds.contains(member) && !selectedGroupIds.contains(member)) {
          throw new IOException(
              "group " + group.id() + " references node or group outside view: " + member);
        }
      }
      GroupProvenance provenance;
      if (group.role() == GenericGraphViewGroupRole.LAYOUT_ONLY) {
        provenance = GroupProvenance.visualOnlyGroup();
      } else {
        String sourceId = group.semanticSourceId() == null ? group.id() : group.semanticSourceId();
        if (group.semanticSourceId() != null && !sourceNodeIds.contains(sourceId)) {
          throw new IOException(
              "group " + group.id() + " semantic_source_id references missing node: " + sourceId);
        }
        provenance = GroupProvenance.semanticBacked(sourceId);
      }
      var members =
          group.members().stream()
              .filter(
                  member ->
                      emittedSceneNodeIds.contains(member) || selectedGroupIds.contains(member))
              .toList();
      if (members.isEmpty()) {
        continue;
      }
      sceneGroups.add(new SceneGroup(group.id(), group.label(), members, provenance));
    }

    return new SceneGraph(
        selectedView.id(),
        sceneNodes,
        sceneEdges,
        sceneGroups,
        notation.layoutIntents(source, selectedView),
        selectedView.layoutPreferences());
  }

  private static GenericGraphPluginData pluginData(SourceDocument source) {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    if (pluginValue == null) {
      throw new UncheckedIOException(new IOException("missing plugins.generic-graph"));
    }
    return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
  }

  private static int indexOfNode(List<SourceNode> nodes, String id) throws IOException {
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).id().equals(id)) {
        return i;
      }
    }
    throw new IOException("view references missing node " + id);
  }

  private static int indexOfRelationship(List<SourceRelationship> relationships, String id)
      throws IOException {
    for (int i = 0; i < relationships.size(); i++) {
      if (relationships.get(i).id().equals(id)) {
        return i;
      }
    }
    throw new IOException("view references missing relationship " + id);
  }
}

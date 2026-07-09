package dev.dediren.semantics.graph;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The shared, backend-neutral projection loop: it turns a selected {@link GenericGraphView} into a
 * layout-request or render-metadata input, delegating every notation-specific decision to a {@link
 * NotationSemantics}. Relocated verbatim from the old single generic-graph projection; the only
 * change is that the stringly {@code semanticProfile} parameter became a {@code notation} hook, so
 * the base structure (node/edge/group iteration, {@code SceneGraph} construction, {@code
 * LayoutRequestMapper} mapping, group provenance, constraint injection) is unchanged.
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

  static LayoutRequest projectLayoutRequest(
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
              dev.dediren.ir.SourcePointers.node(sourceIndex),
              notation.widthHint(sourceNode),
              notation.heightHint(sourceNode),
              notation.layoutRole(sourceNode.type()),
              sourceNode.partition(),
              sourceNode.layerConstraint()));
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
              dev.dediren.ir.SourcePointers.relationship(sourceIndex),
              relationship.type(),
              relationship.priority()));
    }

    LayoutRequest mapped =
        dev.dediren.ir.LayoutRequestMapper.toRequest(
            new dev.dediren.ir.SceneGraph(
                selectedView.id(),
                sceneNodes,
                sceneEdges,
                java.util.List.of(),
                selectedView.layoutPreferences()));
    var nodes = mapped.nodes();
    var edges = mapped.edges();

    var selectedNodeIds = new LinkedHashSet<>(selectedView.nodes());
    var selectedGroupIds =
        selectedView.groups().stream()
            .map(GenericGraphViewGroup::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var sourceNodeIds =
        source.nodes().stream().map(SourceNode::id).collect(java.util.stream.Collectors.toSet());
    var emittedLayoutNodeIds =
        nodes.stream()
            .map(LayoutNode::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var groups = new ArrayList<LayoutGroup>();
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
                      emittedLayoutNodeIds.contains(member) || selectedGroupIds.contains(member))
              .toList();
      if (members.isEmpty()) {
        continue;
      }
      groups.add(new LayoutGroup(group.id(), group.label(), members, provenance));
    }

    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        selectedView.id(),
        nodes,
        edges,
        groups,
        notation.layoutConstraints(source, selectedView),
        selectedView.layoutPreferences());
  }

  private static GenericGraphPluginData pluginData(SourceDocument source) {
    return JsonSupport.objectMapper()
        .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
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

package dev.dediren.plugins.genericgraph;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutLabel;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class GenericGraphProjection {
    private GenericGraphProjection() {
    }

    static RenderMetadata projectRenderMetadata(
            SourceDocument source,
            GenericGraphView selectedView,
            String semanticProfile) throws IOException {
        var nodes = new LinkedHashMap<String, RenderMetadataSelector>();
        for (String id : selectedView.nodes()) {
            SourceNode sourceNode = source.nodes().stream()
                    .filter(node -> node.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing node " + id));
            nodes.put(sourceNode.id(), new RenderMetadataSelector(
                    sourceNode.type(),
                    sourceNode.id(),
                    semanticProfile.equals("uml") ? sourceNode.properties().get("uml") : null));
        }

        var edges = new LinkedHashMap<String, RenderMetadataSelector>();
        for (String id : selectedView.relationships()) {
            SourceRelationship relationship = source.relationships().stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing relationship " + id));
            edges.put(relationship.id(), new RenderMetadataSelector(
                    relationship.type(),
                    relationship.id(),
                    null));
        }

        var groups = new LinkedHashMap<String, RenderMetadataSelector>();
        for (GenericGraphViewGroup group : selectedView.groups()) {
            if (group.role() != GenericGraphViewGroupRole.SEMANTIC_BOUNDARY || group.semanticSourceId() == null) {
                continue;
            }
            SourceNode sourceNode = source.nodes().stream()
                    .filter(node -> node.id().equals(group.semanticSourceId()))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "group " + group.id() + " references missing semantic source " + group.semanticSourceId()));
            groups.put(group.id(), new RenderMetadataSelector(sourceNode.type(), sourceNode.id(), null));
        }

        return new RenderMetadata(
                ContractVersions.RENDER_METADATA_SCHEMA_VERSION,
                semanticProfile,
                nodes,
                edges,
                groups);
    }

    static LayoutRequest projectLayoutRequest(
            SourceDocument source,
            GenericGraphView selectedView,
            String semanticProfile) throws IOException {
        var nodes = new ArrayList<LayoutNode>();
        for (String id : selectedView.nodes()) {
            SourceNode sourceNode = source.nodes().stream()
                    .filter(node -> node.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing node " + id));
            nodes.add(new LayoutNode(
                    sourceNode.id(),
                    sourceNode.label(),
                    sourceNode.id(),
                    GenericGraphLayoutSizing.widthHint(semanticProfile, sourceNode),
                    GenericGraphLayoutSizing.heightHint(semanticProfile, sourceNode)));
        }

        var edges = new ArrayList<LayoutEdge>();
        for (String id : selectedView.relationships()) {
            SourceRelationship relationship = source.relationships().stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing relationship " + id));
            edges.add(new LayoutEdge(
                    relationship.id(),
                    relationship.source(),
                    relationship.target(),
                    relationship.label(),
                    relationship.id(),
                    relationship.type()));
        }

        var sourceNodeIds = source.nodes().stream().map(SourceNode::id).collect(java.util.stream.Collectors.toSet());
        var groups = new ArrayList<LayoutGroup>();
        for (GenericGraphViewGroup group : selectedView.groups()) {
            for (String member : group.members()) {
                if (!selectedView.nodes().contains(member)) {
                    throw new IOException("group " + group.id() + " references node outside view: " + member);
                }
            }
            GroupProvenance provenance;
            if (group.role() == GenericGraphViewGroupRole.LAYOUT_ONLY) {
                provenance = GroupProvenance.visualOnlyGroup();
            } else {
                String sourceId = group.semanticSourceId() == null ? group.id() : group.semanticSourceId();
                if (group.semanticSourceId() != null && !sourceNodeIds.contains(sourceId)) {
                    throw new IOException("group " + group.id()
                            + " semantic_source_id references missing node: " + sourceId);
                }
                provenance = GroupProvenance.semanticBacked(sourceId);
            }
            groups.add(new LayoutGroup(group.id(), group.label(), group.members(), provenance));
        }

        var labels = nodes.stream()
                .map(node -> new LayoutLabel(node.id(), node.label()))
                .toList();
        return new LayoutRequest(
                ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
                selectedView.id(),
                nodes,
                edges,
                groups,
                labels,
                List.of(),
                selectedView.layoutPreferences());
    }

    static String sourceSemanticProfile(GenericGraphPluginData pluginData) {
        return GenericGraphSemanticProfiles.sourceSemanticProfile(pluginData);
    }
}

package dev.dediren.ir;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import java.util.List;

/** Bidirectionally maps a {@link SceneGraph} to the public {@code layout-request} record. */
public final class LayoutRequestMapper {
  private LayoutRequestMapper() {}

  public static LayoutRequest toRequest(SceneGraph graph) {
    List<LayoutNode> nodes =
        graph.nodes().stream()
            .map(
                n ->
                    new LayoutNode(
                        n.id(),
                        n.label(),
                        n.sourceId(),
                        n.widthHint(),
                        n.heightHint(),
                        n.role(),
                        n.partition(),
                        n.layerConstraint(),
                        pointerValue(n.origin())))
            .toList();
    List<LayoutEdge> edges =
        graph.edges().stream()
            .map(
                e ->
                    new LayoutEdge(
                        e.id(),
                        e.source(),
                        e.target(),
                        e.label(),
                        e.sourceId(),
                        e.relationshipType(),
                        e.priority(),
                        pointerValue(e.origin())))
            .toList();
    List<LayoutGroup> groups =
        graph.groups().stream()
            .map(g -> new LayoutGroup(g.id(), g.label(), g.members(), g.provenance()))
            .toList();
    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        graph.viewId(),
        nodes,
        edges,
        groups,
        LayoutIntentCodec.encode(graph.viewId(), graph.constraints()),
        graph.preferences());
  }

  public static SceneGraph toSceneGraph(LayoutRequest request) {
    List<SceneNode> nodes =
        request.nodes().stream()
            .map(
                n ->
                    new SceneNode(
                        n.id(),
                        n.label(),
                        n.sourceId(),
                        n.widthHint(),
                        n.heightHint(),
                        n.role(),
                        n.partition(),
                        n.layerConstraint(),
                        originOf(n.sourcePointer())))
            .toList();
    List<SceneEdge> edges =
        request.edges().stream()
            .map(
                e ->
                    new SceneEdge(
                        e.id(),
                        e.source(),
                        e.target(),
                        e.label(),
                        e.sourceId(),
                        e.relationshipType(),
                        e.priority(),
                        originOf(e.sourcePointer())))
            .toList();
    List<SceneGroup> groups =
        request.groups().stream()
            .map(g -> new SceneGroup(g.id(), g.label(), g.members(), g.provenance()))
            .toList();
    return new SceneGraph(
        request.viewId(),
        nodes,
        edges,
        groups,
        LayoutIntentCodec.decode(request.constraints()),
        request.layoutPreferences());
  }

  private static String pointerValue(SourcePointer pointer) {
    return pointer == null ? null : pointer.value();
  }

  private static SourcePointer originOf(String sourcePointer) {
    return sourcePointer == null ? null : new SourcePointer(sourcePointer);
  }
}

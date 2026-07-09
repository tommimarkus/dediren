package dev.dediren.ir;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import java.util.List;

/** Maps between the public {@code layout-result} record and the {@link LaidOutScene} IR. */
public final class LaidOutSceneMapper {
  private LaidOutSceneMapper() {}

  public static LaidOutScene toScene(LayoutResult result) {
    List<PlacedNode> nodes =
        result.nodes().stream()
            .map(
                n ->
                    new PlacedNode(
                        n.id(),
                        n.sourceId(),
                        n.projectionId(),
                        n.x(),
                        n.y(),
                        n.width(),
                        n.height(),
                        n.label(),
                        n.role(),
                        originOf(n.sourcePointer())))
            .toList();
    List<RoutedEdge> edges =
        result.edges().stream()
            .map(
                e ->
                    new RoutedEdge(
                        e.id(),
                        e.source(),
                        e.target(),
                        e.sourceId(),
                        e.projectionId(),
                        e.routingHints(),
                        e.points(),
                        e.label(),
                        originOf(e.sourcePointer())))
            .toList();
    List<PlacedGroup> groups =
        result.groups().stream()
            .map(
                g ->
                    new PlacedGroup(
                        g.id(),
                        g.sourceId(),
                        g.projectionId(),
                        g.provenance(),
                        g.x(),
                        g.y(),
                        g.width(),
                        g.height(),
                        g.members(),
                        g.label()))
            .toList();
    return new LaidOutScene(result.viewId(), nodes, edges, groups, result.warnings());
  }

  public static LayoutResult toResult(LaidOutScene scene) {
    List<LaidOutNode> nodes =
        scene.nodes().stream()
            .map(
                n ->
                    new LaidOutNode(
                        n.id(),
                        n.sourceId(),
                        n.projectionId(),
                        n.x(),
                        n.y(),
                        n.width(),
                        n.height(),
                        n.label(),
                        n.role(),
                        pointerValue(n.origin())))
            .toList();
    List<LaidOutEdge> edges =
        scene.edges().stream()
            .map(
                e ->
                    new LaidOutEdge(
                        e.id(),
                        e.source(),
                        e.target(),
                        e.sourceId(),
                        e.projectionId(),
                        e.routingHints(),
                        e.points(),
                        e.label(),
                        pointerValue(e.origin())))
            .toList();
    List<LaidOutGroup> groups =
        scene.groups().stream()
            .map(
                g ->
                    new LaidOutGroup(
                        g.id(),
                        g.sourceId(),
                        g.projectionId(),
                        g.provenance(),
                        g.x(),
                        g.y(),
                        g.width(),
                        g.height(),
                        g.members(),
                        g.label()))
            .toList();
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        scene.viewId(),
        nodes,
        edges,
        groups,
        scene.warnings());
  }

  private static SourcePointer originOf(String sourcePointer) {
    return sourcePointer == null ? null : new SourcePointer(sourcePointer);
  }

  private static String pointerValue(SourcePointer origin) {
    return origin == null ? null : origin.value();
  }
}

package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutPreferences;
import java.util.List;

/** The pre-layout typed scene graph produced by projection and mapped to the layout-request. */
public record SceneGraph(
    String viewId,
    List<SceneNode> nodes,
    List<SceneEdge> edges,
    List<SceneGroup> groups,
    List<LayoutConstraint> constraints,
    LayoutPreferences preferences) {
  public SceneGraph {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
    constraints = listOrEmpty(constraints);
  }
}

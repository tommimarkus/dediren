package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.EdgeRoute;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutMode;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.ir.LayoutIntentCodec;
import dev.dediren.ir.RouteGeometry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.KVector;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.options.SizeConstraint;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkConnectableShape;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ElkLayoutEngine {
  private static final String JUNCTION_ROLE = "junction";

  // debug/trace only, by architecture rule: a layout diagnostic an agent must act on belongs in the
  // envelope's diagnostics[], never on stderr. See ArchitectureRulesTest.
  private static final Logger LOG = LoggerFactory.getLogger(ElkLayoutEngine.class);

  LayoutResult layout(LayoutRequest request) {
    LayoutRequestValidator.validate(request);
    if (layoutMode(request.layoutPreferences()) == LayoutMode.PACKED) {
      return layoutPacked(request);
    }
    if (!list(request.groups()).isEmpty()) {
      if (bandableGroups(request)) {
        return layoutFlatBanded(request);
      }
      return layoutGrouped(request);
    }

    return layoutFlat(request, PortPlan.Ordering.UNCONSTRAINED);
  }

  /**
   * A view whose groups are all {@code visualOnly} and none nested inside another is drawn as
   * partition-aligned bands (see {@link #layoutFlatBanded}) rather than ELK compound nodes: the
   * boxes are only a visual grouping, so nesting the members would force every cross-group edge to
   * route through a box boundary and pile up in the gutter. A semantic-boundary group (a real
   * containment) still takes the hierarchy path, where that routing is correct.
   */
  private static boolean bandableGroups(LayoutRequest request) {
    List<LayoutGroup> groups = list(request.groups());
    if (groups.isEmpty() || !ownerByGroup(request).isEmpty()) {
      return false;
    }
    for (LayoutGroup group : groups) {
      GroupProvenance provenance = group.provenance();
      if (provenance == null || !provenance.visualOnly()) {
        return false;
      }
    }
    return true;
  }

  private static LayoutMode layoutMode(LayoutPreferences preferences) {
    return preferences == null || preferences.mode() == null ? LayoutMode.AUTO : preferences.mode();
  }

  /**
   * @param ordering what the calling lane has already fixed about the layer order. {@code
   *     layoutFlatBanded} reaches this method with {@link PortPlan.Ordering#PARTITIONED} after
   *     deriving its bands; a plain flat request arrives {@link PortPlan.Ordering#UNCONSTRAINED}.
   *     Neither contributes reversals today — see {@link PortPlan.Ordering} for the measurement
   *     behind that — but the lane's ordering is what the plan is built from, not the lane's name.
   */
  private static LayoutResult layoutFlat(LayoutRequest request, PortPlan.Ordering ordering) {
    LayoutPreferences preferences = request.layoutPreferences();
    Map<String, String> nodePointers = nodeSourcePointers(request);
    Map<String, String> edgePointers = edgeSourcePointers(request);
    List<LayoutNode> requestNodeList = list(request.nodes());
    List<LayoutIntent> intents = LayoutIntentCodec.decode(request.constraints());
    LayoutIntentNormalizer sequenceConstraints =
        LayoutIntentNormalizer.from(intents, requestNodeList, nodePointers, edgePointers);
    boolean sequenceMode = sequenceConstraints.active();
    Direction layoutDirection =
        sequenceMode ? Direction.RIGHT : ElkLayeredOptions.preferredDirection(preferences);
    ElkNode root = ElkGraphUtil.createGraph();
    if (ordering == PortPlan.Ordering.PARTITIONED) {
      ElkLayeredOptions.configureBandedRoot(root, layoutDirection, preferences);
    } else {
      ElkLayeredOptions.configureRoot(root, layoutDirection, preferences);
    }

    Map<String, LayoutNode> requestNodes = requestNodesById(request);
    List<LayoutEdge> originalRequestEdges = list(request.edges());
    Map<LayoutEdge, Integer> originalEdgeIndexes = originalEdgeIndexes(originalRequestEdges);
    List<LayoutEdge> requestEdges = sequenceConstraints.orderedEdges(originalRequestEdges);
    PortPlan portPlan =
        sequenceMode
            ? PortPlan.sequence(requestEdges, requestNodes, sequenceConstraints)
            : PortPlan.flat(ordering, requestEdges, requestNodes, preferences, layoutDirection);
    Map<String, ElkNode> elkNodes = new HashMap<>();
    for (LayoutNode node : sequenceConstraints.orderedNodes(requestNodeList)) {
      ElkNode elkNode = ElkGraphUtil.createNode(root);
      elkNode.setIdentifier(node.id());
      setGeneratedDimensions(elkNode, node);
      ElkGraphUtil.createLabel(elkNode).setText(node.label());
      ElkLayeredOptions.applyNodeHints(elkNode, node);
      elkNodes.put(node.id(), elkNode);
    }
    ElkLayeredOptions.activatePartitioning(root, list(request.nodes()));

    Map<String, ElkEdge> elkEdges = new HashMap<>();
    List<Diagnostic> warnings = new ArrayList<>();
    for (int index = 0; index < requestEdges.size(); index++) {
      LayoutEdge edge = requestEdges.get(index);
      ElkNode source = elkNodes.get(edge.source());
      ElkNode target = elkNodes.get(edge.target());
      if (source == null || target == null) {
        warnings.add(
            new Diagnostic(
                DiagnosticCode.ELK_DANGLING_EDGE.code(),
                DiagnosticSeverity.WARNING,
                "edge " + edge.id() + " references a missing endpoint",
                "$.edges[" + originalEdgeIndexes.getOrDefault(edge, index) + "]"));
        continue;
      }
      if (sequenceMode && edge.source().equals(edge.target())) {
        // A sequence self-message is drawn as a stem-anchored hook synthesized by the normalizer
        // (LayoutIntentNormalizer#normalizedMessagePoints), so ELK never routes it. Withholding the
        // self-loop from the ELK graph also stops ELK 0.11.0's self-loop margin placement from
        // running: that machinery breaks a mirror-image tie by iterating a collection ordered by
        // object identity hash, which made the whole diagram's cross-axis origin depend on JVM
        // allocation history (a nondeterministic y that flapped the layout-fixture freshness gate).
        // The message is re-materialized as a placeholder LaidOutEdge below and redrawn by
        // normalize.
        continue;
      }
      ElkEdge elkEdge =
          createRoutedEdge(
              source,
              target,
              edge,
              portPlan.sourceSide(edge.id()),
              portPlan.targetSide(edge.id()),
              portPlan.fixesSourceSide(edge.id()),
              portPlan.fixesTargetSide(edge.id()),
              portPlan.mergesSourceEndpoint(edge.id()),
              portPlan.mergesTargetEndpoint(edge.id()));
      elkEdges.put(edge.id(), elkEdge);
      ElkLayeredOptions.applyEdgeHints(elkEdge, edge);
    }

    runElkLayout(root, request);

    List<LaidOutNode> nodes = new ArrayList<>();
    for (LayoutNode node : list(request.nodes())) {
      ElkNode elkNode = elkNodes.get(node.id());
      if (elkNode != null) {
        nodes.add(
            new LaidOutNode(
                node.id(),
                node.sourceId(),
                node.id(),
                elkNode.getX(),
                elkNode.getY(),
                elkNode.getWidth(),
                elkNode.getHeight(),
                node.label(),
                node.role(),
                nodePointers.get(node.id())));
      }
    }

    List<LaidOutEdge> edges = new ArrayList<>();
    for (LayoutEdge edge : list(request.edges())) {
      ElkEdge elkEdge = elkEdges.get(edge.id());
      if (elkEdge != null) {
        edges.add(
            new LaidOutEdge(
                edge.id(),
                edge.source(),
                edge.target(),
                edge.sourceId(),
                edge.id(),
                portPlan.routingHints(edge.id()),
                route(elkEdge),
                edge.label(),
                edgePointers.get(edge.id())));
      } else if (sequenceMode
          && edge.source().equals(edge.target())
          && elkNodes.containsKey(edge.source())) {
        // The self-loop was withheld from ELK above; re-materialize it with a placeholder route the
        // sequence normalizer overwrites with the stem-anchored hook. Two points on the lifeline
        // head keep the message in the row lattice (LayoutIntentNormalizer drops sub-two-point
        // edges); the placeholder coordinates themselves are discarded by normalize().
        edges.add(
            new LaidOutEdge(
                edge.id(),
                edge.source(),
                edge.target(),
                edge.sourceId(),
                edge.id(),
                portPlan.routingHints(edge.id()),
                new PolylineRoute(selfLoopPlaceholderRoute(elkNodes.get(edge.source()))),
                edge.label(),
                edgePointers.get(edge.id())));
      }
    }

    // layout() routes any request with groups to layoutGrouped, so the flat path is only ever
    // reached with zero groups.
    List<LaidOutGroup> groups = List.of();

    LayoutResult result =
        new LayoutResult(
            ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
            request.viewId(),
            nodes,
            edges,
            groups,
            warnings);
    return sequenceConstraints.normalize(result);
  }

  /**
   * Lays out a view whose groups are visual-only tier bands. The members are laid out flat (no ELK
   * compound nodes) with an ELK partition per group so each band occupies its own ordered layer;
   * edges route node-to-node and never through a box boundary. Each group's labelled box is then a
   * bounding box computed over its laid-out members. This is the readable counterpart to {@link
   * #layoutGrouped}, which nests members and is correct only for semantic containment.
   */
  private static LayoutResult layoutFlatBanded(LayoutRequest request) {
    List<LayoutGroup> groups = list(request.groups());
    Map<String, Integer> partitionByGroup = new HashMap<>();
    for (int index = 0; index < groups.size(); index++) {
      partitionByGroup.put(groups.get(index).id(), index);
    }
    Map<String, String> ownerByNode = ownerByNode(request, requestNodesById(request));

    List<LayoutNode> bandedNodes = new ArrayList<>();
    for (LayoutNode node : list(request.nodes())) {
      Integer partition = node.partition();
      if (partition == null) {
        String owner = ownerByNode.get(node.id());
        partition = owner == null ? null : partitionByGroup.get(owner);
      }
      bandedNodes.add(
          new LayoutNode(
              node.id(),
              node.label(),
              node.sourceId(),
              node.widthHint(),
              node.heightHint(),
              node.role(),
              partition,
              node.layerConstraint(),
              node.sourcePointer()));
    }

    // Reuse the flat path verbatim (routing, endpoint merging, ports). Stripping the groups makes
    // it run the plain-flat layout; the derived partitions align each band without a compound node.
    // The one thing the flat path is told is which ordering it is serving: the partitions fix each
    // band's layer, so the lane declares PARTITIONED rather than letting "flat" stand for both.
    LayoutRequest flatRequest =
        new LayoutRequest(
            request.layoutRequestSchemaVersion(),
            request.viewId(),
            bandedNodes,
            request.edges(),
            List.of(),
            request.constraints(),
            request.layoutPreferences());
    LayoutResult flat = layoutFlat(flatRequest, PortPlan.Ordering.PARTITIONED);

    List<Diagnostic> warnings = new ArrayList<>(flat.warnings());
    List<LaidOutGroup> bands =
        bandBounds(groups, flat.nodes(), request.layoutPreferences(), warnings);

    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        request.viewId(),
        flat.nodes(),
        flat.edges(),
        bands,
        warnings);
  }

  /** Bounding box (plus density-aware padding) around each visual-only group's laid-out members. */
  private static List<LaidOutGroup> bandBounds(
      List<LayoutGroup> groups,
      List<LaidOutNode> laidOutNodes,
      LayoutPreferences preferences,
      List<Diagnostic> warnings) {
    Map<String, LaidOutNode> nodeById = new HashMap<>();
    for (LaidOutNode node : laidOutNodes) {
      nodeById.put(node.id(), node);
    }
    double padding = ElkLayeredOptions.groupBandPadding(preferences);
    List<LaidOutGroup> bands = new ArrayList<>();
    for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
      LayoutGroup group = groups.get(groupIndex);
      List<String> memberIds = new ArrayList<>();
      double minX = Double.POSITIVE_INFINITY;
      double minY = Double.POSITIVE_INFINITY;
      double maxX = Double.NEGATIVE_INFINITY;
      double maxY = Double.NEGATIVE_INFINITY;
      for (String member : list(group.members())) {
        LaidOutNode node = nodeById.get(member);
        if (node == null) {
          continue;
        }
        memberIds.add(member);
        minX = Math.min(minX, node.x());
        minY = Math.min(minY, node.y());
        maxX = Math.max(maxX, node.x() + node.width());
        maxY = Math.max(maxY, node.y() + node.height());
      }
      if (memberIds.isEmpty()) {
        warnings.add(
            new Diagnostic(
                DiagnosticCode.ELK_EMPTY_GROUP.code(),
                DiagnosticSeverity.WARNING,
                "group " + group.id() + " has no laid out members",
                "$.groups[" + groupIndex + "]"));
        continue;
      }
      LaidOutGroup band =
          new LaidOutGroup(
              group.id(),
              semanticBackedSourceId(group.provenance(), group.id()),
              group.id(),
              group.provenance(),
              minX - padding,
              minY - padding,
              (maxX - minX) + 2 * padding,
              (maxY - minY) + 2 * padding,
              memberIds,
              group.label());
      for (LaidOutGroup other : bands) {
        if (bandsOverlap(band, other)) {
          warnings.add(
              new Diagnostic(
                  DiagnosticCode.ELK_GROUP_BANDS_OVERLAP.code(),
                  DiagnosticSeverity.WARNING,
                  "group band " + group.id() + " overlaps band " + other.id(),
                  "$.groups[" + groupIndex + "]"));
          break;
        }
      }
      bands.add(band);
    }
    return bands;
  }

  private static boolean bandsOverlap(LaidOutGroup a, LaidOutGroup b) {
    return a.x() < b.x() + b.width()
        && b.x() < a.x() + a.width()
        && a.y() < b.y() + b.height()
        && b.y() < a.y() + a.height();
  }

  private static LayoutResult layoutPacked(LayoutRequest request) {
    if (!list(request.edges()).isEmpty()) {
      throw new IllegalArgumentException(
          "packed layout mode requires an edge-less request at $.edges");
    }

    LayoutPreferences preferences = request.layoutPreferences();
    Map<String, String> nodePointers = nodeSourcePointers(request);
    List<Diagnostic> warnings = new ArrayList<>();
    warnPackedIgnoresLayeredOnlyOptions(request, warnings);
    ElkNode root = ElkGraphUtil.createGraph();
    ElkPackedOptions.configureRoot(root, preferences);
    Map<String, LayoutNode> requestNodes = requestNodesById(request);
    Map<String, String> ownerByNode = ownerByNode(request, requestNodes);
    Map<String, LayoutGroup> requestGroupsById = requestGroupsById(request);
    Map<String, String> ownerByGroup = ownerByGroup(request);
    Map<String, ElkNode> elkGroups = new HashMap<>();
    for (LayoutGroup group : list(request.groups())) {
      createElkGroup(
          group.id(),
          root,
          Direction.RIGHT,
          requestGroupsById,
          requestNodes,
          ownerByNode,
          ownerByGroup,
          elkGroups,
          (elkGroup, ignored) -> ElkPackedOptions.configureRoot(elkGroup, preferences),
          new HashSet<>());
    }

    Map<String, ElkNode> elkNodes = new HashMap<>();
    for (LayoutNode node : list(request.nodes())) {
      ElkNode parent =
          ownerByNode.containsKey(node.id()) ? elkGroups.get(ownerByNode.get(node.id())) : root;
      if (parent == null) {
        continue;
      }
      ElkNode elkNode = ElkGraphUtil.createNode(parent);
      elkNode.setIdentifier(node.id());
      setGeneratedDimensions(elkNode, node);
      ElkGraphUtil.createLabel(elkNode).setText(node.label());
      elkNodes.put(node.id(), elkNode);
    }

    runElkLayout(root, request);

    List<LaidOutNode> nodes = new ArrayList<>();
    for (LayoutNode node : list(request.nodes())) {
      ElkNode elkNode = elkNodes.get(node.id());
      if (elkNode != null) {
        nodes.add(
            new LaidOutNode(
                node.id(),
                node.sourceId(),
                node.id(),
                absoluteX(elkNode),
                absoluteY(elkNode),
                elkNode.getWidth(),
                elkNode.getHeight(),
                node.label(),
                node.role(),
                nodePointers.get(node.id())));
      }
    }

    List<LaidOutGroup> groups = groupedBounds(request, elkGroups, elkNodes, warnings);
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        request.viewId(),
        nodes,
        List.of(),
        groups,
        warnings);
  }

  // Mode dispatch selects the packed (rectpacking) lane before any algorithm gating: the public
  // schema pins algorithm to "layered", so validateAlgorithmCompatibility never fires, and ELK
  // Rectangle Packing reads neither the layered phase preferences nor the per-node
  // partition/layer_constraint hints. Name the ignored content in the envelope instead of dropping
  // it silently — a warning, not a reject, so released packed requests keep succeeding.
  private static void warnPackedIgnoresLayeredOnlyOptions(
      LayoutRequest request, List<Diagnostic> warnings) {
    List<String> ignored = new ArrayList<>();
    LayoutPreferences preferences = request.layoutPreferences();
    addIgnored(ignored, preferences.cycleBreaking() != null, "$.layout_preferences.cycle_breaking");
    addIgnored(ignored, preferences.layering() != null, "$.layout_preferences.layering");
    addIgnored(ignored, preferences.crossing() != null, "$.layout_preferences.crossing");
    addIgnored(ignored, preferences.placement() != null, "$.layout_preferences.placement");
    addIgnored(ignored, preferences.compaction() != null, "$.layout_preferences.compaction");
    addIgnored(
        ignored, preferences.highDegreeNodes() != null, "$.layout_preferences.high_degree_nodes");
    addIgnored(ignored, preferences.thoroughness() != null, "$.layout_preferences.thoroughness");
    List<LayoutNode> nodes = list(request.nodes());
    for (int index = 0; index < nodes.size(); index++) {
      LayoutNode node = nodes.get(index);
      addIgnored(ignored, node.partition() != null, "$.nodes[" + index + "].partition");
      addIgnored(
          ignored, node.layerConstraint() != null, "$.nodes[" + index + "].layer_constraint");
    }
    if (ignored.isEmpty()) {
      return;
    }
    warnings.add(
        new Diagnostic(
            DiagnosticCode.ELK_PACKED_OPTION_IGNORED.code(),
            DiagnosticSeverity.WARNING,
            "packed layout mode ignores layered-only options: " + String.join(", ", ignored),
            "$.layout_preferences.mode"));
  }

  private static void addIgnored(List<String> ignored, boolean present, String path) {
    if (present) {
      ignored.add(path);
    }
  }

  private static LayoutResult layoutGrouped(LayoutRequest request) {
    LayoutPreferences preferences = request.layoutPreferences();
    Map<String, String> nodePointers = nodeSourcePointers(request);
    Map<String, String> edgePointers = edgeSourcePointers(request);
    Direction rootDirection = ElkLayeredOptions.preferredDirection(preferences);
    List<Diagnostic> warnings = new ArrayList<>();
    Map<String, LayoutNode> requestNodes = requestNodesById(request);
    Map<String, String> ownerByNode = ownerByNode(request, requestNodes);
    Map<String, LayoutGroup> requestGroupsById = requestGroupsById(request);
    Map<String, String> ownerByGroup = ownerByGroup(request);
    List<LayoutEdge> requestEdges = list(request.edges());
    ElkNode root = ElkGraphUtil.createGraph();
    ElkLayeredOptions.configureGroupedRoot(root, rootDirection, preferences);

    Map<String, ElkNode> elkGroups = new HashMap<>();
    List<LayoutGroup> requestGroups = list(request.groups());
    for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
      LayoutGroup group = requestGroups.get(groupIndex);
      createElkGroup(
          group.id(),
          root,
          rootDirection,
          requestGroupsById,
          requestNodes,
          ownerByNode,
          ownerByGroup,
          elkGroups,
          (elkGroup, groupDirection) ->
              ElkLayeredOptions.configureGroup(elkGroup, groupDirection, preferences),
          new HashSet<>());
    }

    PortPlan portPlan =
        PortPlan.grouped(requestEdges, requestNodes, ownerByNode, rootDirection, preferences);
    Map<String, ElkNode> elkNodes = new HashMap<>();
    for (LayoutNode node : list(request.nodes())) {
      ElkNode parent =
          ownerByNode.containsKey(node.id()) ? elkGroups.get(ownerByNode.get(node.id())) : root;
      if (parent == null) {
        continue;
      }
      ElkNode elkNode = ElkGraphUtil.createNode(parent);
      elkNode.setIdentifier(node.id());
      setGeneratedDimensions(elkNode, node);
      ElkGraphUtil.createLabel(elkNode).setText(node.label());
      ElkLayeredOptions.applyNodeHints(elkNode, node);
      elkNodes.put(node.id(), elkNode);
    }
    ElkLayeredOptions.activatePartitioning(root, list(request.nodes()));

    Map<String, ElkEdge> elkEdges = new HashMap<>();
    for (int index = 0; index < requestEdges.size(); index++) {
      LayoutEdge edge = requestEdges.get(index);
      ElkNode source = elkNodes.get(edge.source());
      ElkNode target = elkNodes.get(edge.target());
      if (source == null || target == null) {
        warnings.add(
            new Diagnostic(
                DiagnosticCode.ELK_DANGLING_EDGE.code(),
                DiagnosticSeverity.WARNING,
                "edge " + edge.id() + " references a missing endpoint",
                "$.edges[" + index + "]"));
        continue;
      }
      ElkEdge elkEdge =
          createRoutedEdge(
              source,
              target,
              edge,
              portPlan.sourceSide(edge.id()),
              portPlan.targetSide(edge.id()),
              false,
              false,
              portPlan.mergesSourceEndpoint(edge.id()),
              portPlan.mergesTargetEndpoint(edge.id()));
      ElkGraphUtil.updateContainment(elkEdge);
      elkEdges.put(edge.id(), elkEdge);
      ElkLayeredOptions.applyEdgeHints(elkEdge, edge);
    }

    runElkLayout(root, request);

    List<LaidOutNode> nodes = new ArrayList<>();
    for (LayoutNode node : list(request.nodes())) {
      ElkNode elkNode = elkNodes.get(node.id());
      if (elkNode != null) {
        nodes.add(
            new LaidOutNode(
                node.id(),
                node.sourceId(),
                node.id(),
                absoluteX(elkNode),
                absoluteY(elkNode),
                elkNode.getWidth(),
                elkNode.getHeight(),
                node.label(),
                node.role(),
                nodePointers.get(node.id())));
      }
    }

    List<LaidOutEdge> nativeEdges = new ArrayList<>();
    List<LaidOutGroup> groups = groupedBounds(request, elkGroups, elkNodes, warnings);
    for (LayoutEdge edge : list(request.edges())) {
      ElkEdge elkEdge = elkEdges.get(edge.id());
      if (elkEdge != null) {
        nativeEdges.add(
            new LaidOutEdge(
                edge.id(),
                edge.source(),
                edge.target(),
                edge.sourceId(),
                edge.id(),
                portPlan.routingHints(edge.id()),
                route(elkEdge),
                edge.label(),
                edgePointers.get(edge.id())));
      }
    }
    List<LaidOutEdge> edges =
        normalizeGroupedRoutes(nativeEdges, nodes, groups, portPlan, preferences);
    // ELK Layered owns placement and routing. Compound edges arrive as joined hierarchy sections;
    // the normalizer only collapses redundant alternating joins when the shorter route remains
    // orthogonal and clear of every unrelated node.

    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        request.viewId(),
        nodes,
        edges,
        groups,
        warnings);
  }

  private static List<LaidOutEdge> normalizeGroupedRoutes(
      List<LaidOutEdge> nativeEdges,
      List<LaidOutNode> nodes,
      List<LaidOutGroup> groups,
      PortPlan portPlan,
      LayoutPreferences preferences) {
    if (!ElkLayeredOptions.orthogonalRouting(preferences)) {
      return nativeEdges;
    }
    List<LaidOutEdge> normalized = new ArrayList<>(nativeEdges.size());
    for (LaidOutEdge edge : nativeEdges) {
      EdgeRoute route = edge.route();
      if (!edge.source().equals(edge.target()) && route instanceof PolylineRoute polyline) {
        List<List<Point>> siblingRoutes = new ArrayList<>(nativeEdges.size() - 1);
        for (LaidOutEdge sibling : nativeEdges) {
          if (!sibling.id().equals(edge.id())) {
            siblingRoutes.add(RouteGeometry.flatten(sibling.route()));
          }
        }
        boolean sharedJunction =
            portPlan.mergesSourceEndpoint(edge.id()) || portPlan.mergesTargetEndpoint(edge.id());
        route =
            new PolylineRoute(
                OrthogonalRouteNormalizer.collapseStairSteps(
                    polyline.points(),
                    nodes,
                    groups,
                    edge.source(),
                    edge.target(),
                    sharedJunction,
                    siblingRoutes));
      }
      normalized.add(
          new LaidOutEdge(
              edge.id(),
              edge.source(),
              edge.target(),
              edge.sourceId(),
              edge.projectionId(),
              edge.routingHints(),
              route,
              edge.label(),
              edge.sourcePointer()));
    }
    return List.copyOf(normalized);
  }

  private static Map<LayoutEdge, Integer> originalEdgeIndexes(List<LayoutEdge> edges) {
    Map<LayoutEdge, Integer> indexes = new IdentityHashMap<>();
    for (int index = 0; index < edges.size(); index++) {
      indexes.put(edges.get(index), index);
    }
    return indexes;
  }

  private static ElkEdge createRoutedEdge(
      ElkNode source,
      ElkNode target,
      LayoutEdge edge,
      PortSide sourceSide,
      PortSide targetSide,
      boolean fixSourceSide,
      boolean fixTargetSide,
      boolean mergeSourceEndpoint,
      boolean mergeTargetEndpoint) {
    String relationshipType = PortPlan.relationshipType(edge);
    ElkConnectableShape sourceShape =
        mergeSourceEndpoint
            ? sharedMergePort(source, sourceSide, fixSourceSide, true, relationshipType)
            : createEdgePort(source, edge.id() + "-source", sourceSide, fixSourceSide);
    ElkConnectableShape targetShape =
        mergeTargetEndpoint
            ? sharedMergePort(target, targetSide, fixTargetSide, false, relationshipType)
            : createEdgePort(target, edge.id() + "-target", targetSide, fixTargetSide);
    ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(sourceShape, targetShape);
    elkEdge.setIdentifier(edge.id());
    var label = ElkGraphUtil.createLabel(elkEdge);
    label.setText(edge.label());
    if (edge.label() != null && !edge.label().isBlank()) {
      // Reserve the canonical 14px base / 15.4px edge text and background padding before
      // native routing. Render policies may enlarge text later; those retain placement diagnostics.
      label.setDimensions(
          dev.dediren.ir.TextMetrics.estimateTextWidth(edge.label(), 15.4) + 10.0,
          15.4 * 1.25 + 6.0);
      label.setProperty(
          CoreOptions.EDGE_LABELS_PLACEMENT,
          org.eclipse.elk.core.options.EdgeLabelPlacement.CENTER);
    }
    return elkEdge;
  }

  private static ElkPort createEdgePort(ElkNode node, String id, PortSide side, boolean fixedSide) {
    node.setProperty(
        CoreOptions.PORT_CONSTRAINTS,
        fixedSide ? PortConstraints.FIXED_SIDE : PortConstraints.FREE);
    ElkPort port = ElkGraphUtil.createPort(node);
    port.setIdentifier(id);
    port.setDimensions(1.0, 1.0);
    if (fixedSide) {
      port.setProperty(CoreOptions.PORT_SIDE, side);
    }
    return port;
  }

  private static ElkPort sharedMergePort(
      ElkNode node,
      PortSide side,
      boolean fixedSide,
      boolean sourceEndpoint,
      String relationshipType) {
    String id =
        "__dediren_merge_"
            + (sourceEndpoint ? "source_" : "target_")
            + side.name()
            + "_"
            + relationshipTypePortSuffix(relationshipType);
    for (ElkPort port : node.getPorts()) {
      if (id.equals(port.getIdentifier())) {
        return port;
      }
    }
    return createEdgePort(node, id, side, fixedSide);
  }

  private static String relationshipTypePortSuffix(String relationshipType) {
    return Integer.toHexString(relationshipType.hashCode());
  }

  // Two distinct points on a withheld self-loop's lifeline head. The sequence normalizer replaces
  // them with the real stem-anchored hook; they exist only so the self-message keeps its slot in
  // the message-row lattice, which drops edges with fewer than two route points.
  private static List<Point> selfLoopPlaceholderRoute(ElkNode lifeline) {
    double stemX = lifeline.getX() + lifeline.getWidth() / 2.0;
    double headBottom = lifeline.getY() + lifeline.getHeight();
    return List.of(new Point(stemX, headBottom), new Point(stemX, headBottom + 1.0));
  }

  private static void setGeneratedDimensions(ElkNode elkNode, LayoutNode node) {
    double width = positiveOrDefault(node.widthHint(), PortPlan.DEFAULT_WIDTH);
    double height = positiveOrDefault(node.heightHint(), PortPlan.DEFAULT_HEIGHT);
    elkNode.setDimensions(width, height);
    if (JUNCTION_ROLE.equals(node.role())) {
      return;
    }
    elkNode.setProperty(CoreOptions.NODE_SIZE_MINIMUM, new KVector(width, height));
    elkNode.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, SizeConstraint.minimumSizeWithPorts());
    elkNode.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FREE);
  }

  private interface ElkGroupConfigurator {
    void configure(ElkNode group, Direction direction);
  }

  private static Map<String, LayoutNode> requestNodesById(LayoutRequest request) {
    Map<String, LayoutNode> byId = new HashMap<>();
    for (LayoutNode node : list(request.nodes())) {
      byId.put(node.id(), node);
    }
    return byId;
  }

  // Provenance is a pure copy-through: the request's node/edge source pointer travels to the
  // matching LaidOut* element by id. An ELK-synthesized element with no request counterpart gets
  // no entry here, so the lookup yields null (the optional field's accepted "no provenance" value)
  // rather than a synthesized pointer.
  private static Map<String, String> nodeSourcePointers(LayoutRequest request) {
    Map<String, String> pointers = new HashMap<>();
    for (LayoutNode node : list(request.nodes())) {
      pointers.put(node.id(), node.sourcePointer());
    }
    return pointers;
  }

  private static Map<String, String> edgeSourcePointers(LayoutRequest request) {
    Map<String, String> pointers = new HashMap<>();
    for (LayoutEdge edge : list(request.edges())) {
      pointers.put(edge.id(), edge.sourcePointer());
    }
    return pointers;
  }

  private static Map<String, String> ownerByNode(
      LayoutRequest request, Map<String, LayoutNode> requestNodes) {
    Map<String, String> ownerByNode = new HashMap<>();
    for (LayoutGroup group : list(request.groups())) {
      for (String member : list(group.members())) {
        if (requestNodes.containsKey(member)) {
          ownerByNode.putIfAbsent(member, group.id());
        }
      }
    }
    return ownerByNode;
  }

  private static Map<String, String> ownerByGroup(LayoutRequest request) {
    Set<String> groupIds = new HashSet<>();
    for (LayoutGroup group : list(request.groups())) {
      groupIds.add(group.id());
    }

    Map<String, String> ownerByGroup = new HashMap<>();
    for (LayoutGroup group : list(request.groups())) {
      for (String member : list(group.members())) {
        if (groupIds.contains(member)) {
          ownerByGroup.putIfAbsent(member, group.id());
        }
      }
    }
    return ownerByGroup;
  }

  private static Map<String, LayoutGroup> requestGroupsById(LayoutRequest request) {
    Map<String, LayoutGroup> groups = new HashMap<>();
    for (LayoutGroup group : list(request.groups())) {
      groups.put(group.id(), group);
    }
    return groups;
  }

  private static ElkNode createElkGroup(
      String groupId,
      ElkNode root,
      Direction rootDirection,
      Map<String, LayoutGroup> requestGroups,
      Map<String, LayoutNode> requestNodes,
      Map<String, String> ownerByNode,
      Map<String, String> ownerByGroup,
      Map<String, ElkNode> elkGroups,
      ElkGroupConfigurator groupConfigurator,
      Set<String> visiting) {
    ElkNode existing = elkGroups.get(groupId);
    if (existing != null) {
      return existing;
    }
    LayoutGroup group = requestGroups.get(groupId);
    if (group == null) {
      return null;
    }
    if (!visiting.add(groupId)) {
      throw new IllegalArgumentException("group hierarchy contains a cycle at " + groupId);
    }

    List<LayoutNode> directNodeMembers =
        list(group.members()).stream().map(requestNodes::get).filter(node -> node != null).toList();
    boolean hasChildGroupMember =
        list(group.members()).stream().anyMatch(requestGroups::containsKey);
    if (directNodeMembers.isEmpty() && !hasChildGroupMember) {
      visiting.remove(groupId);
      return null;
    }

    String parentGroupId = ownerByGroup.get(groupId);
    ElkNode parent =
        parentGroupId == null
            ? root
            : createElkGroup(
                parentGroupId,
                root,
                rootDirection,
                requestGroups,
                requestNodes,
                ownerByNode,
                ownerByGroup,
                elkGroups,
                groupConfigurator,
                visiting);
    if (parent == null) {
      parent = root;
    }

    ElkNode elkGroup = ElkGraphUtil.createNode(parent);
    elkGroup.setIdentifier(group.id());
    ElkGraphUtil.createLabel(elkGroup).setText(group.label());
    groupConfigurator.configure(elkGroup, rootDirection);
    elkGroups.put(group.id(), elkGroup);
    visiting.remove(groupId);
    return elkGroup;
  }

  private static List<LaidOutGroup> groupedBounds(
      LayoutRequest request,
      Map<String, ElkNode> elkGroups,
      Map<String, ElkNode> elkNodes,
      List<Diagnostic> warnings) {
    List<LaidOutGroup> groups = new ArrayList<>();
    List<LayoutGroup> requestGroups = list(request.groups());
    for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
      LayoutGroup group = requestGroups.get(groupIndex);
      ElkNode groupNode = elkGroups.get(group.id());
      List<String> memberIds = new ArrayList<>();
      List<String> requestedMembers = list(group.members());
      for (int memberIndex = 0; memberIndex < requestedMembers.size(); memberIndex++) {
        String memberId = requestedMembers.get(memberIndex);
        ElkNode memberNode = elkNodes.get(memberId);
        ElkNode memberGroup = elkGroups.get(memberId);
        boolean nodeMember = memberNode != null && memberNode.getParent() == groupNode;
        boolean groupMember = memberGroup != null && memberGroup.getParent() == groupNode;
        if (!nodeMember && !groupMember) {
          warnings.add(
              new Diagnostic(
                  DiagnosticCode.ELK_MISSING_GROUP_MEMBER.code(),
                  DiagnosticSeverity.WARNING,
                  "group " + group.id() + " references missing member " + memberId,
                  "$.groups[" + groupIndex + "].members[" + memberIndex + "]"));
          continue;
        }
        memberIds.add(memberId);
      }
      if (groupNode == null || memberIds.isEmpty()) {
        warnings.add(
            new Diagnostic(
                DiagnosticCode.ELK_EMPTY_GROUP.code(),
                DiagnosticSeverity.WARNING,
                "group " + group.id() + " has no laid out members",
                "$.groups[" + groupIndex + "]"));
        continue;
      }

      groups.add(
          new LaidOutGroup(
              group.id(),
              semanticBackedSourceId(group.provenance(), group.id()),
              group.id(),
              group.provenance(),
              absoluteX(groupNode),
              absoluteY(groupNode),
              groupNode.getWidth(),
              groupNode.getHeight(),
              memberIds,
              group.label()));
    }
    return groups;
  }

  private static double absoluteX(ElkNode node) {
    double x = node.getX();
    ElkNode parent = node.getParent();
    while (parent != null) {
      x += parent.getX();
      parent = parent.getParent();
    }
    return x;
  }

  private static double absoluteY(ElkNode node) {
    double y = node.getY();
    ElkNode parent = node.getParent();
    while (parent != null) {
      y += parent.getY();
      parent = parent.getParent();
    }
    return y;
  }

  static EdgeRoute route(ElkEdge edge) {
    List<ElkEdgeSection> sections = sourceToTargetSections(edge);
    double offsetX = edge.getContainingNode() == null ? 0.0 : absoluteX(edge.getContainingNode());
    double offsetY = edge.getContainingNode() == null ? 0.0 : absoluteY(edge.getContainingNode());
    if (edgeRouting(edge) == EdgeRouting.SPLINES) {
      return cubicRoute(sections, offsetX, offsetY);
    }
    List<Point> points = new ArrayList<>();
    Point previousEnd = null;
    for (ElkEdgeSection section : sections) {
      Point sectionStart = new Point(section.getStartX() + offsetX, section.getStartY() + offsetY);
      if (previousEnd != null && !samePoint(previousEnd, sectionStart)) {
        return new PolylineRoute(List.of());
      }
      if (previousEnd == null) {
        points.add(sectionStart);
      }
      section
          .getBendPoints()
          .forEach(bend -> points.add(new Point(bend.getX() + offsetX, bend.getY() + offsetY)));
      previousEnd = new Point(section.getEndX() + offsetX, section.getEndY() + offsetY);
      points.add(previousEnd);
    }
    return new PolylineRoute(points);
  }

  private static EdgeRouting edgeRouting(ElkEdge edge) {
    if (edge.hasProperty(CoreOptions.EDGE_ROUTING)) {
      return edge.getProperty(CoreOptions.EDGE_ROUTING);
    }
    ElkNode node = edge.getContainingNode();
    while (node != null) {
      if (node.hasProperty(CoreOptions.EDGE_ROUTING)) {
        return node.getProperty(CoreOptions.EDGE_ROUTING);
      }
      node = node.getParent();
    }
    return EdgeRouting.UNDEFINED;
  }

  private static CubicBezierRoute cubicRoute(
      List<ElkEdgeSection> sections, double offsetX, double offsetY) {
    if (sections.isEmpty()) {
      return new CubicBezierRoute(null, List.of());
    }
    Point routeStart =
        point(sections.getFirst().getStartX(), sections.getFirst().getStartY(), offsetX, offsetY);
    Point previousEnd = routeStart;
    List<CubicBezierSegment> segments = new ArrayList<>();
    for (ElkEdgeSection section : sections) {
      Point sectionStart = point(section.getStartX(), section.getStartY(), offsetX, offsetY);
      List<org.eclipse.elk.graph.ElkBendPoint> controls = section.getBendPoints();
      if (!samePoint(previousEnd, sectionStart) || controls.size() % 3 != 2) {
        return new CubicBezierRoute(routeStart, List.of());
      }
      for (int index = 0; index < controls.size(); index += 3) {
        Point control1 =
            point(controls.get(index).getX(), controls.get(index).getY(), offsetX, offsetY);
        Point control2 =
            point(controls.get(index + 1).getX(), controls.get(index + 1).getY(), offsetX, offsetY);
        Point end =
            index + 2 < controls.size()
                ? point(
                    controls.get(index + 2).getX(),
                    controls.get(index + 2).getY(),
                    offsetX,
                    offsetY)
                : point(section.getEndX(), section.getEndY(), offsetX, offsetY);
        segments.add(new CubicBezierSegment(control1, control2, end));
        previousEnd = end;
      }
    }
    return new CubicBezierRoute(routeStart, segments);
  }

  private static List<ElkEdgeSection> sourceToTargetSections(ElkEdge edge) {
    List<ElkEdgeSection> sections = new ArrayList<>(edge.getSections());
    if (sections.size() < 2) {
      return sections;
    }
    ElkEdgeSection current =
        sections.stream()
            .filter(section -> section.getIncomingSections().isEmpty())
            .findFirst()
            .orElse(sections.getFirst());
    List<ElkEdgeSection> ordered = new ArrayList<>(sections.size());
    Set<ElkEdgeSection> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    while (current != null && visited.add(current)) {
      ordered.add(current);
      current =
          current.getOutgoingSections().isEmpty() ? null : current.getOutgoingSections().getFirst();
    }
    for (ElkEdgeSection section : sections) {
      if (visited.add(section)) {
        ordered.add(section);
      }
    }
    return ordered;
  }

  private static Point point(double x, double y, double offsetX, double offsetY) {
    return new Point(x + offsetX, y + offsetY);
  }

  private static boolean samePoint(Point left, Point right) {
    return Double.compare(left.x(), right.x()) == 0 && Double.compare(left.y(), right.y()) == 0;
  }

  private static String semanticBackedSourceId(GroupProvenance provenance, String fallback) {
    if (provenance == null || provenance.semanticBacked() == null) {
      return fallback;
    }
    String sourceId = provenance.semanticBacked().sourceId();
    return sourceId == null ? fallback : sourceId;
  }

  private static double positiveOrDefault(Double value, double fallback) {
    return value != null && value > 0.0 ? value : fallback;
  }

  private static <T> List<T> list(List<T> values) {
    return values == null ? List.of() : values;
  }

  /**
   * The single seam where ELK actually computes geometry. Timing and graph size are the two things
   * you want when a layout comes out wrong or slow, and neither is recoverable from the envelope,
   * so they are logged here rather than duplicated at each of the three call sites.
   */
  private static void runElkLayout(ElkNode root, LayoutRequest request) {
    long startedNanos = System.nanoTime();
    new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "elk layout: nodes={} edges={} elapsedMs={}",
          list(request.nodes()).size(),
          list(request.edges()).size(),
          (System.nanoTime() - startedNanos) / 1_000_000L);
    }
  }
}

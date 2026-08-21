package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutAlgorithm;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutCycleBreaking;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEdgePriority;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutLayeringStrategy;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPlacementStrategy;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import java.util.List;

/** Package-private validation for layout requests. */
final class LayoutRequestValidator {
  private LayoutRequestValidator() {}

  static void validate(LayoutRequest request) {
    requireNonNull(request, "$");
    requireNonNull(request.layoutRequestSchemaVersion(), "$.layout_request_schema_version");
    if (!request
        .layoutRequestSchemaVersion()
        .equals(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION)) {
      throw new IllegalArgumentException(
          "$.layout_request_schema_version must be "
              + ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    }
    requireNonNull(request.viewId(), "$.view_id");
    requireNonNull(request.nodes(), "$.nodes");
    requireNonNull(request.edges(), "$.edges");
    requireNonNull(request.groups(), "$.groups");
    requireNonNull(request.constraints(), "$.constraints");
    validateLayoutPreferences(request.layoutPreferences(), "$.layout_preferences");

    for (int index = 0; index < request.nodes().size(); index++) {
      LayoutNode node = request.nodes().get(index);
      String path = "$.nodes[" + index + "]";
      requireNonNull(node, path);
      requireNonNull(node.id(), path + ".id");
      requireNonNull(node.label(), path + ".label");
      requireNonNull(node.sourceId(), path + ".source_id");
      requirePositive(node.widthHint(), path + ".width_hint");
      requirePositive(node.heightHint(), path + ".height_hint");
    }

    for (int index = 0; index < request.edges().size(); index++) {
      LayoutEdge edge = request.edges().get(index);
      String path = "$.edges[" + index + "]";
      requireNonNull(edge, path);
      requireNonNull(edge.id(), path + ".id");
      requireNonNull(edge.source(), path + ".source");
      requireNonNull(edge.target(), path + ".target");
      requireNonNull(edge.label(), path + ".label");
      requireNonNull(edge.sourceId(), path + ".source_id");
    }

    validateEdgePriorities(request.edges(), request.layoutPreferences());

    for (int index = 0; index < request.groups().size(); index++) {
      LayoutGroup group = request.groups().get(index);
      String path = "$.groups[" + index + "]";
      requireNonNull(group, path);
      requireNonNull(group.id(), path + ".id");
      requireNonNull(group.label(), path + ".label");
      requireNonNull(group.members(), path + ".members");
      requireNonNull(group.provenance(), path + ".provenance");
      validateProvenance(group.provenance(), path + ".provenance");
      for (int memberIndex = 0; memberIndex < group.members().size(); memberIndex++) {
        requireNonNull(group.members().get(memberIndex), path + ".members[" + memberIndex + "]");
      }
    }

    for (int index = 0; index < request.constraints().size(); index++) {
      LayoutConstraint constraint = request.constraints().get(index);
      String path = "$.constraints[" + index + "]";
      requireNonNull(constraint, path);
      requireNonNull(constraint.id(), path + ".id");
      requireNonNull(constraint.kind(), path + ".kind");
      requireNonNull(constraint.subjects(), path + ".subjects");
      for (int subjectIndex = 0; subjectIndex < constraint.subjects().size(); subjectIndex++) {
        requireNonNull(
            constraint.subjects().get(subjectIndex), path + ".subjects[" + subjectIndex + "]");
      }
    }
  }

  private static void validateLayoutPreferences(LayoutPreferences preferences, String path) {
    if (preferences == null) {
      return;
    }
    validateAlgorithmCompatibility(preferences, path);
  }

  private static void validateAlgorithmCompatibility(LayoutPreferences preferences, String path) {
    LayoutAlgorithm algorithm = preferences.algorithm();
    if (algorithm == null || algorithm == LayoutAlgorithm.LAYERED) {
      return;
    }
    rejectLayeredOnly(preferences.cycleBreaking() != null, path + ".cycle_breaking");
    rejectLayeredOnly(preferences.layering() != null, path + ".layering");
    rejectLayeredOnly(preferences.crossing() != null, path + ".crossing");
    rejectLayeredOnly(preferences.placement() != null, path + ".placement");
    rejectLayeredOnly(preferences.compaction() != null, path + ".compaction");
    rejectLayeredOnly(preferences.highDegreeNodes() != null, path + ".high_degree_nodes");
    rejectLayeredOnly(preferences.thoroughness() != null, path + ".thoroughness");
  }

  private static void rejectLayeredOnly(boolean present, String path) {
    if (present) {
      throw new IllegalArgumentException(path + " is only supported for the 'layered' algorithm");
    }
  }

  private static void validateEdgePriorities(
      List<LayoutEdge> edges, LayoutPreferences preferences) {
    for (int index = 0; index < edges.size(); index++) {
      LayoutEdge edge = edges.get(index);
      LayoutEdgePriority priority = edge == null ? null : edge.priority();
      if (priority == null) {
        continue;
      }
      String path = "$.edges[" + index + "].priority";
      if (priority.resistReversal() != null && !cycleBreakingHonorsDirection(preferences)) {
        throw new IllegalArgumentException(
            path + ".resist_reversal is only honored by the 'greedy' cycle_breaking strategy");
      }
      if (priority.keepShort() != null && !layeringHonorsShortness(preferences)) {
        throw new IllegalArgumentException(
            path + ".keep_short is only honored by the 'network-simplex' layering strategy");
      }
      if (priority.keepStraight() != null && !placementHonorsStraightness(preferences)) {
        throw new IllegalArgumentException(
            path + ".keep_straight is not honored by the 'simple' placement strategy");
      }
    }
  }

  private static boolean cycleBreakingHonorsDirection(LayoutPreferences preferences) {
    var strategy = preferences == null ? null : preferences.cycleBreaking();
    return strategy == null || strategy == LayoutCycleBreaking.GREEDY;
  }

  private static boolean layeringHonorsShortness(LayoutPreferences preferences) {
    var layering = preferences == null ? null : preferences.layering();
    var strategy = layering == null ? null : layering.strategy();
    return strategy == null || strategy == LayoutLayeringStrategy.NETWORK_SIMPLEX;
  }

  private static boolean placementHonorsStraightness(LayoutPreferences preferences) {
    var placement = preferences == null ? null : preferences.placement();
    var strategy = placement == null ? null : placement.strategy();
    // Deny-list: every placer except SimpleNodePlacer reads PRIORITY_STRAIGHTNESS in ELK 0.11.0, so
    // only SIMPLE is rejected. Unlike the cycle-breaking/layering allow-lists (which fail safe),
    // this
    // fails OPEN if LayoutPlacementStrategy gains a future non-simple placer that ignores
    // straightness — it would be silently accepted. placementEnumSizeTripwire flags that enum
    // growth.
    return strategy != LayoutPlacementStrategy.SIMPLE;
  }

  private static void validateProvenance(GroupProvenance provenance, String path) {
    boolean visualOnly = Boolean.TRUE.equals(provenance.visualOnly());
    boolean semanticBacked = provenance.semanticBacked() != null;
    if (visualOnly == semanticBacked) {
      throw new IllegalArgumentException(
          "group provenance must contain exactly one of visual_only or semantic_backed at " + path);
    }
    if (semanticBacked && provenance.semanticBacked().sourceId() == null) {
      throw new IllegalArgumentException(
          "required string value is missing at " + path + ".semantic_backed.source_id");
    }
  }

  private static void requireNonNull(Object value, String path) {
    if (value == null) {
      throw new IllegalArgumentException("required value is missing at " + path);
    }
  }

  private static void requirePositive(Double value, String path) {
    if (value != null && (!Double.isFinite(value) || value <= 0.0)) {
      throw new IllegalArgumentException("value at " + path + " must be finite and positive");
    }
  }

}

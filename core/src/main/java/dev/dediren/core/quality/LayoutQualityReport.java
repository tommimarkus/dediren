package dev.dediren.core.quality;

public record LayoutQualityReport(
    String status,
    String policyName,
    int overlapCount,
    int connectorThroughNodeCount,
    int routeNodeClearanceIssueCount,
    int invalidRouteCount,
    int routeDetourCount,
    int routeOverlapCount,
    int routeCloseParallelCount,
    int groupBoundaryIssueCount,
    int groupLabelBandIssueCount,
    int labelSpaceIssueCount,
    int edgeLabelDissociationCount,
    int edgeCrossingCount,
    int warningCount) {}

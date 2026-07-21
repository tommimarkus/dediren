package dev.dediren.contracts.analysis;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * The `dediren query` payload: one fixed-vocabulary question answered deterministically. Exactly
 * the block matching {@code kind} is present (the others serialize away under the global NON_NULL
 * policy) — a fixed vocabulary, not a query language.
 */
public record QueryResult(
    String queryResultSchemaVersion,
    String kind,
    Dependents dependents,
    Orphans orphans,
    ViewCoverage viewCoverage) {

  /** Fan-in ({@code inbound}: relationships targeting the id) and fan-out for one node. */
  public record Dependents(String id, List<Edge> inbound, List<Edge> outbound) {
    public Dependents {
      inbound = listOrEmpty(inbound);
      outbound = listOrEmpty(outbound);
    }

    public record Edge(String relationshipId, String type, String nodeId) {}
  }

  /** Node ids with no incident relationships, and node ids referenced by no view. */
  public record Orphans(List<String> relationshipOrphans, List<String> viewOrphans) {
    public Orphans {
      relationshipOrphans = listOrEmpty(relationshipOrphans);
      viewOrphans = listOrEmpty(viewOrphans);
    }
  }

  /** Per-view membership counts plus the model totals and the nodes no view covers. */
  public record ViewCoverage(
      List<ViewStat> views,
      int modelNodeCount,
      int modelRelationshipCount,
      List<String> uncoveredNodeIds) {
    public ViewCoverage {
      views = listOrEmpty(views);
      uncoveredNodeIds = listOrEmpty(uncoveredNodeIds);
    }

    public record ViewStat(String id, int nodeCount, int relationshipCount) {}
  }
}

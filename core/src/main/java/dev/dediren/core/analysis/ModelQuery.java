package dev.dediren.core.analysis;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.analysis.QueryResult;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The fixed query vocabulary over a validated source model: {@code dependents} (fan-in/fan-out for
 * one node), {@code orphans} (nodes without incident relationships; nodes in no view), and {@code
 * view-coverage} (per-view membership counts plus the uncovered nodes). Pure functions, every list
 * sorted, byte-stable output. A fixed vocabulary, deliberately not a query language.
 */
public final class ModelQuery {

  private ModelQuery() {}

  public static QueryResult dependents(SourceDocument document, String nodeId) {
    var inbound = new ArrayList<QueryResult.Dependents.Edge>();
    var outbound = new ArrayList<QueryResult.Dependents.Edge>();
    for (SourceRelationship relationship : document.relationships()) {
      if (nodeId.equals(relationship.target())) {
        inbound.add(
            new QueryResult.Dependents.Edge(
                relationship.id(), relationship.type(), relationship.source()));
      }
      if (nodeId.equals(relationship.source())) {
        outbound.add(
            new QueryResult.Dependents.Edge(
                relationship.id(), relationship.type(), relationship.target()));
      }
    }
    Comparator<QueryResult.Dependents.Edge> byRelationshipId =
        Comparator.comparing(QueryResult.Dependents.Edge::relationshipId);
    inbound.sort(byRelationshipId);
    outbound.sort(byRelationshipId);
    return new QueryResult(
        ContractVersions.QUERY_RESULT_SCHEMA_VERSION,
        "dependents",
        new QueryResult.Dependents(nodeId, inbound, outbound),
        null,
        null);
  }

  public static QueryResult orphans(SourceDocument document) {
    var withRelationships = new TreeSet<String>();
    for (SourceRelationship relationship : document.relationships()) {
      withRelationships.add(relationship.source());
      withRelationships.add(relationship.target());
    }
    var inViews = new TreeSet<String>();
    for (GenericGraphView view : ModelDiff.views(document).values()) {
      inViews.addAll(view.nodes());
    }
    var relationshipOrphans = new TreeSet<String>();
    var viewOrphans = new TreeSet<String>();
    for (SourceNode node : document.nodes()) {
      if (!withRelationships.contains(node.id())) {
        relationshipOrphans.add(node.id());
      }
      if (!inViews.contains(node.id())) {
        viewOrphans.add(node.id());
      }
    }
    return new QueryResult(
        ContractVersions.QUERY_RESULT_SCHEMA_VERSION,
        "orphans",
        null,
        new QueryResult.Orphans(List.copyOf(relationshipOrphans), List.copyOf(viewOrphans)),
        null);
  }

  public static QueryResult viewCoverage(SourceDocument document) {
    Map<String, GenericGraphView> views = ModelDiff.views(document);
    var stats = new ArrayList<QueryResult.ViewCoverage.ViewStat>();
    var covered = new TreeSet<String>();
    for (GenericGraphView view : views.values()) {
      stats.add(
          new QueryResult.ViewCoverage.ViewStat(
              view.id(), view.nodes().size(), view.relationships().size()));
      covered.addAll(view.nodes());
    }
    var uncovered = new TreeSet<String>();
    for (SourceNode node : document.nodes()) {
      if (!covered.contains(node.id())) {
        uncovered.add(node.id());
      }
    }
    return new QueryResult(
        ContractVersions.QUERY_RESULT_SCHEMA_VERSION,
        "view-coverage",
        null,
        null,
        new QueryResult.ViewCoverage(
            stats,
            document.nodes().size(),
            document.relationships().size(),
            List.copyOf(uncovered)));
  }
}

package dev.dediren.semantics.graph;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.SceneGraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Profile-routing {@link SemanticsEngine} for the generic-graph notation: base source validation
 * and projection, with every notation-specific decision dispatched to one {@link NotationSemantics}
 * per {@link GenericGraphSemanticProfile}. Relocated from the old single {@code
 * GenericGraphEngine}; the base {@code validate} / {@code prepareProjection} call sequences,
 * diagnostic codes, JSON paths, and exit codes are preserved, with the notation legality that used
 * to switch on a string profile now delegated to the routed {@code NotationSemantics}.
 *
 * <p>Every failure is enveloped. Semantic legality failures surface as {@link
 * EngineException#semanticFailure} (exit 3); structural failures — a source without {@code
 * plugins.generic-graph}, an unknown view id, or a ghost view reference (a view node or
 * relationship id absent from the source, a group member outside its view, a {@code
 * semantic_source_id} naming no source node) — surface as {@link EngineException#structuralFailure}
 * (exit 2, the published INPUT_ERROR observable, with the envelope on stdout). {@link
 * SceneProjection}'s own {@link IOException} throws stay only as backstops behind that validation;
 * one escaping would still ride {@link UncheckedIOException} and be enveloped by dispatch as {@code
 * DEDIREN_ENGINE_FAILED}.
 */
public final class SemanticsRouterEngine implements SemanticsEngine {

  private final Map<GenericGraphSemanticProfile, NotationSemantics> notations;

  public SemanticsRouterEngine(Map<GenericGraphSemanticProfile, NotationSemantics> notations) {
    this.notations = Map.copyOf(notations);
  }

  @Override
  public String id() {
    return "generic-graph";
  }

  /**
   * Converts source bytes to a typed {@link SourceDocument}. generic-graph publishes no dedicated
   * parse-failure envelope, so a malformed stream surfaces as today's raw (non-enveloped) failure
   * by letting the underlying parse exception propagate.
   */
  public SourceDocument parseSource(byte[] input) {
    return JsonSupport.objectMapper().readValue(input, SourceDocument.class);
  }

  /**
   * The published missing-profile failure ({@code DEDIREN_SEMANTIC_PROFILE_REQUIRED} / exit 3).
   * Package-visible so the {@code src/test/java} router harness can reproduce the identical
   * envelope; {@link #validate} is the only production caller — this module has no separate
   * semantics {@code Main}, the router is hosted in-process by the cli via {@code EngineWiring}.
   */
  static EngineException profileRequired() {
    return EngineException.semanticFailure(
        DiagnosticCode.SEMANTIC_PROFILE_REQUIRED.code(),
        "semantic validation requires --profile",
        null);
  }

  @Override
  public EngineResult<SemanticValidationResult> validate(SourceDocument source, String profile)
      throws EngineException {
    if (profile == null) {
      throw profileRequired();
    }
    GenericGraphPluginData pluginData = pluginData(source);
    validateGenericGraphPluginData(source, pluginData);

    GenericGraphSemanticProfile requested = requestedProfile(profile);
    if (requested == null) {
      throw EngineException.semanticFailure(
          DiagnosticCode.SEMANTIC_PROFILE_UNSUPPORTED.code(),
          "unsupported semantic profile: " + profile,
          "profile");
    }
    notations.get(requested).validate(source, pluginData);

    return new EngineResult<>(
        new SemanticValidationResult(
            ContractVersions.SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION,
            profile,
            source.nodes().size(),
            source.relationships().size()),
        List.of());
  }

  @Override
  public EngineResult<SceneGraph> projectScene(SourceDocument source, String view)
      throws EngineException {
    Projection projection = prepareProjection(source, view);
    try {
      return new EngineResult<>(
          SceneProjection.projectScene(source, projection.view(), projection.notation()),
          List.of());
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  @Override
  public EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view)
      throws EngineException {
    Projection projection = prepareProjection(source, view);
    try {
      return new EngineResult<>(
          SceneProjection.projectRenderMetadata(source, projection.view(), projection.notation()),
          List.of());
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private Projection prepareProjection(SourceDocument source, String view) throws EngineException {
    GenericGraphPluginData pluginData = pluginData(source);
    validateGenericGraphPluginData(source, pluginData);
    GenericGraphView selectedView =
        pluginData.views().stream()
            .filter(candidate -> candidate.id().equals(view))
            .findFirst()
            .orElse(null);
    if (selectedView == null) {
      throw EngineException.structuralFailure(
          DiagnosticCode.GENERIC_GRAPH_VIEW_UNKNOWN.code(),
          "missing generic-graph view " + view,
          "$.plugins.generic-graph.views");
    }

    GenericGraphSemanticProfile profile = SemanticProfiles.sourceSemanticProfile(pluginData);
    NotationSemantics notation = notations.get(profile);
    notation.validate(source, pluginData);
    return new Projection(selectedView, notation);
  }

  private static GenericGraphSemanticProfile requestedProfile(String profile) {
    return switch (profile) {
      case "archimate" -> GenericGraphSemanticProfile.ARCHIMATE;
      case "uml" -> GenericGraphSemanticProfile.UML;
      default -> null;
    };
  }

  private static GenericGraphPluginData pluginData(SourceDocument source) throws EngineException {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    if (pluginValue == null) {
      throw EngineException.structuralFailure(
          DiagnosticCode.GENERIC_GRAPH_PLUGIN_REQUIRED.code(),
          "missing plugins.generic-graph",
          "$.plugins.generic-graph");
    }
    return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
  }

  /**
   * Whole-document base validation of the generic-graph plugin data. The duplicate view id,
   * endpoint-outside-view, and duplicate group id checks keep their published {@link
   * EngineException#semanticFailure} envelopes; the four ghost view references — a view node or
   * relationship id absent from the source, a group member outside its view, a {@code
   * semantic_source_id} naming no source node — are {@link EngineException#structuralFailure}
   * envelopes. The ghost checks mirror {@link SceneProjection}'s throw conditions exactly (no
   * wider, no narrower), so a document this validation passes cannot hit those projection throws.
   */
  private static void validateGenericGraphPluginData(
      SourceDocument source, GenericGraphPluginData pluginData) throws EngineException {
    var relationshipsById = new java.util.LinkedHashMap<String, SourceRelationship>();
    for (SourceRelationship relationship : source.relationships()) {
      relationshipsById.put(relationship.id(), relationship);
    }
    var sourceNodeIds = new java.util.TreeSet<String>();
    for (SourceNode node : source.nodes()) {
      sourceNodeIds.add(node.id());
    }
    var viewIds = new java.util.TreeSet<String>();
    for (int viewIndex = 0; viewIndex < pluginData.views().size(); viewIndex++) {
      GenericGraphView view = pluginData.views().get(viewIndex);
      if (!viewIds.add(view.id())) {
        throw EngineException.semanticFailure(
            DiagnosticCode.GENERIC_GRAPH_DUPLICATE_VIEW_ID.code(),
            "duplicate generic-graph view id '" + view.id() + "'",
            "$.plugins.generic-graph.views[" + viewIndex + "].id");
      }

      for (int nodeIndex = 0; nodeIndex < view.nodes().size(); nodeIndex++) {
        String nodeId = view.nodes().get(nodeIndex);
        if (!sourceNodeIds.contains(nodeId)) {
          throw EngineException.structuralFailure(
              DiagnosticCode.GENERIC_GRAPH_VIEW_NODE_UNKNOWN.code(),
              "view '" + view.id() + "' references missing node '" + nodeId + "'",
              "$.plugins.generic-graph.views[" + viewIndex + "].nodes[" + nodeIndex + "]");
        }
      }

      var viewNodeIds = new java.util.TreeSet<>(view.nodes());
      for (int relationshipIndex = 0;
          relationshipIndex < view.relationships().size();
          relationshipIndex++) {
        String relationshipId = view.relationships().get(relationshipIndex);
        SourceRelationship relationship = relationshipsById.get(relationshipId);
        if (relationship == null) {
          throw EngineException.structuralFailure(
              DiagnosticCode.GENERIC_GRAPH_VIEW_RELATIONSHIP_UNKNOWN.code(),
              "view '" + view.id() + "' references missing relationship '" + relationshipId + "'",
              "$.plugins.generic-graph.views["
                  + viewIndex
                  + "].relationships["
                  + relationshipIndex
                  + "]");
        }
        if (!viewNodeIds.contains(relationship.source())
            || !viewNodeIds.contains(relationship.target())) {
          throw EngineException.semanticFailure(
              DiagnosticCode.GENERIC_GRAPH_RELATIONSHIP_ENDPOINT_OUTSIDE_VIEW.code(),
              "relationship '"
                  + relationshipId
                  + "' references an endpoint outside view '"
                  + view.id()
                  + "'",
              "$.plugins.generic-graph.views["
                  + viewIndex
                  + "].relationships["
                  + relationshipIndex
                  + "]");
        }
      }

      var viewGroupIds = new java.util.TreeSet<String>();
      for (GenericGraphViewGroup group : view.groups()) {
        viewGroupIds.add(group.id());
      }
      var groupIds = new java.util.TreeSet<String>();
      for (int groupIndex = 0; groupIndex < view.groups().size(); groupIndex++) {
        GenericGraphViewGroup group = view.groups().get(groupIndex);
        if (!groupIds.add(group.id())) {
          throw EngineException.semanticFailure(
              DiagnosticCode.GENERIC_GRAPH_DUPLICATE_GROUP_ID.code(),
              "duplicate generic-graph group id '" + group.id() + "' in view '" + view.id() + "'",
              "$.plugins.generic-graph.views[" + viewIndex + "].groups[" + groupIndex + "].id");
        }
        for (int memberIndex = 0; memberIndex < group.members().size(); memberIndex++) {
          String member = group.members().get(memberIndex);
          if (!viewNodeIds.contains(member) && !viewGroupIds.contains(member)) {
            throw EngineException.structuralFailure(
                DiagnosticCode.GENERIC_GRAPH_GROUP_MEMBER_OUTSIDE_VIEW.code(),
                "group '"
                    + group.id()
                    + "' in view '"
                    + view.id()
                    + "' references member '"
                    + member
                    + "' outside the view",
                "$.plugins.generic-graph.views["
                    + viewIndex
                    + "].groups["
                    + groupIndex
                    + "].members["
                    + memberIndex
                    + "]");
          }
        }
        if (group.role() != GenericGraphViewGroupRole.LAYOUT_ONLY
            && group.semanticSourceId() != null
            && !sourceNodeIds.contains(group.semanticSourceId())) {
          throw EngineException.structuralFailure(
              DiagnosticCode.GENERIC_GRAPH_GROUP_SEMANTIC_SOURCE_UNKNOWN.code(),
              "group '"
                  + group.id()
                  + "' in view '"
                  + view.id()
                  + "' semantic_source_id references missing node '"
                  + group.semanticSourceId()
                  + "'",
              "$.plugins.generic-graph.views["
                  + viewIndex
                  + "].groups["
                  + groupIndex
                  + "].semantic_source_id");
        }
      }
    }
  }

  private record Projection(GenericGraphView view, NotationSemantics notation) {}
}

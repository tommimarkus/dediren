package dev.dediren.semantics.graph;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.engine.SemanticsEngine;
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
 * <p>Published enveloped diagnostics (exit 3) surface as {@link EngineException}. generic-graph
 * publishes no envelope for structural projection failures (missing {@code plugins.generic-graph}
 * or an unknown view id): those surface as today's raw non-enveloped exit, reproduced here by
 * throwing {@link UncheckedIOException}, which the interface's {@code throws EngineException}
 * clause cannot carry as a checked {@link IOException}; the cli's {@code Main} (hosting this engine
 * in-process via {@code EngineWiring}) routes them to today's exit codes — there is no separate
 * semantics {@code Main} in this module.
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
    return failure(
        "DEDIREN_SEMANTIC_PROFILE_REQUIRED", "semantic validation requires --profile", null);
  }

  @Override
  public EngineResult<SemanticValidationResult> validate(SourceDocument source, String profile)
      throws EngineException {
    if (profile == null) {
      throw profileRequired();
    }
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphValidationError graphError = validateGenericGraphPluginData(source, pluginData);
    if (graphError != null) {
      throw failure(graphError.code(), graphError.message(), graphError.path());
    }

    GenericGraphSemanticProfile requested = requestedProfile(profile);
    if (requested == null) {
      throw failure(
          "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
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
  public EngineResult<LayoutRequest> projectLayoutRequest(SourceDocument source, String view)
      throws EngineException {
    Projection projection = prepareProjection(source, view);
    try {
      return new EngineResult<>(
          SceneProjection.projectLayoutRequest(source, projection.view(), projection.notation()),
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
    GenericGraphValidationError graphError = validateGenericGraphPluginData(source, pluginData);
    if (graphError != null) {
      throw failure(graphError.code(), graphError.message(), graphError.path());
    }
    GenericGraphView selectedView =
        pluginData.views().stream()
            .filter(candidate -> candidate.id().equals(view))
            .findFirst()
            .orElse(null);
    if (selectedView == null) {
      throw new UncheckedIOException(new IOException("missing generic-graph view " + view));
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

  private static GenericGraphPluginData pluginData(SourceDocument source) {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    if (pluginValue == null) {
      throw new UncheckedIOException(new IOException("missing plugins.generic-graph"));
    }
    return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
  }

  private static GenericGraphValidationError validateGenericGraphPluginData(
      SourceDocument source, GenericGraphPluginData pluginData) {
    var relationshipsById = new java.util.LinkedHashMap<String, SourceRelationship>();
    for (SourceRelationship relationship : source.relationships()) {
      relationshipsById.put(relationship.id(), relationship);
    }
    var viewIds = new java.util.TreeSet<String>();
    for (int viewIndex = 0; viewIndex < pluginData.views().size(); viewIndex++) {
      GenericGraphView view = pluginData.views().get(viewIndex);
      if (!viewIds.add(view.id())) {
        return new GenericGraphValidationError(
            "DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID",
            "duplicate generic-graph view id '" + view.id() + "'",
            "$.plugins.generic-graph.views[" + viewIndex + "].id");
      }

      var viewNodeIds = new java.util.TreeSet<>(view.nodes());
      for (int relationshipIndex = 0;
          relationshipIndex < view.relationships().size();
          relationshipIndex++) {
        String relationshipId = view.relationships().get(relationshipIndex);
        SourceRelationship relationship = relationshipsById.get(relationshipId);
        if (relationship != null
            && (!viewNodeIds.contains(relationship.source())
                || !viewNodeIds.contains(relationship.target()))) {
          return new GenericGraphValidationError(
              "DEDIREN_GENERIC_GRAPH_RELATIONSHIP_ENDPOINT_OUTSIDE_VIEW",
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

      var groupIds = new java.util.TreeSet<String>();
      for (int groupIndex = 0; groupIndex < view.groups().size(); groupIndex++) {
        GenericGraphViewGroup group = view.groups().get(groupIndex);
        if (!groupIds.add(group.id())) {
          return new GenericGraphValidationError(
              "DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID",
              "duplicate generic-graph group id '" + group.id() + "' in view '" + view.id() + "'",
              "$.plugins.generic-graph.views[" + viewIndex + "].groups[" + groupIndex + "].id");
        }
      }
    }
    return null;
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }

  private record GenericGraphValidationError(String code, String message, String path) {}

  private record Projection(GenericGraphView view, NotationSemantics notation) {}
}

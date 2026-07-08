package dev.dediren.plugins.genericgraph;

import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateJunctionValidationException;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.archimate.JunctionValidationNode;
import dev.dediren.archimate.JunctionValidationRelationship;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link SemanticsEngine} for the generic-graph notation: source validation and
 * projection into layout-request / render-metadata inputs. Extracted from {@code Main}'s {@code
 * validateFromStdin} / {@code projectFromStdin} bodies and {@link GenericGraphProjection},
 * operating on an already-parsed {@link SourceDocument}.
 *
 * <p>Published enveloped diagnostics (exit 3) surface as {@link EngineException}. generic-graph
 * publishes no envelope for structural projection failures (missing {@code plugins.generic-graph},
 * an unknown view id, or a projection reference outside the view): the process form reports those
 * as a raw non-enveloped exit. This engine reproduces that observable by throwing them as {@link
 * UncheckedIOException}, which the interface's {@code throws EngineException} clause cannot carry
 * as a checked {@link IOException}; the thin {@code Main} routes them to today's exit codes.
 */
public final class GenericGraphEngine implements SemanticsEngine {

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

  @Override
  public EngineResult<SemanticValidationResult> validate(SourceDocument source, String profile)
      throws EngineException {
    if (profile == null) {
      throw failure(
          "DEDIREN_SEMANTIC_PROFILE_REQUIRED", "semantic validation requires --profile", null);
    }
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphValidationError graphError = validateGenericGraphPluginData(source, pluginData);
    if (graphError != null) {
      throw failure(graphError.code(), graphError.message(), graphError.path());
    }

    switch (profile) {
      case "archimate" -> validateArchimateSource(source);
      case "uml" -> validateUml(source, pluginData);
      default ->
          throw failure(
              "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
              "unsupported semantic profile: " + profile,
              "profile");
    }

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
          GenericGraphProjection.projectLayoutRequest(
              source, projection.view(), projection.semanticProfile()),
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
          GenericGraphProjection.projectRenderMetadata(
              source, projection.view(), projection.semanticProfile()),
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

    String semanticProfile = GenericGraphProjection.sourceSemanticProfile(pluginData);
    if (semanticProfile.equals("archimate")) {
      validateArchimateSource(source);
    } else if (semanticProfile.equals("uml")) {
      validateUml(source, pluginData);
    }
    return new Projection(selectedView, semanticProfile);
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

  private static void validateArchimateSource(SourceDocument source) throws EngineException {
    try {
      validateArchimateSourceTypes(source);
      validateArchimateJunctionSemantics(source);
    } catch (ArchimateTypeValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (ArchimateJunctionValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    }
  }

  private static void validateUml(SourceDocument source, GenericGraphPluginData pluginData)
      throws EngineException {
    try {
      Uml.validateSource(source, pluginData);
    } catch (UmlValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    }
  }

  private static void validateArchimateSourceTypes(SourceDocument source)
      throws ArchimateTypeValidationException {
    var nodeTypes = new LinkedHashMap<String, String>();
    for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
      SourceNode node = source.nodes().get(nodeIndex);
      Archimate.validateElementType(node.type(), "$.nodes[" + nodeIndex + "].type");
      nodeTypes.put(node.id(), node.type());
    }
    for (int relationshipIndex = 0;
        relationshipIndex < source.relationships().size();
        relationshipIndex++) {
      SourceRelationship relationship = source.relationships().get(relationshipIndex);
      Archimate.validateRelationshipType(
          relationship.type(), "$.relationships[" + relationshipIndex + "].type");
      String sourceType = nodeTypes.get(relationship.source());
      String targetType = nodeTypes.get(relationship.target());
      if (sourceType == null || targetType == null) {
        continue;
      }
      Archimate.validateRelationshipEndpointTypes(
          relationship.type(),
          sourceType,
          targetType,
          "$.relationships[" + relationshipIndex + "]");
    }
  }

  private static void validateArchimateJunctionSemantics(SourceDocument source)
      throws ArchimateJunctionValidationException {
    var nodes = new ArrayList<JunctionValidationNode>();
    for (int index = 0; index < source.nodes().size(); index++) {
      SourceNode node = source.nodes().get(index);
      nodes.add(new JunctionValidationNode(node.id(), node.type(), "$.nodes[" + index + "]"));
    }
    var relationships =
        source.relationships().stream()
            .map(
                relationship ->
                    new JunctionValidationRelationship(
                        relationship.type(), relationship.source(), relationship.target()))
            .toList();
    Archimate.validateJunctionRelationshipSemantics(nodes, relationships);
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }

  private record GenericGraphValidationError(String code, String message, String path) {}

  private record Projection(GenericGraphView view, String semanticProfile) {}
}

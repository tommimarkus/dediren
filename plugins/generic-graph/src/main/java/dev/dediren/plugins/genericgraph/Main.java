package dev.dediren.plugins.genericgraph;

import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateJunctionValidationException;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.archimate.JunctionValidationNode;
import dev.dediren.archimate.JunctionValidationRelationship;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

// lean-audit:dup-intentional: cross-plugin envelope boilerplate; see arch-guidelines.md §12
public final class Main {
  private Main() {}

  public static String moduleName() {
    return "generic-graph";
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args, System.in, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static PluginResult executeForTesting(String[] args, String stdin) throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) throws Exception {
    if (args.length > 0 && args[0].equals("capabilities")) {
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(
                  new RuntimeCapabilities(
                      ContractVersions.PLUGIN_PROTOCOL_VERSION,
                      "generic-graph",
                      List.of("semantic-validation", "projection"),
                      null)));
      return 0;
    }
    if (args.length == 0) {
      stderr.println("expected command: validate or project");
      return 2;
    }
    return switch (args[0]) {
      case "validate" -> validateFromStdin(args, stdin, stdout);
      case "project" -> {
        try {
          yield projectFromStdin(args, stdin, stdout, stderr);
        } catch (IOException error) {
          stderr.println(error.getMessage());
          yield 2;
        }
      }
      default -> {
        stderr.println("expected command: validate or project");
        yield 2;
      }
    };
  }

  private static int validateFromStdin(String[] args, InputStream stdin, PrintStream stdout)
      throws Exception {
    String profile = valueAfter(args, "--profile");
    if (profile == null) {
      return exitWithDiagnostic(
          stdout,
          "DEDIREN_SEMANTIC_PROFILE_REQUIRED",
          "semantic validation requires --profile",
          null);
    }
    SourceDocument source = readSource(stdin);
    GenericGraphPluginData pluginData = genericGraphPluginData(source);
    GenericGraphValidationError graphError = validateGenericGraphPluginData(source, pluginData);
    if (graphError != null) {
      return exitWithDiagnostic(stdout, graphError.code(), graphError.message(), graphError.path());
    }

    switch (profile) {
      case "archimate" -> {
        int validationExit = validateArchimateSource(source, stdout);
        if (validationExit != 0) {
          return validationExit;
        }
      }
      case "uml" -> {
        try {
          Uml.validateSource(source, pluginData);
        } catch (UmlValidationException error) {
          return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        }
      }
      default -> {
        return exitWithDiagnostic(
            stdout,
            "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
            "unsupported semantic profile: " + profile,
            "profile");
      }
    }

    stdout.println(
        JsonSupport.objectMapper()
            .writeValueAsString(
                CommandEnvelope.ok(
                    new SemanticValidationResult(
                        ContractVersions.SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION,
                        profile,
                        source.nodes().size(),
                        source.relationships().size()))));
    return 0;
  }

  private static int projectFromStdin(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) throws Exception {
    String target = valueAfter(args, "--target");
    String view = valueAfter(args, "--view");
    if (!Objects.equals(target, "layout-request") && !Objects.equals(target, "render-metadata")) {
      stderr.println("unsupported target: " + target);
      return 2;
    }
    if (view == null) {
      stderr.println("missing --view");
      return 2;
    }

    SourceDocument source = readSource(stdin);
    GenericGraphPluginData pluginData = genericGraphPluginData(source);
    GenericGraphValidationError graphError = validateGenericGraphPluginData(source, pluginData);
    if (graphError != null) {
      return exitWithDiagnostic(stdout, graphError.code(), graphError.message(), graphError.path());
    }
    GenericGraphView selectedView =
        pluginData.views().stream()
            .filter(candidate -> candidate.id().equals(view))
            .findFirst()
            .orElse(null);
    if (selectedView == null) {
      stderr.println("missing generic-graph view " + view);
      return 2;
    }

    String semanticProfile = GenericGraphProjection.sourceSemanticProfile(pluginData);
    if (semanticProfile.equals("archimate")) {
      int validationExit = validateArchimateSource(source, stdout);
      if (validationExit != 0) {
        return validationExit;
      }
    } else if (semanticProfile.equals("uml")) {
      try {
        Uml.validateSource(source, pluginData);
      } catch (UmlValidationException error) {
        return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
      }
    }

    if (target.equals("render-metadata")) {
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(
                  CommandEnvelope.ok(
                      GenericGraphProjection.projectRenderMetadata(
                          source, selectedView, semanticProfile))));
      return 0;
    }

    stdout.println(
        JsonSupport.objectMapper()
            .writeValueAsString(
                CommandEnvelope.ok(
                    GenericGraphProjection.projectLayoutRequest(
                        source, selectedView, semanticProfile))));
    return 0;
  }

  private static SourceDocument readSource(InputStream stdin) throws IOException {
    return JsonSupport.objectMapper().readValue(stdin.readAllBytes(), SourceDocument.class);
  }

  private static GenericGraphPluginData genericGraphPluginData(SourceDocument source)
      throws IOException {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    if (pluginValue == null) {
      throw new IOException("missing plugins.generic-graph");
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

  private static int validateArchimateSource(SourceDocument source, PrintStream stdout)
      throws Exception {
    try {
      validateArchimateSourceTypes(source);
      validateArchimateJunctionSemantics(source);
      return 0;
    } catch (ArchimateTypeValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (ArchimateJunctionValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
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

  private static int exitWithDiagnostic(
      PrintStream stdout, String code, String message, String path) throws IOException {
    var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    return 3;
  }

  private static String valueAfter(String[] args, String flag) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(flag)) {
        return args[i + 1];
      }
    }
    return null;
  }

  private record GenericGraphValidationError(String code, String message, String path) {}
}

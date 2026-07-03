package dev.dediren.plugins.archimateoef;

import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateJunctionValidationException;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.archimate.JunctionValidationNode;
import dev.dediren.archimate.JunctionValidationRelationship;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.export.OefExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.schemacache.SchemaCacheException;
import dev.dediren.schemacache.SchemaCacheModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class Main {
  private static final String OEF_NS = "http://www.opengroup.org/xsd/archimate/3.0/";
  private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
  private static final String OEF_SCHEMA =
      "http://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd";
  private static final String OEF_SCHEMA_VALIDATOR = "xmllint";
  private static final String OEF_SCHEMA_BASE_URL = "https://www.opengroup.org/xsd/archimate/3.1";
  private static final String OEF_SCHEMA_DIR_ENV = "DEDIREN_OEF_SCHEMA_DIR";
  private static final String SCHEMA_CACHE_DIR_ENV = "DEDIREN_SCHEMA_CACHE_DIR";
  private static final String SCHEMA_FETCHER = "curl";
  private static final List<String> OFFICIAL_OEF_SCHEMA_FILES =
      List.of("archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd");

  private Main() {}

  public static String moduleName() {
    return "archimate-oef-export";
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args, System.in, System.out, System.err, System.getenv());
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static PluginResult executeForTesting(String[] args, String stdin, Map<String, String> env)
      throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            env);
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args,
      InputStream stdin,
      PrintStream stdout,
      PrintStream stderr,
      Map<String, String> env)
      throws Exception {
    if (args.length > 0 && args[0].equals("capabilities")) {
      stdout.println(capabilitiesJson());
      return 0;
    }
    if (args.length > 0 && args[0].equals("export")) {
      return exportFromStdin(stdin, stdout, env);
    }
    stderr.println("expected command: capabilities or export");
    return 2;
  }

  private static String capabilitiesJson() throws IOException {
    ObjectNode root = JsonSupport.objectMapper().createObjectNode();
    root.put("plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION);
    root.put("id", "archimate-oef");
    root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("export"));
    ObjectNode runtime = root.putObject("runtime");
    runtime.put("artifact_kind", "archimate-oef+xml");
    runtime.put("archimate_version", "3.2");
    runtime.put("oef_namespace", OEF_NS);
    ObjectNode schemaValidation = runtime.putObject("schema_validation");
    schemaValidation.put("kind", "official-oef-xsd");
    schemaValidation.put("schema_version", "3.1");
    schemaValidation.put("validator", OEF_SCHEMA_VALIDATOR);
    schemaValidation.put("available", commandAvailable(OEF_SCHEMA_VALIDATOR));
    schemaValidation.put("schema_source", "DEDIREN_OEF_SCHEMA_DIR or runtime cache download");
    schemaValidation.put("schema_dir_env", OEF_SCHEMA_DIR_ENV);
    schemaValidation.put("cache_dir_env", SCHEMA_CACHE_DIR_ENV);
    schemaValidation.put("fetcher", SCHEMA_FETCHER);
    return JsonSupport.objectMapper().writeValueAsString(root);
  }

  private static boolean commandAvailable(String command) {
    try {
      return new ProcessBuilder(command, "--version").start().waitFor() == 0;
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private static int exportFromStdin(InputStream stdin, PrintStream stdout, Map<String, String> env)
      throws Exception {
    ExportRequest request =
        JsonSupport.objectMapper().readValue(stdin.readAllBytes(), ExportRequest.class);
    OefExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), OefExportPolicy.class);
    } catch (IllegalArgumentException error) {
      return exitWithDiagnostic(stdout, "DEDIREN_OEF_POLICY_INVALID", error.getMessage(), "policy");
    }

    try {
      validateArchimateTypes(request);
      validateArchimateJunctionSemantics(request);
      validateArchimateGroupSemantics(request);
      validateLayoutReferences(request);
    } catch (ArchimateTypeValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (ArchimateJunctionValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (GroupSemanticValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
    } catch (OefReferenceValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
    }

    String content = buildOef(request, policy);
    try {
      validateOfficialOefSchema(content, env);
    } catch (OefSchemaValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), "content");
    }

    stdout.println(
        JsonSupport.objectMapper()
            .writeValueAsString(
                CommandEnvelope.ok(
                    new ExportResult(
                        ContractVersions.EXPORT_RESULT_SCHEMA_VERSION,
                        "archimate-oef+xml",
                        content))));
    return 0;
  }

  private static void validatePolicy(JsonNode policy) {
    if (policy == null || !policy.isObject()) {
      throw new IllegalArgumentException("policy must be an object");
    }
    for (String field :
        List.of(
            "oef_export_policy_schema_version",
            "model_identifier",
            "model_name",
            "view_identifier",
            "view_name",
            "viewpoint")) {
      if (!policy.hasNonNull(field) || !policy.get(field).isTextual()) {
        throw new IllegalArgumentException("policy missing required string field " + field);
      }
    }
  }

  private static void validateArchimateTypes(ExportRequest request)
      throws ArchimateTypeValidationException {
    var nodeTypes = new HashMap<String, String>();
    for (int nodeIndex = 0; nodeIndex < request.source().nodes().size(); nodeIndex++) {
      var node = request.source().nodes().get(nodeIndex);
      Archimate.validateElementType(node.type(), "$.source.nodes[" + nodeIndex + "].type");
      nodeTypes.put(node.id(), node.type());
    }
    for (int relationshipIndex = 0;
        relationshipIndex < request.source().relationships().size();
        relationshipIndex++) {
      var relationship = request.source().relationships().get(relationshipIndex);
      Archimate.validateRelationshipType(
          relationship.type(), "$.source.relationships[" + relationshipIndex + "].type");
      String sourceType = nodeTypes.get(relationship.source());
      String targetType = nodeTypes.get(relationship.target());
      if (sourceType == null || targetType == null) {
        continue;
      }
      Archimate.validateRelationshipEndpointTypes(
          relationship.type(),
          sourceType,
          targetType,
          "$.source.relationships[" + relationshipIndex + "]");
    }
  }

  private static void validateArchimateJunctionSemantics(ExportRequest request)
      throws ArchimateJunctionValidationException {
    var nodes = new java.util.ArrayList<JunctionValidationNode>();
    for (int index = 0; index < request.source().nodes().size(); index++) {
      var node = request.source().nodes().get(index);
      nodes.add(
          new JunctionValidationNode(node.id(), node.type(), "$.source.nodes[" + index + "]"));
    }
    var relationships =
        request.source().relationships().stream()
            .map(
                relationship ->
                    new JunctionValidationRelationship(
                        relationship.type(), relationship.source(), relationship.target()))
            .toList();
    Archimate.validateJunctionRelationshipSemantics(nodes, relationships);
  }

  private static void validateArchimateGroupSemantics(ExportRequest request)
      throws GroupSemanticValidationException {
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
    for (int index = 0; index < request.layoutResult().groups().size(); index++) {
      LaidOutGroup group = request.layoutResult().groups().get(index);
      String sourceId = semanticGroupSourceId(group);
      if (sourceId == null) {
        continue;
      }
      SourceNode sourceNode = sourceNodesById.get(sourceId);
      if (sourceNode == null) {
        continue;
      }
      if (!sourceNode.type().equals("Grouping")) {
        throw new GroupSemanticValidationException(
            "DEDIREN_ARCHIMATE_GROUP_SOURCE_NOT_GROUPING",
            "$.layout_result.groups[" + index + "].provenance",
            "layout group "
                + group.id()
                + " semantic source "
                + sourceId
                + " has ArchiMate type "
                + sourceNode.type()
                + ", expected Grouping");
      }
    }
  }

  private static void validateLayoutReferences(ExportRequest request)
      throws OefReferenceValidationException {
    var sourceNodeIds =
        request.source().nodes().stream().map(SourceNode::id).collect(Collectors.toSet());
    var sourceRelationshipIds =
        request.source().relationships().stream()
            .map(SourceRelationship::id)
            .collect(Collectors.toSet());
    for (int index = 0; index < request.layoutResult().nodes().size(); index++) {
      var node = request.layoutResult().nodes().get(index);
      if (!sourceNodeIds.contains(node.sourceId())) {
        throw new OefReferenceValidationException(
            "$.layout_result.nodes[" + index + "].source_id",
            "Layout node '"
                + node.id()
                + "' references missing source node '"
                + node.sourceId()
                + "' while exporting ArchiMate OEF");
      }
    }
    for (int index = 0; index < request.layoutResult().edges().size(); index++) {
      var edge = request.layoutResult().edges().get(index);
      if (!sourceRelationshipIds.contains(edge.sourceId())) {
        throw new OefReferenceValidationException(
            "$.layout_result.edges[" + index + "].source_id",
            "Layout edge '"
                + edge.id()
                + "' references missing source relationship '"
                + edge.sourceId()
                + "' while exporting ArchiMate OEF");
      }
    }
  }

  private static String buildOef(ExportRequest request, OefExportPolicy policy) {
    var ids = new IdentifierMap();
    var elementIds = new HashMap<String, String>();
    request.source().nodes().forEach(node -> elementIds.put(node.id(), ids.oefId("el", node.id())));
    var relationshipIds = new HashMap<String, String>();
    request
        .source()
        .relationships()
        .forEach(
            relationship ->
                relationshipIds.put(relationship.id(), ids.oefId("rel", relationship.id())));
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
    var semanticGroups =
        request.layoutResult().groups().stream()
            .filter(
                group -> {
                  String sourceId = semanticGroupSourceId(group);
                  SourceNode sourceNode = sourceId == null ? null : sourceNodesById.get(sourceId);
                  return sourceNode != null && sourceNode.type().equals("Grouping");
                })
            .toList();
    var viewNodeIds = new HashMap<String, String>();
    request
        .layoutResult()
        .nodes()
        .forEach(
            node ->
                viewNodeIds.put(
                    node.id(), ids.oefId("vn-" + request.layoutResult().viewId(), node.id())));
    var groupViewNodeIds = new HashMap<String, String>();
    semanticGroups.forEach(
        group ->
            groupViewNodeIds.put(
                group.id(), ids.oefId("vg-" + request.layoutResult().viewId(), group.id())));
    var viewConnectionIds = new HashMap<String, String>();
    request
        .layoutResult()
        .edges()
        .forEach(
            edge ->
                viewConnectionIds.put(
                    edge.id(), ids.oefId("vc-" + request.layoutResult().viewId(), edge.id())));

    StringBuilder xml = new StringBuilder();
    xml.append("<model xmlns=\"")
        .append(OEF_NS)
        .append("\" xmlns:xsi=\"")
        .append(XSI_NS)
        .append("\" xsi:schemaLocation=\"")
        .append(OEF_NS)
        .append(" ")
        .append(OEF_SCHEMA)
        .append("\" identifier=\"")
        .append(attr(policy.modelIdentifier()))
        .append("\">");
    writeTextElement(xml, "name", policy.modelName());
    xml.append("<elements>");
    for (var node : request.source().nodes()) {
      xml.append("<element identifier=\"")
          .append(attr(elementIds.get(node.id())))
          .append("\" xsi:type=\"")
          .append(attr(node.type()))
          .append("\">");
      writeTextElement(xml, "name", node.label());
      xml.append("</element>");
    }
    xml.append("</elements>");

    xml.append("<relationships>");
    for (var relationship : request.source().relationships()) {
      xml.append("<relationship identifier=\"")
          .append(attr(relationshipIds.get(relationship.id())))
          .append("\" source=\"")
          .append(attr(elementIds.get(relationship.source())))
          .append("\" target=\"")
          .append(attr(elementIds.get(relationship.target())))
          .append("\" xsi:type=\"")
          .append(attr(relationship.type()))
          .append("\">");
      writeTextElement(xml, "name", relationship.label());
      xml.append("</relationship>");
    }
    xml.append("</relationships>");

    xml.append("<views><diagrams><view identifier=\"")
        .append(attr(policy.viewIdentifier()))
        .append("\" xsi:type=\"Diagram\" viewpoint=\"")
        .append(attr(policy.viewpoint()))
        .append("\">");
    writeTextElement(xml, "name", policy.viewName());
    for (var group : semanticGroups) {
      String sourceId = semanticGroupSourceId(group);
      xml.append("<node identifier=\"")
          .append(attr(groupViewNodeIds.get(group.id())))
          .append("\" xsi:type=\"Element\" elementRef=\"")
          .append(attr(elementIds.get(sourceId)))
          .append("\" x=\"")
          .append(formatNumber(group.x()))
          .append("\" y=\"")
          .append(formatNumber(group.y()))
          .append("\" w=\"")
          .append(formatNumber(group.width()))
          .append("\" h=\"")
          .append(formatNumber(group.height()))
          .append("\"/>");
    }
    for (var node : request.layoutResult().nodes()) {
      xml.append("<node identifier=\"")
          .append(attr(viewNodeIds.get(node.id())))
          .append("\" xsi:type=\"Element\" elementRef=\"")
          .append(attr(elementIds.get(node.sourceId())))
          .append("\" x=\"")
          .append(formatNumber(node.x()))
          .append("\" y=\"")
          .append(formatNumber(node.y()))
          .append("\" w=\"")
          .append(formatNumber(node.width()))
          .append("\" h=\"")
          .append(formatNumber(node.height()))
          .append("\"/>");
    }
    for (var edge : request.layoutResult().edges()) {
      xml.append("<connection identifier=\"")
          .append(attr(viewConnectionIds.get(edge.id())))
          .append("\" xsi:type=\"Relationship\" relationshipRef=\"")
          .append(attr(relationshipIds.get(edge.sourceId())))
          .append("\" source=\"")
          .append(attr(viewNodeIds.get(edge.source())))
          .append("\" target=\"")
          .append(attr(viewNodeIds.get(edge.target())))
          .append("\">");
      writeConnectionGeometry(xml, edge.points());
      xml.append("</connection>");
    }
    xml.append("</view></diagrams></views></model>\n");
    return xml.toString();
  }

  private static void writeConnectionGeometry(StringBuilder xml, List<Point> points) {
    if (points == null || points.isEmpty()) {
      return;
    }
    writeLocation(xml, "sourceAttachment", points.get(0));
    for (int index = 1; index < points.size() - 1; index++) {
      writeLocation(xml, "bendpoint", points.get(index));
    }
    writeLocation(xml, "targetAttachment", points.get(points.size() - 1));
  }

  private static void writeLocation(StringBuilder xml, String elementName, Point point) {
    xml.append("<")
        .append(elementName)
        .append(" x=\"")
        .append(formatNumber(point.x()))
        .append("\" y=\"")
        .append(formatNumber(point.y()))
        .append("\"/>");
  }

  private static void validateOfficialOefSchema(String content, Map<String, String> env)
      throws OefSchemaValidationException {
    Path schemaDir = resolveOfficialOefSchemaDir(env);
    Path schemaPath = schemaDir.resolve("archimate3_Diagram.xsd");
    String validator = oefSchemaValidator(env);
    Process process;
    try {
      process =
          new ProcessBuilder(
                  validator, "--nonet", "--noout", "--schema", schemaPath.toString(), "-")
              .start();
    } catch (IOException error) {
      throw new OefSchemaValidationException(
          DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to run official OEF schema validator " + validator + ": " + error.getMessage());
    }
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException error) {
      throw new OefSchemaValidationException(
          DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to write OEF XML to official OEF schema validator "
              + validator
              + ": "
              + error.getMessage());
    }
    try {
      byte[] stdout = process.getInputStream().readAllBytes();
      byte[] stderr = process.getErrorStream().readAllBytes();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        return;
      }
      throw new OefSchemaValidationException(
          "DEDIREN_OEF_SCHEMA_INVALID",
          "generated OEF XML does not validate against the official OEF schema: "
              + SchemaCacheModule.commandOutputDetails(validator, exitCode, stdout, stderr));
    } catch (IOException error) {
      throw new OefSchemaValidationException(
          DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to read official OEF schema validator output: " + error.getMessage());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new OefSchemaValidationException(
          DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "official OEF schema validator interrupted");
    }
  }

  private static String oefSchemaValidator(Map<String, String> env) {
    String configured = env.get("DEDIREN_OEF_SCHEMA_VALIDATOR");
    return configured == null || configured.isBlank() ? OEF_SCHEMA_VALIDATOR : configured;
  }

  private static Path resolveOfficialOefSchemaDir(Map<String, String> env)
      throws OefSchemaValidationException {
    Optional<Path> configured = SchemaCacheModule.nonEmptyEnvPath(env, OEF_SCHEMA_DIR_ENV);
    if (configured.isPresent()) {
      ensureOefSchemaFilesExist(configured.get());
      return configured.get();
    }
    Path schemaDir;
    try {
      schemaDir =
          SchemaCacheModule.schemaCacheBaseDir(env, SCHEMA_CACHE_DIR_ENV, OEF_SCHEMA_DIR_ENV)
              .resolve("opengroup")
              .resolve("archimate")
              .resolve("3.1");
      for (String fileName : OFFICIAL_OEF_SCHEMA_FILES) {
        SchemaCacheModule.ensureCachedSchemaFile(
            schemaDir.resolve(fileName),
            URI.create(OEF_SCHEMA_BASE_URL + "/" + fileName),
            "official OEF schema",
            SchemaCacheModule.curlFetcher(SCHEMA_FETCHER));
      }
    } catch (SchemaCacheException error) {
      throw new OefSchemaValidationException("DEDIREN_OEF_SCHEMA_UNAVAILABLE", error.getMessage());
    }
    return schemaDir;
  }

  private static void ensureOefSchemaFilesExist(Path schemaDir)
      throws OefSchemaValidationException {
    for (String fileName : OFFICIAL_OEF_SCHEMA_FILES) {
      Path schemaPath = schemaDir.resolve(fileName);
      if (!SchemaCacheModule.isNonEmptyFile(schemaPath)) {
        throw new OefSchemaValidationException(
            "DEDIREN_OEF_SCHEMA_UNAVAILABLE",
            "official OEF schema file "
                + schemaPath
                + " is missing or empty; provide all ArchiMate 3.1 OEF XSD files or unset "
                + OEF_SCHEMA_DIR_ENV
                + " to allow cache download");
      }
    }
  }

  private static int exitWithDiagnostic(
      PrintStream stdout, String code, String message, String path) throws IOException {
    var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    return 3;
  }

  private static void writeTextElement(StringBuilder xml, String name, String text) {
    xml.append("<")
        .append(name)
        .append(" xml:lang=\"en\">")
        .append(text(text))
        .append("</")
        .append(name)
        .append(">");
  }

  private static String semanticGroupSourceId(LaidOutGroup group) {
    if (group.provenance() == null) {
      return group.sourceId();
    }
    if (group.provenance().visualOnly()) {
      return null;
    }
    String sourceId = group.provenance().semanticSourceId();
    return sourceId == null ? group.sourceId() : sourceId;
  }

  private static String formatNumber(double value) {
    return Long.toString(Math.round(value));
  }

  private static String attr(String value) {
    return text(value).replace("\"", "&quot;");
  }

  private static String text(String value) {
    return (value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private static final class IdentifierMap {
    private final Set<String> used = new HashSet<>();

    String oefId(String prefix, String value) {
      String base = "id-" + slug(prefix) + "-" + slug(value);
      if (used.add(base)) {
        return base;
      }
      int suffix = 2;
      while (true) {
        String candidate = base + "-" + suffix;
        if (used.add(candidate)) {
          return candidate;
        }
        suffix++;
      }
    }
  }

  private static String slug(String value) {
    StringBuilder result = new StringBuilder();
    boolean previousDash = false;
    for (char character : value.toCharArray()) {
      if (Character.isLetterOrDigit(character) && character < 128) {
        result.append(Character.toLowerCase(character));
        previousDash = false;
      } else if (!previousDash) {
        result.append("-");
        previousDash = true;
      }
    }
    String trimmed = result.toString().replaceAll("^-+|-+$", "");
    return trimmed.isEmpty() ? "item" : trimmed;
  }

  private static final class GroupSemanticValidationException extends Exception {
    private final String code;
    private final String path;

    private GroupSemanticValidationException(String code, String path, String message) {
      super(message);
      this.code = code;
      this.path = path;
    }

    String code() {
      return code;
    }

    String path() {
      return path;
    }
  }

  private static final class OefReferenceValidationException extends Exception {
    private static final String CODE = "DEDIREN_OEF_LAYOUT_REFERENCE_MISSING";
    private final String path;

    private OefReferenceValidationException(String path, String message) {
      super(message);
      this.path = path;
    }

    String code() {
      return CODE;
    }

    String path() {
      return path;
    }
  }

  private static final class OefSchemaValidationException extends Exception {
    private final String code;

    private OefSchemaValidationException(String code, String message) {
      super(message);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}

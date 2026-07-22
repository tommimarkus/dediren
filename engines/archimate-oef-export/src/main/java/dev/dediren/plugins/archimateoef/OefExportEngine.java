package dev.dediren.plugins.archimateoef;

import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateJunctionValidationException;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.archimate.JunctionValidationNode;
import dev.dediren.archimate.JunctionValidationRelationship;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.export.OefExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutGroups;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.engine.XmlIds;
import dev.dediren.engine.XmlText;
import dev.dediren.schemacache.InJvmXmlValidator;
import dev.dediren.schemacache.SchemaCacheException;
import dev.dediren.schemacache.SchemaCacheModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link ExportEngine} that emits an ArchiMate Open Exchange Format (OEF) XML artifact
 * from a source model and its laid-out view. Extracted from {@code Main}'s export orchestration
 * (policy validation, ArchiMate type/junction/group/reference validation, OEF XML build, and
 * official-XSD schema validation), preserving every published diagnostic code and exit code.
 *
 * <p>Schema and cache paths arrive through an explicit {@code productRoot}: a relative {@code
 * DEDIREN_OEF_SCHEMA_DIR} / {@code DEDIREN_SCHEMA_CACHE_DIR} resolves against that root rather than
 * the JVM cwd, so an in-memory build path can supply the product root directly. The engine reads
 * only the env map handed to {@link #export}; it never touches the ambient process environment.
 */
public final class OefExportEngine implements ExportEngine {
  static final String OEF_NS = "http://www.opengroup.org/xsd/archimate/3.0/";
  private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";
  // A Dediren OEF always carries a <views>/<diagrams> element, which the model-only
  // archimate3_Model.xsd rejects ("element views: not expected"); the diagram-bearing
  // archimate3_Diagram.xsd is the schema the document actually conforms to, and it is the
  // schema this plugin validates against below, so the declared schemaLocation must name it too
  // (issue #34).
  private static final String OEF_SCHEMA =
      "http://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd";
  private static final String OEF_SCHEMA_BASE_URL = "https://www.opengroup.org/xsd/archimate/3.1";
  static final String OEF_SCHEMA_DIR_ENV = "DEDIREN_OEF_SCHEMA_DIR";
  // Offline gate: the three OEF XSDs are required in DEDIREN_OEF_SCHEMA_DIR. The W3C xml.xsd the
  // real Open Group set imports stays optional-until-needed there (self-contained stub sets do
  // not reference it); when it is missing the in-JVM compile fails naming it.
  private static final List<String> OFFICIAL_OEF_SCHEMA_FILES =
      List.of("archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd");

  private record PinnedSchemaFile(String fileName, String url, String sha256, String label) {}

  // One table drives the cache-lane fetch: file, origin, and pinned SHA-256 (verified after every
  // runtime download, audit finding F2) stay in a single row per schema. OEF XSDs retrieved
  // 2026-07-04 from opengroup.org; the W3C xml namespace schema (which the official XSDs import
  // and the in-JVM validator resolves local-only) retrieved 2026-07-22 from w3.org.
  private static final List<PinnedSchemaFile> PINNED_OEF_SCHEMA_SET =
      List.of(
          new PinnedSchemaFile(
              "archimate3_Model.xsd",
              OEF_SCHEMA_BASE_URL + "/archimate3_Model.xsd",
              "dd451abe3e3193f91dd9544b279af9bbbf17e75ff1ef86f65ad52b3f8cd29794",
              "official OEF schema"),
          new PinnedSchemaFile(
              "archimate3_View.xsd",
              OEF_SCHEMA_BASE_URL + "/archimate3_View.xsd",
              "d708ce176403034b1229b892712cfd69660aefe17da4cc54acea1ac35e4a9854",
              "official OEF schema"),
          new PinnedSchemaFile(
              "archimate3_Diagram.xsd",
              OEF_SCHEMA_BASE_URL + "/archimate3_Diagram.xsd",
              "6419080f4c4bc43b4a7b8acf870146a7bae6c3487a3ce08d3c521c028ea6056e",
              "official OEF schema"),
          new PinnedSchemaFile(
              "xml.xsd",
              "https://www.w3.org/2001/xml.xsd",
              "61960fb3131e38022caad5360e2f33a3382578ab3c80cd58bd74320ede61b20c",
              "W3C xml namespace schema"));

  // The proxy half is the shared SchemaCacheModule.PROXY_REMEDIATION (issue #35); only the
  // offline-placement tail is this engine's own. Appended to DEDIREN_OEF_SCHEMA_UNAVAILABLE so an
  // agent can recover from stdout JSON alone.
  private static final String OEF_SCHEMA_DOWNLOAD_REMEDIATION =
      SchemaCacheModule.PROXY_REMEDIATION
          + " To skip the download, pre-fetch the ArchiMate 3.1"
          + " OEF XSD files (plus the W3C xml.xsd they import) and set DEDIREN_OEF_SCHEMA_DIR to"
          + " their absolute directory.";
  // The offline lane never downloads, so its failures get placement advice, not proxy advice.
  private static final String OEF_SCHEMA_OFFLINE_REMEDIATION =
      "Add the named file to the DEDIREN_OEF_SCHEMA_DIR directory — the real Open Group XSD set"
          + " also needs the W3C xml.xsd it imports.";

  @Override
  public String id() {
    return "archimate-oef";
  }

  /**
   * Converts export-request bytes to the typed record the engine consumes. The OEF export publishes
   * no dedicated parse-failure envelope, so a malformed stream surfaces as today's raw
   * (non-enveloped) failure by letting the underlying parse exception propagate.
   */
  public ExportRequest parseRequest(byte[] input) {
    return JsonSupport.objectMapper().readValue(input, ExportRequest.class);
  }

  @Override
  public EngineResult<ExportResult> export(
      ExportRequest request, Map<String, String> env, Path productRoot) throws EngineException {
    OefExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), OefExportPolicy.class);
    } catch (IllegalArgumentException error) {
      throw failure(DiagnosticCode.OEF_POLICY_INVALID.code(), error.getMessage(), "policy");
    }

    try {
      validateArchimateTypes(request);
      validateArchimateJunctionSemantics(request);
      validateArchimateGroupSemantics(request);
      validateLayoutReferences(request);
    } catch (ArchimateTypeValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (ArchimateJunctionValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (GroupSemanticValidationException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    } catch (OefReferenceValidationException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    }

    String content = buildOef(request, policy);
    Diagnostic conformance;
    try {
      conformance = validateOfficialOefSchema(content, env, productRoot);
    } catch (OefSchemaValidationException error) {
      throw failure(error.code(), error.getMessage(), "content");
    }

    var result =
        new ExportResult(
            ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, "archimate-oef+xml", content);
    return new EngineResult<>(
        result,
        withConformance(
            withIdentityTripwire(policy, viewCoverageDiagnostics(request)), conformance));
  }

  /**
   * The whole-model export: one OEF document carrying every supplied laid-out view, each with its
   * own identity ({@link #resolveViewIdentity}). Elements and relationships already cover the full
   * source in every OEF, so no {@code OEF_VIEWS_OMITTED} disclosure applies here — nothing is
   * omitted. Validation mirrors the single-view lane per view, and the composed document passes the
   * same official-XSD check.
   */
  @Override
  public java.util.Optional<EngineResult<ExportResult>> exportModel(
      ModelExportRequest request, Map<String, String> env, Path productRoot)
      throws EngineException {
    if (request.views().isEmpty()) {
      return java.util.Optional.empty();
    }
    OefExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), OefExportPolicy.class);
    } catch (IllegalArgumentException error) {
      throw failure(DiagnosticCode.OEF_POLICY_INVALID.code(), error.getMessage(), "policy");
    }
    try {
      for (ModelExportRequest.ViewLayout view : request.views()) {
        ExportRequest perView =
            new ExportRequest(
                ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
                request.source(),
                view.layout(),
                request.policy());
        validateArchimateTypes(perView);
        validateArchimateJunctionSemantics(perView);
        validateArchimateGroupSemantics(perView);
        validateLayoutReferences(perView);
      }
    } catch (ArchimateTypeValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (ArchimateJunctionValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (GroupSemanticValidationException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    } catch (OefReferenceValidationException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    }

    String content = buildModelOef(request, policy);
    Diagnostic conformance;
    try {
      conformance = validateOfficialOefSchema(content, env, productRoot);
    } catch (OefSchemaValidationException error) {
      throw failure(error.code(), error.getMessage(), "content");
    }
    var result =
        new ExportResult(
            ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, "archimate-oef+xml", content);
    return java.util.Optional.of(
        new EngineResult<>(
            result, withConformance(withIdentityTripwire(policy, List.of()), conformance)));
  }

  /**
   * Keep in lockstep with {@code fixtures/export-policy/default-oef.json}; the drift-pin test fails
   * if the shipped default changes without this constant.
   */
  static final String PLACEHOLDER_MODEL_IDENTIFIER = "id-dediren-oef-basic-model";

  /**
   * The shipped default policy hard-codes fixture identity and export succeeds with it unchanged,
   * so a copied-but-unedited policy silently ships a mis-identified OEF. Appends a warning when the
   * model identifier still equals the shipped placeholder — warning, not error: the export stays
   * usable and the caller decides.
   */
  private static List<Diagnostic> withIdentityTripwire(
      OefExportPolicy policy, List<Diagnostic> diagnostics) {
    if (!PLACEHOLDER_MODEL_IDENTIFIER.equals(policy.modelIdentifier())) {
      return diagnostics;
    }
    var combined = new ArrayList<>(diagnostics);
    combined.add(
        new Diagnostic(
            DiagnosticCode.EXPORT_IDENTITY_PLACEHOLDER.code(),
            DiagnosticSeverity.WARNING,
            "export policy still carries the shipped fixture identity (model_identifier '"
                + PLACEHOLDER_MODEL_IDENTIFIER
                + "'); copy the default policy and replace its identity fields for a real model",
            "policy.model_identifier"));
    return combined;
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }

  /**
   * Declares any ArchiMate views the source defines that this OEF does not carry. A Dediren OEF
   * export renders exactly the one laid-out view it is handed, so a package with several views
   * would silently lose diagrams; instead the omission is surfaced as an {@code info} diagnostic a
   * consumer can read from stdout JSON alone (issue #34, following the #32 uml-xmi coverage
   * precedent). The verdict is informational, not a failure, so the envelope {@code status} stays
   * {@code ok}.
   */
  private static List<Diagnostic> viewCoverageDiagnostics(ExportRequest request) {
    List<GenericGraphView> declaredViews = declaredViews(request);
    if (declaredViews.isEmpty()) {
      return List.of();
    }
    // Compare declared view ids against the one exported view rather than gating on the declared
    // count: a source that declares a single view whose id is not the exported view is still a
    // silent diagram loss, so it must be declared too (issue #34).
    String exportedViewId = request.layoutResult().viewId();
    List<String> omitted =
        declaredViews.stream()
            .map(GenericGraphView::id)
            .filter(id -> id != null && !id.equals(exportedViewId))
            .distinct()
            .sorted()
            .toList();
    if (omitted.isEmpty()) {
      return List.of();
    }
    return List.of(
        new Diagnostic(
            DiagnosticCode.OEF_VIEWS_OMITTED.code(),
            DiagnosticSeverity.INFO,
            omitted.size()
                + " of "
                + declaredViews.size()
                + " ArchiMate views declared in the source are not represented in this OEF"
                + " (omitted: "
                + String.join(", ", omitted)
                + "). This export covers the single laid-out view '"
                + exportedViewId
                + "'; export the other views to represent them.",
            "source.plugins.generic-graph.views"));
  }

  private static List<GenericGraphView> declaredViews(ExportRequest request) {
    JsonNode pluginData = request.source().plugins().get("generic-graph");
    if (pluginData == null || !pluginData.isObject()) {
      return List.of();
    }
    try {
      return JsonSupport.objectMapper()
          .convertValue(pluginData, GenericGraphPluginData.class)
          .views();
    } catch (JacksonException error) {
      // Malformed generic-graph plugin data is not this export's concern to reject; the pipeline
      // validates source structure upstream. Absent a readable view list, declare no omissions.
      return List.of();
    }
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
    var nodes = new ArrayList<JunctionValidationNode>();
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
            DiagnosticCode.ARCHIMATE_GROUP_SOURCE_NOT_GROUPING.code(),
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
    var propertyDefinitionIds = collectPropertyDefinitionIds(request, ids);
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));

    StringBuilder xml = new StringBuilder();
    openModel(xml, request, policy, elementIds, relationshipIds, propertyDefinitionIds);
    xml.append("<views><diagrams>");
    // The single-view lane keeps the legacy top-level identity fields verbatim (unchanged
    // published behavior); per-view resolution applies on the whole-model lane only.
    writeViewBody(
        xml,
        request.layoutResult(),
        new OefExportPolicy.ViewIdentity(
            policy.viewIdentifier(), policy.viewName(), policy.viewpoint()),
        ids,
        elementIds,
        relationshipIds,
        sourceNodesById);
    xml.append("</diagrams></views></model>\n");
    return xml.toString();
  }

  /** One document carrying every supplied laid-out view; see {@link #exportModel}. */
  private static String buildModelOef(ModelExportRequest request, OefExportPolicy policy) {
    ExportRequest first =
        new ExportRequest(
            ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
            request.source(),
            request.views().getFirst().layout(),
            request.policy());
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
    var propertyDefinitionIds = collectPropertyDefinitionIds(first, ids);
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));

    var viewLabels = new HashMap<String, String>();
    for (GenericGraphView declared : declaredViews(first)) {
      viewLabels.put(declared.id(), declared.label());
    }

    StringBuilder xml = new StringBuilder();
    openModel(xml, first, policy, elementIds, relationshipIds, propertyDefinitionIds);
    xml.append("<views><diagrams>");
    for (ModelExportRequest.ViewLayout view : request.views()) {
      writeViewBody(
          xml,
          view.layout(),
          resolveViewIdentity(policy, view.viewId(), viewLabels.get(view.viewId())),
          ids,
          elementIds,
          relationshipIds,
          sourceNodesById);
    }
    xml.append("</diagrams></views></model>\n");
    return xml.toString();
  }

  /**
   * Whole-model identity resolution: explicit {@code views[id]} override wins field by field, then
   * the source-derived default ({@code id-view-<view-id>}, the view's own label, the policy's
   * top-level viewpoint) — never the legacy top-level view identity, which would repeat one
   * identity across views (the per-build "Phase-1 limitation" this feature removes).
   */
  private static OefExportPolicy.ViewIdentity resolveViewIdentity(
      OefExportPolicy policy, String viewId, String sourceLabel) {
    OefExportPolicy.ViewIdentity override =
        policy.views().getOrDefault(viewId, new OefExportPolicy.ViewIdentity(null, null, null));
    String identifier =
        override.viewIdentifier() != null ? override.viewIdentifier() : "id-view-" + viewId;
    String name =
        override.viewName() != null
            ? override.viewName()
            : sourceLabel != null ? sourceLabel : viewId;
    String viewpoint = override.viewpoint() != null ? override.viewpoint() : policy.viewpoint();
    return new OefExportPolicy.ViewIdentity(identifier, name, viewpoint);
  }

  private static void openModel(
      StringBuilder xml,
      ExportRequest request,
      OefExportPolicy policy,
      Map<String, String> elementIds,
      Map<String, String> relationshipIds,
      Map<String, String> propertyDefinitionIds) {
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
      writePropertyValues(xml, node.properties(), propertyDefinitionIds);
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
      writePropertyValues(xml, relationship.properties(), propertyDefinitionIds);
      xml.append("</relationship>");
    }
    xml.append("</relationships>");
    writePropertyDefinitions(xml, propertyDefinitionIds);
  }

  private static void writeViewBody(
      StringBuilder xml,
      LayoutResult layout,
      OefExportPolicy.ViewIdentity identity,
      IdentifierMap ids,
      Map<String, String> elementIds,
      Map<String, String> relationshipIds,
      Map<String, SourceNode> sourceNodesById) {
    var semanticGroups =
        layout.groups().stream()
            .filter(
                group -> {
                  String sourceId = semanticGroupSourceId(group);
                  SourceNode sourceNode = sourceId == null ? null : sourceNodesById.get(sourceId);
                  return sourceNode != null && sourceNode.type().equals("Grouping");
                })
            .toList();
    var viewNodeIds = new HashMap<String, String>();
    layout
        .nodes()
        .forEach(node -> viewNodeIds.put(node.id(), ids.oefId("vn-" + layout.viewId(), node.id())));
    var groupViewNodeIds = new HashMap<String, String>();
    semanticGroups.forEach(
        group -> groupViewNodeIds.put(group.id(), ids.oefId("vg-" + layout.viewId(), group.id())));
    var viewConnectionIds = new HashMap<String, String>();
    layout
        .edges()
        .forEach(
            edge ->
                viewConnectionIds.put(edge.id(), ids.oefId("vc-" + layout.viewId(), edge.id())));

    xml.append("<view identifier=\"")
        .append(attr(identity.viewIdentifier()))
        .append("\" xsi:type=\"Diagram\" viewpoint=\"")
        .append(attr(identity.viewpoint()))
        .append("\">");
    writeTextElement(xml, "name", identity.viewName());
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
    for (var node : layout.nodes()) {
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
    for (var edge : layout.edges()) {
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
    xml.append("</view>");
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

  private static Diagnostic validateOfficialOefSchema(
      String content, Map<String, String> env, Path productRoot)
      throws OefSchemaValidationException {
    OefSchemaDirSource source = resolveOfficialOefSchemaDir(env, productRoot);
    Path schemaPath = source.dir().resolve("archimate3_Diagram.xsd");
    // In-JVM validation (wave 3): no xmllint subprocess in the OEF trust path. Schema imports
    // (the W3C xml.xsd the official XSDs reference) resolve local-only from the schema directory;
    // the cache-download lane fetches xml.xsd alongside the OEF XSDs, and an offline directory
    // missing it fails here with a message naming it and the remediation for its own lane.
    InJvmXmlValidator.Outcome outcome;
    try {
      outcome = InJvmXmlValidator.validate(schemaPath, content);
    } catch (SchemaCacheException error) {
      // advice() picks by failure class: placement/proxy remediation only fits a missing or broken
      // schema set; a saturated or timed-out validator says "retry" instead.
      throw new OefSchemaValidationException(
          DiagnosticCode.OEF_SCHEMA_UNAVAILABLE.code(),
          error.getMessage() + " " + error.advice(source.unavailableRemediation()));
    }
    if (outcome.valid()) {
      return new Diagnostic(
          DiagnosticCode.EXPORT_SCHEMA_CONFORMANCE.code(),
          DiagnosticSeverity.INFO,
          source.conformanceMessage(),
          "content");
    }
    throw new OefSchemaValidationException(
        DiagnosticCode.OEF_SCHEMA_INVALID.code(),
        "generated OEF XML does not validate against the official OEF schema: "
            + outcome.details());
  }

  /** Appends the shared conformance report last, after every actionable diagnostic. */
  private static List<Diagnostic> withConformance(
      List<Diagnostic> diagnostics, Diagnostic conformance) {
    var combined = new ArrayList<>(diagnostics);
    combined.add(conformance);
    return combined;
  }

  /**
   * Where the schema set came from decides which remediation a later failure names and how the
   * conformance report describes the schema's provenance.
   */
  private record OefSchemaDirSource(
      Path dir, String unavailableRemediation, String conformanceMessage) {}

  private static OefSchemaDirSource resolveOfficialOefSchemaDir(
      Map<String, String> env, Path productRoot) throws OefSchemaValidationException {
    // Decision 9: a relative schema/cache env path resolves against the product root, not the JVM
    // cwd, so an in-memory build path can supply the product root explicitly. An absolute value is
    // returned unchanged by Path.resolve, so the process path (product root == child cwd) is
    // byte-identical.
    Map<String, String> schemaEnv =
        productRootRelativeEnv(
            env, productRoot, OEF_SCHEMA_DIR_ENV, SchemaCacheModule.SCHEMA_CACHE_DIR_ENV);
    Optional<Path> configured = SchemaCacheModule.nonEmptyEnvPath(schemaEnv, OEF_SCHEMA_DIR_ENV);
    if (configured.isPresent()) {
      ensureOefSchemaFilesExist(configured.get());
      return new OefSchemaDirSource(
          configured.get(),
          OEF_SCHEMA_OFFLINE_REMEDIATION,
          "validated in-JVM against archimate3_Diagram.xsd from the user-supplied"
              + " DEDIREN_OEF_SCHEMA_DIR directory; ArchiMate 3.2 XSDs are unpublished by The Open"
              + " Group");
    }
    Path schemaDir;
    try {
      schemaDir =
          SchemaCacheModule.schemaCacheBaseDir(
                  schemaEnv, SchemaCacheModule.SCHEMA_CACHE_DIR_ENV, OEF_SCHEMA_DIR_ENV)
              .resolve("opengroup")
              .resolve("archimate")
              .resolve("3.1");
      for (PinnedSchemaFile pinned : PINNED_OEF_SCHEMA_SET) {
        SchemaCacheModule.ensureCachedSchemaFile(
            schemaDir.resolve(pinned.fileName()),
            URI.create(pinned.url()),
            pinned.label(),
            pinned.sha256(),
            SchemaCacheModule.curlFetcher(SchemaCacheModule.SCHEMA_FETCHER));
      }
    } catch (SchemaCacheException error) {
      throw new OefSchemaValidationException(
          DiagnosticCode.OEF_SCHEMA_UNAVAILABLE.code(),
          error.getMessage() + " " + OEF_SCHEMA_DOWNLOAD_REMEDIATION);
    }
    return new OefSchemaDirSource(
        schemaDir,
        OEF_SCHEMA_DOWNLOAD_REMEDIATION,
        "validated in-JVM against the pinned Open Group ArchiMate 3.1 archimate3_Diagram.xsd"
            + " (SHA-256-verified download); ArchiMate 3.2 XSDs are unpublished by The Open Group");
  }

  /** Resolves this engine's schema/cache env paths against the product root (Decision 9). */
  static Map<String, String> productRootRelativeEnv(
      Map<String, String> env, Path productRoot, String... pathEnvNames) {
    return SchemaCacheModule.productRootRelativeEnv(env, productRoot, pathEnvNames);
  }

  private static void ensureOefSchemaFilesExist(Path schemaDir)
      throws OefSchemaValidationException {
    for (String fileName : OFFICIAL_OEF_SCHEMA_FILES) {
      Path schemaPath = schemaDir.resolve(fileName);
      if (!SchemaCacheModule.isNonEmptyFile(schemaPath)) {
        throw new OefSchemaValidationException(
            DiagnosticCode.OEF_SCHEMA_UNAVAILABLE.code(),
            "official OEF schema file "
                + schemaPath
                + " is missing or empty; provide all ArchiMate 3.1 OEF XSD files or unset "
                + OEF_SCHEMA_DIR_ENV
                + " to allow cache download");
      }
    }
  }

  /**
   * Assigns one OEF property-definition identifier per distinct property key across every source
   * node and relationship. The Open Group exchange format models properties by reference: each key
   * is declared once in the model-level {@code <propertyDefinitions>} and each value points back at
   * that definition, so a shared key needs a single definition. Keys are gathered in sorted order
   * for deterministic output (a {@link SourceNode#properties()} map has no defined iteration
   * order).
   */
  private static Map<String, String> collectPropertyDefinitionIds(
      ExportRequest request, IdentifierMap ids) {
    var keys = new TreeSet<String>();
    request.source().nodes().forEach(node -> keys.addAll(node.properties().keySet()));
    request.source().relationships().forEach(rel -> keys.addAll(rel.properties().keySet()));
    var definitionIds = new LinkedHashMap<String, String>();
    for (String key : keys) {
      definitionIds.put(key, ids.oefId("prop", key));
    }
    return definitionIds;
  }

  private static void writePropertyDefinitions(
      StringBuilder xml, Map<String, String> propertyDefinitionIds) {
    if (propertyDefinitionIds.isEmpty()) {
      return;
    }
    xml.append("<propertyDefinitions>");
    propertyDefinitionIds.forEach(
        (key, definitionId) -> {
          xml.append("<propertyDefinition identifier=\"")
              .append(attr(definitionId))
              .append("\" type=\"string\">");
          writeTextElement(xml, "name", key);
          xml.append("</propertyDefinition>");
        });
    xml.append("</propertyDefinitions>");
  }

  private static void writePropertyValues(
      StringBuilder xml,
      Map<String, JsonNode> properties,
      Map<String, String> propertyDefinitionIds) {
    if (properties.isEmpty()) {
      return;
    }
    var keys = new ArrayList<>(properties.keySet());
    keys.sort(null);
    var rendered = new StringBuilder();
    for (String key : keys) {
      String definitionId = propertyDefinitionIds.get(key);
      if (definitionId == null) {
        continue;
      }
      rendered
          .append("<property propertyDefinitionRef=\"")
          .append(attr(definitionId))
          .append("\">");
      writeTextElement(rendered, "value", propertyValueText(properties.get(key)));
      rendered.append("</property>");
    }
    if (rendered.length() > 0) {
      xml.append("<properties>").append(rendered).append("</properties>");
    }
  }

  private static String propertyValueText(JsonNode value) {
    if (value == null || value.isNull()) {
      return "";
    }
    return value.isValueNode() ? value.asText() : value.toString();
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
    return LaidOutGroups.semanticSourceId(group);
  }

  private static String formatNumber(double value) {
    return Long.toString(Math.round(value));
  }

  // In-file shorthands only: escaping and slugging are owned by engine-api's XmlText/XmlIds,
  // shared with the uml-xmi engine (engines may not depend on each other).
  private static String attr(String value) {
    return XmlText.attr(value);
  }

  private static String text(String value) {
    return XmlText.text(value);
  }

  private static String slug(String value) {
    return XmlIds.slug(value);
  }

  private static final class IdentifierMap {
    private final Set<String> used = new HashSet<>();

    String oefId(String prefix, String value) {
      return XmlIds.unique(used, "id-" + slug(prefix) + "-" + slug(value));
    }
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
    private static final String CODE = DiagnosticCode.OEF_LAYOUT_REFERENCE_MISSING.code();
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

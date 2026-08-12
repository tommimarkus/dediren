package dev.dediren.plugins.umlxmi;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.genericGraphPluginData;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.plugins.umlxmi.build.Coverage;
import dev.dediren.plugins.umlxmi.schema.SchemaValidation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds the public, machine-readable assurance claim that accompanies each UML/XMI artifact. */
final class UmlXmiAssurance {
  private static final List<String> SUPPORTED_KINDS =
      List.of(
          "uml-class",
          "uml-sequence",
          "uml-state-machine",
          "uml-use-case",
          "uml-component",
          "uml-deployment",
          "uml-activity",
          "uml-data");

  private UmlXmiAssurance() {}

  static JsonNode forView(
      ExportRequest request, Coverage coverage, Map<String, String> environment) {
    GenericGraphPluginData pluginData = genericGraphPluginData(request);
    String viewKind = viewKind(pluginData, request.layoutResult().viewId());
    return assurance("view", List.of(viewKind), coverage, environment);
  }

  static JsonNode forModel(
      ModelExportRequest request, Coverage coverage, Map<String, String> environment) {
    ExportRequest representative =
        new ExportRequest(
            ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
            request.source(),
            request.views().getFirst().layout(),
            request.policy());
    GenericGraphPluginData pluginData = genericGraphPluginData(representative);
    var selectedKinds = new LinkedHashSet<String>();
    for (ModelExportRequest.ViewLayout view : request.views()) {
      selectedKinds.add(viewKind(pluginData, view.viewId()));
    }
    return assurance("model-aggregate", List.copyOf(selectedKinds), coverage, environment);
  }

  private static JsonNode assurance(
      String scope,
      List<String> selectedKinds,
      Coverage coverage,
      Map<String, String> environment) {
    ObjectNode assurance = JsonSupport.objectMapper().createObjectNode();
    assurance.put("assurance_schema_version", ContractVersions.UML_XMI_ASSURANCE_SCHEMA_VERSION);
    var taxonomy = assurance.putArray("kind_taxonomy");
    for (String kind : SUPPORTED_KINDS) {
      boolean aggregateEligible = kind.equals("uml-class") || kind.equals("uml-data");
      ObjectNode entry = taxonomy.addObject();
      entry.put("kind", kind);
      entry.put(
          "classification",
          kind.equals("uml-data") ? "dediren-local-classifier-view" : "standard-uml-diagram-kind");
      if (kind.equals("uml-data")) {
        entry.put("maps_to_uml_diagram_kind", "class");
      }
      entry
          .putObject("scope")
          .put("xmi_abstract_syntax", "selected-view")
          .put("aggregate_model", aggregateEligible ? "included" : "not-included")
          .put("uml_di", aggregateEligible ? "provisional-aggregate" : "none");
    }

    ObjectNode artifactScope = assurance.putObject("artifact_scope");
    artifactScope.put("scope", scope);
    var selectedViewKinds = artifactScope.putArray("selected_view_kinds");
    selectedKinds.forEach(selectedViewKinds::add);

    ObjectNode evidence = assurance.putObject("validation_evidence");
    evidence.put("level", "xmi-envelope-only");
    ObjectNode xmiSchemaEvidence =
        evidence.putObject("xmi_schema_evidence").put("status", "validated");
    xmiSchemaEvidence.put("validator", "in-jvm-xsd");
    String configuredSchema = environment.get(SchemaValidation.XMI_SCHEMA_PATH_ENV);
    if (configuredSchema != null && !configuredSchema.isBlank()) {
      xmiSchemaEvidence.put("source", "user-supplied-schema-path");
    } else {
      xmiSchemaEvidence.put("standard", "OMG XMI 2.5.1");
      xmiSchemaEvidence.put("source", "pinned-sha256-schema-cache");
    }
    evidence.putArray("uml_metamodel_evidence");
    evidence.putArray("importer_evidence");

    ObjectNode coverageNode = assurance.putObject("coverage");
    coveragePartition(
        coverageNode.putObject("represented"),
        coverage.representedNodeTypes(),
        coverage.representedRelationshipTypes());
    coveragePartition(
        coverageNode.putObject("omitted"),
        coverage.omittedNodeTypes(),
        coverage.omittedRelationshipTypes());
    coveragePartition(
        coverageNode.putObject("unrepresented_in_view"),
        coverage.unrepresentedInViewNodeTypes(),
        coverage.unrepresentedInViewRelationshipTypes());
    return assurance;
  }

  private static void coveragePartition(
      ObjectNode partition,
      Map<String, Integer> elementTypes,
      Map<String, Integer> relationshipTypes) {
    ObjectNode elements = partition.putObject("elements");
    elementTypes.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> elements.put(entry.getKey(), entry.getValue()));
    ObjectNode relationships = partition.putObject("relationships");
    relationshipTypes.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> relationships.put(entry.getKey(), entry.getValue()));
    partition.put("element_total", total(elementTypes));
    partition.put("relationship_total", total(relationshipTypes));
  }

  private static int total(Map<String, Integer> typeCounts) {
    return typeCounts.values().stream().mapToInt(Integer::intValue).sum();
  }

  private static String viewKind(GenericGraphPluginData pluginData, String viewId) {
    GenericGraphView view =
        pluginData.views().stream()
            .filter(candidate -> candidate.id().equals(viewId))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("unknown generic-graph view: " + viewId));
    return wireKind(view.kind());
  }

  private static String wireKind(GenericGraphViewKind kind) {
    return switch (kind) {
      case UML_CLASS -> "uml-class";
      case UML_DATA -> "uml-data";
      case UML_ACTIVITY -> "uml-activity";
      case UML_SEQUENCE -> "uml-sequence";
      case UML_STATE_MACHINE -> "uml-state-machine";
      case UML_COMPONENT -> "uml-component";
      case UML_USE_CASE -> "uml-use-case";
      case UML_DEPLOYMENT -> "uml-deployment";
      case GENERIC, ARCHIMATE ->
          throw new IllegalArgumentException("not a UML/XMI view kind: " + kind);
    };
  }
}

package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.SchemaAssertions;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SchemaValidatorTest {
  private static final List<String> PUBLIC_SCHEMAS =
      List.of(
          "schemas/model.schema.json",
          "schemas/envelope.schema.json",
          "schemas/layout-request.schema.json",
          "schemas/layout-result.schema.json",
          "schemas/semantic-validation-result.schema.json",
          "schemas/render-policy.schema.json",
          "schemas/render-metadata.schema.json",
          "schemas/render-result.schema.json",
          "schemas/export-request.schema.json",
          "schemas/export-result.schema.json",
          "schemas/export-result.first-party.schema.json",
          "schemas/oef-export-policy.schema.json",
          "schemas/uml-xmi-export-policy.schema.json",
          "schemas/bundle.schema.json");

  @Test
  void allPublicSchemasCompile() {
    for (String schema : PUBLIC_SCHEMAS) {
      assertThat(SchemaAssertions.compile(workspaceRoot(), schema)).describedAs(schema).isEmpty();
    }
  }

  @Test
  void validSourceMatchesModelSchemaAndAbsoluteGeometryIsRejected() {
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(), "schemas/model.schema.json", "fixtures/source/valid-basic.json"))
        .isEmpty();
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/model.schema.json",
                "fixtures/source/invalid-absolute-geometry.json"))
        .isNotEmpty();
  }

  @Test
  void renderPolicyAcceptsGenericNodeShapeAndRejectsUnknownShape() throws Exception {
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/render-policy.schema.json",
                "fixtures/render-policy/generic-shapes-svg.json"))
        .describedAs("generic node shapes fixture must validate")
        .isEmpty();

    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String template =
        """
        {
          "render_policy_schema_version": "render-policy.schema.v3",
          "page": { "width": 100, "height": 100 },
          "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 },
          "style": { "node": { "shape": "%s" } }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/render-policy.schema.json",
                mapper.readTree(String.format(template, "ellipse"))))
        .describedAs("known node shape must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/render-policy.schema.json",
                mapper.readTree(String.format(template, "blob"))))
        .describedAs("unknown node shape must be rejected by the schema")
        .isNotEmpty();
  }

  @Test
  void renderPolicyRejectsRetiredInteractiveField() throws Exception {
    // interactive-svg was retired: the `interactive` mode field and the `style.interaction` object
    // are gone from render-policy.schema.v3. additionalProperties:false must reject either, so a
    // stale policy fails loudly at the schema boundary rather than being silently ignored.
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String base =
        """
        {
          "render_policy_schema_version": "render-policy.schema.v3",
          "page": { "width": 100, "height": 100 },
          "margin": { "top": 0, "right": 0, "bottom": 0, "left": 0 }%s
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/render-policy.schema.json",
                mapper.readTree(String.format(base, ",\n          \"interactive\": \"svg\""))))
        .describedAs("retired interactive mode field must be rejected")
        .isNotEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/render-policy.schema.json",
                mapper.readTree(
                    String.format(
                        base,
                        ",\n          \"style\": { \"interaction\": { \"highlight_stroke\":"
                            + " \"#fff\" } }"))))
        .describedAs("retired style.interaction object must be rejected")
        .isNotEmpty();
  }

  @Test
  void layoutResultNodeRoleFieldIsOptional() {
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/layout-result.schema.json",
                "fixtures/layout-result/uml-sequence-validatable.json"))
        .describedAs("role-bearing layout-result should validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/layout-result.schema.json",
                "fixtures/layout-result/basic.json"))
        .describedAs("role-less layout-result should still validate")
        .isEmpty();
  }

  @Test
  void exportResultBaseSchemaAcceptsAnyHonestArtifactKind() {
    // The published export-result contract is the base any export plugin can satisfy honestly:
    // artifact_kind is a pattern, not the closed first-party enum.
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.schema.json",
                exportResult("ticket-stats+json")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.schema.json",
                exportResult("archimate-oef+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/export-result.schema.json", exportResult("uml-xmi+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.schema.json",
                exportResult("Not A Valid Kind")))
        .isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Archimate-OEF+xml", // uppercase leading/id characters
        "ticket-stats", // missing +suffix
        "", // empty string
        "ticket-stats+yaml", // unknown suffix
        "tïcket+json" // non-ASCII id character
      })
  void exportResultBaseSchemaRejectsArtifactKindOutsideThePattern(String artifactKind) {
    // Distinct rejection partitions for ^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$: each is rejected
    // independently, not conflated into one bad string.
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/export-result.schema.json", exportResult(artifactKind)))
        .describedAs("artifact_kind=<%s>", artifactKind)
        .isNotEmpty();
  }

  @Test
  void exportResultFirstPartySchemaKeepsClosedArtifactKindEnum() {
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.first-party.schema.json",
                exportResult("archimate-oef+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.first-party.schema.json",
                exportResult("uml-xmi+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.first-party.schema.json",
                exportResult("ticket-stats+json")))
        .isNotEmpty();
  }

  @Test
  void exportRequestPolicyIsPassThroughForAnyExportPlugin() throws Exception {
    // The CLI forwards the --policy document verbatim to the target export plugin, so the
    // published request schema must accept a third-party policy shape, not just the two
    // first-party ones.
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-request.schema.json",
                exportRequest(thirdPartyPolicy())))
        .isEmpty();
    for (String fixture :
        List.of(
            "fixtures/export-policy/default-oef.json",
            "fixtures/export-policy/default-uml-xmi.json")) {
      assertThat(
              SchemaAssertions.validate(
                  workspaceRoot(),
                  "schemas/export-request.schema.json",
                  exportRequest(readJson(fixture))))
          .describedAs(fixture)
          .isEmpty();
    }
  }

  @Test
  void exportRequestRejectsMissingOrNonObjectPolicy() {
    // export-request.schema.json requires `policy` and constrains it to an object. A request that
    // omits policy, or supplies a non-object (string/array/null), must be rejected.
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    var noPolicy = mapper.createObjectNode();
    noPolicy.put("export_request_schema_version", "export-request.schema.v1");
    noPolicy.putObject("source").put("model_schema_version", "model.schema.v1");
    noPolicy
        .putObject("layout_result")
        .put("layout_result_schema_version", "layout-result.schema.v2");
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/export-request.schema.json", noPolicy))
        .describedAs("policy omitted")
        .isNotEmpty();

    List<tools.jackson.databind.JsonNode> nonObjectPolicies =
        List.of(
            mapper.getNodeFactory().textNode("not-an-object"),
            mapper.createArrayNode(),
            mapper.getNodeFactory().nullNode());
    for (tools.jackson.databind.JsonNode badPolicy : nonObjectPolicies) {
      assertThat(
              SchemaAssertions.validate(
                  workspaceRoot(), "schemas/export-request.schema.json", exportRequest(badPolicy)))
          .describedAs("policy=%s", badPolicy.getNodeType())
          .isNotEmpty();
    }
  }

  private static tools.jackson.databind.JsonNode exportRequest(
      tools.jackson.databind.JsonNode policy) {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    var request = mapper.createObjectNode();
    request.put("export_request_schema_version", "export-request.schema.v1");
    request.putObject("source").put("model_schema_version", "model.schema.v1");
    request
        .putObject("layout_result")
        .put("layout_result_schema_version", "layout-result.schema.v2");
    request.set("policy", policy);
    return request;
  }

  private static tools.jackson.databind.JsonNode thirdPartyPolicy() {
    var policy = dev.dediren.contracts.json.JsonSupport.objectMapper().createObjectNode();
    policy.put("ticket_stats_policy", "v1");
    return policy;
  }

  private static tools.jackson.databind.JsonNode readJson(String path) throws Exception {
    return dev.dediren.contracts.json.JsonSupport.objectMapper()
        .readTree(java.nio.file.Files.readString(workspaceRoot().resolve(path)));
  }

  private static tools.jackson.databind.JsonNode exportResult(String artifactKind) {
    var document = dev.dediren.contracts.json.JsonSupport.objectMapper().createObjectNode();
    document.put("export_result_schema_version", "export-result.schema.v1");
    document.put("artifact_kind", artifactKind);
    document.put("content", "{}");
    return document;
  }

  @Test
  void layoutRequestRoutingStyleAcceptsSplineAndRejectsUnknown() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String template =
        """
        {
          "layout_request_schema_version": "layout-request.schema.v2",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [],
          "constraints": [],
          "layout_preferences": { "routing": { "style": "%s" } }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "spline"))))
        .describedAs("spline style must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "curved"))))
        .describedAs("unknown style must be rejected")
        .isNotEmpty();
  }

  @Test
  void layoutRequestAcceptsPhaseStrategiesAndRejectsUnknown() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String template =
        """
        {
          "layout_request_schema_version": "layout-request.schema.v2",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [],
          "constraints": [],
          "layout_preferences": {
            "cycle_breaking": "model-order",
            "layering": { "strategy": "%s" },
            "crossing": { "strategy": "layer-sweep", "greedy_switch": "two-sided" },
            "placement": { "strategy": "network-simplex" }
          }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "coffman-graham"))))
        .describedAs("valid phase strategies must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "bogus-strategy"))))
        .describedAs("unknown layering strategy must be rejected")
        .isNotEmpty();
  }

  @Test
  void layoutRequestAcceptsGraphTuningAndRejectsUnknown() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String template =
        """
        {
          "layout_request_schema_version": "layout-request.schema.v2",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [],
          "constraints": [],
          "layout_preferences": {
            "compaction": "%s",
            "components": { "separate": false, "spacing": "spacious" },
            "high_degree_nodes": "on",
            "thoroughness": "high"
          }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "balanced"))))
        .describedAs("valid graph-tuning must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "squish"))))
        .describedAs("unknown compaction must be rejected")
        .isNotEmpty();
  }

  @Test
  void layoutRequestAcceptsLayeredAlgorithmAndRejectsOthers() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String template =
        """
        {
          "layout_request_schema_version": "layout-request.schema.v2",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [],
          "constraints": [],
          "layout_preferences": { "algorithm": "%s" }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "layered"))))
        .describedAs("layered algorithm must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "tree"))))
        .describedAs("non-layered algorithm is not publicly exposed yet")
        .isNotEmpty();
  }

  @Test
  void nodePlacementHintsValidateAndRejectUnknown() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String sourceTemplate =
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [ { "id": "n1", "type": "Component", "label": "N1", "properties": {},
                      "partition": 1, "layer_constraint": "%s" } ],
          "relationships": [],
          "plugins": {}
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "first"))))
        .describedAs("valid node hints must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "middle"))))
        .describedAs("unknown layer_constraint must be rejected")
        .isNotEmpty();
  }

  @Test
  void edgePriorityHintsValidateAndRejectUnknownKey() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String sourceTemplate =
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [ { "id": "a", "type": "Component", "label": "A", "properties": {} },
                     { "id": "b", "type": "Component", "label": "B", "properties": {} } ],
          "relationships": [ { "id": "e1", "type": "flow", "source": "a", "target": "b",
                               "label": "", "properties": {}, "priority": { %s } } ],
          "plugins": {}
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "\"keep_short\": 2"))))
        .describedAs("valid edge priority must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "\"keep_medium\": 2"))))
        .describedAs("unknown priority key must be rejected")
        .isNotEmpty();
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

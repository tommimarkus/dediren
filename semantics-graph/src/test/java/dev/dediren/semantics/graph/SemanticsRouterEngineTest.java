package dev.dediren.semantics.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.semantics.archimate.ArchimateNotationSemantics;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import dev.dediren.testsupport.SchemaAssertions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Router integration suite for the semantics carve. Consolidates the relocated generic-graph
 * behaviour: the direct-engine base cases (id, profile gating, base projection) plus the
 * envelope-shaped base/routing and engine-seam cases that the retired {@code
 * dev.dediren.plugins.genericgraph.Main}/{@code GenericGraphPluginTest}/{@code
 * GenericGraphEngineTest}/{@code MainTest} tree pinned, driven through the test-only {@link
 * RouterHarness}. The router is wired with all three notations here so archimate/uml routing is
 * exercised end to end; per-notation legality lives in the {@code semantics-archimate}/{@code
 * semantics-uml} suites.
 */
class SemanticsRouterEngineTest {
  private final SemanticsRouterEngine engine =
      new SemanticsRouterEngine(
          Map.of(GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics()));

  // The seam round-trip and raw-parse cases mirror the harness, which wires all three notations.
  private final SemanticsRouterEngine fullEngine =
      new SemanticsRouterEngine(
          Map.of(
              GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics(),
              GenericGraphSemanticProfile.ARCHIMATE, new ArchimateNotationSemantics(),
              GenericGraphSemanticProfile.UML, new UmlNotationSemantics()));

  // --- id + direct-engine profile gating (relocated GenericGraphEngineTest base cases) ----------

  @Test
  void idIsGenericGraph() {
    assertThat(engine.id()).isEqualTo("generic-graph");
  }

  @Test
  void moduleLoads() {
    assertThat(RouterHarness.moduleName()).isEqualTo("generic-graph");
  }

  @Test
  void validateWithoutProfileIsProfileRequired() {
    assertThatThrownBy(() -> engine.validate(source("fixtures/source/valid-basic.json"), null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_SEMANTIC_PROFILE_REQUIRED");
            });
  }

  @Test
  void validateWithUnsupportedProfileIsRejected() {
    assertThatThrownBy(() -> engine.validate(source("fixtures/source/valid-basic.json"), "bogus"))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().path()).isEqualTo("profile");
            });
  }

  @Test
  void projectsBaseProfileLayoutAndRenderMetadata() throws Exception {
    SourceDocument source = source("fixtures/source/valid-basic.json");

    EngineResult<LayoutRequest> layout = engine.projectLayoutRequest(source, "main");
    EngineResult<RenderMetadata> metadata = engine.projectRenderMetadata(source, "main");

    assertThat(layout.value().viewId()).isEqualTo("main");
    assertThat(layout.value().nodes()).hasSize(2);
    assertThat(layout.value().edges()).hasSize(1);
    assertThat(metadata.value().semanticProfile()).isEqualTo("generic-graph");
    assertThat(metadata.value().nodes()).containsKeys("client", "api");
  }

  @Test
  void constructorRejectsNullNotationMap() {
    assertThatThrownBy(() -> new SemanticsRouterEngine(null))
        .isInstanceOf(NullPointerException.class);
  }

  // --- engine-seam envelope round-trip parity (relocated GenericGraphEngineTest) ----------------

  @Test
  void validateArchimateEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] source = fixtureBytes("fixtures/source/valid-archimate-oef.json");

    EngineResult<?> result = fullEngine.validate(fullEngine.parseSource(source), "archimate");

    assertThat(engineTree(result.value()))
        .isEqualTo(processData(new String[] {"validate", "--profile", "archimate"}, source));
  }

  @Test
  void projectLayoutRequestEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] source = fixtureBytes("fixtures/source/valid-basic.json");

    EngineResult<?> result =
        fullEngine.projectLayoutRequest(fullEngine.parseSource(source), "main");

    assertThat(engineTree(result.value()))
        .isEqualTo(
            processData(
                new String[] {"project", "--target", "layout-request", "--view", "main"}, source));
  }

  @Test
  void projectRenderMetadataEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] source = fixtureBytes("fixtures/source/valid-archimate-oef.json");

    EngineResult<?> result =
        fullEngine.projectRenderMetadata(fullEngine.parseSource(source), "main");

    assertThat(engineTree(result.value()))
        .isEqualTo(
            processData(
                new String[] {"project", "--target", "render-metadata", "--view", "main"}, source));
  }

  @Test
  void validateWithoutProfileWinsOverMalformedStdin() throws Exception {
    // Error precedence: the harness checks --profile before reading stdin, so a missing profile
    // must produce the enveloped DEDIREN_SEMANTIC_PROFILE_REQUIRED + exit 3 even when the stdin
    // bytes are unparseable -- the raw parse failure must not win.
    RouterResult result = RouterHarness.executeForTesting(new String[] {"validate"}, "not-json");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SEMANTIC_PROFILE_REQUIRED");
  }

  @Test
  void parseSourceRejectsUnparseableInput() {
    // generic-graph publishes no parse-failure envelope: unparseable stdin surfaces as today's raw
    // (non-enveloped) failure, so the parse entry point throws rather than returning a diagnostic.
    assertThatThrownBy(() -> fullEngine.parseSource("not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Exception.class);
  }

  // --- envelope-shaped base/routing cases (relocated GenericGraphPluginTest base cases) ----------

  @Test
  void validateWithoutProfileReturnsProfileRequiredEnvelope() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"validate"}, fixture("fixtures/source/valid-basic.json"));

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_SEMANTIC_PROFILE_REQUIRED");
  }

  @Test
  void validateRejectsUnsupportedProfile() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"validate", "--profile", "bpmn"},
            fixture("fixtures/source/valid-basic.json"));

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED");
    assertThat(envelope.at("/diagnostics/0/message").asText()).contains("bpmn");
  }

  @Test
  void projectsBasicViewToLayoutRequest() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            fixture("fixtures/source/valid-basic.json"));

    JsonNode data = okData(result);

    assertThat(result.exitCode()).isZero();
    assertThat(data.at("/layout_request_schema_version").asText())
        .isEqualTo("layout-request.schema.v2");
    assertThat(data.at("/view_id").asText()).isEqualTo("main");
    assertThat(data.get("nodes")).hasSize(2);
    assertThat(data.get("edges")).hasSize(1);
    assertThat(data.at("/edges/0/relationship_type").asText()).isEqualTo("generic.calls");
    assertSchemaValid("schemas/layout-request.schema.json", data);
  }

  @Test
  void projectsRelationshipPriorityOntoLayoutEdge() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "e1",
                      "type": "generic.calls",
                      "source": "client",
                      "target": "api",
                      "label": "calls",
                      "properties": {},
                      "priority": { "keep_short": 7 }
                    }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["client", "api"],
                          "relationships": ["e1"]
                        }
                      ]
                    }
                  }
                }
                """);

    JsonNode data = okData(result);
    JsonNode edge = data.get("edges").get(0);

    assertThat(edge.at("/id").asText()).isEqualTo("e1");
    assertThat(edge.at("/priority/keep_short").asInt()).isEqualTo(7);
    assertSchemaValid("schemas/layout-request.schema.json", data);
  }

  @Test
  void rejectsDuplicateViewIds() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        { "id": "main", "label": "First", "nodes": ["client"], "relationships": [] },
                        { "id": "main", "label": "Second", "nodes": ["api"], "relationships": [] }
                      ]
                    }
                  }
                }
                """);

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID");
  }

  @Test
  void rejectsDuplicateGroupIdsWithinView() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["client", "api"],
                          "relationships": [],
                          "groups": [
                            { "id": "boundary", "label": "First", "members": ["client"] },
                            { "id": "boundary", "label": "Second", "members": ["api"] }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID");
  }

  @Test
  void rejectsViewRelationshipEndpointOutsideView() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "generic.component", "label": "API", "properties": {} },
                    { "id": "database", "type": "generic.data-store", "label": "Database", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "client-reads-database",
                      "type": "generic.reads",
                      "source": "client",
                      "target": "database",
                      "label": "reads",
                      "properties": {}
                    }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["client", "api"],
                          "relationships": ["client-reads-database"],
                          "groups": []
                        }
                      ]
                    }
                  }
                }
                """);

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_GENERIC_GRAPH_RELATIONSHIP_ENDPOINT_OUTSIDE_VIEW");
  }

  @Test
  void projectsLayoutPreferences() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "client-calls-api",
                      "type": "generic.calls",
                      "source": "client",
                      "target": "api",
                      "label": "calls",
                      "properties": {}
                    }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["client", "api"],
                          "relationships": ["client-calls-api"],
                          "layout_preferences": {
                            "direction": "down",
                            "density": "readable",
                            "wrapping": "off",
                            "routing": {
                              "style": "orthogonal",
                              "profile": "spacious",
                              "endpoint_merging": "off"
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """);

    JsonNode data = okData(result);

    assertThat(data.get("layout_preferences"))
        .isEqualTo(
            JsonSupport.objectMapper()
                .readTree(
                    """
                        {
                          "direction": "down",
                          "density": "readable",
                          "wrapping": "off",
                          "routing": {
                            "style": "orthogonal",
                            "profile": "spacious",
                            "endpoint_merging": "off"
                          }
                        }
                        """));
    assertSchemaValid("schemas/layout-request.schema.json", data);
  }

  @Test
  void projectsRichViewGroups() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            fixture("fixtures/source/valid-pipeline-rich.json"));

    JsonNode data = okData(result);
    JsonNode groups = data.get("groups");

    assertThat(groups).hasSize(2);
    assertThat(groups.at("/0/id").asText()).isEqualTo("application-services");
    assertThat(groups.at("/0/label").asText()).isEqualTo("Application Services");
    assertThat(groups.at("/0/members").toString())
        .isEqualTo("[\"web-app\",\"orders-api\",\"worker\"]");
    assertThat(groups.at("/0/provenance/semantic_backed/source_id").asText())
        .isEqualTo("application-services");
    assertThat(groups.at("/1/id").asText()).isEqualTo("external-dependencies");
    assertThat(groups.at("/1/members").toString()).isEqualTo("[\"payments\",\"database\"]");
    assertSchemaValid("schemas/layout-request.schema.json", data);
  }

  @Test
  void projectsGroupRolesIntoProvenance() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "domain-group", "type": "Grouping", "label": "Domain Group", "properties": {} }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["client", "api"],
                          "relationships": [],
                          "groups": [
                            {
                              "id": "domain-boundary",
                              "label": "Domain Boundary",
                              "members": ["client", "api"],
                              "role": "semantic-boundary",
                              "semantic_source_id": "domain-group"
                            },
                            {
                              "id": "visual-column",
                              "label": "Visual Column",
                              "members": ["api"],
                              "role": "layout-only"
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

    JsonNode groups = okData(result).get("groups");

    assertThat(groups.at("/0/provenance/semantic_backed/source_id").asText())
        .isEqualTo("domain-group");
    assertThat(groups.at("/1/provenance/visual_only").asBoolean()).isTrue();
  }

  @Test
  void rejectsGroupSemanticSourceIdThatIsNotASourceNode() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["api"],
                          "relationships": [],
                          "groups": [
                            {
                              "id": "bad-group",
                              "label": "Bad Group",
                              "members": ["api"],
                              "role": "semantic-boundary",
                              "semantic_source_id": "missing-grouping-node"
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(result.stderr())
        .contains("group bad-group semantic_source_id references missing node");
  }

  @Test
  void projectsNodePlacementHintsOntoLayoutNodes() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    {
                      "id": "n1",
                      "type": "ApplicationComponent",
                      "label": "N1",
                      "properties": {},
                      "partition": 4,
                      "layer_constraint": "last"
                    }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["n1"],
                          "relationships": []
                        }
                      ]
                    }
                  }
                }
                """);

    JsonNode data = okData(result);
    JsonNode projected = layoutRequestNode(data, "n1");

    assertThat(projected.at("/partition").asInt()).isEqualTo(4);
    assertThat(projected.at("/layer_constraint").asText()).isEqualTo("last");
    assertSchemaValid("schemas/layout-request.schema.json", data);
  }

  @Test
  void projectsGroupRenderMetadataForSemanticSourceId() throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(
            new String[] {"project", "--target", "render-metadata", "--view", "main"},
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "domain-group", "type": "Grouping", "label": "Domain Group", "properties": {} }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["api"],
                          "relationships": [],
                          "groups": [
                            {
                              "id": "domain-boundary",
                              "label": "Domain Boundary",
                              "members": ["api"],
                              "role": "semantic-boundary",
                              "semantic_source_id": "domain-group"
                            },
                            {
                              "id": "visual-column",
                              "label": "Visual Column",
                              "members": ["api"],
                              "role": "layout-only"
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """);

    JsonNode data = okData(result);

    assertThat(data.at("/groups/domain-boundary/type").asText()).isEqualTo("Grouping");
    assertThat(data.at("/groups/domain-boundary/source_id").asText()).isEqualTo("domain-group");
    assertThat(data.at("/groups/visual-column").isMissingNode()).isTrue();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static JsonNode okData(RouterResult result) throws Exception {
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");
    return envelope.get("data");
  }

  private static void assertErrorCode(RouterResult result, String expectedCode) throws Exception {
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(expectedCode);
  }

  private static void assertSchemaValid(String schemaPath, JsonNode data) {
    SchemaAssertions.assertSchemaValid(workspaceRoot(), schemaPath, data);
  }

  private static JsonNode layoutRequestNode(JsonNode data, String nodeId) {
    for (JsonNode node : data.get("nodes")) {
      if (nodeId.equals(node.at("/id").asText())) {
        return node;
      }
    }
    throw new AssertionError("expected layout request node " + nodeId);
  }

  private static JsonNode engineTree(Object value) {
    return JsonSupport.objectMapper()
        .readTree(JsonSupport.objectMapper().writeValueAsString(value));
  }

  private static JsonNode processData(String[] args, byte[] source) throws Exception {
    RouterResult result =
        RouterHarness.executeForTesting(args, new String(source, StandardCharsets.UTF_8));
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    return JsonSupport.objectMapper().readTree(result.stdout()).get("data");
  }

  private static String fixture(String path) throws Exception {
    return Files.readString(workspaceRoot().resolve(path));
  }

  private static byte[] fixtureBytes(String path) throws Exception {
    return Files.readAllBytes(workspaceRoot().resolve(path));
  }

  private static SourceDocument source(String fixturePath) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(fixturePath)), SourceDocument.class);
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

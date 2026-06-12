package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.testsupport.SchemaAssertions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GenericGraphPluginTest {
    @Test
    void capabilitiesReportSemanticValidationAndProjection() throws Exception {
        PluginResult result = Main.executeForTesting(new String[]{"capabilities"}, "");

        JsonNode capabilities = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(capabilities.get("id").asText()).isEqualTo("generic-graph");
        assertThat(capabilities.get("capabilities")).extracting(JsonNode::toString)
                .asString()
                .contains("semantic-validation", "projection");
    }

    @Test
    void projectsBasicViewToLayoutRequest() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                fixture("fixtures/source/valid-basic.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/layout_request_schema_version").asText()).isEqualTo("layout-request.schema.v1");
        assertThat(data.at("/view_id").asText()).isEqualTo("main");
        assertThat(data.get("nodes")).hasSize(2);
        assertThat(data.get("edges")).hasSize(1);
        assertThat(data.at("/edges/0/relationship_type").asText()).isEqualTo("generic.calls");
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void rejectsDuplicateViewIds() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
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
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
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
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
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
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
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
                .isEqualTo(JsonSupport.objectMapper().readTree("""
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
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                fixture("fixtures/source/valid-pipeline-rich.json"));

        JsonNode data = okData(result);
        JsonNode groups = data.get("groups");

        assertThat(groups).hasSize(2);
        assertThat(groups.at("/0/id").asText()).isEqualTo("application-services");
        assertThat(groups.at("/0/label").asText()).isEqualTo("Application Services");
        assertThat(groups.at("/0/members").toString()).isEqualTo("[\"web-app\",\"orders-api\",\"worker\"]");
        assertThat(groups.at("/0/provenance/semantic_backed/source_id").asText())
                .isEqualTo("application-services");
        assertThat(groups.at("/1/id").asText()).isEqualTo("external-dependencies");
        assertThat(groups.at("/1/members").toString()).isEqualTo("[\"payments\",\"database\"]");
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsGroupRolesIntoProvenance() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
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

        assertThat(groups.at("/0/provenance/semantic_backed/source_id").asText()).isEqualTo("domain-group");
        assertThat(groups.at("/1/provenance/visual_only").asBoolean()).isTrue();
    }

    @Test
    void rejectsGroupSemanticSourceIdThatIsNotASourceNode() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
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
        assertThat(result.stderr()).contains("group bad-group semantic_source_id references missing node");
    }

    @Test
    void validatesArchimateSourceSemantics() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                fixture("fixtures/source/valid-archimate-oef.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/semantic_validation_result_schema_version").asText())
                .isEqualTo("semantic-validation-result.schema.v1");
        assertThat(data.at("/semantic_profile").asText()).isEqualTo("archimate");
        assertThat(data.at("/node_count").asInt()).isEqualTo(2);
        assertThat(data.at("/relationship_count").asInt()).isEqualTo(1);
    }

    @Test
    void validatesUmlProfile() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "uml"},
                fixture("fixtures/source/valid-uml-basic.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/node_count").asInt()).isEqualTo(10);
        assertThat(data.at("/relationship_count").asInt()).isEqualTo(6);
    }

    @Test
    void rejectsInvalidUmlRelationshipEndpoint() throws Exception {
        JsonNode source = JsonSupport.objectMapper().readTree(fixture("fixtures/source/valid-uml-basic.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/relationships/2"))
                .put("type", "Composition");

        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "uml"},
                JsonSupport.objectMapper().writeValueAsString(source));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    }

    @Test
    void projectsUmlRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "class-view"},
                fixture("fixtures/source/valid-uml-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/nodes/class-order/type").asText()).isEqualTo("Class");
        assertThat(data.at("/nodes/class-order/properties/attributes/0/name").asText()).isEqualTo("id");
        assertThat(data.at("/nodes/enum-order-status/properties/literals/1").asText()).isEqualTo("Submitted");
        assertThat(data.at("/edges/order-has-lines/type").asText()).isEqualTo("Composition");
        assertThat(data.at("/edges/order-has-lines/properties/source_multiplicity").asText()).isEqualTo("1");
        assertThat(data.at("/edges/order-has-lines/properties/target_multiplicity").asText()).isEqualTo("1..*");
        assertThat(data.at("/groups/orders-package-boundary/type").asText()).isEqualTo("Package");
        assertThat(data.at("/groups/orders-package-boundary/properties").isMissingNode()).isTrue();
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsUmlSequenceViewKind() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-view"},
                fixture("fixtures/source/valid-uml-sequence-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/view_id").asText()).isEqualTo("sequence-view");
        assertThat(data.get("constraints"))
                .extracting(constraint -> constraint.at("/kind").asText())
                .containsExactly("uml.sequence.lifeline-order", "uml.sequence.message-order");
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsLifelineRoleOntoSequenceLayoutNodes() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-view"},
                fixture("fixtures/source/valid-uml-sequence-basic.json"));

        JsonNode data = okData(result);

        assertThat(layoutRequestNode(data, "customer").at("/role").asText()).isEqualTo("lifeline");
        assertThat(layoutRequestNode(data, "service").at("/role").asText()).isEqualTo("lifeline");
        assertThat(layoutRequestNode(data, "interaction-place-order").has("role")).isFalse();
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsJunctionRoleOntoArchimateLayoutNodes() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                fixture("fixtures/source/valid-archimate-junction.json"));

        JsonNode data = okData(result);

        assertThat(layoutRequestNode(data, "fulfillment-junction").at("/role").asText()).isEqualTo("junction");
        assertThat(layoutRequestNode(data, "approval-junction").at("/role").asText()).isEqualTo("junction");
        assertThat(layoutRequestNode(data, "order-intake").has("role")).isFalse();
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlStateMachineViewKind() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "state-machine-view"},
                fixture("fixtures/source/valid-uml-state-machine-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/view_id").asText()).isEqualTo("state-machine-view");
        assertThat(jsonTexts(data.get("nodes"), "id"))
                .containsExactly("initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected");
        assertThat(jsonTexts(data.get("edges"), "id"))
                .containsExactly("t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");
        assertThat(jsonTexts(data.get("groups"), "id"))
                .containsExactly("order-lifecycle-frame", "main-region-frame");
        assertThat(jsonTexts(layoutRequestGroup(data, "order-lifecycle-frame").get("members")))
                .containsExactly("main-region-frame");
        assertThat(jsonTexts(layoutRequestGroup(data, "main-region-frame").get("members")))
                .containsExactly("initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected");
        assertThat(layoutRequestNode(data, "draft").at("/width_hint").asDouble()).isEqualTo(150.0);
        assertThat(layoutRequestNode(data, "draft").at("/height_hint").asDouble()).isEqualTo(72.0);
        assertThat(layoutRequestNode(data, "initial").at("/width_hint").asDouble()).isEqualTo(36.0);
        assertThat(layoutRequestNode(data, "initial").at("/height_hint").asDouble()).isEqualTo(36.0);
        assertThat(layoutRequestNode(data, "closed").at("/width_hint").asDouble()).isEqualTo(36.0);
        assertThat(layoutRequestNode(data, "closed").at("/height_hint").asDouble()).isEqualTo(36.0);
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlStateMachineRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "state-machine-view"},
                fixture("fixtures/source/valid-uml-state-machine-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/nodes/payment-choice/type").asText()).isEqualTo("Pseudostate");
        assertThat(data.at("/nodes/payment-choice/properties/kind").asText()).isEqualTo("choice");
        assertThat(data.at("/edges/t-approve/type").asText()).isEqualTo("Transition");
        assertThat(data.at("/edges/t-approve/properties/guard").asText()).isEqualTo("paymentAuthorized");
        assertThat(data.at("/groups/order-lifecycle-frame/type").asText()).isEqualTo("StateMachine");
        assertThat(data.at("/groups/main-region-frame/type").asText()).isEqualTo("Region");
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsUmlUseCaseViewKind() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "use-case-view"},
                fixture("fixtures/source/valid-uml-use-case-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/view_id").asText()).isEqualTo("use-case-view");
        assertThat(jsonTexts(data.get("nodes"), "id"))
                .containsExactly(
                        "customer",
                        "support-agent",
                        "place-order",
                        "track-order",
                        "authenticate-customer",
                        "apply-discount",
                        "cancel-order",
                        "payment-extension");
        assertThat(jsonTexts(data.get("edges"), "id"))
                .containsExactly(
                        "customer-place-order",
                        "customer-track-order",
                        "support-cancel-order",
                        "include-authentication",
                        "extend-discount");
        assertThat(jsonTexts(data.get("groups"), "id")).containsExactly("order-service-boundary");
        assertThat(jsonTexts(layoutRequestGroup(data, "order-service-boundary").get("members")))
                .containsExactly(
                        "place-order",
                        "track-order",
                        "authenticate-customer",
                        "apply-discount",
                        "cancel-order",
                        "payment-extension");
        assertThat(layoutRequestNode(data, "customer").at("/width_hint").asDouble()).isEqualTo(80.0);
        assertThat(layoutRequestNode(data, "customer").at("/height_hint").asDouble()).isEqualTo(120.0);
        assertThat(layoutRequestNode(data, "place-order").at("/width_hint").asDouble()).isEqualTo(160.0);
        assertThat(layoutRequestNode(data, "place-order").at("/height_hint").asDouble()).isEqualTo(72.0);
        assertThat(layoutRequestNode(data, "payment-extension").at("/width_hint").asDouble()).isEqualTo(140.0);
        assertThat(layoutRequestNode(data, "payment-extension").at("/height_hint").asDouble()).isEqualTo(40.0);
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlUseCaseRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "use-case-view"},
                fixture("fixtures/source/valid-uml-use-case-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/nodes/customer/type").asText()).isEqualTo("Actor");
        assertThat(data.at("/nodes/place-order/type").asText()).isEqualTo("UseCase");
        assertThat(data.at("/nodes/place-order/properties/subject").asText()).isEqualTo("order-service");
        assertThat(data.at("/nodes/payment-extension/type").asText()).isEqualTo("ExtensionPoint");
        assertThat(data.at("/nodes/payment-extension/properties/use_case").asText()).isEqualTo("place-order");
        assertThat(data.at("/edges/include-authentication/type").asText()).isEqualTo("Include");
        assertThat(data.at("/edges/extend-discount/type").asText()).isEqualTo("Extend");
        assertThat(data.at("/edges/extend-discount/properties/extension_point").asText())
                .isEqualTo("payment-extension");
        assertThat(data.at("/groups/order-service-boundary/type").asText()).isEqualTo("Class");
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsUmlComponentViewKind() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "component-view"},
                fixture("fixtures/source/valid-uml-component-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/view_id").asText()).isEqualTo("component-view");
        assertThat(jsonTexts(data.get("nodes"), "id"))
                .containsExactly(
                        "component-order-api",
                        "port-rest-api",
                        "component-payment-adapter",
                        "port-payment-client",
                        "interface-order-api",
                        "interface-payment-gateway",
                        "class-order-controller");
        assertThat(jsonTexts(data.get("edges"), "id"))
                .containsExactly(
                        "order-api-realizes-order-api",
                        "order-api-uses-payment",
                        "payment-adapter-realizes-gateway",
                        "order-api-depends-controller");
        assertThat(jsonTexts(data.get("groups"), "id"))
                .containsExactly("orders-package-boundary", "order-api-boundary", "payment-adapter-boundary");
        assertThat(jsonTexts(layoutRequestGroup(data, "orders-package-boundary").get("members")))
                .containsExactly(
                        "order-api-boundary",
                        "payment-adapter-boundary",
                        "interface-order-api",
                        "interface-payment-gateway",
                        "class-order-controller");
        assertThat(jsonTexts(layoutRequestGroup(data, "order-api-boundary").get("members")))
                .containsExactly("component-order-api", "port-rest-api");
        assertThat(layoutRequestNode(data, "component-order-api").at("/width_hint").asDouble()).isEqualTo(180.0);
        assertThat(layoutRequestNode(data, "component-order-api").at("/height_hint").asDouble()).isEqualTo(96.0);
        assertThat(layoutRequestNode(data, "port-rest-api").at("/width_hint").asDouble()).isEqualTo(32.0);
        assertThat(layoutRequestNode(data, "port-rest-api").at("/height_hint").asDouble()).isEqualTo(32.0);
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlComponentRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "component-view"},
                fixture("fixtures/source/valid-uml-component-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/nodes/component-order-api/type").asText()).isEqualTo("Component");
        assertThat(data.at("/nodes/port-rest-api/type").asText()).isEqualTo("Port");
        assertThat(data.at("/nodes/port-rest-api/properties/component").asText()).isEqualTo("component-order-api");
        assertThat(data.at("/nodes/port-rest-api/properties/provided/0").asText()).isEqualTo("interface-order-api");
        assertThat(data.at("/nodes/port-rest-api/properties/required/0").asText())
                .isEqualTo("interface-payment-gateway");
        assertThat(data.at("/edges/order-api-uses-payment/type").asText()).isEqualTo("Usage");
        assertThat(data.at("/edges/order-api-realizes-order-api/type").asText()).isEqualTo("Realization");
        assertThat(data.at("/groups/orders-package-boundary/type").asText()).isEqualTo("Package");
        assertThat(data.at("/groups/order-api-boundary/type").asText()).isEqualTo("Component");
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsUmlDeploymentViewKind() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "deployment-view"},
                fixture("fixtures/source/valid-uml-deployment-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/view_id").asText()).isEqualTo("deployment-view");
        assertThat(jsonTexts(data.get("nodes"), "id"))
                .containsExactly(
                        "device-prod-node",
                        "ee-orders-runtime",
                        "node-payment-network",
                        "artifact-orders-service",
                        "artifact-migration-job",
                        "deployment-spec-orders",
                        "component-order-api");
        assertThat(jsonTexts(data.get("edges"), "id"))
                .containsExactly(
                        "deploy-orders-service",
                        "deploy-orders-spec",
                        "artifact-manifests-order-api",
                        "orders-runtime-payment-path");
        assertThat(jsonTexts(data.get("groups"), "id")).containsExactly("prod-node-boundary");
        assertThat(jsonTexts(layoutRequestGroup(data, "prod-node-boundary").get("members")))
                .containsExactly(
                        "ee-orders-runtime",
                        "artifact-orders-service",
                        "artifact-migration-job",
                        "deployment-spec-orders");
        assertThat(layoutRequestNode(data, "device-prod-node").at("/width_hint").asDouble()).isEqualTo(200.0);
        assertThat(layoutRequestNode(data, "device-prod-node").at("/height_hint").asDouble()).isEqualTo(120.0);
        assertThat(layoutRequestNode(data, "ee-orders-runtime").at("/width_hint").asDouble()).isEqualTo(180.0);
        assertThat(layoutRequestNode(data, "ee-orders-runtime").at("/height_hint").asDouble()).isEqualTo(96.0);
        assertThat(layoutRequestNode(data, "artifact-orders-service").at("/width_hint").asDouble()).isEqualTo(150.0);
        assertThat(layoutRequestNode(data, "artifact-orders-service").at("/height_hint").asDouble()).isEqualTo(70.0);
        assertThat(layoutRequestNode(data, "deployment-spec-orders").at("/width_hint").asDouble()).isEqualTo(190.0);
        assertThat(layoutRequestNode(data, "deployment-spec-orders").at("/height_hint").asDouble()).isEqualTo(70.0);
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlDeploymentRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "deployment-view"},
                fixture("fixtures/source/valid-uml-deployment-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/nodes/device-prod-node/type").asText()).isEqualTo("Device");
        assertThat(data.at("/nodes/ee-orders-runtime/type").asText()).isEqualTo("ExecutionEnvironment");
        assertThat(data.at("/nodes/ee-orders-runtime/properties/node").asText()).isEqualTo("device-prod-node");
        assertThat(data.at("/nodes/artifact-orders-service/type").asText()).isEqualTo("Artifact");
        assertThat(data.at("/nodes/deployment-spec-orders/type").asText()).isEqualTo("DeploymentSpecification");
        assertThat(data.at("/edges/deploy-orders-service/type").asText()).isEqualTo("Deployment");
        assertThat(data.at("/edges/artifact-manifests-order-api/type").asText()).isEqualTo("Manifestation");
        assertThat(data.at("/edges/orders-runtime-payment-path/type").asText()).isEqualTo("CommunicationPath");
        assertThat(data.at("/groups/prod-node-boundary/type").asText()).isEqualTo("Device");
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsUmlSequenceEdgeRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "sequence-view"},
                fixture("fixtures/source/valid-uml-sequence-basic.json"));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/edges/m1/type").asText()).isEqualTo("Message");
        assertThat(data.at("/edges/m1/properties/interaction").asText()).isEqualTo("interaction-place-order");
        assertThat(data.at("/edges/m1/properties/sequence").asInt()).isEqualTo(1);
        assertThat(data.at("/edges/m1/properties/message_sort").asText()).isEqualTo("synchCall");
        assertThat(data.at("/edges/m2/properties/sequence").asInt()).isEqualTo(2);
        assertThat(data.at("/edges/m2/properties/message_sort").asText()).isEqualTo("reply");
        assertThat(data.at("/edges/m3/properties/sequence").asInt()).isEqualTo(3);
        assertThat(data.at("/edges/m3/properties/message_sort").asText()).isEqualTo("asynchSignal");
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsUmlSequenceFragmentRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "sequence-fragments-view"},
                fixture("fixtures/source/valid-uml-sequence-fragments.json"));

        JsonNode data = okData(result);

        assertThat(jsonFieldNames(data.get("nodes")))
                .contains(
                        "cf-availability",
                        "op-in-stock",
                        "op-backorder",
                        "cf-coupon",
                        "op-coupon",
                        "cf-retry",
                        "op-retry",
                        "cf-parallel-closeout",
                        "op-charge",
                        "op-confirm");
        assertThat(data.at("/nodes/cf-availability/type").asText()).isEqualTo("CombinedFragment");
        assertThat(data.at("/nodes/cf-availability/properties/operator").asText()).isEqualTo("alt");
        assertThat(data.at("/nodes/op-in-stock/type").asText()).isEqualTo("InteractionOperand");
        assertThat(data.at("/nodes/op-in-stock/properties/guard").asText()).isEqualTo("inStock");
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void excludesSequenceFragmentSemanticNodesFromLayoutRequest() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-fragments-view"},
                fixture("fixtures/source/valid-uml-sequence-fragments.json"));

        JsonNode data = okData(result);

        assertThat(jsonTexts(data.get("nodes"), "id"))
                .containsExactly("interaction-place-order", "customer", "service", "inventory", "payment")
                .doesNotContain(
                        "cf-availability",
                        "op-in-stock",
                        "op-backorder",
                        "cf-coupon",
                        "op-coupon",
                        "cf-retry",
                        "op-retry",
                        "cf-parallel-closeout",
                        "op-charge",
                        "op-confirm");
        assertThat(jsonTexts(data.get("labels"), "owner_id"))
                .containsExactly("interaction-place-order", "customer", "service", "inventory", "payment");
        assertThat(jsonTexts(data.get("edges"), "id"))
                .containsExactly("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10", "m11", "m12");
        assertThat(jsonTexts(data.get("constraints"), "kind"))
                .containsExactly("uml.sequence.lifeline-order", "uml.sequence.message-order");
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void filtersSequenceFragmentSemanticNodesFromLayoutGroups() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-fragments-view"},
                sequenceFragmentsFixtureWithLayoutGroups());

        JsonNode data = okData(result);

        assertThat(jsonTexts(data.get("groups"), "id")).containsExactly("availability-band");
        assertThat(jsonTexts(data.at("/groups/0/members"))).containsExactly("customer", "service");
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlSequenceLayoutConstraints() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-view"},
                sequenceFixtureWithReorderedMessagesForConstraints());

        JsonNode data = okData(result);
        JsonNode lifelineOrder = layoutRequestConstraint(data, "uml.sequence.lifeline-order");
        JsonNode messageOrder = layoutRequestConstraint(data, "uml.sequence.message-order");

        assertThat(lifelineOrder.at("/id").asText())
                .isEqualTo("sequence-view.uml.sequence.lifeline-order");
        assertThat(jsonTexts(lifelineOrder.get("subjects")))
                .containsExactly("customer", "service");
        assertThat(messageOrder.at("/id").asText())
                .isEqualTo("sequence-view.uml.sequence.message-order");
        assertThat(jsonTexts(messageOrder.get("subjects")))
                .containsExactly("m2", "m1", "m3");
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlSequenceMessageOrderWithLargeIntegralSequence() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-view"},
                sequenceFixtureWithLargeSequenceForConstraints());

        JsonNode messageOrder = layoutRequestConstraint(okData(result), "uml.sequence.message-order");

        assertThat(jsonTexts(messageOrder.get("subjects")))
                .containsExactly("m3", "m2", "m1");
    }

    @Test
    void projectsUmlSequenceLifelineSizeHints() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "sequence-view"},
                sequenceFixtureWithAdditionalSequenceNodes());

        JsonNode data = okData(result);

        assertThat(layoutRequestNode(data, "interaction-place-order").at("/width_hint").asDouble()).isEqualTo(360.0);
        assertThat(layoutRequestNode(data, "interaction-place-order").at("/height_hint").asDouble()).isEqualTo(260.0);
        assertThat(layoutRequestNode(data, "customer").at("/width_hint").asDouble()).isEqualTo(140.0);
        assertThat(layoutRequestNode(data, "customer").at("/height_hint").asDouble()).isEqualTo(48.0);
        assertThat(layoutRequestNode(data, "service-execution").at("/width_hint").asDouble()).isEqualTo(16.0);
        assertThat(layoutRequestNode(data, "service-execution").at("/height_hint").asDouble()).isEqualTo(72.0);
        assertThat(layoutRequestNode(data, "entry-gate").at("/width_hint").asDouble()).isEqualTo(24.0);
        assertThat(layoutRequestNode(data, "entry-gate").at("/height_hint").asDouble()).isEqualTo(24.0);
        assertThat(layoutRequestNode(data, "service-destroyed").at("/width_hint").asDouble()).isEqualTo(24.0);
        assertThat(layoutRequestNode(data, "service-destroyed").at("/height_hint").asDouble()).isEqualTo(24.0);
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsCompactUmlActivityNodeSizeHints() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "activity-view"},
                fixture("fixtures/source/valid-uml-basic.json"));

        JsonNode data = okData(result);
        JsonNode initial = layoutRequestNode(data, "initial-submit");

        assertThat(initial.at("/width_hint").asDouble()).isEqualTo(32.0);
        assertThat(initial.at("/height_hint").asDouble()).isEqualTo(32.0);
        assertSchemaValid("schemas/layout-request.schema.json", data);
    }

    @Test
    void projectsUmlStructuralSizeHintsFromCompartments() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "complex-class-view"},
                fixture("fixtures/source/valid-uml-complex.json"));

        JsonNode data = okData(result);

        assertThat(layoutRequestNode(data, "class-order").at("/width_hint").asDouble()).isEqualTo(300.0);
        assertThat(layoutRequestNode(data, "class-order").at("/height_hint").asDouble()).isEqualTo(190.0);
        assertThat(layoutRequestNode(data, "class-shipment").at("/height_hint").asDouble()).isEqualTo(130.0);
        assertThat(layoutRequestNode(data, "interface-payment-gateway").at("/width_hint").asDouble()).isEqualTo(380.0);
        assertThat(layoutRequestNode(data, "interface-payment-gateway").at("/height_hint").asDouble()).isEqualTo(120.0);
    }

    @Test
    void projectsArchimateRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                fixture("fixtures/source/valid-archimate-oef.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/render_metadata_schema_version").asText()).isEqualTo("render-metadata.schema.v1");
        assertThat(data.at("/semantic_profile").asText()).isEqualTo("archimate");
        assertThat(data.at("/nodes/orders-component/type").asText()).isEqualTo("ApplicationComponent");
        assertThat(data.at("/edges/orders-realizes-service/type").asText()).isEqualTo("Realization");
        assertThat(data.at("/nodes/orders-component/properties").isMissingNode()).isTrue();
        assertSchemaValid("schemas/render-metadata.schema.json", data);
    }

    @Test
    void projectsArchimateRenderMetadataWithoutOefExportPlugin() throws Exception {
        JsonNode source = archimateSource();
        ((com.fasterxml.jackson.databind.node.ObjectNode) source).putArray("required_plugins")
                .addObject()
                .put("id", "generic-graph")
                .put("version", "2026.06.4");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/plugins/generic-graph"))
                .put("semantic_profile", "archimate");

        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                JsonSupport.objectMapper().writeValueAsString(source));

        JsonNode data = okData(result);

        assertThat(data.at("/semantic_profile").asText()).isEqualTo("archimate");
        assertThat(data.at("/nodes/orders-component/type").asText()).isEqualTo("ApplicationComponent");
        assertThat(data.at("/edges/orders-realizes-service/type").asText()).isEqualTo("Realization");
    }

    @Test
    void projectsGroupRenderMetadataForSemanticSourceId() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
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

    @Test
    void projectsArchimateJunctionsAsSmallLayoutNodes() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "flow-junction", "type": "AndJunction", "label": "", "properties": {} },
                    { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} },
                    { "id": "billing", "type": "ApplicationService", "label": "Billing", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "flow-junction", "label": "", "properties": {} },
                    { "id": "junction-to-orders", "type": "Flow", "source": "flow-junction", "target": "orders", "label": "", "properties": {} },
                    { "id": "junction-to-billing", "type": "Flow", "source": "flow-junction", "target": "billing", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "semantic_profile": "archimate",
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["api", "flow-junction", "orders", "billing"],
                          "relationships": ["api-to-junction", "junction-to-orders", "junction-to-billing"]
                        }
                      ]
                    }
                  }
                }
                """);

        JsonNode junction = layoutRequestNode(okData(result), "flow-junction");

        assertThat(junction.at("/width_hint").asDouble()).isEqualTo(28.0);
        assertThat(junction.at("/height_hint").asDouble()).isEqualTo(28.0);
    }

    @Test
    void sizesArchimateNodesToFitLabelAndCornerIcon() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "short", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "long", "type": "ApplicationComponent", "label": "Application Collaboration Service Component", "properties": {} },
                    { "id": "flow-junction", "type": "AndJunction", "label": "", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "long-to-junction", "type": "Flow", "source": "long", "target": "flow-junction", "label": "", "properties": {} },
                    { "id": "junction-to-short", "type": "Flow", "source": "flow-junction", "target": "short", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "semantic_profile": "archimate",
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["short", "long", "flow-junction"],
                          "relationships": ["long-to-junction", "junction-to-short"]
                        }
                      ]
                    }
                  }
                }
                """);

        JsonNode data = okData(result);
        double shortWidth = layoutRequestNode(data, "short").at("/width_hint").asDouble();
        double longWidth = layoutRequestNode(data, "long").at("/width_hint").asDouble();
        double shortHeight = layoutRequestNode(data, "short").at("/height_hint").asDouble();

        double longHeight = layoutRequestNode(data, "long").at("/height_hint").asDouble();

        assertThat(shortWidth).isEqualTo(160.0);
        assertThat(shortHeight).isEqualTo(80.0);
        assertThat(longWidth).isGreaterThan(shortWidth);
        assertThat(layoutRequestNode(data, "flow-junction").at("/width_hint").asDouble()).isEqualTo(28.0);
        assertThat(layoutRequestNode(data, "flow-junction").at("/height_hint").asDouble()).isEqualTo(28.0);
        assertThat(longHeight).isGreaterThan(shortHeight);
        assertThat(longWidth).isEqualTo(190.0);
        assertThat(longHeight).isEqualTo(100.0);
    }

    @Test
    void projectsArchimateJunctionRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "flow-junction", "type": "OrJunction", "label": "", "properties": {} },
                    { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "flow-junction", "label": "", "properties": {} },
                    { "id": "junction-to-orders", "type": "Flow", "source": "flow-junction", "target": "orders", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "semantic_profile": "archimate",
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["api", "flow-junction", "orders"],
                          "relationships": ["api-to-junction", "junction-to-orders"]
                        }
                      ]
                    }
                  }
                }
                """);

        JsonNode data = okData(result);

        assertThat(data.at("/nodes/flow-junction/type").asText()).isEqualTo("OrJunction");
        assertThat(data.at("/nodes/flow-junction/source_id").asText()).isEqualTo("flow-junction");
    }

    @Test
    void rejectsArchimateJunctionWithMixedRelationshipTypes() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                archimateJunctionSource("Flow", "Serving", "api", "junction", "orders"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED");
        assertThat(result.stdout()).contains("junction", "Flow", "Serving");
    }

    @Test
    void rejectsArchimateJunctionWithInvalidEffectiveEndpoint() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                archimateJunctionSource("Realization", "Realization", "service", "junction", "component"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(result.stdout()).contains("ApplicationService", "Realization", "ApplicationComponent");
    }

    @Test
    void rejectsArchimateJunctionWithoutIncomingAndOutgoingRelationships() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "junction", "type": "AndJunction", "label": "", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["api", "junction"],
                          "relationships": ["api-to-junction"]
                        }
                      ]
                    }
                  }
                }
                """);

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE");
        assertThat(result.stdout()).contains("at least one incoming", "at least one outgoing");
    }

    @Test
    void rejectsArchimateJunctionChainWithInvalidEffectiveEndpoint() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "service", "type": "ApplicationService", "label": "Service", "properties": {} },
                    { "id": "join", "type": "AndJunction", "label": "", "properties": {} },
                    { "id": "split", "type": "AndJunction", "label": "", "properties": {} },
                    { "id": "component", "type": "ApplicationComponent", "label": "Component", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "service-to-join", "type": "Realization", "source": "service", "target": "join", "label": "", "properties": {} },
                    { "id": "join-to-split", "type": "Realization", "source": "join", "target": "split", "label": "", "properties": {} },
                    { "id": "split-to-component", "type": "Realization", "source": "split", "target": "component", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["service", "join", "split", "component"],
                          "relationships": ["service-to-join", "join-to-split", "split-to-component"]
                        }
                      ]
                    }
                  }
                }
                """);

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(result.stdout()).contains("ApplicationService", "Realization", "ApplicationComponent");
    }

    @Test
    void allowsArchimateJunctionContainmentRelationship() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "group", "type": "Grouping", "label": "Group", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "junction", "type": "AndJunction", "label": "", "properties": {} },
                    { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "group-contains-junction", "type": "Composition", "source": "group", "target": "junction", "label": "", "properties": {} },
                    { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} },
                    { "id": "junction-to-orders", "type": "Flow", "source": "junction", "target": "orders", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "semantic_profile": "archimate",
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["group", "api", "junction", "orders"],
                          "relationships": ["group-contains-junction", "api-to-junction", "junction-to-orders"]
                        }
                      ]
                    }
                  }
                }
                """);

        assertThat(okData(result).get("nodes")).hasSize(4);
    }

    @Test
    void rejectsUnknownArchimateNodeTypeForRenderMetadata() throws Exception {
        JsonNode source = archimateSource();
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/nodes/0"))
                .put("type", "TechnologyNode");

        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                JsonSupport.objectMapper().writeValueAsString(source));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
        assertThat(result.stdout()).contains("TechnologyNode");
    }

    @Test
    void rejectsUnknownArchimateRelationshipTypeForRenderMetadata() throws Exception {
        JsonNode source = archimateSource();
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/relationships/0"))
                .put("type", "ConnectsTo");

        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                JsonSupport.objectMapper().writeValueAsString(source));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
        assertThat(result.stdout()).contains("ConnectsTo");
    }

    @Test
    void rejectsInvalidArchimateRelationshipEndpointForRenderMetadata() throws Exception {
        JsonNode source = archimateSource();
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/nodes/0"))
                .put("type", "ApplicationService");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/nodes/1"))
                .put("type", "ApplicationComponent");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/relationships/0"))
                .put("type", "Realization");

        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                JsonSupport.objectMapper().writeValueAsString(source));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(result.stdout()).contains("ApplicationService", "Realization", "ApplicationComponent");
    }

    @Test
    void rejectsInvalidArchimateRelationshipEndpointForSemanticValidation() throws Exception {
        JsonNode source = archimateSource();
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/nodes/0"))
                .put("type", "ApplicationService");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/nodes/1"))
                .put("type", "ApplicationComponent");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/relationships/0"))
                .put("type", "Realization");

        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                JsonSupport.objectMapper().writeValueAsString(source));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(result.stdout()).contains("ApplicationService", "Realization", "ApplicationComponent");
    }

    @Test
    void rejectsArchimateJunctionAsSourceNodeForSemanticValidation() throws Exception {
        JsonNode source = archimateSource();
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.at("/nodes/0"))
                .put("type", "Junction");

        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                JsonSupport.objectMapper().writeValueAsString(source));

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
        assertThat(result.stdout()).contains("Junction");
    }

    private static JsonNode okData(PluginResult result) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(envelope.at("/status").asText())
                .describedAs(result.stdout())
                .isEqualTo("ok");
        return envelope.get("data");
    }

    private static void assertErrorCode(PluginResult result, String expectedCode) throws Exception {
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

    private static JsonNode layoutRequestGroup(JsonNode data, String groupId) {
        for (JsonNode group : data.get("groups")) {
            if (groupId.equals(group.at("/id").asText())) {
                return group;
            }
        }
        throw new AssertionError("expected layout request group " + groupId);
    }

    private static JsonNode layoutRequestConstraint(JsonNode data, String kind) {
        for (JsonNode constraint : data.get("constraints")) {
            if (kind.equals(constraint.at("/kind").asText())) {
                return constraint;
            }
        }
        throw new AssertionError("expected layout request constraint " + kind);
    }

    private static java.util.List<String> jsonTexts(JsonNode values) {
        var texts = new java.util.ArrayList<String>();
        values.forEach(value -> texts.add(value.asText()));
        return texts;
    }

    private static java.util.List<String> jsonTexts(JsonNode values, String fieldName) {
        var texts = new java.util.ArrayList<String>();
        values.forEach(value -> texts.add(value.at("/" + fieldName).asText()));
        return texts;
    }

    private static java.util.List<String> jsonFieldNames(JsonNode value) {
        var names = new java.util.ArrayList<String>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String fixture(String path) throws Exception {
        return Files.readString(workspaceRoot().resolve(path));
    }

    private static String sequenceFragmentsFixtureWithLayoutGroups() throws Exception {
        JsonNode source = JsonSupport.objectMapper()
                .readTree(fixture("fixtures/source/valid-uml-sequence-fragments.json"));
        var view = (com.fasterxml.jackson.databind.node.ObjectNode) source.at("/plugins/generic-graph/views/0");
        var groups = view.putArray("groups");

        var availabilityBand = groups.addObject();
        availabilityBand.put("id", "availability-band");
        availabilityBand.put("label", "Availability Band");
        availabilityBand.put("role", "layout-only");
        var availabilityMembers = availabilityBand.putArray("members");
        availabilityMembers.add("customer");
        availabilityMembers.add("cf-availability");
        availabilityMembers.add("op-in-stock");
        availabilityMembers.add("service");

        var fragmentOnlyBand = groups.addObject();
        fragmentOnlyBand.put("id", "fragment-only-band");
        fragmentOnlyBand.put("label", "Fragment Only Band");
        fragmentOnlyBand.put("role", "layout-only");
        var fragmentOnlyMembers = fragmentOnlyBand.putArray("members");
        fragmentOnlyMembers.add("cf-coupon");
        fragmentOnlyMembers.add("op-coupon");

        return JsonSupport.objectMapper().writeValueAsString(source);
    }

    private static String sequenceFixtureWithAdditionalSequenceNodes() throws Exception {
        JsonNode source = JsonSupport.objectMapper().readTree(fixture("fixtures/source/valid-uml-sequence-basic.json"));
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) source.get("nodes");
        var viewNodes = (com.fasterxml.jackson.databind.node.ArrayNode) source.at("/plugins/generic-graph/views/0/nodes");

        addSequenceNode(nodes, viewNodes, "service-execution", "ExecutionSpecification", "");
        addSequenceNode(nodes, viewNodes, "entry-gate", "Gate", "Entry");
        addSequenceNode(nodes, viewNodes, "service-destroyed", "DestructionOccurrenceSpecification", "");

        return JsonSupport.objectMapper().writeValueAsString(source);
    }

    private static String sequenceFixtureWithReorderedMessagesForConstraints() throws Exception {
        JsonNode source = JsonSupport.objectMapper().readTree(fixture("fixtures/source/valid-uml-sequence-basic.json"));
        var relationships = (com.fasterxml.jackson.databind.node.ArrayNode) source.get("relationships");
        JsonNode m1 = relationships.get(0).deepCopy();
        JsonNode m2 = relationships.get(1).deepCopy();
        JsonNode m3 = relationships.get(2).deepCopy();

        ((com.fasterxml.jackson.databind.node.ObjectNode) m1.at("/properties/uml")).put("sequence", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) m2.at("/properties/uml")).put("sequence", 1);
        relationships.removeAll();
        relationships.add(m3);
        relationships.add(m2);
        relationships.add(m1);

        return JsonSupport.objectMapper().writeValueAsString(source);
    }

    private static String sequenceFixtureWithLargeSequenceForConstraints() throws Exception {
        JsonNode source = JsonSupport.objectMapper().readTree(fixture("fixtures/source/valid-uml-sequence-basic.json"));
        var relationships = (com.fasterxml.jackson.databind.node.ArrayNode) source.get("relationships");
        JsonNode m1 = relationships.get(0).deepCopy();
        JsonNode m2 = relationships.get(1).deepCopy();
        JsonNode m3 = relationships.get(2).deepCopy();

        ((com.fasterxml.jackson.databind.node.ObjectNode) m1.at("/properties/uml"))
                .set("sequence", JsonSupport.objectMapper().getNodeFactory()
                        .numberNode(new java.math.BigInteger("9223372036854775808")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) m2.at("/properties/uml")).put("sequence", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) m3.at("/properties/uml")).put("sequence", 1);
        relationships.removeAll();
        relationships.add(m3);
        relationships.add(m2);
        relationships.add(m1);

        return JsonSupport.objectMapper().writeValueAsString(source);
    }

    private static void addSequenceNode(
            com.fasterxml.jackson.databind.node.ArrayNode nodes,
            com.fasterxml.jackson.databind.node.ArrayNode viewNodes,
            String id,
            String type,
            String label) {
        var node = nodes.addObject();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.putObject("properties")
                .putObject("uml")
                .put("interaction", "interaction-place-order");
        viewNodes.add(id);
    }

    private static String archimateJunctionSource(
            String incomingType,
            String outgoingType,
            String sourceId,
            String junctionId,
            String targetId) {
        String sourceType = sourceId.equals("service") ? "ApplicationService" : "ApplicationComponent";
        String targetType = targetId.equals("component") ? "ApplicationComponent" : "ApplicationService";
        return """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "%s", "type": "%s", "label": "Source", "properties": {} },
                    { "id": "%s", "type": "AndJunction", "label": "", "properties": {} },
                    { "id": "%s", "type": "%s", "label": "Target", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "source-to-junction", "type": "%s", "source": "%s", "target": "%s", "label": "", "properties": {} },
                    { "id": "junction-to-target", "type": "%s", "source": "%s", "target": "%s", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["%s", "%s", "%s"],
                          "relationships": ["source-to-junction", "junction-to-target"]
                        }
                      ]
                    }
                  }
                }
                """.formatted(
                sourceId,
                sourceType,
                junctionId,
                targetId,
                targetType,
                incomingType,
                sourceId,
                junctionId,
                outgoingType,
                junctionId,
                targetId,
                sourceId,
                junctionId,
                targetId);
    }

    private static JsonNode archimateSource() throws Exception {
        return JsonSupport.objectMapper().readTree("""
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    {
                      "id": "orders-component",
                      "type": "ApplicationComponent",
                      "label": "Orders Component",
                      "properties": {}
                    },
                    {
                      "id": "orders-service",
                      "type": "ApplicationService",
                      "label": "Orders Service",
                      "properties": {}
                    }
                  ],
                  "relationships": [
                    {
                      "id": "orders-realizes-service",
                      "type": "Realization",
                      "source": "orders-component",
                      "target": "orders-service",
                      "label": "realizes",
                      "properties": {}
                    }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "semantic_profile": "archimate",
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["orders-component", "orders-service"],
                          "relationships": ["orders-realizes-service"]
                        }
                      ]
                    }
                  }
                }
                """);
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

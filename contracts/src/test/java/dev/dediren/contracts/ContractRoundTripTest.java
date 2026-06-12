package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutMode;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingProfile;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import dev.dediren.contracts.layout.LayoutWrapping;
import dev.dediren.contracts.plugin.PluginManifest;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.render.SvgEdgeLabelPresentation;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.testsupport.SchemaAssertions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractRoundTripTest {
    @Test
    void sourceDocumentRoundTripsFromFixture() throws Exception {
        SourceDocument source = readFixture("fixtures/source/valid-basic.json", SourceDocument.class);

        assertThat(source.modelSchemaVersion()).isEqualTo(ContractVersions.MODEL_SCHEMA_VERSION);
        assertThat(source.nodes()).first().extracting(node -> node.id()).isEqualTo("client");
        assertThat(source.relationships()).first().extracting(relationship -> relationship.source()).isEqualTo("client");
        assertThat(source.fragments()).isEmpty();

        SourceDocument reparsed = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

        assertThat(reparsed).isEqualTo(source);
    }

    @Test
    void umlSequenceSourceDocumentPreservesPublicSequenceSurface() throws Exception {
        String fixture = "fixtures/source/valid-uml-sequence-basic.json";

        assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
                .describedAs(fixture)
                .isEmpty();

        SourceDocument source = readFixture(fixture, SourceDocument.class);
        GenericGraphPluginData genericGraph = JsonSupport.objectMapper()
                .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
        var view = genericGraph.views().getFirst();

        assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
        assertThat(view.kind().name()).isEqualTo("UML_SEQUENCE");
        assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
                .isEqualTo("uml-sequence");
        assertThat(view.relationships()).containsExactly("m1", "m2", "m3");
        assertThat(source.relationships()).extracting(SourceRelationship::id).containsExactly("m1", "m2", "m3");

        SourceDocument reparsed = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

        assertThat(reparsed.relationships()).extracting(SourceRelationship::id).containsExactly("m1", "m2", "m3");
        assertThat(reparsed.relationships())
                .extracting(relationship -> relationship.properties().get("uml").get("sequence").isNumber())
                .containsExactly(true, true, true);
        assertThat(reparsed.relationships())
                .extracting(relationship -> relationship.properties().get("uml").get("sequence").intValue())
                .containsExactly(1, 2, 3);
        assertThat(reparsed.relationships())
                .extracting(relationship -> relationship.properties().get("uml").get("message_sort").isTextual())
                .containsExactly(true, true, true);
        assertThat(reparsed.relationships())
                .extracting(relationship -> relationship.properties().get("uml").get("message_sort").textValue())
                .containsExactly("synchCall", "reply", "asynchSignal");
    }

    @Test
    void umlStateMachineSourceDocumentPreservesPublicStateMachineSurface() throws Exception {
        String fixture = "fixtures/source/valid-uml-state-machine-basic.json";

        assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
                .describedAs(fixture)
                .isEmpty();

        SourceDocument source = readFixture(fixture, SourceDocument.class);
        GenericGraphPluginData genericGraph = JsonSupport.objectMapper()
                .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
        var view = genericGraph.views().getFirst();

        assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
        assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_STATE_MACHINE);
        assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
                .isEqualTo("uml-state-machine");
        assertThat(view.nodes()).containsExactly(
                "initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected");
        assertThat(view.relationships())
                .containsExactly("t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");
        assertThat(source.relationships()).extracting(SourceRelationship::id)
                .containsExactly("t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");

        SourceDocument reparsed = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

        assertThat(reparsed.relationships())
                .extracting(relationship -> relationship.properties().get("uml").get("kind").textValue())
                .containsExactly("external", "external", "external", "external", "external", "external");
        assertThat(JsonSupport.objectMapper().valueToTree(reparsed).at("/nodes/5/properties/uml/kind").asText())
                .isEqualTo("choice");
    }

    @Test
    void umlUseCaseSourceDocumentPreservesPublicUseCaseSurface() throws Exception {
        String fixture = "fixtures/source/valid-uml-use-case-basic.json";

        assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
                .describedAs(fixture)
                .isEmpty();

        SourceDocument source = readFixture(fixture, SourceDocument.class);
        GenericGraphPluginData genericGraph = JsonSupport.objectMapper()
                .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
        var view = genericGraph.views().getFirst();

        assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
        assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_USE_CASE);
        assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
                .isEqualTo("uml-use-case");
        assertThat(view.nodes())
                .containsExactly(
                        "customer",
                        "support-agent",
                        "place-order",
                        "track-order",
                        "authenticate-customer",
                        "apply-discount",
                        "cancel-order",
                        "payment-extension");
        assertThat(view.relationships())
                .containsExactly(
                        "customer-place-order",
                        "customer-track-order",
                        "support-cancel-order",
                        "include-authentication",
                        "extend-discount");
        assertThat(view.groups().getFirst().semanticSourceId()).isEqualTo("order-service");

        SourceDocument reparsed = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

        assertThat(reparsed.nodes())
                .extracting(node -> node.type())
                .contains("Actor", "UseCase", "ExtensionPoint");
        assertThat(reparsed.relationships())
                .extracting(SourceRelationship::type)
                .contains("Association", "Include", "Extend");
        assertThat(JsonSupport.objectMapper().valueToTree(reparsed).at("/nodes/8/properties/uml/use_case").asText())
                .isEqualTo("place-order");
    }

    @Test
    void umlComponentSourceDocumentPreservesPublicComponentSurface() throws Exception {
        String fixture = "fixtures/source/valid-uml-component-basic.json";

        assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
                .describedAs(fixture)
                .isEmpty();

        SourceDocument source = readFixture(fixture, SourceDocument.class);
        GenericGraphPluginData genericGraph = JsonSupport.objectMapper()
                .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
        var view = genericGraph.views().getFirst();

        assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
        assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_COMPONENT);
        assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
                .isEqualTo("uml-component");
        assertThat(view.nodes())
                .containsExactly(
                        "component-order-api",
                        "port-rest-api",
                        "component-payment-adapter",
                        "port-payment-client",
                        "interface-order-api",
                        "interface-payment-gateway",
                        "class-order-controller");
        assertThat(view.relationships())
                .containsExactly(
                        "order-api-realizes-order-api",
                        "order-api-uses-payment",
                        "payment-adapter-realizes-gateway",
                        "order-api-depends-controller");
        assertThat(view.groups()).hasSize(3);
        assertThat(view.groups().getFirst().semanticSourceId()).isEqualTo("pkg-orders");

        SourceDocument reparsed = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

        assertThat(reparsed.nodes()).extracting(node -> node.type()).contains("Component", "Port", "Interface");
        assertThat(reparsed.relationships()).extracting(SourceRelationship::type)
                .contains("Realization", "Usage", "Dependency");
        assertThat(JsonSupport.objectMapper().valueToTree(reparsed).at("/nodes/2/properties/uml/component").asText())
                .isEqualTo("component-order-api");
    }

    @Test
    void umlDeploymentSourceDocumentPreservesPublicDeploymentSurface() throws Exception {
        String fixture = "fixtures/source/valid-uml-deployment-basic.json";

        assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
                .describedAs(fixture)
                .isEmpty();

        SourceDocument source = readFixture(fixture, SourceDocument.class);
        GenericGraphPluginData genericGraph = JsonSupport.objectMapper()
                .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
        var view = genericGraph.views().getFirst();

        assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
        assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_DEPLOYMENT);
        assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
                .isEqualTo("uml-deployment");
        assertThat(view.nodes())
                .containsExactly(
                        "device-prod-node",
                        "ee-orders-runtime",
                        "node-payment-network",
                        "artifact-orders-service",
                        "artifact-migration-job",
                        "deployment-spec-orders",
                        "component-order-api");
        assertThat(view.relationships())
                .containsExactly(
                        "deploy-orders-service",
                        "deploy-orders-spec",
                        "artifact-manifests-order-api",
                        "orders-runtime-payment-path");
        assertThat(view.groups()).hasSize(1);
        assertThat(view.groups().getFirst().semanticSourceId()).isEqualTo("device-prod-node");

        SourceDocument reparsed = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

        assertThat(reparsed.nodes()).extracting(node -> node.type())
                .contains("Device", "ExecutionEnvironment", "Node", "Artifact", "DeploymentSpecification");
        assertThat(reparsed.relationships()).extracting(SourceRelationship::type)
                .contains("Deployment", "Manifestation", "CommunicationPath");
        assertThat(JsonSupport.objectMapper().valueToTree(reparsed).at("/nodes/1/properties/uml/node").asText())
                .isEqualTo("device-prod-node");
    }

    @Test
    void umlSequenceFragmentsFixtureRoundTrips() throws Exception {
        String fixturePath = "fixtures/source/valid-uml-sequence-fragments.json";
        JsonNode source = JsonSupport.objectMapper().readTree(fixture(fixturePath));

        assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixturePath))
                .describedAs(fixturePath)
                .isEmpty();
        assertUmlSequenceFragmentSurface(source);

        SourceDocument decoded = JsonSupport.objectMapper().treeToValue(source, SourceDocument.class);
        SourceDocument roundTripped = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(decoded), SourceDocument.class);
        JsonNode encoded = JsonSupport.objectMapper().valueToTree(roundTripped);

        assertUmlSequenceFragmentSurface(encoded);
    }

    @Test
    void genericGraphPluginDataPreservesProfilesViewKindsGroupsAndPreferences() throws Exception {
        GenericGraphPluginData data = JsonSupport.readValue("""
                {
                  "semantic_profile": "uml",
                  "views": [
                    {
                      "id": "class-view",
                      "label": "Class View",
                      "kind": "uml-class",
                      "nodes": ["class-order"],
                      "relationships": [],
                      "layout_preferences": {
                        "mode": "packed",
                        "direction": "down",
                        "density": "readable",
                        "wrapping": "off",
                        "routing": {
                          "style": "orthogonal",
                          "profile": "spacious",
                          "endpoint_merging": "local"
                        }
                      },
                      "groups": [
                        {
                          "id": "application-services",
                          "label": "Application Services",
                          "members": ["api"],
                          "semantic_source_id": "application-services-source"
                        }
                      ]
                    }
                  ]
                }
                """, GenericGraphPluginData.class);

        var view = data.views().getFirst();
        var preferences = view.layoutPreferences();
        var routing = preferences.routing();

        assertThat(data.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
        assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_CLASS);
        assertThat(preferences.mode()).isEqualTo(LayoutMode.PACKED);
        assertThat(preferences.direction()).isEqualTo(LayoutDirection.DOWN);
        assertThat(preferences.density()).isEqualTo(LayoutDensity.READABLE);
        assertThat(preferences.wrapping()).isEqualTo(LayoutWrapping.OFF);
        assertThat(routing.style()).isEqualTo(LayoutRoutingStyle.ORTHOGONAL);
        assertThat(routing.profile()).isEqualTo(LayoutRoutingProfile.SPACIOUS);
        assertThat(routing.endpointMerging()).isEqualTo(LayoutEndpointMerging.LOCAL);
        assertThat(view.groups().getFirst().role()).isEqualTo(GenericGraphViewGroupRole.SEMANTIC_BOUNDARY);

        JsonNode encoded = JsonSupport.objectMapper().valueToTree(data);

        assertThat(encoded.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(encoded.at("/views/0/kind").asText()).isEqualTo("uml-class");
        assertThat(encoded.at("/views/0/layout_preferences/mode").asText()).isEqualTo("packed");
        assertThat(encoded.at("/views/0/groups/0/role").asText()).isEqualTo("semantic-boundary");
    }

    @Test
    void layoutRequestAndResultRoundTripFixtures() throws Exception {
        LayoutRequest request = readFixture("fixtures/layout-request/basic.json", LayoutRequest.class);
        LayoutResult result = readFixture("fixtures/layout-result/basic.json", LayoutResult.class);

        assertThat(request.layoutRequestSchemaVersion()).isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
        assertThat(request.groups()).isEmpty();
        assertThat(result.layoutResultSchemaVersion()).isEqualTo(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION);
        assertThat(result.nodes()).isNotEmpty();

        var semanticGroupJson = """
                {
                  "layout_request_schema_version": "layout-request.schema.v1",
                  "view_id": "main",
                  "nodes": [],
                  "edges": [],
                  "groups": [
                    {
                      "id": "group-1",
                      "label": "Group",
                      "members": [],
                      "provenance": { "semantic_backed": { "source_id": "system-group" } }
                    }
                  ],
                  "labels": [],
                  "constraints": []
                }
                """;

        LayoutRequest grouped = JsonSupport.readValue(semanticGroupJson, LayoutRequest.class);

        assertThat(grouped.groups().getFirst().provenance())
                .isEqualTo(GroupProvenance.semanticBacked("system-group"));
    }

    @Test
    void layoutNodesCarryOptionalRoleThatRoundTrips() throws Exception {
        LaidOutNode lifeline = new LaidOutNode("customer", "customer", "customer", 0, 0, 140, 48, "Customer", "lifeline");
        LayoutNode input = new LayoutNode("customer", "Customer", "customer", 140.0, 48.0, "lifeline");

        assertThat(lifeline.role()).isEqualTo("lifeline");
        assertThat(input.role()).isEqualTo("lifeline");
        assertThat(JsonSupport.objectMapper().valueToTree(lifeline).at("/role").asText()).isEqualTo("lifeline");
        assertThat(JsonSupport.objectMapper().valueToTree(input).at("/role").asText()).isEqualTo("lifeline");

        LaidOutNode ordinaryOut = new LaidOutNode("api", "api", "api", 0, 0, 140, 48, "Api");
        LayoutNode ordinaryIn = new LayoutNode("api", "Api", "api", 140.0, 48.0);

        assertThat(ordinaryOut.role()).isNull();
        assertThat(ordinaryIn.role()).isNull();
        assertThat(JsonSupport.objectMapper().valueToTree(ordinaryOut).has("role")).isFalse();
        assertThat(JsonSupport.objectMapper().valueToTree(ordinaryIn).has("role")).isFalse();

        LaidOutNode reparsedOut = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(lifeline), LaidOutNode.class);
        LayoutNode reparsedIn = JsonSupport.objectMapper()
                .treeToValue(JsonSupport.objectMapper().valueToTree(input), LayoutNode.class);

        assertThat(reparsedOut).isEqualTo(lifeline);
        assertThat(reparsedIn).isEqualTo(input);
    }

    @Test
    void renderContractsRoundTripPoliciesMetadataAndResults() throws Exception {
        RenderPolicy policy = readFixture("fixtures/render-policy/rich-svg.json", RenderPolicy.class);
        RenderMetadata metadata = readFixture("fixtures/render-metadata/uml-basic.json", RenderMetadata.class);
        RenderResult result = new RenderResult(
                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                List.of(new dev.dediren.contracts.render.RenderArtifact("svg", "<svg></svg>")));

        assertThat(policy.svgRenderPolicySchemaVersion()).isEqualTo(ContractVersions.SVG_RENDER_POLICY_SCHEMA_VERSION);
        assertThat(policy.style().nodeOverrides().get("api").stroke()).isEqualTo("#0891b2");
        assertThat(policy.style().edge().labelPresentation()).isEqualTo(SvgEdgeLabelPresentation.BACKGROUND);
        assertThat(metadata.renderMetadataSchemaVersion()).isEqualTo(ContractVersions.RENDER_METADATA_SCHEMA_VERSION);

        var decoratorPolicy = JsonSupport.readValue("""
                {
                  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
                  "semantic_profile": "archimate",
                  "page": { "width": 640, "height": 360 },
                  "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
                  "style": {
                    "node_type_overrides": {
                      "ApplicationComponent": {
                        "decorator": "archimate_application_component"
                      }
                    },
                    "edge_type_overrides": {
                      "Realization": {
                        "line_style": "dashed",
                        "marker_end": "hollow_triangle"
                      }
                    }
                  }
                }
                """, RenderPolicy.class);

        assertThat(decoratorPolicy.style().nodeTypeOverrides().get("ApplicationComponent").decorator())
                .isEqualTo(SvgNodeDecorator.ARCHIMATE_APPLICATION_COMPONENT);
        assertThat(decoratorPolicy.style().edgeTypeOverrides().get("Realization").lineStyle())
                .isEqualTo(SvgEdgeLineStyle.DASHED);
        assertThat(decoratorPolicy.style().edgeTypeOverrides().get("Realization").markerEnd())
                .isEqualTo(SvgEdgeMarkerEnd.HOLLOW_TRIANGLE);
        assertThat(JsonSupport.objectMapper().valueToTree(result).at("/artifacts/0/artifact_kind").asText())
                .isEqualTo("svg");
        assertThat(JsonSupport.objectMapper().valueToTree(result).at("/render_result_schema_version").asText())
                .isEqualTo("render-result.schema.v2");

        var interactivePolicy = JsonSupport.readValue("""
                {
                  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
                  "interactive": "both",
                  "page": { "width": 640, "height": 360 },
                  "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
                  "style": {
                    "interaction": {
                      "highlight_stroke": "#ff8800",
                      "highlight_stroke_width": 5
                    }
                  }
                }
                """, RenderPolicy.class);

        assertThat(interactivePolicy.interactive()).isEqualTo("both");
        assertThat(interactivePolicy.style().interaction().highlightStroke()).isEqualTo("#ff8800");
        assertThat(interactivePolicy.style().interaction().highlightStrokeWidth()).isEqualTo(5.0);
    }

    @Test
    void exportContractsAcceptTypedAndGenericPolicies() throws Exception {
        UmlXmiExportPolicy policy = readFixture("fixtures/export-policy/default-uml-xmi.json", UmlXmiExportPolicy.class);
        ExportRequest request = JsonSupport.readValue("""
                {
                  "export_request_schema_version": "export-request.schema.v1",
                  "source": {
                    "model_schema_version": "model.schema.v1",
                    "nodes": [],
                    "relationships": [],
                    "plugins": {}
                  },
                  "layout_result": {
                    "layout_result_schema_version": "layout-result.schema.v1",
                    "view_id": "class-view",
                    "nodes": [],
                    "edges": [],
                    "groups": [],
                    "warnings": []
                  },
                  "policy": {
                    "renderless_export_policy_schema_version": "unused-by-stable-schema",
                    "kind": "opaque"
                  }
                }
                """, ExportRequest.class);

        assertThat(policy.umlXmiExportPolicySchemaVersion())
                .isEqualTo(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION);
        assertThat(request.exportRequestSchemaVersion()).isEqualTo(ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION);
        assertThat(request.policy().get("kind").asText()).isEqualTo("opaque");
    }

    @Test
    void pluginManifestAndRuntimeCapabilityRoundTrip() throws Exception {
        PluginManifest manifest = readFixture("fixtures/plugins/generic-graph.manifest.json", PluginManifest.class);
        RuntimeCapabilities capabilities = JsonSupport.readValue("""
                {
                  "plugin_protocol_version": "plugin.protocol.v1",
                  "id": "generic-graph",
                  "capabilities": ["semantic-validate", "layout-request"],
                  "runtime": { "java": "21" }
                }
                """, RuntimeCapabilities.class);

        assertThat(manifest.pluginManifestSchemaVersion()).isEqualTo("plugin-manifest.schema.v1");
        assertThat(manifest.version()).isEqualTo("2026.06.5");
        assertThat(manifest.allowedEnv()).containsExactly("JAVA_HOME", "PATH");
        assertThat(capabilities.pluginProtocolVersion()).isEqualTo(ContractVersions.PLUGIN_PROTOCOL_VERSION);
        assertThat(capabilities.runtime().get("java").asText()).isEqualTo("21");
    }

    private static <T> T readFixture(String fixture, Class<T> type) throws Exception {
        return JsonSupport.readValue(Files.readString(workspaceRoot().resolve(fixture)), type);
    }

    private static String fixture(String fixture) throws Exception {
        return Files.readString(workspaceRoot().resolve(fixture));
    }

    private static JsonNode nodeById(JsonNode source, String id) {
        for (JsonNode node : source.get("nodes")) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
        }
        throw new AssertionError("Missing node " + id);
    }

    private static void assertUmlSequenceFragmentSurface(JsonNode source) {
        assertThat(source.at("/plugins/generic-graph/views/0/kind").asText()).isEqualTo("uml-sequence");

        assertCombinedFragment(
                source,
                "cf-availability",
                "alt",
                List.of("op-in-stock", "op-backorder"),
                List.of("customer", "service", "inventory"));
        assertCombinedFragment(source, "cf-coupon", "opt", List.of("op-coupon"), List.of("customer", "service"));
        assertCombinedFragment(source, "cf-retry", "loop", List.of("op-retry"), List.of("service", "payment"));
        assertCombinedFragment(
                source,
                "cf-parallel-closeout",
                "par",
                List.of("op-charge", "op-confirm"),
                List.of("customer", "service", "payment"));

        assertOperand(source, "op-in-stock", "cf-availability", 1, "inStock", "m1", "m2");
        assertOperand(source, "op-backorder", "cf-availability", 2, "else", "m3", "m4");
        assertOperand(source, "op-coupon", "cf-coupon", 1, "couponPresent", "m5", "m6");
        assertOperand(source, "op-retry", "cf-retry", 1, "whilePaymentPending", "m7", "m8");
        assertOperand(source, "op-charge", "cf-parallel-closeout", 1, "charge", "m9", "m10");
        assertOperand(source, "op-confirm", "cf-parallel-closeout", 2, "confirm", "m11", "m12");

        assertOrderedSequenceMessages(source);
    }

    private static void assertCombinedFragment(
            JsonNode source, String id, String operator, List<String> operands, List<String> covered) {
        JsonNode fragment = nodeById(source, id);

        assertThat(fragment.get("type").asText()).isEqualTo("CombinedFragment");
        assertThat(fragment.at("/properties/uml/interaction").asText()).isEqualTo("interaction-place-order");
        assertThat(fragment.at("/properties/uml/operator").asText()).isEqualTo(operator);
        assertThat(textValues(fragment.at("/properties/uml/operands"))).containsExactlyElementsOf(operands);
        assertThat(textValues(fragment.at("/properties/uml/covered"))).containsExactlyElementsOf(covered);
    }

    private static void assertOperand(
            JsonNode source, String id, String combinedFragment, int order, String guard, String... fragments) {
        JsonNode operand = nodeById(source, id);

        assertThat(operand.get("type").asText()).isEqualTo("InteractionOperand");
        assertThat(operand.at("/properties/uml/interaction").asText()).isEqualTo("interaction-place-order");
        assertThat(operand.at("/properties/uml/combined_fragment").asText()).isEqualTo(combinedFragment);
        assertThat(operand.at("/properties/uml/order").intValue()).isEqualTo(order);
        assertThat(operand.at("/properties/uml/guard").asText()).isEqualTo(guard);
        assertThat(textValues(operand.at("/properties/uml/fragments"))).containsExactly(fragments);
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }

    private static void assertOrderedSequenceMessages(JsonNode source) {
        assertThat(relationshipIds(source))
                .containsExactly("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10", "m11", "m12");
        assertThat(sequenceValues(source))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    private static List<String> relationshipIds(JsonNode source) {
        List<String> ids = new ArrayList<>();
        for (JsonNode relationship : source.get("relationships")) {
            ids.add(relationship.get("id").asText());
        }
        return ids;
    }

    private static List<Integer> sequenceValues(JsonNode source) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode relationship : source.get("relationships")) {
            values.add(relationship.at("/properties/uml/sequence").intValue());
        }
        return values;
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

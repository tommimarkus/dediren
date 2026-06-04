package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutMode;
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
    void renderContractsRoundTripPoliciesMetadataAndResults() throws Exception {
        RenderPolicy policy = readFixture("fixtures/render-policy/rich-svg.json", RenderPolicy.class);
        RenderMetadata metadata = readFixture("fixtures/render-metadata/uml-basic.json", RenderMetadata.class);
        RenderResult result = new RenderResult(
                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                "svg",
                "<svg></svg>");

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
        assertThat(JsonSupport.objectMapper().valueToTree(result).get("artifact_kind").asText()).isEqualTo("svg");
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
        assertThat(manifest.version()).isEqualTo("0.21.0");
        assertThat(manifest.allowedEnv()).containsExactly("JAVA_HOME", "PATH");
        assertThat(capabilities.pluginProtocolVersion()).isEqualTo(ContractVersions.PLUGIN_PROTOCOL_VERSION);
        assertThat(capabilities.runtime().get("java").asText()).isEqualTo("21");
    }

    private static <T> T readFixture(String fixture, Class<T> type) throws Exception {
        return JsonSupport.readValue(Files.readString(workspaceRoot().resolve(fixture)), type);
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

package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.build.BuildArtifact;
import dev.dediren.contracts.build.BuildResult;
import dev.dediren.contracts.build.BuildViewOutcome;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutAlgorithm;
import dev.dediren.contracts.layout.LayoutCompaction;
import dev.dediren.contracts.layout.LayoutComponentsSpacing;
import dev.dediren.contracts.layout.LayoutCrossingStrategy;
import dev.dediren.contracts.layout.LayoutCycleBreaking;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutGreedySwitch;
import dev.dediren.contracts.layout.LayoutHighDegreeNodes;
import dev.dediren.contracts.layout.LayoutLayerConstraint;
import dev.dediren.contracts.layout.LayoutLayeringStrategy;
import dev.dediren.contracts.layout.LayoutMode;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPlacementStrategy;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import dev.dediren.contracts.layout.LayoutThoroughness;
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
import tools.jackson.databind.JsonNode;

class ContractRoundTripTest {
  @Test
  void sourceDocumentRoundTripsFromFixture() throws Exception {
    SourceDocument source = readFixture("fixtures/source/valid-basic.json", SourceDocument.class);

    assertThat(source.modelSchemaVersion()).isEqualTo(ContractVersions.MODEL_SCHEMA_VERSION);
    assertThat(source.nodes()).first().extracting(node -> node.id()).isEqualTo("client");
    assertThat(source.relationships())
        .first()
        .extracting(relationship -> relationship.source())
        .isEqualTo("client");
    assertThat(source.fragments()).isEmpty();

    SourceDocument reparsed = reparse(source);

    assertThat(reparsed).isEqualTo(source);
  }

  @Test
  void umlSequenceSourceDocumentPreservesPublicSequenceSurface() throws Exception {
    String fixture = "fixtures/source/valid-uml-sequence-basic.json";
    SourceDocument source = validatedFixtureSource(fixture);
    GenericGraphPluginData genericGraph = genericGraphOf(source);
    var view = genericGraph.views().getFirst();

    assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
    assertThat(view.kind().name()).isEqualTo("UML_SEQUENCE");
    assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
        .isEqualTo("uml-sequence");
    assertThat(view.relationships()).containsExactly("m1", "m2", "m3");
    assertThat(source.relationships())
        .extracting(SourceRelationship::id)
        .containsExactly("m1", "m2", "m3");

    SourceDocument reparsed = reparse(source);

    assertThat(reparsed.relationships())
        .extracting(SourceRelationship::id)
        .containsExactly("m1", "m2", "m3");
    assertThat(reparsed.relationships())
        .extracting(relationship -> relationship.properties().get("uml").get("sequence").isNumber())
        .containsExactly(true, true, true);
    assertThat(reparsed.relationships())
        .extracting(relationship -> relationship.properties().get("uml").get("sequence").intValue())
        .containsExactly(1, 2, 3);
    assertThat(reparsed.relationships())
        .extracting(
            relationship -> relationship.properties().get("uml").get("message_sort").isTextual())
        .containsExactly(true, true, true);
    assertThat(reparsed.relationships())
        .extracting(
            relationship -> relationship.properties().get("uml").get("message_sort").textValue())
        .containsExactly("synchCall", "reply", "asynchSignal");
  }

  @Test
  void umlStateMachineSourceDocumentPreservesPublicStateMachineSurface() throws Exception {
    String fixture = "fixtures/source/valid-uml-state-machine-basic.json";
    SourceDocument source = validatedFixtureSource(fixture);
    GenericGraphPluginData genericGraph = genericGraphOf(source);
    var view = genericGraph.views().getFirst();

    assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
    assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_STATE_MACHINE);
    assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
        .isEqualTo("uml-state-machine");
    assertThat(view.nodes())
        .containsExactly(
            "initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected");
    assertThat(view.relationships())
        .containsExactly(
            "t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");
    assertThat(source.relationships())
        .extracting(SourceRelationship::id)
        .containsExactly(
            "t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");

    SourceDocument reparsed = reparse(source);

    assertThat(reparsed.relationships())
        .extracting(relationship -> relationship.properties().get("uml").get("kind").textValue())
        .containsExactly("external", "external", "external", "external", "external", "external");
    assertThat(
            JsonSupport.objectMapper()
                .valueToTree(reparsed)
                .at("/nodes/5/properties/uml/kind")
                .asText())
        .isEqualTo("choice");
  }

  @Test
  void umlUseCaseSourceDocumentPreservesPublicUseCaseSurface() throws Exception {
    String fixture = "fixtures/source/valid-uml-use-case-basic.json";
    SourceDocument source = validatedFixtureSource(fixture);
    GenericGraphPluginData genericGraph = genericGraphOf(source);
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

    SourceDocument reparsed = reparse(source);

    assertThat(reparsed.nodes())
        .extracting(node -> node.type())
        .contains("Actor", "UseCase", "ExtensionPoint");
    assertThat(reparsed.relationships())
        .extracting(SourceRelationship::type)
        .contains("Association", "Include", "Extend");
    assertThat(
            JsonSupport.objectMapper()
                .valueToTree(reparsed)
                .at("/nodes/8/properties/uml/use_case")
                .asText())
        .isEqualTo("place-order");
  }

  @Test
  void umlComponentSourceDocumentPreservesPublicComponentSurface() throws Exception {
    String fixture = "fixtures/source/valid-uml-component-basic.json";
    SourceDocument source = validatedFixtureSource(fixture);
    GenericGraphPluginData genericGraph = genericGraphOf(source);
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

    SourceDocument reparsed = reparse(source);

    assertThat(reparsed.nodes())
        .extracting(node -> node.type())
        .contains("Component", "Port", "Interface");
    assertThat(reparsed.relationships())
        .extracting(SourceRelationship::type)
        .contains("Realization", "Usage", "Dependency");
    assertThat(
            JsonSupport.objectMapper()
                .valueToTree(reparsed)
                .at("/nodes/2/properties/uml/component")
                .asText())
        .isEqualTo("component-order-api");
  }

  @Test
  void umlDeploymentSourceDocumentPreservesPublicDeploymentSurface() throws Exception {
    String fixture = "fixtures/source/valid-uml-deployment-basic.json";
    SourceDocument source = validatedFixtureSource(fixture);
    GenericGraphPluginData genericGraph = genericGraphOf(source);
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

    SourceDocument reparsed = reparse(source);

    assertThat(reparsed.nodes())
        .extracting(node -> node.type())
        .contains("Device", "ExecutionEnvironment", "Node", "Artifact", "DeploymentSpecification");
    assertThat(reparsed.relationships())
        .extracting(SourceRelationship::type)
        .contains("Deployment", "Manifestation", "CommunicationPath");
    assertThat(
            JsonSupport.objectMapper()
                .valueToTree(reparsed)
                .at("/nodes/1/properties/uml/node")
                .asText())
        .isEqualTo("device-prod-node");
  }

  @Test
  void umlSequenceFragmentsFixtureRoundTrips() throws Exception {
    String fixturePath = "fixtures/source/valid-uml-sequence-fragments.json";
    JsonNode source = JsonSupport.objectMapper().readTree(fixture(fixturePath));

    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(), "schemas/model.schema.json", fixturePath))
        .describedAs(fixturePath)
        .isEmpty();
    assertUmlSequenceFragmentSurface(source);

    SourceDocument decoded = JsonSupport.objectMapper().treeToValue(source, SourceDocument.class);
    SourceDocument roundTripped = reparse(decoded);
    JsonNode encoded = JsonSupport.objectMapper().valueToTree(roundTripped);

    assertUmlSequenceFragmentSurface(encoded);
  }

  @Test
  void genericGraphPluginDataPreservesProfilesViewKindsGroupsAndPreferences() throws Exception {
    GenericGraphPluginData data =
        JsonSupport.readValue(
            """
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
                """,
            GenericGraphPluginData.class);

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
    assertThat(routing.endpointMerging()).isEqualTo(LayoutEndpointMerging.LOCAL);
    assertThat(view.groups().getFirst().role())
        .isEqualTo(GenericGraphViewGroupRole.SEMANTIC_BOUNDARY);

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

    assertThat(request.layoutRequestSchemaVersion())
        .isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    assertThat(request.groups()).isEmpty();
    assertThat(result.layoutResultSchemaVersion())
        .isEqualTo(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION);
    assertThat(result.nodes()).isNotEmpty();

    var semanticGroupJson =
        """
                {
                  "layout_request_schema_version": "layout-request.schema.v2",
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
                  "constraints": []
                }
                """;

    LayoutRequest grouped = JsonSupport.readValue(semanticGroupJson, LayoutRequest.class);

    assertThat(grouped.groups().getFirst().provenance())
        .isEqualTo(GroupProvenance.semanticBacked("system-group"));
  }

  @Test
  void layoutNodesCarryOptionalRoleThatRoundTrips() throws Exception {
    LaidOutNode lifeline =
        new LaidOutNode("customer", "customer", "customer", 0, 0, 140, 48, "Customer", "lifeline");
    LayoutNode input = new LayoutNode("customer", "Customer", "customer", 140.0, 48.0, "lifeline");

    assertThat(lifeline.role()).isEqualTo("lifeline");
    assertThat(input.role()).isEqualTo("lifeline");
    assertThat(JsonSupport.objectMapper().valueToTree(lifeline).at("/role").asText())
        .isEqualTo("lifeline");
    assertThat(JsonSupport.objectMapper().valueToTree(input).at("/role").asText())
        .isEqualTo("lifeline");

    LaidOutNode ordinaryOut = new LaidOutNode("api", "api", "api", 0, 0, 140, 48, "Api");
    LayoutNode ordinaryIn = new LayoutNode("api", "Api", "api", 140.0, 48.0);

    assertThat(ordinaryOut.role()).isNull();
    assertThat(ordinaryIn.role()).isNull();
    assertThat(JsonSupport.objectMapper().valueToTree(ordinaryOut).has("role")).isFalse();
    assertThat(JsonSupport.objectMapper().valueToTree(ordinaryIn).has("role")).isFalse();

    LaidOutNode reparsedOut =
        JsonSupport.objectMapper()
            .treeToValue(JsonSupport.objectMapper().valueToTree(lifeline), LaidOutNode.class);
    LayoutNode reparsedIn =
        JsonSupport.objectMapper()
            .treeToValue(JsonSupport.objectMapper().valueToTree(input), LayoutNode.class);

    assertThat(reparsedOut).isEqualTo(lifeline);
    assertThat(reparsedIn).isEqualTo(input);
  }

  @Test
  void layoutNodeRoundTripsPlacementHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "n1",
          "label": "N1",
          "source_id": "n1",
          "partition": 2,
          "layer_constraint": "first-separate"
        }
        """;
    dev.dediren.contracts.layout.LayoutNode node =
        mapper.readValue(json, dev.dediren.contracts.layout.LayoutNode.class);
    assertThat(node.partition()).isEqualTo(2);
    assertThat(node.layerConstraint()).isEqualTo(LayoutLayerConstraint.FIRST_SEPARATE);
    assertThat(mapper.writeValueAsString(LayoutLayerConstraint.LAST_SEPARATE))
        .isEqualTo("\"last-separate\"");
  }

  @Test
  void sourceNodeRoundTripsPlacementHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "n1",
          "type": "Component",
          "label": "N1",
          "properties": {},
          "partition": 3,
          "layer_constraint": "last"
        }
        """;
    dev.dediren.contracts.source.SourceNode node =
        mapper.readValue(json, dev.dediren.contracts.source.SourceNode.class);
    assertThat(node.partition()).isEqualTo(3);
    assertThat(node.layerConstraint()).isEqualTo(LayoutLayerConstraint.LAST);
  }

  @Test
  void renderContractsRoundTripPoliciesMetadataAndResults() throws Exception {
    RenderPolicy policy = readFixture("fixtures/render-policy/rich-svg.json", RenderPolicy.class);
    RenderMetadata metadata =
        readFixture("fixtures/render-metadata/uml-basic.json", RenderMetadata.class);
    RenderResult result =
        new RenderResult(
            ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
            List.of(new dev.dediren.contracts.render.RenderArtifact("svg", "<svg></svg>")));

    assertThat(policy.renderPolicySchemaVersion())
        .isEqualTo(ContractVersions.RENDER_POLICY_SCHEMA_VERSION);
    assertThat(policy.accessibility().title()).isEqualTo("Payment authorization flow");
    assertThat(policy.accessibility().description())
        .isEqualTo("Checkout service calling the payment gateway");
    assertThat(policy.style().nodeOverrides().get("api").stroke()).isEqualTo("#0891b2");
    assertThat(policy.style().edge().labelPresentation())
        .isEqualTo(SvgEdgeLabelPresentation.BACKGROUND);
    assertThat(metadata.renderMetadataSchemaVersion())
        .isEqualTo(ContractVersions.RENDER_METADATA_SCHEMA_VERSION);

    var decoratorPolicy =
        JsonSupport.readValue(
            """
                {
                  "render_policy_schema_version": "render-policy.schema.v3",
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
                """,
            RenderPolicy.class);

    assertThat(decoratorPolicy.style().nodeTypeOverrides().get("ApplicationComponent").decorator())
        .isEqualTo(SvgNodeDecorator.ARCHIMATE_APPLICATION_COMPONENT);
    assertThat(decoratorPolicy.style().edgeTypeOverrides().get("Realization").lineStyle())
        .isEqualTo(SvgEdgeLineStyle.DASHED);
    assertThat(decoratorPolicy.style().edgeTypeOverrides().get("Realization").markerEnd())
        .isEqualTo(SvgEdgeMarkerEnd.HOLLOW_TRIANGLE);
    assertThat(
            JsonSupport.objectMapper()
                .valueToTree(result)
                .at("/artifacts/0/artifact_kind")
                .asText())
        .isEqualTo("svg");
    assertThat(
            JsonSupport.objectMapper()
                .valueToTree(result)
                .at("/render_result_schema_version")
                .asText())
        .isEqualTo("render-result.schema.v4");
  }

  @Test
  void exportContractsAcceptTypedAndGenericPolicies() throws Exception {
    UmlXmiExportPolicy policy =
        readFixture("fixtures/export-policy/default-uml-xmi.json", UmlXmiExportPolicy.class);
    ExportRequest request =
        JsonSupport.readValue(
            """
                {
                  "export_request_schema_version": "export-request.schema.v1",
                  "source": {
                    "model_schema_version": "model.schema.v1",
                    "nodes": [],
                    "relationships": [],
                    "plugins": {}
                  },
                  "layout_result": {
                    "layout_result_schema_version": "layout-result.schema.v2",
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
                """,
            ExportRequest.class);

    assertThat(policy.umlXmiExportPolicySchemaVersion())
        .isEqualTo(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION);
    assertThat(request.exportRequestSchemaVersion())
        .isEqualTo(ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION);
    assertThat(request.policy().get("kind").asText()).isEqualTo("opaque");
  }

  @Test
  void pluginManifestAndRuntimeCapabilityRoundTrip() throws Exception {
    // The plugin-manifest / runtime-capability records ship orphaned after the single-launcher
    // cutover (their source fixtures were retired with the process-plugin surface); this exercises
    // the still-shipping records from inline JSON so the deferred contract cleanup stays visible.
    PluginManifest manifest =
        JsonSupport.readValue(
            """
                {
                  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
                  "id": "generic-graph",
                  "version": "2026.07.16",
                  "capabilities": ["semantic-validation", "projection"],
                  "allowed_env": ["JAVA_HOME", "PATH"]
                }
                """,
            PluginManifest.class);
    RuntimeCapabilities capabilities =
        JsonSupport.readValue(
            """
                {
                  "plugin_protocol_version": "plugin.protocol.v1",
                  "id": "generic-graph",
                  "capabilities": ["semantic-validate", "layout-request"],
                  "runtime": { "java": "21" }
                }
                """,
            RuntimeCapabilities.class);

    assertThat(manifest.pluginManifestSchemaVersion()).isEqualTo("plugin-manifest.schema.v1");
    assertThat(manifest.version()).isEqualTo("2026.07.16");
    assertThat(manifest.allowedEnv()).containsExactly("JAVA_HOME", "PATH");
    assertThat(capabilities.pluginProtocolVersion())
        .isEqualTo(ContractVersions.PLUGIN_PROTOCOL_VERSION);
    assertThat(capabilities.runtime().get("java").asText()).isEqualTo("21");
  }

  @Test
  void routingStyleAcceptsPolylineAndSpline() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    assertThat(mapper.readValue("\"polyline\"", LayoutRoutingStyle.class))
        .isEqualTo(LayoutRoutingStyle.POLYLINE);
    assertThat(mapper.readValue("\"spline\"", LayoutRoutingStyle.class))
        .isEqualTo(LayoutRoutingStyle.SPLINE);
    assertThat(mapper.writeValueAsString(LayoutRoutingStyle.SPLINE)).isEqualTo("\"spline\"");
  }

  @Test
  void layoutPreferencesRoundTripsPhaseStrategies() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "cycle_breaking": "model-order",
          "layering": { "strategy": "coffman-graham" },
          "crossing": { "strategy": "layer-sweep", "greedy_switch": "two-sided" },
          "placement": { "strategy": "network-simplex" }
        }
        """;
    LayoutPreferences prefs = mapper.readValue(json, LayoutPreferences.class);
    assertThat(prefs.cycleBreaking()).isEqualTo(LayoutCycleBreaking.MODEL_ORDER);
    assertThat(prefs.layering().strategy()).isEqualTo(LayoutLayeringStrategy.COFFMAN_GRAHAM);
    assertThat(prefs.crossing().strategy()).isEqualTo(LayoutCrossingStrategy.LAYER_SWEEP);
    assertThat(prefs.crossing().greedySwitch()).isEqualTo(LayoutGreedySwitch.TWO_SIDED);
    assertThat(prefs.placement().strategy()).isEqualTo(LayoutPlacementStrategy.NETWORK_SIMPLEX);
    assertThat(mapper.writeValueAsString(LayoutLayeringStrategy.NETWORK_SIMPLEX))
        .isEqualTo("\"network-simplex\"");
  }

  @Test
  void layoutPreferencesRoundTripsGraphTuning() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "compaction": "balanced",
          "components": { "separate": false, "spacing": "spacious" },
          "high_degree_nodes": "on",
          "thoroughness": "high"
        }
        """;
    LayoutPreferences prefs = mapper.readValue(json, LayoutPreferences.class);
    assertThat(prefs.compaction()).isEqualTo(LayoutCompaction.BALANCED);
    assertThat(prefs.components().separate()).isEqualTo(Boolean.FALSE);
    assertThat(prefs.components().spacing()).isEqualTo(LayoutComponentsSpacing.SPACIOUS);
    assertThat(prefs.highDegreeNodes()).isEqualTo(LayoutHighDegreeNodes.ON);
    assertThat(prefs.thoroughness()).isEqualTo(LayoutThoroughness.HIGH);
    assertThat(mapper.writeValueAsString(LayoutThoroughness.NORMAL)).isEqualTo("\"normal\"");
  }

  @Test
  void layoutPreferencesRoundTripsAlgorithm() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    assertThat(mapper.readValue("{\"algorithm\":\"layered\"}", LayoutPreferences.class).algorithm())
        .isEqualTo(LayoutAlgorithm.LAYERED);
    // Forward-ready values deserialize at the contract layer (the public boundary still restricts
    // to layered).
    assertThat(mapper.readValue("{\"algorithm\":\"tree\"}", LayoutPreferences.class).algorithm())
        .isEqualTo(LayoutAlgorithm.TREE);
    assertThat(mapper.writeValueAsString(LayoutAlgorithm.LAYERED)).isEqualTo("\"layered\"");
  }

  @Test
  void layoutEdgeRoundTripsPriorityHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "e1",
          "source": "a",
          "target": "b",
          "label": "",
          "source_id": "e1",
          "relationship_type": "flow",
          "priority": { "resist_reversal": 5, "keep_short": 2, "keep_straight": 8 }
        }
        """;
    dev.dediren.contracts.layout.LayoutEdge edge =
        mapper.readValue(json, dev.dediren.contracts.layout.LayoutEdge.class);
    assertThat(edge.priority()).isNotNull();
    assertThat(edge.priority().resistReversal()).isEqualTo(5);
    assertThat(edge.priority().keepShort()).isEqualTo(2);
    assertThat(edge.priority().keepStraight()).isEqualTo(8);
    assertThat(
            mapper.writeValueAsString(
                new dev.dediren.contracts.layout.LayoutEdgePriority(null, 2, null)))
        .isEqualTo("{\"keep_short\":2}");
  }

  @Test
  void sourceRelationshipRoundTripsPriorityHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "e1",
          "type": "flow",
          "source": "a",
          "target": "b",
          "label": "",
          "properties": {},
          "priority": { "keep_straight": 3 }
        }
        """;
    dev.dediren.contracts.source.SourceRelationship rel =
        mapper.readValue(json, dev.dediren.contracts.source.SourceRelationship.class);
    assertThat(rel.priority()).isNotNull();
    assertThat(rel.priority().keepStraight()).isEqualTo(3);
    assertThat(rel.priority().resistReversal()).isNull();
  }

  @Test
  void buildResultContractsRoundTripFromFixtures() throws Exception {
    BuildResult basic = readFixture("fixtures/build-result/basic.json", BuildResult.class);
    BuildResult error = readFixture("fixtures/build-result/error.json", BuildResult.class);

    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/build-result.schema.json",
                "fixtures/build-result/basic.json"))
        .describedAs("basic build-result fixture")
        .isEmpty();
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/build-result.schema.json",
                "fixtures/build-result/error.json"))
        .describedAs("error build-result fixture")
        .isEmpty();

    assertThat(basic.buildResultSchemaVersion())
        .isEqualTo(ContractVersions.BUILD_RESULT_SCHEMA_VERSION);
    assertThat(basic.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(basic.diagnostics()).isEmpty();
    assertThat(basic.views())
        .extracting(BuildViewOutcome::viewId)
        .containsExactly("overview", "detail");
    assertThat(basic.views())
        .allSatisfy(view -> assertThat(view.status()).isEqualTo(EnvelopeStatus.OK));
    assertThat(basic.views().getFirst().artifacts())
        .containsExactly(new BuildArtifact("svg", "overview/diagram.svg"));

    assertThat(error.status()).isEqualTo(EnvelopeStatus.ERROR);
    BuildViewOutcome warned = error.views().getFirst();
    BuildViewOutcome failed = error.views().getLast();
    assertThat(warned.status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(warned.diagnostics())
        .first()
        .extracting(Diagnostic::severity)
        .isEqualTo(DiagnosticSeverity.WARNING);
    assertThat(warned.artifacts()).hasSize(1);
    assertThat(failed.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(failed.artifacts()).isEmpty();
    assertThat(failed.diagnostics())
        .first()
        .extracting(Diagnostic::severity)
        .isEqualTo(DiagnosticSeverity.ERROR);

    JsonNode encoded = JsonSupport.objectMapper().valueToTree(basic);
    assertThat(encoded.at("/build_result_schema_version").asText())
        .isEqualTo("build-result.schema.v1");
    assertThat(encoded.at("/views/0/view_id").asText()).isEqualTo("overview");
    assertThat(encoded.at("/views/0/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(encoded.has("diagnostics")).isFalse();

    assertThat(reparse(basic)).isEqualTo(basic);
    assertThat(reparse(error)).isEqualTo(error);
  }

  private static BuildResult reparse(BuildResult result) throws Exception {
    return JsonSupport.objectMapper()
        .treeToValue(JsonSupport.objectMapper().valueToTree(result), BuildResult.class);
  }

  private static <T> T readFixture(String fixture, Class<T> type) throws Exception {
    return JsonSupport.readValue(Files.readString(workspaceRoot().resolve(fixture)), type);
  }

  /** Reads and validates a source-document fixture against the public model schema. */
  private static SourceDocument validatedFixtureSource(String fixture) throws Exception {
    assertThat(
            SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
        .describedAs(fixture)
        .isEmpty();
    return readFixture(fixture, SourceDocument.class);
  }

  private static GenericGraphPluginData genericGraphOf(SourceDocument source) throws Exception {
    return JsonSupport.objectMapper()
        .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
  }

  private static SourceDocument reparse(SourceDocument source) throws Exception {
    return JsonSupport.objectMapper()
        .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);
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
    assertCombinedFragment(
        source, "cf-coupon", "opt", List.of("op-coupon"), List.of("customer", "service"));
    assertCombinedFragment(
        source, "cf-retry", "loop", List.of("op-retry"), List.of("service", "payment"));
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
    assertThat(fragment.at("/properties/uml/interaction").asText())
        .isEqualTo("interaction-place-order");
    assertThat(fragment.at("/properties/uml/operator").asText()).isEqualTo(operator);
    assertThat(textValues(fragment.at("/properties/uml/operands")))
        .containsExactlyElementsOf(operands);
    assertThat(textValues(fragment.at("/properties/uml/covered")))
        .containsExactlyElementsOf(covered);
  }

  private static void assertOperand(
      JsonNode source,
      String id,
      String combinedFragment,
      int order,
      String guard,
      String... fragments) {
    JsonNode operand = nodeById(source, id);

    assertThat(operand.get("type").asText()).isEqualTo("InteractionOperand");
    assertThat(operand.at("/properties/uml/interaction").asText())
        .isEqualTo("interaction-place-order");
    assertThat(operand.at("/properties/uml/combined_fragment").asText())
        .isEqualTo(combinedFragment);
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
    assertThat(sequenceValues(source)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
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
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

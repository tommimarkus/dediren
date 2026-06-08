package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class MainTest {
    @TempDir
    Path temp;

    @Test
    void moduleLoads() {
        assertThat(Main.moduleName()).isEqualTo("cli");
    }

    @Test
    void versionCommandReportsProductVersion() {
        CliResult result = Main.executeForTesting(new String[]{"--version"}, "");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("dediren 2026.06.1");
    }

    @Test
    void sequenceFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
        Map<String, String> env = sequenceWorkflowEnv();
        Path root = workspaceRoot();
        Path source = root.resolve("fixtures/source/valid-uml-sequence-basic.json");

        CliResult validate = Main.executeForTesting(new String[]{
                "validate",
                "--plugin",
                "generic-graph",
                "--profile",
                "uml",
                "--input",
                source.toString()
        }, "", env);

        JsonNode validateData = okData(validate);
        assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(validateData.at("/node_count").asInt()).isEqualTo(3);
        assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(3);

        CliResult layoutRequest = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "sequence-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode layoutRequestData = okData(layoutRequest);
        assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("sequence-view");
        assertThat(layoutRequestData.get("constraints"))
                .extracting(constraint -> constraint.at("/kind").asText())
                .containsExactly("uml.sequence.lifeline-order", "uml.sequence.message-order");
        Path layoutRequestFile = writeStdout("sequence-layout-request.json", layoutRequest);

        CliResult renderMetadata = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "render-metadata",
                "--view",
                "sequence-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode renderMetadataData = okData(renderMetadata);
        assertThat(renderMetadataData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(renderMetadataData.at("/nodes/interaction-place-order/type").asText()).isEqualTo("Interaction");
        assertThat(renderMetadataData.at("/edges/m1/type").asText()).isEqualTo("Message");
        assertThat(renderMetadataData.at("/edges/m1/properties/sequence").asInt()).isEqualTo(1);
        assertThat(renderMetadataData.at("/edges/m1/properties/message_sort").asText()).isEqualTo("synchCall");
        Path renderMetadataFile = writeStdout("sequence-render-metadata.json", renderMetadata);

        CliResult layout = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                layoutRequestFile.toString()
        }, "", env);

        JsonNode layoutData = okData(layout);
        assertThat(layoutData.at("/view_id").asText()).isEqualTo("sequence-view");
        assertThat(layoutData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("interaction-place-order", "customer", "service");
        Path layoutFile = writeStdout("sequence-layout-result.json", layout);

        CliResult render = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                root.resolve("fixtures/render-policy/uml-svg.json").toString(),
                "--metadata",
                renderMetadataFile.toString(),
                "--input",
                layoutFile.toString()
        }, "", env);

        JsonNode renderData = okData(render);
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
        assertThat(svg).contains(
                "<svg",
                "Place Order",
                "data-dediren-node-id=\"interaction-place-order\"",
                "data-dediren-edge-id=\"m1\"",
                "placeOrder");
        assertSequenceSvgGeometry(svgDocument(svg));

        CliResult export = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                source.toString(),
                "--layout",
                layoutFile.toString()
        }, "", env);

        JsonNode exportData = okData(export);
        String xmi = exportData.at("/content").asText();
        assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(xmi)
                .contains("xmi:XMI", "uml:Interaction", "id-interaction-place-order")
                .containsSubsequence(
                        "name=\"placeOrder\"",
                        "name=\"accepted\"",
                        "name=\"receiptReady\"");
    }

    @Test
    void sequenceFragmentsFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
        Map<String, String> env = sequenceWorkflowEnv();
        Path root = workspaceRoot();
        Path source = root.resolve("fixtures/source/valid-uml-sequence-fragments.json");

        CliResult validate = Main.executeForTesting(new String[]{
                "validate",
                "--plugin",
                "generic-graph",
                "--profile",
                "uml",
                "--input",
                source.toString()
        }, "", env);

        JsonNode validateData = okData(validate);
        assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(validateData.at("/node_count").asInt()).isGreaterThan(0);

        CliResult layoutRequest = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "sequence-fragments-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode layoutRequestData = okData(layoutRequest);
        assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("sequence-fragments-view");
        assertThat(layoutRequestData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .containsExactly("interaction-place-order", "customer", "service", "inventory", "payment")
                .doesNotContain("cf-availability");
        Path layoutRequestFile = writeStdout("sequence-fragments-layout-request.json", layoutRequest);

        CliResult renderMetadata = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "render-metadata",
                "--view",
                "sequence-fragments-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode renderMetadataData = okData(renderMetadata);
        assertThat(renderMetadataData.at("/nodes/cf-availability/type").asText()).isEqualTo("CombinedFragment");
        assertThat(renderMetadataData.at("/nodes/cf-availability/properties/operator").asText()).isEqualTo("alt");
        Path renderMetadataFile = writeStdout("sequence-fragments-render-metadata.json", renderMetadata);

        CliResult layout = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                layoutRequestFile.toString()
        }, "", env);

        JsonNode layoutData = okData(layout);
        assertThat(layoutData.at("/view_id").asText()).isEqualTo("sequence-fragments-view");
        Path layoutFile = writeStdout("sequence-fragments-layout-result.json", layout);

        CliResult render = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                root.resolve("fixtures/render-policy/uml-svg.json").toString(),
                "--metadata",
                renderMetadataFile.toString(),
                "--input",
                layoutFile.toString()
        }, "", env);

        JsonNode renderData = okData(render);
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
        assertThat(svg).contains("data-dediren-sequence-combined-fragment=\"cf-availability\"");

        CliResult export = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                source.toString(),
                "--layout",
                layoutFile.toString()
        }, "", env);

        JsonNode exportData = okData(export);
        String xmi = exportData.at("/content").asText();
        assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(xmi).contains("uml:CombinedFragment", "interactionOperator=\"alt\"");
    }

    @Test
    void stateMachineFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
        Map<String, String> env = sequenceWorkflowEnv();
        Path root = workspaceRoot();
        Path source = root.resolve("fixtures/source/valid-uml-state-machine-basic.json");

        CliResult validate = Main.executeForTesting(new String[]{
                "validate",
                "--plugin",
                "generic-graph",
                "--profile",
                "uml",
                "--input",
                source.toString()
        }, "", env);

        JsonNode validateData = okData(validate);
        assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(validateData.at("/node_count").asInt()).isEqualTo(9);
        assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(6);

        CliResult layoutRequest = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "state-machine-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode layoutRequestData = okData(layoutRequest);
        assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("state-machine-view");
        Path layoutRequestFile = writeStdout("state-machine-layout-request.json", layoutRequest);

        CliResult renderMetadata = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "render-metadata",
                "--view",
                "state-machine-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode renderMetadataData = okData(renderMetadata);
        assertThat(renderMetadataData.at("/nodes/payment-choice/type").asText()).isEqualTo("Pseudostate");
        assertThat(renderMetadataData.at("/edges/t-approve/type").asText()).isEqualTo("Transition");
        Path renderMetadataFile = writeStdout("state-machine-render-metadata.json", renderMetadata);

        CliResult layout = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                layoutRequestFile.toString()
        }, "", env);

        JsonNode layoutData = okData(layout);
        assertThat(layoutData.at("/view_id").asText()).isEqualTo("state-machine-view");
        Path layoutFile = writeStdout("state-machine-layout-result.json", layout);

        CliResult render = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                root.resolve("fixtures/render-policy/uml-svg.json").toString(),
                "--metadata",
                renderMetadataFile.toString(),
                "--input",
                layoutFile.toString()
        }, "", env);

        JsonNode renderData = okData(render);
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
        assertThat(svg).contains(
                "<svg",
                "Order Lifecycle",
                "data-dediren-node-id=\"draft\"",
                "data-dediren-edge-id=\"t-submit\"");

        CliResult export = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                source.toString(),
                "--layout",
                layoutFile.toString()
        }, "", env);

        JsonNode exportData = okData(export);
        String xmi = exportData.at("/content").asText();
        assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(xmi).contains("uml:StateMachine", "id-order-lifecycle", "id-t-submit");
    }

    @Test
    void useCaseFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
        Map<String, String> env = sequenceWorkflowEnv();
        Path root = workspaceRoot();
        Path source = root.resolve("fixtures/source/valid-uml-use-case-basic.json");

        CliResult validate = Main.executeForTesting(new String[]{
                "validate",
                "--plugin",
                "generic-graph",
                "--profile",
                "uml",
                "--input",
                source.toString()
        }, "", env);

        JsonNode validateData = okData(validate);
        assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(validateData.at("/node_count").asInt()).isEqualTo(9);
        assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(5);

        CliResult layoutRequest = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "use-case-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode layoutRequestData = okData(layoutRequest);
        assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("use-case-view");
        assertThat(layoutRequestData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("customer", "place-order", "payment-extension");
        Path layoutRequestFile = writeStdout("use-case-layout-request.json", layoutRequest);

        CliResult renderMetadata = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "render-metadata",
                "--view",
                "use-case-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode renderMetadataData = okData(renderMetadata);
        assertThat(renderMetadataData.at("/nodes/customer/type").asText()).isEqualTo("Actor");
        assertThat(renderMetadataData.at("/nodes/place-order/type").asText()).isEqualTo("UseCase");
        assertThat(renderMetadataData.at("/nodes/payment-extension/type").asText()).isEqualTo("ExtensionPoint");
        assertThat(renderMetadataData.at("/edges/include-authentication/type").asText()).isEqualTo("Include");
        assertThat(renderMetadataData.at("/edges/extend-discount/properties/extension_point").asText())
                .isEqualTo("payment-extension");
        Path renderMetadataFile = writeStdout("use-case-render-metadata.json", renderMetadata);

        CliResult layout = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                layoutRequestFile.toString()
        }, "", env);

        JsonNode layoutData = okData(layout);
        assertThat(layoutData.at("/view_id").asText()).isEqualTo("use-case-view");
        assertThat(layoutData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("customer", "place-order", "authenticate-customer");
        Path layoutFile = writeStdout("use-case-layout-result.json", layout);

        CliResult render = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                root.resolve("fixtures/render-policy/uml-svg.json").toString(),
                "--metadata",
                renderMetadataFile.toString(),
                "--input",
                layoutFile.toString()
        }, "", env);

        JsonNode renderData = okData(render);
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
        assertThat(svg).contains(
                "<svg",
                "Order Service",
                "data-dediren-node-id=\"customer\"",
                "data-dediren-node-shape=\"uml_actor\"",
                "data-dediren-node-shape=\"uml_use_case\"",
                "data-dediren-node-id=\"payment-extension\"",
                "data-dediren-edge-id=\"include-authentication\"",
                "data-dediren-edge-id=\"extend-discount\"");

        CliResult export = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                source.toString(),
                "--layout",
                layoutFile.toString()
        }, "", env);

        JsonNode exportData = okData(export);
        String xmi = exportData.at("/content").asText();
        assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(xmi).contains(
                "xmi:type=\"uml:Actor\"",
                "xmi:type=\"uml:UseCase\"",
                "subject=\"id-order-service\"",
                "xmi:id=\"id-include-authentication\"",
                "xmi:id=\"id-extend-discount\"",
                "extensionLocation=\"id-payment-extension\"");
    }

    @Test
    void componentFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
        Map<String, String> env = sequenceWorkflowEnv();
        Path root = workspaceRoot();
        Path source = root.resolve("fixtures/source/valid-uml-component-basic.json");

        CliResult validate = Main.executeForTesting(new String[]{
                "validate",
                "--plugin",
                "generic-graph",
                "--profile",
                "uml",
                "--input",
                source.toString()
        }, "", env);

        JsonNode validateData = okData(validate);
        assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(validateData.at("/node_count").asInt()).isEqualTo(8);
        assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(4);

        CliResult layoutRequest = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "component-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode layoutRequestData = okData(layoutRequest);
        assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("component-view");
        assertThat(layoutRequestData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("component-order-api", "port-rest-api", "interface-payment-gateway");
        Path layoutRequestFile = writeStdout("component-layout-request.json", layoutRequest);

        CliResult renderMetadata = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "render-metadata",
                "--view",
                "component-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode renderMetadataData = okData(renderMetadata);
        assertThat(renderMetadataData.at("/nodes/component-order-api/type").asText()).isEqualTo("Component");
        assertThat(renderMetadataData.at("/nodes/port-rest-api/type").asText()).isEqualTo("Port");
        assertThat(renderMetadataData.at("/edges/order-api-uses-payment/type").asText()).isEqualTo("Usage");
        Path renderMetadataFile = writeStdout("component-render-metadata.json", renderMetadata);

        CliResult layout = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                layoutRequestFile.toString()
        }, "", env);

        JsonNode layoutData = okData(layout);
        assertThat(layoutData.at("/view_id").asText()).isEqualTo("component-view");
        assertThat(layoutData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("component-order-api", "port-rest-api", "component-payment-adapter");
        Path layoutFile = writeStdout("component-layout-result.json", layout);

        CliResult render = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                root.resolve("fixtures/render-policy/uml-svg.json").toString(),
                "--metadata",
                renderMetadataFile.toString(),
                "--input",
                layoutFile.toString()
        }, "", env);

        JsonNode renderData = okData(render);
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
        assertThat(svg).contains(
                "data-dediren-node-shape=\"uml_component\"",
                "data-dediren-node-shape=\"uml_port\"",
                "data-dediren-edge-id=\"order-api-uses-payment\"",
                "PaymentGateway");

        CliResult export = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                source.toString(),
                "--layout",
                layoutFile.toString()
        }, "", env);

        JsonNode exportData = okData(export);
        String xmi = exportData.at("/content").asText();
        assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(xmi).contains(
                "xmi:type=\"uml:Component\"",
                "xmi:type=\"uml:Port\"",
                "xmi:type=\"uml:Usage\"",
                "supplier=\"id-interface-payment-gateway\"");
    }

    @Test
    void deploymentFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
        Map<String, String> env = sequenceWorkflowEnv();
        Path root = workspaceRoot();
        Path source = root.resolve("fixtures/source/valid-uml-deployment-basic.json");

        CliResult validate = Main.executeForTesting(new String[]{
                "validate",
                "--plugin",
                "generic-graph",
                "--profile",
                "uml",
                "--input",
                source.toString()
        }, "", env);

        JsonNode validateData = okData(validate);
        assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(validateData.at("/node_count").asInt()).isEqualTo(7);
        assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(4);

        CliResult layoutRequest = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "deployment-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode layoutRequestData = okData(layoutRequest);
        assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("deployment-view");
        assertThat(layoutRequestData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("device-prod-node", "ee-orders-runtime", "artifact-orders-service");
        Path layoutRequestFile = writeStdout("deployment-layout-request.json", layoutRequest);

        CliResult renderMetadata = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "render-metadata",
                "--view",
                "deployment-view",
                "--input",
                source.toString()
        }, "", env);

        JsonNode renderMetadataData = okData(renderMetadata);
        assertThat(renderMetadataData.at("/nodes/device-prod-node/type").asText()).isEqualTo("Device");
        assertThat(renderMetadataData.at("/nodes/ee-orders-runtime/type").asText())
                .isEqualTo("ExecutionEnvironment");
        assertThat(renderMetadataData.at("/edges/deploy-orders-service/type").asText()).isEqualTo("Deployment");
        assertThat(renderMetadataData.at("/edges/artifact-manifests-order-api/type").asText())
                .isEqualTo("Manifestation");
        Path renderMetadataFile = writeStdout("deployment-render-metadata.json", renderMetadata);

        CliResult layout = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                layoutRequestFile.toString()
        }, "", env);

        JsonNode layoutData = okData(layout);
        assertThat(layoutData.at("/view_id").asText()).isEqualTo("deployment-view");
        assertThat(layoutData.get("nodes"))
                .extracting(node -> node.at("/id").asText())
                .contains("device-prod-node", "ee-orders-runtime", "deployment-spec-orders");
        Path layoutFile = writeStdout("deployment-layout-result.json", layout);

        CliResult render = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                root.resolve("fixtures/render-policy/uml-svg.json").toString(),
                "--metadata",
                renderMetadataFile.toString(),
                "--input",
                layoutFile.toString()
        }, "", env);

        JsonNode renderData = okData(render);
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
        assertThat(svg).contains(
                "data-dediren-node-shape=\"uml_device\"",
                "data-dediren-node-shape=\"uml_execution_environment\"",
                "data-dediren-node-shape=\"uml_artifact\"",
                "data-dediren-edge-id=\"deploy-orders-service\"");

        CliResult export = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                source.toString(),
                "--layout",
                layoutFile.toString()
        }, "", env);

        JsonNode exportData = okData(export);
        String xmi = exportData.at("/content").asText();
        assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(xmi).contains(
                "xmi:type=\"uml:Device\"",
                "xmi:type=\"uml:Artifact\"",
                "xmi:type=\"uml:Deployment\"",
                "xmi:type=\"uml:Manifestation\"",
                "xmi:type=\"uml:CommunicationPath\"");
    }

    private Path writeStdout(String fileName, CliResult result) throws Exception {
        Path path = temp.resolve(fileName);
        Files.writeString(path, result.stdout(), StandardCharsets.UTF_8);
        return path;
    }

    private JsonNode okData(CliResult result) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");
        return envelope.get("data");
    }

    private Map<String, String> sequenceWorkflowEnv() throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        env.putAll(pluginEnv("generic-graph", "dev.dediren.plugins.genericgraph.Main"));
        env.putAll(pluginEnv("elk-layout", "dev.dediren.plugins.elklayout.Main"));
        env.putAll(pluginEnv("svg-render", "dev.dediren.plugins.svgrender.Main"));
        env.putAll(pluginEnv("uml-xmi", "dev.dediren.plugins.umlxmi.Main"));
        env.putAll(envWithXmiSchema());
        return env;
    }

    private Map<String, String> pluginEnv(String pluginId, String mainClass) throws Exception {
        Path script = temp.resolve(pluginId + ".sh");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = pluginId.equals("elk-layout")
                ? elkLayoutPluginClasspath()
                : System.getProperty("java.class.path");
        Files.writeString(script, """
                #!/bin/sh
                exec "%s" -cp "%s" %s "$@"
                """.formatted(java, classpath, mainClass), StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return Map.of(
                "DEDIREN_PLUGIN_" + pluginId.toUpperCase().replace('-', '_'),
                script.toString());
    }

    private static String elkLayoutPluginClasspath() throws Exception {
        Path root = workspaceRoot();
        StringJoiner classpath = new StringJoiner(File.pathSeparator);
        classpath.add(System.getProperty("java.class.path"));
        classpath.add(root.resolve("plugins/elk-layout/target/classes").toString());
        Path repository = root.resolve(".cache/maven/repository");
        if (Files.exists(repository)) {
            try (var paths = Files.walk(repository)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(path -> classpath.add(path.toString()));
            }
        }
        return classpath.toString();
    }

    private Map<String, String> envWithXmiSchema() throws Exception {
        Path schemaPath = temp.resolve("XMI.xsd");
        Files.writeString(schemaPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            targetNamespace="http://www.omg.org/spec/XMI/20131001"
                            xmlns="http://www.omg.org/spec/XMI/20131001"
                            elementFormDefault="qualified">
                  <xsd:element name="XMI">
                    <xsd:complexType>
                      <xsd:choice minOccurs="0" maxOccurs="unbounded">
                        <xsd:any processContents="lax"/>
                      </xsd:choice>
                      <xsd:anyAttribute processContents="lax"/>
                    </xsd:complexType>
                  </xsd:element>
                </xsd:schema>
                """, StandardCharsets.UTF_8);
        return Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString());
    }

    private static void assertSequenceSvgGeometry(Document document) {
        Element frame = elementWithAttribute(document, "rect", "data-dediren-node-shape", "uml_interaction");
        double frameX = doubleAttribute(frame, "x");
        double frameY = doubleAttribute(frame, "y");
        double frameRight = frameX + doubleAttribute(frame, "width");
        double frameBottom = frameY + doubleAttribute(frame, "height");

        for (String lifelineId : List.of("customer", "service")) {
            Element lifeline = firstChildElement(groupWithAttribute(document, "data-dediren-node-id", lifelineId), "rect");
            double lifelineX = doubleAttribute(lifeline, "x");
            double lifelineY = doubleAttribute(lifeline, "y");
            double lifelineRight = lifelineX + doubleAttribute(lifeline, "width");
            double lifelineBottom = lifelineY + doubleAttribute(lifeline, "height");
            Element stem = elementWithAttribute(document, "line", "data-dediren-sequence-lifeline-stem", lifelineId);
            double stemX = doubleAttribute(stem, "x1");
            double stemY1 = doubleAttribute(stem, "y1");
            double stemY2 = doubleAttribute(stem, "y2");

            assertThat(lifelineX).isGreaterThan(frameX);
            assertThat(lifelineY).isGreaterThan(frameY);
            assertThat(lifelineRight).isLessThan(frameRight);
            assertThat(lifelineBottom).isLessThan(frameBottom);
            assertThat(stemX).isGreaterThanOrEqualTo(frameX);
            assertThat(stemX).isLessThanOrEqualTo(frameRight);
            assertThat(stemY1).isEqualTo(lifelineBottom);
            assertThat(stemY2).isGreaterThan(stemY1);
            assertThat(stemY2).isLessThanOrEqualTo(frameBottom);
        }

        for (String edgeId : List.of("m1", "m2", "m3")) {
            Element path = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", edgeId), "path");
            List<SvgPoint> points = pathPoints(path.getAttribute("d"));
            assertThat(points).hasSize(2);
            assertThat(points.getFirst().y()).isEqualTo(points.getLast().y());
            for (SvgPoint point : points) {
                assertThat(point.x()).isGreaterThanOrEqualTo(frameX);
                assertThat(point.x()).isLessThanOrEqualTo(frameRight);
                assertThat(point.y()).isGreaterThan(frameY);
                assertThat(point.y()).isLessThan(frameBottom);
            }
        }
    }

    private static Document svgDocument(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
    }

    private static Element groupWithAttribute(Document document, String name, String value) {
        var elements = document.getElementsByTagName("g");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (value.equals(element.getAttribute(name))) {
                return element;
            }
        }
        throw new AssertionError("expected SVG group with " + name + "=" + value);
    }

    private static Element elementWithAttribute(Document document, String tagName, String name, String value) {
        var elements = document.getElementsByTagName(tagName);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (value.equals(element.getAttribute(name))) {
                return element;
            }
        }
        throw new AssertionError("expected <" + tagName + "> with " + name + "=" + value);
    }

    private static Element firstChildElement(Element parent, String tagName) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(tagName)) {
                return child;
            }
        }
        throw new AssertionError("expected <" + parent.getTagName() + "> to contain <" + tagName + ">");
    }

    private static List<SvgPoint> pathPoints(String pathData) {
        String[] tokens = pathData.replace("M", "").replace("L", "").trim().split("\\s+");
        List<SvgPoint> points = new ArrayList<>();
        for (int index = 0; index < tokens.length; index += 2) {
            points.add(new SvgPoint(Double.parseDouble(tokens[index]), Double.parseDouble(tokens[index + 1])));
        }
        return points;
    }

    private static double doubleAttribute(Element element, String name) {
        return Double.parseDouble(element.getAttribute(name));
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

    private record SvgPoint(double x, double y) {
    }
}

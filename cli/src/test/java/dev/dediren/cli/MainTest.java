package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.SceneGraph;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import tools.jackson.databind.JsonNode;

class MainTest {
  @TempDir Path temp;

  @Test
  void versionCommandReportsProductVersion() {
    CliResult result = Main.executeForTesting(new String[] {"--version"}, "");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("dediren 2026.07.22");
  }

  @Test
  void sequenceFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
    Map<String, String> env = sequenceWorkflowEnv();
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-sequence-basic.json");

    CliResult validate = runValidate(env, source);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isEqualTo(3);
    assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(3);

    CliResult layoutRequest = runLayoutRequest(env, source, "sequence-view");

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("sequence-view");
    assertThat(layoutRequestData.get("constraints"))
        .extracting(constraint -> constraint.at("/kind").asText())
        .containsExactly("ordered-band:x", "ordered-band:y");
    Path layoutRequestFile = writeStdout("sequence-layout-request.json", layoutRequest);

    CliResult renderMetadata = runRenderMetadata(env, source, "sequence-view");

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(renderMetadataData.at("/nodes/interaction-place-order/type").asText())
        .isEqualTo("Interaction");
    assertThat(renderMetadataData.at("/edges/m1/type").asText()).isEqualTo("Message");
    assertThat(renderMetadataData.at("/edges/m1/properties/sequence").asInt()).isEqualTo(1);
    assertThat(renderMetadataData.at("/edges/m1/properties/message_sort").asText())
        .isEqualTo("synchCall");
    Path renderMetadataFile = writeStdout("sequence-render-metadata.json", renderMetadata);

    CliResult layout = runLayout(env, layoutRequestFile);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("sequence-view");
    assertThat(layoutData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("interaction-place-order", "customer", "service");
    Path layoutFile = writeStdout("sequence-layout-result.json", layout);

    CliResult render = runRender(env, root, renderMetadataFile, layoutFile);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/artifacts/0/content").asText();
    assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg)
        .contains(
            "<svg",
            "Place Order",
            "data-dediren-node-id=\"interaction-place-order\"",
            "data-dediren-edge-id=\"m1\"",
            "placeOrder");
    assertSequenceSvgGeometry(svgDocument(svg));

    CliResult export = runExport(env, root, source, layoutFile);

    JsonNode exportData = exportDataExpectingIdentityWarning(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi)
        .contains("xmi:XMI", "uml:Interaction", "id-interaction-place-order")
        .containsSubsequence("name=\"placeOrder\"", "name=\"accepted\"", "name=\"receiptReady\"");
  }

  @Test
  void sequenceFragmentsFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
    Map<String, String> env = sequenceWorkflowEnv();
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-sequence-fragments.json");

    CliResult validate = runValidate(env, source);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isGreaterThan(0);

    CliResult layoutRequest = runLayoutRequest(env, source, "sequence-fragments-view");

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("sequence-fragments-view");
    assertThat(layoutRequestData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .containsExactly("interaction-place-order", "customer", "service", "inventory", "payment")
        .doesNotContain("cf-availability");
    Path layoutRequestFile = writeStdout("sequence-fragments-layout-request.json", layoutRequest);

    CliResult renderMetadata = runRenderMetadata(env, source, "sequence-fragments-view");

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/nodes/cf-availability/type").asText())
        .isEqualTo("CombinedFragment");
    assertThat(renderMetadataData.at("/nodes/cf-availability/properties/operator").asText())
        .isEqualTo("alt");
    Path renderMetadataFile =
        writeStdout("sequence-fragments-render-metadata.json", renderMetadata);

    CliResult layout = runLayout(env, layoutRequestFile);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("sequence-fragments-view");
    Path layoutFile = writeStdout("sequence-fragments-layout-result.json", layout);

    CliResult render = runRender(env, root, renderMetadataFile, layoutFile);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/artifacts/0/content").asText();
    assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg).contains("data-dediren-sequence-combined-fragment=\"cf-availability\"");

    CliResult export = runExport(env, root, source, layoutFile);

    JsonNode exportData = exportDataExpectingIdentityWarning(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi).contains("uml:CombinedFragment", "interactionOperator=\"alt\"");
  }

  @Test
  void stateMachineFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
    Map<String, String> env = sequenceWorkflowEnv();
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-state-machine-basic.json");

    CliResult validate = runValidate(env, source);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isEqualTo(9);
    assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(6);

    CliResult layoutRequest = runLayoutRequest(env, source, "state-machine-view");

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("state-machine-view");
    Path layoutRequestFile = writeStdout("state-machine-layout-request.json", layoutRequest);

    CliResult renderMetadata = runRenderMetadata(env, source, "state-machine-view");

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/nodes/payment-choice/type").asText())
        .isEqualTo("Pseudostate");
    assertThat(renderMetadataData.at("/edges/t-approve/type").asText()).isEqualTo("Transition");
    Path renderMetadataFile = writeStdout("state-machine-render-metadata.json", renderMetadata);

    CliResult layout = runLayout(env, layoutRequestFile);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("state-machine-view");
    Path layoutFile = writeStdout("state-machine-layout-result.json", layout);

    CliResult render = runRender(env, root, renderMetadataFile, layoutFile);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/artifacts/0/content").asText();
    assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg)
        .contains(
            "<svg",
            "Order Lifecycle",
            "data-dediren-node-id=\"draft\"",
            "data-dediren-edge-id=\"t-submit\"");

    CliResult export = runExport(env, root, source, layoutFile);

    JsonNode exportData = exportDataExpectingIdentityWarning(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi).contains("uml:StateMachine", "id-order-lifecycle", "id-t-submit");
  }

  @Test
  void useCaseFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
    Map<String, String> env = sequenceWorkflowEnv();
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-use-case-basic.json");

    CliResult validate = runValidate(env, source);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isEqualTo(9);
    assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(5);

    CliResult layoutRequest = runLayoutRequest(env, source, "use-case-view");

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("use-case-view");
    assertThat(layoutRequestData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("customer", "place-order", "payment-extension");
    Path layoutRequestFile = writeStdout("use-case-layout-request.json", layoutRequest);

    CliResult renderMetadata = runRenderMetadata(env, source, "use-case-view");

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/nodes/customer/type").asText()).isEqualTo("Actor");
    assertThat(renderMetadataData.at("/nodes/place-order/type").asText()).isEqualTo("UseCase");
    assertThat(renderMetadataData.at("/nodes/payment-extension/type").asText())
        .isEqualTo("ExtensionPoint");
    assertThat(renderMetadataData.at("/edges/include-authentication/type").asText())
        .isEqualTo("Include");
    assertThat(renderMetadataData.at("/edges/extend-discount/properties/extension_point").asText())
        .isEqualTo("payment-extension");
    Path renderMetadataFile = writeStdout("use-case-render-metadata.json", renderMetadata);

    CliResult layout = runLayout(env, layoutRequestFile);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("use-case-view");
    assertThat(layoutData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("customer", "place-order", "authenticate-customer");
    Path layoutFile = writeStdout("use-case-layout-result.json", layout);

    CliResult render = runRender(env, root, renderMetadataFile, layoutFile);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/artifacts/0/content").asText();
    assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg)
        .contains(
            "<svg",
            "Order Service",
            "data-dediren-node-id=\"customer\"",
            "data-dediren-node-shape=\"uml_actor\"",
            "data-dediren-node-shape=\"uml_use_case\"",
            "data-dediren-node-id=\"payment-extension\"",
            "data-dediren-edge-id=\"include-authentication\"",
            "data-dediren-edge-id=\"extend-discount\"");

    CliResult export = runExport(env, root, source, layoutFile);

    JsonNode exportData = exportDataExpectingIdentityWarning(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi)
        .contains(
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

    CliResult validate = runValidate(env, source);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isEqualTo(8);
    assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(4);

    CliResult layoutRequest = runLayoutRequest(env, source, "component-view");

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("component-view");
    assertThat(layoutRequestData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("component-order-api", "port-rest-api", "interface-payment-gateway");
    Path layoutRequestFile = writeStdout("component-layout-request.json", layoutRequest);

    CliResult renderMetadata = runRenderMetadata(env, source, "component-view");

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/nodes/component-order-api/type").asText())
        .isEqualTo("Component");
    assertThat(renderMetadataData.at("/nodes/port-rest-api/type").asText()).isEqualTo("Port");
    assertThat(renderMetadataData.at("/edges/order-api-uses-payment/type").asText())
        .isEqualTo("Usage");
    Path renderMetadataFile = writeStdout("component-render-metadata.json", renderMetadata);

    CliResult layout = runLayout(env, layoutRequestFile);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("component-view");
    assertThat(layoutData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("component-order-api", "port-rest-api", "component-payment-adapter");
    Path layoutFile = writeStdout("component-layout-result.json", layout);

    CliResult render = runRender(env, root, renderMetadataFile, layoutFile);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/artifacts/0/content").asText();
    assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg)
        .contains(
            "data-dediren-node-shape=\"uml_component\"",
            "data-dediren-node-shape=\"uml_port\"",
            "data-dediren-edge-id=\"order-api-uses-payment\"",
            "PaymentGateway");

    CliResult export = runExport(env, root, source, layoutFile);

    JsonNode exportData = exportDataExpectingIdentityWarning(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi)
        .contains(
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

    CliResult validate = runValidate(env, source);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isEqualTo(7);
    assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(4);

    CliResult layoutRequest = runLayoutRequest(env, source, "deployment-view");

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("deployment-view");
    assertThat(layoutRequestData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("device-prod-node", "ee-orders-runtime", "artifact-orders-service");
    Path layoutRequestFile = writeStdout("deployment-layout-request.json", layoutRequest);

    CliResult renderMetadata = runRenderMetadata(env, source, "deployment-view");

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/nodes/device-prod-node/type").asText()).isEqualTo("Device");
    assertThat(renderMetadataData.at("/nodes/ee-orders-runtime/type").asText())
        .isEqualTo("ExecutionEnvironment");
    assertThat(renderMetadataData.at("/edges/deploy-orders-service/type").asText())
        .isEqualTo("Deployment");
    assertThat(renderMetadataData.at("/edges/artifact-manifests-order-api/type").asText())
        .isEqualTo("Manifestation");
    Path renderMetadataFile = writeStdout("deployment-render-metadata.json", renderMetadata);

    CliResult layout = runLayout(env, layoutRequestFile);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("deployment-view");
    assertThat(layoutData.get("nodes"))
        .extracting(node -> node.at("/id").asText())
        .contains("device-prod-node", "ee-orders-runtime", "deployment-spec-orders");
    Path layoutFile = writeStdout("deployment-layout-result.json", layout);

    CliResult render = runRender(env, root, renderMetadataFile, layoutFile);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/artifacts/0/content").asText();
    assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg)
        .contains(
            "data-dediren-node-shape=\"uml_device\"",
            "data-dediren-node-shape=\"uml_execution_environment\"",
            "data-dediren-node-shape=\"uml_artifact\"",
            "data-dediren-edge-id=\"deploy-orders-service\"");

    CliResult export = runExport(env, root, source, layoutFile);

    JsonNode exportData = exportDataExpectingIdentityWarning(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi)
        .contains(
            "xmi:type=\"uml:Device\"",
            "xmi:type=\"uml:Artifact\"",
            "xmi:type=\"uml:Deployment\"",
            "xmi:type=\"uml:Manifestation\"",
            "xmi:type=\"uml:CommunicationPath\"");
  }

  @Test
  void buildArtifactWriteCollisionEmitsErrorEnvelope(@TempDir Path tempDir) throws Exception {
    // --out points at an existing FILE, so Files.createDirectories(out/main) must fail.
    Path outCollision = Files.writeString(tempDir.resolve("out"), "occupied");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              workspaceRoot().resolve("fixtures/source/valid-basic.json").toString(),
              "--out",
              outCollision.toString(),
              "--render-policy",
              workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString()
            },
            "",
            sequenceWorkflowEnv());

    assertThat(result.exitCode()).isEqualTo(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_IO_FAILED");
    assertThat(envelope.at("/diagnostics/0/message").asText())
        .contains("failed to write build artifact");
  }

  @Test
  void misconfiguredBundleRootEmitsErrorEnvelopeOnPluginValidate(@TempDir Path tempDir)
      throws Exception {
    CliResult result =
        withBundleRootProperty(
            tempDir.resolve("missing"),
            () ->
                runValidate(
                    sequenceWorkflowEnv(),
                    workspaceRoot().resolve("fixtures/source/valid-basic.json")));

    assertProductRootUnresolved(result);
  }

  @Test
  void misconfiguredBundleRootEmitsErrorEnvelopeOnPlainValidate(@TempDir Path tempDir)
      throws Exception {
    CliResult result =
        withBundleRootProperty(
            tempDir.resolve("missing"),
            () ->
                Main.executeForTesting(
                    new String[] {
                      "validate",
                      "--input",
                      workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
                    },
                    "",
                    Map.of()));

    assertProductRootUnresolved(result);
  }

  @Test
  void misconfiguredBundleRootEmitsErrorEnvelopeOnProject(@TempDir Path tempDir) throws Exception {
    CliResult result =
        withBundleRootProperty(
            tempDir.resolve("missing"),
            () ->
                runLayoutRequest(
                    Map.of(), workspaceRoot().resolve("fixtures/source/valid-basic.json"), "main"));

    assertProductRootUnresolved(result);
  }

  @Test
  void misconfiguredBundleRootEmitsErrorEnvelopeOnExport(@TempDir Path tempDir) throws Exception {
    // The layout argument only needs to be a readable file: the export lane validates the source
    // document (which resolves the product root) before it ever parses the layout bytes.
    Path layoutStub = Files.writeString(tempDir.resolve("layout.json"), "{}");

    CliResult result =
        withBundleRootProperty(
            tempDir.resolve("missing"),
            () ->
                runExport(
                    Map.of(),
                    workspaceRoot(),
                    workspaceRoot().resolve("fixtures/source/valid-basic.json"),
                    layoutStub));

    assertProductRootUnresolved(result);
  }

  @Test
  void misconfiguredBundleRootEmitsErrorEnvelopeOnBuild(@TempDir Path tempDir) throws Exception {
    CliResult result =
        withBundleRootProperty(
            tempDir.resolve("missing"),
            () ->
                Main.executeForTesting(
                    new String[] {
                      "build",
                      "--input",
                      workspaceRoot().resolve("fixtures/source/valid-basic.json").toString(),
                      "--out",
                      tempDir.resolve("out").toString(),
                      "--render-policy",
                      workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString()
                    },
                    "",
                    Map.of()));

    assertProductRootUnresolved(result);
  }

  /**
   * Closes the audit gap left by core-level {@code EngineDispatchTest}: that suite proves an
   * unexpected engine exception maps to a {@code DEDIREN_ENGINE_FAILED} {@link
   * EngineExecutionException} with its cause intact, but nothing drives {@code
   * Main.writePluginError} itself end to end to prove the cli actually writes that cause's stack
   * trace to stderr alongside the stdout envelope.
   *
   * <p>Route taken: {@code Main} has no seam for injecting a fake {@link Engines} registry into a
   * real CLI invocation -- {@code EngineWiring.defaults()} is constructed inline inside the private
   * {@code commandLine()} factory, and {@code executeForTesting} does not accept an {@code Engines}
   * override. Adding one would be a production change, out of scope for this test-only wave.
   * Instead, this drives the exact real dispatch path a command's {@code call()} hits ({@link
   * CoreCommands#layoutCommand} over {@code EngineDispatch.dispatch}) with a fake {@link
   * LayoutEngine} that throws an unexpected {@link IllegalStateException}, producing a genuine
   * {@code DEDIREN_ENGINE_FAILED} exception with its cause attached exactly as the elk-layout
   * engine's real failures would. It then feeds that exception into {@code
   * Main.writePluginError(CommandSpec, EngineExecutionException)} -- the one remaining untested
   * link -- through a real picocli {@link CommandSpec}/{@link CommandLine} pair wired to {@link
   * StringWriter} out/err, reached via reflection because {@code writePluginError} has no
   * package-private test hook and none already exists to reuse.
   */
  @Test
  void unexpectedEngineFailureWritesEngineFailedEnvelopeAndCauseStackTraceToStderr()
      throws Exception {
    Engines engines = Engines.of(List.of(), List.of(new BoomLayoutEngine()), List.of(), List.of());

    EngineExecutionException error =
        catchThrowableOfType(
            () ->
                CoreCommands.layoutCommand(
                    "boom-layout",
                    "{\"layout_request_schema_version\":\""
                        + ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION
                        + "\"}",
                    Map.of(),
                    engines),
            EngineExecutionException.class);

    assertThat(error).isNotNull();
    assertThat(error.diagnostic().code()).isEqualTo("DEDIREN_ENGINE_FAILED");
    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);

    CommandSpec spec = CommandSpec.create();
    CommandLine commandLine = new CommandLine(spec);
    StringWriter stdout = new StringWriter();
    StringWriter stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout, true));
    commandLine.setErr(new PrintWriter(stderr, true));

    Method writePluginError =
        Main.class.getDeclaredMethod(
            "writePluginError", CommandSpec.class, EngineExecutionException.class);
    writePluginError.setAccessible(true);
    Object exitCode = writePluginError.invoke(null, spec, error);

    assertThat(exitCode).isEqualTo(CommandExitCode.PLUGIN_ERROR.code());
    JsonNode envelope = JsonSupport.objectMapper().readTree(stdout.toString());
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_ENGINE_FAILED");

    String stderrText = stderr.toString();
    assertThat(stderrText).contains(IllegalStateException.class.getName());
    assertThat(stderrText).contains("\tat ");
  }

  /** Throws from {@link #parseRequest} so {@code layout} is never reached (unreachable stub). */
  private static final class BoomLayoutEngine implements LayoutEngine {
    @Override
    public String id() {
      return "boom-layout";
    }

    @Override
    public SceneGraph parseRequest(byte[] input) {
      throw new IllegalStateException("boom");
    }

    @Override
    public EngineResult<LaidOutScene> layout(SceneGraph scene) {
      throw new UnsupportedOperationException("unreachable: parseRequest already threw");
    }
  }

  /**
   * Runs a CLI invocation with {@code dediren.bundle.root} pointed at {@code bundleRoot}, restoring
   * the previous property value (or clearing it) in a finally block. The property override is read
   * from JVM globals by DedirenPaths, so the env map the tests inject does not reach it.
   */
  private static CliResult withBundleRootProperty(Path bundleRoot, CliInvocation invocation)
      throws Exception {
    String previous = System.getProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY);
    System.setProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY, bundleRoot.toString());
    try {
      return invocation.run();
    } finally {
      if (previous == null) {
        System.clearProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY);
      } else {
        System.setProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY, previous);
      }
    }
  }

  @FunctionalInterface
  private interface CliInvocation {
    CliResult run() throws Exception;
  }

  private static void assertProductRootUnresolved(CliResult result) {
    assertThat(result.exitCode()).describedAs(result.stdout()).isEqualTo(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_PRODUCT_ROOT_UNRESOLVED");
    // Covers the cli-plumbing-to-message path end to end: DedirenPaths.requireProductRoot names
    // the misconfigured source (dediren.bundle.root here, since every caller of this helper routes
    // through withBundleRootProperty) in the diagnostic message it raises.
    assertThat(envelope.at("/diagnostics/0/message").asText())
        .isNotEmpty()
        .contains(DedirenPaths.BUNDLE_ROOT_PROPERTY);
  }

  private CliResult runValidate(Map<String, String> env, Path source) throws Exception {
    return Main.executeForTesting(
        new String[] {
          "validate", "--plugin", "generic-graph", "--profile", "uml", "--input", source.toString()
        },
        "",
        env);
  }

  private CliResult runLayoutRequest(Map<String, String> env, Path source, String viewId)
      throws Exception {
    return Main.executeForTesting(
        new String[] {
          "project",
          "--plugin",
          "generic-graph",
          "--target",
          "layout-request",
          "--view",
          viewId,
          "--input",
          source.toString()
        },
        "",
        env);
  }

  private CliResult runRenderMetadata(Map<String, String> env, Path source, String viewId)
      throws Exception {
    return Main.executeForTesting(
        new String[] {
          "project",
          "--plugin",
          "generic-graph",
          "--target",
          "render-metadata",
          "--view",
          viewId,
          "--input",
          source.toString()
        },
        "",
        env);
  }

  private CliResult runLayout(Map<String, String> env, Path layoutRequestFile) throws Exception {
    return Main.executeForTesting(
        new String[] {"layout", "--plugin", "elk-layout", "--input", layoutRequestFile.toString()},
        "",
        env);
  }

  private CliResult runRender(
      Map<String, String> env, Path root, Path renderMetadataFile, Path layoutFile)
      throws Exception {
    return Main.executeForTesting(
        new String[] {
          "render",
          "--plugin",
          "render",
          "--policy",
          root.resolve("fixtures/render-policy/uml-svg.json").toString(),
          "--metadata",
          renderMetadataFile.toString(),
          "--input",
          layoutFile.toString()
        },
        "",
        env);
  }

  private CliResult runExport(Map<String, String> env, Path root, Path source, Path layoutFile)
      throws Exception {
    return Main.executeForTesting(
        new String[] {
          "export",
          "--plugin",
          "uml-xmi",
          "--policy",
          root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
          "--source",
          source.toString(),
          "--layout",
          layoutFile.toString()
        },
        "",
        env);
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

  /**
   * The documented workflows reuse the shipped default export policy verbatim, so the export
   * envelope carries the identity-tripwire warning ({@code DEDIREN_EXPORT_IDENTITY_PLACEHOLDER});
   * content and exit code are unaffected.
   */
  private JsonNode exportDataExpectingIdentityWarning(CliResult result) throws Exception {
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    assertThat(result.stderr()).isEmpty();
    assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_EXPORT_IDENTITY_PLACEHOLDER");
    return envelope.get("data");
  }

  private Map<String, String> sequenceWorkflowEnv() throws Exception {
    // The engines run in-process; the only environment the workflow needs is the export lane's
    // offline XMI schema path.
    return new LinkedHashMap<>(envWithXmiSchema());
  }

  private Map<String, String> envWithXmiSchema() throws Exception {
    Path schemaPath = temp.resolve("XMI.xsd");
    Files.writeString(
        schemaPath,
        """
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
                """,
        StandardCharsets.UTF_8);
    return Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString());
  }

  private static void assertSequenceSvgGeometry(Document document) {
    Element frame =
        elementWithAttribute(document, "rect", "data-dediren-node-shape", "uml_interaction");
    double frameX = doubleAttribute(frame, "x");
    double frameY = doubleAttribute(frame, "y");
    double frameRight = frameX + doubleAttribute(frame, "width");
    double frameBottom = frameY + doubleAttribute(frame, "height");

    for (String lifelineId : List.of("customer", "service")) {
      Element lifeline =
          firstChildElement(
              groupWithAttribute(document, "data-dediren-node-id", lifelineId), "rect");
      double lifelineX = doubleAttribute(lifeline, "x");
      double lifelineY = doubleAttribute(lifeline, "y");
      double lifelineRight = lifelineX + doubleAttribute(lifeline, "width");
      double lifelineBottom = lifelineY + doubleAttribute(lifeline, "height");
      Element stem =
          elementWithAttribute(document, "line", "data-dediren-sequence-lifeline-stem", lifelineId);
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
      Element path =
          firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", edgeId), "path");
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

  private static Element elementWithAttribute(
      Document document, String tagName, String name, String value) {
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
      points.add(
          new SvgPoint(Double.parseDouble(tokens[index]), Double.parseDouble(tokens[index + 1])));
    }
    return points;
  }

  private static double doubleAttribute(Element element, String name) {
    return Double.parseDouble(element.getAttribute(name));
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }

  private record SvgPoint(double x, double y) {}
}

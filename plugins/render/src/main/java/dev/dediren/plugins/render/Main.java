package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.svg.SvgDocument.buildArtifacts;
import static dev.dediren.plugins.render.svg.SvgDocument.interactiveMode;
import static dev.dediren.plugins.render.svg.SvgDocument.renderSvg;

import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.plugins.render.node.uml.RenderInputValidator;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public final class Main {

  private Main() {}

  public static String moduleName() {
    return "render";
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args, System.in, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static PluginResult executeForTesting(String[] args, String stdin) throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) throws Exception {
    if (args.length > 0 && args[0].equals("capabilities")) {
      stdout.println(capabilitiesJson());
      return 0;
    }
    if (args.length > 0 && args[0].equals("render")) {
      return renderFromStdin(stdin, stdout);
    }
    stderr.println("expected command: capabilities or render");
    return 2;
  }

  private static String capabilitiesJson() throws IOException {
    ObjectNode root = JsonSupport.objectMapper().createObjectNode();
    root.put("plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION);
    root.put("id", "render");
    root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("render"));
    root.putObject("runtime").put("artifact_kind", "svg");
    return JsonSupport.objectMapper().writeValueAsString(root);
  }

  private static int renderFromStdin(InputStream stdin, PrintStream stdout) throws Exception {
    RenderInput input =
        JsonSupport.objectMapper().readValue(stdin.readAllBytes(), RenderInput.class);
    try {
      RenderInputValidator.validate(input.layoutResult(), input.renderMetadata(), input.policy());
    } catch (RenderInputValidator.PolicyValidationException error) {
      return exitWithDiagnostic(
          stdout, "DEDIREN_SVG_POLICY_INVALID", error.getMessage(), error.path());
    } catch (RenderInputValidator.RenderMetadataUsageException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
    } catch (ArchimateTypeValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (UmlValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    }

    String svg = renderSvg(input.layoutResult(), input.renderMetadata(), input.policy());
    List<RenderArtifact> artifacts = buildArtifacts(interactiveMode(input.policy()), svg);
    var result = new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts);
    stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
    return 0;
  }

  private static int exitWithDiagnostic(
      PrintStream stdout, String code, String message, String path) throws IOException {
    var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    return 3;
  }

  private record RenderInput(
      LayoutResult layoutResult, RenderMetadata renderMetadata, RenderPolicy policy) {}
}

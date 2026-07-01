package dev.dediren.plugins.elklayout;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class Main {
  private Main() {}

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
    if (args.length > 0 && args[0].equals("layout")) {
      return run(stdin, stdout);
    }
    if (args.length == 0) {
      return run(stdin, stdout);
    }
    stderr.println("expected command: capabilities or layout");
    return 2;
  }

  private static String capabilitiesJson() throws Exception {
    ObjectNode runtime = JsonSupport.objectMapper().createObjectNode();
    runtime.put("kind", "official-java-elk");
    runtime
        .putArray("algorithms")
        .add("org.eclipse.elk.layered")
        .add("org.eclipse.elk.rectpacking");
    return JsonSupport.objectMapper()
        .writeValueAsString(
            new dev.dediren.contracts.plugin.RuntimeCapabilities(
                ContractVersions.PLUGIN_PROTOCOL_VERSION,
                "elk-layout",
                java.util.List.of("layout"),
                runtime));
  }

  static int run(InputStream stdin, PrintStream stdout) throws Exception {
    LayoutRequest request;
    try {
      request = LayoutJson.readLayoutRequest(stdin);
    } catch (LayoutJson.LayoutPreferenceValidationException error) {
      stdout.println(
          EnvelopeWriter.error(
              "DEDIREN_ELK_LAYOUT_FAILED", "ELK layout failed: " + error.getMessage()));
      return 3;
    } catch (Exception error) {
      stdout.println(
          EnvelopeWriter.error(
              "DEDIREN_ELK_INPUT_INVALID_JSON",
              "layout request JSON is invalid: " + error.getMessage()));
      return 3;
    }

    try {
      LayoutResult result = new ElkLayoutEngine().layout(request);
      stdout.println(EnvelopeWriter.ok(result));
      return 0;
    } catch (Exception error) {
      stdout.println(
          EnvelopeWriter.error(
              "DEDIREN_ELK_LAYOUT_FAILED", "ELK layout failed: " + error.getMessage()));
      return 3;
    }
  }
}

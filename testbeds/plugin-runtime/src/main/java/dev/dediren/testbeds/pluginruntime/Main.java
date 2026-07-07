package dev.dediren.testbeds.pluginruntime;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class Main {
  private Main() {}

  /**
   * Fixed non-ASCII sentinel echoed back by the {@code report-nonascii} mode: Finnish Latin-1
   * letters (2-byte UTF-8) plus CJK ideographs (3-byte UTF-8). A plugin child JVM launched with a
   * stripped environment mangles every one of these code points to {@code '?'} unless the launcher
   * forces UTF-8 stream encoding (issue #47). Kept as a shared constant so the core round-trip test
   * asserts against exactly what the child emits.
   */
  public static final String NON_ASCII_SENTINEL = "Sähkö öäå 测试";

  public static void main(String[] args) throws Exception {
    if (System.getenv("DEDIREN_TEST_PLUGIN_PIPE_LEAK_CHILD") != null) {
      Thread.sleep(2_000);
      return;
    }

    if (args.length == 0) {
      System.err.println("expected command");
      System.exit(2);
      return;
    }

    if ("capabilities".equals(args[0])) {
      capabilities();
    } else {
      runCommand(args);
    }
  }

  public static String moduleName() {
    return "plugin-runtime-testbed";
  }

  private static void capabilities() throws Exception {
    switch (mode()) {
      case "capabilities-invalid-json" -> System.out.println("{");
      case "capabilities-nonzero" -> {
        System.err.println("capability probe failed by request");
        System.exit(7);
      }
      default -> {
        String id = envOrDefault("DEDIREN_TEST_PLUGIN_ID", "runtime-testbed");
        List<String> capabilities =
            Arrays.stream(envOrDefault("DEDIREN_TEST_PLUGIN_CAPABILITIES", "render").split(","))
                .filter(item -> !item.isEmpty())
                .toList();
        System.out.println(
            JsonSupport.objectMapper()
                .writeValueAsString(
                    new RuntimeCapabilities(
                        ContractVersions.PLUGIN_PROTOCOL_VERSION, id, capabilities, null)));
      }
    }
  }

  private static void runCommand(String[] args) throws Exception {
    if ("no-read-stdin".equals(mode())) {
      Thread.sleep(2_000);
      return;
    }

    String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    switch (mode()) {
      case "sleep" -> Thread.sleep(2_000);
      case "large-stdout" -> {
        System.out.println(
            JsonSupport.objectMapper()
                .writeValueAsString(
                    CommandEnvelope.ok(
                        JsonSupport.objectMapper()
                            .valueToTree(
                                new RenderResult(
                                    ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                                    List.of(
                                        new RenderArtifact("svg", "x".repeat(1024 * 1024))))))));
        return;
      }
      case "large-output" ->
          System.err.write("x".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8));
      case "invalid-json" -> {
        System.out.println("not-json");
        return;
      }
      case "invalid-envelope" -> {
        System.out.println("{\"status\":\"ok\"}");
        return;
      }
      case "invalid-data" -> {
        var data = JsonSupport.objectMapper().createObjectNode();
        data.put("accepted", true);
        data.put("input_length", input.length());
        System.out.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(data)));
        return;
      }
      case "error-envelope" -> {
        printErrorEnvelope();
        System.exit(3);
        return;
      }
      case "error-envelope-zero" -> {
        printErrorEnvelope();
        return;
      }
      case "ok-envelope-nonzero" -> {
        // Valid ok envelope but a non-zero exit: the only state that reaches
        // DEDIREN_PLUGIN_PROCESS_FAILED in PluginRunner.normalizePluginOutput.
        System.out.println(
            JsonSupport.objectMapper()
                .writeValueAsString(CommandEnvelope.ok(successData(args, input.length()))));
        System.exit(3);
        return;
      }
      case "report-env" -> {
        // Echo the value the child process actually received for the env var named by
        // DEDIREN_TEST_REPORT_ENV. Used to prove core forwards a manifest-listed variable (e.g. a
        // proxy var) across the process boundary.
        String name = envOrDefault("DEDIREN_TEST_REPORT_ENV", "");
        var diagnostic =
            new Diagnostic(
                "DEDIREN_TESTBED_ENV", DiagnosticSeverity.ERROR, envOrDefault(name, ""), "$.env");
        System.out.println(
            JsonSupport.objectMapper()
                .writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
        return;
      }
      case "report-cwd" -> {
        var diagnostic =
            new Diagnostic(
                "DEDIREN_TESTBED_CWD",
                DiagnosticSeverity.ERROR,
                System.getProperty("user.dir"),
                "$.cwd");
        System.out.println(
            JsonSupport.objectMapper()
                .writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
        return;
      }
      case "report-nonascii" -> {
        // Echo a fixed non-ASCII sentinel back through System.out. When core launches this child
        // with a stripped environment (no locale), the JVM derives stdout.encoding from
        // native.encoding = US-ASCII and every non-ASCII code point is written as '?' (issue #47).
        // The launcher must force UTF-8 stream encoding so the sentinel round-trips unchanged.
        var diagnostic =
            new Diagnostic(
                "DEDIREN_TESTBED_NONASCII",
                DiagnosticSeverity.ERROR,
                NON_ASCII_SENTINEL,
                "$.label");
        System.out.println(
            JsonSupport.objectMapper()
                .writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
        return;
      }
      default -> {}
    }

    System.out.println(
        JsonSupport.objectMapper()
            .writeValueAsString(CommandEnvelope.ok(successData(args, input.length()))));
  }

  private static JsonNode successData(String[] args, int inputLength) {
    String command = args.length == 0 ? "" : args[0];
    var mapper = JsonSupport.objectMapper();
    return switch (command) {
      case "render" ->
          mapper.valueToTree(
              new RenderResult(
                  ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                  List.of(
                      new RenderArtifact(
                          "svg", "<svg data-input-length=\"" + inputLength + "\"></svg>"))));
      case "layout" ->
          mapper.valueToTree(
              new LayoutResult(
                  ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
                  "test",
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of()));
      case "export" -> {
        var data = mapper.createObjectNode();
        data.put("export_result_schema_version", ContractVersions.EXPORT_RESULT_SCHEMA_VERSION);
        data.put(
            "artifact_kind",
            envOrDefault("DEDIREN_TEST_PLUGIN_ARTIFACT_KIND", "archimate-oef+xml"));
        data.put("content", "<model></model>");
        yield data;
      }
      default -> {
        var data = mapper.createObjectNode();
        data.put("accepted", true);
        data.put("input_length", inputLength);
        yield data;
      }
    };
  }

  private static void printErrorEnvelope() throws IOException {
    var diagnostic =
        new Diagnostic(
            "DEDIREN_TESTBED_ERROR",
            DiagnosticSeverity.ERROR,
            "fixture plugin returned a structured error",
            "$.testbed");
    System.out.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
  }

  private static String mode() {
    return envOrDefault("DEDIREN_TEST_PLUGIN_MODE", "ok");
  }

  private static String envOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null ? defaultValue : value;
  }
}

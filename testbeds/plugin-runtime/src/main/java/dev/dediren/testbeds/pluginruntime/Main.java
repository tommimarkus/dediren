package dev.dediren.testbeds.pluginruntime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import dev.dediren.contracts.render.RenderResult;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private Main() {
    }

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
                List<String> capabilities = Arrays.stream(envOrDefault("DEDIREN_TEST_PLUGIN_CAPABILITIES", "render")
                                .split(","))
                        .filter(item -> !item.isEmpty())
                        .toList();
                System.out.println(JsonSupport.objectMapper().writeValueAsString(new RuntimeCapabilities(
                        ContractVersions.PLUGIN_PROTOCOL_VERSION,
                        id,
                        capabilities,
                        null)));
            }
        }
    }

    private static void runCommand(String[] args) throws Exception {
        if ("no-read-stdin".equals(mode())) {
            Thread.sleep(2_000);
            return;
        }

        String input = new String(System.in.readAllBytes());
        switch (mode()) {
            case "sleep" -> Thread.sleep(2_000);
            case "large-stdout" -> {
                System.out.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(
                        JsonSupport.objectMapper().valueToTree(new RenderResult(
                                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                                "svg",
                                "x".repeat(1024 * 1024))))));
                return;
            }
            case "large-output" -> System.err.write("x".repeat(1024 * 1024).getBytes());
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
            default -> {
            }
        }

        System.out.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(successData(args, input.length()))));
    }

    private static JsonNode successData(String[] args, int inputLength) {
        String command = args.length == 0 ? "" : args[0];
        var mapper = JsonSupport.objectMapper();
        return switch (command) {
            case "render" -> mapper.valueToTree(new RenderResult(
                    ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                    "svg",
                    "<svg data-input-length=\"" + inputLength + "\"></svg>"));
            case "layout" -> mapper.valueToTree(new LayoutResult(
                    ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
                    "test",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()));
            case "export" -> {
                var data = mapper.createObjectNode();
                data.put("export_result_schema_version", ContractVersions.EXPORT_RESULT_SCHEMA_VERSION);
                data.put("artifact_kind", "archimate-oef+xml");
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
        var diagnostic = new Diagnostic(
                "DEDIREN_TESTBED_ERROR",
                DiagnosticSeverity.ERROR,
                "fixture plugin returned a structured error",
                "$.testbed");
        System.out.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    }

    private static String mode() {
        return envOrDefault("DEDIREN_TEST_PLUGIN_MODE", "ok");
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }
}

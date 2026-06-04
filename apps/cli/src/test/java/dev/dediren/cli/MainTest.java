package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(result.stdout()).contains("dediren 0.20.0");
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
        classpath.add(root.resolve("modules/plugins/elk-layout/target/classes").toString());
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

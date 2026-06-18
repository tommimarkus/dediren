package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLayoutRenderCommandTest {
    @TempDir
    Path temp;

    @Test
    void validateLayoutReportsQualityFromFile() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate-layout",
                "--input",
                workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/data/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/overlap_count").asInt()).isZero();
        assertThat(envelope.at("/data/group_label_band_issue_count").asInt()).isZero();
        assertThat(envelope.at("/data/label_space_issue_count").asInt()).isZero();
        // Presence pin, not value pin: the fixture's crossing topology is ELK-determined, not spec-derived.
        assertThat(envelope.at("/data/edge_crossing_count").isInt()).isTrue();
    }

    @Test
    void validateLayoutAcceptsSequenceLifelineMessageEndpoints() throws Exception {
        Path fixture = workspaceRoot().resolve("fixtures/layout-result/uml-sequence-validatable.json");

        CliResult result = Main.executeForTesting(new String[]{
                "validate-layout",
                "--input",
                fixture.toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/data/status").asText()).isEqualTo("ok");

        // Negative control: identical geometry without the lifeline role still trips the perimeter check,
        // proving the acceptance is gated on role rather than loosened for everyone.
        String roleless = Files.readString(fixture).replace(",\n      \"role\": \"lifeline\"", "");
        Path stripped = temp.resolve("sequence-without-role.json");
        Files.writeString(stripped, roleless);

        CliResult control = Main.executeForTesting(new String[]{
                "validate-layout",
                "--input",
                stripped.toString()
        }, "");

        JsonNode controlEnvelope = JsonSupport.objectMapper().readTree(control.stdout());

        assertThat(control.exitCode()).isEqualTo(2);
        assertThat(controlEnvelope.at("/diagnostics/0/code").asText())
                .isEqualTo("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    }

    @Test
    void validateLayoutRejectsEmptyRoutesAndEndpointMisses() throws Exception {
        Path layout = temp.resolve("invalid-route-layout.json");
        Files.writeString(layout, """
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "source", "source_id": "source", "projection_id": "source", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "Source" },
                    { "id": "target", "source_id": "target", "projection_id": "target", "x": 300.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "Target" }
                  ],
                  "edges": [
                    { "id": "empty", "source": "source", "target": "target", "source_id": "empty", "projection_id": "empty", "routing_hints": [], "points": [], "label": "empty" },
                    { "id": "misses-target", "source": "source", "target": "target", "source_id": "misses-target", "projection_id": "misses-target", "routing_hints": [], "points": [{"x": 100.0, "y": 40.0}, {"x": 250.0, "y": 40.0}], "label": "misses target" }
                  ],
                  "groups": [],
                  "warnings": []
                }
                """);

        CliResult result = Main.executeForTesting(new String[]{
                "validate-layout",
                "--input",
                layout.toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText())
                .isEqualTo("DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY");
        assertThat(envelope.at("/diagnostics/1/code").asText())
                .isEqualTo("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    }

    @Test
    void layoutMissingInputFileReturnsJsonEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                temp.resolve("missing-layout-request.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    }

    @Test
    void renderMissingPolicyFileReturnsJsonEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "render",
                "--policy",
                temp.resolve("missing-policy.json").toString(),
                "--input",
                workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    }

    @Test
    void projectCommandRunsJavaGenericGraphPlugin() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "project",
                "--plugin",
                "generic-graph",
                "--target",
                "layout-request",
                "--view",
                "main",
                "--input",
                workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
        }, "", pluginEnv("generic-graph", "dev.dediren.plugins.genericgraph.Main"));

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/layout_request_schema_version").asText()).isEqualTo("layout-request.schema.v1");
        assertThat(envelope.at("/data/view_id").asText()).isEqualTo("main");
        assertThat(envelope.at("/data/nodes")).hasSize(2);
        assertThat(envelope.at("/data/edges")).hasSize(1);
        assertThat(envelope.at("/data/edges/0/relationship_type").asText()).isEqualTo("generic.calls");
    }

    @Test
    void renderCommandRunsJavaSvgPlugin() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "render",
                "--policy",
                workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString(),
                "--input",
                workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
        }, "", pluginEnv("render", "dev.dediren.plugins.render.Main"));

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
        assertThat(envelope.at("/data/artifacts/0/content").asText())
                .contains("<svg", "data-dediren-node-id=\"client\"", "data-dediren-edge-id=\"client-calls-api\"")
                .doesNotContain("data-dediren-edge-label-background");
    }

    @Test
    void exportCommandRunsJavaArchimateOefPlugin() throws Exception {
        Map<String, String> env = pluginEnv("archimate-oef", "dev.dediren.plugins.archimateoef.Main");
        env.putAll(envWithOefSchemas());

        CliResult result = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "archimate-oef",
                "--policy",
                workspaceRoot().resolve("fixtures/export-policy/default-oef.json").toString(),
                "--source",
                workspaceRoot().resolve("fixtures/source/valid-archimate-oef.json").toString(),
                "--layout",
                workspaceRoot().resolve("fixtures/layout-result/archimate-oef-basic.json").toString()
        }, "", env);

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/artifact_kind").asText()).isEqualTo("archimate-oef+xml");
        assertThat(envelope.at("/data/content").asText())
                .contains("<model", "identifier=\"id-dediren-oef-basic-model\"", "xsi:type=\"ApplicationComponent\"");
    }

    @Test
    void exportCommandRunsJavaUmlXmiPlugin() throws Exception {
        Map<String, String> env = pluginEnv("uml-xmi", "dev.dediren.plugins.umlxmi.Main");
        env.putAll(envWithXmiSchema());

        CliResult result = Main.executeForTesting(new String[]{
                "export",
                "--plugin",
                "uml-xmi",
                "--policy",
                workspaceRoot().resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source",
                workspaceRoot().resolve("fixtures/source/valid-uml-basic.json").toString(),
                "--layout",
                workspaceRoot().resolve("fixtures/layout-result/uml-basic.json").toString()
        }, "", env);

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(envelope.at("/data/content").asText())
                .contains("xmi:XMI", "<uml:Model", "xmi:type=\"uml:Class\"");
    }

    private Map<String, String> pluginEnv(String pluginId, String mainClass) throws Exception {
        Path script = temp.resolve(pluginId + ".sh");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Files.writeString(script, """
                #!/bin/sh
                exec "%s" -cp "%s" %s "$@"
                """.formatted(java, classpath, mainClass), StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return new LinkedHashMap<>(Map.of(
                "DEDIREN_PLUGIN_" + pluginId.toUpperCase().replace('-', '_'),
                script.toString()));
    }

    private Map<String, String> envWithOefSchemas() throws Exception {
        Path schemaDir = temp.resolve("oef-schemas");
        Files.createDirectories(schemaDir);
        String schema = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                  targetNamespace="http://www.opengroup.org/xsd/archimate/3.0/"
                  xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                  elementFormDefault="qualified"
                  attributeFormDefault="unqualified">
                  <xs:element name="model">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/>
                      </xs:sequence>
                      <xs:attribute name="identifier" type="xs:ID" use="required"/>
                      <xs:anyAttribute namespace="##any" processContents="lax"/>
                    </xs:complexType>
                  </xs:element>
                  <xs:complexType name="ApplicationComponent" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="ApplicationService" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Grouping" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="AndJunction" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Realization" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Flow" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Composition" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Element" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Relationship" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Diagram" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                </xs:schema>
                """;
        for (String fileName : java.util.List.of(
                "archimate3_Model.xsd",
                "archimate3_View.xsd",
                "archimate3_Diagram.xsd")) {
            Files.writeString(schemaDir.resolve(fileName), schema, StandardCharsets.UTF_8);
        }
        return Map.of("DEDIREN_OEF_SCHEMA_DIR", schemaDir.toString());
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

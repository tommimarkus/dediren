package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maintenance-only baseline refresh for the checked-in {@code fixtures/export/*.xmi} goldens. Each
 * golden runs the shipped default policy through the same {@code Main.executeForTesting} export
 * path MainTest's {@code isEqualTo} cases assert against (including their per-golden input tweaks),
 * so a regenerated golden is byte-identical to what MainTest expects. Disabled by default; run
 * explicitly: {@code ./mvnw -pl engines/uml-xmi-export -am test -Dtest=GoldenExportRegenerator
 * -Ddediren.regen.xmi=true -Dsurefire.failIfNoSpecifiedTests=false} then review the diff.
 */
@EnabledIfSystemProperty(named = "dediren.regen.xmi", matches = "true")
class GoldenExportRegenerator {

  @TempDir Path tempDir;

  @Test
  void regenerateAllGoldens() throws Exception {
    write("uml-basic", "valid-uml-basic", "uml-basic", null);
    write("uml-complex-class", "valid-uml-complex", "uml-complex-class", null);
    write(
        "uml-sequence-basic",
        "valid-uml-sequence-basic",
        "uml-sequence-basic",
        input ->
            ((ObjectNode) input.at("/source/relationships/0/properties/uml"))
                .remove("message_sort"));
    write("uml-sequence-fragments", "valid-uml-sequence-fragments", "uml-sequence-fragments", null);
    write(
        "uml-state-machine-basic",
        "valid-uml-state-machine-basic",
        "uml-state-machine-basic",
        null);
    write("uml-use-case-basic", "valid-uml-use-case-basic", "uml-use-case-basic", null);
    write("uml-component-basic", "valid-uml-component-basic", "uml-component-basic", null);
    write("uml-deployment-basic", "valid-uml-deployment-basic", "uml-deployment-basic", null);
    write("uml-activity", "valid-uml-basic", "uml-activity", null);
    write("uml-data", "valid-uml-basic", "uml-data", null);
    write("uml-generalization", "valid-uml-generalization", "uml-generalization", null);
  }

  private void write(String golden, String source, String layout, Consumer<JsonNode> mutate)
      throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/" + source + ".json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/" + layout + ".json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
    if (mutate != null) {
      mutate.accept(input);
    }
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, JsonSupport.objectMapper().writeValueAsString(input), env());
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    String content = envelope.at("/data/content").asText();
    Files.writeString(
        workspaceRoot().resolve("fixtures/export/" + golden + ".xmi"),
        content,
        StandardCharsets.UTF_8);
  }

  private Map<String, String> env() throws Exception {
    Path schemaPath = tempDir.resolve("XMI.xsd");
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

  private static JsonNode fixtureJson(String path) throws Exception {
    return JsonSupport.objectMapper().readTree(Files.readString(workspaceRoot().resolve(path)));
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

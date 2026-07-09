package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.engine.Engines;
import dev.dediren.testsupport.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Envelope-contract regression gate, the successor of the Task-5 parity suite: every stage command,
 * driven through the in-memory engine registry ({@link CoreCommands} + {@link
 * EngineWiring#defaults()}), must keep producing a schema-valid stdout envelope with its published
 * exit code — success rows plus one published error row per engine, and the registry's own
 * unknown-id / unsupported-capability diagnostics.
 */
class EngineEnvelopeContractTest {

  @TempDir Path temp;

  // --- generic-graph: semantic validation ------------------------------------------------------

  @Test
  void validateArchimateProfileEmitsOkEnvelope() throws Exception {
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");

    PluginRunOutcome outcome =
        CoreCommands.semanticValidateCommand(
            "generic-graph", "archimate", source, base, Map.of(), engines());

    assertOk(outcome);
  }

  @Test
  void validateUmlProfileEmitsOkEnvelope() throws Exception {
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");

    PluginRunOutcome outcome =
        CoreCommands.semanticValidateCommand(
            "generic-graph", "uml", source, base, Map.of(), engines());

    assertOk(outcome);
  }

  @Test
  void validateMissingProfileKeepsPublishedErrorRow() throws Exception {
    // Published error row: missing --profile wins with the enveloped
    // DEDIREN_SEMANTIC_PROFILE_REQUIRED before the engine does any work.
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");

    PluginRunOutcome outcome =
        CoreCommands.semanticValidateCommand(
            "generic-graph", null, source, base, Map.of(), engines());

    assertDiagnostic(outcome, 3, "DEDIREN_SEMANTIC_PROFILE_REQUIRED");
  }

  // --- generic-graph: projection ---------------------------------------------------------------

  @Test
  void projectLayoutRequestEmitsOkEnvelope() throws Exception {
    String source = read("fixtures/source/valid-basic.json");
    Path base = path("fixtures/source");

    PluginRunOutcome outcome =
        CoreCommands.projectCommand(
            "generic-graph", "layout-request", "main", source, base, Map.of(), engines());

    assertOk(outcome);
  }

  @Test
  void projectRenderMetadataEmitsOkEnvelope() throws Exception {
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");

    PluginRunOutcome outcome =
        CoreCommands.projectCommand(
            "generic-graph", "render-metadata", "class-view", source, base, Map.of(), engines());

    assertOk(outcome);
  }

  // --- elk-layout ------------------------------------------------------------------------------

  @Test
  void layoutEmitsOkEnvelope() throws Exception {
    String request = read("fixtures/layout-request/basic.json");

    PluginRunOutcome outcome =
        CoreCommands.layoutCommand("elk-layout", request, Map.of(), engines());

    assertOk(outcome);
  }

  @Test
  void layoutInvalidJsonKeepsPublishedParseRow() throws Exception {
    // Published parse row: well-formed JSON that is not a valid layout request is routed through
    // ElkEngine.parseRequest, yielding DEDIREN_ELK_INPUT_INVALID_JSON / 3.
    String invalid = "{\"unexpected\":\"field\"}";

    PluginRunOutcome outcome =
        CoreCommands.layoutCommand("elk-layout", invalid, Map.of(), engines());

    assertDiagnostic(outcome, 3, "DEDIREN_ELK_INPUT_INVALID_JSON");
  }

  // --- render ----------------------------------------------------------------------------------

  @Test
  void renderWithoutMetadataEmitsOkEnvelope() throws Exception {
    String policy = read("fixtures/render-policy/default-svg.json");
    String layout = read("fixtures/layout-result/basic.json");

    PluginRunOutcome outcome =
        CoreCommands.renderCommand("render", policy, null, layout, Map.of(), engines());

    assertOk(outcome);
  }

  @Test
  void renderWithMetadataEmitsOkEnvelope() throws Exception {
    String policy = read("fixtures/render-policy/uml-svg.json");
    String layout = read("fixtures/layout-result/uml-basic.json");
    String metadata = read("fixtures/render-metadata/uml-basic.json");

    PluginRunOutcome outcome =
        CoreCommands.renderCommand("render", policy, metadata, layout, Map.of(), engines());

    assertOk(outcome);
  }

  @Test
  void renderInvalidPolicyKeepsPublishedErrorRow() throws Exception {
    String policy =
        "{\"render_policy_schema_version\":\"render-policy.schema.v3\","
            + "\"page\":{\"width\":100,\"height\":100},"
            + "\"margin\":{\"top\":0,\"right\":0,\"bottom\":0,\"left\":0},"
            + "\"style\":{\"node\":{\"fill\":\"notacolor#\"}}}";
    String layout = read("fixtures/layout-result/basic.json");

    PluginRunOutcome outcome =
        CoreCommands.renderCommand("render", policy, null, layout, Map.of(), engines());

    assertDiagnostic(outcome, 3, "DEDIREN_SVG_POLICY_INVALID");
  }

  // --- archimate-oef export --------------------------------------------------------------------

  @Test
  void exportArchimateOefEmitsOkEnvelope() throws Exception {
    Map<String, String> schema = envWithOefSchemas();
    String policy = read("fixtures/export-policy/default-oef.json");
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/archimate-oef-basic.json");

    PluginRunOutcome outcome =
        CoreCommands.exportCommand(
            "archimate-oef", policy, source, base, layout, schema, engines());

    assertOk(outcome);
  }

  @Test
  void exportOefInvalidPolicyKeepsPublishedErrorRow() throws Exception {
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/archimate-oef-basic.json");

    PluginRunOutcome outcome =
        CoreCommands.exportCommand(
            "archimate-oef", "{}", source, base, layout, Map.of(), engines());

    assertDiagnostic(outcome, 3, "DEDIREN_OEF_POLICY_INVALID");
  }

  // --- uml-xmi export --------------------------------------------------------------------------

  @Test
  void exportUmlXmiEmitsOkEnvelope() throws Exception {
    Map<String, String> schema = envWithXmiSchema();
    String policy = read("fixtures/export-policy/default-uml-xmi.json");
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/uml-basic.json");

    PluginRunOutcome outcome =
        CoreCommands.exportCommand("uml-xmi", policy, source, base, layout, schema, engines());

    assertOk(outcome);
  }

  @Test
  void exportUmlXmiInvalidPolicyKeepsPublishedErrorRow() throws Exception {
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/uml-basic.json");

    PluginRunOutcome outcome =
        CoreCommands.exportCommand("uml-xmi", "{}", source, base, layout, Map.of(), engines());

    assertDiagnostic(outcome, 3, "DEDIREN_UML_XMI_POLICY_INVALID");
  }

  // --- registry diagnostics through the CLI ----------------------------------------------------

  @Test
  void unknownEngineIdYieldsPublishedUnknownDiagnostic() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "layout",
              "--plugin",
              "no-such-engine",
              "--input",
              root().resolve("fixtures/layout-request/basic.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_PLUGIN_UNKNOWN");
    assertSchemaValid(envelope);
  }

  @Test
  void wrongCapabilityYieldsPublishedUnsupportedCapabilityDiagnostic() throws Exception {
    // "render" is a bound engine id, but it is not a layout engine.
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "layout",
              "--plugin",
              "render",
              "--input",
              root().resolve("fixtures/layout-request/basic.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY");
    assertSchemaValid(envelope);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Engines engines() {
    return EngineWiring.defaults();
  }

  private void assertOk(PluginRunOutcome outcome) {
    assertThat(outcome.exitCode()).describedAs(outcome.stdout()).isZero();
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertSchemaValid(envelope);
  }

  private void assertDiagnostic(PluginRunOutcome outcome, int exitCode, String code) {
    assertThat(outcome.exitCode()).describedAs(outcome.stdout()).isEqualTo(exitCode);
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(code);
    assertSchemaValid(envelope);
  }

  private void assertSchemaValid(JsonNode envelope) {
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/envelope.schema.json", envelope);
    assertThat(errors).describedAs("envelope schema validity: %s", envelope).isEmpty();
  }

  private Map<String, String> envWithOefSchemas() throws Exception {
    Path schemaDir = temp.resolve("oef-schemas");
    Files.createDirectories(schemaDir);
    String schema =
        """
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
    for (String fileName :
        List.of("archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd")) {
      Files.writeString(schemaDir.resolve(fileName), schema, StandardCharsets.UTF_8);
    }
    return new LinkedHashMap<>(Map.of("DEDIREN_OEF_SCHEMA_DIR", schemaDir.toString()));
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
    return new LinkedHashMap<>(Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString()));
  }

  private static String read(String relativePath) throws Exception {
    return Files.readString(root().resolve(relativePath));
  }

  private static Path path(String relativePath) {
    return root().resolve(relativePath);
  }

  private static Path root() {
    return TestSupport.workspaceRoot();
  }
}

package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.plugins.PluginRegistry;
import dev.dediren.core.plugins.PluginRunOptions;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.plugins.PluginRunner;
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
 * The strangler parity gate (Task 5): every stage command must produce a JSON-value-equal,
 * schema-valid stdout envelope with an identical exit code whether it runs through the new
 * in-memory engine dispatch ({@link CoreCommands} + {@link EngineWiring#defaults()}) or the process
 * boundary ({@link PluginRunner} against the bundled first-party plugin, driven with the
 * script-wrapper technique {@code CliLayoutRenderCommandTest} already uses). Success rows plus one
 * published error row per engine prove the two transports are observationally equivalent before
 * Task 8 deletes the process one.
 */
class InMemoryParityTest {
  private static final String GG_MAIN = "dev.dediren.plugins.genericgraph.Main";
  private static final String ELK_MAIN = "dev.dediren.plugins.elklayout.Main";
  private static final String RENDER_MAIN = "dev.dediren.plugins.render.Main";
  private static final String OEF_MAIN = "dev.dediren.plugins.archimateoef.Main";
  private static final String XMI_MAIN = "dev.dediren.plugins.umlxmi.Main";

  @TempDir Path temp;

  // --- generic-graph: semantic validation ------------------------------------------------------

  @Test
  void validateArchimateProfileParity() throws Exception {
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");

    PluginRunOutcome inMemory =
        CoreCommands.semanticValidateCommand(
            "generic-graph", "archimate", source, base, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.semanticValidateCommand(
            "generic-graph", "archimate", source, base, pluginEnv("generic-graph", GG_MAIN));

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void validateUmlProfileParity() throws Exception {
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");

    PluginRunOutcome inMemory =
        CoreCommands.semanticValidateCommand(
            "generic-graph", "uml", source, base, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.semanticValidateCommand(
            "generic-graph", "uml", source, base, pluginEnv("generic-graph", GG_MAIN));

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void validateMissingProfileParity() throws Exception {
    // Published error row: missing --profile wins with the enveloped
    // DEDIREN_SEMANTIC_PROFILE_REQUIRED
    // before stdin is parsed; the process leg feeds raw stdin to the plugin without a --profile
    // arg.
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");

    PluginRunOutcome inMemory =
        CoreCommands.semanticValidateCommand(
            "generic-graph", null, source, base, Map.of(), engines());
    PluginRunOutcome process =
        directProcess(
            "generic-graph", GG_MAIN, "semantic-validation", List.of("validate"), source, Map.of());

    assertParity(inMemory, process);
    assertDiagnostic(inMemory, 3, "DEDIREN_SEMANTIC_PROFILE_REQUIRED");
  }

  // --- generic-graph: projection ---------------------------------------------------------------

  @Test
  void projectLayoutRequestParity() throws Exception {
    String source = read("fixtures/source/valid-basic.json");
    Path base = path("fixtures/source");

    PluginRunOutcome inMemory =
        CoreCommands.projectCommand(
            "generic-graph", "layout-request", "main", source, base, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.projectCommand(
            "generic-graph",
            "layout-request",
            "main",
            source,
            base,
            pluginEnv("generic-graph", GG_MAIN));

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void projectRenderMetadataParity() throws Exception {
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");

    PluginRunOutcome inMemory =
        CoreCommands.projectCommand(
            "generic-graph", "render-metadata", "class-view", source, base, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.projectCommand(
            "generic-graph",
            "render-metadata",
            "class-view",
            source,
            base,
            pluginEnv("generic-graph", GG_MAIN));

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  // --- elk-layout ------------------------------------------------------------------------------

  @Test
  void layoutParity() throws Exception {
    String request = read("fixtures/layout-request/basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.layoutCommand("elk-layout", request, Map.of(), engines());
    PluginRunOutcome process =
        directProcess("elk-layout", ELK_MAIN, "layout", List.of("layout"), request, Map.of());

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void layoutInvalidJsonParity() throws Exception {
    // Published parse row: a well-formed JSON that is not a valid layout request is routed through
    // ElkEngine.parseRequest on both legs (the in-memory leg unwraps first, but this is not an
    // envelope, so the same bytes reach the parser), yielding DEDIREN_ELK_INPUT_INVALID_JSON / 3.
    String invalid = "{\"unexpected\":\"field\"}";

    PluginRunOutcome inMemory =
        CoreCommands.layoutCommand("elk-layout", invalid, Map.of(), engines());
    PluginRunOutcome process =
        directProcess("elk-layout", ELK_MAIN, "layout", List.of("layout"), invalid, Map.of());

    assertParity(inMemory, process);
    assertDiagnostic(inMemory, 3, "DEDIREN_ELK_INPUT_INVALID_JSON");
  }

  // --- render ----------------------------------------------------------------------------------

  @Test
  void renderWithoutMetadataParity() throws Exception {
    String policy = read("fixtures/render-policy/default-svg.json");
    String layout = read("fixtures/layout-result/basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.renderCommand("render", policy, null, layout, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.renderCommand(
            "render", policy, null, layout, pluginEnv("render", RENDER_MAIN));

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void renderWithMetadataParity() throws Exception {
    String policy = read("fixtures/render-policy/uml-svg.json");
    String layout = read("fixtures/layout-result/uml-basic.json");
    String metadata = read("fixtures/render-metadata/uml-basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.renderCommand("render", policy, metadata, layout, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.renderCommand(
            "render", policy, metadata, layout, pluginEnv("render", RENDER_MAIN));

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void renderInvalidPolicyParity() throws Exception {
    String policy =
        "{\"render_policy_schema_version\":\"render-policy.schema.v2\",\"interactive\":\"bogus\"}";
    String layout = read("fixtures/layout-result/basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.renderCommand("render", policy, null, layout, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.renderCommand(
            "render", policy, null, layout, pluginEnv("render", RENDER_MAIN));

    assertParity(inMemory, process);
    assertDiagnostic(inMemory, 3, "DEDIREN_SVG_POLICY_INVALID");
  }

  // --- archimate-oef export --------------------------------------------------------------------

  @Test
  void exportArchimateOefParity() throws Exception {
    Map<String, String> schema = envWithOefSchemas();
    String policy = read("fixtures/export-policy/default-oef.json");
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/archimate-oef-basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.exportCommand(
            "archimate-oef", policy, source, base, layout, schema, engines());
    Map<String, String> processEnv = pluginEnv("archimate-oef", OEF_MAIN);
    processEnv.putAll(schema);
    PluginRunOutcome process =
        CoreCommands.exportCommand("archimate-oef", policy, source, base, layout, processEnv);

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void exportOefInvalidPolicyParity() throws Exception {
    String source = read("fixtures/source/valid-archimate-oef.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/archimate-oef-basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.exportCommand(
            "archimate-oef", "{}", source, base, layout, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.exportCommand(
            "archimate-oef", "{}", source, base, layout, pluginEnv("archimate-oef", OEF_MAIN));

    assertParity(inMemory, process);
    assertDiagnostic(inMemory, 3, "DEDIREN_OEF_POLICY_INVALID");
  }

  // --- uml-xmi export --------------------------------------------------------------------------

  @Test
  void exportUmlXmiParity() throws Exception {
    Map<String, String> schema = envWithXmiSchema();
    String policy = read("fixtures/export-policy/default-uml-xmi.json");
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/uml-basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.exportCommand("uml-xmi", policy, source, base, layout, schema, engines());
    Map<String, String> processEnv = pluginEnv("uml-xmi", XMI_MAIN);
    processEnv.putAll(schema);
    PluginRunOutcome process =
        CoreCommands.exportCommand("uml-xmi", policy, source, base, layout, processEnv);

    assertParity(inMemory, process);
    assertOk(inMemory);
  }

  @Test
  void exportUmlXmiInvalidPolicyParity() throws Exception {
    String source = read("fixtures/source/valid-uml-basic.json");
    Path base = path("fixtures/source");
    String layout = read("fixtures/layout-result/uml-basic.json");

    PluginRunOutcome inMemory =
        CoreCommands.exportCommand("uml-xmi", "{}", source, base, layout, Map.of(), engines());
    PluginRunOutcome process =
        CoreCommands.exportCommand(
            "uml-xmi", "{}", source, base, layout, pluginEnv("uml-xmi", XMI_MAIN));

    assertParity(inMemory, process);
    assertDiagnostic(inMemory, 3, "DEDIREN_UML_XMI_POLICY_INVALID");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Engines engines() {
    return EngineWiring.defaults();
  }

  private void assertParity(PluginRunOutcome inMemory, PluginRunOutcome process) {
    assertThat(inMemory.exitCode())
        .describedAs(
            "exit-code parity%n  in-memory(%s): %s%n  process(%s): %s",
            inMemory.exitCode(), inMemory.stdout(), process.exitCode(), process.stdout())
        .isEqualTo(process.exitCode());
    JsonNode inMemoryEnvelope = JsonSupport.objectMapper().readTree(inMemory.stdout());
    JsonNode processEnvelope = JsonSupport.objectMapper().readTree(process.stdout());
    assertThat(inMemoryEnvelope)
        .describedAs(
            "stdout envelope JSON-value parity%n  in-memory: %s%n  process:   %s",
            inMemory.stdout(), process.stdout())
        .isEqualTo(processEnvelope);
    assertSchemaValid(inMemoryEnvelope);
    assertSchemaValid(processEnvelope);
  }

  private void assertSchemaValid(JsonNode envelope) {
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/envelope.schema.json", envelope);
    assertThat(errors).describedAs("envelope schema validity: %s", envelope).isEmpty();
  }

  private void assertOk(PluginRunOutcome outcome) {
    assertThat(outcome.exitCode()).isZero();
    assertThat(JsonSupport.objectMapper().readTree(outcome.stdout()).get("status").asText())
        .isEqualTo("ok");
  }

  private void assertDiagnostic(PluginRunOutcome outcome, int exitCode, String code) {
    assertThat(outcome.exitCode()).isEqualTo(exitCode);
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(code);
  }

  private PluginRunOutcome directProcess(
      String pluginId,
      String mainClass,
      String capability,
      List<String> args,
      String stdin,
      Map<String, String> extraEnv)
      throws Exception {
    Map<String, String> env = pluginEnv(pluginId, mainClass);
    env.putAll(extraEnv);
    return PluginRunner.runForCapabilityWithRegistry(
        PluginRegistry.bundled(env),
        pluginId,
        capability,
        args,
        stdin,
        PluginRunOptions.defaults().withCandidateEnv(env));
  }

  private Map<String, String> pluginEnv(String pluginId, String mainClass) throws Exception {
    Path script = temp.resolve(pluginId + ".sh");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");
    Files.writeString(
        script,
        """
        #!/bin/sh
        exec "%s" -cp "%s" %s "$@"
        """
            .formatted(java, classpath, mainClass),
        StandardCharsets.UTF_8);
    script.toFile().setExecutable(true);
    return new LinkedHashMap<>(
        Map.of(
            "DEDIREN_PLUGIN_DIRS",
            root().resolve("fixtures/plugins").toString(),
            "DEDIREN_PLUGIN_" + pluginId.toUpperCase().replace('-', '_'),
            script.toString()));
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

package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end coverage for the {@code dediren build} subcommand through the real wired engines
 * ({@link EngineWiring#defaults()}) — no fake engines, no process boundary. The build result it
 * prints is asserted against {@code schemas/build-result.schema.json} directly, and one test proves
 * the one-shot build's artifact is byte-identical to running the same view through the decomposed
 * five-stage flow (the CLI workflow README/agent-usage document as the fallback).
 */
class CliBuildCommandTest {
  @TempDir Path temp;

  @Test
  void archimateBuildRendersSvgAndValidatesBuildResultSchema() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("archimate-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-pipeline-archimate.json").toString(),
              "--out",
              out.toString(),
              "--render-policy",
              root.resolve("fixtures/render-policy/archimate-svg.json").toString()
            },
            "");

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    JsonNode buildResult = assertBuildResultSchemaValid(result.stdout());
    assertThat(buildResult.at("/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/views/0/view_id").asText()).isEqualTo("main");
    assertThat(buildResult.at("/views/0/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/views/0/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(buildResult.at("/views/0/artifacts/0/path").asText()).isEqualTo("main/diagram.svg");

    String svg = Files.readString(out.resolve("main/diagram.svg"), StandardCharsets.UTF_8);
    assertThat(svg).contains("<svg", "Web App", "Application Services");
  }

  @Test
  void sequenceViewBuildMatchesManualFiveStageFlowArtifact() throws Exception {
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-sequence-basic.json");
    Path renderPolicy = root.resolve("fixtures/render-policy/uml-svg.json");
    Path out = temp.resolve("sequence-out");

    CliResult build =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              out.toString(),
              "--views",
              "sequence-view",
              "--render-policy",
              renderPolicy.toString()
            },
            "");

    assertThat(build.exitCode()).describedAs(build.stdout()).isZero();
    JsonNode buildResult = assertBuildResultSchemaValid(build.stdout());
    assertThat(buildResult.at("/views/0/artifacts/0/path").asText())
        .isEqualTo("sequence-view/diagram.svg");
    String builtSvg =
        Files.readString(out.resolve("sequence-view/diagram.svg"), StandardCharsets.UTF_8);

    // The decomposed fallback flow the docs also describe: project (layout-request,
    // render-metadata) -> layout -> render, chained by hand through the per-stage subcommands.
    JsonNode layoutRequest =
        stageData(
            new String[] {
              "project",
              "--plugin",
              "generic-graph",
              "--target",
              "layout-request",
              "--view",
              "sequence-view",
              "--input",
              source.toString()
            });
    JsonNode renderMetadata =
        stageData(
            new String[] {
              "project",
              "--plugin",
              "generic-graph",
              "--target",
              "render-metadata",
              "--view",
              "sequence-view",
              "--input",
              source.toString()
            });
    Path layoutRequestFile = writeStage("layout-request.json", layoutRequest);
    Path renderMetadataFile = writeStage("render-metadata.json", renderMetadata);
    JsonNode layoutResult =
        stageData(
            new String[] {
              "layout", "--plugin", "elk-layout", "--input", layoutRequestFile.toString()
            });
    Path layoutFile = writeStage("layout-result.json", layoutResult);
    JsonNode render =
        stageData(
            new String[] {
              "render",
              "--plugin",
              "render",
              "--policy",
              renderPolicy.toString(),
              "--metadata",
              renderMetadataFile.toString(),
              "--input",
              layoutFile.toString()
            });
    String manualSvg = render.at("/artifacts/0/content").asText();

    assertThat(builtSvg).isEqualTo(manualSvg);
  }

  @Test
  void buildsASequenceViewContainingASelfMessage() throws Exception {
    // Regression: a self-call (source lifeline == target lifeline) is legal UML but used to fail
    // layout validation with ROUTE_ENDPOINT_OFF_NODE_PERIMETER + the lifeline-axis invariant,
    // because the normalizer left self-messages on ELK's raw route.
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-sequence-self-message.json");
    Path renderPolicy = root.resolve("fixtures/render-policy/uml-svg.json");
    Path out = temp.resolve("self-message-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              out.toString(),
              "--views",
              "sequence-view",
              "--render-policy",
              renderPolicy.toString()
            },
            "");

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    JsonNode buildResult = assertBuildResultSchemaValid(result.stdout());
    assertThat(buildResult.at("/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/diagnostics")).isEmpty();
    assertThat(buildResult.at("/views/0/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/views/0/diagnostics")).isEmpty();
    assertThat(buildResult.at("/views/0/artifacts/0/path").asText())
        .isEqualTo("sequence-view/diagram.svg");
    assertThat(Files.exists(out.resolve("sequence-view/diagram.svg"))).isTrue();
  }

  @Test
  void oefLaneWritesArchimateArtifactWithOfflineSchemaEnv() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("oef-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-archimate-oef.json").toString(),
              "--out",
              out.toString(),
              "--oef-policy",
              root.resolve("fixtures/export-policy/default-oef.json").toString()
            },
            "",
            envWithOefSchemas());

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    JsonNode buildResult = assertBuildResultSchemaValid(result.stdout());
    assertThat(buildResult.at("/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/views/0/artifacts/0/artifact_kind").asText())
        .isEqualTo("archimate-oef+xml");
    assertThat(buildResult.at("/views/0/artifacts/0/path").asText()).isEqualTo("main/oef.xml");
    assertThat(Files.readString(out.resolve("main/oef.xml"), StandardCharsets.UTF_8))
        .contains("<model", "identifier=\"id-dediren-oef-basic-model\"");
  }

  @Test
  void xmiLaneWritesUmlArtifactWithOfflineSchemaEnv() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("xmi-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-uml-basic.json").toString(),
              "--out",
              out.toString(),
              "--views",
              "class-view",
              "--xmi-policy",
              root.resolve("fixtures/export-policy/default-uml-xmi.json").toString()
            },
            "",
            envWithXmiSchema());

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    JsonNode buildResult = assertBuildResultSchemaValid(result.stdout());
    assertThat(buildResult.at("/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/views/0/artifacts/0/artifact_kind").asText())
        .isEqualTo("uml-xmi+xml");
    assertThat(buildResult.at("/views/0/artifacts/0/path").asText())
        .isEqualTo("class-view/xmi.xml");
    assertThat(Files.readString(out.resolve("class-view/xmi.xml"), StandardCharsets.UTF_8))
        .contains("xmi:XMI", "<uml:Model");
  }

  @Test
  void failingViewDoesNotAbortOthersAndYieldsAggregateError() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("aggregation-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-basic.json").toString(),
              "--out",
              out.toString(),
              "--views",
              "main,no-such-view",
              "--render-policy",
              root.resolve("fixtures/render-policy/default-svg.json").toString()
            },
            "");

    assertThat(result.exitCode()).isEqualTo(2);
    JsonNode buildResult = assertBuildResultSchemaValid(result.stdout());
    assertThat(buildResult.at("/status").asText()).isEqualTo("error");

    assertThat(buildResult.at("/views/0/view_id").asText()).isEqualTo("main");
    assertThat(buildResult.at("/views/0/status").asText()).isEqualTo("ok");
    assertThat(buildResult.at("/views/0/artifacts/0/path").asText()).isEqualTo("main/diagram.svg");
    assertThat(Files.exists(out.resolve("main/diagram.svg"))).isTrue();

    assertThat(buildResult.at("/views/1/view_id").asText()).isEqualTo("no-such-view");
    assertThat(buildResult.at("/views/1/status").asText()).isEqualTo("error");
    assertThat(buildResult.at("/views/1/artifacts")).isEmpty();
    assertThat(buildResult.at("/views/1/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void atLeastOneLaneIsRequired() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("no-lane-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-basic.json").toString(),
              "--out",
              out.toString()
            },
            "");

    assertThat(result.exitCode()).isEqualTo(2);
    JsonNode buildResult = assertBuildResultSchemaValid(result.stdout());
    assertThat(buildResult.at("/status").asText()).isEqualTo("error");
    assertThat(buildResult.at("/views")).isEmpty();
    assertThat(buildResult.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void unknownEmitValueIsAUsageErrorEnvelopeNotABuildResult() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("bad-emit-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-basic.json").toString(),
              "--out",
              out.toString(),
              "--render-policy",
              root.resolve("fixtures/render-policy/default-svg.json").toString(),
              "--emit",
              "layout-request,not-a-real-kind"
            },
            "");

    assertThat(result.exitCode()).isEqualTo(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.has("build_result_schema_version"))
        .describedAs("a usage error is the generic command envelope, not a build result")
        .isFalse();
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(envelope.at("/diagnostics/0/message").asText()).contains("not-a-real-kind");
  }

  @Test
  void emitPersistsStageEnvelopesUnderEachView() throws Exception {
    Path root = workspaceRoot();
    Path out = temp.resolve("emit-out");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              root.resolve("fixtures/source/valid-basic.json").toString(),
              "--out",
              out.toString(),
              "--render-policy",
              root.resolve("fixtures/render-policy/default-svg.json").toString(),
              "--emit",
              "layout-request,layout-result,render-metadata"
            },
            "");

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    for (String stage : List.of("layout-request", "layout-result", "render-metadata")) {
      Path emitted = out.resolve("main/" + stage + ".json");
      assertThat(Files.exists(emitted)).describedAs(stage).isTrue();
      JsonNode envelope =
          JsonSupport.objectMapper().readTree(Files.readString(emitted, StandardCharsets.UTF_8));
      // --emit writes the stage COMMAND ENVELOPE verbatim (data nested under .data), the same
      // shape a per-stage subcommand would pipe into the next one.
      assertThat(envelope.get("status").asText()).isEqualTo("ok");
      assertThat(envelope.has("data")).isTrue();
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Runs one CLI pipeline stage and returns its ok-envelope {@code data} payload. */
  private JsonNode stageData(String[] args) throws Exception {
    CliResult result = Main.executeForTesting(args, "");
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");
    return envelope.at("/data");
  }

  private Path writeStage(String name, JsonNode data) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(
        file, JsonSupport.objectMapper().writeValueAsString(data), StandardCharsets.UTF_8);
    return file;
  }

  private static JsonNode assertBuildResultSchemaValid(String stdout) {
    JsonNode document = JsonSupport.objectMapper().readTree(stdout);
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/build-result.schema.json", document);
    assertThat(errors).describedAs("build-result schema validity: %s", stdout).isEmpty();
    return document;
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
    return Map.of("DEDIREN_OEF_SCHEMA_DIR", schemaDir.toString());
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

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

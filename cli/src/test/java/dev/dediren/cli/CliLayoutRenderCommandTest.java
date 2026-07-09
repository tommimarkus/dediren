package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class CliLayoutRenderCommandTest {
  @TempDir Path temp;

  @Test
  void validateLayoutReportsQualityFromFile() throws Exception {
    CliResult result =
        runValidateLayout(workspaceRoot().resolve("fixtures/layout-result/basic.json"));

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/data/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/overlap_count").asInt()).isZero();
    assertThat(envelope.at("/data/group_label_band_issue_count").asInt()).isZero();
    assertThat(envelope.at("/data/label_space_issue_count").asInt()).isZero();
    assertThat(envelope.at("/data/edge_label_dissociation_count").asInt()).isZero();
    // Presence pin, not value pin: the fixture's crossing topology is ELK-determined, not
    // spec-derived.
    assertThat(envelope.at("/data/edge_crossing_count").isInt()).isTrue();
  }

  @Test
  void validateLayoutSurfacesQualityWarningInEnvelopeStatusAndDiagnostics() throws Exception {
    Path layout = temp.resolve("overlapping-layout.json");
    Files.writeString(
        layout,
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v2",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
                    { "id": "b", "source_id": "b", "projection_id": "b", "x": 50.0, "y": 20.0, "width": 100.0, "height": 80.0, "label": "B" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """);

    CliResult result = runValidateLayout(layout);

    JsonNode envelope = envelope(result);

    // A warning verdict is not a failure: exit stays 0, but the envelope status and diagnostics
    // now carry the quality verdict so a consumer reading only .status/.diagnostics[] sees it.
    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_QUALITY_WARNING");
    assertThat(envelope.at("/diagnostics/0/severity").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("$.data.overlap_count");
    assertThat(envelope.at("/data/status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/data/overlap_count").asInt()).isEqualTo(1);
  }

  @Test
  void validateLayoutAcceptsSequenceLifelineMessageEndpoints() throws Exception {
    Path fixture = workspaceRoot().resolve("fixtures/layout-result/uml-sequence-validatable.json");

    CliResult result = runValidateLayout(fixture);

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/data/status").asText()).isEqualTo("ok");

    // Negative control: identical geometry without the lifeline role still trips the perimeter
    // check,
    // proving the acceptance is gated on role rather than loosened for everyone.
    String roleless = Files.readString(fixture).replace(",\n      \"role\": \"lifeline\"", "");
    Path stripped = temp.resolve("sequence-without-role.json");
    Files.writeString(stripped, roleless);

    CliResult control = runValidateLayout(stripped);

    JsonNode controlEnvelope = envelope(control);

    assertThat(control.exitCode()).isEqualTo(2);
    assertThat(controlEnvelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
  }

  @Test
  void validateLayoutRejectsEmptyRoutesAndEndpointMisses() throws Exception {
    Path layout = temp.resolve("invalid-route-layout.json");
    Files.writeString(
        layout,
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v2",
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

    CliResult result = runValidateLayout(layout);

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY");
    assertThat(envelope.at("/diagnostics/1/code").asText())
        .isEqualTo("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
  }

  @Test
  void layoutMissingInputFileReturnsJsonEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "layout",
              "--plugin",
              "elk-layout",
              "--input",
              temp.resolve("missing-layout-request.json").toString()
            },
            "");

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void renderMissingPolicyFileReturnsJsonEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "render",
              "--plugin",
              "render",
              "--policy",
              temp.resolve("missing-policy.json").toString(),
              "--input",
              workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
            },
            "");

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void projectCommandRunsJavaGenericGraphPlugin() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "project",
              "--plugin",
              "generic-graph",
              "--target",
              "layout-request",
              "--view",
              "main",
              "--input",
              workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
            },
            "");

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/layout_request_schema_version").asText())
        .isEqualTo("layout-request.schema.v2");
    assertThat(envelope.at("/data/view_id").asText()).isEqualTo("main");
    assertThat(envelope.at("/data/nodes")).hasSize(2);
    assertThat(envelope.at("/data/edges")).hasSize(1);
    assertThat(envelope.at("/data/edges/0/relationship_type").asText()).isEqualTo("generic.calls");
  }

  @Test
  void renderCommandRunsJavaSvgPlugin() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "render",
              "--plugin",
              "render",
              "--policy",
              workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString(),
              "--input",
              workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
            },
            "");

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
    assertThat(envelope.at("/data/artifacts/0/content").asText())
        .contains(
            "<svg",
            "role=\"img\"",
            // default-svg.json has no accessibility title, so the accessible name is the view_id.
            "<title>main</title>",
            "data-dediren-node-id=\"client\"",
            "data-dediren-edge-id=\"client-calls-api\"")
        // The documented default is "none": a static SVG with no interaction script or hooks.
        .doesNotContain(
            "data-dediren-edge-label-background",
            "<script",
            "data-dediren-edge-source",
            "dediren-edge-highlighted");
  }

  @Test
  void sequenceMessageArrowsReachLifelineStemsThroughRealEngine() throws Exception {
    // The sequence render's message geometry comes from the live ELK layout normalizer, so the
    // gallery image is produced from the real engine end to end (project -> layout -> render), not
    // a frozen layout fixture. This refreshes the git-ignored gallery with a styled sequence
    // diagram and guards, at the render boundary, that every message arrow still terminates on its
    // lifeline stem — the same invariant ElkLayoutEngineTest pins at the layout boundary.
    Map<String, String> env = Map.of();
    String source =
        workspaceRoot().resolve("fixtures/source/valid-uml-sequence-basic.json").toString();
    String[] projectView = {
      "--plugin", "generic-graph", "--view", "sequence-view", "--input", source
    };

    JsonNode layoutRequest =
        stageData(concat("project", "--target", "layout-request", projectView), env);
    JsonNode renderMetadata =
        stageData(concat("project", "--target", "render-metadata", projectView), env);
    Path requestFile = writeStage("layout-request.json", layoutRequest);
    JsonNode layoutResult =
        stageData(
            new String[] {"layout", "--plugin", "elk-layout", "--input", requestFile.toString()},
            env);
    Path layoutFile = writeStage("layout-result.json", layoutResult);
    Path metadataFile = writeStage("render-metadata.json", renderMetadata);
    JsonNode render =
        stageData(
            new String[] {
              "render",
              "--plugin",
              "render",
              "--policy",
              workspaceRoot().resolve("fixtures/render-policy/uml-svg.json").toString(),
              "--metadata",
              metadataFile.toString(),
              "--input",
              layoutFile.toString()
            },
            env);

    String svg = render.at("/artifacts/0/content").asText();
    assertSequenceArrowsReachStems(svg);
    writeGalleryArtifact("uml-sequence-real-engine", svg);
  }

  @Test
  void exportCommandRunsJavaArchimateOefPlugin() throws Exception {
    Map<String, String> env = new LinkedHashMap<>(envWithOefSchemas());

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "export",
              "--plugin",
              "archimate-oef",
              "--policy",
              workspaceRoot().resolve("fixtures/export-policy/default-oef.json").toString(),
              "--source",
              workspaceRoot().resolve("fixtures/source/valid-archimate-oef.json").toString(),
              "--layout",
              workspaceRoot().resolve("fixtures/layout-result/archimate-oef-basic.json").toString()
            },
            "",
            env);

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/artifact_kind").asText()).isEqualTo("archimate-oef+xml");
    assertThat(envelope.at("/data/content").asText())
        .contains(
            "<model",
            "identifier=\"id-dediren-oef-basic-model\"",
            "xsi:type=\"ApplicationComponent\"");
  }

  @Test
  void exportCommandRunsJavaUmlXmiPlugin() throws Exception {
    Map<String, String> env = new LinkedHashMap<>(envWithXmiSchema());

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "export",
              "--plugin",
              "uml-xmi",
              "--policy",
              workspaceRoot().resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
              "--source",
              workspaceRoot().resolve("fixtures/source/valid-uml-basic.json").toString(),
              "--layout",
              workspaceRoot().resolve("fixtures/layout-result/uml-basic.json").toString()
            },
            "",
            env);

    JsonNode envelope = envelope(result);

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(envelope.at("/data/content").asText())
        .contains("xmi:XMI", "<uml:Model", "xmi:type=\"uml:Class\"");
  }

  private CliResult runValidateLayout(Path input) {
    return Main.executeForTesting(
        new String[] {"validate-layout", "--input", input.toString()}, "");
  }

  /** Runs one CLI pipeline stage and returns its ok-envelope {@code data} payload. */
  private JsonNode stageData(String[] args, Map<String, String> env) throws Exception {
    CliResult result = Main.executeForTesting(args, "", env);
    JsonNode envelope = envelope(result);
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

  private static String[] concat(String first, String second, String third, String[] rest) {
    String[] all = new String[3 + rest.length];
    all[0] = first;
    all[1] = second;
    all[2] = third;
    System.arraycopy(rest, 0, all, 3, rest.length);
    return all;
  }

  private static void assertSequenceArrowsReachStems(String svg) {
    List<Double> stems = new ArrayList<>();
    Matcher stem =
        Pattern.compile("data-dediren-sequence-lifeline-stem=\"[^\"]*\"\\s+x1=\"([-\\d.]+)\"")
            .matcher(svg);
    while (stem.find()) {
      stems.add(Double.parseDouble(stem.group(1)));
    }
    assertThat(stems).as("rendered sequence must draw lifeline stems").isNotEmpty();

    // Locate message connectors by their stable data hook rather than a cosmetic stroke attribute,
    // so a future style change cannot silently redraw what this oracle counts.
    Matcher message =
        Pattern.compile(
                "<path data-dediren-sequence-message=\"[^\"]*\" d=\"M ([-\\d.]+) [-\\d.]+ L ([-\\d.]+) [-\\d.]+\"")
            .matcher(svg);
    int messages = 0;
    while (message.find()) {
      messages++;
      for (String endpoint : new String[] {message.group(1), message.group(2)}) {
        double x = Double.parseDouble(endpoint);
        double nearestStem =
            stems.stream().mapToDouble(s -> Math.abs(s - x)).min().orElse(Double.MAX_VALUE);
        assertThat(nearestStem)
            .as("message arrow endpoint x=%.1f must reach a lifeline stem %s", x, stems)
            .isLessThan(1.0);
      }
    }
    assertThat(messages).as("rendered sequence must draw message arrows").isGreaterThan(0);
  }

  private static final AtomicBoolean GALLERY_CLEANED = new AtomicBoolean();

  private static void writeGalleryArtifact(String name, String svg) throws IOException {
    Path dir = workspaceRoot().resolve(".test-output/renders/sequence");
    cleanGalleryOnce(dir);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name + ".svg"), svg);
  }

  // Wipe once per JVM run so the sequence gallery holds only this run's render.
  private static void cleanGalleryOnce(Path dir) throws IOException {
    if (!GALLERY_CLEANED.compareAndSet(false, true) || !Files.isDirectory(dir)) {
      return;
    }
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private JsonNode envelope(CliResult result) throws Exception {
    return JsonSupport.objectMapper().readTree(result.stdout());
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
        java.util.List.of(
            "archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd")) {
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

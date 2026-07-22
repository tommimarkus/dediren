package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ModelExportRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the whole-model {@code exportModel} lane end to end: it emits {@code <uml:Model>} once
 * plus one OMG UMLDI diagram per view, and — crucially — passes the XMI schema gate, which proves
 * the tolerance predicate was widened to accept the {@code di:}/{@code dc:}/{@code umldi:}
 * namespaces (otherwise the export throws {@code DEDIREN_XMI_SCHEMA_INVALID}). A permissive {@code
 * ##other}-strict stand-in XMI.xsd reproduces the no-normative-XSD condition offline.
 */
class WholeModelXmiExportTest {

  @TempDir Path tempDir;

  private final XmiExportEngine engine = new XmiExportEngine();

  @Test
  void emitsFullModelPlusPerViewUmldiAndPassesTheWidenedSchemaGate() throws Exception {
    Path schemaPath = writeStubXmiSchema();
    ModelExportRequest request =
        new ModelExportRequest(
            parse("fixtures/source/valid-uml-basic.json", SourceDocument.class),
            List.of(
                new ModelExportRequest.ViewLayout(
                    "class-view",
                    parse("fixtures/layout-result/uml-basic.json", LayoutResult.class))),
            JsonSupport.objectMapper()
                .readTree(
                    Files.readString(
                        workspaceRoot().resolve("fixtures/export-policy/default-uml-xmi.json"))));

    Optional<EngineResult<ExportResult>> result =
        engine.exportModel(
            request,
            Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString()),
            Path.of("").toAbsolutePath());

    assertThat(result).isPresent();
    String content = result.orElseThrow().value().content();
    // One model section, then a UMLDI diagram with geometry-bearing shapes and edges.
    assertThat(content)
        .contains("<uml:Model")
        .contains("<umldi:UMLDiagram")
        .contains("<umldi:UMLShape")
        .contains("<dc:Bounds")
        .contains("<umldi:UMLEdge")
        .contains("<di:waypoint");
    // The export only returns (rather than throwing SCHEMA_INVALID) because the gate tolerated DI.
    assertThat(result.orElseThrow().diagnostics())
        .anySatisfy(
            diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_SCHEMA_CONFORMANCE"));
  }

  @Test
  void perViewDiagramIdentityOverrideFromThePolicyViewsMap() throws Exception {
    Path schemaPath = writeStubXmiSchema();
    ObjectNode policy =
        (ObjectNode)
            JsonSupport.objectMapper()
                .readTree(
                    Files.readString(
                        workspaceRoot().resolve("fixtures/export-policy/default-uml-xmi.json")));
    ObjectNode override = policy.putObject("views").putObject("class-view");
    override.put("diagram_identifier", "id-my-diagram");
    override.put("diagram_name", "My Class Diagram");
    ModelExportRequest request =
        new ModelExportRequest(
            parse("fixtures/source/valid-uml-basic.json", SourceDocument.class),
            List.of(
                new ModelExportRequest.ViewLayout(
                    "class-view",
                    parse("fixtures/layout-result/uml-basic.json", LayoutResult.class))),
            policy);

    Optional<EngineResult<ExportResult>> result =
        engine.exportModel(
            request,
            Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString()),
            Path.of("").toAbsolutePath());

    // The explicit override wins over the source-derived default id-diagram-<viewId>/label.
    assertThat(result.orElseThrow().value().content())
        .contains("<umldi:UMLDiagram xmi:id=\"id-my-diagram\" name=\"My Class Diagram\"");
  }

  private static <T> T parse(String fixture, Class<T> type) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(fixture)), type);
  }

  private Path writeStubXmiSchema() throws Exception {
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
              <xsd:sequence>
                <xsd:any namespace="##other" processContents="strict"
                         minOccurs="0" maxOccurs="unbounded"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
        """,
        StandardCharsets.UTF_8);
    return schemaPath;
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

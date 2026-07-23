package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.plugins.umlxmi.build.XmiHelpers;
import dev.dediren.plugins.umlxmi.schema.SchemaValidation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Structural spec-conformance INVARIANTS that hold for the whole shipped export surface, so future
 * drift is caught mechanically instead of by the next manual audit. These are intentionally the
 * opposite of the per-fixture golden snapshots in {@link MainTest}: a golden {@code isEqualTo}
 * freezes one document's exact bytes (a characterization test — it passes even if those bytes are
 * spec-wrong, which is how a batch of conformance defects once sat green); an invariant here is
 * derived from a UML 2.5.1 / XMI rule and asserted across EVERY golden or EVERY exported fixture,
 * so it fails the moment any family violates the rule.
 *
 * <ol>
 *   <li>Referential integrity: every in-document IDREF resolves to a declared {@code xmi:id}. The
 *       dangling-ObjectNode-type defect (a {@code type=} pointing at a raw name with no target
 *       element) was exactly this class; this gate closes it for every attribute of every golden.
 *   <li>No in-view silent drop: for every exported fixture, nothing that is WITHIN the exported
 *       view goes unrepresented. This turns the SYS-1 coverage/representation signal (unit-tested
 *       in isolation by {@code CoverageTest}) into an end-to-end build gate over the real engine
 *       and every family — the systemic guard against a writer silently dropping valid in-scope
 *       content.
 * </ol>
 */
class XmiConformanceInvariantsTest {

  @TempDir Path tempDir;

  // ---- Invariant 1: referential integrity over every committed golden -------------------------

  static Stream<Path> committedGoldens() throws Exception {
    try (Stream<Path> entries = Files.list(workspaceRoot().resolve("fixtures/export"))) {
      return entries
          .filter(path -> path.getFileName().toString().endsWith(".xmi"))
          .sorted()
          .toList()
          .stream();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("committedGoldens")
  void everyIdrefResolvesToADeclaredXmiId(Path golden) throws Exception {
    IdIndex index = indexIds(parse(Files.readString(golden)));

    // The gate relies on the IdentifierMap convention that every emitted xmi:id is "id-"-prefixed:
    // that is what lets an attribute value be classified as an IDREF by its prefix alone, with no
    // brittle list of IDREF attribute names to keep in sync. Assert the convention so the
    // classifier
    // can never silently miss a reference to a differently-shaped id.
    assertThat(index.declared())
        .allSatisfy(id -> assertThat(id).describedAs("xmi:id in %s", golden).startsWith("id-"));
    assertThat(index.dangling())
        .describedAs("IDREF(s) with no matching xmi:id in %s", golden)
        .isEmpty();
  }

  @Test
  void theReferentialIntegrityGateActuallyCatchesADanglingIdref() throws Exception {
    // Prove the gate bites: a document whose type= points at an id that is never declared, and
    // whose
    // multi-valued memberEnd list contains one declared and one undeclared end, must be reported as
    // exactly the two undeclared references — and nothing else.
    IdIndex index =
        indexIds(
            parse(
                "<xmi:XMI xmlns:xmi=\""
                    + XmiHelpers.XMI_NS
                    + "\" xmlns:uml=\"http://www.omg.org/spec/UML/20161101\">"
                    + "<uml:Model xmi:id=\"id-model\">"
                    + "<packagedElement xmi:type=\"uml:Class\" xmi:id=\"id-order\">"
                    + "<ownedAttribute xmi:id=\"id-order-name\" type=\"id-ghost-type\"/>"
                    + "</packagedElement>"
                    + "<packagedElement xmi:type=\"uml:Association\" xmi:id=\"id-assoc\""
                    + " memberEnd=\"id-real-end id-ghost-end\">"
                    + "<ownedEnd xmi:id=\"id-real-end\" type=\"id-order\"/>"
                    + "</packagedElement>"
                    + "</uml:Model></xmi:XMI>"));

    assertThat(index.dangling()).containsExactlyInAnyOrder("id-ghost-type", "id-ghost-end");
  }

  /** Declared {@code xmi:id}s and the {@code id-}-prefixed IDREFs found across a document. */
  private record IdIndex(Set<String> declared, List<String> referenced) {
    List<String> dangling() {
      return referenced.stream().filter(reference -> !declared.contains(reference)).toList();
    }
  }

  private static IdIndex indexIds(Document document) {
    Set<String> declaredIds = new LinkedHashSet<>();
    List<String> referenced = new ArrayList<>();
    collectIdsAndRefs(document.getDocumentElement(), declaredIds, referenced);
    return new IdIndex(declaredIds, referenced);
  }

  /**
   * Walks the DOM collecting every {@code xmi:id} declaration and every attribute value that names
   * an id (an {@code id-}-prefixed IDREF), skipping the {@code xmi:id} declarations themselves.
   */
  private static void collectIdsAndRefs(
      Element element, Set<String> declaredIds, List<String> referenced) {
    NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Attr attribute = (Attr) attributes.item(i);
      boolean isXmiId =
          "id".equals(attribute.getLocalName())
              && XmiHelpers.XMI_NS.equals(attribute.getNamespaceURI());
      if (isXmiId) {
        declaredIds.add(attribute.getValue());
      } else {
        // A UML multi-valued reference (memberEnd, node, covered, …) serializes as a
        // whitespace-separated IDREFS list, so split before classifying each token by prefix.
        for (String token : attribute.getValue().trim().split("\\s+")) {
          if (token.startsWith("id-")) {
            referenced.add(token);
          }
        }
      }
    }
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE) {
        collectIdsAndRefs((Element) child, declaredIds, referenced);
      }
    }
  }

  // ---- Invariant 2: nothing WITHIN the exported view is silently dropped, per family ----------

  static Stream<Arguments> exportedFixtures() {
    // (label, source fixture, layout-result fixture) — the eight shipped families plus the two
    // extra view-scopings that MainTest pins as goldens. Mirrors GoldenExportRegenerator's inputs;
    // the sequence message_sort tweak is irrelevant to representation, so it is omitted here.
    return Stream.of(
        Arguments.of("class", "valid-uml-basic", "uml-basic"),
        Arguments.of("complex-class", "valid-uml-complex", "uml-complex-class"),
        Arguments.of("sequence", "valid-uml-sequence-basic", "uml-sequence-basic"),
        Arguments.of(
            "sequence-fragments", "valid-uml-sequence-fragments", "uml-sequence-fragments"),
        Arguments.of("state-machine", "valid-uml-state-machine-basic", "uml-state-machine-basic"),
        Arguments.of("use-case", "valid-uml-use-case-basic", "uml-use-case-basic"),
        Arguments.of("component", "valid-uml-component-basic", "uml-component-basic"),
        Arguments.of("deployment", "valid-uml-deployment-basic", "uml-deployment-basic"),
        Arguments.of("activity", "valid-uml-basic", "uml-activity"),
        Arguments.of("data", "valid-uml-basic", "uml-data"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("exportedFixtures")
  void nothingWithinTheExportedViewIsSilentlyDropped(String label, String source, String layout)
      throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/" + source + ".json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/" + layout + ".json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, input.toString(), envWithStubXmiSchema());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    // The engine reuses the *_OMITTED codes for both out-of-view omissions (expected and fine) and
    // in-view fidelity gaps (the silent-drop bug class); only the latter carries the shared marker.
    // Keying on the engine's own constant means the gate cannot drift out of sync with the message.
    for (JsonNode diagnostic : envelope.at("/diagnostics")) {
      assertThat(diagnostic.at("/message").asText())
          .describedAs("in-view fidelity gap in the %s export: %s", label, diagnostic)
          .doesNotContain(XmiExportEngine.IN_VIEW_FIDELITY_GAP_MARKER);
    }
  }

  @Test
  void theInViewFidelityGateActuallyCatchesASilentlyDroppedElement() throws Exception {
    // Prove the gate bites: place a valid UML type in-scope where no writer emits it. An Action is
    // a
    // legal element (the engine's type allow-list accepts it — an UNKNOWN type is rejected
    // outright,
    // so it can never silently drop), but the class lane has no Activity to own it, so it falls
    // through unrepresented. The export still succeeds (the gap is info, not an error), yet the
    // engine must raise the shared marker the per-family gate above asserts is absent when
    // conformant.
    JsonNode source = fixtureJson("fixtures/source/valid-uml-basic.json");
    ((ArrayNode) source.at("/nodes"))
        .addObject()
        .put("id", "ghost-action")
        .put("type", "Action")
        .put("label", "orphan action");
    JsonNode layout = fixtureJson("fixtures/layout-result/uml-basic.json");
    ObjectNode ghostLayout = (ObjectNode) ((ArrayNode) layout.at("/nodes")).addObject();
    ghostLayout.put("id", "ghost-action").put("source_id", "ghost-action");
    ghostLayout.put("projection_id", "ghost-action").put("label", "orphan action");
    ghostLayout.put("x", 0.0).put("y", 0.0).put("width", 10.0).put("height", 10.0);
    ghostLayout.put("source_pointer", "/nodes/10");

    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", source);
    input.set("layout_result", layout);
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, input.toString(), envWithStubXmiSchema());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    assertThat(envelope.at("/diagnostics"))
        .anySatisfy(
            diagnostic ->
                assertThat(diagnostic.at("/message").asText())
                    .contains(XmiExportEngine.IN_VIEW_FIDELITY_GAP_MARKER)
                    .contains("Action=1"));
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static Document parse(String xml) throws Exception {
    return SchemaValidation.secureXmiDocumentBuilderFactory()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private Map<String, String> envWithStubXmiSchema() throws Exception {
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

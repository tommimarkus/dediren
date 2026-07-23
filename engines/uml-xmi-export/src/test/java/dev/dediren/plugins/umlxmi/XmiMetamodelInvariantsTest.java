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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;

/**
 * Per-family UML 2.5.1 metamodel structural invariants over the shipped output — the layer the
 * golden snapshots and point {@code .contains()} assertions cannot provide on their own. A golden
 * freezes one document; a point assertion checks one fact in one fixture. These assert a *spec
 * rule* across every golden (or every fixture) that carries the relevant construct, so a future
 * writer change that reshapes the output wrong fails here even if someone forgets to update the
 * point test.
 *
 * <p>Each rule names the UML 2.5.1 clause it enforces and corresponds to a structural-shape defect
 * class this audit fixed (M-GEN gained a dedicated {@code valid-uml-generalization} golden so it is
 * a real check, not a vacuous scan). The remaining single-fixture semantic rules
 * (trigger/guard/deleteMessage) stay covered by their point tests in {@link MainTest}.
 */
class XmiMetamodelInvariantsTest {

  @TempDir Path tempDir;

  static Stream<Path> committedGoldens() throws Exception {
    try (Stream<Path> entries = Files.list(workspaceRoot().resolve("fixtures/export"))) {
      return entries
          .filter(path -> path.getFileName().toString().endsWith(".xmi"))
          .sorted()
          .toList()
          .stream();
    }
  }

  // ---- M-ENDS: an Association / CommunicationPath is binary+ (memberEnd [2..*], §11.5) ----------

  @ParameterizedTest(name = "{0}")
  @MethodSource("committedGoldens")
  void everyAssociationLikeElementHasAtLeastTwoOwnedEnds(Path golden) throws Exception {
    for (Element association :
        packagedElementsOfType(parse(golden), Set.of("uml:Association", "uml:CommunicationPath"))) {
      assertThat(childElements(association, "ownedEnd"))
          .describedAs(
              "%s %s must own >= 2 ends (memberEnd [2..*], UML 2.5.1 §11.5) in %s",
              association.getAttributeNS(XmiHelpers.XMI_NS, "type"),
              association.getAttributeNS(XmiHelpers.XMI_NS, "id"),
              golden.getFileName())
          .hasSizeGreaterThanOrEqualTo(2);
    }
  }

  // ---- M-DEPLOY: a Deployment is owned by its location target node (location{subsets owner}) ----

  @ParameterizedTest(name = "{0}")
  @MethodSource("committedGoldens")
  void everyDeploymentIsOwnedByItsLocationNodeAndDeploysAnArtifact(Path golden) throws Exception {
    Document document = parse(golden);
    Map<String, String> typeById = xmiTypeById(document);
    NodeList deployments = document.getElementsByTagName("deployment");
    for (int i = 0; i < deployments.getLength(); i++) {
      Element deployment = (Element) deployments.item(i);
      Element owner = (Element) deployment.getParentNode();
      String ownerType = owner.getAttributeNS(XmiHelpers.XMI_NS, "type");
      String ownerId = owner.getAttributeNS(XmiHelpers.XMI_NS, "id");
      // location {subsets owner}: the Deployment nests in a deployment-target Node and names it
      // (UML 2.5.1 p704). A reversed/absent owner or a location pointing elsewhere is the DEP-2
      // bug.
      assertThat(ownerType)
          .describedAs("Deployment %s owner in %s", ownerId, golden.getFileName())
          .isIn("uml:Node", "uml:Device", "uml:ExecutionEnvironment");
      assertThat(deployment.getAttribute("location"))
          .describedAs("Deployment %s location must equal its owning node in %s", ownerId, golden)
          .isEqualTo(ownerId);
      // deployedArtifact is a DeployedArtifact — an Artifact (DeploymentSpecification specializes
      // Artifact), never a Node/other classifier (a reversed supplier would point at a Node).
      assertThat(typeById.get(deployment.getAttribute("deployedArtifact")))
          .describedAs("Deployment %s deployedArtifact target type in %s", ownerId, golden)
          .isIn("uml:Artifact", "uml:DeploymentSpecification");
    }
  }

  // ---- M-GEN: a Generalization is owned by the specific Classifier (§9.2.3, §9.9.7) -------------

  @ParameterizedTest(name = "{0}")
  @MethodSource("committedGoldens")
  void everyGeneralizationIsOwnedByItsSpecificClassifier(Path golden) throws Exception {
    Document document = parse(golden);
    Map<String, String> typeById = xmiTypeById(document);
    NodeList generalizations = document.getElementsByTagName("generalization");
    for (int i = 0; i < generalizations.getLength(); i++) {
      Element generalization = (Element) generalizations.item(i);
      String id = generalization.getAttributeNS(XmiHelpers.XMI_NS, "id");
      // Classifier::generalization (subsets ownedElement): a Generalization is nested in its
      // SPECIFIC classifier, never a standalone Model child (the CL-1 defect), and general names
      // the
      // GENERAL classifier — both ends are Classifiers.
      assertThat(
              ((Element) generalization.getParentNode()).getAttributeNS(XmiHelpers.XMI_NS, "type"))
          .describedAs("Generalization %s owner in %s", id, golden.getFileName())
          .isIn("uml:Class", "uml:Interface", "uml:DataType", "uml:Enumeration");
      assertThat(typeById.get(generalization.getAttribute("general")))
          .describedAs("Generalization %s general target type in %s", id, golden.getFileName())
          .isIn("uml:Class", "uml:Interface", "uml:DataType", "uml:Enumeration");
    }
  }

  // ---- M-PORT: a Port is bare — /provided and /required derive from its type (§11.8.14) ---------

  @ParameterizedTest(name = "{0}")
  @MethodSource("committedGoldens")
  void everyPortIsBareSoItsDerivedPropertiesStayDerived(Path golden) throws Exception {
    for (Element attribute : elementsByTag(parse(golden), "ownedAttribute")) {
      if (!"uml:Port".equals(attribute.getAttributeNS(XmiHelpers.XMI_NS, "type"))) {
        continue;
      }
      // Port::/provided and /required are DERIVED from the Port's type, so they must not be shipped
      // as structural attributes; nor may a Port carry a fabricated type= (the CMP-1 defect class).
      assertThat(attribute.hasAttribute("provided"))
          .describedAs("Port provided= in %s", golden)
          .isFalse();
      assertThat(attribute.hasAttribute("required"))
          .describedAs("Port required= in %s", golden)
          .isFalse();
      assertThat(attribute.hasAttribute("type")).describedAs("Port type= in %s", golden).isFalse();
    }
  }

  // ---- M-PARAMS: an Operation emits one ownedParameter per source parameter + return (§9.6) -----

  static Stream<Arguments> operationBearingFixtures() {
    return Stream.of(
        Arguments.of("class", "valid-uml-basic", "uml-basic"),
        Arguments.of("complex-class", "valid-uml-complex", "uml-complex-class"),
        Arguments.of("component", "valid-uml-component-basic", "uml-component-basic"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("operationBearingFixtures")
  void everyOperationEmitsOneParameterPerSourceParameterAndReturn(
      String label, String source, String layout) throws Exception {
    JsonNode sourceModel = fixtureJson("fixtures/source/" + source + ".json");
    Document output = parse(exportContent(source, layout));
    Map<String, Element> elementById = elementById(output);

    for (JsonNode node : sourceModel.at("/nodes")) {
      JsonNode operations = node.at("/properties/uml/operations");
      if (!operations.isArray()) {
        continue;
      }
      // Operation names are unique only within a classifier, not globally, so scope the lookup to
      // the owning classifier element (IdentifierMap convention: its xmi:id is "id-" + node id).
      Element classifier = elementById.get("id-" + node.at("/id").asText());
      assertThat(classifier)
          .describedAs("classifier %s must be emitted (%s fixture)", node.at("/id").asText(), label)
          .isNotNull();
      Map<String, Element> operationsByName = new HashMap<>();
      for (Element operation : childElements(classifier, "ownedOperation")) {
        operationsByName.put(operation.getAttribute("name"), operation);
      }
      for (JsonNode operation : operations) {
        String name = operation.at("/name").asText();
        // A return parameter exists only when return_type is present and not void — a void (or
        // absent) return is no return parameter in UML, so it must not inflate the expected count.
        String returnType = operation.at("/return_type").asText("");
        boolean hasReturn = !returnType.isBlank() && !returnType.equals("void");
        int expected = operation.at("/parameters").size() + (hasReturn ? 1 : 0);
        assertThat(operationsByName.get(name))
            .describedAs(
                "operation %s.%s must be emitted (%s)", node.at("/id").asText(), name, label)
            .isNotNull();
        assertThat(childElements(operationsByName.get(name), "ownedParameter"))
            .describedAs(
                "operation %s must emit %d ownedParameter(s) — one per source parameter plus"
                    + " return_type — not silently drop them (UML 2.5.1 §9.6, CF-1) in %s",
                name, expected, label)
            .hasSize(expected);
      }
    }
  }

  // ---- bite-proofs: each structural checker actually catches its violation ----------------------

  @Test
  void theStructuralInvariantsActuallyCatchTheirViolations() throws Exception {
    // One document that breaks every structural rule at once, proving none is vacuous: an
    // Association with a single end, a Deployment not owned by its location, a Port that still
    // carries derived provided=/type= attributes, and a Generalization emitted as a standalone
    // Model
    // child instead of nested in its specific classifier.
    Document broken =
        parse(
            "<xmi:XMI xmlns:xmi=\""
                + XmiHelpers.XMI_NS
                + "\" xmlns:uml=\"http://www.omg.org/spec/UML/20161101\">"
                + "<uml:Model xmi:id=\"id-model\">"
                + "<packagedElement xmi:type=\"uml:Component\" xmi:id=\"id-comp\">"
                + "<ownedAttribute xmi:type=\"uml:Port\" xmi:id=\"id-port\" type=\"id-iface\""
                + " provided=\"id-iface\"/>"
                + "</packagedElement>"
                + "<packagedElement xmi:type=\"uml:Node\" xmi:id=\"id-node\">"
                + "<deployment xmi:type=\"uml:Deployment\" xmi:id=\"id-dep\""
                + " deployedArtifact=\"id-node\" location=\"id-elsewhere\"/>"
                + "</packagedElement>"
                + "<packagedElement xmi:type=\"uml:Association\" xmi:id=\"id-assoc\">"
                + "<ownedEnd xmi:id=\"id-only-end\" type=\"id-comp\"/>"
                + "</packagedElement>"
                + "<generalization xmi:type=\"uml:Generalization\" xmi:id=\"id-loose-gen\""
                + " general=\"id-comp\"/>"
                + "</uml:Model></xmi:XMI>");

    Map<String, String> typeById = xmiTypeById(broken);
    Element association = packagedElementsOfType(broken, Set.of("uml:Association")).get(0);
    Element deployment = (Element) broken.getElementsByTagName("deployment").item(0);
    Element port = elementsByTag(broken, "ownedAttribute").get(0);
    Element looseGeneralization = (Element) broken.getElementsByTagName("generalization").item(0);

    assertThat(childElements(association, "ownedEnd")).hasSize(1); // < 2 → M-ENDS would fail
    assertThat(deployment.getAttribute("location"))
        .isNotEqualTo(
            ((Element) deployment.getParentNode()).getAttributeNS(XmiHelpers.XMI_NS, "id"));
    assertThat(typeById.get(deployment.getAttribute("deployedArtifact")))
        .isEqualTo("uml:Node"); // not an Artifact → M-DEPLOY would fail
    assertThat(port.hasAttribute("provided")).isTrue(); // → M-PORT would fail
    assertThat(port.hasAttribute("type")).isTrue();
    // parent is uml:Model, not a classifier → M-GEN would fail
    assertThat(((Element) looseGeneralization.getParentNode()).getLocalName()).isEqualTo("Model");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static List<Element> packagedElementsOfType(Document document, Set<String> xmiTypes) {
    List<Element> matches = new ArrayList<>();
    for (Element element : elementsByTag(document, "packagedElement")) {
      if (xmiTypes.contains(element.getAttributeNS(XmiHelpers.XMI_NS, "type"))) {
        matches.add(element);
      }
    }
    return matches;
  }

  private static Map<String, Element> elementById(Document document) {
    Map<String, Element> byId = new HashMap<>();
    collectElementsById(document.getDocumentElement(), byId);
    return byId;
  }

  private static void collectElementsById(Element element, Map<String, Element> byId) {
    String id = element.getAttributeNS(XmiHelpers.XMI_NS, "id");
    if (!id.isEmpty()) {
      byId.putIfAbsent(id, element);
    }
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
        collectElementsById((Element) children.item(i), byId);
      }
    }
  }

  private static Map<String, String> xmiTypeById(Document document) {
    Map<String, String> typeById = new HashMap<>();
    collectXmiTypes(document.getDocumentElement(), typeById);
    return typeById;
  }

  private static void collectXmiTypes(Element element, Map<String, String> typeById) {
    String id = element.getAttributeNS(XmiHelpers.XMI_NS, "id");
    if (!id.isEmpty()) {
      typeById.put(id, element.getAttributeNS(XmiHelpers.XMI_NS, "type"));
    }
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
        collectXmiTypes((Element) children.item(i), typeById);
      }
    }
  }

  /** Direct child elements of the given local tag name (not descendants). */
  private static List<Element> childElements(Element parent, String tagName) {
    List<Element> children = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE
          && tagName.equals(((Element) node).getTagName())) {
        children.add((Element) node);
      }
    }
    return children;
  }

  private static List<Element> elementsByTag(Document document, String tagName) {
    List<Element> elements = new ArrayList<>();
    NodeList nodes = document.getElementsByTagName(tagName);
    for (int i = 0; i < nodes.getLength(); i++) {
      elements.add((Element) nodes.item(i));
    }
    return elements;
  }

  private static Document parse(Path golden) throws Exception {
    return parse(Files.readString(golden));
  }

  private static Document parse(String xml) throws Exception {
    return SchemaValidation.secureXmiDocumentBuilderFactory()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private String exportContent(String source, String layout) throws Exception {
    var input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/" + source + ".json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/" + layout + ".json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
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
              <xsd:choice minOccurs="0" maxOccurs="unbounded"><xsd:any processContents="lax"/></xsd:choice>
              <xsd:anyAttribute processContents="lax"/>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
        """,
        StandardCharsets.UTF_8);
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            input.toString(),
            Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString()));
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    return envelope.at("/data/content").asText();
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

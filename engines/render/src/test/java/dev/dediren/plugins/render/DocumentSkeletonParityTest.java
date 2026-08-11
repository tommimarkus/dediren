package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two document lanes must open the same document from the same policy.
 *
 * <p>{@code SvgDocument} and {@code UmlSequenceRenderer} draw different things — one paints nodes,
 * edges and groups, the other lifelines, frames and messages — but the shell around them is not a
 * per-lane decision: the sized root, the accessible name, the background that fills the viewBox and
 * the {@code <g>} carrying the base typography all come from the render policy alone. They used to
 * be written twice, and the copies had diverged: the sequence lane dropped the background's {@code
 * fill-opacity} and the base {@code font-weight}/{@code font-style} entirely, so the same policy
 * produced two different documents depending on which lane happened to read it. Nothing caught
 * that, because no fixture policy sets those three fields.
 *
 * <p>This one does. It is a parity oracle rather than a golden: the geometry-derived attributes
 * ({@code width}, {@code height}, {@code viewBox}, and the background rect's own box) legitimately
 * differ between two different diagrams, so they are checked for internal consistency — the
 * background must cover exactly the viewBox — and everything else is compared literally.
 */
class DocumentSkeletonParityTest {

  /** Attributes whose value is derived from the content, so the two lanes must differ on them. */
  private static final Set<String> GEOMETRY_ATTRIBUTES = Set.of("width", "height", "viewBox");

  private static final Set<String> BACKGROUND_GEOMETRY_ATTRIBUTES =
      Set.of("x", "y", "width", "height");

  @Test
  void bothLanesOpenTheSameDocumentFromTheSamePolicy() throws Exception {
    // No render metadata: nothing routes this to the sequence lane, so it takes SvgDocument's.
    Skeleton generic = skeleton(render("fixtures/layout-result/basic.json", null));
    // Lifeline and Message metadata: UmlSequenceRenderer.isSequence sends this one the other way.
    Skeleton sequence =
        skeleton(
            render(
                "fixtures/layout-result/uml-sequence-basic.json",
                "fixtures/render-metadata/uml-sequence-basic.json"));

    assertThat(sequence.root())
        .as("root attributes other than the content-derived size")
        .isEqualTo(generic.root());
    assertThat(sequence.accessibleName())
        .as("accessible-name markup (<title>/<desc>)")
        .isEqualTo(generic.accessibleName());
    assertThat(sequence.background())
        .as("background paint attributes other than the box it fills")
        .isEqualTo(generic.background());
    assertThat(sequence.typography())
        .as("base typography <g> attributes")
        .isEqualTo(generic.typography());

    // The parity above would also be satisfied by two lanes that both emitted nothing, so pin that
    // the policy fields which used to be dropped are present in both.
    assertThat(generic.background()).containsEntry("fill-opacity", "0.85");
    assertThat(generic.typography()).containsEntry("font-weight", "bold");
    assertThat(generic.typography()).containsEntry("font-style", "italic");
    assertThat(generic.root()).containsEntry("xml:lang", "en-GB");

    // And that each lane's background really does cover its own viewBox rather than the other's.
    assertThat(generic.backgroundCoversViewBox()).as("generic background covers viewBox").isTrue();
    assertThat(sequence.backgroundCoversViewBox())
        .as("sequence background covers viewBox")
        .isTrue();
  }

  private static String render(String layoutPath, String metadataPath) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", RenderTestSupport.fixtureJson(layoutPath));
    if (metadataPath != null) {
      input.set("render_metadata", RenderTestSupport.fixtureJson(metadataPath));
    }
    input.set("policy", policy());
    return RenderTestSupport.render(input);
  }

  /**
   * One policy for both lanes — the same object, not two equivalent ones — carrying every skeleton
   * field a policy can set, including the three the sequence lane used to ignore. {@code
   * semantic_profile} is declared so the UML metadata the sequence input carries is applied rather
   * than warned about.
   */
  private static ObjectNode policy() {
    ObjectNode policy = JsonSupport.objectMapper().createObjectNode();
    policy.put("render_policy_schema_version", "render-policy.schema.v3");
    policy.put("semantic_profile", "uml");
    policy.putObject("page").put("width", 900).put("height", 520);
    policy.putObject("margin").put("top", 12).put("right", 12).put("bottom", 12).put("left", 12);
    ObjectNode style = policy.putObject("style");
    style.putObject("background").put("fill", "#fefefe").put("fill_opacity", 0.85);
    style
        .putObject("font")
        .put("family", "Inter, Arial, sans-serif")
        .put("size", 13)
        .put("weight", "bold")
        .put("style", "italic");
    policy
        .putObject("accessibility")
        .put("title", "Skeleton parity")
        .put("description", "One policy, two lanes, one document shell")
        .put("lang", "en-GB")
        .put("dir", "ltr");
    return policy;
  }

  private static Skeleton skeleton(String svg) {
    Element root = SvgAudit.parse(svg).getDocumentElement();
    Element background = firstChildElement(root, "rect");
    Element typography = firstChildElement(root, "g");
    return new Skeleton(
        attributesExcept(root, GEOMETRY_ATTRIBUTES),
        accessibleName(root),
        attributesExcept(background, BACKGROUND_GEOMETRY_ATTRIBUTES),
        attributesExcept(typography, Set.of()),
        root.getAttribute("viewBox"),
        box(background));
  }

  /** The {@code <title>}/{@code <desc>} children, in document order, as {@code name=text}. */
  private static String accessibleName(Element root) {
    StringBuilder markup = new StringBuilder();
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      String name = child.getNodeName();
      if ("title".equals(name) || "desc".equals(name)) {
        markup.append(name).append('=').append(child.getTextContent()).append(';');
      }
    }
    return markup.toString();
  }

  private static Element firstChildElement(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
        return (Element) child;
      }
    }
    throw new AssertionError("no <" + name + "> directly under <" + parent.getNodeName() + ">");
  }

  /** Attribute name/value pairs in document order, minus the ones the caller excludes. */
  private static Map<String, String> attributesExcept(Element element, Set<String> excluded) {
    Map<String, String> attributes = new LinkedHashMap<>();
    NamedNodeMap named = element.getAttributes();
    for (int index = 0; index < named.getLength(); index++) {
      Node attribute = named.item(index);
      if (!excluded.contains(attribute.getNodeName())) {
        attributes.put(attribute.getNodeName(), attribute.getNodeValue());
      }
    }
    return attributes;
  }

  private static double[] box(Element rect) {
    return new double[] {
      Double.parseDouble(rect.getAttribute("x")),
      Double.parseDouble(rect.getAttribute("y")),
      Double.parseDouble(rect.getAttribute("width")),
      Double.parseDouble(rect.getAttribute("height"))
    };
  }

  private record Skeleton(
      Map<String, String> root,
      String accessibleName,
      Map<String, String> background,
      Map<String, String> typography,
      String viewBox,
      double[] backgroundBox) {

    boolean backgroundCoversViewBox() {
      String[] parts = viewBox.trim().split("\\s+");
      for (int index = 0; index < 4; index++) {
        if (Math.abs(Double.parseDouble(parts[index]) - backgroundBox[index]) > 0.05) {
          return false;
        }
      }
      return true;
    }
  }
}

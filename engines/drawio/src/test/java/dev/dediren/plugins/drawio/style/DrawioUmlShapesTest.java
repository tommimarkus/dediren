package dev.dediren.plugins.drawio.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DrawioUmlShapesTest {

  /**
   * The UML element vocabulary, mirrored here so coverage can be asserted without a module edge
   * onto {@code uml} — see {@code DrawioUmlShapes}' javadoc and the dist-tool coverage test, which
   * pins this list against the real vocabulary as text.
   *
   * <p>Order is the union {@code Uml.isNamedElementType} forms, in the order it unions its seven
   * backing constants: structural, activity, sequence, state-machine, use-case, component,
   * deployment.
   */
  private static final List<String> ELEMENT_TYPES =
      List.of(
          "Package",
          "Class",
          "Interface",
          "DataType",
          "Enumeration",
          "Component",
          "Activity",
          "Action",
          "InitialNode",
          "ActivityFinalNode",
          "DecisionNode",
          "MergeNode",
          "ForkNode",
          "JoinNode",
          "ObjectNode",
          "Interaction",
          "Lifeline",
          "ExecutionSpecification",
          "Gate",
          "DestructionOccurrenceSpecification",
          "CombinedFragment",
          "InteractionOperand",
          "StateMachine",
          "Region",
          "State",
          "FinalState",
          "Pseudostate",
          "Actor",
          "UseCase",
          "ExtensionPoint",
          "Port",
          "Node",
          "Device",
          "ExecutionEnvironment",
          "Artifact",
          "DeploymentSpecification");

  @Test
  void everyElementTypeResolvesToAShape() {
    for (String elementType : ELEMENT_TYPES) {
      assertThat(DrawioUmlShapes.shapeFor(elementType))
          .as("element type %s must resolve to a shape", elementType)
          .isNotNull();
      assertThat(DrawioUmlShapes.isMapped(elementType))
          .as("element type %s must be covered by the table", elementType)
          .isTrue();
    }
  }

  @Test
  void coverageIsExactlyThe36ElementTypes() {
    assertThat(ELEMENT_TYPES).hasSize(36);
  }

  @Test
  void noStyleStringIsMissingATrailingSemicolon() {
    for (String elementType : ELEMENT_TYPES) {
      String style = DrawioUmlShapes.shapeFor(elementType).style();
      assertThat(style)
          .as(
              "style for %s must end with ';' or a following key silently swallows the previous"
                  + " one, as it does in draw.io's own shape library",
              elementType)
          .endsWith(";");
    }
    assertThat(DrawioUmlShapes.shapeFor("NotARealUmlType").style()).endsWith(";");
  }

  @Test
  void anUnmappedTypeReturnsTheFallbackRatherThanFailing() {
    assertThat(DrawioUmlShapes.isMapped("NotARealUmlType")).isFalse();
    assertThat(DrawioUmlShapes.shapeFor("NotARealUmlType").style())
        .isEqualTo("rounded=0;whiteSpace=wrap;html=1;");
  }

  @Test
  void theDedicatedStencilsAreTheOnesDrawioActuallyShips() {
    // Verified against draw.io's own grapheditor UML palette; each of these is a real stencil
    // rather than a stereotyped rectangle, which is the only reason to reach for it.
    assertThat(DrawioUmlShapes.shapeFor("Actor").style()).contains("shape=umlActor;");
    assertThat(DrawioUmlShapes.shapeFor("Component").style()).contains("shape=module;");
    assertThat(DrawioUmlShapes.shapeFor("Package").style()).contains("shape=folder;");
    assertThat(DrawioUmlShapes.shapeFor("Interaction").style()).contains("shape=umlFrame;");
    assertThat(DrawioUmlShapes.shapeFor("Node").style()).contains("shape=cube;");
    assertThat(DrawioUmlShapes.shapeFor("FinalState").style()).contains("shape=endState;");
  }

  @Test
  void aLifelineIsDrawnAsItsHeadAloneRatherThanAsAStencilItsGeometryCannotFill() {
    // The laid-out Lifeline box is the head only (64 tall in every sequence fixture) while the
    // messages route below it, so shape=umlLifeline would draw a tail stopping short of every
    // message. The head rectangle is the honest reading of the geometry the layout supplies, and
    // DEDIREN_DRAWIO_ORNAMENT_OMITTED still declares the missing tail.
    assertThat(DrawioUmlShapes.shapeFor("Lifeline").style()).doesNotContain("umlLifeline");
    assertThat(DrawioUmlShapes.shapeFor("Lifeline").style()).doesNotContain("lifelinePerimeter");
  }

  @Test
  void theControlNodesCarryTheirSemanticFillRatherThanAnOutline() {
    // An initial node, a final node and a fork/join bar are solid by definition; an unfilled one
    // reads as a different node.
    for (String elementType : List.of("InitialNode", "ForkNode", "JoinNode", "Pseudostate")) {
      assertThat(DrawioUmlShapes.shapeFor(elementType).style())
          .as("element type %s", elementType)
          .contains("fillColor=strokeColor;");
    }
  }

  @Test
  void theFramesAreUnfilledSoWhatTheyEncloseStaysVisible() {
    for (String elementType :
        List.of("Activity", "Interaction", "StateMachine", "Region", "CombinedFragment")) {
      assertThat(DrawioUmlShapes.shapeFor(elementType).style())
          .as("element type %s", elementType)
          .contains("fillColor=none;");
    }
  }

  @Test
  void theThreeTypeNamesSharedWithArchimateResolveInBothTablesToDifferentShapes() {
    // Node, Device and Artifact are declared by both vocabularies. Keeping the two tables separate
    // is what stops a UML deployment node from being drawn as an ArchiMate technology node; the
    // exporter picks the table from the declared view kind.
    for (String shared : List.of("Node", "Device", "Artifact")) {
      assertThat(DrawioShapes.isMapped(shared)).as("%s is an ArchiMate type", shared).isTrue();
      assertThat(DrawioUmlShapes.isMapped(shared)).as("%s is a UML type", shared).isTrue();
      assertThat(DrawioUmlShapes.shapeFor(shared).style())
          .as("%s must not resolve to the ArchiMate shape", shared)
          .isNotEqualTo(DrawioShapes.shapeFor(shared).style());
    }
  }

  @Test
  void noUmlStyleReachesForTheArchimateStencilLibrary() {
    for (String elementType : ELEMENT_TYPES) {
      assertThat(DrawioUmlShapes.shapeFor(elementType).style())
          .as("element type %s", elementType)
          .doesNotContain("mxgraph.archimate3");
    }
  }
}

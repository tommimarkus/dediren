package dev.dediren.plugins.drawio.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DrawioShapesTest {

  /**
   * {@code Archimate.ELEMENT_TYPES}, mirrored here so coverage can be asserted without a module
   * edge onto {@code archimate} — see {@code DrawioShapes}' javadoc and the dist-tool coverage
   * test, which pins this list against the real vocabulary as text.
   */
  private static final List<String> ELEMENT_TYPES =
      List.of(
          "Plateau",
          "WorkPackage",
          "Deliverable",
          "ImplementationEvent",
          "Gap",
          "AndJunction",
          "OrJunction",
          "Grouping",
          "Location",
          "Stakeholder",
          "Driver",
          "Assessment",
          "Goal",
          "Outcome",
          "Value",
          "Meaning",
          "Constraint",
          "Requirement",
          "Principle",
          "CourseOfAction",
          "Resource",
          "ValueStream",
          "Capability",
          "BusinessInterface",
          "BusinessCollaboration",
          "BusinessActor",
          "BusinessRole",
          "BusinessProcess",
          "BusinessService",
          "BusinessInteraction",
          "BusinessFunction",
          "BusinessEvent",
          "Product",
          "BusinessObject",
          "Contract",
          "Representation",
          "ApplicationInterface",
          "ApplicationCollaboration",
          "ApplicationComponent",
          "ApplicationService",
          "ApplicationInteraction",
          "ApplicationFunction",
          "ApplicationProcess",
          "ApplicationEvent",
          "DataObject",
          "TechnologyInterface",
          "TechnologyCollaboration",
          "Node",
          "SystemSoftware",
          "Device",
          "Facility",
          "Equipment",
          "Path",
          "TechnologyService",
          "TechnologyInteraction",
          "TechnologyFunction",
          "TechnologyProcess",
          "TechnologyEvent",
          "Artifact",
          "Material",
          "CommunicationNetwork",
          "DistributionNetwork");

  @Test
  void everyElementTypeResolvesToAShape() {
    for (String elementType : ELEMENT_TYPES) {
      assertThat(DrawioShapes.shapeFor(elementType))
          .as("element type %s must resolve to a shape", elementType)
          .isNotNull();
    }
  }

  @Test
  void coverageIsExactlyThe62ElementTypes() {
    assertThat(ELEMENT_TYPES).hasSize(62);
  }

  @Test
  void theTwoJunctionsDifferOnlyByFill() {
    DrawioShape and = DrawioShapes.shapeFor("AndJunction");
    DrawioShape or = DrawioShapes.shapeFor("OrJunction");

    assertThat(and.width()).isEqualTo(10);
    assertThat(and.height()).isEqualTo(10);
    assertThat(or.width()).isEqualTo(10);
    assertThat(or.height()).isEqualTo(10);

    assertThat(and.style()).contains("ellipse").contains("fillColor=strokeColor");
    assertThat(or.style()).contains("ellipse").contains("fillColor=#ffffff");

    // Stripping the fill fragment leaves the two styles identical.
    assertThat(and.style().replace("fillColor=strokeColor", ""))
        .isEqualTo(or.style().replace("fillColor=#ffffff", ""));
  }

  @Test
  void groupingKeepsDashedUnfilledOutline() {
    DrawioShape grouping = DrawioShapes.shapeFor("Grouping");

    assertThat(grouping.style()).contains("dashed=1;").contains("fillColor=none;");
    assertThat(grouping.style()).contains("archiType=square;");
  }

  @Test
  void anUnmappedTypeReturnsTheFallbackRatherThanFailing() {
    DrawioShape fallback = DrawioShapes.shapeFor("NotARealArchimateType");

    assertThat(fallback.width()).isEqualTo(150);
    assertThat(fallback.height()).isEqualTo(75);
    assertThat(fallback.style()).isEqualTo("rounded=1;whiteSpace=wrap;html=1;");
  }

  @Test
  void noStyleStringIsMissingATrailingSemicolon() {
    for (String elementType : ELEMENT_TYPES) {
      String style = DrawioShapes.shapeFor(elementType).style();
      assertThat(style)
          .as(
              "style for %s must end with ';' or a following key silently swallows the previous"
                  + " one, as it does in draw.io's own shape library",
              elementType)
          .endsWith(";");
    }
    assertThat(DrawioShapes.shapeFor("NotARealArchimateType").style()).endsWith(";");
  }

  @Test
  void everyBoxedElementUsesTheArchimate3ApplicationShapeAndTheDefault150x75Box() {
    for (String elementType : ELEMENT_TYPES) {
      if (elementType.equals("AndJunction") || elementType.equals("OrJunction")) {
        continue;
      }
      DrawioShape shape = DrawioShapes.shapeFor(elementType);
      assertThat(shape.style())
          .as("element type %s", elementType)
          .contains("shape=mxgraph.archimate3.application;");
      assertThat(shape.width()).isEqualTo(150);
      assertThat(shape.height()).isEqualTo(75);
    }
  }

  @Test
  void archiTypeDefaultsToAbsentRatherThanAnExplicitSquareToken() {
    // Plateau, Deliverable, Gap, and Device are the table's four "none" entries: square is the
    // default outline, so they carry no archiType= token at all.
    for (String elementType : List.of("Plateau", "Deliverable", "Gap", "Device")) {
      assertThat(DrawioShapes.shapeFor(elementType).style())
          .as("element type %s", elementType)
          .doesNotContain("archiType=");
    }
  }
}

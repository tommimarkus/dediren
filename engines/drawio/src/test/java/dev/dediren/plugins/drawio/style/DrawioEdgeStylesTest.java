package dev.dediren.plugins.drawio.style;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.plugins.drawio.style.DrawioEdgeStyles.Notation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DrawioEdgeStylesTest {

  /** {@code Archimate.RELATIONSHIP_TYPES}, mirrored here; pinned as text by the dist-tool test. */
  private static final List<String> ARCHIMATE_RELATIONSHIP_TYPES =
      List.of(
          "Composition",
          "Aggregation",
          "Assignment",
          "Realization",
          "Specialization",
          "Serving",
          "Access",
          "Influence",
          "Flow",
          "Triggering",
          "Association");

  /** {@code Uml.RELATIONSHIP_TYPES}, mirrored here; pinned as text by the dist-tool test. */
  private static final List<String> UML_RELATIONSHIP_TYPES =
      List.of(
          "Association",
          "Composition",
          "Aggregation",
          "Generalization",
          "Realization",
          "Dependency",
          "ControlFlow",
          "ObjectFlow",
          "Message",
          "Transition",
          "Include",
          "Extend",
          "Usage",
          "Deployment",
          "Manifestation",
          "CommunicationPath");

  @Test
  void coverageIsExactlyTheTwoDeclaredRelationshipVocabularies() {
    assertThat(ARCHIMATE_RELATIONSHIP_TYPES).hasSize(11);
    assertThat(UML_RELATIONSHIP_TYPES).hasSize(16);
    for (String type : ARCHIMATE_RELATIONSHIP_TYPES) {
      assertThat(DrawioEdgeStyles.isMapped(Notation.ARCHIMATE, type))
          .as("ArchiMate relationship %s", type)
          .isTrue();
    }
    for (String type : UML_RELATIONSHIP_TYPES) {
      assertThat(DrawioEdgeStyles.isMapped(Notation.UML, type))
          .as("UML relationship %s", type)
          .isTrue();
    }
  }

  @Test
  void noStyleStringIsMissingATrailingSemicolon() {
    for (String type : ARCHIMATE_RELATIONSHIP_TYPES) {
      assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, type))
          .as("ArchiMate relationship %s", type)
          .endsWith(";");
    }
    for (String type : UML_RELATIONSHIP_TYPES) {
      assertThat(DrawioEdgeStyles.styleFor(Notation.UML, type))
          .as("UML relationship %s", type)
          .endsWith(";");
    }
    assertThat(DrawioEdgeStyles.styleFor(Notation.UML, "NotARealRelationship")).endsWith(";");
  }

  @Test
  void everyStyleKeepsTheRouteTheLayoutComputed() {
    // edgeStyle=none is what stops draw.io recomputing an orthogonal route around the waypoints
    // the exporter supplies; a notation fragment must never displace it.
    for (String type : ARCHIMATE_RELATIONSHIP_TYPES) {
      assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, type))
          .as("ArchiMate relationship %s", type)
          .startsWith("edgeStyle=none;rounded=0;html=1;");
    }
    for (String type : UML_RELATIONSHIP_TYPES) {
      assertThat(DrawioEdgeStyles.styleFor(Notation.UML, type))
          .as("UML relationship %s", type)
          .startsWith("edgeStyle=none;rounded=0;html=1;");
    }
  }

  @Test
  void theArchimateWholePartDiamondsSitAtTheSourceEnd() {
    // ArchiMate draws composition and aggregation from the whole, which dediren models as the
    // relationship source. Reversing the diamond states the opposite relationship, so this is the
    // one assertion here that is about meaning rather than appearance. Matches the shipped
    // render policy (marker_start) and the in-repo evidence file.
    String composition = DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Composition");
    assertThat(composition).contains("startArrow=diamondThin;").contains("startFill=1;");
    assertThat(composition).contains("endArrow=none;").doesNotContain("endArrow=diamondThin");

    String aggregation = DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Aggregation");
    assertThat(aggregation).contains("startArrow=diamondThin;").contains("startFill=0;");
    assertThat(aggregation).contains("endArrow=none;").doesNotContain("endArrow=diamondThin");
  }

  @Test
  void theUmlWholePartDiamondsSitAtTheSourceEndToo() {
    String composition = DrawioEdgeStyles.styleFor(Notation.UML, "Composition");
    assertThat(composition).contains("startArrow=diamondThin;").contains("startFill=1;");
    assertThat(composition).contains("endArrow=none;");

    String aggregation = DrawioEdgeStyles.styleFor(Notation.UML, "Aggregation");
    assertThat(aggregation).contains("startArrow=diamondThin;").contains("startFill=0;");
    assertThat(aggregation).contains("endArrow=none;");
  }

  @Test
  void hollowAndFilledHeadsSeparateTheRelationshipsThatShareALine() {
    // Specialization and Triggering are both solid lines; only the head tells them apart.
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Specialization"))
        .contains("endArrow=block;")
        .contains("endFill=0;");
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Triggering"))
        .contains("endArrow=block;")
        .contains("endFill=1;");
    assertThat(DrawioEdgeStyles.styleFor(Notation.UML, "Generalization"))
        .contains("endArrow=block;")
        .contains("endFill=0;");
  }

  @Test
  void theDashedAndDottedRelationshipsMatchTheShippedRenderPolicysLineStyles() {
    // ArchiMate: Realization and Access are dotted, Influence and Flow dashed. The distinction is
    // in the policy dediren's own SVG renderer reads, so the two exports agree.
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Realization"))
        .contains("dashed=1;")
        .contains("dashPattern=1 3;");
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Access"))
        .contains("dashed=1;")
        .contains("dashPattern=1 3;");
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Influence"))
        .contains("dashed=1;")
        .doesNotContain("dashPattern");
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Flow"))
        .contains("dashed=1;")
        .doesNotContain("dashPattern");

    // UML: Realization is dashed rather than dotted, and the whole dependency family with it.
    for (String type :
        List.of(
            "Realization", "Dependency", "Usage", "Include", "Extend", "Deployment",
            "Manifestation")) {
      assertThat(DrawioEdgeStyles.styleFor(Notation.UML, type))
          .as("UML relationship %s", type)
          .contains("dashed=1;");
    }
  }

  @Test
  void theUndirectedRelationshipsCarryNoHeadAtEitherEnd() {
    for (String style :
        List.of(
            DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Association"),
            DrawioEdgeStyles.styleFor(Notation.UML, "Association"),
            DrawioEdgeStyles.styleFor(Notation.UML, "CommunicationPath"))) {
      assertThat(style).contains("endArrow=none;").doesNotContain("startArrow=");
    }
  }

  @Test
  void anUnmappedRelationshipFallsBackToAPlainDirectedLine() {
    assertThat(DrawioEdgeStyles.isMapped(Notation.ARCHIMATE, "NotARealRelationship")).isFalse();
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "NotARealRelationship"))
        .isEqualTo("edgeStyle=none;rounded=0;html=1;endArrow=block;endFill=1;endSize=12;");
    assertThat(DrawioEdgeStyles.styleFor(Notation.UML, null))
        .isEqualTo("edgeStyle=none;rounded=0;html=1;endArrow=block;endFill=1;endSize=12;");
  }

  @Test
  void theTwoVocabulariesDisagreeWhereTheNotationsDisagree() {
    // Realization is dotted in ArchiMate and dashed in UML; a shared table would have to pick one.
    assertThat(DrawioEdgeStyles.styleFor(Notation.ARCHIMATE, "Realization"))
        .isNotEqualTo(DrawioEdgeStyles.styleFor(Notation.UML, "Realization"));
    // A relationship name only one vocabulary declares stays absent from the other.
    assertThat(DrawioEdgeStyles.isMapped(Notation.ARCHIMATE, "Generalization")).isFalse();
    assertThat(DrawioEdgeStyles.isMapped(Notation.UML, "Serving")).isFalse();
  }
}

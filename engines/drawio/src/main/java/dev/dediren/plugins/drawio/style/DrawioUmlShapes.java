package dev.dediren.plugins.drawio.style;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UML element type name (dediren's own vocabulary, the union {@code Uml.isNamedElementType} forms) →
 * draw.io shape.
 *
 * <p><strong>Ordering rule.</strong> The table below is built as a {@link LinkedHashMap} in {@code
 * Uml.java} declaration order — the seven element constants in the order {@code isNamedElementType}
 * unions them, and within each the order its source text lists them — and that order is
 * load-bearing, not cosmetic: {@code dist-tool}'s {@code DrawioNotationCoverageTest} reads {@code
 * Uml.java} as text and asserts this table's key sequence matches it exactly. The table is never
 * reordered to match either of draw.io's UML palettes. It is keyed and sequenced by dediren's
 * vocabulary alone.
 *
 * <p><strong>Why a second table rather than entries in {@link DrawioShapes}.</strong> {@code Node},
 * {@code Device} and {@code Artifact} are declared by <em>both</em> the UML and the ArchiMate
 * vocabularies and mean different things in each. One flat table keyed by bare type name would have
 * to pick a winner and would silently draw a UML deployment node as an ArchiMate technology node.
 * The exporter chooses between the two tables using the view's declared kind, which is the only
 * place that distinction exists.
 *
 * <p><strong>A hybrid palette, chosen per type.</strong> draw.io has two UML shape libraries and
 * neither covers this vocabulary: the spec-faithful one is largely stereotyped rectangles with only
 * four real stencils, and the classic one has dedicated stencils but no Artifact and no deployment
 * Node. Each entry below takes the best glyph available from either, and a plain rectangle where
 * neither offers one. A plain rectangle here is a decision, not a gap — UML draws a Class, an
 * Interface, an Artifact and a lifeline head as plain rectangles, distinguished by compartment
 * content and stereotype keywords this export has no source data to fabricate.
 *
 * <p><strong>No reverse (style → type) index.</strong> As in {@link DrawioShapes}, the style string
 * is not a unique key: sixteen types share the plain-rectangle style and three more share the cube.
 * Import resolves the element type from the {@code dedirenType} cell attribute, never from the
 * style.
 *
 * <p><strong>No palette colours.</strong> {@link DrawioPalette} is the ArchiMate layer palette,
 * pinned to the shipped ArchiMate render policy; UML has no layer semantics to colour by and is
 * conventionally monochrome, so UML cells carry only the semantic fills below — solid for the
 * control nodes whose meaning <em>is</em> their fill, {@code none} for the frames that must not hide
 * what they enclose.
 */
public final class DrawioUmlShapes {

  /**
   * The classifier box: UML's default element notation, and the correct drawing for every type
   * whose distinguishing detail is compartment content or a stereotype keyword.
   */
  private static final String PLAIN = "rounded=0;whiteSpace=wrap;html=1;";

  /** Returned for an element type absent from the table below — never a hard failure. */
  private static final DrawioShape FALLBACK = new DrawioShape(PLAIN, 160, 80);

  private static final Map<String, DrawioShape> TABLE = buildTable();

  private DrawioUmlShapes() {}

  /** The draw.io shape for {@code elementType}, or {@link #FALLBACK} if it is not in the table. */
  public static DrawioShape shapeFor(String elementType) {
    return TABLE.getOrDefault(elementType, FALLBACK);
  }

  /**
   * Whether the table covers {@code elementType} at all.
   *
   * <p>Separate from {@link #shapeFor} for the same reason as in {@link DrawioShapes}: the fallback
   * is a real, usable shape rather than an error signal, and it is byte-identical to the sixteen
   * deliberate plain-rectangle entries, so comparing against it would report every one of them as
   * unmapped.
   */
  public static boolean isMapped(String elementType) {
    return TABLE.containsKey(elementType);
  }

  private static Map<String, DrawioShape> buildTable() {
    var table = new LinkedHashMap<String, DrawioShape>();

    // Structural (Uml.STRUCTURAL_TYPES).
    table.put(
        "Package",
        new DrawioShape(
            "shape=folder;tabWidth=80;tabHeight=30;tabPosition=left;boundedLbl=1;"
                + "whiteSpace=wrap;html=1;",
            150,
            80));
    table.put("Class", plain(160, 80));
    table.put("Interface", plain(160, 80));
    table.put("DataType", plain(160, 80));
    table.put("Enumeration", plain(160, 80));
    // The module stencil draws the jetty tabs itself, so the component glyph is one cell; draw.io's
    // own palette builds it as a rectangle plus a badge child only because its template also
    // carries stereotype text.
    table.put(
        "Component",
        new DrawioShape(
            "shape=module;jettyWidth=10;jettyHeight=4;align=center;verticalAlign=top;"
                + "whiteSpace=wrap;html=1;",
            160,
            90));

    // Activity (Uml.ACTIVITY_TYPES).
    table.put("Activity", frame("rounded=1;absoluteArcSize=1;arcSize=20;", 200, 120));
    table.put("Action", roundedBox(140, 40));
    table.put("InitialNode", solidEllipse(30, 30));
    table.put("ActivityFinalNode", endState(30, 30));
    table.put("DecisionNode", new DrawioShape("rhombus;whiteSpace=wrap;html=1;", 40, 40));
    table.put("MergeNode", new DrawioShape("rhombus;whiteSpace=wrap;html=1;", 40, 40));
    table.put("ForkNode", bar(80, 6));
    table.put("JoinNode", bar(80, 6));
    // Not draw.io's own choice: its palette draws an object node with an infographic ribbon
    // stencil, which is not UML notation. UML draws it as a rectangle.
    table.put("ObjectNode", plain(140, 40));

    // Sequence (Uml.SEQUENCE_TYPES).
    table.put(
        "Interaction",
        new DrawioShape(
            "shape=umlFrame;whiteSpace=wrap;html=1;pointerEvents=0;fillColor=none;", 400, 300));
    // The head alone, deliberately. draw.io does ship a real shape=umlLifeline stencil that draws
    // a head plus a dashed tail down the cell, but the tail spans the cell and dediren lays a
    // Lifeline out as its head only — 64 tall in every sequence fixture, while the messages route
    // below it. Drawn with that stencil, every tail would stop short of every message it is
    // supposed to meet. Reaching the messages means deriving the stem extent from the enclosing
    // interaction frame, which is what the SVG renderer does and what this builder's
    // geometry-is-taken-never-computed rule forbids. DEDIREN_DRAWIO_ORNAMENT_OMITTED declares the
    // missing tail instead.
    table.put("Lifeline", plain(140, 64));
    table.put("ExecutionSpecification", plain(16, 96));
    table.put("Gate", plain(20, 20));
    table.put("DestructionOccurrenceSpecification", plain(24, 24));
    table.put("CombinedFragment", frame("rounded=0;", 240, 120));
    table.put("InteractionOperand", frame("rounded=0;dashed=1;", 240, 60));

    // State machine (Uml.STATE_MACHINE_TYPES).
    table.put("StateMachine", frame("rounded=1;absoluteArcSize=1;arcSize=10;", 200, 120));
    table.put("Region", frame("rounded=0;dashed=1;", 200, 120));
    table.put("State", roundedBox(140, 40));
    table.put("FinalState", endState(30, 30));
    // The initial kind's filled circle. Pseudostate carries its kind in source properties, and a
    // choice diamond or a fork bar is a different glyph; a type-keyed table cannot express that.
    table.put("Pseudostate", solidEllipse(30, 30));

    // Use case (Uml.USE_CASE_TYPES).
    table.put(
        "Actor",
        new DrawioShape(
            "shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;", 30, 60));
    table.put("UseCase", new DrawioShape("ellipse;whiteSpace=wrap;html=1;", 140, 70));
    table.put("ExtensionPoint", plain(140, 30));

    // Component view (Uml.COMPONENT_TYPES).
    table.put("Port", plain(20, 20));

    // Deployment (Uml.DEPLOYMENT_TYPES).
    table.put("Node", cube(140, 80));
    table.put("Device", cube(140, 80));
    table.put("ExecutionEnvironment", cube(140, 80));
    // Neither draw.io palette has an artifact glyph: both draw a rectangle and put «artifact» in
    // the label. The label here is the element's own, so the stereotype is not ours to invent.
    table.put("Artifact", plain(160, 60));
    table.put("DeploymentSpecification", plain(160, 60));

    return Collections.unmodifiableMap(table);
  }

  private static DrawioShape plain(int width, int height) {
    return new DrawioShape(PLAIN, width, height);
  }

  /**
   * The rounded box a state and an action share.
   *
   * <p>Built without {@link #PLAIN} on purpose: mxGraph parses a style left to right and the last
   * occurrence of a key wins, so prefixing {@code rounded=1} onto a fragment that already carries
   * {@code rounded=0} silently draws a square box.
   */
  private static DrawioShape roundedBox(int width, int height) {
    return new DrawioShape(
        "rounded=1;absoluteArcSize=1;arcSize=10;whiteSpace=wrap;html=1;", width, height);
  }

  /** An enclosing frame: unfilled, with its label at the top, so its contents stay readable. */
  private static DrawioShape frame(String outline, int width, int height) {
    return new DrawioShape(
        outline + "verticalAlign=top;fillColor=none;whiteSpace=wrap;html=1;", width, height);
  }

  /** The filled disc an initial node and an initial pseudostate share. */
  private static DrawioShape solidEllipse(int width, int height) {
    return new DrawioShape("ellipse;fillColor=strokeColor;html=1;", width, height);
  }

  /** The encircled disc of a final node/state. */
  private static DrawioShape endState(int width, int height) {
    return new DrawioShape("ellipse;shape=endState;fillColor=strokeColor;html=1;", width, height);
  }

  /** The solid synchronisation bar a fork and a join share. */
  private static DrawioShape bar(int width, int height) {
    return new DrawioShape(
        "points=[];perimeter=orthogonalPerimeter;fillColor=strokeColor;html=1;", width, height);
  }

  /** The 3D box the three deployment targets share. */
  private static DrawioShape cube(int width, int height) {
    return new DrawioShape(
        "shape=cube;size=10;direction=south;verticalAlign=top;boundedLbl=1;whiteSpace=wrap;html=1;",
        width,
        height);
  }
}

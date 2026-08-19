package dev.dediren.plugins.drawio.style;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Relationship type name (dediren's own vocabularies, {@code Archimate.RELATIONSHIP_TYPES} and
 * {@code Uml.RELATIONSHIP_TYPES}) → the draw.io {@code style=} fragment that draws its notation.
 *
 * <p><strong>Ordering rule.</strong> Both tables below are {@link LinkedHashMap}s built in their
 * vocabulary's declaration order, and that order is load-bearing: {@code dist-tool}'s {@code
 * DrawioNotationCoverageTest} reads {@code Archimate.java} and {@code Uml.java} as text and asserts
 * each table's key sequence matches exactly. Neither is reordered to match a third-party palette's
 * grouping.
 *
 * <p><strong>Where the notation comes from.</strong> Not from draw.io's shape libraries, which
 * carry no ArchiMate relationship styles at all, but from dediren's own shipped render policies
 * ({@code fixtures/render-policy/archimate-svg.json} and {@code uml-svg.json}) — the same marker
 * and line-style decisions its SVG renderer already draws. The draw.io tokens below are the
 * functional spelling of those decisions: {@code block}/{@code endFill=0} for a hollow triangle,
 * {@code diamondThin} for a whole-part diamond, {@code open} for an open arrowhead, {@code oval}
 * for a ball, {@code dashPattern=1 3} for a dotted line. Sourcing both exports from one policy is
 * what keeps a `.drawio` file and the SVG dediren renders from the same model saying the same
 * thing.
 *
 * <p><strong>Direction is meaning, not decoration.</strong> Composition and aggregation are drawn
 * from the <em>source</em> end in both notations ({@code startArrow=}), because dediren models the
 * whole as the relationship source. A diamond moved to the other end states the opposite
 * containment, so these are the entries to be most careful with.
 *
 * <p><strong>No reverse (style → type) index.</strong> The fragment is not a unique key: the whole
 * UML dependency family — Dependency, Usage, Include, Extend, Deployment, Manifestation — is one
 * dashed open arrow, separated in UML only by stereotype keywords this export has no source data to
 * fabricate. Import resolves the relationship type from the {@code dedirenType} cell attribute.
 */
public final class DrawioEdgeStyles {

  /** Which vocabulary a relationship type name should be read against. */
  public enum Notation {
    ARCHIMATE,
    UML
  }

  /**
   * The routing prefix every edge keeps.
   *
   * <p>{@code edgeStyle=none} makes draw.io draw the polyline through the supplied waypoints
   * literally instead of recomputing an orthogonal route around them, which is what keeps the
   * exported route the one ELK produced. It leads every style so a notation fragment can never
   * displace it.
   */
  public static final String BASE = "edgeStyle=none;rounded=0;html=1;";

  private static final int ARROW_SIZE = 12;

  private static final String NO_ARROW = "endArrow=none;";
  private static final String DASHED = "dashed=1;";

  /** draw.io spells a dotted line as a dash pattern, so it needs {@code dashed=1} in front. */
  private static final String DOTTED = "dashed=1;dashPattern=1 3;";

  /** A plain directed line: what an unmapped relationship type falls back to. */
  private static final String FALLBACK = "endArrow=block;endFill=1;endSize=" + ARROW_SIZE + ";";

  private static final Map<String, String> ARCHIMATE_TABLE = buildArchimateTable();
  private static final Map<String, String> UML_TABLE = buildUmlTable();

  private DrawioEdgeStyles() {}

  /**
   * The full edge style for {@code relationshipType} read against {@code notation}, falling back to
   * a plain directed line for a type the vocabulary does not cover.
   */
  public static String styleFor(Notation notation, String relationshipType) {
    return BASE + tableFor(notation).getOrDefault(relationshipType, FALLBACK);
  }

  /**
   * Whether {@code notation}'s table covers {@code relationshipType} at all.
   *
   * <p>Separate from {@link #styleFor} for the same reason the shape tables separate the two: the
   * fallback is a usable line rather than an error signal, and the exporter needs to know whether
   * to say the notation was lost.
   */
  public static boolean isMapped(Notation notation, String relationshipType) {
    return tableFor(notation).containsKey(relationshipType);
  }

  private static Map<String, String> tableFor(Notation notation) {
    return notation == Notation.UML ? UML_TABLE : ARCHIMATE_TABLE;
  }

  private static Map<String, String> buildArchimateTable() {
    var archimate = new LinkedHashMap<String, String>();

    archimate.put("Composition", wholePart(true));
    archimate.put("Aggregation", wholePart(false));
    // The only relationship drawn at both ends: a ball at the assigned-from end, an arrow at the
    // assigned-to end.
    archimate.put(
        "Assignment", "startArrow=oval;startFill=1;startSize=" + ARROW_SIZE + ";" + filledArrow());
    archimate.put("Realization", hollowTriangle() + DOTTED);
    archimate.put("Specialization", hollowTriangle());
    archimate.put("Serving", openArrow());
    archimate.put("Access", openArrow() + DOTTED);
    archimate.put("Influence", openArrow() + DASHED);
    archimate.put("Flow", filledArrow() + DASHED);
    archimate.put("Triggering", filledArrow());
    archimate.put("Association", NO_ARROW);

    return Collections.unmodifiableMap(archimate);
  }

  private static Map<String, String> buildUmlTable() {
    var uml = new LinkedHashMap<String, String>();

    uml.put("Association", NO_ARROW);
    uml.put("Composition", wholePart(true));
    uml.put("Aggregation", wholePart(false));
    uml.put("Generalization", hollowTriangle());
    // Dashed where ArchiMate's realization is dotted: the two notations genuinely differ, which is
    // half the reason these are two tables rather than one.
    uml.put("Realization", hollowTriangle() + DASHED);
    uml.put("Dependency", openArrow() + DASHED);
    uml.put("ControlFlow", openArrow());
    uml.put("ObjectFlow", openArrow());
    // The synchronous call, which is the default message sort. A message's sort lives in its source
    // properties and changes the head and the line — a type-keyed table cannot express that, so
    // asynchronous, reply, create and delete messages all export as a synchronous call.
    uml.put("Message", filledArrow());
    uml.put("Transition", openArrow());
    uml.put("Include", openArrow() + DASHED);
    uml.put("Extend", openArrow() + DASHED);
    uml.put("Usage", openArrow() + DASHED);
    uml.put("Deployment", openArrow() + DASHED);
    uml.put("Manifestation", openArrow() + DASHED);
    uml.put("CommunicationPath", NO_ARROW);

    return Collections.unmodifiableMap(uml);
  }

  /** The whole-part diamond, always at the source end. Filled composes, hollow aggregates. */
  private static String wholePart(boolean filled) {
    return "startArrow=diamondThin;startFill="
        + (filled ? 1 : 0)
        + ";startSize="
        + ARROW_SIZE
        + ";endArrow=none;";
  }

  private static String hollowTriangle() {
    return "endArrow=block;endFill=0;endSize=" + ARROW_SIZE + ";";
  }

  private static String filledArrow() {
    return "endArrow=block;endFill=1;endSize=" + ARROW_SIZE + ";";
  }

  private static String openArrow() {
    return "endArrow=open;endFill=0;endSize=" + ARROW_SIZE + ";";
  }
}

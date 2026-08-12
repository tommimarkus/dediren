package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.contracts.render.SvgEdgeStyle;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The line style and arrowheads ArchiMate 3.2 draws for each relationship type (Appendix A.3, §5.6
 * Table 3, and the §5.2 prose that states the line styles in words).
 *
 * <p>This is notation, not presentation, so it belongs to the {@code archimate} profile rather than
 * to any one policy. Before it existed the entire table lived in the shipped policy fixture, and a
 * policy that overrode fewer types — the repo's own self-model policy overrides exactly one — fell
 * through to the generic house default of a solid line with a filled arrow, silently emitting
 * non-ArchiMate glyphs for every type it did not spell out.
 *
 * <p>Deliberately carries <em>only</em> line style and markers. Stroke, width and label colour stay
 * null so they keep resolving from the policy: a policy may restyle ArchiMate freely, but it should
 * have to opt out of the notation explicitly rather than by omission.
 */
public final class ArchimateEdgeNotation {

  private ArchimateEdgeNotation() {}

  private static final Map<String, SvgEdgeStyle> NOTATION = buildNotation();

  /**
   * The notation defaults for {@code relationshipType}, or {@code null} when the type is unknown —
   * an unknown type keeps the generic base style rather than being forced into a wrong glyph.
   */
  public static SvgEdgeStyle forRelationshipType(String relationshipType) {
    return relationshipType == null ? null : NOTATION.get(relationshipType);
  }

  private static Map<String, SvgEdgeStyle> buildNotation() {
    Map<String, SvgEdgeStyle> notation = new LinkedHashMap<>();
    // Structural (§5.1): a ball or diamond at the source end, no head at the target.
    notation.put("Composition", edge(null, SvgEdgeMarkerEnd.FILLED_DIAMOND, SvgEdgeMarkerEnd.NONE));
    notation.put("Aggregation", edge(null, SvgEdgeMarkerEnd.HOLLOW_DIAMOND, SvgEdgeMarkerEnd.NONE));
    notation.put(
        "Assignment", edge(null, SvgEdgeMarkerEnd.FILLED_CIRCLE, SvgEdgeMarkerEnd.FILLED_ARROW));
    notation.put(
        "Realization", edge(SvgEdgeLineStyle.DOTTED, null, SvgEdgeMarkerEnd.HOLLOW_TRIANGLE));
    // Dependency (§5.2): the three line styles are what separates these, per the §5.2 prose --
    // serving solid, access dotted, influence dashed.
    notation.put("Serving", edge(SvgEdgeLineStyle.SOLID, null, SvgEdgeMarkerEnd.OPEN_ARROW));
    notation.put("Access", edge(SvgEdgeLineStyle.DOTTED, null, SvgEdgeMarkerEnd.OPEN_ARROW));
    notation.put("Influence", edge(SvgEdgeLineStyle.DASHED, null, SvgEdgeMarkerEnd.OPEN_ARROW));
    notation.put("Association", edge(SvgEdgeLineStyle.SOLID, null, SvgEdgeMarkerEnd.NONE));
    // Dynamic (§5.3): both carry a filled head; only the line style tells them apart.
    notation.put("Triggering", edge(SvgEdgeLineStyle.SOLID, null, SvgEdgeMarkerEnd.FILLED_ARROW));
    notation.put("Flow", edge(SvgEdgeLineStyle.DASHED, null, SvgEdgeMarkerEnd.FILLED_ARROW));
    // Other (§5.4).
    notation.put(
        "Specialization", edge(SvgEdgeLineStyle.SOLID, null, SvgEdgeMarkerEnd.HOLLOW_TRIANGLE));
    return Map.copyOf(notation);
  }

  private static SvgEdgeStyle edge(
      SvgEdgeLineStyle lineStyle, SvgEdgeMarkerEnd markerStart, SvgEdgeMarkerEnd markerEnd) {
    return new SvgEdgeStyle(
        null,
        null,
        null,
        lineStyle,
        markerStart,
        markerEnd,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}

package dev.dediren.plugins.render.svg;

import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabel;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelCandidate;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelVisibleBox;
import static dev.dediren.plugins.render.svg.Svg.attr;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Renders UML association end adornments — the end multiplicities and role names — beside the
 * source and target ends of an edge route.
 *
 * <p>The adornment strings live in render-metadata edge {@code properties} (the model's {@code
 * properties.uml.*} carried through generic-graph projection): {@code source_multiplicity}, {@code
 * target_multiplicity}, {@code source_role}, {@code target_role}. Before this renderer they reached
 * render metadata but were never drawn, so the SVG was silently lossier than the model (issue #37).
 *
 * <p>Placement anchors to the ELK-computed route endpoints, exactly as {@link EdgeRenderer} already
 * anchors the centre edge label to the route. Because the layout result already carries the route
 * points, no layout-contract change is needed and no route geometry is invented here: multiplicity
 * and role sit a fixed step in from each endpoint and a fixed step off the line.
 */
public final class EdgeEndAdornments {

  private EdgeEndAdornments() {}

  /** Distance stepped inward from the endpoint along the route (clears the node border/marker). */
  private static final double ADORNMENT_ALONG = 16.0;

  /** Distance stepped off the route line, perpendicular to it (keeps text off the stroke). */
  private static final double ADORNMENT_PERPENDICULAR = 12.0;

  /** Baseline nudge that vertically centres the glyph box on the anchor point. */
  private static final double BASELINE_CENTERING = 0.34;

  /** A placed end adornment: its machine-detectable kind, which end it sits at, and its label. */
  public record Adornment(String kind, String end, EdgeLabel label, String text) {}

  /**
   * Computes the end adornments carried by {@code selector} for {@code edge}, laid out against the
   * route endpoints. Returns an empty list when there is no metadata, no adornment property, or too
   * few route points to determine an end direction.
   */
  public static List<Adornment> adornments(
      LaidOutEdge edge, RenderMetadataSelector selector, double fontSize) {
    if (selector == null || selector.properties() == null || edge.points().size() < 2) {
      return List.of();
    }
    JsonNode properties = selector.properties();
    String sourceMultiplicity = textProperty(properties, "source_multiplicity");
    String targetMultiplicity = textProperty(properties, "target_multiplicity");
    String sourceRole = textProperty(properties, "source_role");
    String targetRole = textProperty(properties, "target_role");
    if (sourceMultiplicity == null
        && targetMultiplicity == null
        && sourceRole == null
        && targetRole == null) {
      return List.of();
    }
    List<Adornment> adornments = new ArrayList<>();
    EndDirection source = endDirection(edge.points(), true);
    EndDirection target = endDirection(edge.points(), false);
    if (source != null) {
      addAdornment(
          adornments, "source_multiplicity", "source", source, 1, sourceMultiplicity, fontSize);
      addAdornment(adornments, "source_role", "source", source, -1, sourceRole, fontSize);
    }
    if (target != null) {
      addAdornment(
          adornments, "target_multiplicity", "target", target, 1, targetMultiplicity, fontSize);
      addAdornment(adornments, "target_role", "target", target, -1, targetRole, fontSize);
    }
    return adornments;
  }

  /** Serialises the adornments to SVG, each wrapped in a machine-detectable {@code <g>}. */
  public static String markup(
      List<Adornment> adornments, ResolvedEdgeStyle style, String backgroundFill, double fontSize) {
    if (adornments.isEmpty()) {
      return "";
    }
    StringBuilder output = new StringBuilder();
    for (Adornment adornment : adornments) {
      output
          .append("<g data-dediren-edge-adornment=\"")
          .append(attr(adornment.kind()))
          .append("\" data-dediren-edge-adornment-end=\"")
          .append(attr(adornment.end()))
          .append("\">")
          .append(edgeLabel(adornment.label(), adornment.text(), style, backgroundFill, fontSize))
          .append("</g>");
    }
    return output.toString();
  }

  /** The rendered bounding box of an adornment, for layout-bounds and overlap bookkeeping. */
  public static LabelBox visibleBox(Adornment adornment, ResolvedEdgeStyle style) {
    return edgeLabelVisibleBox(adornment.label(), style.labelPresentation());
  }

  private static void addAdornment(
      List<Adornment> adornments,
      String kind,
      String end,
      EndDirection direction,
      int side,
      String text,
      double fontSize) {
    if (text == null) {
      return;
    }
    double perpendicularX = -direction.dy() * side;
    double perpendicularY = direction.dx() * side;
    double anchorX =
        direction.x() + direction.dx() * ADORNMENT_ALONG + perpendicularX * ADORNMENT_PERPENDICULAR;
    double anchorY =
        direction.y() + direction.dy() * ADORNMENT_ALONG + perpendicularY * ADORNMENT_PERPENDICULAR;
    String textAnchor;
    if (Math.abs(direction.dx()) >= Math.abs(direction.dy())) {
      // Roughly horizontal route: the offset is vertical, so centre the text over the anchor.
      textAnchor = "middle";
    } else {
      // Roughly vertical route: the offset is horizontal, so push the text away from the line.
      textAnchor = perpendicularX >= 0 ? "start" : "end";
    }
    double baseline = anchorY + fontSize * BASELINE_CENTERING;
    adornments.add(
        new Adornment(
            kind, end, edgeLabelCandidate(anchorX, baseline, textAnchor, text, fontSize), text));
  }

  private static EndDirection endDirection(List<Point> points, boolean fromSource) {
    if (fromSource) {
      Point endpoint = points.getFirst();
      for (int index = 1; index < points.size(); index++) {
        EndDirection direction = direction(endpoint, points.get(index));
        if (direction != null) {
          return direction;
        }
      }
      return null;
    }
    Point endpoint = points.getLast();
    for (int index = points.size() - 2; index >= 0; index--) {
      EndDirection direction = direction(endpoint, points.get(index));
      if (direction != null) {
        return direction;
      }
    }
    return null;
  }

  private static EndDirection direction(Point endpoint, Point toward) {
    double dx = toward.x() - endpoint.x();
    double dy = toward.y() - endpoint.y();
    double length = Math.hypot(dx, dy);
    if (length < 1.0e-6) {
      return null;
    }
    return new EndDirection(endpoint.x(), endpoint.y(), dx / length, dy / length);
  }

  private static String textProperty(JsonNode properties, String key) {
    JsonNode value = properties.get(key);
    if (value == null || !value.isTextual()) {
      return null;
    }
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }

  /** An endpoint and the unit vector pointing from it back along the route. */
  private record EndDirection(double x, double y, double dx, double dy) {}
}

package dev.dediren.plugins.render.node.generic;

import static dev.dediren.plugins.render.svg.Svg.f1;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.SvgNodeShape;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.Locale;

/**
 * Free-form node geometry for generic (decorator-less) nodes. The render policy selects a {@link
 * SvgNodeShape}; this builder emits the matching SVG element, each carrying {@code
 * data-dediren-node-shape="<name>"} and the resolved {@code fill}/{@code stroke}/{@code
 * stroke-width}. Mirrors the per-notation shape builders (e.g. {@code ArchimateShapes}); reached
 * only from {@code SvgDocument.nodeShape} when a node has no ArchiMate/UML decorator.
 */
public final class GenericShapes {

  private GenericShapes() {}

  public static void genericNodeShape(SvgWriter w, LaidOutNode node, ResolvedNodeStyle style) {
    SvgNodeShape shape = style.shape();
    switch (shape) {
      case RECTANGLE -> rect(w, node, style, shape, 0.0);
      case ROUNDED_RECTANGLE -> rect(w, node, style, shape, style.rx());
      case STADIUM -> rect(w, node, style, shape, Math.min(node.width(), node.height()) / 2.0);
      case ELLIPSE -> ellipse(w, node, style, shape);
      case CIRCLE -> circle(w, node, style, shape);
      case DIAMOND -> diamond(w, node, style, shape);
      case HEXAGON -> hexagon(w, node, style, shape);
      case PARALLELOGRAM -> parallelogram(w, node, style, shape);
      case TRIANGLE -> triangle(w, node, style, shape);
      case CYLINDER -> cylinder(w, node, style, shape);
    }
  }

  private static String name(SvgNodeShape shape) {
    return shape.name().toLowerCase(Locale.ROOT);
  }

  private static void rect(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape, double rx) {
    w.empty("rect")
        .attr("data-dediren-node-shape", name(shape))
        .attr("x", f1(node.x()))
        .attr("y", f1(node.y()))
        .attr("width", f1(node.width()))
        .attr("height", f1(node.height()))
        .attr("rx", styleNumber(rx))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  private static void ellipse(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    w.empty("ellipse")
        .attr("data-dediren-node-shape", name(shape))
        .attr("cx", f1(node.x() + node.width() / 2.0))
        .attr("cy", f1(node.y() + node.height() / 2.0))
        .attr("rx", f1(node.width() / 2.0))
        .attr("ry", f1(node.height() / 2.0))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  private static void circle(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    w.empty("circle")
        .attr("data-dediren-node-shape", name(shape))
        .attr("cx", f1(node.x() + node.width() / 2.0))
        .attr("cy", f1(node.y() + node.height() / 2.0))
        .attr("r", f1(Math.min(node.width(), node.height()) / 2.0))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  private static void diamond(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double cx = node.x() + node.width() / 2.0;
    double cy = node.y() + node.height() / 2.0;
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            cx,
            node.y(),
            node.x() + node.width(),
            cy,
            cx,
            node.y() + node.height(),
            node.x(),
            cy);
    polygon(w, shape, points, style);
  }

  private static void hexagon(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double cy = node.y() + node.height() / 2.0;
    double inset = Math.min(node.width() * 0.25, node.height() / 2.0);
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            node.x() + inset,
            node.y(),
            node.x() + node.width() - inset,
            node.y(),
            node.x() + node.width(),
            cy,
            node.x() + node.width() - inset,
            node.y() + node.height(),
            node.x() + inset,
            node.y() + node.height(),
            node.x(),
            cy);
    polygon(w, shape, points, style);
  }

  private static void parallelogram(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double slant = Math.min(node.width() * 0.25, node.height());
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            node.x() + slant,
            node.y(),
            node.x() + node.width(),
            node.y(),
            node.x() + node.width() - slant,
            node.y() + node.height(),
            node.x(),
            node.y() + node.height());
    polygon(w, shape, points, style);
  }

  private static void triangle(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f",
            node.x() + node.width() / 2.0,
            node.y(),
            node.x() + node.width(),
            node.y() + node.height(),
            node.x(),
            node.y() + node.height());
    polygon(w, shape, points, style);
  }

  private static void polygon(
      SvgWriter w, SvgNodeShape shape, String points, ResolvedNodeStyle style) {
    w.empty("polygon")
        .attr("data-dediren-node-shape", name(shape))
        .attr("points", points)
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  private static void cylinder(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double rx = node.width() / 2.0;
    double ry = Math.min(node.height() * 0.16, node.width() / 2.0);
    double top = node.y() + ry;
    double bottom = node.y() + node.height() - ry;
    // Body (both sides + front bottom curve) then the visible top-cap front curve, as one path so
    // the shape stays a single element carrying the data attribute.
    String d =
        String.format(
            Locale.ROOT,
            "M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f L %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f Z M %.1f"
                + " %.1f A %.1f %.1f 0 0 1 %.1f %.1f",
            node.x(),
            top,
            rx,
            ry,
            node.x() + node.width(),
            top,
            node.x() + node.width(),
            bottom,
            rx,
            ry,
            node.x(),
            bottom,
            node.x(),
            top,
            rx,
            ry,
            node.x() + node.width(),
            top);
    w.empty("path")
        .attr("data-dediren-node-shape", name(shape))
        .attr("d", d)
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }
}

package dev.dediren.plugins.render.node;

import static dev.dediren.plugins.render.node.NodeShapeSupport.ARCHIMATE_ICON_SIZE;
import static dev.dediren.plugins.render.node.NodeShapeSupport.ARCHIMATE_ICON_TOP_INSET;
import static dev.dediren.plugins.render.node.NodeShapeSupport.archimateJunctionLabelOutside;
import static dev.dediren.plugins.render.node.NodeShapeSupport.archimateJunctionRadius;
import static dev.dediren.plugins.render.node.NodeShapeSupport.hasArchimateCornerIcon;
import static dev.dediren.plugins.render.node.NodeShapeSupport.umlCompactControlNodeLabelOutside;
import static dev.dediren.plugins.render.svg.Svg.estimateTextWidth;
import static dev.dediren.plugins.render.svg.Svg.labelNumber;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.SvgLabelAlign;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NodeLabels {

  private NodeLabels() {}

  // Must equal ARCHIMATE_LABEL_ICON_RESERVE in engines/generic-graph
  // GenericGraphLayoutSizing: per-side room reserved so a centered label clears
  // the upper-right type icon. Enforced by dist-tool ArchimateLabelReserveConsistencyTest.
  private static final double ARCHIMATE_LABEL_ICON_RESERVE = 34.0;
  private static final double NODE_LABEL_VERTICAL_PADDING = 8.0;
  private static final double NODE_LABEL_MIN_FONT_SIZE = 9.0;
  private static final double LABEL_ALIGN_INSET = 8.0;

  public static void nodeLabel(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    NodeLabelLines label = nodeLabelLinesAndSize(node, style, fontSize);
    NodeLabelPosition position =
        nodeLabelPosition(node, style, label.fontSize(), label.lines().size());
    // label_align only applies to plain centered labels; junction and UML compact-control labels
    // sit at their own outside position and keep their middle anchor.
    boolean plainCentered =
        !archimateJunctionLabelOutside(style.decorator())
            && !umlCompactControlNodeLabelOutside(style.decorator());
    double textX = position.x();
    String anchor = "middle";
    if (plainCentered && style.labelAlign() == SvgLabelAlign.START) {
      anchor = "start";
      textX = node.x() + LABEL_ALIGN_INSET;
    } else if (plainCentered && style.labelAlign() == SvgLabelAlign.END) {
      anchor = "end";
      textX = node.x() + node.width() - LABEL_ALIGN_INSET;
    }
    w.start("text")
        .attr("x", f1(textX))
        .attr("y", f1(position.y()))
        .attr("text-anchor", anchor)
        .attrIf("dominant-baseline", position.centerBaseline() ? "middle" : null)
        .attr("fill", style.labelFill())
        .attr("font-size", labelNumber(label.fontSize()))
        .attrIf("font-family", style.fontFamily())
        .attrIf("font-weight", enumValue(style.fontWeight()))
        .attrIf("font-style", enumValue(style.fontStyle()))
        .attrIf("fill-opacity", opacity(style.labelOpacity()));
    if (label.lines().size() == 1) {
      emitLengthAttributes(w, label.lines().get(0), label.fontSize());
      w.text(label.lines().get(0)).end();
      return;
    }
    for (int index = 0; index < label.lines().size(); index++) {
      String dy = index == 0 ? "0" : labelNumber(nodeLabelLineHeight(label.fontSize()));
      w.start("tspan").attr("x", f1(textX)).attr("dy", dy);
      emitLengthAttributes(w, label.lines().get(index), label.fontSize());
      w.text(label.lines().get(index)).end();
    }
    w.end();
  }

  // Pin each rendered label line to the layout metric (estimateTextWidth) via
  // textLength + lengthAdjust, so the displayed width — and thus the label's
  // clearance from the corner decorator and node border — is independent of the
  // viewer's installed font rather than the layout's internal metric. See issue #25.
  //
  // lengthAdjust="spacing" adjusts only inter-glyph spacing to hit textLength, never
  // the glyph shapes themselves. That is safe here because estimateTextWidth now
  // approximates the natural per-glyph width (Svg per-glyph advance table), so the
  // spacing correction is small — unlike the flat 0.62em/char metric it replaced,
  // whose ~47% over-estimate on narrow-glyph labels forced spacingAndGlyphs to
  // stretch the letterforms into visibly deformed type. See issue #39.
  private static void emitLengthAttributes(SvgWriter w, String line, double fontSize) {
    double width = estimateTextWidth(line, fontSize);
    if (width > 0.0) {
      w.attr("textLength", labelNumber(width)).attr("lengthAdjust", "spacing");
    }
  }

  public static NodeLabelLines nodeLabelLinesAndSize(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    List<String> lines = wrappedNodeLabelLines(node, style, fontSize);
    double maxWidth = nodeLabelMaxWidth(node, style, fontSize);
    double widestLine =
        lines.stream().mapToDouble(line -> estimateTextWidth(line, fontSize)).max().orElse(0.0);
    double widthFontSize = widestLine > maxWidth ? fontSize * maxWidth / widestLine : fontSize;
    double availableHeight =
        node.height() - NODE_LABEL_VERTICAL_PADDING - archimateLabelTopReserve(style);
    double blockHeight = lines.size() * nodeLabelLineHeight(fontSize);
    double heightFontSize =
        blockHeight > availableHeight ? fontSize * availableHeight / blockHeight : fontSize;
    double labelFontSize =
        Math.max(Math.min(widthFontSize, heightFontSize), NODE_LABEL_MIN_FONT_SIZE);
    return new NodeLabelLines(lines, labelFontSize);
  }

  public static List<String> wrappedNodeLabelLines(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    // Reached only via shouldRenderPlainNodeLabel, which already requires a non-null, non-empty
    // label; the model schema also makes node label required. So node.label() is non-null here.
    String rawLabel = node.label();
    double maxWidth = nodeLabelMaxWidth(node, style, fontSize);
    List<String> tokens = labelWrapTokens(rawLabel);
    List<String> lines = new ArrayList<>();
    String current = "";
    for (String token : tokens) {
      String candidate = current.isEmpty() ? token : current + " " + token;
      if (estimateTextWidth(candidate, fontSize) <= maxWidth) {
        current = candidate;
        continue;
      }
      if (!current.isEmpty()) {
        lines.add(current);
      }
      current = token;
    }
    if (!current.isEmpty()) {
      lines.add(current);
    }
    return lines.isEmpty() ? List.of(rawLabel) : lines;
  }

  public static List<String> labelWrapTokens(String label) {
    List<String> tokens = new ArrayList<>();
    for (String token : label.split("\\s+")) {
      if (!token.isEmpty()) {
        tokens.addAll(splitCamelToken(token));
      }
    }
    return tokens;
  }

  public static List<String> splitCamelToken(String token) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int[] codePoints = token.codePoints().toArray();
    for (int index = 0; index < codePoints.length; index++) {
      boolean previousIsLowercase = index > 0 && Character.isLowerCase(codePoints[index - 1]);
      boolean nextIsLowercase =
          index + 1 < codePoints.length && Character.isLowerCase(codePoints[index + 1]);
      if (previousIsLowercase
          && Character.isUpperCase(codePoints[index])
          && nextIsLowercase
          && !current.isEmpty()) {
        parts.add(current.toString());
        current.setLength(0);
      }
      current.appendCodePoint(codePoints[index]);
    }
    if (!current.isEmpty()) {
      parts.add(current.toString());
    }
    return parts;
  }

  public static NodeLabelPosition nodeLabelPosition(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize, int lineCount) {
    if (archimateJunctionLabelOutside(style.decorator())) {
      // Top-aligned below the circle: the first line's baseline sits `gap` below the
      // circle's southmost point and any wrapped lines grow downward. Junctions carry
      // empty labels in practice, so block-centering (as in the UML branch below) is
      // unnecessary here.
      double radius = archimateJunctionRadius(node, style);
      double gap = 6.0;
      double firstLineY = node.y() + node.height() / 2.0 + radius + gap + fontSize;
      return new NodeLabelPosition(node.x() + node.width() / 2.0, firstLineY, false);
    }
    if (umlCompactControlNodeLabelOutside(style.decorator())) {
      double lineHeight = nodeLabelLineHeight(fontSize);
      double lineSpan = Math.max(0, lineCount - 1) * lineHeight;
      double diagonalGap = 8.0;
      double diagonalDelta = Math.min(node.width(), node.height()) / 2.0 + diagonalGap;
      double labelCenterY = node.y() + node.height() / 2.0 - diagonalDelta;
      double labelCenterX = node.x() + node.width() / 2.0 - diagonalDelta;
      double firstLineY = labelCenterY - (lineSpan - fontSize * 0.6) / 2.0;
      return new NodeLabelPosition(labelCenterX, firstLineY, false);
    }
    return new NodeLabelPosition(
        node.x() + node.width() / 2.0, nodeLabelFirstLineY(node, style, fontSize, lineCount), true);
  }

  public static double nodeLabelMaxWidth(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    double reserve =
        hasArchimateCornerIcon(style.decorator()) ? 2.0 * ARCHIMATE_LABEL_ICON_RESERVE : 20.0;
    return Math.max(node.width() - reserve, fontSize * 3.0);
  }

  public static double nodeLabelLineHeight(double fontSize) {
    return fontSize * 1.15;
  }

  // Center the label block in the box area below the corner-decorator's reserved band
  // (the icon's top inset + glyph box) so a multi-line label's top line never lands at
  // the decorator's vertical level — the vertical complement of the horizontal
  // textLength gutter shipped for #25. Nodes without a corner icon reserve nothing and
  // stay centered over the full box height. See issue #27.
  public static double nodeLabelFirstLineY(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize, int lineCount) {
    double topReserve = archimateLabelTopReserve(style);
    double areaCenterY = node.y() + topReserve + (node.height() - topReserve) / 2.0;
    return areaCenterY - (Math.max(0, lineCount - 1) * nodeLabelLineHeight(fontSize)) / 2.0;
  }

  public static double archimateLabelTopReserve(ResolvedNodeStyle style) {
    return hasArchimateCornerIcon(style.decorator())
        ? ARCHIMATE_ICON_TOP_INSET + ARCHIMATE_ICON_SIZE
        : 0.0;
  }

  public static List<LabelBox> nodeLabelBoxes(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    NodeLabelLines label = nodeLabelLinesAndSize(node, style, fontSize);
    NodeLabelPosition position =
        nodeLabelPosition(node, style, label.fontSize(), label.lines().size());
    List<LabelBox> boxes = new ArrayList<>();
    double lineHeight = nodeLabelLineHeight(label.fontSize());
    for (int index = 0; index < label.lines().size(); index++) {
      double y = position.y() + index * lineHeight;
      boxes.add(nodeLabelBox(position.x(), y, label.lines().get(index), label.fontSize()));
    }
    return boxes;
  }

  public static LabelBox nodeLabelBox(double x, double y, String text, double fontSize) {
    double width = estimateTextWidth(text, fontSize);
    double minX = x - width / 2.0;
    double minY = y - fontSize;
    return new LabelBox(minX, minY, minX + width, y + fontSize * 0.25);
  }

  private static String f1(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static String opacity(Double value) {
    return value == null ? null : styleNumber(value);
  }

  private static String enumValue(Enum<?> value) {
    return value == null ? null : value.name().toLowerCase(Locale.ROOT);
  }
}

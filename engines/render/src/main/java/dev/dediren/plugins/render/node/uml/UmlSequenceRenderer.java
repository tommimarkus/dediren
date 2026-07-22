package dev.dediren.plugins.render.node.uml;

import static dev.dediren.plugins.render.svg.Svg.dashArrayValue;
import static dev.dediren.plugins.render.svg.Svg.f1;
import static dev.dediren.plugins.render.svg.Svg.opacity;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import dev.dediren.plugins.render.svg.EdgeMarkers;
import dev.dediren.plugins.render.svg.Svg;
import dev.dediren.plugins.render.svg.SvgAccessibleName;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class UmlSequenceRenderer {
  private static final String DASH_PATTERN = "8 5";
  private static final double INTERACTION_HORIZONTAL_PADDING = 48.0;
  private static final double INTERACTION_TOP_PADDING = 40.0;
  private static final double INTERACTION_BOTTOM_PADDING = 48.0;
  private static final double FRAGMENT_HORIZONTAL_PADDING = 20.0;
  private static final double FRAGMENT_VERTICAL_PADDING = 42.0;
  private static final double FRAGMENT_HEADER_HEIGHT = 24.0;

  private final LayoutResult result;
  private final RenderMetadata metadata;
  private final RenderPolicy policy;
  private final ResolvedStyle base;
  private final UmlSequenceModel model;
  private final Map<String, LaidOutNode> nodesById;
  private final Map<String, LaidOutEdge> edgesById;
  private final Map<String, SequenceFrame> interactionFrames;
  private final Map<String, UmlSequenceModel.SequenceCombinedFragment> combinedFragmentsById;
  private final Map<String, List<UmlSequenceModel.SequenceOperand>> operandsByFragmentId;
  private final Map<String, SequenceFrame> combinedFragmentFrames;

  public UmlSequenceRenderer(LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
    this.result = result;
    this.metadata = metadata;
    this.policy = policy;
    this.base = StyleResolver.sequenceBaseStyle(policy);
    this.model = UmlSequenceModel.from(result, metadata);
    this.nodesById = nodesById(result.nodes());
    this.edgesById = edgesById(result.edges());
    this.interactionFrames = interactionFrames();
    this.combinedFragmentsById = combinedFragmentsById();
    this.operandsByFragmentId = operandsByFragmentId();
    this.combinedFragmentFrames = combinedFragmentFrames();
  }

  public static boolean isSequence(RenderMetadata metadata) {
    if (metadata == null || !"uml".equals(metadata.semanticProfile())) {
      return false;
    }
    boolean hasLifeline =
        metadata.nodes().values().stream().anyMatch(selector -> "Lifeline".equals(selector.type()));
    boolean hasMessage =
        metadata.edges().values().stream().anyMatch(selector -> "Message".equals(selector.type()));
    return hasLifeline || hasMessage;
  }

  public String render() {
    SvgBox bounds = bounds().padded(policy);
    SvgWriter w = new SvgWriter();
    w.start("svg")
        .attr("xmlns", "http://www.w3.org/2000/svg")
        .attr("role", "img")
        .attr("width", String.format(Locale.ROOT, "%.0f", bounds.width()))
        .attr("height", String.format(Locale.ROOT, "%.0f", bounds.height()))
        .attr(
            "viewBox",
            String.format(
                Locale.ROOT,
                "%.1f %.1f %.1f %.1f",
                bounds.minX(),
                bounds.minY(),
                bounds.width(),
                bounds.height()));
    SvgAccessibleName.markup(w, policy, result.viewId());
    w.empty("rect")
        .attr("x", String.format(Locale.ROOT, "%.1f", bounds.minX()))
        .attr("y", String.format(Locale.ROOT, "%.1f", bounds.minY()))
        .attr("width", String.format(Locale.ROOT, "%.1f", bounds.width()))
        .attr("height", String.format(Locale.ROOT, "%.1f", bounds.height()))
        .attr("fill", base.backgroundFill());
    w.start("g")
        .attr("font-family", base.fontFamily())
        .attr("font-size", styleNumber(base.fontSize()));

    renderInteractions(w);
    renderCombinedFragments(w);
    renderLifelineHeads(w);
    renderLifelineStems(w);
    renderExecutions(w);
    renderGates(w);
    renderMessages(w);
    renderDeleteMarkers(w);

    w.end();
    w.end();
    return w.finish() + "\n";
  }

  // Emits a box shape's optional fill/stroke opacity and dash (line_style preset only), matching
  // the former private NodePaint's boxAttrs() string. Called right after the shape's fixed
  // the element's start tag is still open.
  private static void boxAttrs(SvgWriter w, ResolvedNodeStyle paint) {
    w.attrIf("fill-opacity", opacity(paint.fillOpacity()))
        .attrIf("stroke-opacity", opacity(paint.strokeOpacity()))
        .attrIf("stroke-dasharray", boxDash(paint));
  }

  private static String boxDash(ResolvedNodeStyle paint) {
    String value = dashArrayValue(paint.lineStyle(), null, "6 4");
    return value.isEmpty() ? null : value;
  }

  private void renderInteractions(SvgWriter w) {
    for (UmlSequenceModel.SequenceNode interaction : model.interactions()) {
      LaidOutNode node = interaction.node();
      SequenceFrame frame = interactionFrame(interaction);
      ResolvedNodeStyle paint = nodePaint(node.id());
      double titleWidth =
          Math.max(
              96.0,
              Math.min(frame.width() * 0.5, labelWidth(node.label(), base.fontSize()) + 24.0));
      double titleHeight = Math.max(24.0, base.fontSize() + 10.0);
      w.start("g")
          .attr("data-dediren-node-id", node.id())
          .attr("data-dediren-node-type", "Interaction")
          .attr("data-dediren-sequence-interaction", "true");
      w.empty("rect")
          .attr("data-dediren-node-shape", "uml_interaction")
          .attr("x", f1(frame.x()))
          .attr("y", f1(frame.y()))
          .attr("width", f1(frame.width()))
          .attr("height", f1(frame.height()))
          .attr("rx", styleNumber(paint.rx()))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.empty("path")
          .attr("data-dediren-sequence-interaction-title", "true")
          .attr(
              "d",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z",
                  frame.x(),
                  frame.y(),
                  frame.x() + titleWidth,
                  frame.x() + titleWidth - 12.0,
                  frame.y() + titleHeight,
                  frame.y() + titleHeight,
                  frame.x()))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.start("text")
          .attr("x", f1(frame.x() + 10.0))
          .attr("y", f1(frame.y() + titleHeight - 8.0))
          .attr("fill", paint.labelFill())
          .attrIf("fill-opacity", opacity(paint.labelOpacity()))
          .text(node.label())
          .end();
      w.end();
    }
  }

  private void renderCombinedFragments(SvgWriter w) {
    List<UmlSequenceModel.SequenceCombinedFragment> fragments =
        model.combinedFragments().stream()
            .filter(fragment -> combinedFragmentFrames.containsKey(fragment.id()))
            .sorted(
                Comparator.<UmlSequenceModel.SequenceCombinedFragment>comparingInt(
                        this::combinedFragmentDepth)
                    .reversed()
                    .thenComparingDouble(fragment -> combinedFragmentFrames.get(fragment.id()).y())
                    .thenComparingDouble(fragment -> combinedFragmentFrames.get(fragment.id()).x())
                    .thenComparing(UmlSequenceModel.SequenceCombinedFragment::id))
            .toList();
    for (UmlSequenceModel.SequenceCombinedFragment fragment : fragments) {
      SequenceFrame frame = combinedFragmentFrames.get(fragment.id());
      ResolvedNodeStyle paint = nodePaint(fragment.id());
      double tabWidth =
          Math.max(
              44.0,
              Math.min(
                  frame.width() * 0.5, labelWidth(fragment.operator(), base.fontSize()) + 24.0));
      w.start("g")
          .attr("data-dediren-sequence-combined-fragment", fragment.id())
          .attr("data-dediren-sequence-interaction-operator", fragment.operator());
      w.empty("rect")
          .attr("data-dediren-node-shape", "uml_combined_fragment")
          .attr("x", f1(frame.x()))
          .attr("y", f1(frame.y()))
          .attr("width", f1(frame.width()))
          .attr("height", f1(frame.height()))
          .attr("rx", styleNumber(paint.rx()))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.empty("path")
          .attr("data-dediren-sequence-fragment-operator-tab", "true")
          .attr(
              "d",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z",
                  frame.x(),
                  frame.y(),
                  frame.x() + tabWidth,
                  frame.x() + tabWidth - 10.0,
                  frame.y() + FRAGMENT_HEADER_HEIGHT,
                  frame.y() + FRAGMENT_HEADER_HEIGHT,
                  frame.x()))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.start("text")
          .attr("data-dediren-sequence-fragment-operator", fragment.id())
          .attr("x", f1(frame.x() + 10.0))
          .attr("y", f1(frame.y() + FRAGMENT_HEADER_HEIGHT - 7.0))
          .attr("fill", paint.labelFill())
          .attr("font-weight", "600")
          .attrIf("fill-opacity", opacity(paint.labelOpacity()))
          .text(fragment.operator())
          .end();
      renderOperandSeparatorsAndGuards(w, fragment, frame, paint);
      w.end();
    }
  }

  private void renderLifelineHeads(SvgWriter w) {
    for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
      LaidOutNode node = lifeline.node();
      ResolvedNodeStyle paint = nodePaint(node.id());
      w.start("g")
          .attr("data-dediren-node-id", node.id())
          .attr("data-dediren-node-type", "Lifeline")
          .attr("data-dediren-sequence-lifeline", "true");
      w.empty("rect")
          .attr("data-dediren-node-shape", "uml_lifeline")
          .attr("x", f1(node.x()))
          .attr("y", f1(node.y()))
          .attr("width", f1(node.width()))
          .attr("height", f1(node.height()))
          .attr("rx", styleNumber(Math.max(paint.rx(), 2.0)))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.start("g").attr("data-dediren-node-decorator", "uml_lifeline").end();
      w.start("text")
          .attr("x", f1(node.x() + node.width() / 2.0))
          .attr("y", f1(node.y() + node.height() / 2.0 + base.fontSize() / 3.0))
          .attr("text-anchor", "middle")
          .attr("fill", paint.labelFill())
          .attrIf("fill-opacity", opacity(paint.labelOpacity()))
          .text(node.label())
          .end();
      w.end();
    }
  }

  private void renderLifelineStems(SvgWriter w) {
    for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
      LaidOutNode node = lifeline.node();
      ResolvedNodeStyle paint = nodePaint(node.id());
      double x = node.x() + node.width() / 2.0;
      double bottom = stemBottom(lifeline);
      w.empty("line")
          .attr("data-dediren-sequence-lifeline-stem", node.id())
          .attr("x1", f1(x))
          .attr("y1", f1(node.y() + node.height()))
          .attr("x2", f1(x))
          .attr("y2", f1(bottom))
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()))
          .attr("stroke-dasharray", DASH_PATTERN);
    }
  }

  private void renderExecutions(SvgWriter w) {
    for (UmlSequenceModel.SequenceNode execution : model.executions()) {
      LaidOutNode node = execution.node();
      ResolvedNodeStyle paint = nodePaint(node.id());
      w.start("g")
          .attr("data-dediren-node-id", node.id())
          .attr("data-dediren-node-type", "ExecutionSpecification");
      w.empty("rect")
          .attr("data-dediren-node-shape", "uml_execution_specification")
          .attr("x", f1(node.x()))
          .attr("y", f1(node.y()))
          .attr("width", f1(node.width()))
          .attr("height", f1(node.height()))
          .attr("rx", styleNumber(Math.max(0.0, paint.rx())))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.start("g").attr("data-dediren-node-decorator", "uml_execution_specification").end();
      w.end();
    }
  }

  private void renderGates(SvgWriter w) {
    for (UmlSequenceModel.SequenceNode gate : model.gates()) {
      LaidOutNode node = gate.node();
      ResolvedNodeStyle paint = nodePaint(node.id());
      double radius = Math.max(4.0, Math.min(node.width(), node.height()) / 2.0);
      w.start("g").attr("data-dediren-node-id", node.id()).attr("data-dediren-node-type", "Gate");
      w.empty("circle")
          .attr("data-dediren-node-shape", "uml_gate")
          .attr("cx", f1(node.x() + node.width() / 2.0))
          .attr("cy", f1(node.y() + node.height() / 2.0))
          .attr("r", f1(radius))
          .attr("fill", paint.fill())
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
      boxAttrs(w, paint);
      w.start("g").attr("data-dediren-node-decorator", "uml_gate").end();
      w.end();
    }
  }

  private void renderMessages(SvgWriter w) {
    for (UmlSequenceModel.SequenceMessage message : model.messages()) {
      ResolvedEdgeStyle paint = edgePaint(message.edge().id());
      MessageAppearance appearance = MessageAppearance.from(message.messageSort(), paint);
      w.start("g")
          .attr("data-dediren-edge-id", message.edge().id())
          .attr("data-dediren-sequence-message-sort", message.messageSort());
      edgeMarker(w, message.edge(), appearance.markerEnd(), appearance.stroke(), "end");
      edgePath(w, message.edge(), appearance);
      if (message.edge().label() != null && !message.edge().label().isEmpty()) {
        messageLabel(w, message.edge(), appearance);
      }
      w.end();
    }
  }

  private void renderDeleteMarkers(SvgWriter w) {
    for (UmlSequenceModel.SequenceMessage message : model.messages()) {
      if (!"deleteMessage".equals(message.messageSort())) {
        continue;
      }
      MarkerPoint point = deleteMarkerPoint(message.edge());
      ResolvedEdgeStyle paint = edgePaint(message.edge().id());
      double size = point.size();
      w.start("g").attr("data-dediren-sequence-delete-marker", point.id());
      w.empty("line")
          .attr("x1", f1(point.x() - size))
          .attr("y1", f1(point.y() - size))
          .attr("x2", f1(point.x() + size))
          .attr("y2", f1(point.y() + size))
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(Math.max(1.5, paint.strokeWidth())));
      w.empty("line")
          .attr("x1", f1(point.x() - size))
          .attr("y1", f1(point.y() + size))
          .attr("x2", f1(point.x() + size))
          .attr("y2", f1(point.y() - size))
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(Math.max(1.5, paint.strokeWidth())));
      w.end();
    }
  }

  private void renderOperandSeparatorsAndGuards(
      SvgWriter w,
      UmlSequenceModel.SequenceCombinedFragment fragment,
      SequenceFrame frame,
      ResolvedNodeStyle paint) {
    List<UmlSequenceModel.SequenceOperand> operands = operandsFor(fragment);
    Map<String, SvgBox> operandBoxes = new HashMap<>();
    Map<String, Double> separators = new HashMap<>();
    for (UmlSequenceModel.SequenceOperand operand : operands) {
      operandBoxes.put(operand.id(), operandContentBox(operand, new HashMap<>(), new HashSet<>()));
    }
    for (int index = 1; index < operands.size(); index++) {
      UmlSequenceModel.SequenceOperand previous = operands.get(index - 1);
      UmlSequenceModel.SequenceOperand current = operands.get(index);
      double y = separatorY(frame, operandBoxes.get(previous.id()), operandBoxes.get(current.id()));
      separators.put(current.id(), y);
      w.empty("line")
          .attr("data-dediren-sequence-operand-separator", current.id())
          .attr("x1", f1(frame.x()))
          .attr("y1", f1(y))
          .attr("x2", f1(frame.right()))
          .attr("y2", f1(y))
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
    }
    for (int index = 0; index < operands.size(); index++) {
      UmlSequenceModel.SequenceOperand operand = operands.get(index);
      if (operand.guard() == null || operand.guard().isBlank()) {
        continue;
      }
      double y =
          index == 0
              ? Math.min(frame.y() + FRAGMENT_HEADER_HEIGHT + base.fontSize(), frame.bottom() - 4.0)
              : Math.min(
                  separators.getOrDefault(operand.id(), frame.y()) + base.fontSize() + 6.0,
                  frame.bottom() - 4.0);
      w.start("text")
          .attr("data-dediren-sequence-operand", operand.id())
          .attr("data-dediren-sequence-operand-guard", operand.guard())
          .attr("x", f1(frame.x() + 12.0))
          .attr("y", f1(y))
          .attr("fill", paint.labelFill())
          .attr("font-size", styleNumber(base.fontSize()))
          .text("[" + operand.guard() + "]")
          .end();
    }
  }

  private void edgeMarker(
      SvgWriter w, LaidOutEdge edge, SvgEdgeMarkerEnd marker, String stroke, String side) {
    // Message endpoints sit on the lifeline stem rather than a node border, but the anchoring rule
    // is the same one EdgeMarkers states once for every edge in the product.
    EdgeMarkers.emit(w, edge.id(), side, marker, stroke);
  }

  private void edgePath(SvgWriter w, LaidOutEdge edge, MessageAppearance appearance) {
    if (edge.points().isEmpty()) {
      return;
    }
    w.empty("path")
        .attr("data-dediren-sequence-message", edge.id())
        .attr("d", pathData(edge.points()))
        .attr("fill", "none")
        .attr("stroke", appearance.stroke())
        .attr("stroke-width", styleNumber(appearance.strokeWidth()))
        .attr("stroke-linecap", "round")
        .attr("stroke-linejoin", "round")
        .attrIf("stroke-opacity", opacity(appearance.strokeOpacity()))
        .attrIf(
            "stroke-dasharray",
            appearance.lineStyle() == SvgEdgeLineStyle.DASHED ? DASH_PATTERN : null)
        .attrIf(
            "marker-end",
            appearance.markerEnd() == SvgEdgeMarkerEnd.NONE
                ? null
                : "url(#marker-end-" + edge.id() + ")");
  }

  private void messageLabel(SvgWriter w, LaidOutEdge edge, MessageAppearance appearance) {
    LabelPoint point = labelPoint(edge);
    w.start("text")
        .attr("data-dediren-sequence-message-label", edge.id())
        .attr("x", f1(point.x()))
        .attr("y", f1(point.y()))
        .attr("text-anchor", "middle")
        .attr("fill", appearance.labelFill())
        .attr("font-size", styleNumber(base.fontSize()))
        .attr("font-weight", "600")
        .attrIf("fill-opacity", opacity(appearance.labelOpacity()))
        .text(edge.label())
        .end();
  }

  private LabelPoint labelPoint(LaidOutEdge edge) {
    for (int index = 0; index < edge.points().size() - 1; index++) {
      Point start = edge.points().get(index);
      Point end = edge.points().get(index + 1);
      if (Math.abs(start.y() - end.y()) < 0.001 && Math.abs(start.x() - end.x()) > 0.001) {
        return new LabelPoint((start.x() + end.x()) / 2.0, start.y() - 8.0);
      }
    }
    if (edge.points().isEmpty()) {
      return new LabelPoint(0.0, 0.0);
    }
    Point point = edge.points().get(edge.points().size() / 2);
    return new LabelPoint(point.x(), point.y() - 8.0);
  }

  private MarkerPoint deleteMarkerPoint(LaidOutEdge edge) {
    LaidOutNode target = nodesById.get(edge.target());
    if (target != null) {
      // Inscribe the X in the destruction node's own box (same convention renderGates() uses for
      // its circle) rather than shrinking it below that box: layout anchors the deleteMessage edge
      // at the node's real boundary, so a smaller marker leaves the incoming arrow stopping short
      // of the X instead of touching it.
      return new MarkerPoint(
          target.id(),
          target.x() + target.width() / 2.0,
          target.y() + target.height() / 2.0,
          Math.max(4.0, Math.min(target.width(), target.height()) / 2.0));
    }
    Point point = edge.points().isEmpty() ? new Point(0.0, 0.0) : edge.points().getLast();
    return new MarkerPoint(edge.target(), point.x(), point.y(), 10.0);
  }

  private double diagramBottom() {
    if (!interactionFrames.isEmpty()) {
      double bottom = 0.0;
      for (SequenceFrame frame : interactionFrames.values()) {
        bottom = Math.max(bottom, frame.bottom());
      }
      return bottom;
    }
    double bottom = 0.0;
    for (LaidOutNode node : result.nodes()) {
      bottom = Math.max(bottom, node.y() + node.height());
    }
    for (LaidOutEdge edge : result.edges()) {
      for (Point point : edge.points()) {
        bottom = Math.max(bottom, point.y());
      }
    }
    return bottom + 48.0;
  }

  private SvgBox bounds() {
    SvgBox box = SvgBox.empty();
    for (UmlSequenceModel.SequenceNode interaction : model.interactions()) {
      SequenceFrame frame = interactionFrame(interaction);
      box = box.includeRect(frame.x(), frame.y(), frame.width(), frame.height());
    }
    for (SequenceFrame frame : combinedFragmentFrames.values()) {
      box = box.includeRect(frame.x(), frame.y(), frame.width(), frame.height());
    }
    for (LaidOutNode node : result.nodes()) {
      box = box.includeRect(node.x(), node.y(), node.width(), node.height());
    }
    for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
      LaidOutNode node = lifeline.node();
      box = box.includePoint(node.x() + node.width() / 2.0, stemBottom(lifeline));
    }
    for (LaidOutEdge edge : result.edges()) {
      for (Point point : edge.points()) {
        box = box.includePoint(point.x(), point.y());
      }
      if (edge.label() != null && !edge.label().isEmpty()) {
        LabelPoint label = labelPoint(edge);
        double width = labelWidth(edge.label(), base.fontSize());
        box =
            box.includeRect(
                label.x() - width / 2.0,
                label.y() - base.fontSize(),
                width,
                base.fontSize() * 1.25);
      }
    }
    if (box.isEmpty()) {
      double width = policy.page() == null ? 640.0 : policy.page().width();
      double height = policy.page() == null ? 360.0 : policy.page().height();
      return box.includeRect(0.0, 0.0, width, height);
    }
    return box;
  }

  private Map<String, SequenceFrame> interactionFrames() {
    Map<String, SequenceFrame> frames = new HashMap<>();
    for (UmlSequenceModel.SequenceNode interaction : model.interactions()) {
      frames.put(sequenceInteractionId(interaction), calculateInteractionFrame(interaction));
    }
    return frames;
  }

  private Map<String, SequenceFrame> combinedFragmentFrames() {
    Map<String, SequenceFrame> frames = new HashMap<>();
    Set<String> visiting = new HashSet<>();
    for (UmlSequenceModel.SequenceCombinedFragment fragment : model.combinedFragments()) {
      calculateCombinedFragmentFrame(fragment, frames, visiting);
    }
    return frames;
  }

  private SequenceFrame calculateCombinedFragmentFrame(
      UmlSequenceModel.SequenceCombinedFragment fragment,
      Map<String, SequenceFrame> frames,
      Set<String> visiting) {
    SequenceFrame existing = frames.get(fragment.id());
    if (existing != null) {
      return existing;
    }
    if (!visiting.add(fragment.id())) {
      return null;
    }

    SvgBox content = SvgBox.empty();
    for (UmlSequenceModel.SequenceOperand operand : operandsFor(fragment)) {
      SvgBox operandContent = operandContentBox(operand, frames, visiting);
      if (!operandContent.isEmpty()) {
        content =
            content.includeRect(
                operandContent.minX(),
                operandContent.minY(),
                operandContent.width(),
                operandContent.height());
      }
    }
    content = includeCoveredLifelineExtents(content, fragment);
    SequenceFrame frame = content.isEmpty() ? null : clipToInteractionFrame(fragment, content);
    if (frame != null) {
      frames.put(fragment.id(), frame);
    }
    visiting.remove(fragment.id());
    return frame;
  }

  private SvgBox operandContentBox(
      UmlSequenceModel.SequenceOperand operand,
      Map<String, SequenceFrame> frames,
      Set<String> visiting) {
    SvgBox content = SvgBox.empty();
    for (String fragmentId : operand.fragmentIds()) {
      LaidOutEdge edge = edgesById.get(fragmentId);
      if (edge != null) {
        for (Point point : edge.points()) {
          content = content.includePoint(point.x(), point.y());
        }
        continue;
      }
      UmlSequenceModel.SequenceCombinedFragment nestedFragment =
          combinedFragmentsById.get(fragmentId);
      if (nestedFragment == null) {
        continue;
      }
      SequenceFrame nestedFrame = calculateCombinedFragmentFrame(nestedFragment, frames, visiting);
      if (nestedFrame != null) {
        content =
            content.includeRect(
                nestedFrame.x(), nestedFrame.y(), nestedFrame.width(), nestedFrame.height());
      }
    }
    return content;
  }

  private SvgBox includeCoveredLifelineExtents(
      SvgBox content, UmlSequenceModel.SequenceCombinedFragment fragment) {
    if (content.isEmpty()) {
      return content;
    }
    for (String lifelineId : fragment.coveredLifelineIds()) {
      LaidOutNode lifeline = nodesById.get(lifelineId);
      if (lifeline == null) {
        continue;
      }
      content =
          content
              .includePoint(lifeline.x(), content.minY())
              .includePoint(lifeline.x() + lifeline.width(), content.maxY());
    }
    return content;
  }

  private SequenceFrame clipToInteractionFrame(
      UmlSequenceModel.SequenceCombinedFragment fragment, SvgBox content) {
    double left = content.minX() - FRAGMENT_HORIZONTAL_PADDING;
    double top = content.minY() - FRAGMENT_VERTICAL_PADDING - FRAGMENT_HEADER_HEIGHT;
    double right = content.maxX() + FRAGMENT_HORIZONTAL_PADDING;
    double bottom = content.maxY() + FRAGMENT_VERTICAL_PADDING;

    SequenceFrame interactionFrame = interactionFrame(fragment.interactionId());
    if (interactionFrame != null) {
      left = Math.max(left, interactionFrame.x());
      top = Math.max(top, interactionFrame.y() + FRAGMENT_HEADER_HEIGHT);
      right = Math.min(right, interactionFrame.right());
      bottom = Math.min(bottom, interactionFrame.bottom());
    }
    if (right <= left || bottom <= top) {
      return null;
    }
    return new SequenceFrame(left, top, right - left, bottom - top);
  }

  private SequenceFrame calculateInteractionFrame(UmlSequenceModel.SequenceNode interaction) {
    LaidOutNode node = interaction.node();
    String interactionId = sequenceInteractionId(interaction);
    SvgBox content = SvgBox.empty();
    for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
      if (belongsToInteraction(lifeline, interactionId)) {
        content = includeNode(content, lifeline.node());
      }
    }
    for (UmlSequenceModel.SequenceNode execution : model.executions()) {
      if (belongsToInteraction(execution, interactionId)) {
        content = includeNode(content, execution.node());
      }
    }
    for (UmlSequenceModel.SequenceNode gate : model.gates()) {
      if (belongsToInteraction(gate, interactionId)) {
        content = includeNode(content, gate.node());
      }
    }
    for (UmlSequenceModel.SequenceNode destruction : model.destructions()) {
      if (belongsToInteraction(destruction, interactionId)) {
        content = includeNode(content, destruction.node());
      }
    }
    for (UmlSequenceModel.SequenceMessage message : model.messages()) {
      if (!belongsToInteraction(message, interactionId)) {
        continue;
      }
      for (Point point : message.edge().points()) {
        content = content.includePoint(point.x(), point.y());
      }
    }

    if (content.isEmpty()) {
      return new SequenceFrame(node.x(), node.y(), node.width(), node.height());
    }

    double left = Math.min(node.x(), content.minX() - INTERACTION_HORIZONTAL_PADDING);
    double top = Math.min(node.y(), content.minY() - INTERACTION_TOP_PADDING);
    double right =
        Math.max(node.x() + node.width(), content.maxX() + INTERACTION_HORIZONTAL_PADDING);
    double bottom = Math.max(node.y() + node.height(), content.maxY() + INTERACTION_BOTTOM_PADDING);
    return new SequenceFrame(left, top, right - left, bottom - top);
  }

  private SequenceFrame interactionFrame(UmlSequenceModel.SequenceNode interaction) {
    SequenceFrame frame = interactionFrame(sequenceInteractionId(interaction));
    return frame == null
        ? new SequenceFrame(
            interaction.node().x(),
            interaction.node().y(),
            interaction.node().width(),
            interaction.node().height())
        : frame;
  }

  private SequenceFrame interactionFrame(String interactionId) {
    SequenceFrame frame = interactionId == null ? null : interactionFrames.get(interactionId);
    if (frame == null && interactionFrames.size() == 1) {
      return interactionFrames.values().iterator().next();
    }
    return frame;
  }

  private double stemBottom(UmlSequenceModel.SequenceNode lifeline) {
    String interactionId = propertyText(lifeline.selector().properties(), "interaction");
    SequenceFrame frame = interactionFrame(interactionId);
    return frame == null ? diagramBottom() : frame.bottom();
  }

  private List<UmlSequenceModel.SequenceOperand> operandsFor(
      UmlSequenceModel.SequenceCombinedFragment fragment) {
    return operandsByFragmentId.getOrDefault(fragment.id(), List.of()).stream()
        .filter(
            operand ->
                fragment.operandIds().isEmpty() || fragment.operandIds().contains(operand.id()))
        .sorted(
            Comparator.<UmlSequenceModel.SequenceOperand>comparingInt(
                    operand -> operandIndex(fragment, operand))
                .thenComparingInt(UmlSequenceModel.SequenceOperand::order)
                .thenComparing(UmlSequenceModel.SequenceOperand::id))
        .toList();
  }

  private static int operandIndex(
      UmlSequenceModel.SequenceCombinedFragment fragment,
      UmlSequenceModel.SequenceOperand operand) {
    int index = fragment.operandIds().indexOf(operand.id());
    return index >= 0 ? index : Integer.MAX_VALUE;
  }

  private double separatorY(SequenceFrame frame, SvgBox previous, SvgBox current) {
    double y = frame.y() + FRAGMENT_HEADER_HEIGHT;
    if (previous != null && current != null && !previous.isEmpty() && !current.isEmpty()) {
      y = (previous.maxY() + current.minY()) / 2.0;
    }
    double min = frame.y() + FRAGMENT_HEADER_HEIGHT + 4.0;
    double max = frame.bottom() - 4.0;
    return Math.max(min, Math.min(max, y));
  }

  private int combinedFragmentDepth(UmlSequenceModel.SequenceCombinedFragment fragment) {
    return combinedFragmentDepth(fragment, new HashSet<>());
  }

  private int combinedFragmentDepth(
      UmlSequenceModel.SequenceCombinedFragment fragment, Set<String> visiting) {
    if (!visiting.add(fragment.id())) {
      return 0;
    }
    int depth = 0;
    for (UmlSequenceModel.SequenceOperand operand : operandsFor(fragment)) {
      for (String fragmentId : operand.fragmentIds()) {
        UmlSequenceModel.SequenceCombinedFragment nested = combinedFragmentsById.get(fragmentId);
        if (nested != null) {
          depth = Math.max(depth, 1 + combinedFragmentDepth(nested, visiting));
        }
      }
    }
    visiting.remove(fragment.id());
    return depth;
  }

  private boolean belongsToInteraction(UmlSequenceModel.SequenceNode node, String interactionId) {
    return belongsToInteraction(node.selector().properties(), interactionId);
  }

  private boolean belongsToInteraction(
      UmlSequenceModel.SequenceMessage message, String interactionId) {
    return belongsToInteraction(message.selector().properties(), interactionId);
  }

  private boolean belongsToInteraction(JsonNode properties, String interactionId) {
    String candidate = propertyText(properties, "interaction");
    return interactionId.equals(candidate)
        || (candidate == null && model.interactions().size() == 1);
  }

  private static String sequenceInteractionId(UmlSequenceModel.SequenceNode interaction) {
    return interaction.selector().sourceId();
  }

  private static String propertyText(JsonNode properties, String name) {
    JsonNode value = properties == null ? null : properties.get(name);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static SvgBox includeNode(SvgBox box, LaidOutNode node) {
    return box.includeRect(node.x(), node.y(), node.width(), node.height());
  }

  // The base already carries the policy-level node/edge overrides; StyleResolver layers the
  // per-type and per-id overrides on top (looking the type up from metadata itself).
  private ResolvedNodeStyle nodePaint(String nodeId) {
    return StyleResolver.nodeStyle(policy, metadata, nodeId, base);
  }

  private ResolvedEdgeStyle edgePaint(String edgeId) {
    return StyleResolver.edgeStyle(policy, metadata, edgeId, base);
  }

  private static Map<String, LaidOutNode> nodesById(List<LaidOutNode> nodes) {
    Map<String, LaidOutNode> byId = new HashMap<>();
    for (LaidOutNode node : nodes) {
      byId.put(node.id(), node);
    }
    return byId;
  }

  private static Map<String, LaidOutEdge> edgesById(List<LaidOutEdge> edges) {
    Map<String, LaidOutEdge> byId = new HashMap<>();
    for (LaidOutEdge edge : edges) {
      byId.put(edge.id(), edge);
    }
    return byId;
  }

  private Map<String, UmlSequenceModel.SequenceCombinedFragment> combinedFragmentsById() {
    Map<String, UmlSequenceModel.SequenceCombinedFragment> byId = new HashMap<>();
    for (UmlSequenceModel.SequenceCombinedFragment fragment : model.combinedFragments()) {
      byId.put(fragment.id(), fragment);
    }
    return byId;
  }

  private Map<String, List<UmlSequenceModel.SequenceOperand>> operandsByFragmentId() {
    Map<String, List<UmlSequenceModel.SequenceOperand>> byId = new HashMap<>();
    for (UmlSequenceModel.SequenceOperand operand : model.operands()) {
      byId.computeIfAbsent(operand.combinedFragmentId(), ignored -> new ArrayList<>()).add(operand);
    }
    return byId;
  }

  private static String pathData(List<Point> points) {
    if (points.isEmpty()) {
      return "";
    }
    StringBuilder data = new StringBuilder();
    Point first = points.getFirst();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
    for (int index = 1; index < points.size(); index++) {
      Point point = points.get(index);
      data.append(String.format(Locale.ROOT, " L %.1f %.1f", point.x(), point.y()));
    }
    return data.toString();
  }

  private static double labelWidth(String label, double fontSize) {
    return Svg.estimateTextWidth(label, fontSize);
  }

  private record MessageAppearance(
      String stroke,
      double strokeWidth,
      String labelFill,
      SvgEdgeLineStyle lineStyle,
      SvgEdgeMarkerEnd markerEnd,
      Double strokeOpacity,
      Double labelOpacity) {
    static MessageAppearance from(String messageSort, ResolvedEdgeStyle paint) {
      SvgEdgeLineStyle lineStyle =
          "reply".equals(messageSort) ? SvgEdgeLineStyle.DASHED : SvgEdgeLineStyle.SOLID;
      SvgEdgeMarkerEnd markerEnd =
          switch (messageSort) {
            case "asynchCall", "asynchSignal", "reply", "createMessage" ->
                SvgEdgeMarkerEnd.OPEN_ARROW;
            case "deleteMessage" -> SvgEdgeMarkerEnd.NONE;
            default -> SvgEdgeMarkerEnd.FILLED_ARROW;
          };
      return new MessageAppearance(
          paint.stroke(),
          paint.strokeWidth(),
          paint.labelFill(),
          lineStyle,
          markerEnd,
          paint.strokeOpacity(),
          paint.labelOpacity());
    }
  }

  private record SvgBox(double minX, double minY, double maxX, double maxY, boolean isEmpty) {
    static SvgBox empty() {
      return new SvgBox(0.0, 0.0, 0.0, 0.0, true);
    }

    SvgBox includePoint(double x, double y) {
      if (isEmpty) {
        return new SvgBox(x, y, x, y, false);
      }
      return new SvgBox(
          Math.min(minX, x), Math.min(minY, y), Math.max(maxX, x), Math.max(maxY, y), false);
    }

    SvgBox includeRect(double x, double y, double width, double height) {
      return includePoint(x, y).includePoint(x + width, y + height);
    }

    SvgBox padded(RenderPolicy policy) {
      double top = policy.margin() == null ? 16.0 : policy.margin().top();
      double right = policy.margin() == null ? 16.0 : policy.margin().right();
      double bottom = policy.margin() == null ? 16.0 : policy.margin().bottom();
      double left = policy.margin() == null ? 16.0 : policy.margin().left();
      return new SvgBox(minX - left, minY - top, maxX + right, maxY + bottom, isEmpty);
    }

    double width() {
      return maxX - minX;
    }

    double height() {
      return maxY - minY;
    }
  }

  private record LabelPoint(double x, double y) {}

  private record MarkerPoint(String id, double x, double y, double size) {}

  private record SequenceFrame(double x, double y, double width, double height) {
    double right() {
      return x + width;
    }

    double bottom() {
      return y + height;
    }
  }
}

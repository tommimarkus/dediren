package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeMarker;
import static dev.dediren.plugins.render.svg.EdgeRenderer.pathData;
import static dev.dediren.plugins.render.svg.Geometry.labelBox;
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
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedCombinedFragment;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedDeleteMarker;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedExecution;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedGate;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedInteraction;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedLifelineHead;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedLifelineStem;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedMessage;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedMessageLabel;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedOperandGuard;
import dev.dediren.plugins.render.PlacedSequenceScene.PlacedOperandSeparator;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.plugins.render.svg.Svg;
import dev.dediren.plugins.render.svg.SvgBounds;
import dev.dediren.plugins.render.svg.SvgIds;
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

/**
 * The UML sequence lane of {@link SvgDocument}: the same resolve → measure → emit passes over the
 * same {@link PlacedElement} fold and the same {@link SvgDocument#openDocument} skeleton, placing a
 * document made of lifelines, interaction frames, combined fragments and messages instead of nodes,
 * edges and groups. See {@link PlacedSequenceScene} for what is deliberately not shared and why.
 */
final class UmlSequenceRenderer {
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

  UmlSequenceRenderer(LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
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

  static boolean isSequence(RenderMetadata metadata) {
    if (metadata == null || !"uml".equals(metadata.semanticProfile())) {
      return false;
    }
    boolean hasLifeline =
        metadata.nodes().values().stream().anyMatch(selector -> "Lifeline".equals(selector.type()));
    boolean hasMessage =
        metadata.edges().values().stream().anyMatch(selector -> "Message".equals(selector.type()));
    return hasLifeline || hasMessage;
  }

  String render() {
    PlacedSequenceScene scene = resolve();
    return emit(new SvgWriter(), scene, SvgDocument.measure(scene.elements(), scene.policy()));
  }

  /**
   * Places everything the document draws, once. Nothing below this line reads the layout result or
   * the model again: the viewBox is folded from these objects and then the very same objects are
   * written, so the bounds and the markup cannot part company.
   *
   * <p>Package-private rather than private so {@code PlacedSceneCompletenessTest} can fold a real
   * scene and check that {@link PlacedSequenceScene#elements()} covers every drawable list the
   * record carries. That enumeration is hand-maintained and the emitter's loop set is a second one;
   * nothing in the language keeps them in step.
   */
  PlacedSequenceScene resolve() {
    List<PlacedInteraction> interactions = new ArrayList<>();
    for (UmlSequenceModel.SequenceNode interaction : model.interactions()) {
      interactions.add(placeInteraction(interaction));
    }
    List<PlacedCombinedFragment> fragments = new ArrayList<>();
    for (UmlSequenceModel.SequenceCombinedFragment fragment : sortedCombinedFragments()) {
      fragments.add(placeCombinedFragment(fragment));
    }
    List<PlacedLifelineHead> heads = new ArrayList<>();
    List<PlacedLifelineStem> stems = new ArrayList<>();
    for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
      LaidOutNode node = lifeline.node();
      ResolvedNodeStyle paint = nodePaint(node.id());
      heads.add(
          new PlacedLifelineHead(
              node,
              paint,
              Math.max(paint.rx(), 2.0),
              placeCentredLabel(
                  node.x() + node.width() / 2.0,
                  node.y() + node.height() / 2.0 + base.fontSize() / 3.0,
                  node.label())));
      stems.add(
          new PlacedLifelineStem(
              node.id(),
              paint,
              node.x() + node.width() / 2.0,
              node.y() + node.height(),
              stemBottom(lifeline)));
    }
    List<PlacedExecution> executions = new ArrayList<>();
    for (UmlSequenceModel.SequenceNode execution : model.executions()) {
      LaidOutNode node = execution.node();
      ResolvedNodeStyle paint = nodePaint(node.id());
      executions.add(new PlacedExecution(node, paint, Math.max(0.0, paint.rx())));
    }
    List<PlacedGate> gates = new ArrayList<>();
    for (UmlSequenceModel.SequenceNode gate : model.gates()) {
      LaidOutNode node = gate.node();
      gates.add(
          new PlacedGate(
              node,
              nodePaint(node.id()),
              Math.max(4.0, Math.min(node.width(), node.height()) / 2.0)));
    }
    List<PlacedMessage> messages = new ArrayList<>();
    List<PlacedDeleteMarker> deleteMarkers = new ArrayList<>();
    for (UmlSequenceModel.SequenceMessage message : model.messages()) {
      LaidOutEdge edge = message.edge();
      ResolvedEdgeStyle style = messageStyle(message.messageSort(), edgePaint(edge.id()));
      messages.add(new PlacedMessage(edge, style, message.messageSort(), placeMessageLabel(edge)));
    }
    // A second pass over the same messages, because every destruction X is painted after every
    // message: the arrow that causes it must arrive under the marker, not over it.
    for (UmlSequenceModel.SequenceMessage message : model.messages()) {
      if ("deleteMessage".equals(message.messageSort())) {
        deleteMarkers.add(placeDeleteMarker(message.edge()));
      }
    }
    return new PlacedSequenceScene(
        policy,
        result.viewId(),
        base,
        interactions,
        fragments,
        heads,
        stems,
        executions,
        gates,
        messages,
        deleteMarkers);
  }

  /** Writes the placed scene. No placement decision is left to make here — see {@link #resolve}. */
  private String emit(SvgWriter w, PlacedSequenceScene scene, SvgBounds bounds) {
    // Per-document, like SvgDocument's: message-marker ids are minted from layout edge ids, which
    // the layout contract constrains neither in charset nor in uniqueness. Local to emit() so a
    // second render() of the same renderer starts from an empty document, not a used-id set.
    SvgIds ids = new SvgIds();
    SvgDocument.openDocument(w, scene.policy(), scene.viewId(), scene.base(), bounds);

    for (PlacedInteraction placed : scene.interactions()) {
      emitInteraction(w, placed);
    }
    for (PlacedCombinedFragment placed : scene.combinedFragments()) {
      emitCombinedFragment(w, placed);
    }
    for (PlacedLifelineHead placed : scene.lifelineHeads()) {
      emitLifelineHead(w, placed);
    }
    for (PlacedLifelineStem placed : scene.lifelineStems()) {
      emitLifelineStem(w, placed);
    }
    for (PlacedExecution placed : scene.executions()) {
      emitExecution(w, placed);
    }
    for (PlacedGate placed : scene.gates()) {
      emitGate(w, placed);
    }
    for (PlacedMessage placed : scene.messages()) {
      emitMessage(w, ids, placed);
    }
    for (PlacedDeleteMarker placed : scene.deleteMarkers()) {
      emitDeleteMarker(w, placed);
    }

    return SvgDocument.closeDocument(w);
  }

  /**
   * Opens the wrapper {@code <g>} that carries a box shape's optional fill/stroke opacity and dash,
   * returning whether one was opened. The same mechanism the generic node lane uses, rather than
   * the attributes-on-the-shape one the sequence lane used to have: two structurally different ways
   * to express the same policy fields, with the precedence between them documented only in prose. A
   * shape inside may still write its own {@code stroke-dasharray} and it will win, which is why the
   * shapes that have a notation fallback state the winner outright ({@code Svg.shapeDash}) instead
   * of leaning on the wrapper being there.
   *
   * <p>{@code dash_pattern} is deliberately not consulted: sequence boxes take the {@code
   * line_style} presets only. {@code MessageAppearance}'s successor {@link #messageStyle} holds the
   * same line for messages.
   */
  private static boolean beginShapeWrapper(SvgWriter w, ResolvedNodeStyle paint) {
    String dash = boxDash(paint);
    boolean wrap = paint.fillOpacity() != null || paint.strokeOpacity() != null || dash != null;
    if (wrap) {
      w.start("g")
          .attrIf("fill-opacity", opacity(paint.fillOpacity()))
          .attrIf("stroke-opacity", opacity(paint.strokeOpacity()))
          .attrIf("stroke-dasharray", dash);
    }
    return wrap;
  }

  private static String boxDash(ResolvedNodeStyle paint) {
    String value = dashArrayValue(paint.lineStyle(), null, "6 4");
    return value.isEmpty() ? null : value;
  }

  private PlacedInteraction placeInteraction(UmlSequenceModel.SequenceNode interaction) {
    LaidOutNode node = interaction.node();
    SequenceFrame frame = interactionFrame(interaction);
    ResolvedNodeStyle paint = nodePaint(node.id());
    String title = interactionFrameTitle(node.label());
    double titleWidth =
        Math.max(96.0, Math.min(frame.width() * 0.5, labelWidth(title, base.fontSize()) + 24.0));
    double titleHeight = Math.max(24.0, base.fontSize() + 10.0);
    return new PlacedInteraction(
        node,
        paint,
        frame,
        titleWidth,
        titleHeight,
        placeStartLabel(frame.x() + 10.0, frame.y() + titleHeight - 8.0, title));
  }

  /**
   * The interaction frame's pentagon carries the kind tag {@code sd} before the interaction name
   * (§17.2.4.1). Without it the frame is untagged and a reader cannot tell a sequence diagram's
   * frame from any other framed diagram — the tag is the only thing that names the kind.
   */
  private static String interactionFrameTitle(String label) {
    return label == null || label.isBlank() ? "sd" : "sd " + label;
  }

  private void emitInteraction(SvgWriter w, PlacedInteraction placed) {
    LaidOutNode node = placed.node();
    ResolvedNodeStyle paint = placed.style();
    SequenceFrame frame = placed.frame();
    w.start("g")
        .attr("data-dediren-node-id", node.id())
        .attr("data-dediren-node-type", "Interaction")
        .attr("data-dediren-sequence-interaction", "true");
    boolean wrapped = beginShapeWrapper(w, paint);
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
    w.empty("path")
        .attr("data-dediren-sequence-interaction-title", "true")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z",
                frame.x(),
                frame.y(),
                frame.x() + placed.titleWidth(),
                frame.x() + placed.titleWidth() - 12.0,
                frame.y() + placed.titleHeight(),
                frame.y() + placed.titleHeight(),
                frame.x()))
        .attr("fill", paint.fill())
        .attr("stroke", paint.stroke())
        .attr("stroke-width", styleNumber(paint.strokeWidth()));
    if (wrapped) {
      w.end();
    }
    w.start("text")
        .attr("x", f1(frame.x() + 10.0))
        .attr("y", f1(frame.y() + placed.titleHeight() - 8.0))
        .attr("fill", paint.labelFill())
        .attrIf("fill-opacity", opacity(paint.labelOpacity()))
        .text(interactionFrameTitle(node.label()))
        .end();
    w.end();
  }

  /**
   * Combined fragments in paint order: deepest first, then top-to-bottom, left-to-right, then by
   * id.
   *
   * <p>A deliberate total order over what is otherwise a {@code HashMap}'s iteration, and the
   * reason a nested fragment is painted before — and so under — the fragment that contains it. The
   * trailing id keeps two frames at the same depth and position from swapping between runs.
   */
  private List<UmlSequenceModel.SequenceCombinedFragment> sortedCombinedFragments() {
    return model.combinedFragments().stream()
        .filter(fragment -> combinedFragmentFrames.containsKey(fragment.id()))
        .sorted(
            Comparator.<UmlSequenceModel.SequenceCombinedFragment>comparingInt(
                    this::combinedFragmentDepth)
                .reversed()
                .thenComparingDouble(fragment -> combinedFragmentFrames.get(fragment.id()).y())
                .thenComparingDouble(fragment -> combinedFragmentFrames.get(fragment.id()).x())
                .thenComparing(UmlSequenceModel.SequenceCombinedFragment::id))
        .toList();
  }

  private PlacedCombinedFragment placeCombinedFragment(
      UmlSequenceModel.SequenceCombinedFragment fragment) {
    SequenceFrame frame = combinedFragmentFrames.get(fragment.id());
    ResolvedNodeStyle paint = nodePaint(fragment.id());
    double tabWidth =
        Math.max(
            44.0,
            Math.min(frame.width() * 0.5, labelWidth(fragment.operator(), base.fontSize()) + 24.0));

    List<UmlSequenceModel.SequenceOperand> operands = operandsFor(fragment);
    Map<String, SvgBounds> operandBoxes = new HashMap<>();
    for (UmlSequenceModel.SequenceOperand operand : operands) {
      operandBoxes.put(operand.id(), operandContentBox(operand, new HashMap<>(), new HashSet<>()));
    }
    List<PlacedOperandSeparator> separators = new ArrayList<>();
    Map<String, Double> separatorY = new HashMap<>();
    for (int index = 1; index < operands.size(); index++) {
      UmlSequenceModel.SequenceOperand previous = operands.get(index - 1);
      UmlSequenceModel.SequenceOperand current = operands.get(index);
      double y = separatorY(frame, operandBoxes.get(previous.id()), operandBoxes.get(current.id()));
      separatorY.put(current.id(), y);
      separators.add(new PlacedOperandSeparator(current.id(), y));
    }
    List<PlacedOperandGuard> guards = new ArrayList<>();
    for (int index = 0; index < operands.size(); index++) {
      UmlSequenceModel.SequenceOperand operand = operands.get(index);
      if (operand.guard() == null || operand.guard().isBlank()) {
        continue;
      }
      double y =
          index == 0
              ? Math.min(frame.y() + FRAGMENT_HEADER_HEIGHT + base.fontSize(), frame.bottom() - 4.0)
              : Math.min(
                  separatorY.getOrDefault(operand.id(), frame.y()) + base.fontSize() + 6.0,
                  frame.bottom() - 4.0);
      String text = "[" + operand.guard() + "]";
      double x = frame.x() + 12.0;
      guards.add(
          new PlacedOperandGuard(
              operand.id(),
              operand.guard(),
              text,
              x,
              y,
              labelBox(x, y, "start", text, base.fontSize())));
    }
    return new PlacedCombinedFragment(
        fragment.id(),
        fragment.operator(),
        paint,
        frame,
        tabWidth,
        FRAGMENT_HEADER_HEIGHT,
        placeStartLabel(
            frame.x() + 10.0, frame.y() + FRAGMENT_HEADER_HEIGHT - 7.0, fragment.operator()),
        separators,
        guards);
  }

  private void emitCombinedFragment(SvgWriter w, PlacedCombinedFragment placed) {
    ResolvedNodeStyle paint = placed.style();
    SequenceFrame frame = placed.frame();
    w.start("g")
        .attr("data-dediren-sequence-combined-fragment", placed.id())
        .attr("data-dediren-sequence-interaction-operator", placed.operator());
    boolean wrapped = beginShapeWrapper(w, paint);
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
    w.empty("path")
        .attr("data-dediren-sequence-fragment-operator-tab", "true")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z",
                frame.x(),
                frame.y(),
                frame.x() + placed.tabWidth(),
                frame.x() + placed.tabWidth() - 10.0,
                frame.y() + placed.tabHeight(),
                frame.y() + placed.tabHeight(),
                frame.x()))
        .attr("fill", paint.fill())
        .attr("stroke", paint.stroke())
        .attr("stroke-width", styleNumber(paint.strokeWidth()));
    if (wrapped) {
      w.end();
    }
    w.start("text")
        .attr("data-dediren-sequence-fragment-operator", placed.id())
        .attr("x", f1(frame.x() + 10.0))
        .attr("y", f1(frame.y() + placed.tabHeight() - 7.0))
        .attr("fill", paint.labelFill())
        .attr("font-weight", "600")
        .attrIf("fill-opacity", opacity(paint.labelOpacity()))
        .text(placed.operator())
        .end();
    for (PlacedOperandSeparator separator : placed.separators()) {
      w.empty("line")
          .attr("data-dediren-sequence-operand-separator", separator.operandId())
          .attr("x1", f1(frame.x()))
          .attr("y1", f1(separator.y()))
          .attr("x2", f1(frame.right()))
          .attr("y2", f1(separator.y()))
          .attr("stroke", paint.stroke())
          .attr("stroke-width", styleNumber(paint.strokeWidth()));
    }
    for (PlacedOperandGuard guard : placed.guards()) {
      w.start("text")
          .attr("data-dediren-sequence-operand", guard.operandId())
          .attr("data-dediren-sequence-operand-guard", guard.guard())
          .attr("x", f1(guard.x()))
          .attr("y", f1(guard.y()))
          .attr("fill", paint.labelFill())
          .attr("font-size", styleNumber(base.fontSize()))
          .text(guard.text())
          .end();
    }
    w.end();
  }

  private void emitLifelineHead(SvgWriter w, PlacedLifelineHead placed) {
    LaidOutNode node = placed.node();
    ResolvedNodeStyle paint = placed.style();
    w.start("g")
        .attr("data-dediren-node-id", node.id())
        .attr("data-dediren-node-type", "Lifeline")
        .attr("data-dediren-sequence-lifeline", "true");
    boolean wrapped = beginShapeWrapper(w, paint);
    w.empty("rect")
        .attr("data-dediren-node-shape", "uml_lifeline")
        .attr("x", f1(node.x()))
        .attr("y", f1(node.y()))
        .attr("width", f1(node.width()))
        .attr("height", f1(node.height()))
        .attr("rx", styleNumber(placed.rx()))
        .attr("fill", paint.fill())
        .attr("stroke", paint.stroke())
        .attr("stroke-width", styleNumber(paint.strokeWidth()));
    if (wrapped) {
      w.end();
    }
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

  private void emitLifelineStem(SvgWriter w, PlacedLifelineStem placed) {
    ResolvedNodeStyle paint = placed.style();
    w.empty("line")
        .attr("data-dediren-sequence-lifeline-stem", placed.lifelineId())
        .attr("x1", f1(placed.x()))
        .attr("y1", f1(placed.top()))
        .attr("x2", f1(placed.x()))
        .attr("y2", f1(placed.bottom()))
        .attr("stroke", paint.stroke())
        .attr("stroke-width", styleNumber(paint.strokeWidth()))
        .attr("stroke-dasharray", DASH_PATTERN);
  }

  private void emitExecution(SvgWriter w, PlacedExecution placed) {
    LaidOutNode node = placed.node();
    ResolvedNodeStyle paint = placed.style();
    w.start("g")
        .attr("data-dediren-node-id", node.id())
        .attr("data-dediren-node-type", "ExecutionSpecification");
    boolean wrapped = beginShapeWrapper(w, paint);
    w.empty("rect")
        .attr("data-dediren-node-shape", "uml_execution_specification")
        .attr("x", f1(node.x()))
        .attr("y", f1(node.y()))
        .attr("width", f1(node.width()))
        .attr("height", f1(node.height()))
        .attr("rx", styleNumber(placed.rx()))
        .attr("fill", paint.fill())
        .attr("stroke", paint.stroke())
        .attr("stroke-width", styleNumber(paint.strokeWidth()));
    if (wrapped) {
      w.end();
    }
    w.start("g").attr("data-dediren-node-decorator", "uml_execution_specification").end();
    w.end();
  }

  private void emitGate(SvgWriter w, PlacedGate placed) {
    LaidOutNode node = placed.node();
    ResolvedNodeStyle paint = placed.style();
    w.start("g").attr("data-dediren-node-id", node.id()).attr("data-dediren-node-type", "Gate");
    boolean wrapped = beginShapeWrapper(w, paint);
    w.empty("circle")
        .attr("data-dediren-node-shape", "uml_gate")
        .attr("cx", f1(node.x() + node.width() / 2.0))
        .attr("cy", f1(node.y() + node.height() / 2.0))
        .attr("r", f1(placed.radius()))
        .attr("fill", paint.fill())
        .attr("stroke", paint.stroke())
        .attr("stroke-width", styleNumber(paint.strokeWidth()));
    if (wrapped) {
      w.end();
    }
    w.start("g").attr("data-dediren-node-decorator", "uml_gate").end();
    w.end();
  }

  private void emitMessage(SvgWriter w, SvgIds ids, PlacedMessage placed) {
    LaidOutEdge edge = placed.edge();
    ResolvedEdgeStyle style = placed.style();
    w.start("g")
        .attr("data-dediren-edge-id", edge.id())
        .attr("data-dediren-sequence-message-sort", placed.messageSort());
    // Message endpoints sit on the lifeline stem rather than a node border, but the marker mint,
    // the anchoring rule and the reference it hands back are the ones every edge in the product
    // goes through.
    String endMarkerId = edgeMarker(w, ids, edge, style, "end");
    if (!edge.points().isEmpty()) {
      String dash = dashArrayValue(style.lineStyle(), style.dashPattern(), DASH_PATTERN);
      w.empty("path")
          .attr("data-dediren-sequence-message", edge.id())
          // The shared route emitter, so a corner-rounding or line-jump fix made for the generic
          // lane reaches sequence messages too. No jump list: a jump's mask is a backdrop-coloured
          // stroke, and the only backdrop this lane could name is the page — it would punch a hole
          // in the interaction or fragment fill the crossing actually sits on.
          .attr("d", pathData(edge, List.of()))
          .attr("fill", "none")
          .attr("stroke", style.stroke())
          .attr("stroke-width", styleNumber(style.strokeWidth()))
          .attr("stroke-linecap", "round")
          .attr("stroke-linejoin", "round")
          .attrIf("stroke-opacity", opacity(style.strokeOpacity()))
          .attrIf("stroke-dasharray", dash.isEmpty() ? null : dash)
          .attrIf("marker-end", ids.reference(endMarkerId));
    }
    PlacedMessageLabel label = placed.label();
    if (label != null) {
      w.start("text")
          .attr("data-dediren-sequence-message-label", edge.id())
          .attr("x", f1(label.x()))
          .attr("y", f1(label.y()))
          .attr("text-anchor", "middle")
          .attr("fill", style.labelFill())
          .attr("font-size", styleNumber(base.fontSize()))
          .attr("font-weight", "600")
          .attrIf("fill-opacity", opacity(style.labelOpacity()))
          .text(label.text())
          .end();
    }
    w.end();
  }

  private void emitDeleteMarker(SvgWriter w, PlacedDeleteMarker placed) {
    double size = placed.size();
    w.start("g").attr("data-dediren-sequence-delete-marker", placed.id());
    w.empty("line")
        .attr("x1", f1(placed.x() - size))
        .attr("y1", f1(placed.y() - size))
        .attr("x2", f1(placed.x() + size))
        .attr("y2", f1(placed.y() + size))
        .attr("stroke", placed.style().stroke())
        .attr("stroke-width", styleNumber(placed.strokeWidth()));
    w.empty("line")
        .attr("x1", f1(placed.x() - size))
        .attr("y1", f1(placed.y() + size))
        .attr("x2", f1(placed.x() + size))
        .attr("y2", f1(placed.y() - size))
        .attr("stroke", placed.style().stroke())
        .attr("stroke-width", styleNumber(placed.strokeWidth()));
    w.end();
  }

  /**
   * The notation-resolved style a message is drawn with: marker end and line style derive from
   * {@code message_sort} alone (reply → dashed; asynch/create/reply → open arrow; delete → none;
   * else filled arrow), and a sequence message never carries a start marker.
   *
   * <p>Returned as a {@link ResolvedEdgeStyle} rather than a private appearance record so the one
   * value drives the emitted attributes <em>and</em> {@code markerInkBoxes} — the viewBox grows
   * around the arrowhead that is actually drawn. The policy's own {@code marker_end}/{@code
   * line_style}/{@code dash_pattern} and the edge-label position fields are intentionally not
   * consulted: {@code dash_pattern} is nulled here so {@code dashArrayValue} falls through to the
   * notation dash, which is the same "line_style presets only" constraint {@code beginShapeWrapper}
   * applies to sequence boxes. Only the paint dimensions (stroke, widths, label fill, opacities)
   * pass through.
   */
  private static ResolvedEdgeStyle messageStyle(String messageSort, ResolvedEdgeStyle paint) {
    // §17.4.4 dashes both the reply and the createMessage; the open-vs-filled arrowhead is what
    // separates them from each other and from a synchronous call. Rendered solid, a createMessage
    // is byte-identical to an asynchronous message and object creation reads as a signal.
    SvgEdgeLineStyle lineStyle =
        switch (messageSort) {
          case "reply", "createMessage" -> SvgEdgeLineStyle.DASHED;
          default -> SvgEdgeLineStyle.SOLID;
        };
    SvgEdgeMarkerEnd markerEnd =
        switch (messageSort) {
          case "asynchCall", "asynchSignal", "reply", "createMessage" ->
              SvgEdgeMarkerEnd.OPEN_ARROW;
          case "deleteMessage" -> SvgEdgeMarkerEnd.NONE;
          default -> SvgEdgeMarkerEnd.FILLED_ARROW;
        };
    return new ResolvedEdgeStyle(
        paint.stroke(),
        paint.strokeWidth(),
        paint.labelFill(),
        lineStyle,
        SvgEdgeMarkerEnd.NONE,
        markerEnd,
        paint.labelHorizontalPosition(),
        paint.labelHorizontalSide(),
        paint.labelVerticalPosition(),
        paint.labelVerticalSide(),
        paint.labelPresentation(),
        paint.strokeOpacity(),
        null,
        paint.labelOpacity());
  }

  private PlacedMessageLabel placeMessageLabel(LaidOutEdge edge) {
    if (edge.label() == null || edge.label().isEmpty()) {
      return null;
    }
    LabelPoint point = labelPoint(edge);
    return new PlacedMessageLabel(
        edge.label(),
        point.x(),
        point.y(),
        labelBox(point.x(), point.y(), "middle", edge.label(), base.fontSize()));
  }

  /** The glyph box of a start-anchored label, or null when there are no glyphs to grow around. */
  private LabelBox placeStartLabel(double x, double y, String text) {
    return text == null || text.isEmpty() ? null : labelBox(x, y, "start", text, base.fontSize());
  }

  /** The glyph box of a middle-anchored label, or null when there are no glyphs. */
  private LabelBox placeCentredLabel(double x, double y, String text) {
    return text == null || text.isEmpty() ? null : labelBox(x, y, "middle", text, base.fontSize());
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

  private PlacedDeleteMarker placeDeleteMarker(LaidOutEdge edge) {
    ResolvedEdgeStyle paint = edgePaint(edge.id());
    double strokeWidth = Math.max(1.5, paint.strokeWidth());
    LaidOutNode target = nodesById.get(edge.target());
    if (target != null) {
      // Inscribe the X in the destruction node's own box (same convention the gate circle uses)
      // rather than shrinking it below that box: layout anchors the deleteMessage edge at the
      // node's real boundary, so a smaller marker leaves the incoming arrow stopping short of the X
      // instead of touching it.
      return new PlacedDeleteMarker(
          target.id(),
          paint,
          target.x() + target.width() / 2.0,
          target.y() + target.height() / 2.0,
          Math.max(4.0, Math.min(target.width(), target.height()) / 2.0),
          strokeWidth);
    }
    Point point = edge.points().isEmpty() ? new Point(0.0, 0.0) : edge.points().getLast();
    return new PlacedDeleteMarker(edge.target(), paint, point.x(), point.y(), 10.0, strokeWidth);
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

    SvgBounds content = SvgBounds.empty();
    for (UmlSequenceModel.SequenceOperand operand : operandsFor(fragment)) {
      SvgBounds operandContent = operandContentBox(operand, frames, visiting);
      if (!operandContent.isEmpty()) {
        content.includeRect(
            operandContent.minX(),
            operandContent.minY(),
            operandContent.width(),
            operandContent.height());
      }
    }
    includeCoveredLifelineExtents(content, fragment);
    SequenceFrame frame = content.isEmpty() ? null : clipToInteractionFrame(fragment, content);
    if (frame != null) {
      frames.put(fragment.id(), frame);
    }
    visiting.remove(fragment.id());
    return frame;
  }

  private SvgBounds operandContentBox(
      UmlSequenceModel.SequenceOperand operand,
      Map<String, SequenceFrame> frames,
      Set<String> visiting) {
    SvgBounds content = SvgBounds.empty();
    for (String fragmentId : operand.fragmentIds()) {
      LaidOutEdge edge = edgesById.get(fragmentId);
      if (edge != null) {
        for (Point point : edge.points()) {
          content.includePoint(point.x(), point.y());
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
        content.includeRect(
            nestedFrame.x(), nestedFrame.y(), nestedFrame.width(), nestedFrame.height());
      }
    }
    return content;
  }

  private void includeCoveredLifelineExtents(
      SvgBounds content, UmlSequenceModel.SequenceCombinedFragment fragment) {
    if (content.isEmpty()) {
      return;
    }
    // Reach out to each covered lifeline's own left and right edge without moving the vertical
    // extent: the y values handed back in are the ones already folded, so only x can grow.
    for (String lifelineId : fragment.coveredLifelineIds()) {
      LaidOutNode lifeline = nodesById.get(lifelineId);
      if (lifeline == null) {
        continue;
      }
      double minY = content.minY();
      double maxY = content.maxY();
      content.includePoint(lifeline.x(), minY);
      content.includePoint(lifeline.x() + lifeline.width(), maxY);
    }
  }

  private SequenceFrame clipToInteractionFrame(
      UmlSequenceModel.SequenceCombinedFragment fragment, SvgBounds content) {
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
    SvgBounds content = SvgBounds.empty();
    for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
      if (belongsToInteraction(lifeline, interactionId)) {
        includeNode(content, lifeline.node());
      }
    }
    for (UmlSequenceModel.SequenceNode execution : model.executions()) {
      if (belongsToInteraction(execution, interactionId)) {
        includeNode(content, execution.node());
      }
    }
    for (UmlSequenceModel.SequenceNode gate : model.gates()) {
      if (belongsToInteraction(gate, interactionId)) {
        includeNode(content, gate.node());
      }
    }
    for (UmlSequenceModel.SequenceNode destruction : model.destructions()) {
      if (belongsToInteraction(destruction, interactionId)) {
        includeNode(content, destruction.node());
      }
    }
    for (UmlSequenceModel.SequenceMessage message : model.messages()) {
      if (!belongsToInteraction(message, interactionId)) {
        continue;
      }
      for (Point point : message.edge().points()) {
        content.includePoint(point.x(), point.y());
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

  private double separatorY(SequenceFrame frame, SvgBounds previous, SvgBounds current) {
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

  private static void includeNode(SvgBounds bounds, LaidOutNode node) {
    bounds.includeRect(node.x(), node.y(), node.width(), node.height());
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

  private static double labelWidth(String label, double fontSize) {
    return Svg.estimateTextWidth(label, fontSize);
  }

  private record LabelPoint(double x, double y) {}
}

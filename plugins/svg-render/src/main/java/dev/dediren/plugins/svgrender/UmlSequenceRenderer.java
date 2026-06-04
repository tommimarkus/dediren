package dev.dediren.plugins.svgrender;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgBackgroundStyle;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.contracts.render.SvgEdgeStyle;
import dev.dediren.contracts.render.SvgFontStyle;
import dev.dediren.contracts.render.SvgNodeStyle;
import dev.dediren.contracts.render.SvgStylePolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class UmlSequenceRenderer {
    private static final String DASH_PATTERN = "8 5";
    private static final double INTERACTION_HORIZONTAL_PADDING = 48.0;
    private static final double INTERACTION_TOP_PADDING = 40.0;
    private static final double INTERACTION_BOTTOM_PADDING = 48.0;

    private final LayoutResult result;
    private final RenderPolicy policy;
    private final SequenceStyle base;
    private final UmlSequenceModel model;
    private final Map<String, LaidOutNode> nodesById;
    private final Map<String, SequenceFrame> interactionFrames;

    UmlSequenceRenderer(LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
        this.result = result;
        this.policy = policy;
        this.base = SequenceStyle.from(policy);
        this.model = UmlSequenceModel.from(result, metadata);
        this.nodesById = nodesById(result.nodes());
        this.interactionFrames = interactionFrames();
    }

    static boolean isSequence(RenderMetadata metadata) {
        if (metadata == null || !"uml".equals(metadata.semanticProfile())) {
            return false;
        }
        boolean hasLifeline = metadata.nodes().values().stream()
                .anyMatch(selector -> "Lifeline".equals(selector.type()));
        boolean hasMessage = metadata.edges().values().stream()
                .anyMatch(selector -> "Message".equals(selector.type()));
        return hasLifeline || hasMessage;
    }

    String render() {
        SvgBox bounds = bounds().padded(policy);
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(
                Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" viewBox=\"%.1f %.1f %.1f %.1f\">",
                bounds.width(),
                bounds.height(),
                bounds.minX(),
                bounds.minY(),
                bounds.width(),
                bounds.height()));
        svg.append(String.format(
                Locale.ROOT,
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\"/>",
                bounds.minX(),
                bounds.minY(),
                bounds.width(),
                bounds.height(),
                attr(base.backgroundFill())));
        svg.append("<g font-family=\"").append(attr(base.fontFamily()))
                .append("\" font-size=\"").append(styleNumber(base.fontSize())).append("\">");

        renderInteractions(svg);
        renderLifelineHeads(svg);
        renderLifelineStems(svg);
        renderExecutions(svg);
        renderGates(svg);
        renderMessages(svg);
        renderDeleteMarkers(svg);

        svg.append("</g></svg>\n");
        return svg.toString();
    }

    private void renderInteractions(StringBuilder svg) {
        for (UmlSequenceModel.SequenceNode interaction : model.interactions()) {
            LaidOutNode node = interaction.node();
            SequenceFrame frame = interactionFrame(interaction);
            NodePaint paint = nodePaint(node.id(), interaction.selector().type());
            double titleWidth = Math.max(
                    96.0,
                    Math.min(frame.width() * 0.5, labelWidth(node.label(), base.fontSize()) + 24.0));
            double titleHeight = Math.max(24.0, base.fontSize() + 10.0);
            svg.append("<g data-dediren-node-id=\"").append(attr(node.id()))
                    .append("\" data-dediren-node-type=\"Interaction\"")
                    .append(" data-dediren-sequence-interaction=\"true\">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect data-dediren-node-shape=\"uml_interaction\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    frame.x(),
                    frame.y(),
                    frame.width(),
                    frame.height(),
                    styleNumber(paint.rx()),
                    attr(paint.fill()),
                    attr(paint.stroke()),
                    styleNumber(paint.strokeWidth())));
            svg.append(String.format(
                    Locale.ROOT,
                    "<path data-dediren-sequence-interaction-title=\"true\" d=\"M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    frame.x(),
                    frame.y(),
                    frame.x() + titleWidth,
                    frame.x() + titleWidth - 12.0,
                    frame.y() + titleHeight,
                    frame.y() + titleHeight,
                    frame.x(),
                    attr(paint.fill()),
                    attr(paint.stroke()),
                    styleNumber(paint.strokeWidth())));
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\">%s</text>",
                    frame.x() + 10.0,
                    frame.y() + titleHeight - 8.0,
                    attr(paint.labelFill()),
                    text(node.label())));
            svg.append("</g>");
        }
    }

    private void renderLifelineHeads(StringBuilder svg) {
        for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
            LaidOutNode node = lifeline.node();
            NodePaint paint = nodePaint(node.id(), lifeline.selector().type());
            svg.append("<g data-dediren-node-id=\"").append(attr(node.id()))
                    .append("\" data-dediren-node-type=\"Lifeline\"")
                    .append(" data-dediren-sequence-lifeline=\"true\">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect data-dediren-node-shape=\"uml_lifeline\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    node.x(),
                    node.y(),
                    node.width(),
                    node.height(),
                    styleNumber(Math.max(paint.rx(), 2.0)),
                    attr(paint.fill()),
                    attr(paint.stroke()),
                    styleNumber(paint.strokeWidth())));
            svg.append("<g data-dediren-node-decorator=\"uml_lifeline\"></g>");
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\">%s</text>",
                    node.x() + node.width() / 2.0,
                    node.y() + node.height() / 2.0 + base.fontSize() / 3.0,
                    attr(paint.labelFill()),
                    text(node.label())));
            svg.append("</g>");
        }
    }

    private void renderLifelineStems(StringBuilder svg) {
        for (UmlSequenceModel.SequenceNode lifeline : model.lifelines()) {
            LaidOutNode node = lifeline.node();
            NodePaint paint = nodePaint(node.id(), lifeline.selector().type());
            double x = node.x() + node.width() / 2.0;
            double bottom = stemBottom(lifeline);
            svg.append(String.format(
                    Locale.ROOT,
                    "<line data-dediren-sequence-lifeline-stem=\"%s\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\" stroke-dasharray=\"%s\"/>",
                    attr(node.id()),
                    x,
                    node.y() + node.height(),
                    x,
                    bottom,
                    attr(paint.stroke()),
                    styleNumber(paint.strokeWidth()),
                    DASH_PATTERN));
        }
    }

    private void renderExecutions(StringBuilder svg) {
        for (UmlSequenceModel.SequenceNode execution : model.executions()) {
            LaidOutNode node = execution.node();
            NodePaint paint = nodePaint(node.id(), execution.selector().type());
            svg.append("<g data-dediren-node-id=\"").append(attr(node.id()))
                    .append("\" data-dediren-node-type=\"ExecutionSpecification\">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect data-dediren-node-shape=\"uml_execution_specification\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    node.x(),
                    node.y(),
                    node.width(),
                    node.height(),
                    styleNumber(Math.max(0.0, paint.rx())),
                    attr(paint.fill()),
                    attr(paint.stroke()),
                    styleNumber(paint.strokeWidth())));
            svg.append("<g data-dediren-node-decorator=\"uml_execution_specification\"></g></g>");
        }
    }

    private void renderGates(StringBuilder svg) {
        for (UmlSequenceModel.SequenceNode gate : model.gates()) {
            LaidOutNode node = gate.node();
            NodePaint paint = nodePaint(node.id(), gate.selector().type());
            double radius = Math.max(4.0, Math.min(node.width(), node.height()) / 2.0);
            svg.append("<g data-dediren-node-id=\"").append(attr(node.id()))
                    .append("\" data-dediren-node-type=\"Gate\">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<circle data-dediren-node-shape=\"uml_gate\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    node.x() + node.width() / 2.0,
                    node.y() + node.height() / 2.0,
                    radius,
                    attr(paint.fill()),
                    attr(paint.stroke()),
                    styleNumber(paint.strokeWidth())));
            svg.append("<g data-dediren-node-decorator=\"uml_gate\"></g></g>");
        }
    }

    private void renderMessages(StringBuilder svg) {
        for (UmlSequenceModel.SequenceMessage message : model.messages()) {
            EdgePaint paint = edgePaint(message.edge().id(), message.selector().type());
            MessageAppearance appearance = MessageAppearance.from(message.messageSort(), paint);
            svg.append("<g data-dediren-edge-id=\"").append(attr(message.edge().id()))
                    .append("\" data-dediren-sequence-message-sort=\"")
                    .append(attr(message.messageSort())).append("\">");
            svg.append(edgeMarker(message.edge(), appearance.markerEnd(), appearance.stroke(), "end"));
            svg.append(edgePath(message.edge(), appearance));
            if (message.edge().label() != null && !message.edge().label().isEmpty()) {
                svg.append(messageLabel(message.edge(), appearance));
            }
            svg.append("</g>");
        }
    }

    private void renderDeleteMarkers(StringBuilder svg) {
        for (UmlSequenceModel.SequenceMessage message : model.messages()) {
            if (!"deleteMessage".equals(message.messageSort())) {
                continue;
            }
            MarkerPoint point = deleteMarkerPoint(message.edge());
            EdgePaint paint = edgePaint(message.edge().id(), message.selector().type());
            double size = point.size();
            svg.append("<g data-dediren-sequence-delete-marker=\"").append(attr(point.id())).append("\">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    point.x() - size,
                    point.y() - size,
                    point.x() + size,
                    point.y() + size,
                    attr(paint.stroke()),
                    styleNumber(Math.max(1.5, paint.strokeWidth()))));
            svg.append(String.format(
                    Locale.ROOT,
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    point.x() - size,
                    point.y() + size,
                    point.x() + size,
                    point.y() - size,
                    attr(paint.stroke()),
                    styleNumber(Math.max(1.5, paint.strokeWidth()))));
            svg.append("</g>");
        }
    }

    private String edgeMarker(LaidOutEdge edge, SvgEdgeMarkerEnd marker, String stroke, String side) {
        if (marker == SvgEdgeMarkerEnd.NONE) {
            return "";
        }
        String markerName = marker.name().toLowerCase(Locale.ROOT);
        String id = "marker-" + side + "-" + edge.id();
        String attribute = "data-dediren-edge-marker-" + side;
        String fill = marker == SvgEdgeMarkerEnd.OPEN_ARROW ? "none" : stroke;
        String body = marker == SvgEdgeMarkerEnd.OPEN_ARROW
                ? "<path d=\"M 1 1 L 9 5 L 1 9\" fill=\"none\" stroke=\"" + attr(stroke)
                + "\" stroke-width=\"1.5\"/>"
                : "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\"" + attr(fill)
                + "\" stroke=\"" + attr(stroke) + "\" stroke-width=\"1\"/>";
        return "<marker id=\"" + attr(id) + "\" " + attribute + "=\"" + markerName
                + "\" markerWidth=\"10\" markerHeight=\"10\" refX=\"5\" refY=\"5\" orient=\"auto\">"
                + body + "</marker>";
    }

    private String edgePath(LaidOutEdge edge, MessageAppearance appearance) {
        if (edge.points().isEmpty()) {
            return "";
        }
        String dash = appearance.lineStyle() == SvgEdgeLineStyle.DASHED
                ? " stroke-dasharray=\"" + DASH_PATTERN + "\""
                : "";
        String markerEnd = appearance.markerEnd() == SvgEdgeMarkerEnd.NONE
                ? ""
                : " marker-end=\"url(#marker-end-" + attr(edge.id()) + ")\"";
        return "<path d=\"" + attr(pathData(edge.points())) + "\" fill=\"none\" stroke=\""
                + attr(appearance.stroke()) + "\" stroke-width=\"" + styleNumber(appearance.strokeWidth()) + "\""
                + " stroke-linecap=\"round\" stroke-linejoin=\"round\""
                + dash + markerEnd + "/>";
    }

    private String messageLabel(LaidOutEdge edge, MessageAppearance appearance) {
        LabelPoint point = labelPoint(edge);
        return String.format(
                Locale.ROOT,
                "<text data-dediren-sequence-message-label=\"%s\" x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"%s\" font-weight=\"600\">%s</text>",
                attr(edge.id()),
                point.x(),
                point.y(),
                attr(appearance.labelFill()),
                styleNumber(base.fontSize()),
                text(edge.label()));
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
            return new MarkerPoint(
                    target.id(),
                    target.x() + target.width() / 2.0,
                    target.y() + target.height() / 2.0,
                    Math.max(8.0, Math.min(target.width(), target.height()) * 0.32));
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
                box = box.includeRect(label.x() - width / 2.0, label.y() - base.fontSize(), width, base.fontSize() * 1.25);
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
        double right = Math.max(node.x() + node.width(), content.maxX() + INTERACTION_HORIZONTAL_PADDING);
        double bottom = Math.max(node.y() + node.height(), content.maxY() + INTERACTION_BOTTOM_PADDING);
        return new SequenceFrame(left, top, right - left, bottom - top);
    }

    private SequenceFrame interactionFrame(UmlSequenceModel.SequenceNode interaction) {
        return interactionFrames.getOrDefault(
                sequenceInteractionId(interaction),
                new SequenceFrame(
                        interaction.node().x(),
                        interaction.node().y(),
                        interaction.node().width(),
                        interaction.node().height()));
    }

    private double stemBottom(UmlSequenceModel.SequenceNode lifeline) {
        String interactionId = propertyText(lifeline.selector().properties(), "interaction");
        SequenceFrame frame = interactionId == null ? null : interactionFrames.get(interactionId);
        if (frame == null && interactionFrames.size() == 1) {
            frame = interactionFrames.values().iterator().next();
        }
        return frame == null ? diagramBottom() : frame.bottom();
    }

    private boolean belongsToInteraction(UmlSequenceModel.SequenceNode node, String interactionId) {
        return belongsToInteraction(node.selector().properties(), interactionId);
    }

    private boolean belongsToInteraction(UmlSequenceModel.SequenceMessage message, String interactionId) {
        return belongsToInteraction(message.selector().properties(), interactionId);
    }

    private boolean belongsToInteraction(JsonNode properties, String interactionId) {
        String candidate = propertyText(properties, "interaction");
        return interactionId.equals(candidate) || (candidate == null && model.interactions().size() == 1);
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

    private NodePaint nodePaint(String nodeId, String type) {
        SvgStylePolicy style = policy.style();
        NodePaint paint = new NodePaint("#ffffff", "#000000", 1.25, 0.0, "#000000");
        paint = paint.merge(style == null ? null : style.node());
        paint = paint.merge(style == null ? null : style.nodeTypeOverrides().get(type));
        paint = paint.merge(style == null ? null : style.nodeOverrides().get(nodeId));
        return paint;
    }

    private EdgePaint edgePaint(String edgeId, String type) {
        SvgStylePolicy style = policy.style();
        EdgePaint paint = new EdgePaint("#000000", 1.25, "#000000");
        paint = paint.merge(style == null ? null : style.edge());
        paint = paint.merge(style == null ? null : style.edgeTypeOverrides().get(type));
        paint = paint.merge(style == null ? null : style.edgeOverrides().get(edgeId));
        return paint;
    }

    private static Map<String, LaidOutNode> nodesById(List<LaidOutNode> nodes) {
        Map<String, LaidOutNode> byId = new HashMap<>();
        for (LaidOutNode node : nodes) {
            byId.put(node.id(), node);
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
        return (label == null ? 0 : label.length()) * fontSize * 0.56;
    }

    private static String styleNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private static String attr(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String text(String value) {
        return attr(value);
    }

    private record SequenceStyle(String backgroundFill, String fontFamily, double fontSize) {
        static SequenceStyle from(RenderPolicy policy) {
            SvgStylePolicy style = policy.style();
            return new SequenceStyle(
                    Optional.ofNullable(style)
                            .map(SvgStylePolicy::background)
                            .map(SvgBackgroundStyle::fill)
                            .orElse("#ffffff"),
                    Optional.ofNullable(style)
                            .map(SvgStylePolicy::font)
                            .map(SvgFontStyle::family)
                            .orElse("Inter, Arial, sans-serif"),
                    Optional.ofNullable(style)
                            .map(SvgStylePolicy::font)
                            .map(SvgFontStyle::size)
                            .orElse(14.0));
        }
    }

    private record NodePaint(String fill, String stroke, double strokeWidth, double rx, String labelFill) {
        NodePaint merge(SvgNodeStyle override) {
            if (override == null) {
                return this;
            }
            return new NodePaint(
                    override.fill() == null ? fill : override.fill(),
                    override.stroke() == null ? stroke : override.stroke(),
                    override.strokeWidth() == null ? strokeWidth : override.strokeWidth(),
                    override.rx() == null ? rx : override.rx(),
                    override.labelFill() == null ? labelFill : override.labelFill());
        }
    }

    private record EdgePaint(String stroke, double strokeWidth, String labelFill) {
        EdgePaint merge(SvgEdgeStyle override) {
            if (override == null) {
                return this;
            }
            return new EdgePaint(
                    override.stroke() == null ? stroke : override.stroke(),
                    override.strokeWidth() == null ? strokeWidth : override.strokeWidth(),
                    override.labelFill() == null ? labelFill : override.labelFill());
        }
    }

    private record MessageAppearance(
            String stroke,
            double strokeWidth,
            String labelFill,
            SvgEdgeLineStyle lineStyle,
            SvgEdgeMarkerEnd markerEnd) {
        static MessageAppearance from(String messageSort, EdgePaint paint) {
            SvgEdgeLineStyle lineStyle = "reply".equals(messageSort)
                    ? SvgEdgeLineStyle.DASHED
                    : SvgEdgeLineStyle.SOLID;
            SvgEdgeMarkerEnd markerEnd = switch (messageSort) {
                case "asynchCall", "asynchSignal", "reply", "createMessage" -> SvgEdgeMarkerEnd.OPEN_ARROW;
                case "deleteMessage" -> SvgEdgeMarkerEnd.NONE;
                default -> SvgEdgeMarkerEnd.FILLED_ARROW;
            };
            return new MessageAppearance(paint.stroke(), paint.strokeWidth(), paint.labelFill(), lineStyle, markerEnd);
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
            return new SvgBox(Math.min(minX, x), Math.min(minY, y), Math.max(maxX, x), Math.max(maxY, y), false);
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

    private record LabelPoint(double x, double y) {
    }

    private record MarkerPoint(String id, double x, double y, double size) {
    }

    private record SequenceFrame(double x, double y, double width, double height) {
        double bottom() {
            return y + height;
        }
    }
}

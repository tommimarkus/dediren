package dev.dediren.plugins.svgrender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.render.SvgBackgroundStyle;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalSide;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalSide;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.contracts.render.SvgEdgeStyle;
import dev.dediren.contracts.render.SvgFontStyle;
import dev.dediren.contracts.render.SvgGroupStyle;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.contracts.render.SvgNodeStyle;
import dev.dediren.contracts.render.SvgStylePolicy;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class Main {
    private Main() {
    }

    public static String moduleName() {
        return "svg-render";
    }

    public static void main(String[] args) throws Exception {
        int exitCode = execute(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static PluginResult executeForTesting(String[] args, String stdin) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = execute(
                args,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new PluginResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static int execute(String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr)
            throws Exception {
        if (args.length > 0 && args[0].equals("capabilities")) {
            stdout.println(capabilitiesJson());
            return 0;
        }
        if (args.length > 0 && args[0].equals("render")) {
            return renderFromStdin(stdin, stdout);
        }
        stderr.println("expected command: capabilities or render");
        return 2;
    }

    private static String capabilitiesJson() throws IOException {
        ObjectNode root = JsonSupport.objectMapper().createObjectNode();
        root.put("plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION);
        root.put("id", "svg-render");
        root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("render"));
        root.putObject("runtime").put("artifact_kind", "svg");
        return JsonSupport.objectMapper().writeValueAsString(root);
    }

    private static int renderFromStdin(InputStream stdin, PrintStream stdout) throws Exception {
        RenderInput input = JsonSupport.objectMapper().readValue(stdin.readAllBytes(), RenderInput.class);
        try {
            validateRenderPolicy(input.policy());
            validateRenderMetadataUsage(input.policy(), input.renderMetadata());
            validateArchimatePolicyTypes(input.policy());
            validateArchimateRenderMetadata(input.layoutResult(), input.renderMetadata());
            validateUmlPolicyTypes(input.policy());
            validateUmlRenderMetadata(input.layoutResult(), input.renderMetadata());
        } catch (PolicyValidationException error) {
            return exitWithDiagnostic(stdout, "DEDIREN_SVG_POLICY_INVALID", error.getMessage(), error.path());
        } catch (RenderMetadataUsageException error) {
            return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
        } catch (ArchimateTypeValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        } catch (UmlValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        }

        String content = renderSvg(input.layoutResult(), input.renderMetadata(), input.policy());
        var result = new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, "svg", content);
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
        return 0;
    }

    private static int exitWithDiagnostic(PrintStream stdout, String code, String message, String path)
            throws IOException {
        var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
        return 3;
    }

    private static void validateRenderMetadataUsage(RenderPolicy policy, RenderMetadata metadata)
            throws RenderMetadataUsageException {
        SvgStylePolicy style = policy.style();
        boolean usesTypeOverrides = style != null
                && (!style.nodeTypeOverrides().isEmpty()
                || !style.edgeTypeOverrides().isEmpty()
                || !style.groupTypeOverrides().isEmpty());
        if (!usesTypeOverrides) {
            return;
        }
        if (policy.semanticProfile() == null) {
            throw new RenderMetadataUsageException(
                    "DEDIREN_RENDER_METADATA_PROFILE_REQUIRED",
                    "semantic_profile",
                    "type-aware SVG render policies must declare semantic_profile");
        }
        if (metadata == null) {
            throw new RenderMetadataUsageException(
                    "DEDIREN_RENDER_METADATA_REQUIRED",
                    "render_metadata",
                    "type-aware SVG render policy requires render metadata");
        }
        if (!policy.semanticProfile().equals(metadata.semanticProfile())) {
            throw new RenderMetadataUsageException(
                    "DEDIREN_RENDER_METADATA_PROFILE_MISMATCH",
                    "render_metadata.semantic_profile",
                    "render metadata profile " + metadata.semanticProfile()
                            + " does not match policy profile " + policy.semanticProfile());
        }
    }

    private static void validateArchimatePolicyTypes(RenderPolicy policy)
            throws ArchimateTypeValidationException {
        if (!"archimate".equals(policy.semanticProfile()) || policy.style() == null) {
            return;
        }
        for (String type : policy.style().nodeTypeOverrides().keySet()) {
            Archimate.validateElementType(type, "policy.style.node_type_overrides." + type);
        }
        for (String type : policy.style().edgeTypeOverrides().keySet()) {
            Archimate.validateRelationshipType(type, "policy.style.edge_type_overrides." + type);
        }
        for (String type : policy.style().groupTypeOverrides().keySet()) {
            Archimate.validateElementType(type, "policy.style.group_type_overrides." + type);
        }
    }

    private static void validateArchimateRenderMetadata(LayoutResult layout, RenderMetadata metadata)
            throws ArchimateTypeValidationException {
        if (metadata == null || !"archimate".equals(metadata.semanticProfile())) {
            return;
        }
        for (Map.Entry<String, RenderMetadataSelector> entry : metadata.nodes().entrySet()) {
            Archimate.validateElementType(entry.getValue().type(), "render_metadata.nodes." + entry.getKey() + ".type");
        }
        for (Map.Entry<String, RenderMetadataSelector> entry : metadata.edges().entrySet()) {
            Archimate.validateRelationshipType(entry.getValue().type(), "render_metadata.edges." + entry.getKey() + ".type");
        }
        for (Map.Entry<String, RenderMetadataSelector> entry : metadata.groups().entrySet()) {
            Archimate.validateElementType(entry.getValue().type(), "render_metadata.groups." + entry.getKey() + ".type");
        }
        for (LaidOutEdge edge : layout.edges()) {
            RenderMetadataSelector edgeSelector = metadata.edges().get(edge.id());
            RenderMetadataSelector sourceSelector = metadata.nodes().get(edge.source());
            RenderMetadataSelector targetSelector = metadata.nodes().get(edge.target());
            if (edgeSelector != null && sourceSelector != null && targetSelector != null) {
                Archimate.validateRelationshipEndpointTypes(
                        edgeSelector.type(),
                        sourceSelector.type(),
                        targetSelector.type(),
                        "render_metadata.edges." + edge.id());
            }
        }
    }

    private static void validateUmlPolicyTypes(RenderPolicy policy) throws UmlValidationException {
        if (!"uml".equals(policy.semanticProfile()) || policy.style() == null) {
            return;
        }
        for (String type : policy.style().nodeTypeOverrides().keySet()) {
            Uml.validateElementType(type, "policy.style.node_type_overrides." + type);
        }
        for (String type : policy.style().edgeTypeOverrides().keySet()) {
            Uml.validateRelationshipType(type, "policy.style.edge_type_overrides." + type);
        }
        for (String type : policy.style().groupTypeOverrides().keySet()) {
            Uml.validateElementType(type, "policy.style.group_type_overrides." + type);
        }
    }

    private static void validateUmlRenderMetadata(LayoutResult layout, RenderMetadata metadata)
            throws UmlValidationException {
        if (metadata == null || !"uml".equals(metadata.semanticProfile())) {
            return;
        }
        for (Map.Entry<String, RenderMetadataSelector> entry : metadata.nodes().entrySet()) {
            Uml.validateElementType(entry.getValue().type(), "render_metadata.nodes." + entry.getKey() + ".type");
        }
        for (Map.Entry<String, RenderMetadataSelector> entry : metadata.edges().entrySet()) {
            Uml.validateRelationshipType(entry.getValue().type(), "render_metadata.edges." + entry.getKey() + ".type");
        }
        for (Map.Entry<String, RenderMetadataSelector> entry : metadata.groups().entrySet()) {
            Uml.validateElementType(entry.getValue().type(), "render_metadata.groups." + entry.getKey() + ".type");
        }
        for (LaidOutEdge edge : layout.edges()) {
            RenderMetadataSelector edgeSelector = metadata.edges().get(edge.id());
            RenderMetadataSelector sourceSelector = metadata.nodes().get(edge.source());
            RenderMetadataSelector targetSelector = metadata.nodes().get(edge.target());
            if (edgeSelector != null && sourceSelector != null && targetSelector != null) {
                Uml.validateRelationshipEndpointTypes(
                        edgeSelector.type(),
                        sourceSelector.type(),
                        targetSelector.type(),
                        "render_metadata.edges." + edge.id());
            }
        }
    }

    private static void validateRenderPolicy(RenderPolicy policy) throws PolicyValidationException {
        SvgStylePolicy style = policy.style();
        if (style == null) {
            return;
        }
        validateBackgroundStyle(style.background(), "style.background");
        validateFontStyle(style.font(), "style.font");
        validateNodeStyle(style.node(), "style.node");
        validateEdgeStyle(style.edge(), "style.edge");
        validateGroupStyle(style.group(), "style.group");
        for (Map.Entry<String, SvgNodeStyle> entry : style.nodeTypeOverrides().entrySet()) {
            validateNodeStyle(entry.getValue(), "style.node_type_overrides." + entry.getKey());
        }
        for (Map.Entry<String, SvgEdgeStyle> entry : style.edgeTypeOverrides().entrySet()) {
            validateEdgeStyle(entry.getValue(), "style.edge_type_overrides." + entry.getKey());
        }
        for (Map.Entry<String, SvgGroupStyle> entry : style.groupTypeOverrides().entrySet()) {
            validateGroupStyle(entry.getValue(), "style.group_type_overrides." + entry.getKey());
        }
        for (Map.Entry<String, SvgNodeStyle> entry : style.nodeOverrides().entrySet()) {
            validateNodeStyle(entry.getValue(), "style.node_overrides." + entry.getKey());
        }
        for (Map.Entry<String, SvgEdgeStyle> entry : style.edgeOverrides().entrySet()) {
            validateEdgeStyle(entry.getValue(), "style.edge_overrides." + entry.getKey());
        }
        for (Map.Entry<String, SvgGroupStyle> entry : style.groupOverrides().entrySet()) {
            validateGroupStyle(entry.getValue(), "style.group_overrides." + entry.getKey());
        }
    }

    private static void validateBackgroundStyle(SvgBackgroundStyle style, String path)
            throws PolicyValidationException {
        if (style != null) {
            validateColor(style.fill(), path + ".fill");
        }
    }

    private static void validateFontStyle(SvgFontStyle style, String path) throws PolicyValidationException {
        if (style == null) {
            return;
        }
        if (style.family() != null) {
            int length = style.family().codePointCount(0, style.family().length());
            if (length < 1 || length > 120) {
                throw new PolicyValidationException(path + ".family", "SVG render policy " + path
                        + ".family length is outside the allowed range");
            }
        }
        validateNumber(style.size(), path + ".size", Bound.EXCLUSIVE_MIN, 0.0, 96.0);
    }

    private static void validateNodeStyle(SvgNodeStyle style, String path) throws PolicyValidationException {
        if (style == null) {
            return;
        }
        validateColor(style.fill(), path + ".fill");
        validateColor(style.stroke(), path + ".stroke");
        validateNumber(style.strokeWidth(), path + ".stroke_width", Bound.MIN, 0.0, 24.0);
        validateNumber(style.rx(), path + ".rx", Bound.MIN, 0.0, 80.0);
        validateColor(style.labelFill(), path + ".label_fill");
    }

    private static void validateEdgeStyle(SvgEdgeStyle style, String path) throws PolicyValidationException {
        if (style == null) {
            return;
        }
        validateColor(style.stroke(), path + ".stroke");
        validateNumber(style.strokeWidth(), path + ".stroke_width", Bound.MIN, 0.0, 24.0);
        validateColor(style.labelFill(), path + ".label_fill");
    }

    private static void validateGroupStyle(SvgGroupStyle style, String path) throws PolicyValidationException {
        if (style == null) {
            return;
        }
        validateColor(style.fill(), path + ".fill");
        validateColor(style.stroke(), path + ".stroke");
        validateNumber(style.strokeWidth(), path + ".stroke_width", Bound.MIN, 0.0, 24.0);
        validateNumber(style.rx(), path + ".rx", Bound.MIN, 0.0, 80.0);
        validateColor(style.labelFill(), path + ".label_fill");
        validateNumber(style.labelSize(), path + ".label_size", Bound.EXCLUSIVE_MIN, 0.0, 96.0);
    }

    private static void validateColor(String value, String path) throws PolicyValidationException {
        if (value == null) {
            return;
        }
        boolean valid = value.length() == 7 && value.charAt(0) == '#'
                && value.substring(1).chars().allMatch(character ->
                Character.digit(character, 16) >= 0);
        if (!valid) {
            throw new PolicyValidationException(
                    path,
                    "SVG render policy " + path + " must be a #RRGGBB hex color");
        }
    }

    private static void validateNumber(
            Double value,
            String path,
            Bound lowerBoundKind,
            double minimum,
            double maximum) throws PolicyValidationException {
        if (value == null) {
            return;
        }
        boolean lowerOk = lowerBoundKind == Bound.MIN ? value >= minimum : value > minimum;
        if (!Double.isFinite(value) || !lowerOk || value > maximum) {
            throw new PolicyValidationException(path, "SVG render policy " + path
                    + " is outside the allowed range");
        }
    }

    private static String renderSvg(LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
        ResolvedStyle base = baseStyle(policy);
        SvgBounds bounds = svgBounds(result, metadata, policy, base);
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
        for (LaidOutGroup group : result.groups()) {
            ResolvedGroupStyle style = groupStyle(policy, metadata, group.id(), base);
            svg.append("<g data-dediren-group-id=\"").append(attr(group.id())).append("\"");
            RenderMetadataSelector selector = metadata == null ? null : metadata.groups().get(group.id());
            if (selector != null) {
                svg.append(" data-dediren-group-type=\"").append(attr(selector.type()))
                        .append("\" data-dediren-group-source-id=\"").append(attr(selector.sourceId())).append("\"");
            }
            svg.append(">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    group.x(),
                    group.y(),
                    group.width(),
                    group.height(),
                    styleNumber(style.rx()),
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth())));
            svg.append(groupDecorator(group, style));
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"%s\">%s</text>",
                    group.x() + 8.0,
                    group.y() + style.labelSize() + 4.0,
                    attr(style.labelFill()),
                    styleNumber(style.labelSize()),
                    text(group.label())));
            svg.append("</g>");
        }
        List<LaidOutEdge> renderedEdges = new ArrayList<>();
        List<LabelBox> occupiedLabelBoxes = nodeObstacleBoxes(result);
        for (LaidOutEdge edge : result.edges()) {
            ResolvedEdgeStyle style = edgeStyle(policy, metadata, edge.id(), base);
            List<LineJump> lineJumps = lineJumps(edge, renderedEdges);
            svg.append("<g data-dediren-edge-id=\"").append(attr(edge.id())).append("\">");
            svg.append(edgeMarker(edge, style, "start"));
            svg.append(edgeMarker(edge, style, "end"));
            svg.append(lineJumpMasks(edge, lineJumps, base.backgroundFill()));
            svg.append(edgePath(edge, style, lineJumps));
            if (edge.label() != null && !edge.label().isEmpty()) {
                EdgeLabel label = edgeLabel(edge, style, occupiedLabelBoxes, base.fontSize());
                svg.append(String.format(
                        Locale.ROOT,
                        "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"%s\" paint-order=\"stroke\" stroke=\"%s\" stroke-width=\"4\">%s</text>",
                        label.x(),
                        label.y(),
                        attr(label.anchor()),
                        attr(style.labelFill()),
                        attr(base.backgroundFill()),
                        text(edge.label())));
                occupiedLabelBoxes.add(label.bounds());
            }
            svg.append("</g>");
            renderedEdges.add(edge);
        }
        for (LaidOutNode node : result.nodes()) {
            ResolvedNodeStyle style = nodeStyle(policy, metadata, node.id(), base);
            RenderMetadataSelector selector = metadata == null ? null : metadata.nodes().get(node.id());
            svg.append("<g data-dediren-node-id=\"").append(attr(node.id())).append("\">");
            svg.append(nodeShape(node, style));
            svg.append(nodeDecorator(node, style, selector));
            if (!umlDecoratorSuppliesNodeLabel(style.decorator())) {
                svg.append(String.format(
                        Locale.ROOT,
                        "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\">%s</text>",
                        node.x() + node.width() / 2.0,
                        node.y() + node.height() / 2.0 + base.fontSize() / 3.0,
                        attr(style.labelFill()),
                        text(node.label())));
            }
            svg.append("</g>");
        }
        svg.append("</g></svg>\n");
        return svg.toString();
    }

    private static String groupDecorator(LaidOutGroup group, ResolvedGroupStyle style) {
        if (style.decorator() != SvgNodeDecorator.ARCHIMATE_GROUPING) {
            return "";
        }
        double size = 22.0;
        double x = group.x() + group.width() - size - 6.0;
        double y = group.y() + 9.0;
        String body = String.format(
                Locale.ROOT,
                "<path data-dediren-icon-part=\"grouping\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                x,
                y,
                x + size,
                y,
                x + size,
                y + size * 0.72,
                x,
                y + size * 0.72,
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()));
        return "<g data-dediren-group-decorator=\"archimate_grouping\" data-dediren-icon-kind=\"grouping\""
                + " data-dediren-icon-size=\"22\">" + body + "</g>";
    }

    private static String nodeShape(LaidOutNode node, ResolvedNodeStyle style) {
        SvgNodeDecorator decorator = style.decorator();
        if (decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
                || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION) {
            double radius = Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
            String fill = decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION ? style.stroke() : style.fill();
            return String.format(
                    Locale.ROOT,
                    "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    decoratorName(decorator),
                    node.x() + node.width() / 2.0,
                    node.y() + node.height() / 2.0,
                    radius,
                    attr(fill),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
        }
        if (decorator != null && isUmlDecorator(decorator)) {
            return umlNodeShape(node, style, decorator);
        }
        String shapeName = "archimate_rectangle";
        double rx = 0.0;
        if (decorator == null) {
            rx = style.rx();
        } else if (isArchimateCutCornerRectangle(decorator)) {
            return archimateCutCornerShape(node, style);
        } else if (isArchimateRoundedRectangle(decorator)) {
            rx = Math.max(1.0, style.rx());
            shapeName = "archimate_rounded_rectangle";
        }
        return String.format(
                Locale.ROOT,
                "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                shapeName,
                node.x(),
                node.y(),
                node.width(),
                node.height(),
                styleNumber(rx),
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()));
    }

    private static String archimateCutCornerShape(LaidOutNode node, ResolvedNodeStyle style) {
        double corner = Math.max(8.0, Math.min(14.0, Math.min(node.width(), node.height()) * 0.14));
        return String.format(
                Locale.ROOT,
                "<path data-dediren-node-shape=\"archimate_cut_corner_rectangle\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                node.x() + corner,
                node.y(),
                node.x() + node.width() - corner,
                node.y(),
                node.x() + node.width(),
                node.y() + corner,
                node.x() + node.width(),
                node.y() + node.height() - corner,
                node.x() + node.width() - corner,
                node.y() + node.height(),
                node.x() + corner,
                node.y() + node.height(),
                node.x(),
                node.y() + node.height() - corner,
                node.x(),
                node.y() + corner,
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()));
    }

    private static String umlNodeShape(LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
        String shapeName = decoratorName(decorator);
        return switch (decorator) {
            case UML_INITIAL_NODE -> {
                double radius = Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
                yield String.format(
                        Locale.ROOT,
                        "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                        shapeName,
                        node.x() + node.width() / 2.0,
                        node.y() + node.height() / 2.0,
                        radius,
                        attr(style.fill()),
                        attr(style.stroke()),
                        styleNumber(style.strokeWidth()));
            }
            case UML_ACTIVITY_FINAL_NODE -> {
                double radius = Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
                double innerRadius = Math.max(3.0, radius * 0.48);
                yield String.format(
                        Locale.ROOT,
                        "<g data-dediren-node-shape=\"%s\"><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#ffffff\" stroke=\"%s\" stroke-width=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/></g>",
                        shapeName,
                        node.x() + node.width() / 2.0,
                        node.y() + node.height() / 2.0,
                        radius,
                        attr(style.stroke()),
                        styleNumber(style.strokeWidth()),
                        node.x() + node.width() / 2.0,
                        node.y() + node.height() / 2.0,
                        innerRadius,
                        attr(style.stroke()));
            }
            case UML_DECISION_NODE, UML_MERGE_NODE -> {
                double centerX = node.x() + node.width() / 2.0;
                double centerY = node.y() + node.height() / 2.0;
                yield String.format(
                        Locale.ROOT,
                        "<path data-dediren-node-shape=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                        shapeName,
                        centerX,
                        node.y(),
                        node.x() + node.width(),
                        centerY,
                        centerX,
                        node.y() + node.height(),
                        node.x(),
                        centerY,
                        attr(style.fill()),
                        attr(style.stroke()),
                        styleNumber(style.strokeWidth()));
            }
            case UML_FORK_NODE, UML_JOIN_NODE -> {
                boolean horizontal = node.width() >= node.height();
                double width = horizontal ? node.width() : Math.min(node.width(), 14.0);
                double height = horizontal ? Math.min(node.height(), 14.0) : node.height();
                double x = node.x() + (node.width() - width) / 2.0;
                double y = node.y() + (node.height() - height) / 2.0;
                yield String.format(
                        Locale.ROOT,
                        "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                        shapeName,
                        x,
                        y,
                        width,
                        height,
                        attr(style.fill()),
                        attr(style.stroke()),
                        styleNumber(style.strokeWidth()));
            }
            case UML_PACKAGE -> {
                double tabWidth = Math.max(40.0, Math.min(96.0, node.width() * 0.34));
                double tabHeight = Math.max(14.0, Math.min(24.0, node.height() * 0.18));
                yield String.format(
                        Locale.ROOT,
                        "<path data-dediren-node-shape=\"%s\" d=\"M %.1f %.1f H %.1f V %.1f H %.1f V %.1f H %.1f V %.1f H %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                        shapeName,
                        node.x(),
                        node.y(),
                        node.x() + tabWidth,
                        node.y() + tabHeight,
                        node.x() + node.width(),
                        node.y() + node.height(),
                        node.x(),
                        node.y() + tabHeight,
                        node.x(),
                        attr(style.fill()),
                        attr(style.stroke()),
                        styleNumber(style.strokeWidth()));
            }
            case UML_ACTION -> String.format(
                    Locale.ROOT,
                    "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    shapeName,
                    node.x(),
                    node.y(),
                    node.width(),
                    node.height(),
                    styleNumber(Math.max(style.rx(), 10.0)),
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
            default -> String.format(
                    Locale.ROOT,
                    "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    shapeName,
                    node.x(),
                    node.y(),
                    node.width(),
                    node.height(),
                    styleNumber(style.rx()),
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
        };
    }

    private static String nodeDecorator(LaidOutNode node, ResolvedNodeStyle style, RenderMetadataSelector selector) {
        SvgNodeDecorator decorator = style.decorator();
        if (decorator == null || decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
                || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION) {
            return "";
        }
        if (isUmlDecorator(decorator)) {
            return umlNodeDecorator(node, style, decorator, selector);
        }
        return archimateNodeDecorator(node, style, decorator);
    }

    private static String archimateNodeDecorator(LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
        String name = decoratorName(decorator);
        String kind = archimateIconKind(decorator);
        double size = 22.0;
        double x = node.x() + node.width() - size - 6.0;
        double y = node.y() + 9.0;
        String body = switch (decorator) {
            case ARCHIMATE_APPLICATION_COMPONENT -> String.format(
                    Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    x + size * 0.22,
                    y + size * 0.08,
                    size * 0.72,
                    size * 0.64,
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()),
                    x,
                    y + size * 0.2,
                    size * 0.28,
                    size * 0.18,
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()),
                    x,
                    y + size * 0.48,
                    size * 0.28,
                    size * 0.18,
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
            case ARCHIMATE_APPLICATION_SERVICE, ARCHIMATE_BUSINESS_SERVICE, ARCHIMATE_TECHNOLOGY_SERVICE ->
                    String.format(
                            Locale.ROOT,
                            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                            x,
                            y + size * 0.12,
                            size,
                            size * 0.5,
                            size * 0.18,
                            attr(style.fill()),
                            attr(style.stroke()),
                            styleNumber(style.strokeWidth()));
            case ARCHIMATE_BUSINESS_ACTOR -> String.format(
                    Locale.ROOT,
                    "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
                    x + size * 0.5,
                    y + size * 0.2,
                    size * 0.16,
                    size * 0.2,
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()),
                    x + size * 0.5,
                    y + size * 0.42,
                    x + size * 0.5,
                    y + size * 0.72,
                    x + size * 0.22,
                    y + size * 0.54,
                    x + size * 0.5,
                    y + size * 0.5,
                    x + size * 0.78,
                    y + size * 0.54,
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
            case ARCHIMATE_DATA_OBJECT, ARCHIMATE_BUSINESS_OBJECT -> String.format(
                    Locale.ROOT,
                    "<path data-dediren-icon-part=\"document-body\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"document-header\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    x,
                    y,
                    x + size * 0.92,
                    y,
                    x + size * 0.92,
                    y + size * 0.72,
                    x,
                    y + size * 0.72,
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()),
                    x,
                    y + size * 0.22,
                    x + size * 0.92,
                    y + size * 0.22,
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
            case ARCHIMATE_TECHNOLOGY_NODE -> String.format(
                    Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"node-3d-edges\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    x,
                    y + size * 0.12,
                    size * 0.82,
                    size * 0.62,
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()),
                    x + size * 0.64,
                    y,
                    x + size,
                    y,
                    x + size,
                    y + size * 0.58,
                    x + size * 0.82,
                    y + size * 0.12,
                    x + size,
                    y,
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()));
            default -> "<path data-dediren-icon-part=\"" + attr(kind)
                    + "\" d=\"M " + styleNumber(x) + " " + styleNumber(y)
                    + " L " + styleNumber(x + size) + " " + styleNumber(y)
                    + " L " + styleNumber(x + size * 0.5) + " " + styleNumber(y + size * 0.72)
                    + " Z\" fill=\"" + attr(style.fill()) + "\" stroke=\"" + attr(style.stroke())
                    + "\" stroke-width=\"" + styleNumber(style.strokeWidth()) + "\"/>";
        };
        return "<g data-dediren-node-decorator=\"" + attr(name)
                + "\" data-dediren-icon-kind=\"" + attr(kind)
                + "\" data-dediren-icon-size=\"22\">" + body + "</g>";
    }

    private static String umlNodeDecorator(
            LaidOutNode node,
            ResolvedNodeStyle style,
            SvgNodeDecorator decorator,
            RenderMetadataSelector selector) {
        String name = decoratorName(decorator);
        String body = "";
        if (umlDecoratorSuppliesNodeLabel(decorator)) {
            body = umlClassifierNotation(node, style, decorator, selector);
        } else if (decorator == SvgNodeDecorator.UML_PACKAGE) {
            body = String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
                    node.x() + 8.0,
                    node.y() + 16.0,
                    attr(style.labelFill()),
                    text(node.label()));
        }
        return "<g data-dediren-node-decorator=\"" + attr(name) + "\">" + body + "</g>";
    }

    private static String umlClassifierNotation(
            LaidOutNode node,
            ResolvedNodeStyle style,
            SvgNodeDecorator decorator,
            RenderMetadataSelector selector) {
        JsonNode properties = selector == null ? null : selector.properties();
        List<String> titleLines = new ArrayList<>();
        if (decorator == SvgNodeDecorator.UML_ENUMERATION) {
            titleLines.add("&#171;enumeration&#187;");
        } else if (decorator == SvgNodeDecorator.UML_INTERFACE) {
            titleLines.add("&#171;interface&#187;");
        } else if (decorator == SvgNodeDecorator.UML_DATA_TYPE) {
            titleLines.add("&#171;dataType&#187;");
        }
        titleLines.add(text(node.label()));

        List<String> attributeLines = decorator == SvgNodeDecorator.UML_ENUMERATION
                ? umlLiteralLines(properties)
                : umlAttributeLines(properties);
        List<String> operationLines = decorator == SvgNodeDecorator.UML_ENUMERATION
                ? List.of()
                : umlOperationLines(properties);
        double titleHeight = Math.max(28.0, titleLines.size() * 15.0 + 8.0);
        double attributeHeight = attributeLines.isEmpty() ? 0.0 : attributeLines.size() * 14.0 + 8.0;
        double firstSeparatorY = node.y() + titleHeight;
        double secondSeparatorY = firstSeparatorY + attributeHeight;

        StringBuilder svg = new StringBuilder();
        if (!attributeLines.isEmpty() || !operationLines.isEmpty()) {
            svg.append(String.format(
                    Locale.ROOT,
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    node.x(),
                    firstSeparatorY,
                    node.x() + node.width(),
                    firstSeparatorY,
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth())));
        }
        if (!operationLines.isEmpty()) {
            svg.append(String.format(
                    Locale.ROOT,
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    node.x(),
                    secondSeparatorY,
                    node.x() + node.width(),
                    secondSeparatorY,
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth())));
        }
        double y = node.y() + 15.0;
        for (String line : titleLines) {
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"12\">%s</text>",
                    node.x() + node.width() / 2.0,
                    y,
                    attr(style.labelFill()),
                    line));
            y += 15.0;
        }
        y = firstSeparatorY + 15.0;
        for (String line : attributeLines) {
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
                    node.x() + 8.0,
                    y,
                    attr(style.labelFill()),
                    text(line)));
            y += 14.0;
        }
        y = secondSeparatorY + 15.0;
        for (String line : operationLines) {
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
                    node.x() + 8.0,
                    y,
                    attr(style.labelFill()),
                    text(line)));
            y += 14.0;
        }
        return svg.toString();
    }

    private static List<String> umlAttributeLines(JsonNode properties) {
        JsonNode attributes = properties == null ? null : properties.get("attributes");
        if (attributes == null || !attributes.isArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode attribute : attributes) {
            String visibility = umlVisibilitySymbol(textField(attribute, "visibility", "public"));
            String name = textField(attribute, "name", "");
            String type = textField(attribute, "type", "");
            lines.add(type.isEmpty() ? visibility + " " + name : visibility + " " + name + " : " + type);
        }
        return lines;
    }

    private static List<String> umlOperationLines(JsonNode properties) {
        JsonNode operations = properties == null ? null : properties.get("operations");
        if (operations == null || !operations.isArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode operation : operations) {
            String visibility = umlVisibilitySymbol(textField(operation, "visibility", "public"));
            String name = textField(operation, "name", "");
            List<String> parameters = new ArrayList<>();
            JsonNode parameterValues = operation.get("parameters");
            if (parameterValues != null && parameterValues.isArray()) {
                for (JsonNode parameter : parameterValues) {
                    String parameterName = textField(parameter, "name", "");
                    String parameterType = textField(parameter, "type", "");
                    if (parameterType.isEmpty()) {
                        parameters.add(parameterName);
                    } else if (parameterName.isEmpty()) {
                        parameters.add(parameterType);
                    } else {
                        parameters.add(parameterName + " : " + parameterType);
                    }
                }
            }
            String returnType = textField(operation, "return_type", "");
            String signature = visibility + " " + name + "(" + String.join(", ", parameters) + ")";
            lines.add(returnType.isEmpty() ? signature : signature + " : " + returnType);
        }
        return lines;
    }

    private static List<String> umlLiteralLines(JsonNode properties) {
        JsonNode literals = properties == null ? null : properties.get("literals");
        if (literals == null || !literals.isArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode literal : literals) {
            if (literal.isTextual()) {
                lines.add(literal.asText());
            }
        }
        return lines;
    }

    private static String umlVisibilitySymbol(String visibility) {
        return switch (visibility) {
            case "private" -> "-";
            case "protected" -> "#";
            case "package" -> "~";
            default -> "+";
        };
    }

    private static String textField(JsonNode value, String field, String fallback) {
        JsonNode fieldValue = value == null ? null : value.get(field);
        return fieldValue != null && fieldValue.isTextual() ? fieldValue.asText() : fallback;
    }

    private static boolean umlDecoratorSuppliesNodeLabel(SvgNodeDecorator decorator) {
        return decorator == SvgNodeDecorator.UML_CLASS
                || decorator == SvgNodeDecorator.UML_INTERFACE
                || decorator == SvgNodeDecorator.UML_DATA_TYPE
                || decorator == SvgNodeDecorator.UML_ENUMERATION;
    }

    private static boolean isUmlDecorator(SvgNodeDecorator decorator) {
        return decorator != null && decorator.name().startsWith("UML_");
    }

    private static boolean isArchimateCutCornerRectangle(SvgNodeDecorator decorator) {
        return decorator == SvgNodeDecorator.ARCHIMATE_STAKEHOLDER
                || decorator == SvgNodeDecorator.ARCHIMATE_DRIVER
                || decorator == SvgNodeDecorator.ARCHIMATE_ASSESSMENT
                || decorator == SvgNodeDecorator.ARCHIMATE_GOAL
                || decorator == SvgNodeDecorator.ARCHIMATE_OUTCOME
                || decorator == SvgNodeDecorator.ARCHIMATE_VALUE
                || decorator == SvgNodeDecorator.ARCHIMATE_MEANING
                || decorator == SvgNodeDecorator.ARCHIMATE_CONSTRAINT
                || decorator == SvgNodeDecorator.ARCHIMATE_REQUIREMENT
                || decorator == SvgNodeDecorator.ARCHIMATE_PRINCIPLE;
    }

    private static boolean isArchimateRoundedRectangle(SvgNodeDecorator decorator) {
        return decorator == SvgNodeDecorator.ARCHIMATE_WORK_PACKAGE
                || decorator == SvgNodeDecorator.ARCHIMATE_IMPLEMENTATION_EVENT
                || decorator == SvgNodeDecorator.ARCHIMATE_COURSE_OF_ACTION
                || decorator == SvgNodeDecorator.ARCHIMATE_VALUE_STREAM
                || decorator == SvgNodeDecorator.ARCHIMATE_CAPABILITY
                || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_SERVICE
                || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_FUNCTION
                || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_PROCESS
                || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_EVENT
                || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE
                || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_FUNCTION
                || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_PROCESS
                || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_EVENT
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_SERVICE
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_FUNCTION
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_PROCESS
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_EVENT;
    }

    private static String archimateIconKind(SvgNodeDecorator decorator) {
        String name = decoratorName(decorator).replace("archimate_", "");
        return switch (name) {
            case "application_component" -> "component";
            case "business_actor" -> "actor";
            case "technology_node" -> "node";
            case "data_object", "business_object" -> "object";
            case "application_service", "business_service", "technology_service" -> "service";
            default -> name;
        };
    }

    private static String decoratorName(SvgNodeDecorator decorator) {
        return decorator.name().toLowerCase(Locale.ROOT);
    }

    private static String edgeMarker(LaidOutEdge edge, ResolvedEdgeStyle style, String side) {
        SvgEdgeMarkerEnd marker = side.equals("start") ? style.markerStart() : style.markerEnd();
        if (marker == SvgEdgeMarkerEnd.NONE) {
            return "";
        }
        String markerName = markerName(marker);
        String id = "marker-" + side + "-" + edge.id();
        String attribute = "data-dediren-edge-marker-" + side;
        String fill = markerFill(marker, style);
        String stroke = markerStroke(marker, style);
        String body = switch (marker) {
            case FILLED_DIAMOND, HOLLOW_DIAMOND -> "<path d=\"M 1 5 L 5 1 L 9 5 L 5 9 Z\" fill=\""
                    + fill + "\" stroke=\"" + stroke + "\" stroke-width=\"1\"/>";
            case HOLLOW_TRIANGLE -> "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\"" + fill
                    + "\" stroke=\"" + stroke + "\" stroke-width=\"1\"/>";
            case OPEN_ARROW -> "<path d=\"M 1 1 L 9 5 L 1 9\" fill=\"none\" stroke=\""
                    + stroke + "\" stroke-width=\"1.5\"/>";
            case FILLED_CIRCLE, HOLLOW_CIRCLE -> "<circle cx=\"5\" cy=\"5\" r=\"3.5\" fill=\""
                    + fill + "\" stroke=\"" + stroke + "\" stroke-width=\"1\"/>";
            default -> "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\"" + fill + "\" stroke=\""
                    + stroke + "\" stroke-width=\"1\"/>";
        };
        return "<marker id=\"" + attr(id) + "\" " + attribute + "=\"" + markerName
                + "\" markerWidth=\"10\" markerHeight=\"10\" refX=\"5\" refY=\"5\" orient=\"auto\">"
                + body + "</marker>";
    }

    private static String lineJumpMasks(LaidOutEdge edge, List<LineJump> lineJumps, String backgroundFill) {
        if (lineJumps.isEmpty()) {
            return "";
        }
        StringBuilder masks = new StringBuilder();
        masks.append("<g data-dediren-line-jump-masks=\"").append(attr(edge.id())).append("\">");
        for (LineJump jump : lineJumps) {
            masks.append("<path d=\"").append(attr(jump.maskPath()))
                    .append("\" fill=\"none\" stroke=\"").append(attr(backgroundFill))
                    .append("\" stroke-width=\"6\"/>");
        }
        masks.append("</g>");
        return masks.toString();
    }

    private static String edgePath(LaidOutEdge edge, ResolvedEdgeStyle style, List<LineJump> lineJumps) {
        if (edge.points().isEmpty()) {
            return "";
        }
        String data = pathData(edge, lineJumps);
        String dash = style.lineStyle() == SvgEdgeLineStyle.DASHED ? " stroke-dasharray=\"8 5\"" : "";
        String markerStart = style.markerStart() == SvgEdgeMarkerEnd.NONE
                ? ""
                : " marker-start=\"url(#marker-start-" + attr(edge.id()) + ")\"";
        String markerEnd = style.markerEnd() == SvgEdgeMarkerEnd.NONE
                ? ""
                : " marker-end=\"url(#marker-end-" + attr(edge.id()) + ")\"";
        return "<path d=\"" + data + "\" fill=\"none\" stroke=\""
                + attr(style.stroke()) + "\" stroke-width=\"" + styleNumber(style.strokeWidth()) + "\""
                + " stroke-linecap=\"round\" stroke-linejoin=\"round\""
                + dash + markerStart + markerEnd + "/>";
    }

    private static String markerName(SvgEdgeMarkerEnd marker) {
        return marker.name().toLowerCase(Locale.ROOT);
    }

    private static String markerFill(SvgEdgeMarkerEnd marker, ResolvedEdgeStyle style) {
        return switch (marker) {
            case HOLLOW_TRIANGLE, HOLLOW_DIAMOND, HOLLOW_CIRCLE -> "#ffffff";
            case OPEN_ARROW -> "none";
            default -> attr(style.stroke());
        };
    }

    private static String markerStroke(SvgEdgeMarkerEnd marker, ResolvedEdgeStyle style) {
        return switch (marker) {
            case FILLED_ARROW, FILLED_DIAMOND, FILLED_CIRCLE -> attr(style.stroke());
            default -> attr(style.stroke());
        };
    }

    private static String pathData(LaidOutEdge edge, List<LineJump> lineJumps) {
        if (lineJumps.isEmpty()) {
            return roundedPathData(edge.points());
        }
        StringBuilder data = new StringBuilder();
        Point first = edge.points().get(0);
        data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
        for (int index = 0; index < edge.points().size() - 1; index++) {
            int segmentIndex = index;
            Point start = edge.points().get(index);
            Point end = edge.points().get(index + 1);
            List<LineJump> segmentJumps = lineJumps.stream()
                    .filter(jump -> jump.segmentIndex() == segmentIndex)
                    .sorted((left, right) -> Double.compare(
                            segmentProgress(start, end, left.x(), left.y()),
                            segmentProgress(start, end, right.x(), right.y())))
                    .toList();
            for (LineJump jump : segmentJumps) {
                data.append(" ").append(jump.pathPrefix(start, end));
            }
            data.append(String.format(Locale.ROOT, " L %.1f %.1f", end.x(), end.y()));
        }
        return data.toString();
    }

    private static String roundedPathData(List<Point> points) {
        if (points.isEmpty()) {
            return "";
        }
        if (points.size() == 1) {
            Point only = points.getFirst();
            return String.format(Locale.ROOT, "M %.1f %.1f", only.x(), only.y());
        }
        StringBuilder data = new StringBuilder();
        Point first = points.getFirst();
        data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
        for (int index = 1; index < points.size() - 1; index++) {
            Point previous = points.get(index - 1);
            Point corner = points.get(index);
            Point next = points.get(index + 1);
            RoundedCorner rounded = roundedCorner(previous, corner, next);
            if (rounded == null) {
                data.append(String.format(Locale.ROOT, " L %.1f %.1f", corner.x(), corner.y()));
            } else {
                data.append(String.format(
                        Locale.ROOT,
                        " L %.1f %.1f Q %.1f %.1f %.1f %.1f",
                        rounded.before().x(),
                        rounded.before().y(),
                        corner.x(),
                        corner.y(),
                        rounded.after().x(),
                        rounded.after().y()));
            }
        }
        Point last = points.getLast();
        data.append(String.format(Locale.ROOT, " L %.1f %.1f", last.x(), last.y()));
        return data.toString();
    }

    private static RoundedCorner roundedCorner(Point previous, Point corner, Point next) {
        boolean firstHorizontal = nearlyEqual(previous.y(), corner.y());
        boolean firstVertical = nearlyEqual(previous.x(), corner.x());
        boolean secondHorizontal = nearlyEqual(corner.y(), next.y());
        boolean secondVertical = nearlyEqual(corner.x(), next.x());
        if (!((firstHorizontal && secondVertical) || (firstVertical && secondHorizontal))) {
            return null;
        }
        double firstLength = distance(previous, corner);
        double secondLength = distance(corner, next);
        double radius = Math.min(8.0, Math.min(firstLength / 2.0, secondLength / 2.0));
        if (radius < 2.0) {
            return null;
        }
        return new RoundedCorner(
                shiftedToward(corner, previous, radius),
                shiftedToward(corner, next, radius));
    }

    private static Point shiftedToward(Point from, Point toward, double distance) {
        double length = distance(from, toward);
        if (length == 0.0) {
            return from;
        }
        double ratio = distance / length;
        return new Point(
                from.x() + (toward.x() - from.x()) * ratio,
                from.y() + (toward.y() - from.y()) * ratio);
    }

    private static double distance(Point left, Point right) {
        return Math.hypot(left.x() - right.x(), left.y() - right.y());
    }

    private static double segmentProgress(Point start, Point end, double x, double y) {
        double dx = Math.abs(end.x() - start.x());
        double dy = Math.abs(end.y() - start.y());
        if (dx >= dy) {
            double length = end.x() - start.x();
            return length == 0.0 ? 0.0 : (x - start.x()) / length;
        }
        double length = end.y() - start.y();
        return length == 0.0 ? 0.0 : (y - start.y()) / length;
    }

    private static List<LineJump> lineJumps(LaidOutEdge edge, List<LaidOutEdge> renderedEdges) {
        List<LineJump> jumps = new ArrayList<>();
        for (int segmentIndex = 0; segmentIndex < edge.points().size() - 1; segmentIndex++) {
            Point currentStart = edge.points().get(segmentIndex);
            Point currentEnd = edge.points().get(segmentIndex + 1);
            boolean currentVertical = nearlyEqual(currentStart.x(), currentEnd.x());
            boolean currentHorizontal = nearlyEqual(currentStart.y(), currentEnd.y());
            if (!currentVertical && !currentHorizontal) {
                continue;
            }
            for (LaidOutEdge previousEdge : renderedEdges) {
                if (isSharedJunctionPair(edge, previousEdge)) {
                    continue;
                }
                for (int previousIndex = 0; previousIndex < previousEdge.points().size() - 1; previousIndex++) {
                    Point previousStart = previousEdge.points().get(previousIndex);
                    Point previousEnd = previousEdge.points().get(previousIndex + 1);
                    boolean previousVertical = nearlyEqual(previousStart.x(), previousEnd.x());
                    boolean previousHorizontal = nearlyEqual(previousStart.y(), previousEnd.y());
                    if (currentVertical && previousHorizontal) {
                        double x = currentStart.x();
                        double y = previousStart.y();
                        if (insideSegment(y, currentStart.y(), currentEnd.y())
                                && insideSegment(x, previousStart.x(), previousEnd.x())) {
                            jumps.add(new LineJump(segmentIndex, x, y, true));
                        }
                    } else if (currentHorizontal && previousVertical) {
                        double x = previousStart.x();
                        double y = currentStart.y();
                        if (insideSegment(x, currentStart.x(), currentEnd.x())
                                && insideSegment(y, previousStart.y(), previousEnd.y())) {
                            jumps.add(new LineJump(segmentIndex, x, y, false));
                        }
                    }
                }
            }
        }
        return dedupeJumps(jumps);
    }

    private static boolean isSharedJunctionPair(LaidOutEdge edge, LaidOutEdge previousEdge) {
        return (edge.routingHints().contains("shared_source_junction") && edge.source().equals(previousEdge.source()))
                || (edge.routingHints().contains("shared_target_junction") && edge.target().equals(previousEdge.target()));
    }

    private static List<LineJump> dedupeJumps(List<LineJump> jumps) {
        List<LineJump> deduped = new ArrayList<>();
        for (LineJump jump : jumps) {
            boolean exists = deduped.stream().anyMatch(existing ->
                    existing.segmentIndex() == jump.segmentIndex()
                            && Math.abs(existing.x() - jump.x()) < 0.1
                            && Math.abs(existing.y() - jump.y()) < 0.1
                            && existing.vertical() == jump.vertical());
            if (!exists) {
                deduped.add(jump);
            }
        }
        return deduped;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.001;
    }

    private static boolean insideSegment(double value, double start, double end) {
        double min = Math.min(start, end);
        double max = Math.max(start, end);
        return value > min && value < max;
    }

    private static EdgeLabel edgeLabel(
            LaidOutEdge edge,
            ResolvedEdgeStyle style,
            List<LabelBox> occupiedBoxes,
            double fontSize) {
        Optional<Segment> horizontal = firstHorizontalSegment(edge);
        if (horizontal.isPresent()) {
            Segment segment = horizontal.get();
            double direction = Math.signum(segment.end().x() - segment.start().x());
            if (direction == 0.0) {
                direction = 1.0;
            }
            double preferredX = switch (style.labelHorizontalPosition()) {
                case CENTER -> (segment.start().x() + segment.end().x()) / 2.0;
                case NEAR_END -> segment.end().x() - direction * 18.0;
                case NEAR_START -> segment.start().x() + direction * 18.0;
            };
            double centerX = (segment.start().x() + segment.end().x()) / 2.0;
            double nearStartX = segment.start().x() + direction * 18.0;
            double nearEndX = segment.end().x() - direction * 18.0;
            double baseOffset = switch (style.labelHorizontalSide()) {
                case ABOVE -> -10.0;
                case BELOW -> 18.0;
                case AUTO -> autoHorizontalLabelOffset(edge, segment.index());
            };
            List<Double> xCandidates = orderedValues(preferredX, centerX, nearStartX, nearEndX);
            double oppositeOffset = baseOffset < 0.0 ? 18.0 : -10.0;
            List<Double> offsets = orderedValues(
                    baseOffset,
                    oppositeOffset,
                    baseOffset + 28.0,
                    baseOffset - 28.0,
                    baseOffset + 56.0,
                    baseOffset - 56.0);
            for (double offset : offsets) {
                for (double x : xCandidates) {
                    EdgeLabel candidate = edgeLabelCandidate(x, segment.start().y() + offset, "middle", edge.label(), fontSize);
                    if (occupiedBoxes.stream().noneMatch(candidate.bounds()::overlaps)) {
                        return candidate;
                    }
                }
            }
            return edgeLabelCandidate(preferredX, segment.start().y() + baseOffset, "middle", edge.label(), fontSize);
        }
        Optional<Segment> vertical = firstVerticalSegment(edge);
        if (vertical.isPresent()) {
            Segment segment = vertical.get();
            double minY = edge.points().stream().mapToDouble(Point::y).min().orElse(segment.start().y());
            double maxY = edge.points().stream().mapToDouble(Point::y).max().orElse(segment.end().y());
            return edgeLabelCandidate(
                    segment.start().x() - 6.0,
                    (minY + maxY) / 2.0,
                    "end",
                    edge.label(),
                    fontSize);
        }
        Point point = edge.points().isEmpty() ? new Point(0.0, 0.0) : edge.points().get(edge.points().size() / 2);
        return edgeLabelCandidate(point.x(), point.y() - 6.0, "middle", edge.label(), fontSize);
    }

    private static EdgeLabel edgeLabelCandidate(double x, double y, String anchor, String text, double fontSize) {
        return new EdgeLabel(x, y, anchor, labelBox(x, y, anchor, text, fontSize));
    }

    private static List<Double> orderedValues(double... values) {
        List<Double> ordered = new ArrayList<>();
        for (double value : values) {
            boolean exists = ordered.stream().anyMatch(existing -> Math.abs(existing - value) < 0.1);
            if (!exists) {
                ordered.add(value);
            }
        }
        return ordered;
    }

    private static double autoHorizontalLabelOffset(LaidOutEdge edge, int segmentIndex) {
        if (segmentIndex + 2 < edge.points().size()) {
            Point segmentStart = edge.points().get(segmentIndex);
            Point next = edge.points().get(segmentIndex + 2);
            if (next.y() < segmentStart.y()) {
                return -10.0;
            }
        }
        return 18.0;
    }

    private static Optional<Segment> firstHorizontalSegment(LaidOutEdge edge) {
        if (edge.routingHints().contains("shared_source_junction")) {
            for (int index = edge.points().size() - 2; index >= 0; index--) {
                Point start = edge.points().get(index);
                Point end = edge.points().get(index + 1);
                if (nearlyEqual(start.y(), end.y()) && Math.abs(start.x() - end.x()) > 0.001) {
                    return Optional.of(new Segment(index, start, end));
                }
            }
        }
        for (int index = 0; index < edge.points().size() - 1; index++) {
            Point start = edge.points().get(index);
            Point end = edge.points().get(index + 1);
            if (nearlyEqual(start.y(), end.y()) && Math.abs(start.x() - end.x()) > 0.001) {
                return Optional.of(new Segment(index, start, end));
            }
        }
        return Optional.empty();
    }

    private static Optional<Segment> firstVerticalSegment(LaidOutEdge edge) {
        for (int index = 0; index < edge.points().size() - 1; index++) {
            Point start = edge.points().get(index);
            Point end = edge.points().get(index + 1);
            if (nearlyEqual(start.x(), end.x()) && Math.abs(start.y() - end.y()) > 0.001) {
                return Optional.of(new Segment(index, start, end));
            }
        }
        return Optional.empty();
    }

    private static SvgBounds svgBounds(
            LayoutResult result,
            RenderMetadata metadata,
            RenderPolicy policy,
            ResolvedStyle base) {
        var bounds = SvgBounds.empty();
        for (LaidOutGroup group : result.groups()) {
            bounds.includeRect(group.x(), group.y(), group.width(), group.height());
        }
        for (LaidOutEdge edge : result.edges()) {
            for (Point point : edge.points()) {
                bounds.includePoint(point.x(), point.y());
            }
        }
        for (LaidOutNode node : result.nodes()) {
            bounds.includeRect(node.x(), node.y(), node.width(), node.height());
        }
        List<LabelBox> occupiedBoxes = nodeObstacleBoxes(result);
        for (LaidOutEdge edge : result.edges()) {
            if (edge.label() == null || edge.label().isEmpty()) {
                continue;
            }
            EdgeLabel label = edgeLabel(edge, edgeStyle(policy, metadata, edge.id(), base), occupiedBoxes, base.fontSize());
            bounds.includeRect(
                    label.bounds().minX(),
                    label.bounds().minY(),
                    label.bounds().width(),
                    label.bounds().height());
            occupiedBoxes.add(label.bounds());
        }
        if (bounds.isEmpty()) {
            bounds.includeRect(0.0, 0.0, policy.page().width(), policy.page().height());
        }
        return bounds.padded(policy);
    }

    private static List<LabelBox> nodeObstacleBoxes(LayoutResult result) {
        List<LabelBox> boxes = new ArrayList<>();
        for (LaidOutNode node : result.nodes()) {
            boxes.add(new LabelBox(node.x(), node.y(), node.x() + node.width(), node.y() + node.height()));
        }
        return boxes;
    }

    private static LabelBox labelBox(double x, double y, String anchor, String text, double fontSize) {
        double width = (text == null ? 0 : text.length()) * fontSize * 0.56;
        double minX = switch (anchor) {
            case "end" -> x - width;
            case "middle" -> x - width / 2.0;
            default -> x;
        };
        double minY = y - fontSize;
        return new LabelBox(minX, minY, minX + width, y + fontSize * 0.25);
    }

    private static ResolvedStyle baseStyle(RenderPolicy policy) {
        SvgStylePolicy style = policy.style();
        var defaultNode = new ResolvedNodeStyle("#f8fafc", "#334155", 1.5, 6.0, "#0f172a", null);
        var defaultEdge = new ResolvedEdgeStyle(
                "#64748b",
                1.5,
                "#374151",
                SvgEdgeLineStyle.SOLID,
                SvgEdgeMarkerEnd.NONE,
                SvgEdgeMarkerEnd.FILLED_ARROW,
                SvgEdgeLabelHorizontalPosition.NEAR_START,
                SvgEdgeLabelHorizontalSide.AUTO,
                SvgEdgeLabelVerticalPosition.CENTER,
                SvgEdgeLabelVerticalSide.LEFT);
        var defaultGroup = new ResolvedGroupStyle("#eff6ff", "#93c5fd", 1.0, 8.0, "#1e3a8a", 12.0, null);
        return new ResolvedStyle(
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
                        .orElse(14.0),
                mergeNodeStyle(defaultNode, style == null ? null : style.node()),
                mergeEdgeStyle(defaultEdge, style == null ? null : style.edge()),
                mergeGroupStyle(defaultGroup, style == null ? null : style.group()));
    }

    private static ResolvedNodeStyle nodeStyle(
            RenderPolicy policy,
            RenderMetadata metadata,
            String nodeId,
            ResolvedStyle base) {
        SvgStylePolicy style = policy.style();
        SvgNodeStyle typeStyle = null;
        if (style != null && metadata != null && metadata.nodes().containsKey(nodeId)) {
            typeStyle = style.nodeTypeOverrides().get(metadata.nodes().get(nodeId).type());
        }
        ResolvedNodeStyle resolved = mergeNodeStyle(base.node(), typeStyle);
        return mergeNodeStyle(resolved, style == null ? null : style.nodeOverrides().get(nodeId));
    }

    private static ResolvedEdgeStyle edgeStyle(
            RenderPolicy policy,
            RenderMetadata metadata,
            String edgeId,
            ResolvedStyle base) {
        SvgStylePolicy style = policy.style();
        SvgEdgeStyle typeStyle = null;
        if (style != null && metadata != null && metadata.edges().containsKey(edgeId)) {
            typeStyle = style.edgeTypeOverrides().get(metadata.edges().get(edgeId).type());
        }
        ResolvedEdgeStyle resolved = mergeEdgeStyle(base.edge(), typeStyle);
        return mergeEdgeStyle(resolved, style == null ? null : style.edgeOverrides().get(edgeId));
    }

    private static ResolvedGroupStyle groupStyle(
            RenderPolicy policy,
            RenderMetadata metadata,
            String groupId,
            ResolvedStyle base) {
        SvgStylePolicy style = policy.style();
        SvgGroupStyle typeStyle = null;
        if (style != null && metadata != null && metadata.groups().containsKey(groupId)) {
            typeStyle = style.groupTypeOverrides().get(metadata.groups().get(groupId).type());
        }
        ResolvedGroupStyle resolved = mergeGroupStyle(base.group(), typeStyle);
        return mergeGroupStyle(resolved, style == null ? null : style.groupOverrides().get(groupId));
    }

    private static ResolvedNodeStyle mergeNodeStyle(ResolvedNodeStyle base, SvgNodeStyle override) {
        if (override == null) {
            return base;
        }
        return new ResolvedNodeStyle(
                override.fill() == null ? base.fill() : override.fill(),
                override.stroke() == null ? base.stroke() : override.stroke(),
                override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
                override.rx() == null ? base.rx() : override.rx(),
                override.labelFill() == null ? base.labelFill() : override.labelFill(),
                override.decorator() == null ? base.decorator() : override.decorator());
    }

    private static ResolvedEdgeStyle mergeEdgeStyle(ResolvedEdgeStyle base, SvgEdgeStyle override) {
        if (override == null) {
            return base;
        }
        return new ResolvedEdgeStyle(
                override.stroke() == null ? base.stroke() : override.stroke(),
                override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
                override.labelFill() == null ? base.labelFill() : override.labelFill(),
                override.lineStyle() == null ? base.lineStyle() : override.lineStyle(),
                override.markerStart() == null ? base.markerStart() : override.markerStart(),
                override.markerEnd() == null ? base.markerEnd() : override.markerEnd(),
                override.labelHorizontalPosition() == null
                        ? base.labelHorizontalPosition()
                        : override.labelHorizontalPosition(),
                override.labelHorizontalSide() == null ? base.labelHorizontalSide() : override.labelHorizontalSide(),
                override.labelVerticalPosition() == null
                        ? base.labelVerticalPosition()
                        : override.labelVerticalPosition(),
                override.labelVerticalSide() == null ? base.labelVerticalSide() : override.labelVerticalSide());
    }

    private static ResolvedGroupStyle mergeGroupStyle(ResolvedGroupStyle base, SvgGroupStyle override) {
        if (override == null) {
            return base;
        }
        return new ResolvedGroupStyle(
                override.fill() == null ? base.fill() : override.fill(),
                override.stroke() == null ? base.stroke() : override.stroke(),
                override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
                override.rx() == null ? base.rx() : override.rx(),
                override.labelFill() == null ? base.labelFill() : override.labelFill(),
                override.labelSize() == null ? base.labelSize() : override.labelSize(),
                override.decorator() == null ? base.decorator() : override.decorator());
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
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private enum Bound {
        MIN,
        EXCLUSIVE_MIN
    }

    private record RenderInput(LayoutResult layoutResult, RenderMetadata renderMetadata, RenderPolicy policy) {
    }

    private record Segment(int index, Point start, Point end) {
    }

    private record RoundedCorner(Point before, Point after) {
    }

    private record EdgeLabel(double x, double y, String anchor, LabelBox bounds) {
    }

    private record LabelBox(double minX, double minY, double maxX, double maxY) {
        boolean overlaps(LabelBox other) {
            return minX < other.maxX
                    && maxX > other.minX
                    && minY < other.maxY
                    && maxY > other.minY;
        }

        double width() {
            return maxX - minX;
        }

        double height() {
            return maxY - minY;
        }
    }

    private record LineJump(int segmentIndex, double x, double y, boolean vertical) {
        String pathPrefix(Point start, Point end) {
            if (vertical) {
                double before = y + (start.y() < end.y() ? -6.0 : 6.0);
                double after = y + (start.y() < end.y() ? 6.0 : -6.0);
                double controlX = x + 6.0;
                return String.format(
                        Locale.ROOT,
                        "L %.1f %.1f Q %.1f %.1f %.1f %.1f",
                        x,
                        before,
                        controlX,
                        y,
                        x,
                        after);
            }
            double before = x + (start.x() < end.x() ? -6.0 : 6.0);
            double after = x + (start.x() < end.x() ? 6.0 : -6.0);
            double controlY = y - 6.0;
            return String.format(
                    Locale.ROOT,
                    "L %.1f %.1f Q %.1f %.1f %.1f %.1f",
                    before,
                    y,
                    x,
                    controlY,
                    after,
                    y);
        }

        String maskPath() {
            if (vertical) {
                return String.format(
                        Locale.ROOT,
                        "M %.1f %.1f Q %.1f %.1f %.1f %.1f",
                        x,
                        y - 6.0,
                        x + 6.0,
                        y,
                        x,
                        y + 6.0);
            }
            return String.format(
                    Locale.ROOT,
                    "M %.1f %.1f Q %.1f %.1f %.1f %.1f",
                    x - 6.0,
                    y,
                    x,
                    y - 6.0,
                    x + 6.0,
                    y);
        }
    }

    private static final class SvgBounds {
        private double minX;
        private double minY;
        private double maxX;
        private double maxY;

        private SvgBounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        static SvgBounds empty() {
            return new SvgBounds(
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY);
        }

        boolean isEmpty() {
            return !Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY);
        }

        void includeRect(double x, double y, double width, double height) {
            includePoint(x, y);
            includePoint(x + width, y + height);
        }

        void includePoint(double x, double y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        SvgBounds padded(RenderPolicy policy) {
            return new SvgBounds(
                    minX - policy.margin().left(),
                    minY - policy.margin().top(),
                    maxX + policy.margin().right(),
                    maxY + policy.margin().bottom());
        }

        double width() {
            return maxX - minX;
        }

        double height() {
            return maxY - minY;
        }

        double minX() {
            return minX;
        }

        double minY() {
            return minY;
        }

        double maxX() {
            return maxX;
        }

        double maxY() {
            return maxY;
        }
    }

    private record ResolvedStyle(
            String backgroundFill,
            String fontFamily,
            double fontSize,
            ResolvedNodeStyle node,
            ResolvedEdgeStyle edge,
            ResolvedGroupStyle group) {
    }

    private record ResolvedNodeStyle(
            String fill,
            String stroke,
            double strokeWidth,
            double rx,
            String labelFill,
            SvgNodeDecorator decorator) {
    }

    private record ResolvedEdgeStyle(
            String stroke,
            double strokeWidth,
            String labelFill,
            SvgEdgeLineStyle lineStyle,
            SvgEdgeMarkerEnd markerStart,
            SvgEdgeMarkerEnd markerEnd,
            SvgEdgeLabelHorizontalPosition labelHorizontalPosition,
            SvgEdgeLabelHorizontalSide labelHorizontalSide,
            SvgEdgeLabelVerticalPosition labelVerticalPosition,
            SvgEdgeLabelVerticalSide labelVerticalSide) {
    }

    private record ResolvedGroupStyle(
            String fill,
            String stroke,
            double strokeWidth,
            double rx,
            String labelFill,
            double labelSize,
            SvgNodeDecorator decorator) {
    }

    private static final class PolicyValidationException extends Exception {
        private final String path;

        private PolicyValidationException(String path, String message) {
            super(message);
            this.path = path;
        }

        String path() {
            return path;
        }
    }

    private static final class RenderMetadataUsageException extends Exception {
        private final String code;
        private final String path;

        private RenderMetadataUsageException(String code, String path, String message) {
            super(message);
            this.code = code;
            this.path = path;
        }

        String code() {
            return code;
        }

        String path() {
            return path;
        }
    }
}

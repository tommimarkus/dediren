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
        SvgBounds bounds = svgBounds(result, policy);
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
        for (LaidOutEdge edge : result.edges()) {
            ResolvedEdgeStyle style = edgeStyle(policy, metadata, edge.id(), base);
            svg.append("<g data-dediren-edge-id=\"").append(attr(edge.id())).append("\">");
            svg.append(edgePath(edge, style));
            if (edge.label() != null && !edge.label().isEmpty()) {
                Point labelPoint = edgeLabelPoint(edge);
                svg.append(String.format(
                        Locale.ROOT,
                        "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\">%s</text>",
                        labelPoint.x(),
                        labelPoint.y() - 6.0,
                        attr(style.labelFill()),
                        text(edge.label())));
            }
            svg.append("</g>");
        }
        for (LaidOutNode node : result.nodes()) {
            ResolvedNodeStyle style = nodeStyle(policy, metadata, node.id(), base);
            svg.append("<g data-dediren-node-id=\"").append(attr(node.id())).append("\">");
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect data-dediren-node-shape=\"archimate_rectangle\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    node.x(),
                    node.y(),
                    node.width(),
                    node.height(),
                    styleNumber(style.rx()),
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth())));
            svg.append(String.format(
                    Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\">%s</text>",
                    node.x() + node.width() / 2.0,
                    node.y() + node.height() / 2.0 + base.fontSize() / 3.0,
                    attr(style.labelFill()),
                    text(node.label())));
            svg.append("</g>");
        }
        svg.append("</g></svg>\n");
        return svg.toString();
    }

    private static String edgePath(LaidOutEdge edge, ResolvedEdgeStyle style) {
        if (edge.points().isEmpty()) {
            return "";
        }
        List<String> commands = new ArrayList<>();
        for (int index = 0; index < edge.points().size(); index++) {
            Point point = edge.points().get(index);
            commands.add((index == 0 ? "M " : "L ")
                    + String.format(Locale.ROOT, "%.1f %.1f", point.x(), point.y()));
        }
        String dash = style.lineStyle() == SvgEdgeLineStyle.DASHED ? " stroke-dasharray=\"6 4\"" : "";
        return "<path d=\"" + String.join(" ", commands) + "\" fill=\"none\" stroke=\""
                + attr(style.stroke()) + "\" stroke-width=\"" + styleNumber(style.strokeWidth()) + "\"" + dash + "/>";
    }

    private static Point edgeLabelPoint(LaidOutEdge edge) {
        if (edge.points().isEmpty()) {
            return new Point(0.0, 0.0);
        }
        return edge.points().get(edge.points().size() / 2);
    }

    private static SvgBounds svgBounds(LayoutResult result, RenderPolicy policy) {
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
        if (bounds.isEmpty()) {
            bounds.includeRect(0.0, 0.0, policy.page().width(), policy.page().height());
        }
        return bounds.padded(policy);
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

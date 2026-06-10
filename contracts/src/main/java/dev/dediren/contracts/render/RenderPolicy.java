package dev.dediren.contracts.render;

public record RenderPolicy(
        String svgRenderPolicySchemaVersion,
        String semanticProfile,
        Page page,
        Margin margin,
        SvgStylePolicy style,
        String interactive) {
}

package dev.dediren.contracts.render;

public record RenderPolicy(
        String renderPolicySchemaVersion,
        String semanticProfile,
        Page page,
        Margin margin,
        SvgStylePolicy style,
        String interactive) {
}

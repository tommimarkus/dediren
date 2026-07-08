package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgNodeDecorator;

public record ResolvedGroupStyle(
    String fill,
    String stroke,
    double strokeWidth,
    double rx,
    String labelFill,
    double labelSize,
    SvgNodeDecorator decorator) {}

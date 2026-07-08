package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgNodeDecorator;

public record ResolvedNodeStyle(
    String fill,
    String stroke,
    double strokeWidth,
    double rx,
    String labelFill,
    SvgNodeDecorator decorator) {}

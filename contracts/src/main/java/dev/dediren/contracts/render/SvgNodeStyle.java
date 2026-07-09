package dev.dediren.contracts.render;

public record SvgNodeStyle(
    String fill,
    String stroke,
    Double strokeWidth,
    Double rx,
    String labelFill,
    SvgNodeDecorator decorator,
    SvgNodeShape shape) {}

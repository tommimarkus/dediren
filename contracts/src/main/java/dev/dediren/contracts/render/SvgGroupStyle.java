package dev.dediren.contracts.render;

public record SvgGroupStyle(
    String fill,
    String stroke,
    Double strokeWidth,
    Double rx,
    String labelFill,
    Double labelSize,
    SvgNodeDecorator decorator,
    Double fillOpacity,
    Double strokeOpacity) {}

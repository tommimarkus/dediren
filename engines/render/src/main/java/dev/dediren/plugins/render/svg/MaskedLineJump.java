package dev.dediren.plugins.render.svg;

/**
 * A line jump together with the colour its mask must paint: the backdrop at the crossing point,
 * which is whichever group rect sits under it or the page background when none does.
 *
 * <p>Resolved while the scene is placed, not while it is written, so {@link
 * EdgeRenderer#lineJumpMasks} has no style lookup left to perform. See {@link
 * EdgeRenderer#backdropFillAt}.
 */
public record MaskedLineJump(LineJump jump, String maskFill) {}

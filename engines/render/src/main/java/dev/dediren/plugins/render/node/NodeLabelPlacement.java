package dev.dediren.plugins.render.node;

/**
 * A plain node label, fully placed: the wrapped lines with the size they fit at, the block
 * position, and the anchor the lines hang from.
 *
 * <p>The three are one decision, not three. {@link NodeLabels#nodeLabel} (which writes the {@code
 * <text>}) and {@link NodeLabels#nodeLabelBoxes} (which grows the viewBox to hold it) used to open
 * with the same three derivation calls, so a step added to one copy and not the other moved the
 * bounds off the ink — the label_align drift is exactly that bug. Both now take this record and
 * derive nothing.
 */
public record NodeLabelPlacement(
    NodeLabelLines lines, NodeLabelPosition position, double anchorX, String anchor) {}

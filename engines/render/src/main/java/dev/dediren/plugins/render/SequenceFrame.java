package dev.dediren.plugins.render;

/**
 * A render-synthesized rectangle in a UML sequence view: the interaction frame that encloses a
 * whole interaction, or the frame of one combined fragment.
 *
 * <p>Neither is a laid-out node — layout sizes lifelines, executions and gates, and the frames are
 * derived here from what they enclose — which is why this is not a {@code LaidOutNode} and why
 * {@code SequenceFragmentAlignmentTest} checks the derivation against the lifelines and messages it
 * claims to span.
 */
record SequenceFrame(double x, double y, double width, double height) {
  double right() {
    return x + width;
  }

  double bottom() {
    return y + height;
  }
}

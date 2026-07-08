package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutPreferences;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.graph.ElkNode;

final class ElkPackedOptions {
  private static final String RECTANGLE_PACKING_ALGORITHM = "org.eclipse.elk.rectpacking";
  private static final double COMPACT_SPACING = 32.0;
  private static final double READABLE_SPACING = 48.0;
  private static final double SPACIOUS_SPACING = 64.0;
  private static final double COMPACT_PADDING = 24.0;
  private static final double READABLE_PADDING = 32.0;
  private static final double SPACIOUS_PADDING = 40.0;

  private ElkPackedOptions() {}

  static void configureRoot(ElkNode root, LayoutPreferences preferences) {
    root.setProperty(CoreOptions.ALGORITHM, RECTANGLE_PACKING_ALGORITHM);
    root.setProperty(CoreOptions.SPACING_NODE_NODE, spacing(preferences));
    root.setProperty(CoreOptions.PADDING, new ElkPadding(padding(preferences)));
    root.setProperty(CoreOptions.ASPECT_RATIO, 1.6);
  }

  private static double spacing(LayoutPreferences preferences) {
    return switch (density(preferences)) {
      case READABLE -> READABLE_SPACING;
      case SPACIOUS -> SPACIOUS_SPACING;
      default -> COMPACT_SPACING;
    };
  }

  private static double padding(LayoutPreferences preferences) {
    return switch (density(preferences)) {
      case READABLE -> READABLE_PADDING;
      case SPACIOUS -> SPACIOUS_PADDING;
      default -> COMPACT_PADDING;
    };
  }

  private static LayoutDensity density(LayoutPreferences preferences) {
    return preferences == null || preferences.density() == null
        ? LayoutDensity.COMPACT
        : preferences.density();
  }
}

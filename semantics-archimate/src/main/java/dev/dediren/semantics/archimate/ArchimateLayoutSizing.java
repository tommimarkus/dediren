package dev.dediren.semantics.archimate;

import dev.dediren.archimate.Archimate;
import dev.dediren.contracts.source.SourceNode;

/**
 * ArchiMate node sizing: relocated verbatim from the ArchiMate branch of the old single
 * generic-graph {@code GenericGraphLayoutSizing} (Plan B P3). Relationship connectors ({@code
 * AndJunction}/{@code OrJunction}) size to a compact 28x28; every other node sizes to fit its label
 * plus the corner-icon reserve, rounded up to a 10px grid with a 160x80 floor.
 */
public final class ArchimateLayoutSizing {
  private static final double ARCHIMATE_MIN_WIDTH = 160.0;
  private static final double ARCHIMATE_MIN_HEIGHT = 80.0;
  private static final double ARCHIMATE_TEXT_CHAR_WIDTH = 8.7;

  // Must equal ARCHIMATE_LABEL_ICON_RESERVE in engines/render Main: per-side
  // room reserved so a centered label clears the upper-right type icon.
  // Enforced by dist-tool ArchimateLabelReserveConsistencyTest.
  public static final double ARCHIMATE_LABEL_ICON_RESERVE = 34.0;

  private static final double ARCHIMATE_LINE_HEIGHT = 18.0;
  private static final double ARCHIMATE_VERTICAL_PADDING = 28.0;

  private ArchimateLayoutSizing() {}

  static double widthHint(SourceNode sourceNode) {
    if (Archimate.isRelationshipConnectorType(sourceNode.type())) {
      return 28.0;
    }
    double content =
        archimateLongestTokenChars(sourceNode.label()) * ARCHIMATE_TEXT_CHAR_WIDTH
            + 2.0 * ARCHIMATE_LABEL_ICON_RESERVE;
    return roundUp(Math.max(content, ARCHIMATE_MIN_WIDTH), 10.0);
  }

  static double heightHint(SourceNode sourceNode) {
    if (Archimate.isRelationshipConnectorType(sourceNode.type())) {
      return 28.0;
    }
    double width = widthHint(sourceNode);
    double widthBudget = width - 2.0 * ARCHIMATE_LABEL_ICON_RESERVE;
    double content =
        archimateEstimatedLineCount(sourceNode.label(), widthBudget) * ARCHIMATE_LINE_HEIGHT
            + ARCHIMATE_VERTICAL_PADDING;
    return roundUp(Math.max(content, ARCHIMATE_MIN_HEIGHT), 10.0);
  }

  private static int archimateLongestTokenChars(String label) {
    int longest = 0;
    for (String token : label.trim().split("\\s+")) {
      longest = Math.max(longest, token.length());
    }
    return Math.max(longest, 1);
  }

  private static int archimateEstimatedLineCount(String label, double widthBudget) {
    if (widthBudget <= 0.0) {
      return 1;
    }
    double total = label.trim().length() * ARCHIMATE_TEXT_CHAR_WIDTH;
    return Math.max(1, (int) Math.ceil(total / widthBudget));
  }

  private static double roundUp(double value, double step) {
    return Math.ceil(value / step) * step;
  }
}

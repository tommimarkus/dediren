package dev.dediren.plugins.archimateoef;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The viewpoint names the ArchiMate exchange format names, and the nearest-match hint for one it
 * does not.
 *
 * <p>{@code ViewpointTypeType} is a union of {@code xs:string} with the {@code ViewpointsEnum}
 * below, so the schema accepts any string at all and a misspelled viewpoint exports clean and
 * imports as an unknown one. That union is deliberate — a tool-specific or organisation-specific
 * viewpoint is legitimate — so an unrecognised name is worth a warning, never a rejection.
 *
 * <p>The list is the exchange schema's own enumeration rather than a transcription of the
 * specification's viewpoint chapter: it is the vocabulary {@code view/@viewpoint} is actually
 * measured against, and it is a superset of the current example viewpoints, so a name the format
 * itself blesses never draws a false warning.
 */
final class OefViewpoints {

  private static final List<String> NAMES =
      List.of(
          "Organization",
          "Application Platform",
          "Application Structure",
          "Information Structure",
          "Technology",
          "Layered",
          "Physical",
          "Product",
          "Application Usage",
          "Technology Usage",
          "Business Process Cooperation",
          "Application Cooperation",
          "Service Realization",
          "Implementation and Deployment",
          "Goal Realization",
          "Goal Contribution",
          "Principles",
          "Requirements Realization",
          "Motivation",
          "Strategy",
          "Capability Map",
          "Outcome Realization",
          "Resource Map",
          "Value Stream",
          "Project",
          "Migration",
          "Implementation and Migration",
          "Stakeholder");

  private static final Set<String> LOOKUP = Set.copyOf(NAMES);

  private OefViewpoints() {}

  static boolean isNamedByTheExchangeFormat(String viewpoint) {
    return viewpoint != null && LOOKUP.contains(viewpoint);
  }

  /**
   * The closest named viewpoint to {@code viewpoint}, or {@code null} when nothing is close enough
   * for the suggestion to be more use than noise.
   */
  static String nearest(String viewpoint) {
    if (viewpoint == null || viewpoint.isBlank()) {
      return null;
    }
    String lowered = viewpoint.toLowerCase(Locale.ROOT);
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    // Declaration order, so the suggestion is the same on every run and in every JVM.
    for (String candidate : NAMES) {
      int distance = distance(lowered, candidate.toLowerCase(Locale.ROOT));
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    // Two edits covers the realistic typo (a dropped, doubled, swapped or mistyped letter) without
    // proposing an unrelated viewpoint for a name that was never meant to be one of these.
    return bestDistance <= 2 ? best : null;
  }

  private static int distance(String left, String right) {
    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];
    for (int column = 0; column <= right.length(); column++) {
      previous[column] = column;
    }
    for (int row = 1; row <= left.length(); row++) {
      current[0] = row;
      for (int column = 1; column <= right.length(); column++) {
        int substitution =
            previous[column - 1] + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
        current[column] =
            Math.min(substitution, Math.min(previous[column] + 1, current[column - 1] + 1));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[right.length()];
  }
}

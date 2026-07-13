package dev.dediren.plugins.render.svg;

import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgAccessibility;

/**
 * Builds the accessible-name markup placed as the first children of an SVG root, satisfying WCAG
 * 2.2 SC 1.1.1 together with {@code role="img"} on the root itself. The {@code <title>} text is the
 * policy accessibility title when set, otherwise the layout {@code view_id}, otherwise a generic
 * fallback so {@code role="img"} never ships without a name (a blank {@code view_id} is schema
 * legal); the {@code <desc>} is emitted only when the policy supplies a description. Both call
 * sites (the shared document renderer and the sequence renderer) reuse this so the accessible-name
 * recipe stays identical.
 */
public final class SvgAccessibleName {
  private SvgAccessibleName() {}

  private static final String DEFAULT_TITLE = "Diagram";

  public static void markup(SvgWriter w, RenderPolicy policy, String viewId) {
    SvgAccessibility accessibility = policy == null ? null : policy.accessibility();
    String title = firstNonBlank(accessibility == null ? null : accessibility.title(), viewId);
    if (title == null) {
      title = DEFAULT_TITLE;
    }
    String description = accessibility == null ? null : accessibility.description();
    w.start("title").text(title).end();
    if (description != null && !description.isBlank()) {
      w.start("desc").text(description).end();
    }
  }

  private static String firstNonBlank(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
    }
    return null;
  }
}

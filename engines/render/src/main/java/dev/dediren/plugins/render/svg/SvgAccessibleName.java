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
 *
 * <p>{@link #rootLanguage} carries the other half of the same contract onto the root element
 * itself: the language and base direction of that authored prose. It is separate from {@link
 * #markup} only because the attributes must be written while the root start-tag is still open,
 * before any child element exists.
 */
public final class SvgAccessibleName {
  private SvgAccessibleName() {}

  private static final String DEFAULT_TITLE = "Diagram";

  /**
   * Writes {@code xml:lang} and {@code direction} on the open SVG root start-tag when the policy
   * declares them. Both are omitted when unset, so an untagged policy emits the identical root it
   * always did — the attributes are additive, never defaulted. {@code direction} is the SVG
   * presentation attribute, so setting it on the root establishes the base direction every
   * descendant text element inherits.
   *
   * <p>Must be called while the root start-tag is open — that is, before {@link #markup}.
   */
  public static void rootLanguage(SvgWriter w, RenderPolicy policy) {
    SvgAccessibility accessibility = policy == null ? null : policy.accessibility();
    if (accessibility == null) {
      return;
    }
    w.attrIf("xml:lang", blankToNull(accessibility.lang()));
    w.attrIf("direction", blankToNull(accessibility.dir()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

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

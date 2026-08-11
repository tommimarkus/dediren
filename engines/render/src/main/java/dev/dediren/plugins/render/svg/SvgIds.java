package dev.dediren.plugins.render.svg;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mints the {@code id=} attributes of one rendered SVG document, and the {@code url(#…)} tokens
 * that reference them.
 *
 * <p>Every id the renderer emits is composed from a layout-result id — an edge id for a marker, a
 * node or group id for a gradient — and {@code layout-result.schema.json} constrains those in no
 * way: no charset, no uniqueness. Two defects follow from emitting them raw. A duplicate id makes
 * the reference ambiguous, because a user agent binds every {@code url(#…)} to the first match, so
 * the second edge silently paints the first edge's arrowhead. A sloppy charset breaks the reference
 * outright: edge id {@code a)b} yields {@code url(#marker-end-a)b)}, whose CSS url token closes at
 * the first {@code )} and therefore resolves a target that does not exist, silently dropping the
 * marker; a space ends the token the same way. {@link SvgWriter}'s scrub and XML escaping cannot
 * help with either — they make the document well formed, not the id resolvable and unique.
 *
 * <p><strong>Byte identity is the hard constraint.</strong> An id already matching {@code
 * ^[A-Za-z0-9][A-Za-z0-9._-]*$} comes back unchanged on its first occurrence. Every fixture id and
 * every id in the checked-in render goldens is in that set, so the transform below must stay a
 * no-op for them: a "simplification" that normalises, hashes, prefixes, or renumbers every id would
 * move all of the goldens at once while looking tidier. Character replacement and collision
 * suffixing are reached only by input that is already broken.
 *
 * <p>Stateful and per-document by necessity: suffixing a collision means knowing what this document
 * has already minted. One instance belongs to one rendered document and never outlives it. Not
 * thread-safe, and does not need to be — a document is assembled on one thread.
 */
public final class SvgIds {

  /** Substituted for any character outside the safe set; itself in the safe set, so idempotent. */
  private static final char REPLACEMENT = '_';

  /** An empty layout id would compose an id ending in the separator only; this keeps it a name. */
  private static final String EMPTY_FALLBACK = "id";

  private final Set<String> minted = new LinkedHashSet<>();

  /**
   * Mints a document-unique SVG id from {@code candidate}, which is the fully composed id (prefix
   * included), never a bare layout id — composing after minting would defeat both the charset
   * transform and the collision check.
   *
   * <p>A safe, not-yet-used candidate is returned unchanged. Otherwise unsafe characters become
   * {@code _}, and a repeat of an already-minted result takes a {@code -2}, {@code -3}, … suffix.
   * Purely positional, never random or hashed: the same emission order always produces the same
   * ids, which is what makes the goldens an oracle at all.
   */
  public String mint(String candidate) {
    String base = safeIdentifier(candidate);
    String unique = base;
    int occurrence = 1;
    while (!minted.add(unique)) {
      occurrence++;
      unique = base + "-" + occurrence;
    }
    return unique;
  }

  /**
   * The {@code url(#…)} token for an id this document minted. {@code null} in, {@code null} out, so
   * an absent marker stays an absent attribute.
   *
   * <p>Takes the value {@link #mint} returned rather than the raw layout id: that is the coupling
   * this class exists for. A reference built independently of the id it points at can disagree with
   * it — which is exactly how a suffixed duplicate would end up pointing at its neighbour.
   */
  public String reference(String mintedId) {
    if (mintedId == null) {
      return null;
    }
    if (!minted.contains(mintedId)) {
      throw new IllegalStateException(
          "url(#…) reference to an id this document never minted: " + mintedId);
    }
    return "url(#" + mintedId + ")";
  }

  private static String safeIdentifier(String candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return EMPTY_FALLBACK;
    }
    if (isSafeIdentifier(candidate)) {
      return candidate;
    }
    StringBuilder safe = new StringBuilder(candidate.length());
    for (int index = 0; index < candidate.length(); index++) {
      char character = candidate.charAt(index);
      safe.append(isSafeCharacter(character, index == 0) ? character : REPLACEMENT);
    }
    return safe.toString();
  }

  private static boolean isSafeIdentifier(String candidate) {
    for (int index = 0; index < candidate.length(); index++) {
      if (!isSafeCharacter(candidate.charAt(index), index == 0)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The safe set is deliberately narrow ASCII: {@code [A-Za-z0-9._-]}, with the first character
   * restricted to a letter or digit. A leading digit is not a legal XML name start, but it is legal
   * in the layout contract and in every id the renderer already emits, so it passes through rather
   * than triggering a rewrite the byte-identity rule forbids. Non-ASCII letters are replaced even
   * though XML would accept them: predictable output beats maximal fidelity for an identifier that
   * only ever has to round-trip inside this one document.
   */
  private static boolean isSafeCharacter(char character, boolean first) {
    boolean alphanumeric =
        (character >= 'a' && character <= 'z')
            || (character >= 'A' && character <= 'Z')
            || (character >= '0' && character <= '9');
    if (first) {
      return alphanumeric;
    }
    return alphanumeric || character == '.' || character == '_' || character == '-';
  }
}

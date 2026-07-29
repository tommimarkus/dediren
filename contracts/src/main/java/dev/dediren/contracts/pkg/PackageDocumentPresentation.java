package dev.dediren.contracts.pkg;

/**
 * Package-level natural-language metadata: the language and base writing direction of the authored
 * prose a package carries, declared once for every view in it.
 *
 * <p>Unlike the per-view {@link PackagePresentation}, this is not opaque carriage. Both fields feed
 * the render lane, reaching each view's effective render policy as {@code accessibility.lang} /
 * {@code accessibility.dir} and, from there, {@code xml:lang} and {@code direction} on the emitted
 * SVG root. That is why the package earns them: a view's accessible name is authored prose, and
 * prose served to assistive technology with no language tag — or right-to-left prose laid out under
 * an implicit left-to-right base direction — is a defect in Dediren's own artifact, not a
 * consumer's concern. Declaring them per package rather than per view reflects how they actually
 * vary: a package is authored in one language, not one language per diagram.
 *
 * <p>A view's own render policy always wins. Both fields are optional, and a package that sets
 * neither renders exactly as it did before they existed.
 */
public record PackageDocumentPresentation(String lang, String dir) {}

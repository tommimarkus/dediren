package dev.dediren.contracts.render;

/**
 * Accessible-name and natural-language metadata for a rendered SVG. {@code title} names the graphic
 * (the {@code <title>} element, and the accessible name for {@code role="img"}); {@code
 * description} is the optional longer purpose text emitted as {@code <desc>}. Both are optional;
 * when {@code title} is absent the renderer falls back to the layout {@code view_id}, then to a
 * generic default.
 *
 * <p>{@code lang} and {@code dir} tag that text with its natural language and base writing
 * direction, emitted as {@code xml:lang} and {@code direction} on the SVG root. They matter because
 * the accessible name is authored prose: an assistive technology handed a {@code <title>} carrying
 * no language tag has to guess a pronunciation, and right-to-left prose laid out under an implicit
 * left-to-right base direction renders its punctuation and embedded runs in the wrong order. Both
 * are optional and omitted from the output when absent, so a policy that sets neither renders byte
 * for byte as it did before they existed.
 */
public record SvgAccessibility(String title, String description, String lang, String dir) {}

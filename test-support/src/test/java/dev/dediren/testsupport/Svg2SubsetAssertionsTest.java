package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Svg2SubsetAssertionsTest {

  // Requirement sources: https://www.w3.org/TR/SVG2/conform.html and the element/attribute
  // definitions it references. This specimen is authored independently of renderer output.
  private static final String POSITIVE_SPECIMEN =
      """
      <svg xmlns="http://www.w3.org/2000/svg" role="img" width="100" height="80"
           viewBox="0 0 100 80" xml:lang="en" direction="ltr" data-root-kind="specimen">
        <metadata id="provenance">{"source":"independent"}</metadata>
        <title>Subset specimen</title>
        <desc>Every supported element and attribute family</desc>
        <g id="scene" data-scene-id="main" font-family="sans-serif" font-size="12"
           font-weight="600" font-style="italic" fill-opacity="0.9" stroke-opacity="0.8"
           stroke-dasharray="3 2">
          <linearGradient id="linear" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#fff" stop-opacity="0.5"/>
            <stop offset="1" stop-color="rebeccapurple"/>
          </linearGradient>
          <radialGradient id="radial"><stop offset="1" stop-color="rgb(1, 2, 3)"/></radialGradient>
          <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto">
            <path d="M 1 1 L 9 5 L 1 9 Z" fill="none" stroke="currentColor"
                  stroke-width="1" stroke-linecap="round" stroke-linejoin="bevel"/>
          </marker>
          <rect x="1" y="2" width="20" height="10" rx="2" ry="3" fill="url(#linear)"
                fill-opacity="0.7" stroke="#12345678" stroke-width="1.5"
                stroke-opacity="0.6" stroke-dasharray="4, 2"/>
          <circle cx="30" cy="10" r="5" fill="transparent" stroke="blue"/>
          <ellipse cx="45" cy="10" rx="8" ry="4" fill="url(#radial)"/>
          <line x1="1" y1="25" x2="20" y2="25" stroke="#000" marker-start="url(#arrow)"
                marker-end="none"/>
          <polyline points="1,30 10,35 20,30" fill="none" stroke="#000"/>
          <polygon points="25,30 35,35 45,30" fill="#abc" stroke="none"/>
          <path d="M 1 45 L 5 45 H 10 V 50 C 11 51 12 52 13 53 Q 14 54 15 55
                   A 2 3 45 0 1 20 60 Z" fill="none" stroke="rgba(1, 2, 3, 0.5)"
                marker-end="url(#arrow)"/>
          <text x="2" y="72" text-anchor="start" dominant-baseline="middle" fill="#000"
                fill-opacity="1" stroke="none" stroke-width="0" font-family="serif"
                font-size="10" font-weight="normal" font-style="normal" textLength="40"
                lengthAdjust="spacing" data-label="sample">
            <tspan x="2" dy="0" textLength="30" lengthAdjust="spacing">Label</tspan>
          </text>
        </g>
      </svg>
      """;

  @Test
  void acceptsIndependentSpecimenCoveringTheWholeSubset() {
    Svg2SubsetAssertions.assertConforms(POSITIVE_SPECIMEN);
  }

  @Test
  void rejectsWrongOrMixedNamespacesAndUnknownOrMisplacedElements() {
    assertRejected("<svg xmlns=\"urn:not-svg\"/>", "namespace");
    assertRejected(
        svg("<g><rect xmlns=\"urn:not-svg\" width=\"1\" height=\"1\"/></g>"), "namespace");
    assertRejected(svg("<script/>"), "element <script>");
    assertRejected(
        svg("<g><stop offset=\"0\" stop-color=\"#000\"/></g>"), "not allowed inside <g>");
    assertRejected(svg("<tspan>orphan</tspan>"), "not allowed inside <svg>");
  }

  @Test
  void rejectsUnknownOrMisplacedAttributesAndInvalidDataAttributes() {
    assertRejected(svg("<rect width=\"1\" height=\"1\" mystery=\"x\"/>"), "attribute rect@mystery");
    assertRejected(svg("<circle width=\"1\" cx=\"1\" cy=\"1\" r=\"1\"/>"), "circle@width");
    assertRejected(svg("<g data-Bad=\"x\"/>"), "data-Bad");
    assertRejected(svg("<g data-=\"x\"/>"), "data-");
  }

  @Test
  void rejectsMalformedNumbersAndOutOfRangeValues() {
    assertRejected(svg("<rect x=\"NaN\" width=\"1\" height=\"1\"/>"), "rect@x");
    assertRejected(svg("<rect width=\"-1\" height=\"1\"/>"), "rect@width");
    assertRejected(svg("<g fill-opacity=\"1.01\"/>"), "fill-opacity");
    assertRejected(svg("<svg/>"), "element <svg>");
  }

  @Test
  void rejectsInvalidEnumerationsPaintAndReferences() {
    assertRejected(svg("<text text-anchor=\"center\">x</text>"), "text-anchor");
    assertRejected(svg("<path d=\"M 0 0\" stroke-linecap=\"curved\"/>"), "stroke-linecap");
    assertRejected(
        svg("<rect width=\"1\" height=\"1\" fill=\"url(https://example.test/x)\"/>"), "paint");
    assertRejected(svg("<rect width=\"1\" height=\"1\" fill=\"url(#missing)\"/>"), "missing");
    assertRejected(
        svg(
            "<linearGradient id=\"paint\"><stop offset=\"0\" stop-color=\"#000\"/></linearGradient>"
                + "<path d=\"M 0 0\" marker-end=\"url(#paint)\"/>"),
        "marker");
  }

  @Test
  void rejectsMalformedPointListsIdsAndPathData() {
    assertRejected(svg("<polygon points=\"0,0 1\"/>"), "points");
    assertRejected(svg("<g id=\"9bad\"/>"), "id");
    assertRejected(svg("<g id=\"same\"/><g id=\"same\"/>"), "duplicate");
    assertRejected(svg("<path d=\"L 0 0\"/>"), "path@d");
    assertRejected(svg("<path d=\"M 0\"/>"), "path@d");
    assertRejected(svg("<path d=\"M 0 0 S 1 1 2 2\"/>"), "path@d");
    assertRejected(svg("<path d=\"M 0 0 A 1 1 0 2 0 3 3\"/>"), "path@d");
    assertRejected(svg("<path d=\"M 0 0 Z 1 1\"/>"), "path@d");
  }

  @Test
  void rejectsDoctypesWithoutResolvingExternalEntities() {
    assertRejected(
        "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + svg("<metadata>&xxe;</metadata>"),
        "well-formed secure XML");
  }

  private static String svg(String children) {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" width=\"10\" height=\"10\" viewBox=\"0 0 10 10\">"
        + children
        + "</svg>";
  }

  private static void assertRejected(String svg, String diagnostic) {
    assertThatThrownBy(() -> Svg2SubsetAssertions.assertConforms(svg))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(diagnostic);
  }
}

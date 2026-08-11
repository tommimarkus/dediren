package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SvgPaintAuditContrastTest {

  @Test
  void builtInThemesUseNormalAndLargeTextContrastBaselines() throws Exception {
    SvgPaintAudit.Report normal = SvgPaintAudit.audit(nodeSvg("#ffffff", "#777777", "16", "400"));
    assertThat(normal.violations())
        .filteredOn(violation -> violation.code().equals("contrast_baseline"))
        .singleElement()
        .satisfies(
            violation -> {
              assertThat(violation.observed()).contains("4.48");
              assertThat(violation.expected()).contains("4.50");
            });

    assertThat(SvgPaintAudit.audit(nodeSvg("#ffffff", "#777777", "24", "400")).violations())
        .noneMatch(violation -> violation.code().equals("contrast_baseline"));
    assertThat(SvgPaintAudit.audit(nodeSvg("#ffffff", "#777777", "18.66", "700")).violations())
        .noneMatch(violation -> violation.code().equals("contrast_baseline"));
  }

  @Test
  void flatAlphaColorsAreCompositedBeforeTheBaselineRatio() throws Exception {
    String svg =
        nodeSvg("#000000", "#ffffff", "16", "400")
            .replace("fill=\"#000000\"", "fill=\"#000000\" fill-opacity=\"0.5\"");
    SvgPaintAudit.Report report = SvgPaintAudit.audit(svg);
    assertThat(report.violations())
        .filteredOn(violation -> violation.code().equals("contrast_baseline"))
        .singleElement();
  }

  @Test
  void translucentNodeFillCompositesAgainstTheAuthoredDarkPage() throws Exception {
    String svg =
        nodeSvg("#ffffff", "#767676", "16", "400")
            .replace(
                "<rect width=\"120\" height=\"80\" fill=\"#ffffff\"/>",
                "<rect width=\"120\" height=\"80\" fill=\"#000000\"/>")
            .replace(
                "width=\"100\" height=\"60\" fill=\"#ffffff\"",
                "width=\"100\" height=\"60\" fill=\"#ffffff\" fill-opacity=\"0.5\"");

    assertThat(SvgPaintAudit.audit(svg).violations())
        .filteredOn(violation -> violation.code().equals("contrast_baseline"))
        .singleElement();
  }

  @Test
  void gradientsAreNotMeasurableAndUserThemesNeverBlock() throws Exception {
    String gradient =
        nodeSvg("url(#gradient)", "#777777", "16", "400")
            .replace(
                "<rect width=\"120\"",
                "<defs><linearGradient id=\"gradient\"><stop offset=\"0\""
                    + " stop-color=\"#ffffff\"/><stop offset=\"1\""
                    + " stop-color=\"#000000\"/></linearGradient></defs><rect width=\"120\"");
    SvgPaintAudit.Report notMeasurable = SvgPaintAudit.audit(gradient);
    assertThat(notMeasurable.advisories())
        .filteredOn(advisory -> advisory.code().equals("not_measurable"))
        .singleElement();

    SvgPaintAudit.Report userTheme =
        SvgPaintAudit.audit(
            nodeSvg("#ffffff", "#777777", "16", "400"), SvgPaintAudit.ThemeOwnership.USER_SUPPLIED);
    assertThat(userTheme.violations())
        .noneMatch(violation -> violation.code().equals("contrast_baseline"));
    assertThat(userTheme.advisories())
        .anyMatch(advisory -> advisory.code().equals("contrast_baseline"));
  }

  private static String nodeSvg(
      String shapeFill, String textFill, String fontSize, String fontWeight) {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"80\" viewBox=\"0 0 120"
        + " 80\"><rect width=\"120\" height=\"80\" fill=\"#ffffff\"/><g"
        + " font-family=\"Liberation Sans\"><g data-dediren-node-id=\"n\"><rect"
        + " data-dediren-node-shape=\"rectangle\" x=\"10\" y=\"10\" width=\"100\""
        + " height=\"60\" fill=\""
        + shapeFill
        + "\"/><text x=\"60\" y=\"40\" text-anchor=\"middle\" dominant-baseline=\"middle\" fill=\""
        + textFill
        + "\" font-size=\""
        + fontSize
        + "\" font-weight=\""
        + fontWeight
        + "\">Label</text></g></g></svg>";
  }
}

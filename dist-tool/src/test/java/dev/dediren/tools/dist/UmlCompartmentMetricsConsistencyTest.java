package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the UML classifier compartment metrics against cross-plugin drift. The semantics-uml
 * sizing module and the render engine are independent leaf libraries behind engine-api (ArchUnit
 * forbids a compile edge between them), yet both must agree on the compartment geometry — title row
 * height, title padding, the 28px title minimum, member row height, compartment padding — and on
 * the stereotype title lines: the sizer budgets a second title row using a hard-coded character
 * count, the renderer draws the actual guillemet string. They were previously kept equal only by
 * being numeric twins. This converts that into an enforced invariant (the
 * ArchimateLabelReserveConsistencyTest pattern): a one-sided edit fails CI instead of silently
 * skewing node sizing against drawing.
 */
class UmlCompartmentMetricsConsistencyTest {
  private static final Path SIZING =
      Path.of("semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlLayoutSizing.java");
  private static final Path DECORATORS =
      Path.of(
          "engines/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlDecorators.java");

  // Sizing side: named constants plus the title-height minimum and the stereotype char counts.
  private static final Pattern SIZING_TITLE_ROW =
      Pattern.compile("UML_TITLE_ROW_HEIGHT\\s*=\\s*([0-9.]+);");
  private static final Pattern SIZING_TITLE_PADDING =
      Pattern.compile("UML_TITLE_PADDING\\s*=\\s*([0-9.]+);");
  private static final Pattern SIZING_MEMBER_ROW =
      Pattern.compile("UML_MEMBER_ROW_HEIGHT\\s*=\\s*([0-9.]+);");
  private static final Pattern SIZING_COMPARTMENT_PADDING =
      Pattern.compile("UML_COMPARTMENT_PADDING\\s*=\\s*([0-9.]+);");
  private static final Pattern SIZING_TITLE_MIN =
      Pattern.compile("UML_TITLE_ROW_HEIGHT \\+ UML_TITLE_PADDING,\\s*([0-9.]+)\\)");
  private static final Pattern SIZING_STEREOTYPE_COUNT =
      Pattern.compile("case \"(Enumeration|Interface|DataType)\" -> ([0-9]+);");

  // Renderer side: the inline title/member height expressions and the drawn stereotype strings.
  private static final Pattern DECORATOR_TITLE =
      Pattern.compile(
          "Math\\.max\\(([0-9.]+), titleLines\\.size\\(\\) \\* ([0-9.]+) \\+ ([0-9.]+)\\)");
  private static final Pattern DECORATOR_MEMBER =
      Pattern.compile("attributeLines\\.size\\(\\) \\* ([0-9.]+) \\+ ([0-9.]+)");
  private static final Pattern DECORATOR_STEREOTYPE =
      Pattern.compile("titleLines\\.add\\(\"(«[^»]+»)\"\\)");

  @Test
  void compartmentMetricsMatchAcrossSizingAndRenderer() throws IOException {
    Path repoRoot = repoRoot();
    String sizing = Files.readString(repoRoot.resolve(SIZING), StandardCharsets.UTF_8);
    String decorators = Files.readString(repoRoot.resolve(DECORATORS), StandardCharsets.UTF_8);

    Matcher title = DECORATOR_TITLE.matcher(decorators);
    assertThat(title.find())
        .as("title-height expression not found in " + DECORATORS + " (reshaped?)")
        .isTrue();
    assertThat(title.group(1))
        .as("title-height minimum must match sizing's umlTitleHeight floor")
        .isEqualTo(one(sizing, SIZING_TITLE_MIN, "title minimum"));
    assertThat(title.group(2))
        .as("title row height must match sizing's UML_TITLE_ROW_HEIGHT")
        .isEqualTo(one(sizing, SIZING_TITLE_ROW, "UML_TITLE_ROW_HEIGHT"));
    assertThat(title.group(3))
        .as("title padding must match sizing's UML_TITLE_PADDING")
        .isEqualTo(one(sizing, SIZING_TITLE_PADDING, "UML_TITLE_PADDING"));

    Matcher member = DECORATOR_MEMBER.matcher(decorators);
    assertThat(member.find())
        .as("member-height expression not found in " + DECORATORS + " (reshaped?)")
        .isTrue();
    assertThat(member.group(1))
        .as("member row height must match sizing's UML_MEMBER_ROW_HEIGHT")
        .isEqualTo(one(sizing, SIZING_MEMBER_ROW, "UML_MEMBER_ROW_HEIGHT"));
    assertThat(member.group(2))
        .as("compartment padding must match sizing's UML_COMPARTMENT_PADDING")
        .isEqualTo(one(sizing, SIZING_COMPARTMENT_PADDING, "UML_COMPARTMENT_PADDING"));
  }

  @Test
  void stereotypeCharCountsMatchTheDrawnGuillemetStrings() throws IOException {
    Path repoRoot = repoRoot();
    String sizing = Files.readString(repoRoot.resolve(SIZING), StandardCharsets.UTF_8);
    String decorators = Files.readString(repoRoot.resolve(DECORATORS), StandardCharsets.UTF_8);

    Map<String, Integer> sizingCounts = new LinkedHashMap<>();
    Matcher counts = SIZING_STEREOTYPE_COUNT.matcher(sizing);
    while (counts.find()) {
      sizingCounts.put(counts.group(1), Integer.parseInt(counts.group(2)));
    }
    assertThat(sizingCounts)
        .as("stereotype char-count switch not found in " + SIZING + " (reshaped?)")
        .containsKeys("Enumeration", "Interface", "DataType");

    Map<String, Integer> drawnLengths = new LinkedHashMap<>();
    Matcher drawn = DECORATOR_STEREOTYPE.matcher(decorators);
    while (drawn.find()) {
      String text = drawn.group(1);
      // «enumeration» -> Enumeration: strip guillemets, capitalize, keep camel humps.
      String bare = text.substring(1, text.length() - 1);
      String typeName = Character.toUpperCase(bare.charAt(0)) + bare.substring(1);
      drawnLengths.put(typeName, text.length());
    }
    assertThat(drawnLengths)
        .as("stereotype title strings not found in " + DECORATORS + " (reshaped?)")
        .containsKeys("Enumeration", "Interface", "DataType");

    // The sizer budgets exactly the characters the renderer draws — including the guillemets.
    for (Map.Entry<String, Integer> entry : drawnLengths.entrySet()) {
      assertThat(sizingCounts.get(entry.getKey()))
          .as(
              "sizing char count for "
                  + entry.getKey()
                  + " must equal the drawn stereotype string's length")
          .isEqualTo(entry.getValue());
    }
  }

  private static String one(String source, Pattern pattern, String label) {
    Matcher matcher = pattern.matcher(source);
    assertThat(matcher.find()).as(label + " not found in " + SIZING + " (renamed?)").isTrue();
    return matcher.group(1);
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

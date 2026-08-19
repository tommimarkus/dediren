package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the draw.io export shape/palette tables against ordering and coverage drift.
 *
 * <p>This is an IP-hygiene enforcement test, not an ordinary coverage check. {@code
 * DrawioShapes}/{@code DrawioPalette} are keyed and sequenced by dediren's own {@code
 * Archimate.ELEMENT_TYPES} declaration order, deliberately never reordered to match draw.io's own
 * shape-library layout or any third-party palette — see both classes' javadoc for why that
 * provenance matters. Without this test, that ordering claim is only a comment, and a future edit
 * that quietly re-sorted the table (or dropped/duplicated an entry) would compile and pass every
 * other test while silently breaking the provenance argument. Modelled on {@link
 * ArchimateElementVocabularyConsistencyTest}, which reads the same {@code archimate} vocabulary
 * module as text because {@code dist-tool} does not, and must not, depend on it (nor on {@code
 * engines/drawio}: the engine keeps the boundary described in its own javadoc). Every source below
 * is read as plain text for the same reason.
 */
class DrawioShapeCoverageTest {

  private static final Path VOCABULARY =
      Path.of("archimate/src/main/java/dev/dediren/archimate/Archimate.java");
  private static final Path DRAWIO_SHAPES =
      Path.of(
          "engines/drawio/src/main/java/dev/dediren/plugins/drawio/style/DrawioShapes.java");
  private static final Path DRAWIO_PALETTE =
      Path.of(
          "engines/drawio/src/main/java/dev/dediren/plugins/drawio/style/DrawioPalette.java");
  private static final Path SHIPPED_POLICY = Path.of("fixtures/render-policy/archimate-svg.json");

  @Test
  void drawioShapesKeySequenceMatchesTheElementVocabularyDeclarationOrder() throws IOException {
    assertThat(tablePutKeysInOrder(DRAWIO_SHAPES))
        .as(
            "DrawioShapes must be keyed and sequenced exactly as Archimate.ELEMENT_TYPES declares"
                + " it, not reordered to match draw.io's own shape library or any other palette")
        .containsExactlyElementsOf(elementTypesInOrder());
  }

  @Test
  void drawioShapesCoversEveryElementTypeAndNothingElse() throws IOException {
    assertThat(new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_SHAPES)))
        .as(
            "an element type missing here exports with the generic fallback shape, and an extra"
                + " key here names a type the vocabulary does not accept")
        .isEqualTo(new LinkedHashSet<>(elementTypesInOrder()));
  }

  @Test
  void drawioPaletteElementKeysMatchTheShippedRenderPolicysNodeTypeOverrides() throws IOException {
    assertThat(new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_PALETTE)))
        .as(
            "DrawioPalette is sourced from the shipped ArchiMate render policy's"
                + " node_type_overrides vocabulary; the two must name exactly the same element"
                + " types or a `.drawio` export drifts from the SVG dediren renders for the same"
                + " model")
        .isEqualTo(nodeTypeOverrideKeys());
  }

  /** The element type names {@code Archimate.ELEMENT_TYPES} declares, in declaration order. */
  private static List<String> elementTypesInOrder() throws IOException {
    String source = Files.readString(repoRoot().resolve(VOCABULARY), StandardCharsets.UTF_8);
    int start = source.indexOf("ELEMENT_TYPES =");
    String declaration = source.substring(start, source.indexOf(");", start));
    return matchAll(declaration, "\"([A-Z][A-Za-z]+)\"");
  }

  /** The {@code table.put("Type", ...)} keys in {@code file}, in the order they are written. */
  private static List<String> tablePutKeysInOrder(Path file) throws IOException {
    String source = Files.readString(repoRoot().resolve(file), StandardCharsets.UTF_8);
    return matchAll(source, "table\\.put\\(\"([A-Z][A-Za-z]+)\"");
  }

  /** The element-type keys of the shipped policy's {@code node_type_overrides} object. */
  private static Set<String> nodeTypeOverrideKeys() throws IOException {
    String policy = Files.readString(repoRoot().resolve(SHIPPED_POLICY), StandardCharsets.UTF_8);
    int start = policy.indexOf("\"node_type_overrides\"");
    int end = policy.indexOf("\"edge_type_overrides\"", start);
    String section = policy.substring(start, end);
    return new LinkedHashSet<>(matchAll(section, "\"([A-Z][A-Za-z]+)\":\\s*\\{"));
  }

  private static List<String> matchAll(String text, String pattern) {
    var found = new java.util.ArrayList<String>();
    Matcher matcher = Pattern.compile(pattern).matcher(text);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

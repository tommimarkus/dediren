package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the draw.io UML shape table and both relationship-notation tables against ordering and
 * coverage drift, exactly as {@link DrawioShapeCoverageTest} does for the ArchiMate element table.
 *
 * <p>This is an IP-hygiene enforcement test, not an ordinary coverage check. The tables it pins are
 * keyed and sequenced by dediren's own vocabularies — {@code Uml.java} and {@code Archimate.java} —
 * and deliberately never reordered to match draw.io's own UML palettes, whose grouping and section
 * structure are theirs. Without a red build, that provenance claim is only a comment.
 *
 * <p>Every source below is read as plain text because {@code dist-tool} does not, and must not,
 * depend on the {@code uml}, {@code archimate}, or {@code engines/drawio} modules.
 *
 * <h2>Why the UML element vocabulary needs a rule the ArchiMate one does not</h2>
 *
 * <p>{@code Archimate.java} declares one ordered {@code ELEMENT_TYPES} list. {@code Uml.java} has
 * no such constant: its element vocabulary is the union {@code isNamedElementType} forms over seven
 * separate constants, three of which are unordered {@link Set}s. Textual declaration order is
 * therefore the only well-defined sequence, and "which constants count" is a decision that could
 * silently change — so {@link #umlElementVocabularyIsStillTheUnionOfExactlySevenPredicates} pins
 * the predicate list itself. Adding an eighth kind of element to {@code Uml} fails that test rather
 * than quietly leaving the new types undrawn.
 */
class DrawioNotationCoverageTest {

  private static final Path UML_VOCABULARY = Path.of("uml/src/main/java/dev/dediren/uml/Uml.java");
  private static final Path ARCHIMATE_VOCABULARY =
      Path.of("archimate/src/main/java/dev/dediren/archimate/Archimate.java");
  private static final Path DRAWIO_UML_SHAPES =
      Path.of("engines/drawio/src/main/java/dev/dediren/plugins/drawio/style/DrawioUmlShapes.java");
  private static final Path DRAWIO_EDGE_STYLES =
      Path.of(
          "engines/drawio/src/main/java/dev/dediren/plugins/drawio/style/DrawioEdgeStyles.java");
  private static final Path SHIPPED_ARCHIMATE_POLICY =
      Path.of("fixtures/render-policy/archimate-svg.json");
  private static final Path SHIPPED_UML_POLICY = Path.of("fixtures/render-policy/uml-svg.json");

  /**
   * The seven constants {@code Uml.isNamedElementType} unions, in the order it unions them. This
   * list is the test's definition of "the UML element vocabulary" and is pinned against the
   * predicate below.
   */
  private static final List<String> UML_ELEMENT_CONSTANTS =
      List.of(
          "STRUCTURAL_TYPES",
          "ACTIVITY_TYPES",
          "SEQUENCE_TYPES",
          "STATE_MACHINE_TYPES",
          "USE_CASE_TYPES",
          "COMPONENT_TYPES",
          "DEPLOYMENT_TYPES");

  // ---------------------------------------------------------------- UML element shapes

  @Test
  void umlElementVocabularyIsStillTheUnionOfExactlySevenPredicates() throws IOException {
    String source = Files.readString(repoRoot().resolve(UML_VOCABULARY), StandardCharsets.UTF_8);
    int start = source.indexOf("private static boolean isNamedElementType(");
    assertThat(start).as("Uml.isNamedElementType must still exist").isNotNegative();
    String body = source.substring(start, source.indexOf('}', start));

    assertThat(matchAll(body, "is([A-Za-z]+)\\(value\\)"))
        .as(
            "the UML element vocabulary is the union isNamedElementType forms; if it gains or loses"
                + " a member, DrawioUmlShapes and UML_ELEMENT_CONSTANTS here must both follow, or"
                + " the new element types silently export as neutral rectangles")
        .containsExactly(
            "StructuralType",
            "ActivityType",
            "SequenceType",
            "StateMachineType",
            "UseCaseType",
            "ComponentType",
            "DeploymentType");
  }

  @Test
  void drawioUmlShapesKeySequenceMatchesTheElementVocabularyDeclarationOrder() throws IOException {
    assertThat(tablePutKeysInOrder(DRAWIO_UML_SHAPES, "table"))
        .as(
            "DrawioUmlShapes must be keyed and sequenced exactly as Uml.java declares its element"
                + " constants, not reordered to match either of draw.io's UML palettes")
        .containsExactlyElementsOf(umlElementTypesInOrder());
  }

  @Test
  void drawioUmlShapesCoversEveryElementTypeAndNothingElse() throws IOException {
    assertThat(new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_UML_SHAPES, "table")))
        .as(
            "an element type missing here exports with the generic fallback shape, and an extra key"
                + " here names a type the vocabulary does not accept")
        .isEqualTo(new LinkedHashSet<>(umlElementTypesInOrder()));
  }

  @Test
  void theThreeTypeNamesBothVocabulariesDeclareAreStillThreeAndStillCollide() throws IOException {
    Set<String> shared = new LinkedHashSet<>(umlElementTypesInOrder());
    shared.retainAll(new LinkedHashSet<>(archimateElementTypesInOrder()));

    assertThat(shared)
        .as(
            "Node, Device and Artifact are declared by both vocabularies, which is why the two"
                + " shape tables stay separate and the exporter picks between them by view kind. A"
                + " fourth collision needs the same deliberate handling, not a silent merge")
        .containsExactly("Node", "Device", "Artifact");
  }

  // ---------------------------------------------------------------- relationship notation

  @Test
  void archimateEdgeStyleKeySequenceMatchesTheRelationshipVocabularyDeclarationOrder()
      throws IOException {
    assertThat(tablePutKeysInOrder(DRAWIO_EDGE_STYLES, "archimate"))
        .as(
            "the ArchiMate relationship table must be keyed and sequenced exactly as"
                + " Archimate.RELATIONSHIP_TYPES declares it")
        .containsExactlyElementsOf(relationshipTypesInOrder(ARCHIMATE_VOCABULARY));
  }

  @Test
  void umlEdgeStyleKeySequenceMatchesTheRelationshipVocabularyDeclarationOrder()
      throws IOException {
    assertThat(tablePutKeysInOrder(DRAWIO_EDGE_STYLES, "uml"))
        .as(
            "the UML relationship table must be keyed and sequenced exactly as"
                + " Uml.RELATIONSHIP_TYPES declares it")
        .containsExactlyElementsOf(relationshipTypesInOrder(UML_VOCABULARY));
  }

  @Test
  void bothEdgeTablesCoverTheirWholeVocabularyAndNothingElse() throws IOException {
    assertThat(new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_EDGE_STYLES, "archimate")))
        .as("an unmapped relationship type exports as a plain directed line")
        .isEqualTo(new LinkedHashSet<>(relationshipTypesInOrder(ARCHIMATE_VOCABULARY)));
    assertThat(new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_EDGE_STYLES, "uml")))
        .as("an unmapped relationship type exports as a plain directed line")
        .isEqualTo(new LinkedHashSet<>(relationshipTypesInOrder(UML_VOCABULARY)));
  }

  @Test
  void bothEdgeTablesNameTheSameRelationshipsAsTheShippedRenderPolicies() throws IOException {
    // The notation dediren draws in SVG and the notation it exports to draw.io are the same
    // decision; sourcing both from the same vocabulary is what keeps the two pictures agreeing.
    assertThat(new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_EDGE_STYLES, "archimate")))
        .isEqualTo(edgeTypeOverrideKeys(SHIPPED_ARCHIMATE_POLICY));

    Set<String> umlPolicyKeys = edgeTypeOverrideKeys(SHIPPED_UML_POLICY);
    Set<String> umlTableKeys = new LinkedHashSet<>(tablePutKeysInOrder(DRAWIO_EDGE_STYLES, "uml"));
    assertThat(umlTableKeys)
        .as(
            "the UML render policy does not override Message — the sequence renderer styles it per"
                + " message sort instead — so the export table is the policy's keys plus Message,"
                + " and nothing else")
        .containsAll(umlPolicyKeys);
    umlTableKeys.removeAll(umlPolicyKeys);
    assertThat(umlTableKeys).containsExactly("Message");
  }

  // ---------------------------------------------------------------- readers

  /**
   * The UML element type names, in the order {@link #UML_ELEMENT_CONSTANTS} declares them and, at
   * each constant, the order its source text lists them. A name declared by more than one constant
   * keeps its first position.
   */
  private static List<String> umlElementTypesInOrder() throws IOException {
    String source = Files.readString(repoRoot().resolve(UML_VOCABULARY), StandardCharsets.UTF_8);
    var ordered = new ArrayList<String>();
    for (String constant : UML_ELEMENT_CONSTANTS) {
      for (String type : matchAll(declarationOf(source, constant), "\"([A-Z][A-Za-z]+)\"")) {
        if (!ordered.contains(type)) {
          ordered.add(type);
        }
      }
    }
    return ordered;
  }

  private static List<String> archimateElementTypesInOrder() throws IOException {
    String source =
        Files.readString(repoRoot().resolve(ARCHIMATE_VOCABULARY), StandardCharsets.UTF_8);
    return matchAll(declarationOf(source, "ELEMENT_TYPES"), "\"([A-Z][A-Za-z]+)\"");
  }

  private static List<String> relationshipTypesInOrder(Path vocabulary) throws IOException {
    String source = Files.readString(repoRoot().resolve(vocabulary), StandardCharsets.UTF_8);
    return matchAll(declarationOf(source, "RELATIONSHIP_TYPES"), "\"([A-Z][A-Za-z]+)\"");
  }

  /**
   * The text of one {@code NAME = List.of(...)} / {@code Set.of(...)} declaration.
   *
   * <p>Matched on {@code NAME =} preceded by a non-identifier character so {@code
   * RELATIONSHIP_TYPES} does not match {@code STRUCTURAL_RELATIONSHIP_TYPES}, which is a real
   * constant in {@code Uml.java} and a real way to read the wrong list.
   */
  private static String declarationOf(String source, String constant) {
    Matcher matcher =
        Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(constant) + "\\s*=").matcher(source);
    if (!matcher.find()) {
      throw new AssertionError("no declaration of " + constant);
    }
    return source.substring(matcher.end(), source.indexOf(");", matcher.end()));
  }

  /**
   * The {@code <map>.put("Type", ...)} keys in {@code file}, in the order they are written.
   *
   * <p>The {@code \s*} after the parenthesis is load-bearing: google-java-format wraps a long
   * {@code put(} onto the following line, so a pattern anchored on {@code put("} sees only the
   * entries that happen to fit on one line and silently reads a short table.
   */
  private static List<String> tablePutKeysInOrder(Path file, String map) throws IOException {
    String source = Files.readString(repoRoot().resolve(file), StandardCharsets.UTF_8);
    return matchAll(source, map + "\\.put\\(\\s*\"([A-Z][A-Za-z]+)\"");
  }

  /** The relationship-type keys of a shipped policy's {@code edge_type_overrides} object. */
  private static Set<String> edgeTypeOverrideKeys(Path policyFile) throws IOException {
    String policy = Files.readString(repoRoot().resolve(policyFile), StandardCharsets.UTF_8);
    int start = policy.indexOf("\"edge_type_overrides\"");
    // Bounded at the next section, not at end of file: group_type_overrides keys "Grouping" the
    // same way, and an unbounded slice would read it as a relationship type.
    int end = policy.indexOf("\"group_type_overrides\"", start);
    String section = policy.substring(start, end);
    return new LinkedHashSet<>(matchAll(section, "\"([A-Z][A-Za-z]+)\":\\s*\\{"));
  }

  private static List<String> matchAll(String text, String pattern) {
    var found = new ArrayList<String>();
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

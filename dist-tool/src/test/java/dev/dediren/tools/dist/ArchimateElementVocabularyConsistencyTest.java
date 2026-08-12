package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the ArchiMate element vocabulary against cross-copy drift.
 *
 * <p>The list of element types is written out independently in five places that cannot import one
 * another: the {@code archimate} vocabulary module, the {@code contracts} render-decorator
 * enumeration, the published render-policy schema, the shipped ArchiMate render policy, and the
 * bundled agent guide. They agree today and nothing enforced it, so a sixty-third element added to
 * the vocabulary alone would compile, pass the suite, and then render undecorated and export
 * unstyled — the failure arriving as a wrong-looking diagram rather than a red build.
 *
 * <p>The two remaining copies are pinned where they live: the legality model's category map by
 * {@code ArchimateRelationshipLegalityConformanceTest}, which also pins that test's own category
 * sets.
 */
class ArchimateElementVocabularyConsistencyTest {

  private static final Path VOCABULARY =
      Path.of("archimate/src/main/java/dev/dediren/archimate/Archimate.java");
  private static final Path DECORATORS =
      Path.of("contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java");
  private static final Path RENDER_POLICY_SCHEMA = Path.of("schemas/render-policy.schema.json");
  private static final Path SHIPPED_POLICY = Path.of("fixtures/render-policy/archimate-svg.json");
  private static final Path AGENT_GUIDE = Path.of("docs/agent-usage.md");

  @Test
  void everyElementTypeHasARenderDecorator() throws IOException {
    assertThat(decoratorTokens(DECORATORS, "@JsonProperty\\(\"(archimate_[a-z0-9_]+)\"\\)"))
        .as(
            "every ArchiMate element type needs an SvgNodeDecorator constant, or nodes of that type"
                + " render undecorated")
        .isEqualTo(elementTypes());
  }

  @Test
  void everyElementTypeIsAcceptedByTheRenderPolicySchema() throws IOException {
    assertThat(decoratorTokens(RENDER_POLICY_SCHEMA, "\"(archimate_[a-z0-9_]+)\""))
        .as(
            "every ArchiMate element type needs its decorator in the published render-policy"
                + " schema enum, or a policy naming it fails schema validation")
        .isEqualTo(elementTypes());
  }

  @Test
  void everyElementTypeIsStyledByTheShippedArchimatePolicy() throws IOException {
    assertThat(decoratorTokens(SHIPPED_POLICY, "\"decorator\": \"(archimate_[a-z0-9_]+)\""))
        .as(
            "every ArchiMate element type needs a row in the shipped ArchiMate render policy, or"
                + " nodes of that type fall back to generic styling")
        .isEqualTo(elementTypes());
  }

  @Test
  void theAgentGuideListsExactlyTheAcceptedElementTypes() throws IOException {
    String guide = Files.readString(repoRoot().resolve(AGENT_GUIDE), StandardCharsets.UTF_8);
    int start = guide.indexOf("Elements: `");
    assertThat(start)
        .as("the agent guide's ArchiMate element list moved or was renamed")
        .isNotEqualTo(-1);
    String list = guide.substring(start, guide.indexOf("\n\n", start));

    var listed = new LinkedHashSet<String>();
    Matcher matcher = Pattern.compile("`([A-Z][A-Za-z]+)`").matcher(list);
    while (matcher.find()) {
      listed.add(matcher.group(1));
    }

    assertThat(listed)
        .as(
            "the guide ships inside the bundle and is the only element list an agent consumer"
                + " sees; an element missing here is unauthorable in practice")
        .isEqualTo(elementTypes());
  }

  /** The element type names the {@code archimate} vocabulary module accepts. */
  private static Set<String> elementTypes() throws IOException {
    String source = Files.readString(repoRoot().resolve(VOCABULARY), StandardCharsets.UTF_8);
    int start = source.indexOf("ELEMENT_TYPES =");
    String declaration = source.substring(start, source.indexOf(");", start));
    var types = new LinkedHashSet<String>();
    Matcher matcher = Pattern.compile("\"([A-Z][A-Za-z]+)\"").matcher(declaration);
    while (matcher.find()) {
      types.add(matcher.group(1));
    }
    return types;
  }

  /**
   * The one decorator token that is not the plain snake_case of its element name. §10.2.1 calls the
   * element {@code Node}, but its token is {@code archimate_technology_node}, so an agent deriving
   * the token mechanically writes {@code archimate_node} and is rejected by the enum. Renaming it
   * would break every existing render policy, so the asymmetry is recorded here instead — naming it
   * in the one test that would otherwise trip over it, rather than leaving it to be rediscovered.
   */
  private static final String TECHNOLOGY_NODE_TOKEN = "TechnologyNode";

  /** Snake-case {@code archimate_*} tokens in {@code file}, as the element names they encode. */
  private static Set<String> decoratorTokens(Path file, String pattern) throws IOException {
    Matcher matcher =
        Pattern.compile(pattern)
            .matcher(Files.readString(repoRoot().resolve(file), StandardCharsets.UTF_8));
    var names = new LinkedHashSet<String>();
    while (matcher.find()) {
      String name = pascalCase(matcher.group(1).substring("archimate_".length()));
      names.add(TECHNOLOGY_NODE_TOKEN.equals(name) ? "Node" : name);
    }
    return names;
  }

  private static String pascalCase(String snake) {
    var out = new StringBuilder();
    for (String word : snake.split("_")) {
      out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
    }
    return out.toString();
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

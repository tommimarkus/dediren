package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The render policy's colour grammar is broadened beyond {@code #RRGGBB} to CSS colour keywords,
 * hex with alpha (3/4/6/8 digits), and {@code rgb()}/{@code rgba()} — but stays injection-safe.
 * This matters because {@code style.interaction.highlight_stroke} is emitted into a CSS {@code
 * <style>} block where XML escaping does nothing, so the validator (not escaping) is the security
 * boundary: a value carrying CSS metacharacters must be rejected as {@code
 * DEDIREN_SVG_POLICY_INVALID}.
 */
class ColorGrammarTest {

  private final SvgRenderEngine engine = new SvgRenderEngine();

  @Test
  void acceptsBroadenedColorFormats() throws Exception {
    for (String color :
        List.of(
            "rebeccapurple",
            "#abc",
            "#abcd",
            "#80ffffff",
            "rgb(1,2,3)",
            "rgba(10, 20, 30, 0.5)",
            "none",
            "transparent")) {
      String svg = RenderTestSupport.render(nodeFillInput(color));
      assertThat(svg).describedAs("color %s", color).contains("fill=\"" + color + "\"");
    }
  }

  @Test
  void rejectsGarbageAndInjectionColors() throws Exception {
    for (String bad :
        List.of("notacolor#", "red;}svg{display:none", "rgb(1);}", "url(#x)", "#12g", "a b")) {
      EngineException failure = renderExpectingFailure(nodeFillInput(bad));
      assertThat(failure.diagnostics().get(0).code())
          .describedAs("color %s", bad)
          .isEqualTo("DEDIREN_SVG_POLICY_INVALID");
    }
  }

  @Test
  void rejectsCssInjectionThroughHighlightStroke() throws Exception {
    ObjectNode input = baseInput();
    ObjectNode policy = (ObjectNode) input.get("policy");
    policy.put("interactive", "svg");
    policy
        .putObject("style")
        .putObject("interaction")
        .put("highlight_stroke", "red;}svg{display:none;");

    EngineException failure = renderExpectingFailure(input);

    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
  }

  private EngineException renderExpectingFailure(ObjectNode input) throws Exception {
    byte[] bytes =
        JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
    SvgRenderEngine.ParsedInput parsed = engine.parseInput(bytes);
    return assertThrows(
        EngineException.class,
        () -> engine.render(parsed.layoutResult(), parsed.policy(), parsed.renderMetadata()));
  }

  private static ObjectNode nodeFillInput(String color) throws Exception {
    ObjectNode input = baseInput();
    ObjectNode policy = (ObjectNode) input.get("policy");
    policy.putObject("style").putObject("node_overrides").putObject("client").put("fill", color);
    return input;
  }

  private static ObjectNode baseInput() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson("fixtures/layout-result/basic.json"));
    input.set("policy", fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }

  private static JsonNode fixtureJson(String path) throws Exception {
    return JsonSupport.objectMapper().readTree(Files.readString(workspaceRoot().resolve(path)));
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

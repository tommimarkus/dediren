package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.commands.AnalysisCommands;
import dev.dediren.core.commands.BuildCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The build tool's advertised input schema is how an agent discovers what it may ask for; {@code
 * BuildCommand} is what actually enforces it. Nothing but this test stops the two from drifting.
 * The motivating direction is the dangerous one: a kind added to {@code BuildCommand.EMIT_KINDS}
 * but not advertised here is simply unreachable over MCP, with nothing else to surface the gap. The
 * check itself is symmetric ({@code containsExactlyInAnyOrderElementsOf} is set equality), so it
 * fails just as surely if the schema advertises a kind {@code BuildCommand} no longer accepts.
 */
class ToolSchemasTest {

  @Test
  void advertisedEmitEnumMatchesTheVocabularyBuildCommandAccepts() {
    JsonNode advertisedEnum =
        JsonSupport.objectMapper()
            .readTree(ToolSchemas.BUILD)
            .path("properties")
            .path("emit")
            .path("items")
            .path("enum");

    assertThat(advertisedEnum.isArray())
        .as("ToolSchemas.BUILD must advertise an emit enum")
        .isTrue();
    List<String> advertised = new ArrayList<>();
    advertisedEnum.forEach(node -> advertised.add(node.asText()));

    assertThat(advertised)
        .as(
            "ToolSchemas.BUILD's emit enum is the only way an agent learns the emit vocabulary —"
                + " a kind added to BuildCommand.EMIT_KINDS must be advertised here too")
        .containsExactlyInAnyOrderElementsOf(BuildCommand.EMIT_KINDS);
  }

  @Test
  void advertisedQueryKindEnumMatchesTheVocabularyAnalysisCommandsAccepts() {
    // Same drift guard as the emit case: the SDK validates 'kind' against this enum before the
    // handler runs, so a kind added to AnalysisCommands.QUERY_KINDS but not advertised here is
    // silently unreachable over MCP.
    JsonNode advertisedEnum =
        JsonSupport.objectMapper()
            .readTree(ToolSchemas.QUERY)
            .path("properties")
            .path("kind")
            .path("enum");

    assertThat(advertisedEnum.isArray())
        .as("ToolSchemas.QUERY must advertise a kind enum")
        .isTrue();
    List<String> advertised = new ArrayList<>();
    advertisedEnum.forEach(node -> advertised.add(node.asText()));

    assertThat(advertised).containsExactlyInAnyOrderElementsOf(AnalysisCommands.QUERY_KINDS);
  }

  @Test
  void importAndBuildAdvertiseTheExactOptionalImageContract() {
    JsonNode build = JsonSupport.objectMapper().readTree(ToolSchemas.BUILD);
    JsonNode imported = JsonSupport.objectMapper().readTree(ToolSchemas.IMPORT);

    for (JsonNode schema : List.of(imported, build)) {
      JsonNode properties = schema.path("properties");
      JsonNode output = properties.path("output");
      JsonNode acceptedImageTypes = properties.path("accepted_image_types");
      assertThat(textValues(output.path("enum"))).containsExactly("data", "svg", "image");
      assertThat(output.path("default").asText()).isEqualTo("data");
      assertThat(acceptedImageTypes.path("uniqueItems").asBoolean()).isTrue();
      assertThat(textValues(acceptedImageTypes.path("items").path("enum")))
          .containsExactly("image/svg+xml", "image/png");
      assertThat(textValues(schema.at("/allOf/0/if/required")))
          .containsExactly("accepted_image_types");
      assertThat(schema.at("/allOf/0/then/properties/output/const").asText()).isEqualTo("image");
    }
  }

  private static List<String> textValues(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }
}

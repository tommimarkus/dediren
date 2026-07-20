package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.commands.BuildCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The build tool's advertised input schema is how an agent discovers what it may ask for; {@code
 * BuildCommand} is what actually enforces it. Nothing but this test stops the two from drifting,
 * and drift is silent in the direction that matters: a kind added to core but not advertised here
 * is simply unreachable over MCP.
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
}

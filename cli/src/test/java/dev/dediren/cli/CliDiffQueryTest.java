package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The model-intelligence commands: deterministic semantic diff between two model revisions, and the
 * fixed query vocabulary (dependents / orphans / view-coverage). Both are read-only, both are
 * envelope-decidable, and both order every list by id so output is byte-stable.
 */
class CliDiffQueryTest {
  @TempDir Path temp;

  private static final String OLD_MODEL =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "api", "type": "generic.component", "label": "API", "properties": {} },
          { "id": "client", "type": "generic.actor", "label": "Client",
            "properties": { "tier": "silver" } },
          { "id": "db", "type": "generic.component", "label": "DB", "properties": {} }
        ],
        "relationships": [
          { "id": "api-writes-db", "type": "generic.calls", "source": "api", "target": "db",
            "label": "writes", "properties": {} },
          { "id": "client-calls-api", "type": "generic.calls", "source": "client", "target": "api",
            "label": "calls", "properties": {} }
        ],
        "plugins": {
          "generic-graph": {
            "views": [
              { "id": "main", "label": "Main", "nodes": ["api", "client", "db"],
                "relationships": ["api-writes-db", "client-calls-api"], "groups": [] }
            ]
          }
        }
      }
      """;

  private static final String NEW_MODEL =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "api", "type": "generic.component", "label": "API v2", "properties": {} },
          { "id": "cache", "type": "generic.component", "label": "Cache", "properties": {} },
          { "id": "client", "type": "generic.component", "label": "Client",
            "properties": { "tier": "gold" } }
        ],
        "relationships": [
          { "id": "client-calls-api", "type": "generic.calls", "source": "client", "target": "api",
            "label": "calls", "properties": {} },
          { "id": "client-uses-cache", "type": "generic.calls", "source": "client", "target": "cache",
            "label": "uses", "properties": {} }
        ],
        "plugins": {
          "generic-graph": {
            "views": [
              { "id": "extra", "label": "Extra", "nodes": ["cache"],
                "relationships": [], "groups": [] },
              { "id": "main", "label": "Main", "nodes": ["api", "cache", "client"],
                "relationships": ["client-calls-api", "client-uses-cache"], "groups": [] }
            ]
          }
        }
      }
      """;

  @Test
  void diffReportsAddedRemovedAndChangedEntitiesDeterministically() throws Exception {
    Path oldModel = write("old.json", OLD_MODEL);
    Path newModel = write("new.json", NEW_MODEL);

    CliResult result =
        Main.executeForTesting(
            new String[] {"diff", "--old", oldModel.toString(), "--new", newModel.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    JsonNode data = envelope.get("data");
    assertThat(data.at("/diff_result_schema_version").asText()).isEqualTo("diff-result.schema.v1");
    assertDataMatchesSchema(data, "schemas/diff-result.schema.json");

    assertThat(data.at("/nodes/added/0/id").asText()).isEqualTo("cache");
    assertThat(data.at("/nodes/removed/0/id").asText()).isEqualTo("db");
    // changed sorted by id: api (label) before client (type + property).
    assertThat(data.at("/nodes/changed/0/id").asText()).isEqualTo("api");
    assertThat(data.at("/nodes/changed/0/changes/0/field").asText()).isEqualTo("label");
    assertThat(data.at("/nodes/changed/0/changes/0/from").asText()).isEqualTo("API");
    assertThat(data.at("/nodes/changed/0/changes/0/to").asText()).isEqualTo("API v2");
    assertThat(data.at("/nodes/changed/1/id").asText()).isEqualTo("client");
    List<String> clientFields =
        List.of(
            data.at("/nodes/changed/1/changes/0/field").asText(),
            data.at("/nodes/changed/1/changes/1/field").asText());
    assertThat(clientFields).containsExactly("properties.tier", "type");

    assertThat(data.at("/relationships/added/0/id").asText()).isEqualTo("client-uses-cache");
    assertThat(data.at("/relationships/removed/0/id").asText()).isEqualTo("api-writes-db");

    assertThat(data.at("/views/added/0").asText()).isEqualTo("extra");
    assertThat(data.at("/views/changed/0/id").asText()).isEqualTo("main");
    assertThat(data.at("/views/changed/0/nodes_added/0").asText()).isEqualTo("cache");
    assertThat(data.at("/views/changed/0/nodes_removed/0").asText()).isEqualTo("db");
    assertThat(data.at("/views/changed/0/relationships_added/0").asText())
        .isEqualTo("client-uses-cache");
    assertThat(data.at("/views/changed/0/relationships_removed/0").asText())
        .isEqualTo("api-writes-db");
  }

  @Test
  void diffOfIdenticalModelsIsAllEmptyAndOk() throws Exception {
    Path model = write("same.json", OLD_MODEL);

    CliResult result =
        Main.executeForTesting(
            new String[] {"diff", "--old", model.toString(), "--new", model.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/nodes/added")).isEmpty();
    assertThat(envelope.at("/data/nodes/removed")).isEmpty();
    assertThat(envelope.at("/data/nodes/changed")).isEmpty();
    assertThat(envelope.at("/data/views/changed")).isEmpty();
  }

  @Test
  void diffRejectsAStaleSideWithTheVersionGateEnvelope() throws Exception {
    Path oldModel = write("stale.json", "{\"model_schema_version\":\"model.schema.v0\"}");
    Path newModel = write("new.json", NEW_MODEL);

    CliResult result =
        Main.executeForTesting(
            new String[] {"diff", "--old", oldModel.toString(), "--new", newModel.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
  }

  @Test
  void queryDependentsReportsFanInAndFanOut() throws Exception {
    Path model = write("model.json", NEW_MODEL);

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "query", "--kind", "dependents", "--id", "api", "--input", model.toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    JsonNode data = envelope.get("data");
    assertThat(data.at("/query_result_schema_version").asText())
        .isEqualTo("query-result.schema.v1");
    assertDataMatchesSchema(data, "schemas/query-result.schema.json");
    assertThat(data.at("/kind").asText()).isEqualTo("dependents");
    assertThat(data.at("/dependents/id").asText()).isEqualTo("api");
    assertThat(data.at("/dependents/inbound/0/relationship_id").asText())
        .isEqualTo("client-calls-api");
    assertThat(data.at("/dependents/inbound/0/node_id").asText()).isEqualTo("client");
    assertThat(data.at("/dependents/outbound")).isEmpty();
  }

  @Test
  void queryOrphansReportsBothOrphanKinds() throws Exception {
    JsonNode model = JsonSupport.objectMapper().readTree(NEW_MODEL);
    ((tools.jackson.databind.node.ArrayNode) model.at("/nodes"))
        .addObject()
        .put("id", "ghost")
        .put("type", "generic.component")
        .put("label", "Ghost")
        .putObject("properties");
    Path file = write("orphan.json", JsonSupport.objectMapper().writeValueAsString(model));

    CliResult result =
        Main.executeForTesting(
            new String[] {"query", "--kind", "orphans", "--input", file.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    JsonNode data = envelope.get("data");
    assertThat(data.at("/kind").asText()).isEqualTo("orphans");
    assertThat(data.at("/orphans/relationship_orphans/0").asText()).isEqualTo("ghost");
    assertThat(data.at("/orphans/view_orphans/0").asText()).isEqualTo("ghost");
  }

  @Test
  void queryViewCoverageNamesUncoveredNodes() throws Exception {
    JsonNode model = JsonSupport.objectMapper().readTree(NEW_MODEL);
    ((tools.jackson.databind.node.ArrayNode) model.at("/nodes"))
        .addObject()
        .put("id", "ghost")
        .put("type", "generic.component")
        .put("label", "Ghost")
        .putObject("properties");
    Path file = write("coverage.json", JsonSupport.objectMapper().writeValueAsString(model));

    CliResult result =
        Main.executeForTesting(
            new String[] {"query", "--kind", "view-coverage", "--input", file.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    JsonNode data = envelope.get("data");
    assertThat(data.at("/kind").asText()).isEqualTo("view-coverage");
    assertThat(data.at("/view_coverage/model_node_count").asInt()).isEqualTo(4);
    assertThat(data.at("/view_coverage/views/0/id").asText()).isEqualTo("extra");
    assertThat(data.at("/view_coverage/views/1/id").asText()).isEqualTo("main");
    assertThat(data.at("/view_coverage/views/1/node_count").asInt()).isEqualTo(3);
    assertThat(data.at("/view_coverage/uncovered_node_ids/0").asText()).isEqualTo("ghost");
  }

  @Test
  void queryDependentsWithoutIdIsAUsageErrorEnvelope() throws Exception {
    Path model = write("model.json", NEW_MODEL);

    CliResult result =
        Main.executeForTesting(
            new String[] {"query", "--kind", "dependents", "--input", model.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void queryRejectsAnUnknownKindWithAUsageErrorEnvelope() throws Exception {
    Path model = write("model.json", NEW_MODEL);

    CliResult result =
        Main.executeForTesting(
            new String[] {"query", "--kind", "everything", "--input", model.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  private Path write(String name, String content) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static void assertDataMatchesSchema(JsonNode data, String schemaPath) {
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot()).validate(schemaPath, data);
    assertThat(errors).describedAs("%s validity: %s", schemaPath, data).isEmpty();
  }
}

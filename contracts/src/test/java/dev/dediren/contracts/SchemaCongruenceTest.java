package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The layout-preferences vocabulary is defined twice — once in {@code model.schema.json} (where a
 * source package declares its preferences) and once in {@code layout-request.schema.json} (where
 * the layout command accepts them). Both files are deliberately self-contained, so an agent can
 * validate either offline without resolving a cross-file {@code $ref}; the duplication is the price
 * of that.
 *
 * <p>Nothing enforced it, though. Every layout-vocabulary change was a mandatory two-file edit, and
 * a one-sided edit was silent: the suite stayed green as long as the fixtures happened to stay
 * inside the intersection, after which a source package could declare preferences the layout
 * command rejects (or the reverse). This test makes the two copies provably equal.
 *
 * <p>The same self-containment duplicates two more subtrees between {@code
 * layout-request.schema.json} and {@code layout-result.schema.json}: the node {@code role} enum and
 * the group {@code provenance} oneOf, both of which the layout engine copies verbatim from request
 * to result. They sit inline under {@code node}/{@code group} defs that otherwise differ, so those
 * twins are compared at their JSON pointers rather than as whole {@code $defs} entries.
 */
class SchemaCongruenceTest {

  private static final List<String> SHARED_DEFS =
      List.of(
          "layoutPreferences",
          "layoutRoutingPreferences",
          "layoutLayeringPreferences",
          "layoutCrossingPreferences",
          "layoutPlacementPreferences",
          "layoutComponentsPreferences");

  @Test
  void theLayoutPreferenceDefsAreIdenticalInModelAndLayoutRequestSchemas() throws Exception {
    JsonNode model = schema("schemas/model.schema.json").get("$defs");
    JsonNode layoutRequest = schema("schemas/layout-request.schema.json").get("$defs");

    for (String def : SHARED_DEFS) {
      assertThat(model.get(def))
          .as(
              "$defs.%s must be identical in model.schema.json and layout-request.schema.json —"
                  + " a one-sided edit silently desynchronises what a source may declare from what"
                  + " the layout command accepts",
              def)
          .isNotNull()
          .isEqualTo(layoutRequest.get(def));
    }
  }

  @Test
  void theNodeRoleEnumIsIdenticalInLayoutRequestAndLayoutResultSchemas() throws Exception {
    assertRequestResultTwin("/$defs/node/properties/role");
  }

  @Test
  void theGroupProvenanceOneOfIsIdenticalInLayoutRequestAndLayoutResultSchemas() throws Exception {
    assertRequestResultTwin("/$defs/group/properties/provenance");
  }

  private static void assertRequestResultTwin(String pointer) throws Exception {
    JsonNode request = schema("schemas/layout-request.schema.json").at(pointer);
    JsonNode result = schema("schemas/layout-result.schema.json").at(pointer);

    assertThat(request.isMissingNode())
        .as("%s must exist in layout-request.schema.json", pointer)
        .isFalse();
    assertThat(request)
        .as(
            "%s must be identical in layout-request.schema.json and layout-result.schema.json —"
                + " the engine copies the value verbatim from request to result, so a one-sided"
                + " edit silently desynchronises what the layout command accepts from what it"
                + " emits",
            pointer)
        .isEqualTo(result);
  }

  private static JsonNode schema(String path) throws Exception {
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

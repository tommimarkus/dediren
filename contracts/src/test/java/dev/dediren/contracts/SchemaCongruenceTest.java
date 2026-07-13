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

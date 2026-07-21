package dev.dediren.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.MigrationOperation;
import dev.dediren.contracts.MigrationPath;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The registry's migration operations are only trustworthy if applying them actually produces a
 * document this build accepts — a wrong machine recipe is worse than prose. This test is the
 * oracle: a reference applier (test-only; dediren never applies steps in production) runs each
 * shipped path against a stale fixture document and the outcome must both equal the expected
 * document and pass the family's current version gate and JSON Schema.
 */
class MigrationPathApplicationTest {

  @Test
  void renderPolicyV2PathProducesTheExpectedCurrentDocument() {
    JsonNode stale =
        JsonSupport.readTree(
            """
            {
              "render_policy_schema_version": "render-policy.schema.v2",
              "interactive": "svg",
              "page": { "width": 800, "height": 600 },
              "margin": { "top": 12, "right": 12, "bottom": 12, "left": 12 },
              "style": { "interaction": { "highlight_stroke": "#ff0000" } }
            }
            """);

    JsonNode migrated = apply(stale, pathFor(KnownSchemaVersions.RENDER_POLICY, stale));

    JsonNode expected =
        JsonSupport.readTree(
            """
            {
              "render_policy_schema_version": "render-policy.schema.v3",
              "page": { "width": 800, "height": 600 },
              "margin": { "top": 12, "right": 12, "bottom": 12, "left": 12 },
              "style": {}
            }
            """);
    assertThat(migrated).isEqualTo(expected);
    assertAcceptedByFamily(KnownSchemaVersions.RENDER_POLICY, migrated);
  }

  @Test
  void oldestRenderPolicyMigratesAcrossAllThreeBumpsInOneApplication() {
    JsonNode stale =
        JsonSupport.readTree(
            """
            {
              "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
              "raster": { "scale": 2 },
              "interactive": "none",
              "page": { "width": 800, "height": 600 },
              "margin": { "top": 12, "right": 12, "bottom": 12, "left": 12 }
            }
            """);

    JsonNode migrated = apply(stale, pathFor(KnownSchemaVersions.RENDER_POLICY, stale));

    JsonNode expected =
        JsonSupport.readTree(
            """
            {
              "page": { "width": 800, "height": 600 },
              "margin": { "top": 12, "right": 12, "bottom": 12, "left": 12 },
              "render_policy_schema_version": "render-policy.schema.v3"
            }
            """);
    assertThat(migrated).isEqualTo(expected);
    assertAcceptedByFamily(KnownSchemaVersions.RENDER_POLICY, migrated);
  }

  private static MigrationPath pathFor(KnownSchemaVersions.Family family, JsonNode stale) {
    return SchemaVersionGate.check(family, stale).orElseThrow().migration();
  }

  /**
   * Reference semantics for the operation vocabulary: rename_field moves a value, remove_key
   * deletes if present (absent keys are a no-op, so a path composed across bumps applies to files
   * from any intermediate era), set_version writes the string. Pointers are parent-walked; a
   * missing parent makes the operation a no-op.
   */
  private static JsonNode apply(JsonNode document, MigrationPath path) {
    ObjectNode working = (ObjectNode) document.deepCopy();
    for (MigrationOperation operation : path.operations()) {
      switch (operation.op()) {
        case "rename_field" -> {
          ObjectNode parent = parentOf(working, operation.pointer());
          String field = lastSegment(operation.pointer());
          if (parent != null && parent.has(field)) {
            JsonNode value = parent.get(field);
            parent.remove(field);
            ObjectNode target = parentOf(working, operation.to());
            if (target != null) {
              target.set(lastSegment(operation.to()), value);
            }
          }
        }
        case "remove_key" -> {
          ObjectNode parent = parentOf(working, operation.pointer());
          if (parent != null) {
            parent.remove(lastSegment(operation.pointer()));
          }
        }
        case "set_version" -> {
          ObjectNode parent = parentOf(working, operation.pointer());
          if (parent != null) {
            parent.put(lastSegment(operation.pointer()), operation.value());
          }
        }
        default ->
            throw new AssertionError("no reference semantics for operation " + operation.op());
      }
    }
    return working;
  }

  private static ObjectNode parentOf(ObjectNode root, String pointer) {
    String[] segments = pointer.substring(1).split("/");
    JsonNode current = root;
    for (int i = 0; i < segments.length - 1; i++) {
      current = current.get(segments[i]);
      if (current == null || !current.isObject()) {
        return null;
      }
    }
    return (ObjectNode) current;
  }

  private static String lastSegment(String pointer) {
    return pointer.substring(pointer.lastIndexOf('/') + 1);
  }

  private static void assertAcceptedByFamily(KnownSchemaVersions.Family family, JsonNode migrated) {
    assertThat(SchemaVersionGate.check(family, migrated)).isEmpty();
    assertThat(
            SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
                .validate("schemas/render-policy.schema.json", migrated))
        .isEmpty();
  }
}

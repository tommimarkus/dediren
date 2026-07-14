package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.SchemaAssertions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every fixture is validated against the published schema of its family.
 *
 * <p>The schemas and fixtures under {@code schemas/} and {@code fixtures/} are the product surface
 * agents validate against (architecture-guidelines §4), but conformance was only ever spot-checked:
 * a handful of fixtures were validated by name, four schemas had no instance validation at all, and
 * most engine-regenerated layout-result goldens were never checked against
 * layout-result.schema.json. A regeneration that emitted a schema-invalid document, or a schema
 * edit that orphaned its fixtures, would have shipped.
 *
 * <p>This sweep is deliberately exhaustive rather than curated: adding a fixture to a covered
 * directory enrolls it automatically.
 */
class FixtureConformanceSweepTest {

  /** Fixture directory -> the schema every document in it must satisfy. */
  private static final Map<String, String> FAMILIES =
      Map.of(
          "fixtures/source", "schemas/model.schema.json",
          "fixtures/layout-request", "schemas/layout-request.schema.json",
          "fixtures/layout-result", "schemas/layout-result.schema.json",
          "fixtures/render-metadata", "schemas/render-metadata.schema.json",
          "fixtures/render-policy", "schemas/render-policy.schema.json",
          "fixtures/build-result", "schemas/build-result.schema.json");

  /** Fixture file -> schema, for directories whose fixtures use different schemas. */
  private static final Map<String, String> PER_FILE_FAMILIES =
      Map.of(
          "fixtures/export-policy/default-oef.json", "schemas/oef-export-policy.schema.json",
          "fixtures/export-policy/default-uml-xmi.json",
              "schemas/uml-xmi-export-policy.schema.json");

  /**
   * Negative fixtures that exist precisely to be rejected by their schema. They are asserted
   * invalid below rather than skipped, so one silently becoming valid is a failure too.
   *
   * <p>Note the other {@code invalid-*} source fixtures are NOT listed: {@code
   * invalid-dangling-relationship} and {@code invalid-duplicate-id} are schema-VALID and fail only
   * semantic validation, so they stay in the positive sweep where they belong.
   */
  private static final Map<String, String> SCHEMA_INVALID_BY_DESIGN =
      Map.of("fixtures/source/invalid-absolute-geometry.json", "schemas/model.schema.json");

  static Stream<Object[]> fixtures() throws IOException {
    List<Object[]> cases = new ArrayList<>();
    for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
      Path dir = workspaceRoot().resolve(family.getKey());
      try (Stream<Path> files = Files.list(dir)) {
        files
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .map(path -> family.getKey() + "/" + path.getFileName())
            .filter(fixture -> !SCHEMA_INVALID_BY_DESIGN.containsKey(fixture))
            .sorted()
            .forEach(fixture -> cases.add(new Object[] {fixture, family.getValue()}));
      }
    }
    PER_FILE_FAMILIES.forEach((fixture, schema) -> cases.add(new Object[] {fixture, schema}));
    return cases.stream();
  }

  static Stream<Object[]> negativeFixtures() {
    return SCHEMA_INVALID_BY_DESIGN.entrySet().stream()
        .map(entry -> new Object[] {entry.getKey(), entry.getValue()});
  }

  @ParameterizedTest(name = "{0} conforms to {1}")
  @MethodSource("fixtures")
  void everyFixtureConformsToItsFamilySchema(String fixture, String schema) {
    List<String> errors = SchemaAssertions.validateFixture(workspaceRoot(), schema, fixture);

    assertThat(errors).as("%s must validate against %s", fixture, schema).isEmpty();
  }

  @ParameterizedTest(name = "{0} is still rejected by {1}")
  @MethodSource("negativeFixtures")
  void everyNegativeFixtureIsStillRejectedByItsSchema(String fixture, String schema) {
    List<String> errors = SchemaAssertions.validateFixture(workspaceRoot(), schema, fixture);

    assertThat(errors)
        .as(
            "%s exists to be rejected by %s; if it now validates, the schema has weakened",
            fixture, schema)
        .isNotEmpty();
  }

  @Test
  void everyExportPolicyFixtureIsEnrolled() throws IOException {
    try (Stream<Path> files = Files.list(workspaceRoot().resolve("fixtures/export-policy"))) {
      assertThat(
              files
                  .filter(path -> path.getFileName().toString().endsWith(".json"))
                  .map(path -> "fixtures/export-policy/" + path.getFileName())
                  .sorted())
          .containsExactlyElementsOf(PER_FILE_FAMILIES.keySet().stream().sorted().toList());
    }
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

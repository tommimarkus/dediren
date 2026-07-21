package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The resources surface serves the bundle's own bytes — ground truth, not paraphrase. These tests
 * pin the three anti-drift guarantees: a schema resource is byte-identical to the shipped file, a
 * guide resource is byte-identical to what the dediren_guide tool serves for the same topic, and
 * the diagnostics catalog covers the entire DiagnosticCode vocabulary with the guide's explicit
 * repair rules attached.
 */
class DedirenResourcesTest {

  private static final Map<String, SyncResourceSpecification> BY_URI =
      DedirenResources.specifications().stream()
          .collect(Collectors.toMap(s -> s.resource().uri(), Function.identity()));

  private static String read(String uri) {
    SyncResourceSpecification specification = BY_URI.get(uri);
    assertThat(specification).describedAs("resource %s", uri).isNotNull();
    return ((TextResourceContents)
            specification.readHandler().apply(null, new ReadResourceRequest(uri)).contents().get(0))
        .text();
  }

  @Test
  void everyPublicSchemaIsListed() throws Exception {
    try (var files = Files.list(DedirenPaths.productRoot().resolve("schemas"))) {
      List<String> expected =
          files.map(f -> "dediren://schema/" + f.getFileName()).sorted().toList();
      assertThat(BY_URI.keySet()).containsAll(expected);
      assertThat(expected).isNotEmpty();
    }
  }

  @Test
  void aSchemaResourceIsByteIdenticalToTheShippedFile() throws Exception {
    String served = read("dediren://schema/model.schema.json");

    String shipped =
        Files.readString(
            DedirenPaths.productRoot().resolve("schemas/model.schema.json"),
            StandardCharsets.UTF_8);
    assertThat(served).isEqualTo(shipped);
  }

  @Test
  void aFixtureResourceIsByteIdenticalToTheShippedFile() throws Exception {
    String served = read("dediren://fixture/source/valid-basic.json");

    String shipped =
        Files.readString(
            DedirenPaths.productRoot().resolve("fixtures/source/valid-basic.json"),
            StandardCharsets.UTF_8);
    assertThat(served).isEqualTo(shipped);
  }

  @Test
  void everyGuideTopicIsListedAndMatchesTheGuideCatalog() {
    // dediren_guide serves GuideCatalog.section verbatim (GuideCatalogTest pins that), so equality
    // with the catalog here is transitively equality with the tool: one text, two doors.
    for (String topic : GuideCatalog.topics()) {
      String resourceText = read("dediren://guide/" + topic);
      assertThat(resourceText)
          .describedAs("guide topic %s", topic)
          .isEqualTo(GuideCatalog.section(topic));
    }
  }

  @Test
  void diagnosticsCatalogCoversEveryCodeAndCarriesExplicitRepairRules() {
    JsonNode catalog = JsonSupport.readTree(read("dediren://diagnostics/catalog"));

    assertThat(catalog.size()).isEqualTo(DiagnosticCode.values().length);
    JsonNode pluginRequired = entryFor(catalog, "DEDIREN_GENERIC_GRAPH_PLUGIN_REQUIRED");
    assertThat(pluginRequired.get("repair_rule").asText()).contains("plugins.generic-graph");
    JsonNode identityPlaceholder = entryFor(catalog, "DEDIREN_EXPORT_IDENTITY_PLACEHOLDER");
    assertThat(identityPlaceholder.get("repair_rule").asText()).contains("identity");
    // Codes without an explicit bullet are self-repairing via their message: null, not absent.
    JsonNode elkInternal = entryFor(catalog, "DEDIREN_ELK_LAYOUT_FAILED");
    assertThat(elkInternal.get("repair_rule").isNull()).isTrue();
    // A bullet naming two codes documents both.
    assertThat(
            entryFor(catalog, "DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE")
                .get("repair_rule")
                .asText())
        .contains("xmllint");
    assertThat(
            entryFor(catalog, "DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE")
                .get("repair_rule")
                .asText())
        .contains("xmllint");
  }

  private static JsonNode entryFor(JsonNode catalog, String code) {
    for (JsonNode entry : catalog) {
      if (code.equals(entry.get("code").asText())) {
        return entry;
      }
    }
    throw new AssertionError("no catalog entry for " + code);
  }
}

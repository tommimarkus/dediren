package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The typed {@link GenericGraphEngine} seam must produce exactly what the process {@code Main}
 * emits: the engine result JSON-equals the {@code data} of {@link Main#executeForTesting}, and the
 * published diagnostic errors throw {@link EngineException} with the same code and exit code. The
 * unparseable-input case reproduces today's raw (non-enveloped) parse failure through the engine's
 * parse entry point.
 */
class GenericGraphEngineTest {
  private final GenericGraphEngine engine = new GenericGraphEngine();

  @Test
  void idIsGenericGraph() {
    assertThat(engine.id()).isEqualTo("generic-graph");
  }

  @Test
  void validateArchimateEqualsProcessData() throws Exception {
    byte[] source = fixtureBytes("fixtures/source/valid-archimate-oef.json");

    EngineResult<?> result = engine.validate(engine.parseSource(source), "archimate");

    assertThat(engineTree(result.value()))
        .isEqualTo(processData(new String[] {"validate", "--profile", "archimate"}, source));
  }

  @Test
  void projectLayoutRequestEqualsProcessData() throws Exception {
    byte[] source = fixtureBytes("fixtures/source/valid-basic.json");

    EngineResult<?> result = engine.projectLayoutRequest(engine.parseSource(source), "main");

    assertThat(engineTree(result.value()))
        .isEqualTo(
            processData(
                new String[] {"project", "--target", "layout-request", "--view", "main"}, source));
  }

  @Test
  void projectRenderMetadataEqualsProcessData() throws Exception {
    byte[] source = fixtureBytes("fixtures/source/valid-archimate-oef.json");

    EngineResult<?> result = engine.projectRenderMetadata(engine.parseSource(source), "main");

    assertThat(engineTree(result.value()))
        .isEqualTo(
            processData(
                new String[] {"project", "--target", "render-metadata", "--view", "main"}, source));
  }

  @Test
  void validateWithoutProfileThrowsProfileRequired() throws Exception {
    SourceDocument source = engine.parseSource(fixtureBytes("fixtures/source/valid-basic.json"));

    EngineException failure =
        assertThrows(EngineException.class, () -> engine.validate(source, null));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SEMANTIC_PROFILE_REQUIRED");
  }

  @Test
  void validateUnsupportedProfileThrowsUnsupported() throws Exception {
    SourceDocument source = engine.parseSource(fixtureBytes("fixtures/source/valid-basic.json"));

    EngineException failure =
        assertThrows(EngineException.class, () -> engine.validate(source, "bpmn"));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code())
        .isEqualTo("DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED");
  }

  @Test
  void validateWithoutProfileWinsOverMalformedStdin() throws Exception {
    // Error precedence: the process form checks --profile before reading stdin, so a missing
    // profile must produce the enveloped DEDIREN_SEMANTIC_PROFILE_REQUIRED + exit 3 even when the
    // stdin bytes are unparseable — the raw parse failure must not win.
    PluginResult result = Main.executeForTesting(new String[] {"validate"}, "not-json");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SEMANTIC_PROFILE_REQUIRED");
  }

  @Test
  void parseSourceRejectsUnparseableInput() {
    // generic-graph publishes no parse-failure envelope: unparseable stdin surfaces as today's raw
    // (non-enveloped) failure, so the parse entry point throws rather than returning a diagnostic.
    assertThatThrownBy(() -> engine.parseSource("not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Exception.class);
  }

  private static JsonNode engineTree(Object value) {
    return JsonSupport.objectMapper()
        .readTree(JsonSupport.objectMapper().writeValueAsString(value));
  }

  private static JsonNode processData(String[] args, byte[] source) throws Exception {
    PluginResult result = Main.executeForTesting(args, new String(source, StandardCharsets.UTF_8));
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    return JsonSupport.objectMapper().readTree(result.stdout()).get("data");
  }

  private static byte[] fixtureBytes(String path) throws Exception {
    return Files.readAllBytes(workspaceRoot().resolve(path));
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

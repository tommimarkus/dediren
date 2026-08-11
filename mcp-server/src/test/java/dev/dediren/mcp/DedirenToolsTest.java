package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DedirenToolsTest {

  /** The checked-in fixtures are the authority on the source shape — never hand-roll one. */
  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static Path renderPolicyFixture(String name) {
    return Path.of("..", "fixtures", "render-policy", name).toAbsolutePath().normalize();
  }

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static JsonNode envelopeOf(CallToolResult result) {
    return JsonSupport.objectMapper().readTree(textOf(result));
  }

  /**
   * An empty registry. None of these cases reaches an engine: schema validation is engine-free
   * (SourceValidator), the guide never touches core, and the path-escape and missing-argument cases
   * fail before dispatch. Real-engine coverage lives in CliMcpParityTest (Task 6), which is in cli
   * because only cli may construct engines — and mcp must not depend on cli, which depends on mcp.
   */
  private static Engines noEngines() {
    return Engines.of(List.of(), List.of(), List.of(), List.of());
  }

  private DedirenTools toolsIn(Path root) {
    return new DedirenTools(root, noEngines(), Map.of());
  }

  @Test
  void guideWithoutTopicReturnsTheIndex() {
    CallToolResult result =
        toolsIn(Path.of(".")).guide(new CallToolRequest("dediren_guide", Map.of()));

    assertThat(textOf(result)).contains("topics");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  @Test
  void guideWithTopicReturnsThatSection() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "render-policy")));

    assertThat(textOf(result)).contains("Render Policy Options");
  }

  @Test
  void guideFlagsAnUnknownTopicAsAnError() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "no-such-topic")));

    assertThat(result.isError()).isTrue();
    // Still helpful: the body names the valid topics so the model can retry without a second call.
    assertThat(textOf(result)).contains("unknown topic 'no-such-topic'");
    assertThat(textOf(result)).contains("render-policy");
  }

  @Test
  void guideFlagsAKnownTopicAsASuccess() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "repair")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("Repair Rules");
  }

  @Test
  void validateReturnsTheEnvelopeVerbatim(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("ok");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  @Test
  void validateFlagsAnErrorEnvelopeAsIsError(@TempDir Path root) throws Exception {
    Files.copy(fixture("invalid-duplicate-id.json"), root.resolve("broken.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "broken.json")));

    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("error");
    assertThat(result.isError()).isTrue();
  }

  @Test
  void validateRejectsASourceOutsideTheRoot(@TempDir Path root) throws Exception {
    CallToolResult result =
        toolsIn(root)
            .validate(
                new CallToolRequest("dediren_validate", Map.of("source", "../../etc/passwd")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    // The envelope goes to the model. It must never leak the resolved absolute workspace root
    // (host filesystem reconnaissance) -- only the model's own candidate string comes back.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toString());
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
    assertThat(diagnostic.path("message").asText()).contains("../../etc/passwd");
  }

  @Test
  void buildRejectsAnOutDirOutsideTheRoot(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build", Map.of("source", "model.json", "out", "../escape")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    // Same host-filesystem-reconnaissance concern as the validate case above.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toString());
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  // A source is model-supplied, so its fragments[] paths are model-supplied too. They must be
  // confined to --root exactly like the tool's own path arguments, and their errors sanitized. No
  // fixture carries a fragment shape, so these mirror CliValidateTest's inline fragment models.
  private static String modelWithFragment(String fragmentPath) {
    return """
        {
          "model_schema_version": "model.schema.v1",
          "fragments": ["%s"],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """
        .formatted(fragmentPath);
  }

  @Test
  void validateConfinesAnEscapingSourceFragmentToTheRoot(@TempDir Path root) throws Exception {
    // The source is inside root, but its fragment escapes it. The fragment target need not exist:
    // confinement decides before the read, so no exists-vs-not-exists oracle is possible.
    Files.writeString(root.resolve("model.json"), modelWithFragment("../frag-escape.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path("path").asText()).isEqualTo("$.fragments[0]");
    // Only the model's own relative fragment string comes back -- never the resolved absolute
    // target or the workspace root, and never a distinguishable exists-vs-not-exists signal.
    assertThat(diagnostic.path("message").asText()).contains("../frag-escape.json");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toString());
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
    assertThat(diagnostic.path("message").asText())
        .doesNotContain(root.getParent().toRealPath().toString());
  }

  @Test
  void validateLoadsALegitimateFragmentInsideTheRoot(@TempDir Path root) throws Exception {
    // A fragment in a subdirectory of root must still load -- confinement must not false-reject it.
    Files.writeString(root.resolve("model.json"), modelWithFragment("sub/piece.json"));
    Files.createDirectories(root.resolve("sub"));
    Files.writeString(
        root.resolve("sub/piece.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
          ],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """);

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    JsonNode envelope = envelopeOf(result);
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.path("data").path("node_count").asInt()).isEqualTo(1);
  }

  // The core-side twin of resolveForWriteRejectsWriteThroughADanglingSymlink. SourceValidator's
  // fragment walk carries the identical anchoring on the READ path, and its javadoc claims to
  // mirror WorkspacePaths -- so the two must be pinned together, not just kept in sync by hand.
  //
  // Behavior change worth knowing: before this fix the walk stepped past the dangling link, the
  // textual confine passed, and the failure surfaced later as FRAGMENT_READ_FAILED. Now the walk
  // anchors ON the link, real-path resolution fails, and it is rejected as a confinement failure.
  // Both are errors and the fragment is unreadable either way; only the code and message change.
  @Test
  void validateRejectsAFragmentReachedThroughADanglingSymlink(
      @TempDir Path root, @TempDir Path outside) throws Exception {
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, outside.resolve("never-created"));
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support.
    }
    Files.writeString(root.resolve("model.json"), modelWithFragment("link/frag.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    // Same anti-fingerprinting rule as the sibling fragment tests.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  @Test
  void buildRequiresASource(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root).build(new CallToolRequest("dediren_build", Map.of("out", "out")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void buildRejectsAViewIdWithAPathSeparator(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", List.of("../evil"))));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    // Rejected before any write: the out directory is never even created.
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  // THE LIVE CASE. A blank string clears the SDK's input-schema validation ({"type":"string"} has
  // no minLength), reaches this handler, and used to be dropped -- collapsing views to empty, which
  // BuildCommand.selectViews reads as "build every view". Verified on the shipped bundle: a 3-view
  // model with views:[""] built all three under status:"ok".
  //
  // A render_policy is supplied so a request that got past arg validation would actually reach
  // BuildCommand instead of dying at its own no-output-lane check with the SAME
  // DEDIREN_COMMAND_INPUT_INVALID code the fixed guard reports -- that coincidence is what made the
  // code assertion below inert against a regression before this fixture was added. With a lane
  // present, this suite's empty engine registry (noEngines()) means a regressed guard would instead
  // fail per-view at engine dispatch (DEDIREN_PLUGIN_UNKNOWN, nested under views[0].diagnostics),
  // leaving this top-level envelope's own diagnostics[] empty -- so the code assertion below now
  // reads "" instead of DEDIREN_COMMAND_INPUT_INVALID and genuinely fails on a regression, rather
  // than passing by coincidence. See DedirenToolsEngineBackedTest (cli) for the real-engine case
  // that proves the actual harm -- a build that succeeds and writes every view's output.
  @Test
  void buildRejectsABlankViewsElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Files.copy(renderPolicyFixture("default-svg.json"), root.resolve("policy.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "render_policy", "policy.json",
                        "views", List.of(""))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    // The whole point: a malformed list must not silently become "build every view".
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  // Defence in depth. The SDK's input validation (on by default, opt-out only) rejects this over a
  // real connection before the handler sees it -- but the handler must not depend on that default.
  //
  // Same render_policy addition as buildRejectsABlankViewsElement, and for the same reason: without
  // a lane, this would die at BuildCommand's no-output-lane check under the identical
  // DEDIREN_COMMAND_INPUT_INVALID code whether or not the guard is doing its job.
  @Test
  void buildRejectsANonStringViewsElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Files.copy(renderPolicyFixture("default-svg.json"), root.resolve("policy.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "render_policy", "policy.json",
                        "views", List.of(1))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    assertThat(diagnostic.path("path").asText()).isEqualTo("views");
    // The whole point: a malformed list must not silently become "build every view".
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  // Same render_policy addition as buildRejectsABlankViewsElement, and for the same reason: without
  // a lane, this would die at BuildCommand's no-output-lane check under the identical
  // DEDIREN_COMMAND_INPUT_INVALID code whether or not the guard is doing its job.
  @Test
  void buildRejectsABlankEmitElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Files.copy(renderPolicyFixture("default-svg.json"), root.resolve("policy.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "render_policy", "policy.json",
                        "emit", List.of("layout-request", "  "))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'emit'[1]");
  }

  // Same render_policy addition as buildRejectsABlankViewsElement, and for the same reason: without
  // a lane, this would die at BuildCommand's no-output-lane check under the identical
  // DEDIREN_COMMAND_INPUT_INVALID code whether or not the guard is doing its job. (Pre-fix, a
  // non-array 'views' also collapsed to an empty list rather than erroring -- the same silent
  // "build every view" defect, just from a differently-shaped bad argument.)
  @Test
  void buildRejectsAViewsArgumentThatIsNotAnArray(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Files.copy(renderPolicyFixture("default-svg.json"), root.resolve("policy.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "render_policy", "policy.json",
                        "views", "main")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views' must be an array of strings");
  }

  @Test
  void buildNamesWhichPolicyArgumentFailedToRead(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    // A directory resolves like a file (so it clears WorkspacePaths) but cannot be read as one.
    Files.createDirectory(root.resolve("oef.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "oef_policy", "oef.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    // Which of the three policy arguments failed must be in the envelope, not just on stderr.
    assertThat(diagnostic.path("message").asText()).contains("oef_policy");
    assertThat(diagnostic.path("path").asText()).isEqualTo("oef.json");
    // The resolved absolute path is stderr-only and must never reach the model.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  // --- diff (engine-free, so it runs fully on the empty registry) -----------

  @Test
  void diffReturnsAChangeEnvelope(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("old.json"));
    Files.copy(fixture("valid-pipeline-rich.json"), root.resolve("new.json"));

    CallToolResult result =
        toolsIn(root)
            .diff(
                new CallToolRequest("dediren_diff", Map.of("old", "old.json", "new", "new.json")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(envelopeOf(result).path("data").path("diff_result_schema_version").asText())
        .isEqualTo("diff-result.schema.v1");
  }

  @Test
  void diffRequiresOldAndNew(@TempDir Path root) {
    CallToolResult missingOld =
        toolsIn(root).diff(new CallToolRequest("dediren_diff", Map.of("new", "new.json")));
    assertThat(missingOld.isError()).isTrue();
    assertThat(envelopeOf(missingOld).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");

    CallToolResult missingNew =
        toolsIn(root).diff(new CallToolRequest("dediren_diff", Map.of("old", "old.json")));
    assertThat(missingNew.isError()).isTrue();
    assertThat(envelopeOf(missingNew).path("diagnostics").path(0).path("message").asText())
        .contains("'new'");
  }

  @Test
  void diffRejectsAModelOutsideTheRoot(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("new.json"));

    CallToolResult result =
        toolsIn(root)
            .diff(
                new CallToolRequest(
                    "dediren_diff", Map.of("old", "../../etc/passwd", "new", "new.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  @Test
  void diffNamesTheModelThatFailedToRead(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("new.json"));
    // A directory resolves like a file (clears WorkspacePaths) but cannot be read as one.
    Files.createDirectory(root.resolve("old.json"));

    CallToolResult result =
        toolsIn(root)
            .diff(
                new CallToolRequest("dediren_diff", Map.of("old", "old.json", "new", "new.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("old");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  // --- query ----------------------------------------------------------------

  @Test
  void queryReturnsAResultEnvelope(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .query(
                new CallToolRequest(
                    "dediren_query", Map.of("source", "model.json", "kind", "orphans")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(envelopeOf(result).path("data").path("query_result_schema_version").asText())
        .isEqualTo("query-result.schema.v1");
  }

  @Test
  void queryRequiresSourceAndKind(@TempDir Path root) {
    CallToolResult missingSource =
        toolsIn(root).query(new CallToolRequest("dediren_query", Map.of("kind", "orphans")));
    assertThat(missingSource.isError()).isTrue();
    assertThat(envelopeOf(missingSource).path("diagnostics").path(0).path("message").asText())
        .contains("'source'");

    CallToolResult missingKind =
        toolsIn(root).query(new CallToolRequest("dediren_query", Map.of("source", "model.json")));
    assertThat(missingKind.isError()).isTrue();
    assertThat(envelopeOf(missingKind).path("diagnostics").path(0).path("message").asText())
        .contains("'kind'");
  }

  @Test
  void queryRejectsASourceOutsideTheRoot(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root)
            .query(
                new CallToolRequest(
                    "dediren_query", Map.of("source", "../../etc/passwd", "kind", "orphans")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  // --- verify ---------------------------------------------------------------

  @Test
  void verifyReportsAnUnstampedArtifact(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Path artifacts = Files.createDirectory(root.resolve("artifacts"));
    // A plain SVG carries no provenance stamp, so verify reports it as unstamped (a warning).
    Files.writeString(artifacts.resolve("diagram.svg"), "<svg/>");

    CallToolResult result =
        toolsIn(root)
            .verify(
                new CallToolRequest(
                    "dediren_verify", Map.of("source", "model.json", "artifacts", "artifacts")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    JsonNode envelope = envelopeOf(result);
    assertThat(envelope.path("status").asText()).isEqualTo("warning");
    assertThat(envelope.path("data").path("artifacts").path(0).path("status").asText())
        .isEqualTo("unstamped");
  }

  @Test
  void verifyRequiresSourceAndArtifacts(@TempDir Path root) {
    CallToolResult missingSource =
        toolsIn(root)
            .verify(new CallToolRequest("dediren_verify", Map.of("artifacts", "artifacts")));
    assertThat(missingSource.isError()).isTrue();
    assertThat(envelopeOf(missingSource).path("diagnostics").path(0).path("message").asText())
        .contains("'source'");

    CallToolResult missingArtifacts =
        toolsIn(root).verify(new CallToolRequest("dediren_verify", Map.of("source", "model.json")));
    assertThat(missingArtifacts.isError()).isTrue();
    assertThat(envelopeOf(missingArtifacts).path("diagnostics").path(0).path("message").asText())
        .contains("'artifacts'");
  }

  @Test
  void verifyRejectsArtifactsThatAreNotADirectory(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Files.writeString(root.resolve("not-a-dir"), "plain file");

    CallToolResult result =
        toolsIn(root)
            .verify(
                new CallToolRequest(
                    "dediren_verify", Map.of("source", "model.json", "artifacts", "not-a-dir")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("not a directory");
    // The model's own candidate, not the resolved absolute path.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  // --- status ---------------------------------------------------------------

  @Test
  void statusIndexesTheRootByDefault(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result = toolsIn(root).status(new CallToolRequest("dediren_status", Map.of()));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    JsonNode envelope = envelopeOf(result);
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.path("data").path("models").path(0).path("path").asText())
        .isEqualTo("model.json");
  }

  @Test
  void statusRejectsADirOutsideTheRoot(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root).status(new CallToolRequest("dediren_status", Map.of("dir", "../../etc")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  @Test
  void statusRejectsADirThatIsNotADirectory(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("plain.txt"), "not a dir");

    CallToolResult result =
        toolsIn(root).status(new CallToolRequest("dediren_status", Map.of("dir", "plain.txt")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("not a directory");
  }

  // --- fragment confinement (each new source-loading handler must thread --root) ---------------
  // A source is model-supplied, so its fragments[] paths are too. These prove diff/query/verify
  // each
  // pass --root as the confinement root into core's source loader: with a null root the escaping
  // fragment would be read unconfined instead of rejected, so DEDIREN_MCP_PATH_OUTSIDE_ROOT here is
  // the evidence the wiring is live (the mechanism itself is proven generically by
  // SourceValidatorTest).

  @Test
  void diffConfinesAnEscapingSourceFragment(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("old.json"), modelWithFragment("../frag-escape.json"));
    Files.copy(fixture("valid-basic.json"), root.resolve("new.json"));

    CallToolResult result =
        toolsIn(root)
            .diff(
                new CallToolRequest("dediren_diff", Map.of("old", "old.json", "new", "new.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path("path").asText()).isEqualTo("$.fragments[0]");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  @Test
  void queryConfinesAnEscapingSourceFragment(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("model.json"), modelWithFragment("../frag-escape.json"));

    CallToolResult result =
        toolsIn(root)
            .query(
                new CallToolRequest(
                    "dediren_query", Map.of("source", "model.json", "kind", "orphans")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path("path").asText()).isEqualTo("$.fragments[0]");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  @Test
  void verifyConfinesAnEscapingSourceFragment(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("model.json"), modelWithFragment("../frag-escape.json"));
    Files.createDirectory(root.resolve("artifacts"));

    CallToolResult result =
        toolsIn(root)
            .verify(
                new CallToolRequest(
                    "dediren_verify", Map.of("source", "model.json", "artifacts", "artifacts")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path("path").asText()).isEqualTo("$.fragments[0]");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }
}

package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.mcp.DedirenTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MCP surface must not drift from the CLI. Same inputs, same envelope — byte for byte.
 *
 * <p>Both lanes call the same core entry points, so a divergence here means the MCP layer has
 * started interpreting, reformatting, or re-deriving something it should be passing through.
 *
 * <p>Assertions compare {@code .strip()}ed text on purpose, not to launder a divergence: the
 * envelope JSON is identical, but the CLI's {@code println} appends a trailing newline the MCP
 * tool-result text does not — an immaterial, agent-invisible difference, since every consumer
 * parses the envelope as JSON and none compares it as raw bytes. Only the older {@code
 * validate}/{@code build} lanes actually rely on the strip; the four analysis commands are
 * newline-free on both lanes (each passes through one pre-serialized {@code EngineRunOutcome}), so
 * for them the strip is a no-op and the equality is genuinely byte-for-byte.
 */
class CliMcpParityTest {

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static Path policy(String name) {
    return Path.of("..", "fixtures", "render-policy", name).toAbsolutePath().normalize();
  }

  private static Path exportPolicy(String name) {
    return Path.of("..", "fixtures", "export-policy", name).toAbsolutePath().normalize();
  }

  @Test
  void validateProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"validate", "--input", source.toString()}, "", Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }

  @Test
  void validateProducesTheSameErrorEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("invalid-duplicate-id.json"), source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"validate", "--input", source.toString()}, "", Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isTrue();
  }

  @Test
  void policyValidateProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    // dediren_validate accepts policy documents like the CLI does (the schema-migration design's
    // "Known asymmetry", closed): same family dispatch, same envelope, byte for byte.
    Path policy = root.resolve("policy.json");
    Files.copy(exportPolicy("default-oef.json"), policy);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"validate", "--input", policy.toString()}, "", Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "policy.json")));

    assertThat(cli.exitCode()).isZero();
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isFalse();
  }

  @Test
  void structuralFailureProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root)
      throws Exception {
    // Missing plugins.generic-graph passes schema validation ("plugins": {} is legal) and fails
    // structurally in the semantics engine. Both lanes must carry the same error envelope — this
    // failure class historically went raw (stderr + empty stdout) on the CLI lane only.
    Path source = root.resolve("model.json");
    Files.writeString(
        source,
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [],
          "relationships": [],
          "plugins": {}
        }
        """);

    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--plugin",
              "generic-graph",
              "--profile",
              "archimate",
              "--input",
              source.toString()
            },
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(
                new CallToolRequest(
                    "dediren_validate", Map.of("source", "model.json", "profile", "archimate")));

    assertThat(cli.exitCode()).isEqualTo(2);
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isTrue();
  }

  @Test
  void buildProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json")));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(mcpOut.resolve("main")).isDirectory();
  }

  /**
   * The one lane the suite never drove: a build that fails <em>after</em> validation, inside {@code
   * BuildCommand.run} itself, so it escapes as a raw exception rather than folding into a per-view
   * error in the returned {@code BuildResult} envelope. That is exactly where the two lanes diverge
   * in code -- {@code DedirenTools.build}'s own {@code catch} clauses versus {@code Main}'s {@code
   * writePluginError}/{@code printCommandIoFailure}/{@code printProductRootFailure} -- and it had
   * no coverage at all.
   *
   * <p>Forced by occupying the view's write target with a plain file before the build runs: {@code
   * BuildCommand.writeFile} calls {@code Files.createDirectories(outDir/"main")} to create the view
   * directory, and per the {@code Files.createDirectories} contract that throws {@code
   * FileAlreadyExistsException} when the target exists but is not a directory. That IOException is
   * wrapped as an {@code UncheckedIOException} and thrown from a call site outside every {@code
   * runStage} try/catch in {@code BuildCommand}, so it propagates out of {@code
   * BuildCommand.run(...)} uncaught -- straight into the one exception->envelope branch this test
   * had never exercised on either lane.
   */
  @Test
  void buildProducesTheSameErrorEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-basic.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("default-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    Files.writeString(cliOut.resolve("main"), "occupied");
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    Files.writeString(mcpOut.resolve("main"), "occupied");
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json")));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    // Sanity check that this is genuinely the exception->envelope branch, not a per-view
    // BuildResult error: DedirenTools.build's UncheckedIOException catch and Main's
    // printCommandIoFailure both publish DEDIREN_COMMAND_IO_FAILED.
    assertThat(textOf(mcp)).contains("DEDIREN_COMMAND_IO_FAILED");
  }

  /**
   * Handler parity, not wire parity. This calls DedirenTools directly; over a real MCP connection
   * the SDK validates arguments against ToolSchemas.BUILD's enum first (validateToolInputs defaults
   * to true), so an unknown emit kind is rejected by the transport and this handler path is never
   * reached. Both rejections are correct; they simply happen at different layers, which is why
   * ToolSchemasTest pins the advertised enum to BuildCommand.EMIT_KINDS.
   */
  @Test
  void buildRejectsUnknownEmitKindThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString(),
              "--emit",
              "bogus"
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        mcpOut.toString(),
                        "render_policy",
                        "policy.json",
                        "emit",
                        java.util.List.of("bogus"))));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(textOf(mcp)).contains("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void buildDeduplicatesRepeatedViewsThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString(),
              "--views",
              "main,main"
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        mcpOut.toString(),
                        "render_policy",
                        "policy.json",
                        "views",
                        java.util.List.of("main", "main"))));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }

  @Test
  void validateWithProfileProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root)
      throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-archimate.json"), source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--plugin",
              "generic-graph",
              "--profile",
              "archimate",
              "--input",
              source.toString()
            },
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(
                new CallToolRequest(
                    "dediren_validate", Map.of("source", "model.json", "profile", "archimate")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }

  @Test
  void buildOefLaneProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-archimate.json"), source);
    Path oefPolicy = root.resolve("oef.json");
    Files.copy(exportPolicy("default-oef.json"), oefPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--oef-policy",
              oefPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "oef_policy", "oef.json")));

    // Parity is asserted regardless of whether the export itself succeeds. OEF/XMI schema
    // validation needs an XSD -- via DEDIREN_OEF_SCHEMA_DIR / a cache dir, or a network download --
    // which this hermetic Map.of() env deliberately does not provide, so the lane legitimately
    // errors (schema unavailable, offline) or succeeds (schema reachable) depending on the
    // environment. Either way BOTH lanes must agree byte-for-byte: that is the seam under test --
    // policy parse, engine dispatch, and env forwarding to the export engine, which only these two
    // lanes exercise. Deliberately NOT asserting a specific status/exit: that would couple the test
    // to schema/network availability and make it flaky. Successful-export CORRECTNESS is owned by
    // the engine tests (OefExportEngineTest / umlxmi MainTest, which supply a stub XSD via env);
    // this test does not duplicate it.
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }

  @Test
  void buildXmiLaneProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-uml-basic.json"), source);
    Path xmiPolicy = root.resolve("xmi.json");
    Files.copy(exportPolicy("default-uml-xmi.json"), xmiPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--xmi-policy",
              xmiPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "xmi_policy", "xmi.json")));

    // Parity is asserted regardless of whether the export itself succeeds. OEF/XMI schema
    // validation needs an XSD -- via DEDIREN_OEF_SCHEMA_DIR / a cache dir, or a network download --
    // which this hermetic Map.of() env deliberately does not provide, so the lane legitimately
    // errors (schema unavailable, offline) or succeeds (schema reachable) depending on the
    // environment. Either way BOTH lanes must agree byte-for-byte: that is the seam under test --
    // policy parse, engine dispatch, and env forwarding to the export engine, which only these two
    // lanes exercise. Deliberately NOT asserting a specific status/exit: that would couple the test
    // to schema/network availability and make it flaky. Successful-export CORRECTNESS is owned by
    // the engine tests (OefExportEngineTest / umlxmi MainTest, which supply a stub XSD via env);
    // this test does not duplicate it.
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }

  @Test
  void buildWithAllEmitKindsProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root)
      throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString(),
              "--emit",
              "layout-request,layout-result,render-metadata"
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        mcpOut.toString(),
                        "render_policy",
                        "policy.json",
                        "emit",
                        java.util.List.of("layout-request", "layout-result", "render-metadata"))));

    assertThat(cli.exitCode()).isZero();
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(mcpOut.resolve("main/layout-request.json")).exists();
    assertThat(mcpOut.resolve("main/layout-result.json")).exists();
    assertThat(mcpOut.resolve("main/render-metadata.json")).exists();
  }

  /**
   * An explicitly empty {@code views} list must keep meaning "build every view", exactly like
   * omitting the argument. Only a list whose <em>elements</em> are malformed is an error — see
   * DedirenToolsTest.buildRejectsANonStringViewsElement. Pinned here because the CLI lane is the
   * oracle for what "build every view" produces.
   */
  @Test
  void buildTreatsAnEmptyViewsListAsEveryViewThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        mcpOut.toString(),
                        "render_policy",
                        "policy.json",
                        "views",
                        java.util.List.of())));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }

  @Test
  void diffProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path oldModel = root.resolve("old.json");
    Files.copy(fixture("valid-basic.json"), oldModel);
    Path newModel = root.resolve("new.json");
    Files.copy(fixture("valid-pipeline-rich.json"), newModel);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"diff", "--old", oldModel.toString(), "--new", newModel.toString()},
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .diff(
                new CallToolRequest("dediren_diff", Map.of("old", "old.json", "new", "new.json")));

    // The parity claim is byte-equality + the isError mapping, not that the diff succeeds; that a
    // real diff yields an ok result is pinned by DedirenToolsTest.diffReturnsAChangeEnvelope.
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }

  @Test
  void queryProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"query", "--kind", "orphans", "--input", source.toString()},
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .query(
                new CallToolRequest(
                    "dediren_query", Map.of("source", "model.json", "kind", "orphans")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }

  /** The argument validation moved into core (dependents needs an id) must reject identically. */
  @Test
  void queryDependentsWithoutIdRejectsThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"query", "--kind", "dependents", "--input", source.toString()},
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .query(
                new CallToolRequest(
                    "dediren_query", Map.of("source", "model.json", "kind", "dependents")));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isTrue();
  }

  @Test
  void verifyProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-basic.json"), source);
    Path out = buildArtifacts(root, source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {"verify", "--input", source.toString(), "--artifacts", out.toString()},
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .verify(
                new CallToolRequest(
                    "dediren_verify", Map.of("source", "model.json", "artifacts", "out")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    // The invariant that makes verify parity possible: artifact paths are reported relative to the
    // artifacts dir (pinned literally by CliProvenanceTest), so the MCP lane — which resolves that
    // dir to an absolute real path — never echoes the server's absolute root back to the model.
    assertThat(textOf(mcp)).doesNotContain(root.toString());
  }

  @Test
  void statusProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-basic.json"), source);
    buildArtifacts(root, source);

    CliResult cli =
        Main.executeForTesting(new String[] {"status", "--root", root.toString()}, "", Map.of());

    // Omitting 'dir' indexes the server root itself, matching --root.
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .status(new CallToolRequest("dediren_status", Map.of()));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(textOf(mcp)).doesNotContain(root.toString());
  }

  /** Builds the render lane into {@code <root>/out} so verify/status have a stamped artifact. */
  private static Path buildArtifacts(Path root, Path source) throws Exception {
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("default-svg.json"), renderPolicy);
    Path out = Files.createDirectories(root.resolve("out"));
    CliResult built =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              out.toString(),
              "--render-policy",
              renderPolicy.toString()
            },
            "",
            Map.of());
    assertThat(built.exitCode()).describedAs(built.stdout()).isZero();
    return out;
  }

  /** The envelope names the artifacts it wrote, so the out dir differs between the two lanes. */
  private static String normalizePaths(String envelope, Path out) {
    return envelope.replace(out.toString(), "<OUT>").strip();
  }
}

package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The artifact trust chain: build-lane artifacts carry a deterministic provenance stamp (model
 * canonical hash, view id, policy hash, tool version — never a timestamp), `verify` turns "is this
 * artifact current?" into an exit-decidable envelope, and `status` indexes a workspace's models and
 * stamped artifacts. Two identical builds must produce byte-identical stamped artifacts.
 */
class CliProvenanceTest {
  @TempDir Path temp;

  private Path model;
  private Path policy;

  private Path build(String outName) throws Exception {
    model = temp.resolve("model.json");
    if (!Files.exists(model)) {
      Files.copy(workspaceRoot().resolve("fixtures/source/valid-basic.json"), model);
    }
    policy = temp.resolve("policy.json");
    if (!Files.exists(policy)) {
      Files.copy(workspaceRoot().resolve("fixtures/render-policy/default-svg.json"), policy);
    }
    Path out = temp.resolve(outName);
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              model.toString(),
              "--out",
              out.toString(),
              "--render-policy",
              policy.toString()
            },
            "");
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    return out;
  }

  @Test
  void buildStampsTheSvgAndVerifyReportsCurrent() throws Exception {
    Path out = build("out");

    String svg = Files.readString(out.resolve("main/diagram.svg"));
    assertThat(svg).contains("dediren-provenance", "model_sha256", "render_policy_sha256");

    CliResult verify =
        Main.executeForTesting(
            new String[] {"verify", "--input", model.toString(), "--artifacts", out.toString()},
            "");
    JsonNode envelope = JsonSupport.objectMapper().readTree(verify.stdout());
    assertThat(verify.exitCode()).describedAs(verify.stdout()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/verify_result_schema_version").asText())
        .isEqualTo("verify-result.schema.v1");
    assertThat(envelope.at("/data/artifacts/0/path").asText()).isEqualTo("main/diagram.svg");
    assertThat(envelope.at("/data/artifacts/0/status").asText()).isEqualTo("current");
  }

  @Test
  void verifyFlagsArtifactsOfAnEditedModelAsStale() throws Exception {
    Path out = build("out");
    // The semantic content changed, so the canonical hash changes with it.
    Files.writeString(model, Files.readString(model).replace("\"Client\"", "\"Customer\""));

    CliResult verify =
        Main.executeForTesting(
            new String[] {"verify", "--input", model.toString(), "--artifacts", out.toString()},
            "");
    JsonNode envelope = JsonSupport.objectMapper().readTree(verify.stdout());
    assertThat(verify.exitCode()).isEqualTo(2);
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_ARTIFACT_STALE");
    assertThat(envelope.at("/data/artifacts/0/status").asText()).isEqualTo("stale");
  }

  @Test
  void verifyFlagsAnUnstampedArtifactWithAWarning() throws Exception {
    Path out = build("out");
    Files.writeString(
        out.resolve("main/diagram.svg"),
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"></svg>");

    CliResult verify =
        Main.executeForTesting(
            new String[] {"verify", "--input", model.toString(), "--artifacts", out.toString()},
            "");
    JsonNode envelope = JsonSupport.objectMapper().readTree(verify.stdout());
    assertThat(verify.exitCode()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_ARTIFACT_UNSTAMPED");
    assertThat(envelope.at("/data/artifacts/0/status").asText()).isEqualTo("unstamped");
  }

  @Test
  void statusIndexesModelsAndClassifiesArtifacts() throws Exception {
    build("out");

    CliResult status =
        Main.executeForTesting(new String[] {"status", "--root", temp.toString()}, "");
    JsonNode envelope = JsonSupport.objectMapper().readTree(status.stdout());
    assertThat(status.exitCode()).describedAs(status.stdout()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    JsonNode data = envelope.get("data");
    assertThat(data.at("/status_result_schema_version").asText())
        .isEqualTo("status-result.schema.v1");
    assertThat(data.at("/models/0/path").asText()).isEqualTo("model.json");
    assertThat(data.at("/models/0/sha256").asText()).hasSize(64);
    assertThat(data.at("/artifacts/0/path").asText()).isEqualTo("out/main/diagram.svg");
    assertThat(data.at("/artifacts/0/status").asText()).isEqualTo("current");
  }

  @Test
  void twoIdenticalBuildsProduceByteIdenticalStampedArtifacts() throws Exception {
    Path first = build("first");
    Path second = build("second");

    assertThat(Files.readString(first.resolve("main/diagram.svg")))
        .isEqualTo(Files.readString(second.resolve("main/diagram.svg")));
  }

  private static Path workspaceRoot() {
    return Path.of("..").toAbsolutePath().normalize();
  }
}

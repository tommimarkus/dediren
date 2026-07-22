package dev.dediren.core.analysis;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.analysis.StatusResult;
import dev.dediren.contracts.analysis.VerifyResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.source.SourceValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The read side of the trust chain. {@code verify} classifies every recognized artifact under a
 * tree against one model's recomputed canonical hash; {@code status} indexes a workspace — model
 * documents by hash plus every stamped artifact matched against the models actually present. Both
 * are pure reads with sorted, byte-stable output.
 */
public final class ProvenanceCheck {

  public static final String CURRENT = "current";
  public static final String STALE = "stale";
  public static final String UNSTAMPED = "unstamped";

  // Build-lane stamps sit at the top of the file (an SVG <metadata> first child, an XML comment
  // right after the declaration), and a source model carries model_schema_version as a top-level
  // field, so only a bounded head of each file is read to find them — a workspace of large
  // artifacts is never pulled into memory in full. A stamp or version field pushed beyond this head
  // (never true of `dediren build` output) is simply not seen.
  private static final int SCAN_HEAD_BYTES = 64 * 1024;

  private ProvenanceCheck() {}

  public static VerifyResult verify(String modelSha256, Path artifactsDir) {
    var artifacts = new ArrayList<VerifyResult.ArtifactStatus>();
    for (Path artifact : artifactFiles(artifactsDir)) {
      String status =
          Provenance.extract(readHead(artifact, SCAN_HEAD_BYTES))
              .map(
                  stamp ->
                      modelSha256.equals(stamp.path("model_sha256").asText()) ? CURRENT : STALE)
              .orElse(UNSTAMPED);
      artifacts.add(new VerifyResult.ArtifactStatus(relative(artifactsDir, artifact), status));
    }
    return new VerifyResult(ContractVersions.VERIFY_RESULT_SCHEMA_VERSION, modelSha256, artifacts);
  }

  public static StatusResult status(Path root) {
    var models = new ArrayList<StatusResult.ModelEntry>();
    var modelHashes = new TreeSet<String>();
    for (Path candidate : filesWithSuffix(root, ".json")) {
      modelSha(candidate)
          .ifPresent(
              sha -> {
                models.add(new StatusResult.ModelEntry(relative(root, candidate), sha));
                modelHashes.add(sha);
              });
    }
    var artifacts = new ArrayList<StatusResult.ArtifactEntry>();
    for (Path artifact : artifactFiles(root)) {
      Optional<JsonNode> stamp = Provenance.extract(readHead(artifact, SCAN_HEAD_BYTES));
      String claimed = stamp.map(s -> s.path("model_sha256").asText()).orElse(null);
      String status = stamp.isEmpty() ? UNSTAMPED : modelHashes.contains(claimed) ? CURRENT : STALE;
      artifacts.add(new StatusResult.ArtifactEntry(relative(root, artifact), status, claimed));
    }
    return new StatusResult(ContractVersions.STATUS_RESULT_SCHEMA_VERSION, models, artifacts);
  }

  /**
   * A model document is any JSON file carrying {@code model_schema_version} that passes the full
   * source load — the same path build takes — so its hash is computed over the identical assembled,
   * validated form the stamps were computed over (fragments included). Files that do not load as
   * valid models are simply not indexed. A bounded head read is a cheap negative filter first, so a
   * large non-model JSON is never fully read or parsed.
   */
  private static Optional<String> modelSha(Path candidate) {
    try {
      if (!readHead(candidate, SCAN_HEAD_BYTES).contains("\"model_schema_version\"")) {
        return Optional.empty();
      }
      String text = readQuietly(candidate);
      JsonNode parsed = JsonSupport.objectMapper().readTree(text);
      if (!parsed.isObject() || !parsed.has("model_schema_version")) {
        return Optional.empty();
      }
      SourceDocument document =
          SourceValidator.loadAndValidateSourceDocument(text, candidate.getParent());
      return Optional.of(CanonicalJson.sha256(JsonSupport.objectMapper().valueToTree(document)));
    } catch (SourceValidator.SourceDiagnosticsException | RuntimeException error) {
      return Optional.empty();
    }
  }

  private static List<Path> artifactFiles(Path root) {
    var files = new ArrayList<Path>();
    files.addAll(filesWithSuffix(root, ".svg"));
    files.addAll(filesWithSuffix(root, ".xml"));
    files.addAll(filesWithSuffix(root, ".xmi"));
    files.sort(Path::compareTo);
    return files;
  }

  private static List<Path> filesWithSuffix(Path root, String suffix) {
    try (Stream<Path> walk = Files.walk(root)) {
      // Regular files always have a name; the requireNonNull states that for the analyzer.
      return walk.filter(Files::isRegularFile)
          .filter(
              file ->
                  Objects.requireNonNull(file.getFileName(), "file name")
                      .toString()
                      .endsWith(suffix))
          .sorted()
          .toList();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String readQuietly(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "";
    }
  }

  /**
   * The first {@code limit} bytes of a file, decoded UTF-8. A multibyte character split at the byte
   * boundary decodes to the replacement character, which is harmless here: the tokens sought (stamp
   * marker, version field) are ASCII and sit far from the boundary.
   */
  private static String readHead(Path file, int limit) {
    try (InputStream in = Files.newInputStream(file)) {
      return new String(in.readNBytes(limit), StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "";
    }
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }
}

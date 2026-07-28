package dev.dediren.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.core.source.SourceLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvenanceCheckTest {
  @TempDir Path dir;

  private static final String HASH_A = "aaaaaaaa";
  private static final String HASH_B = "bbbbbbbb";
  private static final String EMPTY_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>";

  private static String stampFor(String modelSha) {
    return Provenance.payload("model.schema.v1", modelSha, "main", "oef_policy_sha256", "p", "1");
  }

  @Test
  void verifyClassifiesTopStampedArtifactsAgainstTheModelHash() throws Exception {
    Files.writeString(dir.resolve("current.svg"), Provenance.stampSvg(EMPTY_SVG, stampFor(HASH_A)));
    Files.writeString(dir.resolve("stale.svg"), Provenance.stampSvg(EMPTY_SVG, stampFor(HASH_B)));
    Files.writeString(dir.resolve("plain.svg"), EMPTY_SVG);

    var result = ProvenanceCheck.verify(HASH_A, dir);

    assertThat(result.artifacts())
        .extracting(a -> a.path() + "=" + a.status())
        .containsExactlyInAnyOrder(
            "current.svg=" + ProvenanceCheck.CURRENT,
            "stale.svg=" + ProvenanceCheck.STALE,
            "plain.svg=" + ProvenanceCheck.UNSTAMPED);
  }

  @Test
  void aStampBuriedBeyondTheHeadIsNotRead() throws Exception {
    // The read is bounded to the file head, so a stamp past it (never produced by build) reports
    // unstamped instead of forcing the whole, potentially huge, file into memory.
    String filler = " ".repeat(256 * 1024);
    Files.writeString(
        dir.resolve("deep.svg"),
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/>"
            + filler
            + "<metadata id=\"dediren-provenance\">"
            + stampFor(HASH_A)
            + "</metadata></svg>");

    var result = ProvenanceCheck.verify(HASH_A, dir);

    assertThat(result.artifacts())
        .singleElement()
        .satisfies(a -> assertThat(a.path()).isEqualTo("deep.svg"));
    assertThat(result.artifacts().getFirst().status()).isEqualTo(ProvenanceCheck.UNSTAMPED);
  }

  @Test
  void statusDoesNotIndexALargeNonModelJson() throws Exception {
    // A large JSON without the version field is filtered by the bounded head read, never fully
    // parsed or validated as a model.
    Files.writeString(dir.resolve("data.json"), "{\"x\":\"" + "y".repeat(256 * 1024) + "\"}");

    var result = ProvenanceCheck.status(dir, null);

    assertThat(result.models()).isEmpty();
    assertThat(result.artifacts()).isEmpty();
  }

  @Test
  void statusDoesNotIndexAValidModelOverTheReadCeiling() throws Exception {
    // An otherwise-valid model that exceeds the input ceiling: the head scan sees the version
    // marker, but the full read is refused by BoundedReads, so the candidate degrades to
    // not-indexed instead of being pulled into memory whole. A small copy of the same shape
    // is the control proving only the size excludes it.
    try (var writer = Files.newBufferedWriter(dir.resolve("huge.json"))) {
      writer.write(modelHead());
      char[] chunk = new char[1 << 20];
      Arrays.fill(chunk, 'a');
      long target = SourceLimits.DEFAULT.maxInputFileBytes() + chunk.length;
      for (long written = 0; written < target; written += chunk.length) {
        writer.write(chunk);
      }
      writer.write(modelTail());
    }
    Files.writeString(dir.resolve("small.json"), modelHead() + "small" + modelTail());

    var result = ProvenanceCheck.status(dir, null);

    assertThat(result.models())
        .singleElement()
        .satisfies(model -> assertThat(model.path()).isEqualTo("small.json"));
  }

  private static String modelHead() {
    return """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [
            { "id": "api", "type": "generic.component", "properties": {}, "label": "\
        """
        .stripTrailing();
  }

  private static String modelTail() {
    return """
        " }
          ],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """;
  }
}

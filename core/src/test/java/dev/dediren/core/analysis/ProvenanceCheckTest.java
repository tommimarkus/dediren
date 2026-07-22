package dev.dediren.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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

    var result = ProvenanceCheck.status(dir);

    assertThat(result.models()).isEmpty();
    assertThat(result.artifacts()).isEmpty();
  }
}

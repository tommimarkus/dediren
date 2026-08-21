package dev.dediren.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.dediren.contracts.pkg.PackageExportLane;
import dev.dediren.core.analysis.Provenance;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The owner for "stamp, name, and write an engine's output artifact".
 *
 * <p>Before this type existed the concern was smeared across orchestration: {@code BuildCommand}
 * decided the provenance field name with {@code "oef".equals(baseName)}, {@code
 * PackageBuildCommand} repeated that decision in a second form keyed on the lane enum, and every
 * render artifact was pushed through {@code Provenance.stampSvg} regardless of its kind — which
 * returns its input unchanged when there is no {@code <svg>} root, so a non-SVG render artifact
 * would have been written silently unstamped.
 */
class ArtifactSinkTest {

  private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>";
  private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model/>";
  private static final String TEXT = "+--------+\n| a box  |\n+--------+\n";

  private static String payload() {
    return Provenance.payload(
        "model.schema.v1", "deadbeef", "main", "render_policy_sha256", "cafef00d", "2026.08.8");
  }

  @Test
  void textArtifactsReportThatTheyAreNotStampedInsteadOfPassingThroughSilently() {
    // The SD-E-6 guard. The old path returned the input unchanged and the caller wrote it as if it
    // had been stamped; `dediren status` would then report the file `unstamped` with nothing in the
    // build lane having said so. Not-stampable must be a value the caller can see, not a no-op.
    ArtifactSink.Stamped stamped = ArtifactSink.stamp("ascii+text", TEXT, payload());

    assertAll(
        () -> assertThat(stamped.stamped()).isFalse(),
        () -> assertThat(stamped.content()).isEqualTo(TEXT),
        () -> assertThat(Provenance.extract(stamped.content())).isEmpty());
  }

  @Test
  void bothXmlSerializationsStampThroughOneDecision() {
    // svg+xml and archimate-oef+xml differ in stamp *style* (metadata element vs leading comment),
    // which is a property of the format and belongs in one owned table — not in two call sites that
    // each picked a Provenance method by hand.
    ArtifactSink.Stamped svg = ArtifactSink.stamp("svg+xml", SVG, payload());
    ArtifactSink.Stamped oef = ArtifactSink.stamp("archimate-oef+xml", XML, payload());

    assertAll(
        () -> assertThat(svg.stamped()).isTrue(),
        () -> assertThat(oef.stamped()).isTrue(),
        () ->
            assertThat(readBack(svg.content()).path("model_sha256").asText()).isEqualTo("deadbeef"),
        () ->
            assertThat(readBack(oef.content()).path("model_sha256").asText()).isEqualTo("deadbeef"),
        // SVG carries the stamp inside the root element; the generic XML lane carries it as a
        // comment ahead of the root. Both are stamped; only the mechanism differs.
        () -> assertThat(svg.content()).contains("<metadata id=\"dediren-provenance\""),
        () -> assertThat(oef.content()).contains("<!-- dediren-provenance"));
  }

  @Test
  void jsonArtifactsStayParseableRatherThanTakingAnXmlComment() {
    // Regression: every export artifact used to go through Provenance.stampXml regardless of its
    // serialization, so a "<id>+json" export was written as an XML comment followed by the
    // document — not parseable as JSON by anything. No first-party engine emits +json today, which
    // is the only reason this never shipped as a visible defect.
    ArtifactSink.Stamped stamped = ArtifactSink.stamp("stats+json", "{}", payload());

    assertAll(
        () -> assertThat(stamped.stamped()).isFalse(),
        () -> assertThat(stamped.content()).isEqualTo("{}"),
        () -> assertThat(stamped.content()).doesNotContain("<!--"));
  }

  @Test
  void anUnknownSerializationFailsFastRatherThanWritingAnUnstampedFile() {
    assertThatThrownBy(() -> ArtifactSink.stamp("something+binary", "x", payload()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("something+binary");
  }

  @Test
  void everyExportLaneOwnsItsOwnProvenanceFieldName() {
    // Replaces `boolean oef = "oef".equals(baseName)` in BuildCommand and the parallel ternary in
    // PackageBuildCommand. A third lane must get its own key rather than inheriting XMI's.
    assertAll(
        () -> assertThat(ExportLane.ARCHIMATE_OEF.policyShaKey()).isEqualTo("oef_policy_sha256"),
        () -> assertThat(ExportLane.UML_XMI.policyShaKey()).isEqualTo("xmi_policy_sha256"),
        () ->
            assertThat(ExportLane.values())
                .extracting(ExportLane::policyShaKey)
                .doesNotHaveDuplicates(),
        () ->
            assertThat(ExportLane.values())
                .extracting(ExportLane::engineId)
                .containsExactlyInAnyOrder("archimate-oef", "uml-xmi"));
  }

  @Test
  void theBuildLaneAndThePackageLaneResolveTheSameLaneIdentically() {
    // The SD-S-2 divergence: the two orchestrators reached the same answer by different routes, so
    // nothing stopped them drifting. One table, both callers.
    assertAll(
        () ->
            assertThat(ExportLane.of(PackageExportLane.ARCHIMATE_OEF))
                .isEqualTo(ExportLane.ARCHIMATE_OEF),
        () -> assertThat(ExportLane.of(PackageExportLane.UML_XMI)).isEqualTo(ExportLane.UML_XMI));
  }

  @Test
  void artifactKindsNameTheirOwnFileExtensions() {
    // An SVG stays `diagram.svg`, not `diagram.xml`: the render lane names files by format, the
    // export lane by serialization. Converging the kind encoding does not converge file naming, so
    // both rules live here explicitly rather than one being inferred from the other.
    assertAll(
        () -> assertThat(ArtifactSink.renderExtension("svg+xml")).isEqualTo("svg"),
        () -> assertThat(ArtifactSink.renderExtension("ascii+text")).isEqualTo("txt"),
        () -> assertThat(ArtifactSink.exportExtension("archimate-oef+xml")).isEqualTo("xml"),
        () -> assertThat(ArtifactSink.exportExtension("uml-xmi+xml")).isEqualTo("xml"),
        () -> assertThat(ArtifactSink.exportExtension("drawio+xml")).isEqualTo("xml"));
  }

  private static JsonNode readBack(String content) {
    Optional<JsonNode> extracted = Provenance.extract(content);
    assertThat(extracted).as("stamped content must carry an extractable payload").isPresent();
    return extracted.get();
  }
}

package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz-regression target for the draw.io import engine seam: whatever bytes {@link
 * DrawioImportEngine#importSource} sees, it either returns a valid {@link SourceDocument} or fails
 * atomically with exactly one published {@code DEDIREN_DRAWIO_*} diagnostic. A raw {@link
 * RuntimeException} (index-out-of-bounds, {@link StackOverflowError}, an infinite loop, ...)
 * escaping is the finding this guards against — the same invariant {@code DotParserFuzzTest} pins
 * for the DOT lane, exercised here through the whole reader-then-mapper composition rather than one
 * stage.
 *
 * <p>With {@code JAZZER_FUZZ} unset this runs in deterministic regression mode over the checked-in
 * seed corpus under {@code DrawioImportFuzzTestInputs/<methodName>/}: malformed XML, a DOCTYPE, a
 * deeply-nested parent chain, a compressed page, a truncated base64 payload, a cyclic parent chain,
 * and a valid basic document. To run the coverage-guided exploratory fuzzer locally (needs the
 * Jazzer native agent):
 *
 * <pre>{@code
 * JAZZER_FUZZ=1 ./mvnw -pl engines/drawio -am test \
 *     -Dtest=DrawioImportFuzzTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
class DrawioImportFuzzTest {

  private final DrawioImportEngine engine = new DrawioImportEngine();

  @FuzzTest
  void arbitraryBoundedBytesEitherImportOrFailAtomically(byte[] data) throws Exception {
    String source = new String(data, StandardCharsets.UTF_8);
    try {
      EngineResult<SourceDocument> result = engine.importSource(source);
      assertThat(result.value()).isNotNull();
    } catch (EngineException rejected) {
      assertThat(rejected.exitCode()).isEqualTo(2);
      assertThat(rejected.diagnostics()).hasSize(1);
      assertThat(rejected.diagnostics().get(0).code()).startsWith("DEDIREN_DRAWIO_");
    }
  }
}

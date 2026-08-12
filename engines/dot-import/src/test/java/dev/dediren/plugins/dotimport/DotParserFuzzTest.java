package dev.dediren.plugins.dotimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.dediren.engine.EngineException;
import java.nio.charset.StandardCharsets;

/**
 * Fuzz-regression target: whatever bytes the lexer/parser see, they either produce a document or
 * fail atomically with exactly one published {@code DEDIREN_DOT_*} diagnostic. A raw {@link
 * RuntimeException} (index-out-of-bounds, {@link StackOverflowError}, ...) escaping is the finding
 * this guards against.
 *
 * <p>With {@code JAZZER_FUZZ} unset this runs in deterministic regression mode over the checked-in
 * seed corpus under {@code DotParserFuzzTestInputs/<methodName>/}. To run the coverage-guided
 * exploratory fuzzer locally (needs the Jazzer native agent):
 *
 * <pre>{@code
 * JAZZER_FUZZ=1 ./mvnw -pl engines/dot-import -am test \
 *     -Dtest=DotParserFuzzTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
class DotParserFuzzTest {

  @FuzzTest
  void arbitraryBoundedBytesEitherParseOrFailAtomically(byte[] data) throws Exception {
    String source = new String(data, StandardCharsets.UTF_8);
    try {
      DotDocument document = DotParser.parse(source);
      assertThat(document).isNotNull();
    } catch (EngineException rejected) {
      assertThat(rejected.exitCode()).isEqualTo(2);
      assertThat(rejected.diagnostics()).hasSize(1);
      assertThat(rejected.diagnostics().get(0).code()).startsWith("DEDIREN_DOT_");
    }
  }
}

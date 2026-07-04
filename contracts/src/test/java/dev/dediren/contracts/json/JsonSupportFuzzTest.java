package dev.dediren.contracts.json;

import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.dediren.contracts.layout.LayoutRequest;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;

/**
 * Fuzz-regression targets for the public envelope-JSON parse surface (audit finding F7).
 *
 * <p>Agents feed attacker-influenceable envelope JSON to the CLI and plugins, all of which decode
 * it through {@link JsonSupport}. The invariant these targets pin is robustness, not correctness:
 * the only {@link Throwable} allowed to escape {@link JsonSupport#readTree(String)} and {@link
 * JsonSupport#readValue(String, Class)} is Jackson's documented parse/mapping failure type, {@link
 * JacksonException} (Jackson 3 made it an unchecked {@link RuntimeException}; every databind and
 * stream-constraint failure is a subtype). Anything else — {@link StackOverflowError}, {@link
 * OutOfMemoryError}, {@link NullPointerException}, {@link IllegalArgumentException}, ... — is not
 * caught here, so it propagates and fails the target, which is the finding we want.
 *
 * <p>With {@code JAZZER_FUZZ} unset these run in deterministic regression mode over the checked-in
 * seed corpora under {@code JsonSupportFuzzTestInputs/<methodName>/}. To run the coverage-guided
 * exploratory fuzzer locally (needs the Jazzer native agent; ~60s per target):
 *
 * <pre>{@code
 * JAZZER_FUZZ=1 ./mvnw -pl contracts -am test \
 *     -Dtest=JsonSupportFuzzTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
class JsonSupportFuzzTest {

  @FuzzTest
  void readTreeOnlyThrowsJacksonException(byte[] data) {
    String json = new String(data, StandardCharsets.UTF_8);
    try {
      JsonSupport.readTree(json);
    } catch (JacksonException expected) {
      // Documented parse failure — the only Throwable the invariant permits to escape.
    }
  }

  @FuzzTest
  void readValueOnlyThrowsJacksonException(byte[] data) {
    String json = new String(data, StandardCharsets.UTF_8);
    try {
      JsonSupport.readValue(json, LayoutRequest.class);
    } catch (JacksonException expected) {
      // Documented parse/mapping failure — the only Throwable the invariant permits to escape.
    }
  }
}

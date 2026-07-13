package dev.dediren.schemacache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XmlSchemaValidatorTest {

  @TempDir Path tempDir;

  @Test
  void validatorExitingZeroReportsAValidDocument() throws Exception {
    Path validator = fakeValidator("ok", "cat > /dev/null\nexit 0\n");

    XmlSchemaValidator.Outcome outcome =
        XmlSchemaValidator.validate(validator.toString(), schema(), "<doc/>");

    assertThat(outcome.valid()).isTrue();
    assertThat(outcome.exitCode()).isZero();
  }

  @Test
  void validatorExitingNonZeroReportsTheValidatorDetails() throws Exception {
    Path validator =
        fakeValidator(
            "invalid", "cat > /dev/null\necho 'element doc: no declaration' >&2\nexit 3\n");

    XmlSchemaValidator.Outcome outcome =
        XmlSchemaValidator.validate(validator.toString(), schema(), "<doc/>");

    assertThat(outcome.valid()).isFalse();
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(outcome.details()).contains("element doc: no declaration");
  }

  @Test
  void aValidatorFloodingStderrDoesNotDeadlock() throws Exception {
    // Regression guard. Both export engines drained stdout to EOF *before* reading stderr, with an
    // unbounded waitFor() after: a validator that fills the ~64 KiB stderr pipe blocks in write(2)
    // and never exits, so the parent waits forever on a stdout EOF that can never arrive. A
    // schema-invalid document with a systematic per-element violation reaches that volume easily —
    // so the build hung exactly when it should have reported the schema error.
    Path validator =
        fakeValidator(
            "flood",
            "cat > /dev/null\n"
                + "i=0\n"
                + "while [ $i -lt 4000 ]; do\n"
                + "  echo \"line $i: element does not validate against the schema\" >&2\n"
                + "  i=$((i+1))\n"
                + "done\n"
                + "exit 4\n");

    XmlSchemaValidator.Outcome outcome =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            () -> XmlSchemaValidator.validate(validator.toString(), schema(), "<doc/>"));

    assertThat(outcome.valid()).isFalse();
    assertThat(outcome.exitCode()).isEqualTo(4);
    assertThat(outcome.details()).contains("line 3999");
  }

  @Test
  void aValidatorThatNeverExitsIsReportedRatherThanHangingTheBuild() throws Exception {
    Path validator = fakeValidator("hang", "cat > /dev/null\nsleep 600\n");

    assertThatThrownBy(
            () ->
                XmlSchemaValidator.validate(
                    validator.toString(), schema(), "<doc/>", Duration.ofSeconds(2)))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("did not finish");
  }

  @Test
  void aMissingValidatorCommandIsReportedAsASchemaCacheFailure() throws Exception {
    Path missing = tempDir.resolve("no-such-validator");

    assertThatThrownBy(() -> XmlSchemaValidator.validate(missing.toString(), schema(), "<doc/>"))
        .isInstanceOf(SchemaCacheException.class);
  }

  private Path schema() throws IOException {
    Path schema = tempDir.resolve("schema.xsd");
    Files.writeString(schema, "<xsd/>", StandardCharsets.UTF_8);
    return schema;
  }

  private Path fakeValidator(String name, String body) throws IOException {
    assumeTrue(
        Files.isExecutable(Path.of("/bin/sh")), "a POSIX shell is required for the fake validator");
    Path script = tempDir.resolve(name);
    Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
    Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(script));
    permissions.add(PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(script, permissions);
    return script;
  }
}

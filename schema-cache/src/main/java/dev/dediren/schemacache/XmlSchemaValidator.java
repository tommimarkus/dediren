package dev.dediren.schemacache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an external XML schema validator ({@code xmllint}) over a document supplied on stdin.
 *
 * <p>This is the single home for the validator subprocess the UML/XMI export engine needs (the OEF
 * lane validates in-JVM via {@link InJvmXmlValidator} and runs no subprocess). Engines may not
 * depend on each other, so before this class each maintained its own copy of the run block; the
 * copies drifted, and every hardening fix had to be applied twice.
 *
 * <p>The outcome is deliberately code-free: a caller maps a failed run onto its own notation's
 * published diagnostic codes (for example {@code DEDIREN_OEF_SCHEMA_INVALID} versus {@code
 * DEDIREN_XMI_SCHEMA_INVALID}), so this module stays notation-neutral.
 */
public final class XmlSchemaValidator {
  // debug/trace only, by architecture rule. An invalid document is an Outcome the caller maps to a
  // published diagnostic code; it must not also be announced on stderr. See ArchitectureRulesTest.
  private static final Logger LOG = LoggerFactory.getLogger(XmlSchemaValidator.class);

  private XmlSchemaValidator() {}

  /** Wall-clock ceiling for one validator run before it is reported as unavailable. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /**
   * The result of one validator run. {@code details} carries the validator's own output for an
   * invalid document and is empty for a valid one.
   */
  public record Outcome(boolean valid, int exitCode, String details) {}

  public static Outcome validate(String validatorCommand, Path schemaPath, String content)
      throws SchemaCacheException {
    return validate(validatorCommand, schemaPath, content, DEFAULT_TIMEOUT);
  }

  public static Outcome validate(
      String validatorCommand, Path schemaPath, String content, Duration timeout)
      throws SchemaCacheException {
    // The one place a subprocess crosses the engine boundary (the XMI lane; OEF validates
    // in-JVM). Which binary, against which schema, is the first question when a validation
    // result surprises you.
    LOG.debug("schema validator: command={} schema={}", validatorCommand, schemaPath);
    Process process;
    try {
      process =
          new ProcessBuilder(
                  validatorCommand, "--nonet", "--noout", "--schema", schemaPath.toString(), "-")
              .start();
    } catch (IOException error) {
      throw new SchemaCacheException(
          "failed to run schema validator " + validatorCommand + ": " + error.getMessage(), error);
    }

    // Both pipes are drained concurrently. Draining stdout to EOF first — as both engines used to —
    // deadlocks whenever the validator fills the ~64 KiB stderr pipe (a systematic per-element
    // violation reaches that easily): the child blocks in write(2), so it never exits, so stdout
    // never reaches EOF.
    StreamDrain stdout = StreamDrain.start(process.getInputStream());
    StreamDrain stderr = StreamDrain.start(process.getErrorStream());

    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException error) {
      process.destroyForcibly();
      throw new SchemaCacheException(
          "failed to write XML to schema validator " + validatorCommand + ": " + error.getMessage(),
          error);
    }

    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new SchemaCacheException(
            "schema validator "
                + validatorCommand
                + " did not finish within "
                + timeout.toSeconds()
                + "s");
      }
      int exitCode = process.exitValue();
      byte[] out = stdout.await();
      byte[] err = stderr.await();
      LOG.debug("schema validator exit: command={} exitCode={}", validatorCommand, exitCode);
      if (exitCode == 0) {
        return new Outcome(true, 0, "");
      }
      return new Outcome(
          false,
          exitCode,
          SchemaCacheModule.commandOutputDetails(validatorCommand, exitCode, out, err));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new SchemaCacheException(
          "schema validator " + validatorCommand + " was interrupted", error);
    }
  }
}

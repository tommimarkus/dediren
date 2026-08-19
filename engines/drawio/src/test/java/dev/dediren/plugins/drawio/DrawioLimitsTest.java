package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.engine.EngineException;
import org.junit.jupiter.api.Test;

/**
 * The draw.io ceilings are not free parameters: each one either mirrors a ceiling another module
 * already enforces, or would be dead code if it did not.
 *
 * <p>The engine modules are leaf libraries and may not depend on {@code core} (§2, §5), so the
 * cross-module values are asserted here as literals that name the constant they must track. That
 * makes the coupling visible and greppable, but it is one-directional: editing {@code
 * SourceLimits.DEFAULT} would not fail this test. The stronger guard would live in {@code
 * dist-tool}, which is the module that already owns cross-module invariants; recorded rather than
 * built here because that module is outside this step's footprint.
 */
class DrawioLimitsTest {

  /** {@code SourceLimits.DEFAULT.maxInputFileBytes()} — {@code core.source.SourceLimits}. */
  private static final long SOURCE_LIMITS_MAX_INPUT_FILE_BYTES = 64L * 1024 * 1024;

  /** {@code SourceLimits.DEFAULT.maxElements()} — {@code core.source.SourceLimits}. */
  private static final int SOURCE_LIMITS_MAX_ELEMENTS = 100_000;

  /** {@code DotLimits.MAX_STATEMENTS} — {@code plugins.dotimport.DotLimits}. */
  private static final int DOT_LIMITS_MAX_STATEMENTS = 200_000;

  /** {@code DotLimits.MAX_NESTING} — {@code plugins.dotimport.DotLimits}. */
  private static final int DOT_LIMITS_MAX_NESTING = 256;

  /** {@code DotLimits.MAX_TOKEN_BYTES} — {@code plugins.dotimport.DotLimits}. */
  private static final int DOT_LIMITS_MAX_TOKEN_BYTES = 64 * 1024;

  @Test
  void theInputCeilingEqualsTheOneBoundedReadsAlreadyEnforcedBeforeTheEngineRan() {
    // Any other value would be dead code: the CLI and MCP lanes read through BoundedReads with
    // SourceLimits.DEFAULT.maxInputFileBytes() before the engine sees a byte. A *lower* value
    // would be reachable but would reject files core accepts, for no stated reason.
    assertThat(DrawioLimits.MAX_INPUT_BYTES).isEqualTo(SOURCE_LIMITS_MAX_INPUT_FILE_BYTES);
  }

  @Test
  void theElementCeilingDoesNotExceedTheOneSourceValidatorWillApplyNext() {
    // If the importer accepted more elements than SourceValidator.gateImportedDocument allows, an
    // over-large model would pass the importer and then be rejected downstream at exit 3 with a
    // core diagnostic, hiding the importer's own exit-2 DRAWIO_ELEMENT_LIMIT_EXCEEDED.
    assertThat(DrawioLimits.MAX_ELEMENTS).isLessThanOrEqualTo(SOURCE_LIMITS_MAX_ELEMENTS);
    assertThat(DrawioLimits.MAX_ELEMENTS).isEqualTo(100_000);
  }

  @Test
  void theCellNestingAndTokenCeilingsTrackTheDotImporter() {
    // An mxCell is the draw.io analogue of a DOT statement, the mxCell parent chain is the
    // analogue of subgraph nesting, and a label/attribute value is the analogue of a token.
    assertThat(DrawioLimits.MAX_CELLS).isEqualTo(DOT_LIMITS_MAX_STATEMENTS);
    assertThat(DrawioLimits.MAX_NESTING).isEqualTo(DOT_LIMITS_MAX_NESTING);
    assertThat(DrawioLimits.MAX_TOKEN_BYTES).isEqualTo(DOT_LIMITS_MAX_TOKEN_BYTES);
  }

  @Test
  void theCellCeilingIsNotBelowTheElementCeilingItFeeds() {
    // Cells outnumber elements (styling-only and group cells produce none), so a cell ceiling at
    // or below the element ceiling would make MAX_ELEMENTS unreachable and therefore untestable.
    assertThat(DrawioLimits.MAX_CELLS).isGreaterThan(DrawioLimits.MAX_ELEMENTS);
  }

  @Test
  void theDecompressionBudgetIsAggregateAndBuysAnAttackerNoExtraBytes() {
    // The whole point of the aggregate: a compressed document may not decompress to more than an
    // uncompressed document of the same ceiling would have contained. A per-page budget of this
    // size would admit MAX_PAGES x MAX_DECOMPRESSED_BYTES = 16 GiB.
    assertThat(DrawioLimits.MAX_DECOMPRESSED_BYTES).isEqualTo(DrawioLimits.MAX_INPUT_BYTES);
    assertThat(DrawioLimits.MAX_PAGES).isEqualTo(256);
  }

  @Test
  void theTokenCheckAcceptsTheCeilingAndRefusesTheFirstByteAboveIt() {
    assertThatCode(() -> DrawioLimits.checkTokenBytes("x".repeat(64 * 1024)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> DrawioLimits.checkTokenBytes("x".repeat(64 * 1024 + 1)))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error ->
                assertDiagnostic(
                    (EngineException) error, "DEDIREN_DRAWIO_TOKEN_LIMIT_EXCEEDED", "$"));
  }

  @Test
  void theTokenCheckCountsUtf8BytesNotJavaChars() {
    // A label of 64K astral code points is 256 KiB on the wire; counting String.length() would let
    // it through. Half the ceiling in code points, over it in bytes.
    String astral = "😀".repeat(16 * 1024 + 1);
    assertThat(astral.length()).isLessThan(64 * 1024);
    assertThat(DrawioLimits.utf8Length(astral)).isGreaterThan(64L * 1024);
    assertThatThrownBy(() -> DrawioLimits.checkTokenBytes(astral))
        .isInstanceOf(EngineException.class);
  }

  @Test
  void utf8LengthCountsEachEncodingWidth() {
    assertThat(DrawioLimits.utf8Length("a")).isEqualTo(1);
    assertThat(DrawioLimits.utf8Length("é")).isEqualTo(2);
    assertThat(DrawioLimits.utf8Length("€")).isEqualTo(3);
    assertThat(DrawioLimits.utf8Length("😀")).isEqualTo(4);
  }

  @Test
  void theFailureHelpersPublishTheEngineBoundaryStructuralShape() {
    assertDiagnostic(
        DrawioLimits.limit(DiagnosticCode.DRAWIO_PAGE_LIMIT_EXCEEDED, "too many pages"),
        "DEDIREN_DRAWIO_PAGE_LIMIT_EXCEEDED",
        "$");
    assertDiagnostic(
        DrawioLimits.syntax("bad", 3, 7), "DEDIREN_DRAWIO_SYNTAX_INVALID", "line 3, column 7");
    assertDiagnostic(
        DrawioLimits.unsupported("nope", 1, 1),
        "DEDIREN_DRAWIO_UNSUPPORTED_CONSTRUCT",
        "line 1, column 1");
  }

  private static void assertDiagnostic(EngineException failure, String code, String path) {
    // Structural: the caller's file is wrong, so exit 2, one ERROR diagnostic.
    assertThat(failure.exitCode()).isEqualTo(2);
    assertThat(failure.diagnostics()).hasSize(1);
    Diagnostic diagnostic = failure.diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo(code);
    assertThat(diagnostic.path()).isEqualTo(path);
    assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
  }
}

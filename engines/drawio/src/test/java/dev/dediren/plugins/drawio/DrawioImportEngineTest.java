package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.plugins.drawio.mx.MxReader;
import dev.dediren.plugins.drawio.read.DrawioSourceMapper;
import org.junit.jupiter.api.Test;

/**
 * The importer's own seam: {@link MxReader}/{@link DrawioSourceMapper} composition, the
 * pre-parse byte ceiling, and that a rejection surfaces as the mapper's or reader's own published
 * {@code EngineException} rather than something the seam invents. The mapper's own behaviour
 * (both identity paths, declines, warnings) is {@code DrawioSourceMapperTest}'s job; this pins only
 * that {@link DrawioImportEngine#importSource} wires it up.
 */
class DrawioImportEngineTest {

  private final DrawioImportEngine engine = new DrawioImportEngine();

  @Test
  void importsAForeignDocumentIntoTheGenericGraphProfile() throws Exception {
    String source =
        """
        <mxfile host="app.diagrams.net">
          <diagram id="p-architecture" name="Architecture">
            <mxGraphModel><root>
              <mxCell id="0"/>
              <mxCell id="1" parent="0"/>
              <mxCell id="a" value="Alpha" style="rounded=1;" vertex="1" parent="1"/>
              <mxCell id="b" value="Beta" style="rounded=1;" vertex="1" parent="1"/>
              <mxCell id="e" value="calls" style="html=1;" edge="1" parent="1" source="a" target="b"/>
            </root></mxGraphModel>
          </diagram>
        </mxfile>
        """;

    EngineResult<SourceDocument> result = engine.importSource(source);

    assertThat(result.value().modelSchemaVersion()).isEqualTo("model.schema.v1");
    assertThat(result.value().nodes()).extracting(SourceNode::id).containsExactly("a", "b");
  }

  @Test
  void malformedXmlFailsAtomicallyWithThePublishedSyntaxDiagnostic() {
    assertThatThrownBy(() -> engine.importSource("<mxfile><diagram><mxGraphModel>"))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException rejected = (EngineException) error;
              assertThat(rejected.exitCode()).isEqualTo(2);
              assertThat(rejected.diagnostics()).hasSize(1);
              assertThat(rejected.diagnostics().get(0).code())
                  .isEqualTo("DEDIREN_DRAWIO_SYNTAX_INVALID");
            });
  }

  @Test
  void nullSourceFailsAtomicallyRatherThanNullPointerException() {
    assertThatThrownBy(() -> engine.importSource(null))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException rejected = (EngineException) error;
              assertThat(rejected.diagnostics().get(0).code())
                  .isEqualTo("DEDIREN_DRAWIO_SYNTAX_INVALID");
            });
  }

  @Test
  void sourceOverTheByteCeilingFailsAtomicallyBeforeParsing() {
    String oversized = "x".repeat((int) DrawioLimits.MAX_INPUT_BYTES + 1);

    assertThatThrownBy(() -> engine.importSource(oversized))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException rejected = (EngineException) error;
              assertThat(rejected.diagnostics().get(0).code())
                  .isEqualTo("DEDIREN_DRAWIO_INPUT_TOO_LARGE");
            });
  }
}

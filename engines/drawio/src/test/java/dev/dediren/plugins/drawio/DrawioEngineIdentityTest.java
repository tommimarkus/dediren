package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Both draw.io engines are skeletons in this step: the engine id and the exporter's artifact kind
 * are already the published contract, but neither body is implemented yet, so a call refuses
 * outright. It refuses as an {@link UnsupportedOperationException} rather than a diagnostic code,
 * so the scaffold adds no dead entry to the published {@code DEDIREN_DRAWIO_*} vocabulary.
 */
class DrawioEngineIdentityTest {
  private final DrawioExportEngine exportEngine = new DrawioExportEngine();
  private final DrawioImportEngine importEngine = new DrawioImportEngine();

  @Test
  void bothEnginesPublishTheDrawioEngineId() {
    assertThat(exportEngine.id()).isEqualTo("drawio");
    assertThat(importEngine.id()).isEqualTo("drawio");
  }

  @Test
  void theExporterPublishesTheDrawioXmlArtifactKind() {
    assertThat(DrawioExportEngine.ARTIFACT_KIND).isEqualTo("drawio+xml");
  }

  @Test
  void theExporterSkeletonRefusesInsteadOfReturningAnEmptyArtifact() {
    assertThatThrownBy(() -> exportEngine.export(null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theImporterSkeletonRefusesInsteadOfReturningAnEmptyDocument() {
    assertThatThrownBy(() -> importEngine.importSource("anything"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

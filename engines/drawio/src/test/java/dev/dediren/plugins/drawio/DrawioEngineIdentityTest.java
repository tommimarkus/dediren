package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The published identity of the draw.io lane: one engine id under two capabilities, and the
 * exporter's artifact kind. The exporter's own behaviour is covered by {@code
 * DrawioExportEngineTest}; the importer is still a skeleton and refuses as an {@link
 * UnsupportedOperationException} rather than a diagnostic code, so the scaffold adds no dead entry
 * to the published {@code DEDIREN_DRAWIO_*} vocabulary.
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
  void theImporterSkeletonRefusesInsteadOfReturningAnEmptyDocument() {
    assertThatThrownBy(() -> importEngine.importSource("anything"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

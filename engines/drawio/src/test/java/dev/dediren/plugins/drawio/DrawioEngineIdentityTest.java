package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The published identity of the draw.io lane: one engine id under two capabilities, and the
 * exporter's artifact kind. Both engines' own behaviour is covered by {@code
 * DrawioExportEngineTest} and {@code DrawioImportEngineTest} / {@code DrawioImportFuzzTest}.
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
}

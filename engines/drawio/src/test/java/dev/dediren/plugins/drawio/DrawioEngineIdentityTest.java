package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.engine.EngineException;
import org.junit.jupiter.api.Test;

/**
 * Both draw.io engines are skeletons in this step: {@link #id()} and the exporter's artifact kind
 * are already the published contract, but neither engine body is implemented yet, so a call
 * surfaces a structured {@link EngineException} rather than an unstructured runtime failure.
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
  void theExporterSkeletonSurfacesAStructuralEngineException() {
    assertThatThrownBy(() -> exportEngine.export(null, null, null))
        .isInstanceOf(EngineException.class)
        .extracting(exception -> ((EngineException) exception).exitCode())
        .isEqualTo(2);
  }

  @Test
  void theImporterSkeletonSurfacesAStructuralEngineException() {
    assertThatThrownBy(() -> importEngine.importSource("anything"))
        .isInstanceOf(EngineException.class)
        .extracting(exception -> ((EngineException) exception).exitCode())
        .isEqualTo(2);
  }
}

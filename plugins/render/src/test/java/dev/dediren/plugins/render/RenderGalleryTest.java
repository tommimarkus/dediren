package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Emits one styled SVG per {@link RenderScenarios#standard()} entry into the git-ignored render
 * gallery, so a human can eyeball every notation and diagram kind — ArchiMate plus the non-sequence
 * UML kinds (class, data, activity, state-machine, use-case, component, deployment) — that
 * otherwise appears in the gallery only as a node/relationship-shape sampler, never as a whole
 * diagram. Sequence is deliberately absent here: its message geometry depends on the live ELK
 * layout normalizer, so it is rendered from the real engine in the cli suite instead.
 *
 * <p>These are fixture-based renders. The assertion is a light "still produces an SVG" sanity gate;
 * the artifact itself is for visual review, not a geometry oracle. This class solely owns the
 * {@code scenarios/} gallery subdirectory (wiped once per JVM run) so it never races the sampler
 * writers in {@code MainTest}, which own {@code render-plugin/}.
 */
class RenderGalleryTest {
  private static final AtomicBoolean CLEANED = new AtomicBoolean();

  @ParameterizedTest(name = "{0} [{2}]")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#standard")
  void emitsGalleryRenderForEachScenario(String name, String layout, String policy, String metadata)
      throws Exception {
    String svg = RenderTestSupport.renderFixtures(layout, policy, metadata);
    assertThat(svg).as("scenario %s must render an SVG", name).contains("<svg", "</svg>");
    writeGalleryArtifact(name + "__" + basename(policy), svg);
  }

  private static String basename(String path) {
    String file = path.substring(path.lastIndexOf('/') + 1);
    int dot = file.lastIndexOf('.');
    return dot < 0 ? file : file.substring(0, dot);
  }

  private static void writeGalleryArtifact(String name, String svg) throws IOException {
    Path dir = RenderTestSupport.workspaceRoot().resolve(".test-output/renders/scenarios");
    cleanOnce(dir);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name + ".svg"), svg);
  }

  // Wipe the output directory once per JVM run so it holds only this run's renders, never a
  // cumulative pile of artifacts from renamed or deleted scenarios.
  private static void cleanOnce(Path dir) throws IOException {
    if (!CLEANED.compareAndSet(false, true) || !Files.isDirectory(dir)) {
      return;
    }
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}

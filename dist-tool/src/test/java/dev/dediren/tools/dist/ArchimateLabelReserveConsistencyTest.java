package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the ArchiMate label/icon reserve against cross-plugin drift. The generic-graph sizing
 * plugin and the render plugin are isolated process-boundary plugins that cannot depend on each
 * other, yet both must reserve the same {@code ARCHIMATE_LABEL_ICON_RESERVE} so a centered node
 * label clears the upper-right type icon: the sizer budgets the space, the renderer places the
 * label inside it. They were previously kept equal only by a comment. This converts that prose into
 * an enforced invariant, so a change to one constant without the other fails CI instead of silently
 * clipping labels.
 */
class ArchimateLabelReserveConsistencyTest {
  private static final Path SIZING =
      Path.of(
          "plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java");
  private static final Path RENDER =
      Path.of("plugins/render/src/main/java/dev/dediren/plugins/render/node/NodeLabels.java");
  private static final Pattern RESERVE =
      Pattern.compile("ARCHIMATE_LABEL_ICON_RESERVE\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)");

  @Test
  void archimateLabelIconReserveMatchesAcrossPlugins() throws IOException {
    Path repoRoot = repoRoot();
    Optional<String> sizing = reserveLiteral(repoRoot.resolve(SIZING));
    Optional<String> render = reserveLiteral(repoRoot.resolve(RENDER));

    assertThat(sizing)
        .as("ARCHIMATE_LABEL_ICON_RESERVE not found in " + SIZING + " (renamed or removed?)")
        .isPresent();
    assertThat(render)
        .as("ARCHIMATE_LABEL_ICON_RESERVE not found in " + RENDER + " (renamed or removed?)")
        .isPresent();
    assertThat(render.get())
        .as(
            "ARCHIMATE_LABEL_ICON_RESERVE must be identical in the generic-graph sizer and the "
                + "render plugin; they reserve and consume the same per-side label/icon room")
        .isEqualTo(sizing.get());
  }

  private static Optional<String> reserveLiteral(Path file) throws IOException {
    Matcher matcher = RESERVE.matcher(Files.readString(file, StandardCharsets.UTF_8));
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

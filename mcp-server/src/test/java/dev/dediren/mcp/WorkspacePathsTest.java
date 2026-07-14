package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTest {

  @Test
  void resolvesRelativePathInsideRoot(@TempDir Path root) throws Exception {
    Path source = Files.createFile(root.resolve("model.json"));

    Path resolved = WorkspacePaths.resolveExisting(root, "model.json");

    assertThat(resolved).isEqualTo(source.toRealPath());
  }

  @Test
  void resolvesAbsolutePathInsideRoot(@TempDir Path root) throws Exception {
    Path source = Files.createFile(root.resolve("model.json"));

    Path resolved = WorkspacePaths.resolveExisting(root, source.toString());

    assertThat(resolved).isEqualTo(source.toRealPath());
  }

  @Test
  void rejectsTraversalOutsideRoot(@TempDir Path root) {
    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "../../etc/passwd"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void rejectsSymlinkEscapingRoot(@TempDir Path root, @TempDir Path outside) throws Exception {
    Path secret = Files.writeString(outside.resolve("secret.json"), "{}");
    Path link = root.resolve("link.json");
    try {
      Files.createSymbolicLink(link, secret);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support; the traversal test still covers containment.
    }

    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "link.json"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveExistingRejectsMissingFile(@TempDir Path root) {
    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "absent.json"))
        .isInstanceOf(PathOutsideRootException.class);
  }

  @Test
  void resolveExistingRejectsAbsolutePathOutsideRoot(@TempDir Path root, @TempDir Path outside)
      throws Exception {
    Path secret = Files.writeString(outside.resolve("secret.json"), "{}");

    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, secret.toString()))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  // A mutant that swapped WorkspacePaths.confine's `target.startsWith(realRoot)` (component-wise)
  // for `target.toString().startsWith(realRoot.toString())` (a plain string prefix compare) leaves
  // this file's existing tests all green -- none of them plants a sibling directory whose *name*
  // has the root's name as a string prefix. "/rootspace-evil" does not start with the path
  // "/rootspace", but the string "/rootspace-evil" does start with the string "/rootspace", so the
  // mutant would let this escape through. See the class doc: symlink-in-root escapes are the
  // documented residual; a sibling-directory escape like this is not, and must stay caught.
  @Test
  void resolveExistingRejectsSiblingDirectoryWhoseNameIsAStringPrefixOfRoot(@TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("rootspace"));
    Path evilSibling = Files.createDirectory(tmp.resolve("rootspace-evil"));
    Files.writeString(evilSibling.resolve("secret.json"), "{}");

    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "../rootspace-evil/secret.json"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveForWriteAcceptsNonExistentPathInsideRoot(@TempDir Path root) throws Exception {
    Path resolved = WorkspacePaths.resolveForWrite(root, "out/nested/dir");

    assertThat(resolved).isEqualTo(root.toRealPath().resolve("out/nested/dir"));
    assertThat(resolved).doesNotExist();
  }

  @Test
  void resolveForWriteRejectsNonExistentPathOutsideRoot(@TempDir Path root) {
    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "../escape/out"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveForWriteRejectsAbsolutePathOutsideRoot(@TempDir Path root, @TempDir Path outside) {
    Path target = outside.resolve("out");

    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, target.toString()))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  // See resolveExistingRejectsSiblingDirectoryWhoseNameIsAStringPrefixOfRoot: same sibling-prefix
  // escape, driven through the write entry point instead of the existing-file entry point.
  @Test
  void resolveForWriteRejectsSiblingDirectoryWhoseNameIsAStringPrefixOfRoot(@TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("rootspace"));
    Files.createDirectory(tmp.resolve("rootspace-evil"));

    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "../rootspace-evil/out"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveForWriteRejectsWriteThroughEscapingSymlinkDirectory(
      @TempDir Path root, @TempDir Path outside) throws Exception {
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return;
    }

    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "link/out"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }
}

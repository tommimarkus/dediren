package dev.dediren.mcp;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Confines every model-supplied path to the server's workspace root.
 *
 * <p>Normalization alone is not enough. A symlink <em>inside</em> the root pointing outside it is
 * exactly the interesting case, and only real-path resolution catches it — so both entry points
 * resolve symlinks before the containment check. For a path that need not exist yet (an output
 * directory), the nearest existing ancestor is real-path-resolved instead, and the remaining
 * segments are appended to it.
 *
 * <p>Known and accepted residual: resolve-then-open is not atomic, so a local attacker who can
 * create symlinks inside the root during the window can defeat this. That grants them nothing they
 * did not already have — see docs/threat-model.md.
 */
public final class WorkspacePaths {
  private WorkspacePaths() {}

  /** Resolves a path that must already exist, confined to {@code root}. */
  public static Path resolveExisting(Path root, String candidate) throws PathOutsideRootException {
    Path realRoot = realRoot(root);
    Path resolved = realRoot.resolve(candidate).normalize();
    Path real;
    try {
      real = resolved.toRealPath();
    } catch (IOException error) {
      throw new PathOutsideRootException(
          candidate, "cannot be resolved (" + error.getMessage() + ")");
    }
    return confine(realRoot, real, candidate);
  }

  /** Resolves a path that need not exist yet (an output directory), confined to {@code root}. */
  public static Path resolveForWrite(Path root, String candidate) throws PathOutsideRootException {
    Path realRoot = realRoot(root);
    Path resolved = realRoot.resolve(candidate).normalize();

    Path existing = resolved;
    while (existing != null && !existing.toFile().exists()) {
      existing = existing.getParent();
    }
    if (existing == null) {
      throw new PathOutsideRootException(candidate, "has no existing ancestor to anchor");
    }
    Path realExisting;
    try {
      realExisting = existing.toRealPath();
    } catch (IOException error) {
      throw new PathOutsideRootException(
          candidate, "cannot be resolved (" + error.getMessage() + ")");
    }
    confine(realRoot, realExisting, candidate);

    Path remainder = existing.relativize(resolved);
    Path target = realExisting.resolve(remainder).normalize();
    return confine(realRoot, target, candidate);
  }

  private static Path realRoot(Path root) throws PathOutsideRootException {
    try {
      return root.toRealPath();
    } catch (IOException error) {
      throw new PathOutsideRootException(
          root.toString(), "workspace root cannot be resolved (" + error.getMessage() + ")");
    }
  }

  private static Path confine(Path realRoot, Path target, String candidate)
      throws PathOutsideRootException {
    if (!target.startsWith(realRoot)) {
      throw new PathOutsideRootException(candidate, "resolves to " + target);
    }
    return target;
  }
}

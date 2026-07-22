package dev.dediren.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The one implementation of root-confined path resolution, shared by the MCP adapter (tool
 * arguments) and core's source-fragment loading (model-supplied fragment paths). It used to exist
 * twice, kept aligned by comments; a confinement algorithm is exactly the code that must not fork.
 *
 * <p>Normalization alone is not enough. A symlink <em>inside</em> the root pointing outside it is
 * the interesting case, and only real-path resolution catches it — so both entry points resolve
 * symlinks before the containment check. For a target that need not exist yet, the nearest existing
 * ancestor is real-path-resolved instead, and the remaining segments are appended to it.
 *
 * <p>The ancestor walk deliberately does not follow symlinks: with {@code NOFOLLOW_LINKS} a
 * dangling symlink is itself the nearest existing ancestor and gets real-path-resolved like any
 * other, instead of being stepped past and re-checked only as text.
 *
 * <p>Critically, callers hand in the <em>unnormalized</em> resolved path. Calling {@link
 * Path#normalize()} before the walk would lexically collapse a {@code link/..} sequence with no
 * regard for what {@code link} actually is on disk, discarding the symlink before the walk (or the
 * eventual physical I/O) ever sees it. {@link Path#toRealPath} resolves symlinks physically,
 * component-by-component — exactly like the OS call the actual read or write performs — so leaving
 * the path unnormalized until it is anchored on a real, existing ancestor keeps the confinement
 * decision and the physical I/O looking at the same target.
 *
 * <p>Known and accepted residual: resolve-then-open is not atomic, so a local attacker who can
 * create symlinks inside the root during the window can defeat this. That grants them nothing they
 * did not already have — see docs/threat-model.md.
 */
public final class ConfinedPaths {
  private ConfinedPaths() {}

  /**
   * A candidate that failed confinement. The message names the reason (and may include a resolved
   * absolute path) — suitable for a debugging channel, never for a model-facing envelope; callers
   * on a trust boundary sanitize.
   */
  public static final class PathEscapeException extends Exception {
    public PathEscapeException(String reason) {
      super(reason);
    }
  }

  /** Resolves a target that must already exist, confined to {@code root}. */
  public static Path resolveExisting(Path root, Path unresolved) throws PathEscapeException {
    Path realRoot = realRoot(root);
    Path real;
    try {
      real = unresolved.toRealPath();
    } catch (IOException error) {
      throw new PathEscapeException("cannot be resolved (" + error.getMessage() + ")");
    }
    return confine(realRoot, real);
  }

  /**
   * Resolves a target that need not exist yet (an output path, or a read path whose absence the
   * reader reports), confined to {@code root} via the nearest-existing-ancestor anchor.
   */
  public static Path resolveAnchored(Path root, Path unresolved) throws PathEscapeException {
    Path realRoot = realRoot(root);
    Path existing = unresolved;
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (existing == null) {
      throw new PathEscapeException("has no existing ancestor to anchor");
    }
    Path realExisting;
    try {
      realExisting = existing.toRealPath();
    } catch (IOException error) {
      throw new PathEscapeException("cannot be resolved (" + error.getMessage() + ")");
    }
    confine(realRoot, realExisting);
    Path target = realExisting.resolve(existing.relativize(unresolved)).normalize();
    return confine(realRoot, target);
  }

  private static Path realRoot(Path root) throws PathEscapeException {
    try {
      return root.toRealPath();
    } catch (IOException error) {
      throw new PathEscapeException(
          "workspace root cannot be resolved (" + error.getMessage() + ")");
    }
  }

  private static Path confine(Path realRoot, Path target) throws PathEscapeException {
    if (!target.startsWith(realRoot)) {
      throw new PathEscapeException("resolves to " + target);
    }
    return target;
  }
}

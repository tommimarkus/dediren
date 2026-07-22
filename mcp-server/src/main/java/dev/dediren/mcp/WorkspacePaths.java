package dev.dediren.mcp;

import dev.dediren.core.io.ConfinedPaths;
import java.nio.file.Path;

/**
 * Confines every model-supplied path to the server's workspace root. The algorithm — real-path
 * resolution, the no-follow ancestor walk, and the unnormalized-until-anchored rule — is owned by
 * core's {@link ConfinedPaths} (also used for source-fragment confinement); this adapter only maps
 * failures onto the MCP lane's sanitized {@link PathOutsideRootException}.
 */
public final class WorkspacePaths {
  private WorkspacePaths() {}

  /** Resolves a path that must already exist, confined to {@code root}. */
  public static Path resolveExisting(Path root, String candidate) throws PathOutsideRootException {
    try {
      return ConfinedPaths.resolveExisting(root, root.resolve(candidate));
    } catch (ConfinedPaths.PathEscapeException escape) {
      throw new PathOutsideRootException(candidate, escape.getMessage());
    }
  }

  /** Resolves a path that need not exist yet (an output directory), confined to {@code root}. */
  public static Path resolveForWrite(Path root, String candidate) throws PathOutsideRootException {
    try {
      return ConfinedPaths.resolveAnchored(root, root.resolve(candidate));
    } catch (ConfinedPaths.PathEscapeException escape) {
      throw new PathOutsideRootException(candidate, escape.getMessage());
    }
  }
}

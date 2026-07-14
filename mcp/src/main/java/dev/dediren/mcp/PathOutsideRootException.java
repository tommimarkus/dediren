package dev.dediren.mcp;

/**
 * A tool argument named a path that does not resolve inside the server's workspace root.
 *
 * <p>This is the model-facing control, not a defence against a hostile local user: the server runs
 * with the spawning user's authority, and MCP clients frequently auto-approve tool calls, so an
 * unconfined {@code out} would let a model write anywhere the user can.
 */
public final class PathOutsideRootException extends Exception {
  private static final long serialVersionUID = 1L;

  private final String candidate;

  public PathOutsideRootException(String candidate, String reason) {
    super("path '" + candidate + "' is outside the workspace root: " + reason);
    this.candidate = candidate;
  }

  public String candidate() {
    return candidate;
  }
}

package dev.dediren.mcp;

/**
 * A tool argument named a path that does not resolve inside the server's workspace root.
 *
 * <p>This is the model-facing control, not a defence against a hostile local user: the server runs
 * with the spawning user's authority, and MCP clients frequently auto-approve tool calls, so an
 * unconfined {@code out} would let a model write anywhere the user can.
 *
 * <p>{@link #getMessage()} is deliberately generic and carries only the model's own candidate
 * string back. It never carries the resolved absolute target and never distinguishes "resolves
 * outside the root" from "cannot be resolved at all" (for example a missing file) -- either would
 * let a hostile model use the tool-call error envelope to fingerprint the host filesystem: to learn
 * the canonical absolute workspace root, or to probe for the existence of paths outside it. The
 * resolved detail -- the absolute target, or the underlying {@link java.io.IOException} text -- is
 * available separately via {@link #detail()} strictly for the stderr debug channel; it must never
 * reach the MCP tool result.
 */
public final class PathOutsideRootException extends Exception {
  private static final long serialVersionUID = 1L;

  private final String candidate;
  private final String detail;

  public PathOutsideRootException(String candidate, String detail) {
    super("path '" + candidate + "' is outside the workspace root");
    this.candidate = candidate;
    this.detail = detail;
  }

  public String candidate() {
    return candidate;
  }

  /**
   * The resolved absolute target or the underlying I/O failure text. Stderr-only; see class doc.
   */
  public String detail() {
    return detail;
  }
}

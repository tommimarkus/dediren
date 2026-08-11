package dev.dediren.contracts.mcp;

/**
 * The public result of opening or resuming an MCP-managed build workspace.
 *
 * @param mcpWorkspaceSchemaVersion the workspace contract version
 * @param workspaceId the canonical UUIDv4 handle supplied to later MCP operations
 * @param workspacePath the server-root-relative directory containing this workspace's artifacts
 * @param expiresAt the RFC 3339 instant at which the handle expires without another accepted use
 */
public record McpWorkspace(
    String mcpWorkspaceSchemaVersion, String workspaceId, String workspacePath, String expiresAt) {}

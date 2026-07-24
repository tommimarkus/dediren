package dev.dediren.core.pkg;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A request to build a whole package in one call.
 *
 * @param packageText the package document (schema {@code package.schema.v1})
 * @param baseDir the directory every package-relative path — model sources, policies, declared
 *     outputs — resolves against; the package file's own directory
 * @param confinementRoot the boundary those resolved paths must stay within: the package directory
 *     on the CLI lane, the server workspace root under MCP (never {@code null} — the CLI passes
 *     {@code baseDir})
 * @param env the environment allowlist forwarded to export engines
 * @param views an optional subset of view ids to build; empty means every view
 * @param noExport when true, the export lanes are suppressed
 */
public record PackageBuildRequest(
    String packageText,
    Path baseDir,
    Path confinementRoot,
    Map<String, String> env,
    List<String> views,
    boolean noExport) {
  public PackageBuildRequest {
    env = env == null ? Map.of() : Map.copyOf(env);
    views = listOrEmpty(views);
  }
}

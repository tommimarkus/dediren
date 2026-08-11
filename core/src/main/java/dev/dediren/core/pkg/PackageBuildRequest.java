package dev.dediren.core.pkg;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A request to build a whole package in one call.
 *
 * @param packageText the package document (schema {@code package.schema.v1})
 * @param inputBaseDir the package directory against which model and policy paths resolve
 * @param inputConfinementRoot the boundary package inputs must stay within
 * @param outputBaseDir the directory against which declared output paths resolve
 * @param outputConfinementRoot the boundary package outputs must stay within
 * @param env the environment allowlist forwarded to export engines
 * @param views an optional subset of view ids to build; empty means every view
 * @param noExport when true, the export lanes are suppressed
 */
public record PackageBuildRequest(
    String packageText,
    Path inputBaseDir,
    Path inputConfinementRoot,
    Path outputBaseDir,
    Path outputConfinementRoot,
    Map<String, String> env,
    List<String> views,
    boolean noExport) {
  public PackageBuildRequest {
    env = env == null ? Map.of() : Map.copyOf(env);
    // List.copyOf inline (not the ContractCollections helper) so SpotBugs models the copy as
    // immutable and does not flag EI_EXPOSE_REP — this request is core-internal, not a wire record.
    views = views == null ? List.of() : List.copyOf(views);
  }

  /** CLI-compatible form: package inputs and outputs share the package directory and boundary. */
  public PackageBuildRequest(
      String packageText,
      Path baseDir,
      Path confinementRoot,
      Map<String, String> env,
      List<String> views,
      boolean noExport) {
    this(packageText, baseDir, confinementRoot, baseDir, confinementRoot, env, views, noExport);
  }
}

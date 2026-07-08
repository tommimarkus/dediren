package dev.dediren.contracts;

/**
 * Compile-time owner of the published {@code DEDIREN_*} diagnostic vocabulary. Covers every code
 * emitted by {@code core} and {@code cli}; export-engine-internal codes (in the {@code archimate}
 * /{@code uml} and export-engine modules) are the remaining family to migrate. This enum owns
 * diagnostic codes only, not {@code DEDIREN_*} environment-variable names (for example {@code
 * DEDIREN_OEF_SCHEMA_DIR}), which are a separate vocabulary. The {@link #code()} string is the wire
 * contract and must never change for an existing constant.
 */
public enum DiagnosticCode {
  // In-memory engine registry (core: EngineDispatch). The two PLUGIN_* constants keep the wire
  // strings published by the retired process runtime: an engine id bound to no capability, and an
  // id bound only under another capability. The twelve process-taxonomy codes (timeout, process
  // failure, I/O error, manifest, executable, id-mismatch, capability-probe, output-validation)
  // were retired with the plugin process boundary.
  PLUGIN_UNKNOWN("DEDIREN_PLUGIN_UNKNOWN"),
  PLUGIN_UNSUPPORTED_CAPABILITY("DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY"),

  // Emitted only for an unexpected in-memory engine exception; the successor of the retired
  // process-crash category.
  ENGINE_FAILED("DEDIREN_ENGINE_FAILED"),

  // Command input (core: CoreCommands; cli: Main).
  COMMAND_INPUT_INVALID("DEDIREN_COMMAND_INPUT_INVALID"),
  VALIDATE_PROFILE_REQUIRED("DEDIREN_VALIDATE_PROFILE_REQUIRED"),
  VALIDATE_PLUGIN_REQUIRED("DEDIREN_VALIDATE_PLUGIN_REQUIRED"),

  // Source validation (core: SourceValidator).
  SCHEMA_INVALID("DEDIREN_SCHEMA_INVALID"),
  DUPLICATE_ID("DEDIREN_DUPLICATE_ID"),
  DANGLING_ENDPOINT("DEDIREN_DANGLING_ENDPOINT"),
  FRAGMENT_BASE_DIR_REQUIRED("DEDIREN_FRAGMENT_BASE_DIR_REQUIRED"),
  FRAGMENT_PATH_UNSUPPORTED("DEDIREN_FRAGMENT_PATH_UNSUPPORTED"),
  FRAGMENT_READ_FAILED("DEDIREN_FRAGMENT_READ_FAILED"),
  FRAGMENT_NESTED_UNSUPPORTED("DEDIREN_FRAGMENT_NESTED_UNSUPPORTED"),
  FRAGMENT_CONFLICT("DEDIREN_FRAGMENT_CONFLICT"),

  // Layout quality (core: LayoutQuality).
  LAYOUT_ROUTE_POINTS_EMPTY("DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY"),
  LAYOUT_ROUTE_POINTS_INSUFFICIENT("DEDIREN_LAYOUT_ROUTE_POINTS_INSUFFICIENT"),
  LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER"),
  LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE"),
  LAYOUT_NON_FINITE_GEOMETRY("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"),
  LAYOUT_SELF_LOOP_DEGENERATE("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE"),
  LAYOUT_QUALITY_WARNING("DEDIREN_LAYOUT_QUALITY_WARNING"),

  // Export schema-validator availability (export plugins; owned here for cross-module reuse).
  OEF_SCHEMA_VALIDATOR_UNAVAILABLE("DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE"),
  XMI_SCHEMA_VALIDATOR_UNAVAILABLE("DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE");

  private final String code;

  DiagnosticCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}

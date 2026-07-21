package dev.dediren.contracts;

/**
 * Compile-time owner of the published {@code DEDIREN_*} diagnostic vocabulary. Covers every code
 * emitted by every module that can depend on {@code contracts} — {@code core}, {@code cli}, the
 * engines, the semantics front ends, and the {@code uml} notation core. The one exception is {@code
 * archimate}, which is deliberately standalone (§2 allows it no internal dependencies) and
 * therefore owns its five {@code DEDIREN_ARCHIMATE_*} codes locally: three from the {@code
 * ArchimateTypeValidationException} switch, plus two constructor-site literals in {@code
 * Archimate}. This enum owns diagnostic codes only, not {@code DEDIREN_*} environment-variable
 * names (for example {@code DEDIREN_OEF_SCHEMA_DIR}), which are a separate vocabulary. The {@link
 * #code()} string is the wire contract and must never change for an existing constant.
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
  // A project --target value outside the accepted set (core: CoreCommands).
  COMMAND_TARGET_UNSUPPORTED("DEDIREN_COMMAND_TARGET_UNSUPPORTED"),
  // Command-owned I/O failure (cli: Main), e.g. a build artifact write hitting an unwritable or
  // colliding --out.
  COMMAND_IO_FAILED("DEDIREN_COMMAND_IO_FAILED"),
  // A misconfigured dediren.bundle.root / DEDIREN_BUNDLE_ROOT override, or a failed
  // working-directory walk-up, could not resolve the Dediren product root (core: DedirenPaths).
  PRODUCT_ROOT_UNRESOLVED("DEDIREN_PRODUCT_ROOT_UNRESOLVED"),
  VALIDATE_PROFILE_REQUIRED("DEDIREN_VALIDATE_PROFILE_REQUIRED"),
  VALIDATE_PLUGIN_REQUIRED("DEDIREN_VALIDATE_PLUGIN_REQUIRED"),

  // Source validation (core: SourceValidator).
  SCHEMA_INVALID("DEDIREN_SCHEMA_INVALID"),
  // Schema version gating (core: SchemaVersionGate) over every hand-authored surface: the source
  // model and the three policy files. OUTDATED means the registry recognizes the version as
  // superseded and docs/agent-usage.md carries the upgrade steps, so the message points there.
  // UNKNOWN means the version is absent, misspelled, or from a newer bundle than this one — there
  // is nothing useful to say beyond naming the version this build wants.
  SCHEMA_VERSION_OUTDATED("DEDIREN_SCHEMA_VERSION_OUTDATED"),
  SCHEMA_VERSION_UNKNOWN("DEDIREN_SCHEMA_VERSION_UNKNOWN"),
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

  // Sequence-diagram geometric invariants over the typed IR (core: CoreCommands, checks owned by
  // ir.quality.SequenceInvariants). Folded into the same hard-error lane as the LAYOUT_* codes
  // above: a violated invariant (e.g. a message endpoint off its lifeline axis) is an input error.
  LAYOUT_SEQUENCE_INVARIANT_VIOLATED("DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED"),

  // External schema-validator availability (XMI lane only since wave 3: the OEF lane validates
  // in-JVM and its former validator-unavailable code is retired with the xmllint subprocess).
  XMI_SCHEMA_VALIDATOR_UNAVAILABLE("DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE"),
  // Shared export-policy tripwire (both export engines): the policy still carries the shipped
  // fixture identity, so the artifact would introduce itself with placeholder identity. Warning,
  // not error — the export succeeds; the agent decides.
  EXPORT_IDENTITY_PLACEHOLDER("DEDIREN_EXPORT_IDENTITY_PLACEHOLDER"),

  // Artifact trust chain (core: ProvenanceCheck via `dediren verify`): a stamped artifact whose
  // provenance no longer matches the model's recomputed canonical hash (error — the CI drift
  // gate), and a recognized artifact carrying no stamp at all (warning; e.g. decomposed
  // single-stage outputs, which the build lane alone stamps).
  ARTIFACT_STALE("DEDIREN_ARTIFACT_STALE"),
  ARTIFACT_UNSTAMPED("DEDIREN_ARTIFACT_UNSTAMPED"),
  // ArchiMate OEF export engine.
  ARCHIMATE_GROUP_SOURCE_NOT_GROUPING("DEDIREN_ARCHIMATE_GROUP_SOURCE_NOT_GROUPING"),
  OEF_LAYOUT_REFERENCE_MISSING("DEDIREN_OEF_LAYOUT_REFERENCE_MISSING"),
  OEF_POLICY_INVALID("DEDIREN_OEF_POLICY_INVALID"),
  OEF_SCHEMA_INVALID("DEDIREN_OEF_SCHEMA_INVALID"),
  OEF_SCHEMA_UNAVAILABLE("DEDIREN_OEF_SCHEMA_UNAVAILABLE"),
  OEF_VIEWS_OMITTED("DEDIREN_OEF_VIEWS_OMITTED"),

  // ELK layout engine.
  ELK_DANGLING_EDGE("DEDIREN_ELK_DANGLING_EDGE"),
  ELK_EMPTY_GROUP("DEDIREN_ELK_EMPTY_GROUP"),
  ELK_INPUT_INVALID_JSON("DEDIREN_ELK_INPUT_INVALID_JSON"),
  ELK_LAYOUT_FAILED("DEDIREN_ELK_LAYOUT_FAILED"),
  ELK_MISSING_GROUP_MEMBER("DEDIREN_ELK_MISSING_GROUP_MEMBER"),

  // SVG render engine.
  RENDER_METADATA_PROFILE_MISMATCH("DEDIREN_RENDER_METADATA_PROFILE_MISMATCH"),
  RENDER_METADATA_PROFILE_REQUIRED("DEDIREN_RENDER_METADATA_PROFILE_REQUIRED"),
  RENDER_METADATA_REQUIRED("DEDIREN_RENDER_METADATA_REQUIRED"),
  SVG_POLICY_INVALID("DEDIREN_SVG_POLICY_INVALID"),
  UML_COMBINED_FRAGMENT_METADATA_INVALID("DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID"),
  UML_INTERACTION_OPERAND_METADATA_INVALID("DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID"),
  UML_MESSAGE_METADATA_INVALID("DEDIREN_UML_MESSAGE_METADATA_INVALID"),

  // UML/XMI export engine.
  UML_XMI_POLICY_INVALID("DEDIREN_UML_XMI_POLICY_INVALID"),
  UML_XMI_SEQUENCE_FRAGMENT_OPERATOR_UNSUPPORTED(
      "DEDIREN_UML_XMI_SEQUENCE_FRAGMENT_OPERATOR_UNSUPPORTED"),
  UML_XMI_SEQUENCE_MESSAGE_ENDPOINT_UNSUPPORTED(
      "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_ENDPOINT_UNSUPPORTED"),
  UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING(
      "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING"),
  UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED(
      "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED"),
  UML_XMI_SEQUENCE_NODE_UNSUPPORTED("DEDIREN_UML_XMI_SEQUENCE_NODE_UNSUPPORTED"),
  XMI_ELEMENTS_OMITTED("DEDIREN_XMI_ELEMENTS_OMITTED"),
  XMI_ID_INVALID("DEDIREN_XMI_ID_INVALID"),
  XMI_RELATIONSHIPS_OMITTED("DEDIREN_XMI_RELATIONSHIPS_OMITTED"),
  XMI_SCHEMA_INVALID("DEDIREN_XMI_SCHEMA_INVALID"),
  XMI_SCHEMA_UNAVAILABLE("DEDIREN_XMI_SCHEMA_UNAVAILABLE"),
  XMI_XML_INVALID("DEDIREN_XMI_XML_INVALID"),

  // generic-graph semantics (profile routing, view integrity). The two structural codes carry the
  // failures that historically went raw (stderr + empty stdout): a source without the
  // plugins.generic-graph object, and a view id no view in that object declares.
  GENERIC_GRAPH_DUPLICATE_GROUP_ID("DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID"),
  GENERIC_GRAPH_DUPLICATE_VIEW_ID("DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID"),
  GENERIC_GRAPH_PLUGIN_REQUIRED("DEDIREN_GENERIC_GRAPH_PLUGIN_REQUIRED"),
  GENERIC_GRAPH_VIEW_UNKNOWN("DEDIREN_GENERIC_GRAPH_VIEW_UNKNOWN"),
  GENERIC_GRAPH_RELATIONSHIP_ENDPOINT_OUTSIDE_VIEW(
      "DEDIREN_GENERIC_GRAPH_RELATIONSHIP_ENDPOINT_OUTSIDE_VIEW"),
  SEMANTIC_PROFILE_REQUIRED("DEDIREN_SEMANTIC_PROFILE_REQUIRED"),

  // MCP stdio server: a model-supplied path that resolves outside the server's workspace root.
  // Emitted by mcp WorkspacePaths for a path-shaped tool argument (source/out/policies) and by core
  // SourceValidator for a source-document fragment path, when a confinement root is in force.
  MCP_PATH_OUTSIDE_ROOT("DEDIREN_MCP_PATH_OUTSIDE_ROOT"),
  SEMANTIC_PROFILE_UNSUPPORTED("DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED"),

  // UML notation core.
  UML_ELEMENT_PROPERTY_UNSUPPORTED("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED"),
  UML_ELEMENT_TYPE_UNSUPPORTED("DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED"),
  UML_MULTIPLICITY_INVALID("DEDIREN_UML_MULTIPLICITY_INVALID"),
  UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED("DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED"),
  UML_RELATIONSHIP_PROPERTY_INVALID("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID"),
  UML_RELATIONSHIP_TYPE_UNSUPPORTED("DEDIREN_UML_RELATIONSHIP_TYPE_UNSUPPORTED"),
  UML_VIEW_KIND_UNSUPPORTED_ELEMENT("DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT");

  private final String code;

  DiagnosticCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}

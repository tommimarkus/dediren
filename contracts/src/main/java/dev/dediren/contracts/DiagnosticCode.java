package dev.dediren.contracts;

/**
 * Compile-time owner of the published {@code DEDIREN_*} diagnostic vocabulary. Seeded with the codes
 * that currently have explicit test ownership; add constants here as each emitting family migrates
 * off raw string literals. The {@link #code()} string is the wire contract and must never change for
 * an existing constant.
 */
public enum DiagnosticCode {
    PLUGIN_TIMEOUT("DEDIREN_PLUGIN_TIMEOUT"),
    PLUGIN_PROCESS_FAILED("DEDIREN_PLUGIN_PROCESS_FAILED"),
    PLUGIN_IO_ERROR("DEDIREN_PLUGIN_IO_ERROR"),
    PLUGIN_UNKNOWN("DEDIREN_PLUGIN_UNKNOWN"),
    PLUGIN_MANIFEST_INVALID("DEDIREN_PLUGIN_MANIFEST_INVALID"),
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

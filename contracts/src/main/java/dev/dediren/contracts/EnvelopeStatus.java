package dev.dediren.contracts;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Owner of the command-envelope {@code status} vocabulary. {@link #OK} and {@link #ERROR} are the
 * statuses {@code core} and {@code cli} produce today; {@link #WARNING} is a reserved member of the
 * published envelope contract (a plugin may emit it) that the first-party pipeline does not yet
 * produce. The {@link #wire()} string is the contract and must never change for an existing
 * constant.
 */
public enum EnvelopeStatus {
    OK("ok"),
    WARNING("warning"),
    ERROR("error");

    private final String wire;

    EnvelopeStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}

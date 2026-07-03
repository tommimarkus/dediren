package dev.dediren.contracts;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Owner of the command-envelope {@code status} vocabulary. {@link #OK} and {@link #ERROR} are the
 * baseline success/failure statuses {@code core} and {@code cli} produce; {@link #WARNING} is
 * produced by {@code validate-layout} when a layout carries a non-informational quality issue (and
 * a plugin may emit it too) — a non-failing result whose envelope still restates the payload
 * verdict. The {@link #wire()} string is the contract and must never change for an existing
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

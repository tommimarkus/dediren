package dev.dediren.contracts;

/**
 * Compile-time owner of the CLI process exit-code vocabulary. These are the intentional codes the
 * product returns; they are part of the observable command contract an agent reads alongside the
 * stdout envelope. (A picocli fallback of {@code 1} can still occur for an unexpected internal
 * error, but it is not part of this vocabulary.) The {@link #code()} integer is the wire contract
 * and must never change for an existing constant.
 */
public enum CommandExitCode {
    /** Command succeeded; stdout carries an {@code ok} envelope. */
    OK(0),
    /** Command input or validation failure; stdout carries an {@code error} envelope. */
    INPUT_ERROR(2),
    /** Plugin execution failed or produced invalid output; stdout carries an {@code error} envelope. */
    PLUGIN_ERROR(3);

    private final int code;

    CommandExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}

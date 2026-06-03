package dev.dediren.core.plugins;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;

public final class PluginExecutionException extends Exception {
    private final String code;
    private final String diagnosticPath;

    private PluginExecutionException(String code, String diagnosticPath, String message) {
        super(message);
        this.code = code;
        this.diagnosticPath = diagnosticPath;
    }

    public static PluginExecutionException plugin(String code, String pluginId, String message) {
        return new PluginExecutionException(code, "plugin:" + pluginId, message);
    }

    public static PluginExecutionException command(String code, String command, String message) {
        return new PluginExecutionException(code, "command:" + command, message);
    }

    public Diagnostic diagnostic() {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, getMessage(), diagnosticPath);
    }
}

package dev.dediren.core.commands;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.io.JsonInput;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRegistry;
import dev.dediren.core.plugins.PluginRunOptions;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.plugins.PluginRunner;
import dev.dediren.core.quality.LayoutQuality;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CoreCommands {
    private CoreCommands() {
    }

    public static PluginRunOutcome layoutCommand(String plugin, String inputText) throws PluginExecutionException {
        return layoutCommand(plugin, inputText, System.getenv());
    }

    public static PluginRunOutcome layoutCommand(
            String plugin,
            String inputText,
            Map<String, String> env) throws PluginExecutionException {
        return layoutCommand(new LayoutCommandInput(plugin, inputText, PluginRegistry.bundled(), env));
    }

    public static PluginRunOutcome layoutCommand(LayoutCommandInput input) throws PluginExecutionException {
        LayoutRequest request = parseCommandData("layout", input.inputText(), LayoutRequest.class);
        return PluginRunner.runForCapabilityWithRegistry(
                input.registry(),
                input.plugin(),
                "layout",
                List.of("layout"),
                toJson("layout", request),
                PluginRunOptions.defaults().withCandidateEnv(input.env()));
    }

    public static PluginRunOutcome projectCommand(
            String plugin,
            String target,
            String view,
            String inputText,
            Path baseDir) throws PluginExecutionException {
        return projectCommand(plugin, target, view, inputText, baseDir, System.getenv());
    }

    public static PluginRunOutcome projectCommand(
            String plugin,
            String target,
            String view,
            String inputText,
            Path baseDir,
            Map<String, String> env) throws PluginExecutionException {
        SourceDocument source;
        try {
            source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
        } catch (SourceValidator.SourceDiagnosticsException error) {
            return errorOutcome(error.diagnostics());
        }
        return PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.bundled(),
                plugin,
                "projection",
                List.of("project", "--target", target, "--view", view),
                toJson("project", source),
                PluginRunOptions.defaults().withCandidateEnv(env));
    }

    public static PluginRunOutcome semanticValidateCommand(
            String plugin,
            String profile,
            String inputText) throws PluginExecutionException {
        return semanticValidateCommand(plugin, profile, inputText, null);
    }

    public static PluginRunOutcome semanticValidateCommand(
            String plugin,
            String profile,
            String inputText,
            Path baseDir) throws PluginExecutionException {
        return semanticValidateCommand(plugin, profile, inputText, baseDir, System.getenv());
    }

    public static PluginRunOutcome semanticValidateCommand(
            String plugin,
            String profile,
            String inputText,
            Path baseDir,
            Map<String, String> env) throws PluginExecutionException {
        SourceDocument source;
        try {
            source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
        } catch (SourceValidator.SourceDiagnosticsException error) {
            return errorOutcome(error.diagnostics());
        }
        return PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.bundled(),
                plugin,
                "semantic-validation",
                List.of("validate", "--profile", profile),
                toJson("validate", source),
                PluginRunOptions.defaults().withCandidateEnv(env));
    }

    public static ValidationResult validateLayoutCommand(String inputText) {
        try {
            LayoutResult result = JsonInput.parseCommandData(inputText, LayoutResult.class);
            List<Diagnostic> diagnostics = LayoutQuality.validateLayoutDiagnostics(result);
            if (!diagnostics.isEmpty()) {
                return new ValidationResult(2, CommandEnvelope.error(diagnostics));
            }
            return new ValidationResult(0, CommandEnvelope.ok(JsonSupport.objectMapper()
                    .valueToTree(LayoutQuality.validateLayout(result))));
        } catch (RuntimeException | IOException error) {
            return commandInputValidationResult("validate-layout", error);
        }
    }

    public static PluginRunOutcome renderCommand(
            String plugin,
            String policyText,
            String metadataText,
            String layoutText) throws PluginExecutionException {
        return renderCommand(plugin, policyText, metadataText, layoutText, System.getenv());
    }

    public static PluginRunOutcome renderCommand(
            String plugin,
            String policyText,
            String metadataText,
            String layoutText,
            Map<String, String> env) throws PluginExecutionException {
        LayoutResult layoutResult = parseCommandData("render", layoutText, LayoutResult.class);
        JsonNode policy = parseJson("render", policyText);
        RenderMetadata metadata = metadataText == null
                ? null
                : parseCommandData("render", metadataText, RenderMetadata.class);

        var input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", JsonSupport.objectMapper().valueToTree(layoutResult));
        input.set("policy", policy);
        if (metadata != null) {
            input.set("render_metadata", JsonSupport.objectMapper().valueToTree(metadata));
        }
        return PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.bundled(),
                plugin,
                "render",
                List.of("render"),
                toJson("render", input),
                PluginRunOptions.defaults().withCandidateEnv(env));
    }

    public static PluginRunOutcome exportCommand(
            String plugin,
            String policyText,
            String sourceText,
            Path sourceBaseDir,
            String layoutText) throws PluginExecutionException {
        return exportCommand(plugin, policyText, sourceText, sourceBaseDir, layoutText, System.getenv());
    }

    public static PluginRunOutcome exportCommand(
            String plugin,
            String policyText,
            String sourceText,
            Path sourceBaseDir,
            String layoutText,
            Map<String, String> env) throws PluginExecutionException {
        SourceDocument source;
        try {
            source = SourceValidator.loadAndValidateSourceDocument(sourceText, sourceBaseDir);
        } catch (SourceValidator.SourceDiagnosticsException error) {
            return errorOutcome(error.diagnostics());
        }
        LayoutResult layoutResult = parseCommandData("export", layoutText, LayoutResult.class);
        JsonNode policy = parseJson("export", policyText);
        var input = new ExportRequest(
                ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
                source,
                layoutResult,
                policy);
        return PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.bundled(),
                plugin,
                "export",
                List.of("export"),
                toJson("export", input),
                PluginRunOptions.defaults().withCandidateEnv(env));
    }

    static PluginRunOutcome errorOutcome(List<Diagnostic> diagnostics) {
        try {
            return new PluginRunOutcome(JsonSupport.objectMapper()
                    .writeValueAsString(CommandEnvelope.error(diagnostics)), 2);
        } catch (IOException error) {
            throw new IllegalStateException("error envelope should serialize", error);
        }
    }

    private static <T> T parseCommandData(String command, String text, Class<T> type)
            throws PluginExecutionException {
        try {
            return JsonInput.parseCommandData(text, type);
        } catch (RuntimeException | IOException error) {
            throw commandInputInvalid(command, error);
        }
    }

    private static JsonNode parseJson(String command, String text) throws PluginExecutionException {
        try {
            return JsonSupport.objectMapper().readTree(text);
        } catch (IOException error) {
            throw commandInputInvalid(command, error);
        }
    }

    private static String toJson(String command, Object value) throws PluginExecutionException {
        try {
            return JsonSupport.objectMapper().writeValueAsString(value);
        } catch (IOException error) {
            throw commandInputInvalid(command, error);
        }
    }

    private static PluginExecutionException commandInputInvalid(String command, Exception error) {
        return PluginExecutionException.command(
                "DEDIREN_COMMAND_INPUT_INVALID",
                command,
                error.getMessage());
    }

    private static ValidationResult commandInputValidationResult(String command, Exception error) {
        var diagnostic = PluginExecutionException.command(
                "DEDIREN_COMMAND_INPUT_INVALID",
                command,
                error.getMessage()).diagnostic();
        return new ValidationResult(2, CommandEnvelope.error(List.of(diagnostic)));
    }

}

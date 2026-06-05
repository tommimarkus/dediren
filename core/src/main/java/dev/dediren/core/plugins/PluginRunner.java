package dev.dediren.core.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PluginRunner {
    private PluginRunner() {
    }

    public static PluginRunOutcome runForCapabilityWithRegistry(
            PluginRegistry registry,
            String pluginId,
            String requiredCapability,
            List<String> args,
            String input,
            PluginRunOptions options) throws PluginExecutionException {
        LoadedPluginManifest loaded = registry.loadManifest(pluginId);
        boolean capabilitiesCommand = !args.isEmpty() && "capabilities".equals(args.getFirst());
        if (!capabilitiesCommand && !loaded.manifest().capabilities().contains(requiredCapability)) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY",
                    pluginId,
                    "plugin " + pluginId + " does not support capability " + requiredCapability);
        }

        Path executable = executablePath(loaded, options);
        if (!Files.exists(executable)) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_MISSING_EXECUTABLE",
                    pluginId,
                    "plugin " + pluginId + " executable does not exist: " + executable);
        }

        Map<String, String> allowedEnv = allowedEnv(options, loaded);
        ProcessOutput capabilities = runExecutable(pluginId, executable, List.of("capabilities"), "", options.timeout(), allowedEnv);
        RuntimeCapabilities runtimeCapabilities = normalizeRuntimeCapabilities(pluginId, capabilities);
        if (!loaded.manifest().id().equals(runtimeCapabilities.id())) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_ID_MISMATCH",
                    pluginId,
                    "plugin " + pluginId + " runtime id " + runtimeCapabilities.id() + " does not match manifest id");
        }
        if (!capabilitiesCommand && !runtimeCapabilities.capabilities().contains(requiredCapability)) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY",
                    pluginId,
                    "plugin " + pluginId + " does not support capability " + requiredCapability);
        }
        if (capabilitiesCommand) {
            return new PluginRunOutcome(capabilities.stdout(), 0);
        }

        ProcessOutput output = runExecutable(pluginId, executable, args, input, options.timeout(), allowedEnv);
        return normalizePluginOutput(pluginId, requiredCapability, args, output);
    }

    private static Map<String, String> allowedEnv(PluginRunOptions options, LoadedPluginManifest loaded) {
        var allowed = new java.util.LinkedHashMap<String, String>();
        for (String name : loaded.manifest().allowedEnv()) {
            String value = options.candidateEnv().get(name);
            if (value == null) {
                value = System.getenv(name);
            }
            if (value != null) {
                allowed.put(name, value);
            }
        }
        return allowed;
    }

    private static Path executablePath(LoadedPluginManifest loaded, PluginRunOptions options) {
        String envName = "DEDIREN_PLUGIN_" + loaded.manifest().id().toUpperCase().replace('-', '_');
        String override = options.candidateEnv().get(envName);
        if (override == null) {
            override = System.getenv(envName);
        }
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path executable = Path.of(loaded.manifest().executable());
        if (executable.isAbsolute()) {
            return executable;
        }
        Path manifestDir = loaded.path().getParent();
        if (executable.getNameCount() == 1
                && manifestDir.toAbsolutePath().normalize()
                        .equals(DedirenPaths.productRoot().resolve("plugins").toAbsolutePath().normalize())) {
            return DedirenPaths.productRoot().resolve("bin").resolve(executable).normalize();
        }
        return manifestDir.resolve(executable).normalize();
    }

    private static RuntimeCapabilities normalizeRuntimeCapabilities(String pluginId, ProcessOutput output)
            throws PluginExecutionException {
        if (output.exitCode() != 0) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED",
                    pluginId,
                    "plugin " + pluginId + " capability probe failed: " + output.stderr());
        }
        JsonNode value;
        try {
            value = JsonSupport.objectMapper().readTree(output.stdout());
        } catch (IOException error) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON",
                    pluginId,
                    "plugin " + pluginId + " capability output is not valid JSON: " + error.getMessage());
        }
        List<String> errors = validator().validate("schemas/runtime-capability.schema.json", value);
        if (!errors.isEmpty()) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_CAPABILITY_SCHEMA_INVALID",
                    pluginId,
                    "plugin " + pluginId + " capability output does not match the runtime schema: " + errors.getFirst());
        }
        try {
            return JsonSupport.objectMapper().treeToValue(value, RuntimeCapabilities.class);
        } catch (IOException error) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_CAPABILITY_SCHEMA_INVALID",
                    pluginId,
                    error.getMessage());
        }
    }

    private static PluginRunOutcome normalizePluginOutput(
            String pluginId,
            String requiredCapability,
            List<String> args,
            ProcessOutput output) throws PluginExecutionException {
        JsonNode envelope = commandEnvelope(pluginId, output.stdout());
        String status = envelope.path("status").asText("error");
        if ("error".equals(status)) {
            return new PluginRunOutcome(output.stdout(), output.exitCode() == 0 ? 3 : output.exitCode());
        }
        if (output.exitCode() == 0) {
            validateSuccessData(pluginId, requiredCapability, args, envelope);
            return new PluginRunOutcome(output.stdout(), 0);
        }
        throw PluginExecutionException.plugin(
                "DEDIREN_PLUGIN_PROCESS_FAILED",
                pluginId,
                "plugin " + pluginId + " exited with status " + output.exitCode() + ": " + output.stderr());
    }

    private static JsonNode commandEnvelope(String pluginId, String stdout) throws PluginExecutionException {
        JsonNode value;
        try {
            value = JsonSupport.objectMapper().readTree(stdout);
        } catch (IOException error) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_OUTPUT_INVALID_JSON",
                    pluginId,
                    "plugin " + pluginId + " stdout is not valid JSON: " + error.getMessage());
        }
        List<String> errors = validator().validate("schemas/envelope.schema.json", value);
        if (!errors.isEmpty()) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE",
                    pluginId,
                    "plugin " + pluginId + " stdout does not match the command envelope schema: " + errors.getFirst());
        }
        return value;
    }

    private static void validateSuccessData(
            String pluginId,
            String requiredCapability,
            List<String> args,
            JsonNode envelope) throws PluginExecutionException {
        String schema = capabilityResultSchema(requiredCapability, args);
        if (schema == null) {
            return;
        }
        JsonNode data = envelope.get("data");
        if (data == null) {
            throw outputInvalidData(pluginId, requiredCapability, "successful envelope does not contain data");
        }
        List<String> errors = validator().validate(schema, data);
        if (!errors.isEmpty()) {
            throw outputInvalidData(pluginId, requiredCapability, errors.getFirst());
        }
    }

    private static PluginExecutionException outputInvalidData(String pluginId, String capability, String message) {
        return PluginExecutionException.plugin(
                "DEDIREN_PLUGIN_OUTPUT_INVALID_DATA",
                pluginId,
                "plugin " + pluginId + " successful " + capability
                        + " output data does not match the capability schema: " + message);
    }

    private static String capabilityResultSchema(String capability, List<String> args) {
        return switch (capability) {
            case "layout" -> "schemas/layout-result.schema.json";
            case "render" -> "schemas/render-result.schema.json";
            case "export" -> "schemas/export-result.schema.json";
            case "semantic-validation" -> "schemas/semantic-validation-result.schema.json";
            case "projection" -> args.contains("--target") && args.indexOf("--target") + 1 < args.size()
                    && "render-metadata".equals(args.get(args.indexOf("--target") + 1))
                    ? "schemas/render-metadata.schema.json"
                    : "schemas/layout-request.schema.json";
            default -> null;
        };
    }

    private static ProcessOutput runExecutable(
            String pluginId,
            Path executable,
            List<String> args,
            String input,
            Duration timeout,
            Map<String, String> env) throws PluginExecutionException {
        try {
            var command = new java.util.ArrayList<String>();
            command.add(executable.toString());
            command.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            builder.environment().putAll(env);
            Process process = builder.start();
            CompletableFuture<Void> stdin = CompletableFuture.runAsync(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write(input.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Broken pipes are acceptable when a plugin exits before reading stdin.
                }
            });
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));

            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw timeout(pluginId, timeout);
            }
            stdin.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return new ProcessOutput(
                    stdout.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS),
                    stderr.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS),
                    process.exitValue());
        } catch (PluginExecutionException error) {
            throw error;
        } catch (java.util.concurrent.TimeoutException error) {
            throw timeout(pluginId, timeout);
        } catch (Exception error) {
            throw PluginExecutionException.plugin(
                    "DEDIREN_PLUGIN_IO_ERROR",
                    pluginId,
                    "plugin " + pluginId + " I/O error: " + error.getMessage());
        }
    }

    private static String readAll(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static PluginExecutionException timeout(String pluginId, Duration timeout) {
        return PluginExecutionException.plugin(
                "DEDIREN_PLUGIN_TIMEOUT",
                pluginId,
                "plugin " + pluginId + " timed out after " + timeout.toMillis() + " ms");
    }

    private static SchemaValidator validator() {
        return SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot());
    }

    private record ProcessOutput(String stdout, String stderr, int exitCode) {
    }
}

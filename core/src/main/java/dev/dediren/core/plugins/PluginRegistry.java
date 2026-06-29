package dev.dediren.core.plugins;

import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.plugin.PluginManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PluginRegistry {
    private final List<Path> manifestDirs;
    private final Set<Path> trustedDirs;

    private PluginRegistry(List<Path> manifestDirs, Set<Path> trustedDirs) {
        this.manifestDirs = List.copyOf(manifestDirs);
        this.trustedDirs = trustedDirs.stream()
                .map(PluginRegistry::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static PluginRegistry fromDirs(List<Path> manifestDirs) {
        return new PluginRegistry(manifestDirs, Set.of());
    }

    public static PluginRegistry fromDirs(List<Path> manifestDirs, List<Path> trustedDirs) {
        return new PluginRegistry(manifestDirs, new LinkedHashSet<>(trustedDirs));
    }

    public static PluginRegistry bundled() {
        return bundled(System.getenv());
    }

    public static PluginRegistry bundled(Map<String, String> env) {
        Path root = DedirenPaths.productRoot();
        Path bundledPlugins = root.resolve("plugins");
        var dirs = new ArrayList<Path>();
        dirs.add(bundledPlugins);
        dirs.add(root.resolve(".dediren/plugins"));
        String configured = env == null ? null : env.get("DEDIREN_PLUGIN_DIRS");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEDIREN_PLUGIN_DIRS");
        }
        if (configured != null && !configured.isBlank()) {
            for (String part : configured.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    dirs.add(Path.of(part));
                }
            }
        }
        // Only the bundled first-party plugin directory is trusted. Project-local and
        // user-configured directories are untrusted, so a manifest discovered there can never
        // claim trust mode (DEDIREN_TRUST_MANIFEST_CAPABILITIES) to skip the runtime probe.
        return new PluginRegistry(dirs, Set.of(bundledPlugins));
    }

    LoadedPluginManifest loadManifest(String pluginId) throws PluginExecutionException {
        for (Path dir : manifestDirs) {
            Path path = dir.resolve(pluginId + ".manifest.json");
            if (!Files.exists(path)) {
                continue;
            }
            try {
                var value = JsonSupport.objectMapper().readTree(path.toFile());
                var errors = SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
                        .validate("schemas/plugin-manifest.schema.json", value);
                if (!errors.isEmpty()) {
                    throw PluginExecutionException.plugin(
                            "DEDIREN_PLUGIN_MANIFEST_INVALID",
                            pluginId,
                            "plugin manifest for " + pluginId + " is invalid: " + errors.getFirst());
                }
                PluginManifest manifest = JsonSupport.objectMapper().treeToValue(value, PluginManifest.class);
                if (!pluginId.equals(manifest.id())) {
                    throw PluginExecutionException.plugin(
                            "DEDIREN_PLUGIN_MANIFEST_INVALID",
                            pluginId,
                            "manifest id '" + manifest.id() + "' did not match requested id");
                }
                return new LoadedPluginManifest(manifest, path, trustedDirs.contains(normalize(dir)));
            } catch (IOException error) {
                throw PluginExecutionException.plugin(
                        "DEDIREN_PLUGIN_MANIFEST_INVALID",
                        pluginId,
                        "plugin manifest for " + pluginId + " is invalid: " + error.getMessage());
            }
        }
        throw PluginExecutionException.plugin(
                "DEDIREN_PLUGIN_UNKNOWN",
                pluginId,
                "unknown plugin id: " + pluginId);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}

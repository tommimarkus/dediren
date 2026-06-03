package dev.dediren.core.plugins;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.plugin.PluginManifest;
import dev.dediren.contracts.schema.SchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PluginRegistry {
    private final List<Path> manifestDirs;

    private PluginRegistry(List<Path> manifestDirs) {
        this.manifestDirs = List.copyOf(manifestDirs);
    }

    public static PluginRegistry fromDirs(List<Path> manifestDirs) {
        return new PluginRegistry(manifestDirs);
    }

    public static PluginRegistry bundled() {
        Path root = CorePaths.repositoryRoot();
        var dirs = new ArrayList<Path>();
        dirs.add(root.resolve("fixtures/plugins"));
        dirs.add(root.resolve(".dediren/plugins"));
        String configured = System.getenv("DEDIREN_PLUGIN_DIRS");
        if (configured != null && !configured.isBlank()) {
            for (String part : configured.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    dirs.add(Path.of(part));
                }
            }
        }
        return new PluginRegistry(dirs);
    }

    LoadedPluginManifest loadManifest(String pluginId) throws PluginExecutionException {
        for (Path dir : manifestDirs) {
            Path path = dir.resolve(pluginId + ".manifest.json");
            if (!Files.exists(path)) {
                continue;
            }
            try {
                var value = JsonSupport.objectMapper().readTree(path.toFile());
                var errors = SchemaValidator.fromRepositoryRoot(CorePaths.repositoryRoot())
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
                return new LoadedPluginManifest(manifest, path);
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
}

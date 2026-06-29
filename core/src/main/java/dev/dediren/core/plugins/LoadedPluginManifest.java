package dev.dediren.core.plugins;

import dev.dediren.contracts.plugin.PluginManifest;
import java.nio.file.Path;

record LoadedPluginManifest(PluginManifest manifest, Path path, boolean trusted) {
}

package dev.dediren.core.plugins;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.plugin.PluginManifest;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;

public final class PluginRegistry {
  private final List<Path> manifestDirs;
  private final Set<Path> trustedDirs;

  private PluginRegistry(List<Path> manifestDirs, Set<Path> trustedDirs) {
    this.manifestDirs = List.copyOf(manifestDirs);
    this.trustedDirs =
        trustedDirs.stream().map(PluginRegistry::normalize).collect(Collectors.toUnmodifiableSet());
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
    // Discovery order: bundled first-party plugins, then (opt-in) the caller-cwd project plugin
    // directory, then the bundle-root project plugin directory, then user-configured directories.
    // Discovery is always explicit; plugins are never discovered from PATH.
    var dirs = new LinkedHashSet<Path>();
    dirs.add(normalize(bundledPlugins));
    // Security gate (PB-1): the caller-cwd `.dediren/plugins` directory is consulted only when
    // DEDIREN_ALLOW_PROJECT_PLUGINS is truthy. `.dediren/plugins` resolves against the CLI
    // process's own working directory (the caller's project); plugin child processes run with
    // cwd = product root, so this must key off the CLI's cwd, not the child's. Running an
    // executable registered in an untrusted cloned repo's `.dediren/plugins` is arbitrary code
    // execution with the caller's privileges, so cwd project-plugin discovery is opt-in and off
    // by default. The gate keys off the absolute, normalized cwd; a relative user.dir is
    // unsupported. The bundle-root variant below is a separate, ungated later lookup for
    // manifests registered inside the bundle itself.
    if (projectPluginsAllowed(env)) {
      dirs.add(normalize(Path.of(System.getProperty("user.dir")).resolve(".dediren/plugins")));
    }
    dirs.add(normalize(root.resolve(".dediren/plugins")));
    String configured = env == null ? null : env.get("DEDIREN_PLUGIN_DIRS");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("DEDIREN_PLUGIN_DIRS");
    }
    if (configured != null && !configured.isBlank()) {
      for (String part : configured.split(java.io.File.pathSeparator)) {
        if (!part.isBlank()) {
          dirs.add(normalize(Path.of(part)));
        }
      }
    }
    // Only the bundled first-party plugin directory is trusted. Project-local and
    // user-configured directories are untrusted, so a manifest discovered there can never
    // claim trust mode (DEDIREN_TRUST_MANIFEST_CAPABILITIES) to skip the runtime probe.
    return new PluginRegistry(new ArrayList<>(dirs), Set.of(bundledPlugins));
  }

  /**
   * True when cwd project-plugin discovery is opted in via {@code DEDIREN_ALLOW_PROJECT_PLUGINS}
   * ({@code "1"} or {@code "true"}, case-insensitive). Read from the passed {@code env} first, then
   * the process environment, mirroring how {@code DEDIREN_PLUGIN_DIRS} is resolved above.
   */
  private static boolean projectPluginsAllowed(Map<String, String> env) {
    String value = env == null ? null : env.get("DEDIREN_ALLOW_PROJECT_PLUGINS");
    if (value == null || value.isBlank()) {
      value = System.getenv("DEDIREN_ALLOW_PROJECT_PLUGINS");
    }
    if (value == null) {
      return false;
    }
    String normalized = value.trim();
    return normalized.equals("1") || normalized.equalsIgnoreCase("true");
  }

  LoadedPluginManifest loadManifest(String pluginId) throws PluginExecutionException {
    for (Path dir : manifestDirs) {
      Path path = dir.resolve(pluginId + ".manifest.json");
      if (!Files.exists(path)) {
        continue;
      }
      try {
        var value = JsonSupport.objectMapper().readTree(path.toFile());
        var errors =
            SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
                .validate("schemas/plugin-manifest.schema.json", value);
        if (!errors.isEmpty()) {
          throw PluginExecutionException.plugin(
              DiagnosticCode.PLUGIN_MANIFEST_INVALID.code(),
              pluginId,
              "plugin manifest for " + pluginId + " is invalid: " + errors.getFirst());
        }
        PluginManifest manifest =
            JsonSupport.objectMapper().treeToValue(value, PluginManifest.class);
        if (!pluginId.equals(manifest.id())) {
          throw PluginExecutionException.plugin(
              DiagnosticCode.PLUGIN_MANIFEST_INVALID.code(),
              pluginId,
              "manifest id '" + manifest.id() + "' did not match requested id");
        }
        return new LoadedPluginManifest(manifest, path, trustedDirs.contains(normalize(dir)));
      } catch (JacksonException error) {
        throw PluginExecutionException.plugin(
            DiagnosticCode.PLUGIN_MANIFEST_INVALID.code(),
            pluginId,
            "plugin manifest for " + pluginId + " is invalid: " + error.getMessage());
      }
    }
    throw PluginExecutionException.plugin(
        DiagnosticCode.PLUGIN_UNKNOWN.code(), pluginId, "unknown plugin id: " + pluginId);
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }
}

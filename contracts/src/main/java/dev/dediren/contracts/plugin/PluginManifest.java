package dev.dediren.contracts.plugin;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record PluginManifest(
        String pluginManifestSchemaVersion,
        String id,
        String version,
        String executable,
        List<String> capabilities,
        List<String> allowedEnv) {
    public PluginManifest {
        capabilities = listOrEmpty(capabilities);
        allowedEnv = listOrEmpty(allowedEnv);
    }
}

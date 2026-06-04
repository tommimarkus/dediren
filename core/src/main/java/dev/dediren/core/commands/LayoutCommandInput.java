package dev.dediren.core.commands;

import dev.dediren.core.plugins.PluginRegistry;
import java.util.Map;

public record LayoutCommandInput(
        String plugin,
        String inputText,
        PluginRegistry registry,
        Map<String, String> env) {
    public LayoutCommandInput {
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}

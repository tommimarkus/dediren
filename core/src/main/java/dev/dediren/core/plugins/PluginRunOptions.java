package dev.dediren.core.plugins;

import java.time.Duration;
import java.util.Map;

public record PluginRunOptions(Duration timeout, Map<String, String> candidateEnv) {
  public PluginRunOptions {
    timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    candidateEnv = candidateEnv == null ? Map.of() : Map.copyOf(candidateEnv);
  }

  public static PluginRunOptions defaults() {
    return new PluginRunOptions(Duration.ofSeconds(10), Map.of());
  }

  public PluginRunOptions withCandidateEnv(Map<String, String> env) {
    return new PluginRunOptions(timeout, env);
  }

  public PluginRunOptions withTimeout(Duration timeout) {
    return new PluginRunOptions(timeout, candidateEnv);
  }
}

package dev.dediren.engine;

import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.SceneGraph;

/** Backend-neutral graph layout: an ELK-backed engine is the first-party implementation. */
public interface LayoutEngine {
  /** Stable engine id, for example {@code "elk-layout"}. */
  String id();

  /**
   * Converts request bytes to a typed {@link SceneGraph}, surfacing the engine's published
   * parse-failure envelope (for example {@code DEDIREN_ELK_INPUT_INVALID_JSON} / exit 3) as an
   * {@link EngineException}. The in-memory dispatch routes raw stdin through this entry point so
   * the layout stage reproduces the plugin-native parse behavior byte-for-byte rather than core's
   * generic input diagnostic.
   */
  SceneGraph parseRequest(byte[] input) throws EngineException;

  EngineResult<LaidOutScene> layout(SceneGraph scene) throws EngineException;
}

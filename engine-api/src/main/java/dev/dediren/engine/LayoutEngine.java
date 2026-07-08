package dev.dediren.engine;

import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;

/** Backend-neutral graph layout: an ELK-backed engine is the first-party implementation. */
public interface LayoutEngine {
  /** Stable engine id, for example {@code "elk-layout"}. */
  String id();

  EngineResult<LayoutResult> layout(LayoutRequest request) throws EngineException;
}

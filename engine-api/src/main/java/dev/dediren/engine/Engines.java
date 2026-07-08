package dev.dediren.engine;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable in-process registry of the engines available to a build. Lookups are by capability
 * (semantics/layout/render/export) and engine id; a missing id resolves to {@link Optional#empty()}
 * rather than throwing.
 */
public record Engines(
    Map<String, SemanticsEngine> semantics,
    Map<String, LayoutEngine> layouts,
    Map<String, RenderEngine> renderers,
    Map<String, ExportEngine> exporters) {

  public Engines {
    semantics = mapOrEmpty(semantics);
    layouts = mapOrEmpty(layouts);
    renderers = mapOrEmpty(renderers);
    exporters = mapOrEmpty(exporters);
  }

  /**
   * Builds a registry from engine instances, indexing each capability by {@code id()}. Rejects a
   * duplicate id within the same capability.
   */
  public static Engines of(
      List<? extends SemanticsEngine> semanticsEngines,
      List<? extends LayoutEngine> layoutEngines,
      List<? extends RenderEngine> renderEngines,
      List<? extends ExportEngine> exportEngines) {
    return new Engines(
        indexById(semanticsEngines, SemanticsEngine::id),
        indexById(layoutEngines, LayoutEngine::id),
        indexById(renderEngines, RenderEngine::id),
        indexById(exportEngines, ExportEngine::id));
  }

  public Optional<SemanticsEngine> semanticsEngine(String id) {
    return Optional.ofNullable(semantics.get(id));
  }

  public Optional<LayoutEngine> layoutEngine(String id) {
    return Optional.ofNullable(layouts.get(id));
  }

  public Optional<RenderEngine> renderEngine(String id) {
    return Optional.ofNullable(renderers.get(id));
  }

  public Optional<ExportEngine> exportEngine(String id) {
    return Optional.ofNullable(exporters.get(id));
  }

  private static <T> Map<String, T> indexById(List<? extends T> engines, Function<T, String> id) {
    Map<String, T> index = new LinkedHashMap<>();
    for (T engine : engines == null ? List.<T>of() : engines) {
      String engineId = id.apply(engine);
      if (index.putIfAbsent(engineId, engine) != null) {
        throw new IllegalArgumentException("duplicate engine id: " + engineId);
      }
    }
    return Map.copyOf(index);
  }
}

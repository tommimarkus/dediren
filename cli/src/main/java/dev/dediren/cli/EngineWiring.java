package dev.dediren.cli;

import dev.dediren.engine.Engines;
import dev.dediren.plugins.archimateoef.OefExportEngine;
import dev.dediren.plugins.elklayout.ElkEngine;
import dev.dediren.plugins.genericgraph.GenericGraphEngine;
import dev.dediren.plugins.render.SvgRenderEngine;
import dev.dediren.plugins.umlxmi.XmiExportEngine;
import java.util.List;

/**
 * The single, explicit binding of the five bundled first-party engines into an {@link Engines}
 * registry (decision 3). This is the only cli class permitted to reference {@code
 * dev.dediren.plugins..} implementations — every other cli class knows engines only through the
 * {@code engine-api} interfaces, a boundary the dist-tool {@code ArchitectureRulesTest} enforces. A
 * hardcoded list of in-tree engines needs no lookup indirection and gives ArchUnit a single named
 * class to confine the cli to engine-implementation edge to.
 */
public final class EngineWiring {
  private EngineWiring() {}

  public static Engines defaults() {
    return Engines.of(
        List.of(new GenericGraphEngine()),
        List.of(new ElkEngine()),
        List.of(new SvgRenderEngine()),
        List.of(new OefExportEngine(), new XmiExportEngine()));
  }
}

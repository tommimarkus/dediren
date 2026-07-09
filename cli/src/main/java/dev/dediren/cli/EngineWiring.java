package dev.dediren.cli;

import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.engine.Engines;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.plugins.archimateoef.OefExportEngine;
import dev.dediren.plugins.elklayout.ElkEngine;
import dev.dediren.plugins.render.SvgRenderEngine;
import dev.dediren.plugins.umlxmi.XmiExportEngine;
import dev.dediren.semantics.archimate.ArchimateNotationSemantics;
import dev.dediren.semantics.graph.GraphNotationSemantics;
import dev.dediren.semantics.graph.SemanticsRouterEngine;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import java.util.List;
import java.util.Map;

/**
 * The single, explicit binding of the five bundled first-party engines into an {@link Engines}
 * registry (decision 3). The semantics slot is the profile router ({@link SemanticsRouterEngine})
 * over the per-notation {@link NotationSemantics} implementations, rather than the monolithic
 * generic-graph engine, but it still publishes engine id {@code "generic-graph"}. This is the only
 * cli class permitted to reference {@code dev.dediren.plugins..} and {@code
 * dev.dediren.semantics..} implementations — every other cli class knows engines only through the
 * {@code engine-api} interfaces, a boundary the dist-tool {@code ArchitectureRulesTest} enforces. A
 * hardcoded list of in-tree engines needs no lookup indirection and gives ArchUnit a single named
 * class to confine the cli to engine-implementation edge to.
 */
public final class EngineWiring {
  private EngineWiring() {}

  public static Engines defaults() {
    Map<GenericGraphSemanticProfile, NotationSemantics> notations =
        Map.of(
            GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics(),
            GenericGraphSemanticProfile.ARCHIMATE, new ArchimateNotationSemantics(),
            GenericGraphSemanticProfile.UML, new UmlNotationSemantics());
    return Engines.of(
        List.of(new SemanticsRouterEngine(notations)),
        List.of(new ElkEngine()),
        List.of(new SvgRenderEngine()),
        List.of(new OefExportEngine(), new XmiExportEngine()));
  }
}

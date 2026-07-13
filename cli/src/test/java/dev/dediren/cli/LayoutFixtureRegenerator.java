package dev.dediren.cli;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.SceneGraph;
import dev.dediren.plugins.elklayout.ElkEngine;
import dev.dediren.semantics.archimate.ArchimateNotationSemantics;
import dev.dediren.semantics.graph.GraphNotationSemantics;
import dev.dediren.semantics.graph.SemanticsRouterEngine;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import dev.dediren.testsupport.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in real-engine regenerator for {@code fixtures/layout-result/*.json} (Plan B P2, Task 9).
 * Default runs are a no-op (hermetic by default, modelled on {@code
 * dev.dediren.plugins.elklayout.ElkLayoutRenderArtifacts}); set {@code
 * -Ddediren.regen-layout-fixtures=true} to actually regenerate.
 *
 * <p>A layout-result fixture is named by <em>view id</em>, not by source model: a single {@code
 * fixtures/source/valid-*.json} model can declare multiple {@code generic-graph} views (for example
 * {@code valid-uml-basic.json} declares {@code class-view}, {@code data-view}, and {@code
 * activity-view}, feeding {@code uml-basic.json}, {@code uml-data.json}, and {@code
 * uml-activity.json} respectively). {@link #MAPPINGS} is therefore an explicit (source fixture,
 * view id) -> target fixture table, not a derived 1:1 name match; see {@code
 * .superpowers/sdd/p2-task-9-report.md} for the full investigation, including two layout-result
 * fixtures that are intentionally left out of this table because no source view produces them:
 *
 * <ul>
 *   <li>{@code uml-sequence-validatable.json} — a hand-authored schema/quality oracle (minimal
 *       2-lifeline fixture asserting {@code role: lifeline} acceptance) that does not correspond to
 *       any declared view's node set. Reworked separately (Plan B P2 Task 10).
 * </ul>
 *
 * <p>Determinism precondition: reproducible geometry requires the bundled Liberation Sans font and
 * the pinned ELK version (see memory: visual-defect-test-suite's real-font hermeticity fix). Run
 * only through {@code scripts/regen-layout-fixtures.sh}, which documents this precondition.
 */
class LayoutFixtureRegenerator {

  private record FixtureMapping(String fixtureName, String sourceFileName, String viewId) {}

  private static final List<FixtureMapping> MAPPINGS =
      List.of(
          new FixtureMapping("archimate-oef-basic.json", "valid-archimate-oef.json", "main"),
          new FixtureMapping("basic.json", "valid-basic.json", "main"),
          new FixtureMapping("pipeline-rich.json", "valid-pipeline-rich.json", "main"),
          new FixtureMapping("uml-basic.json", "valid-uml-basic.json", "class-view"),
          new FixtureMapping("uml-activity.json", "valid-uml-basic.json", "activity-view"),
          new FixtureMapping("uml-data.json", "valid-uml-basic.json", "data-view"),
          new FixtureMapping(
              "uml-complex-class.json", "valid-uml-complex.json", "complex-class-view"),
          new FixtureMapping(
              "uml-component-basic.json", "valid-uml-component-basic.json", "component-view"),
          new FixtureMapping(
              "uml-deployment-basic.json", "valid-uml-deployment-basic.json", "deployment-view"),
          new FixtureMapping(
              "uml-sequence-basic.json", "valid-uml-sequence-basic.json", "sequence-view"),
          new FixtureMapping(
              "uml-sequence-self-message.json",
              "valid-uml-sequence-self-message.json",
              "sequence-view"),
          new FixtureMapping(
              "uml-sequence-fragments.json",
              "valid-uml-sequence-fragments.json",
              "sequence-fragments-view"),
          new FixtureMapping(
              "uml-sequence-lifecycle.json", "valid-uml-sequence-lifecycle.json", "sequence-view"),
          new FixtureMapping(
              "uml-state-machine-basic.json",
              "valid-uml-state-machine-basic.json",
              "state-machine-view"),
          new FixtureMapping(
              "uml-use-case-basic.json", "valid-uml-use-case-basic.json", "use-case-view"));

  @Test
  void regenerateLayoutFixtures() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("dediren.regen-layout-fixtures"),
        "opt-in only; run via scripts/regen-layout-fixtures.sh"
            + " (-Ddediren.regen-layout-fixtures=true)");

    Path workspaceRoot = TestSupport.workspaceRoot();
    Path sourceDir = workspaceRoot.resolve("fixtures/source");
    Path layoutResultDir = workspaceRoot.resolve("fixtures/layout-result");

    SemanticsRouterEngine genericGraph =
        new SemanticsRouterEngine(
            Map.of(
                GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics(),
                GenericGraphSemanticProfile.ARCHIMATE, new ArchimateNotationSemantics(),
                GenericGraphSemanticProfile.UML, new UmlNotationSemantics()));
    ElkEngine elk = new ElkEngine();

    for (FixtureMapping mapping : MAPPINGS) {
      byte[] sourceBytes = Files.readAllBytes(sourceDir.resolve(mapping.sourceFileName()));
      SourceDocument source = genericGraph.parseSource(sourceBytes);
      SceneGraph scene = genericGraph.projectScene(source, mapping.viewId()).value();
      LaidOutScene laid = elk.layout(scene).value();
      LayoutResult result = LaidOutSceneMapper.toResult(laid);

      String json =
          JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result)
              + "\n";
      Files.writeString(
          layoutResultDir.resolve(mapping.fixtureName()), json, StandardCharsets.UTF_8);
    }
  }
}

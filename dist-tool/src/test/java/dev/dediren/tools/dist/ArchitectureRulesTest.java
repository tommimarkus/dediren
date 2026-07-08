package dev.dediren.tools.dist;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Whole-reactor enforcement of the compile-time dependency spine in docs/architecture-guidelines.md
 * §2 and the engine boundary in §5. ArchUnit is the compile-time wall that replaced the retired OS
 * process wall (§5 "Historical note"): the rules below pin every edge in the §2 allowed-edge table,
 * including the pairwise engine independence and the named SVG/ELK and exporter/SVG examples §2
 * calls out. dist-tool is the only non-profile module whose (transitive) test classpath sees every
 * product module, so these rules are asserted here in one place. Runs as a plain JUnit test against
 * core ArchUnit (no archunit-junit5 engine) for compatibility with this JUnit 6 build.
 */
class ArchitectureRulesTest {

  private static final String CONTRACTS = "dev.dediren.contracts..";
  private static final String ENGINE_API = "dev.dediren.engine..";
  private static final String CORE = "dev.dediren.core..";
  private static final String CLI = "dev.dediren.cli..";
  private static final String PLUGINS = "dev.dediren.plugins..";

  // The five first-party engines, keyed by their retained dev.dediren.plugins.* package names
  // (§12 debt: the package rename did not follow the engines/ directory move).
  private static final String RENDER = "dev.dediren.plugins.render..";
  private static final String ELK_LAYOUT = "dev.dediren.plugins.elklayout..";
  private static final String GENERIC_GRAPH = "dev.dediren.plugins.genericgraph..";
  private static final String ARCHIMATE_OEF = "dev.dediren.plugins.archimateoef..";
  private static final String UML_XMI = "dev.dediren.plugins.umlxmi..";

  private static final String ELK = "org.eclipse.elk..";

  /** Production classes across the reactor; test classes are excluded on purpose. */
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.dediren..");

  @Test
  void reactorProductionClassesWereImported() {
    // Guards every rule below: an empty import would make each ArchRule pass vacuously. Assert
    // we actually loaded classes from every package a rule below constrains, including each
    // individual engine package (not just the coarse "dev.dediren.plugins" aggregate) so a
    // misspelled engine package name cannot hide behind a positive count from a sibling engine.
    int coreClasses = 0;
    int engineApiClasses = 0;
    int renderClasses = 0;
    int elkLayoutClasses = 0;
    int genericGraphClasses = 0;
    int archimateOefClasses = 0;
    int umlXmiClasses = 0;
    for (JavaClass javaClass : PRODUCTION_CLASSES) {
      String packageName = javaClass.getPackageName();
      if (packageName.startsWith("dev.dediren.core")) {
        coreClasses++;
      } else if (packageName.startsWith("dev.dediren.engine")) {
        engineApiClasses++;
      } else if (packageName.startsWith("dev.dediren.plugins.render")) {
        renderClasses++;
      } else if (packageName.startsWith("dev.dediren.plugins.elklayout")) {
        elkLayoutClasses++;
      } else if (packageName.startsWith("dev.dediren.plugins.genericgraph")) {
        genericGraphClasses++;
      } else if (packageName.startsWith("dev.dediren.plugins.archimateoef")) {
        archimateOefClasses++;
      } else if (packageName.startsWith("dev.dediren.plugins.umlxmi")) {
        umlXmiClasses++;
      }
    }
    assertThat(coreClasses).as("core production classes on the classpath").isPositive();
    assertThat(engineApiClasses).as("engine-api production classes on the classpath").isPositive();
    assertThat(renderClasses).as("render engine production classes on the classpath").isPositive();
    assertThat(elkLayoutClasses)
        .as("elk-layout engine production classes on the classpath")
        .isPositive();
    assertThat(genericGraphClasses)
        .as("generic-graph engine production classes on the classpath")
        .isPositive();
    assertThat(archimateOefClasses)
        .as("archimate-oef-export engine production classes on the classpath")
        .isPositive();
    assertThat(umlXmiClasses)
        .as("uml-xmi-export engine production classes on the classpath")
        .isPositive();
  }

  @Test
  void internalPackagesAreAcyclic() {
    slices()
        .matching("dev.dediren.(*)..")
        .should()
        .beFreeOfCycles()
        .because("the internal dependency graph must remain a DAG rooted at contracts (§2, ADP)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void contractsDependsOnNothingInternal() {
    noClasses()
        .that()
        .resideInAPackage(CONTRACTS)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            CORE,
            CLI,
            PLUGINS,
            "dev.dediren.archimate..",
            "dev.dediren.uml..",
            "dev.dediren.schemacache..",
            "dev.dediren.tools..")
        .because(
            "contracts is the stable foundation and must not depend on any"
                + " other internal module (§2)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void engineApiDependsOnlyOnContracts() {
    noClasses()
        .that()
        .resideInAPackage(ENGINE_API)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            CORE,
            CLI,
            PLUGINS,
            "dev.dediren.archimate..",
            "dev.dediren.uml..",
            "dev.dediren.schemacache..",
            "dev.dediren.tools..")
        .because(
            "engine-api is the shared engine-facing interface surface and must depend"
                + " only on contracts, never core or any engine implementation (Task 2)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void enginesDoNotDependOnCore() {
    noClasses()
        .that()
        .resideInAPackage(PLUGINS)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(CORE)
        .because(
            "engines are leaf libraries behind engine-api, reachable only through the in-memory"
                + " dispatch; a compile edge to core would let an engine reach back into"
                + " orchestration it must not know about (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void coreDoesNotDependOnEngineImplementations() {
    noClasses()
        .that()
        .resideInAPackage(CORE)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(PLUGINS)
        .because(
            "core dispatches engines only through the engine-api interfaces and contracts"
                + " records; it must never compile-depend on a concrete engine implementation"
                + " (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void enginesDoNotDependOnEachOther() {
    // The five engines are independent leaf libraries behind engine-api (§2, §5); shared code
    // between them flows only through contracts, engine-api, archimate, uml, or schema-cache —
    // never a direct engine-to-engine edge. Checked pairwise: for each engine, no class in its
    // package may depend on any of the other four engine packages.
    Map<String, String> enginePackages =
        Map.of(
            "render", RENDER,
            "elk-layout", ELK_LAYOUT,
            "generic-graph", GENERIC_GRAPH,
            "archimate-oef-export", ARCHIMATE_OEF,
            "uml-xmi-export", UML_XMI);
    for (Map.Entry<String, String> engine : enginePackages.entrySet()) {
      String[] otherEnginePackages =
          enginePackages.values().stream()
              .filter(enginePackage -> !enginePackage.equals(engine.getValue()))
              .toArray(String[]::new);
      noClasses()
          .that()
          .resideInAPackage(engine.getValue())
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(otherEnginePackages)
          .because(
              "engines are pairwise independent; the "
                  + engine.getKey()
                  + " engine must reach shared code only through contracts, engine-api,"
                  + " archimate, uml, or schema-cache, never another engine (§2, §5)")
          .check(PRODUCTION_CLASSES);
    }
  }

  @Test
  void svgEmitterDoesNotImportElk() {
    noClasses()
        .that()
        .resideInAPackage(RENDER)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ELK)
        .because(
            "the SVG emitter renders whatever layout it is handed; depending on ELK would let"
                + " layout geometry leak into render, duplicating elk-layout's sole concern"
                + " (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void exportersDoNotImportSvgEmitter() {
    noClasses()
        .that()
        .resideInAnyPackage(ARCHIMATE_OEF, UML_XMI)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(RENDER)
        .because(
            "the OEF and XMI export engines map source models to their export formats; SVG"
                + " styling is render's sole concern and must not leak into export mapping"
                + " (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void onlyEngineWiringTouchesEngineImplementations() {
    // The five engine deps are compile scope so EngineWiring can construct them for the
    // in-memory dispatch. That single named class is the only permitted cli-to-engine-
    // implementation edge; every other cli class must reach engines through the engine-api
    // interfaces (decision 3, §2, §5).
    noClasses()
        .that()
        .resideInAPackage(CLI)
        .and()
        .doNotHaveFullyQualifiedName("dev.dediren.cli.EngineWiring")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(PLUGINS)
        .because(
            "only EngineWiring wires the concrete first-party engines; the rest of cli knows"
                + " them through engine-api, confining the cli-to-engine-implementation edge to"
                + " one class (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }
}

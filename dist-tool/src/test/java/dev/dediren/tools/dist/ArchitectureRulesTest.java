package dev.dediren.tools.dist;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Whole-reactor enforcement of the compile-time dependency spine in docs/architecture-guidelines.md
 * §2 (the allowed edge table and the inward-only, acyclic rules). dist-tool is the only non-profile
 * module whose (transitive) test classpath sees every product module, so these rules are asserted
 * here in one place. Runs as a plain JUnit test against core ArchUnit (no archunit-junit5 engine)
 * for compatibility with this JUnit 6 build.
 */
class ArchitectureRulesTest {

  private static final String CONTRACTS = "dev.dediren.contracts..";
  private static final String ENGINE_API = "dev.dediren.engine..";
  private static final String CORE = "dev.dediren.core..";
  private static final String CLI = "dev.dediren.cli..";
  private static final String PLUGINS = "dev.dediren.plugins..";

  /** Production classes across the reactor; test classes are excluded on purpose. */
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.dediren..");

  @Test
  void reactorProductionClassesWereImported() {
    // Guards every rule below: an empty import would make each ArchRule pass
    // vacuously. Assert we actually loaded classes from the tiers under test.
    int coreClasses = 0;
    int pluginClasses = 0;
    int engineApiClasses = 0;
    for (JavaClass javaClass : PRODUCTION_CLASSES) {
      String packageName = javaClass.getPackageName();
      if (packageName.startsWith("dev.dediren.core")) {
        coreClasses++;
      } else if (packageName.startsWith("dev.dediren.plugins")) {
        pluginClasses++;
      } else if (packageName.startsWith("dev.dediren.engine")) {
        engineApiClasses++;
      }
    }
    assertThat(coreClasses).as("core production classes on the classpath").isPositive();
    assertThat(pluginClasses).as("plugin production classes on the classpath").isPositive();
    assertThat(engineApiClasses).as("engine-api production classes on the classpath").isPositive();
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
                + " only on contracts, never core or any plugin module (Task 2)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void pluginsDoNotDependOnCore() {
    noClasses()
        .that()
        .resideInAPackage(PLUGINS)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(CORE)
        .because(
            "plugins are reachable only across the process boundary; a compile"
                + " edge to core would collapse the microkernel split (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void coreDoesNotDependOnPlugins() {
    noClasses()
        .that()
        .resideInAPackage(CORE)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(PLUGINS)
        .because(
            "core discovers and runs plugins as subprocesses; it knows them"
                + " only through contracts (§2)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void cliDoesNotDependOnPlugins() {
    noClasses()
        .that()
        .resideInAPackage(CLI)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(PLUGINS)
        .because(
            "cli depends on core and contracts only; its plugin dependencies are"
                + " test-scope end-to-end coverage, never a compile edge (§2)")
        .check(PRODUCTION_CLASSES);
  }
}

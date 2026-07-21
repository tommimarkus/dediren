package dev.dediren.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.JsonNode;

// Mutates JVM-global state (user.dir / dediren.bundle.root system properties); must never run
// concurrently with other test classes if parallel execution is ever enabled.
@Isolated
class SourceValidatorTest {
  @TempDir Path temp;

  @Test
  void validateSourceUsesExplicitBundleRootOutsideCurrentWorkingDirectory() throws Exception {
    Path bundleRoot = temp.resolve("bundle-root");
    Path outsideBundle = temp.resolve("outside-bundle");
    Files.createDirectories(bundleRoot.resolve("schemas"));
    Files.createDirectories(outsideBundle);
    Files.writeString(bundleRoot.resolve("schemas/model.schema.json"), "{}");
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", outsideBundle.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      ValidationResult result =
          SourceValidator.validateSourceJson(
              """
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """,
              null);

      assertThat(result.exitCode()).isZero();
      JsonNode data = result.envelope().data();
      assertThat(data.path("node_count").asInt()).isZero();
      assertThat(data.path("relationship_count").asInt()).isZero();
      assertThat(data.path("model_schema_version").asText()).isEqualTo("model.schema.v1");
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void validateSourceFailsWhenBundleRootIsAbsentAndCwdHasNoSchema() throws Exception {
    Path outsideBundle = temp.resolve("outside-bundle-no-schema");
    Files.createDirectories(outsideBundle); // deliberately no schemas/ dir
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", outsideBundle.toString());
    System.clearProperty("dediren.bundle.root");
    try {
      // DedirenPaths.productRoot() throws IllegalStateException when neither
      // dediren.bundle.root nor a schemas/model.schema.json ancestor is found;
      // validateSourceJson does not catch it, so it propagates as the failure signal.
      assertThatThrownBy(
              () ->
                  SourceValidator.validateSourceJson(
                      """
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """,
                      null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("dediren.bundle.root");
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void validateSourceReportsEverySchemaViolationNotJustTheFirst() {
    // Two independent schema violations: node "n1" is missing required "label" and
    // node "n2" is missing required "properties". SchemaValidator must surface both,
    // not just the alphabetically-first violation message.
    ValidationResult result =
        SourceValidator.validateSourceJson(
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "n1", "type": "Type", "properties": {} },
                    { "id": "n2", "type": "Type", "label": "Two" }
                  ],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """,
            null);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    var diagnostics = result.envelope().diagnostics();
    assertThat(diagnostics.size()).isGreaterThanOrEqualTo(2);
    assertThat(diagnostics)
        .allSatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_SCHEMA_INVALID"));
    // The two violation messages must be distinct: this proves they are independent
    // sibling violations, not the same violation reported twice.
    assertThat(diagnostics.stream().map(Diagnostic::message).distinct().count())
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void aModelWithAnUnknownSchemaVersionSaysSoInsteadOfFailingGenericSchemaValidation() {
    ValidationResult result =
        SourceValidator.validateSourceJson(
            """
            {
              "model_schema_version": "model.schema.v0",
              "nodes": [],
              "relationships": [],
              "plugins": { "generic-graph": { "views": [] } }
            }
            """,
            null);

    assertThat(result.exitCode()).isNotZero();
    List<Diagnostic> diagnostics = result.envelope().diagnostics();
    assertThat(diagnostics).hasSize(1);
    assertThat(diagnostics.get(0).code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostics.get(0).message()).contains("model.schema.v1");
    assertThat(diagnostics.get(0).path()).isEqualTo("$.model_schema_version");
  }

  // --- Source-fragment confinement (the MCP trust boundary). The null confinement root is the
  // CLI/human lane and must stay byte-identical; a non-null root is the confined MCP lane. ---

  private static final String FRAGMENT_NODE =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
        ],
        "relationships": [],
        "plugins": { "generic-graph": { "views": [] } }
      }
      """;

  private static String modelWithFragment(String fragmentPath) {
    return """
        {
          "model_schema_version": "model.schema.v1",
          "fragments": ["%s"],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """
        .formatted(fragmentPath);
  }

  @Test
  void cliLaneResolvesADotDotFragmentUnchanged() throws Exception {
    // The human lane (null confinement root) must keep resolving a '..' traversal fragment exactly
    // as before -- a human legitimately references fragments across their own project. This guards
    // against accidentally confining the human lane.
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.createDirectories(temp.resolve("shared"));
    Files.writeString(temp.resolve("shared/piece.json"), FRAGMENT_NODE);

    ValidationResult result =
        SourceValidator.validateSourceJson(modelWithFragment("../shared/piece.json"), base);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().data().path("node_count").asInt()).isEqualTo(1);
  }

  @Test
  void confinedLaneRejectsADotDotFragmentThatEscapesRoot() throws Exception {
    Path base = Files.createDirectories(temp.resolve("base"));
    // The escaping target need not exist: confinement decides before the read, so there is no
    // exists-vs-not-exists oracle either way.
    ValidationResult result =
        SourceValidator.validateSourceJson(
            modelWithFragment("../escape.json"), base, /* confinementRoot= */ base);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path()).isEqualTo("$.fragments[0]");
    // Sanitized: only the model-supplied relative string, never the resolved absolute target.
    assertThat(diagnostic.message()).contains("../escape.json");
    assertThat(diagnostic.message()).doesNotContain(temp.toString());
    assertThat(diagnostic.message()).doesNotContain(temp.toRealPath().toString());
  }

  @Test
  void confinedLaneLoadsAFragmentInsideRoot() throws Exception {
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.writeString(base.resolve("piece.json"), FRAGMENT_NODE);

    ValidationResult result =
        SourceValidator.validateSourceJson(
            modelWithFragment("piece.json"), base, /* confinementRoot= */ base);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().data().path("node_count").asInt()).isEqualTo(1);
  }

  // PoC for the symlink-then-'..' escape: requireFragmentWithinRoot used to
  // baseDir.resolve(fragmentPath).normalize() the FULL path before ever touching the
  // filesystem. Path.normalize() is purely lexical -- it cancels "link/.." textually with no
  // regard for what "link" actually is on disk -- so it collapsed "link/../secret.json" down to
  // "secret.json" *before* the confinement walk ever saw the symlink. The confinement check then
  // approved a path that was never going to be read. Meanwhile the actual read
  // (Files.readString(baseDir.resolve(fragmentPath))) used the RAW, un-normalized path, which the
  // OS resolves physically: follow the "link" symlink first, THEN apply "..", landing outside
  // root. Confined "approve" + raw "read" were two independent resolutions of the same string
  // that disagreed -- exactly the divergence this test exists to close.
  @Test
  void confinedLaneRejectsASymlinkThenDotDotEscapeThroughAnOutOfRootSymlink(@TempDir Path outside)
      throws Exception {
    Path base = Files.createDirectories(temp.resolve("base"));
    // "link" points at outside/child; "link/.." therefore physically resolves to "outside" --
    // one level above the symlink target -- which is where the secret fragment lives. A fragment
    // literally named "secret.json" is deliberately never created directly under base: the
    // outside content must be readable ONLY by following the symlink then walking back up.
    Path outsideChild = Files.createDirectories(outside.resolve("child"));
    Files.writeString(outside.resolve("secret.json"), FRAGMENT_NODE);
    Path link = base.resolve("link");
    try {
      Files.createSymbolicLink(link, outsideChild);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support; other confinement tests still cover it.
    }

    ValidationResult result =
        SourceValidator.validateSourceJson(
            modelWithFragment("link/../secret.json"), base, /* confinementRoot= */ base);

    // Fail closed: the escape must be REJECTED with the same code a plain '..' escape gets, not
    // silently succeed (which would prove the outside fragment was read and merged) and not fall
    // through to a generic read failure either (which would prove the check approved the wrong,
    // never-read path instead of correctly identifying the escape).
    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path()).isEqualTo("$.fragments[0]");
    // Sanitized: only the model-supplied relative string, never the resolved absolute target or
    // an exists-vs-not-exists signal for the outside file.
    assertThat(diagnostic.message()).contains("link/../secret.json");
    assertThat(diagnostic.message()).doesNotContain(outside.toString());
    assertThat(diagnostic.message()).doesNotContain(outside.toRealPath().toString());
    // No leak: the outside fragment's node must never have been merged into the result.
    assertThat(result.envelope().data()).isNull();
  }

  // Guard against over-tightening: a symlink that stays entirely inside root (not a traversal
  // trick) must still resolve. Real-path confinement rejects symlinks that escape the root; it
  // must not reject symlinks that merely alias another location within it.
  @Test
  void confinedLaneLoadsAFragmentReachedThroughAnInRootSymlink() throws Exception {
    Path base = Files.createDirectories(temp.resolve("base"));
    Path real = Files.createDirectories(base.resolve("real"));
    Files.writeString(real.resolve("piece.json"), FRAGMENT_NODE);
    Path alias = base.resolve("alias");
    try {
      Files.createSymbolicLink(alias, real);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support; confinedLaneLoadsAFragmentInsideRoot still
      // covers the no-false-rejection guarantee for a plain in-root path.
    }

    ValidationResult result =
        SourceValidator.validateSourceJson(
            modelWithFragment("alias/piece.json"), base, /* confinementRoot= */ base);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().data().path("node_count").asInt()).isEqualTo(1);
  }

  // --- Fragment-merge CONFLICT / error partitions. Each triggers one published reject diagnostic
  // and asserts the specific code + JSON-pointer path the SUT documents for that branch. The CLI
  // (null confinement root) lane is used throughout: these guards are lane-independent. ---

  private static String modelWithTwoFragments() {
    return """
        {
          "model_schema_version": "model.schema.v1",
          "fragments": ["frag-a.json", "frag-b.json"],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """;
  }

  @Test
  void conflictingRequiredPluginVersionsBetweenFragmentsAreRejected() throws Exception {
    // Covers mergeRequiredPlugins: the same required-plugin id appears in two fragments with
    // different versions, so the second merge finds an existing id whose version differs.
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.writeString(
        base.resolve("frag-a.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "required_plugins": [ { "id": "elk-layout", "version": "1.0.0" } ],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """);
    Files.writeString(
        base.resolve("frag-b.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "required_plugins": [ { "id": "elk-layout", "version": "2.0.0" } ],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """);

    ValidationResult result = SourceValidator.validateSourceJson(modelWithTwoFragments(), base);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_FRAGMENT_CONFLICT");
    assertThat(diagnostic.path()).isEqualTo("$.required_plugins");
    assertThat(diagnostic.message()).contains("elk-layout").contains("1.0.0").contains("2.0.0");
  }

  @Test
  void conflictingScalarValuesForTheSamePluginKeyBetweenFragmentsAreRejected() throws Exception {
    // Covers mergeValue's leaf-conflict branch: two fragments set the same plugin-config scalar to
    // different values. A sibling key under "plugins" (additionalProperties:true) is deliberate --
    // "generic-graph" is additionalProperties:false and its only child "views" is an array that
    // concatenates rather than conflicts, so it can never reach the scalar-conflict branch.
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.writeString(
        base.resolve("frag-a.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [],
          "relationships": [],
          "plugins": { "custom": { "mode": "a" } }
        }
        """);
    Files.writeString(
        base.resolve("frag-b.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [],
          "relationships": [],
          "plugins": { "custom": { "mode": "b" } }
        }
        """);

    ValidationResult result = SourceValidator.validateSourceJson(modelWithTwoFragments(), base);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_FRAGMENT_CONFLICT");
    // Path is the fully-qualified pointer to the conflicting leaf: "$.plugins" + ".custom" +
    // ".mode".
    assertThat(diagnostic.path()).isEqualTo("$.plugins.custom.mode");
    assertThat(diagnostic.message()).contains("$.plugins.custom.mode");
  }

  @Test
  void aFragmentThatItselfDeclaresFragmentsIsRejected() throws Exception {
    // Covers the nested-fragment guard: a read fragment whose own document declares fragments. The
    // referenced "deeper.json" is never read -- the guard fires on the parsed fragment's own
    // fragments() list before any further resolution.
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.writeString(
        base.resolve("frag-nested.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "fragments": ["deeper.json"],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """);

    ValidationResult result =
        SourceValidator.validateSourceJson(modelWithFragment("frag-nested.json"), base);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_FRAGMENT_NESTED_UNSUPPORTED");
    assertThat(diagnostic.path()).isEqualTo("$.fragments[0]");
    assertThat(diagnostic.message()).contains("frag-nested.json").contains("nested fragments");
  }

  @Test
  void anAbsoluteFragmentPathIsRejected() throws Exception {
    // Covers the absolute-path guard (fragmentPath.isAbsolute()), which fires before any read and
    // regardless of confinement. The target need not exist -- rejection precedes resolution.
    Path base = Files.createDirectories(temp.resolve("base"));

    ValidationResult result =
        SourceValidator.validateSourceJson(modelWithFragment("/nonexistent/abs.json"), base);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_FRAGMENT_PATH_UNSUPPORTED");
    assertThat(diagnostic.path()).isEqualTo("$.fragments[0]");
    assertThat(diagnostic.message()).contains("/nonexistent/abs.json").contains("must be relative");
  }

  @Test
  void aFragmentedSourceGivenNoBaseDirIsRejected() {
    // Covers the base-dir guard: a source that declares fragments but is validated with a null
    // baseDir (no file input), so relative fragment paths cannot be resolved.
    ValidationResult result =
        SourceValidator.validateSourceJson(modelWithFragment("piece.json"), null);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_FRAGMENT_BASE_DIR_REQUIRED");
    assertThat(diagnostic.path()).isEqualTo("$.fragments");
    assertThat(diagnostic.message()).contains("source fragments require file input");
  }

  @Test
  void duplicateNodeIdsAreReportedAsADuplicateIdDiagnostic() {
    // Covers the DUPLICATE_ID branch: two nodes share an id, so the second insertion into the id
    // set fails. The JSON schema does not enforce id uniqueness, so this is the SUT's own check.
    ValidationResult result =
        SourceValidator.validateSourceJson(
            """
            {
              "model_schema_version": "model.schema.v1",
              "nodes": [
                { "id": "dup", "type": "T", "label": "One", "properties": {} },
                { "id": "dup", "type": "T", "label": "Two", "properties": {} }
              ],
              "relationships": [],
              "plugins": { "generic-graph": { "views": [] } }
            }
            """,
            null);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_DUPLICATE_ID");
    assertThat(diagnostic.path()).isEqualTo("$.nodes[?(@.id=='dup')]");
    assertThat(diagnostic.message()).contains("duplicate id 'dup'");
  }

  @Test
  void aRelationshipReferencingAMissingEndpointIsReportedAsDangling() {
    // Covers the DANGLING_ENDPOINT branch: a relationship whose target names a node id that no node
    // declares. Source "a" resolves, so the single diagnostic is the missing target, not source.
    ValidationResult result =
        SourceValidator.validateSourceJson(
            """
            {
              "model_schema_version": "model.schema.v1",
              "nodes": [
                { "id": "a", "type": "T", "label": "A", "properties": {} }
              ],
              "relationships": [
                { "id": "r1", "type": "R", "source": "a", "target": "ghost",
                  "label": "", "properties": {} }
              ],
              "plugins": { "generic-graph": { "views": [] } }
            }
            """,
            null);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    assertThat(result.envelope().diagnostics()).hasSize(1);
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_DANGLING_ENDPOINT");
    assertThat(diagnostic.path()).isEqualTo("$.relationships[?(@.id=='r1')].target");
    assertThat(diagnostic.message()).contains("r1").contains("ghost").contains("missing target");
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }
}

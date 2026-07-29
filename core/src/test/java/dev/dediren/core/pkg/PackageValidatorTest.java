package dev.dediren.core.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.pkg.PackageDocument;
import dev.dediren.contracts.pkg.PackageExport;
import dev.dediren.contracts.pkg.PackageExportLane;
import dev.dediren.contracts.pkg.PackageModel;
import dev.dediren.contracts.pkg.PackageOutputs;
import dev.dediren.contracts.pkg.PackagePresentation;
import dev.dediren.contracts.pkg.PackageView;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageValidatorTest {

  @Test
  void internallyConsistentPackageHasNoDiagnostics() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of(oefExport("e", "main", null, "generated/export/main.oef.xml")));
    assertThat(PackageValidator.validate(pkg)).isEmpty();
  }

  @Test
  void viewMayOmitModelWhenExactlyOneModelIsDeclared() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", null, "generated/svg/main.svg")),
            List.of());
    assertThat(PackageValidator.validate(pkg)).isEmpty();
  }

  @Test
  void viewOmittingModelWithSeveralModelsIsUnresolved() {
    PackageDocument pkg =
        pkg(
            List.of(
                new PackageModel("arch", "model.json"), new PackageModel("uml", "model-uml.json")),
            List.of(view("main", null, "generated/svg/main.svg")),
            List.of());
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_MODEL_UNRESOLVED");
  }

  @Test
  void viewReferencingUnknownModelIsRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "ghost", "generated/svg/main.svg")),
            List.of());
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_MODEL_UNKNOWN");
  }

  @Test
  void exportTargetingUnknownViewIsRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of(oefExport("e", "ghost", null, "generated/export/e.oef.xml")));
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_VIEW_UNKNOWN");
  }

  @Test
  void exportTargetingUnknownModelIsRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of(oefExport("e", null, "ghost", "generated/export/e.oef.xml")));
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_MODEL_UNKNOWN");
  }

  @Test
  void exportTargetingBothViewAndModelIsRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of(oefExport("e", "main", "arch", "generated/export/e.oef.xml")));
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_EXPORT_TARGET_INVALID");
  }

  @Test
  void exportTargetingNeitherViewNorModelIsRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of(oefExport("e", null, null, "generated/export/e.oef.xml")));
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_EXPORT_TARGET_INVALID");
  }

  @Test
  void duplicateModelIdsAreRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json"), new PackageModel("arch", "other.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of());
    List<Diagnostic> diagnostics = PackageValidator.validate(pkg);
    assertThat(diagnostics)
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_DUPLICATE_ID");
    assertThat(diagnostics.getFirst().message()).isEqualTo("models[] declares duplicate id 'arch'");
  }

  @Test
  void duplicateViewIdsAreRejected() {
    // Distinct output paths, so the only finding is the duplicate id itself.
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(
                view("main", "arch", "generated/svg/main-a.svg"),
                view("main", "arch", "generated/svg/main-b.svg")),
            List.of());
    List<Diagnostic> diagnostics = PackageValidator.validate(pkg);
    assertThat(diagnostics)
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_DUPLICATE_ID");
    assertThat(diagnostics.getFirst().message()).isEqualTo("views[] declares duplicate id 'main'");
  }

  @Test
  void duplicateExportIdsAreRejected() {
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/svg/main.svg")),
            List.of(
                oefExport("e", "main", null, "generated/export/a.oef.xml"),
                oefExport("e", "main", null, "generated/export/b.oef.xml")));
    List<Diagnostic> diagnostics = PackageValidator.validate(pkg);
    assertThat(diagnostics)
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_DUPLICATE_ID");
    assertThat(diagnostics.getFirst().message()).isEqualTo("exports[] declares duplicate id 'e'");
  }

  @Test
  void collidingDeclaredOutputPathsAreRejectedAfterNormalization() {
    // The export writes the same file the view's diagram already declares, once "./" is normalized.
    PackageDocument pkg =
        pkg(
            List.of(new PackageModel("arch", "model.json")),
            List.of(view("main", "arch", "generated/out.xml")),
            List.of(oefExport("e", "main", null, "generated/./out.xml")));
    assertThat(PackageValidator.validate(pkg))
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_PACKAGE_OUTPUT_COLLISION");
  }

  private static PackageDocument pkg(
      List<PackageModel> models, List<PackageView> views, List<PackageExport> exports) {
    return new PackageDocument("package.schema.v1", null, models, views, exports);
  }

  private static PackageView view(String id, String model, String diagram) {
    return new PackageView(
        id,
        model,
        "render-policy.json",
        new PackagePresentation("T", null, null),
        new PackageOutputs(diagram, null, null));
  }

  private static PackageExport oefExport(String id, String view, String model, String output) {
    return new PackageExport(
        id, view, model, PackageExportLane.ARCHIMATE_OEF, "export-policy.json", output);
  }
}

package dev.dediren.core.pkg;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.pkg.PackageDocument;
import dev.dediren.contracts.pkg.PackageExport;
import dev.dediren.contracts.pkg.PackageOutputs;
import dev.dediren.contracts.pkg.PackageView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-schema semantic validation of a package document — the cross-references and declared-output
 * topology JSON Schema cannot express. Pure and engine-free: it runs before {@code
 * PackageBuildCommand} touches any engine, so a mis-wired package fails fast with one diagnostic
 * list instead of a half-built output directory. Every diagnostic is an {@code ERROR}; an empty
 * list means the package is internally consistent.
 */
public final class PackageValidator {

  private PackageValidator() {}

  public static List<Diagnostic> validate(PackageDocument pkg) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    Set<String> modelIds = new LinkedHashSet<>();
    pkg.models().forEach(model -> modelIds.add(model.id()));
    Set<String> viewIds = new LinkedHashSet<>();
    pkg.views().forEach(view -> viewIds.add(view.id()));
    boolean singleModel = pkg.models().size() == 1;

    for (PackageView view : pkg.views()) {
      if (view.model() == null) {
        if (!singleModel) {
          diagnostics.add(
              error(
                  DiagnosticCode.PACKAGE_MODEL_UNRESOLVED,
                  "view '"
                      + view.id()
                      + "' must name a model; the package declares "
                      + pkg.models().size()
                      + " models"));
        }
      } else if (!modelIds.contains(view.model())) {
        diagnostics.add(
            error(
                DiagnosticCode.PACKAGE_MODEL_UNKNOWN,
                "view '" + view.id() + "' references unknown model '" + view.model() + "'"));
      }
    }

    for (PackageExport export : pkg.exports()) {
      boolean hasView = export.view() != null;
      boolean hasModel = export.model() != null;
      if (hasView == hasModel) {
        diagnostics.add(
            error(
                DiagnosticCode.PACKAGE_EXPORT_TARGET_INVALID,
                "export '" + export.id() + "' must target exactly one of a view or a model"));
      } else if (hasView && !viewIds.contains(export.view())) {
        diagnostics.add(
            error(
                DiagnosticCode.PACKAGE_VIEW_UNKNOWN,
                "export '" + export.id() + "' targets unknown view '" + export.view() + "'"));
      } else if (hasModel && !modelIds.contains(export.model())) {
        diagnostics.add(
            error(
                DiagnosticCode.PACKAGE_MODEL_UNKNOWN,
                "export '" + export.id() + "' targets unknown model '" + export.model() + "'"));
      }
    }

    // Declared output paths must be unique: two artifacts writing the same file would silently
    // overwrite. Keyed on the normalized path so "a/./b" and "a/b" collide.
    Map<String, String> owners = new LinkedHashMap<>();
    for (PackageView view : pkg.views()) {
      PackageOutputs outputs = view.outputs();
      if (outputs != null) {
        recordOutput(diagnostics, owners, outputs.diagram(), "view '" + view.id() + "' diagram");
        recordOutput(
            diagnostics,
            owners,
            outputs.renderMetadata(),
            "view '" + view.id() + "' render-metadata");
        recordOutput(diagnostics, owners, outputs.layout(), "view '" + view.id() + "' layout");
      }
    }
    for (PackageExport export : pkg.exports()) {
      recordOutput(diagnostics, owners, export.output(), "export '" + export.id() + "'");
    }

    return diagnostics;
  }

  private static void recordOutput(
      List<Diagnostic> diagnostics, Map<String, String> owners, String path, String owner) {
    if (path == null || path.isBlank()) {
      return;
    }
    String key = Path.of(path).normalize().toString();
    String existing = owners.putIfAbsent(key, owner);
    if (existing != null) {
      diagnostics.add(
          error(
              DiagnosticCode.PACKAGE_OUTPUT_COLLISION,
              "output path '" + path + "' is declared by both " + existing + " and " + owner));
    }
  }

  private static Diagnostic error(DiagnosticCode code, String message) {
    return new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, null);
  }
}

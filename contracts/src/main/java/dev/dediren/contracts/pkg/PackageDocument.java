package dev.dediren.contracts.pkg;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * A hand-authorable package: several views, across several models, each with its own render policy,
 * presentation, declared output paths, and view- or model-scoped exports. The top-level build input
 * above the source model (schema {@code package.schema.v1}).
 */
public record PackageDocument(
    String packageSchemaVersion,
    PackageDocumentPresentation presentation,
    List<PackageModel> models,
    List<PackageView> views,
    List<PackageExport> exports) {
  public PackageDocument {
    models = listOrEmpty(models);
    views = listOrEmpty(views);
    exports = listOrEmpty(exports);
  }
}

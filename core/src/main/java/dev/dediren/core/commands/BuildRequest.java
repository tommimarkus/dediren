package dev.dediren.core.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The inputs to a single {@code build} run (decision 14). {@code views} empty means every view in
 * model order; a non-empty list selects and orders views explicitly. Each {@code *PolicyText} is
 * the raw policy JSON for its output lane, or {@code null} to skip that lane — at least one lane
 * must be selected. {@code emit} is the subset of {@code layout-request|layout-result|
 * render-metadata} stage envelopes to also write under {@code outDir}. {@code env} is the candidate
 * environment forwarded to the export lanes' schema-validator boundary.
 *
 * <p>{@code confinementRoot} is the MCP trust boundary: when non-null, the model — not a human —
 * chose the source, so its {@code fragments[]} paths are confined to this root and fragment errors
 * are sanitized (see {@link dev.dediren.core.source.SourceValidator#validateSourceJson(String,
 * java.nio.file.Path, java.nio.file.Path)}). The nine-argument constructor is the unconfined
 * CLI/human lane and leaves it null.
 */
public record BuildRequest(
    String sourceText,
    Path sourceBaseDir,
    Path confinementRoot,
    List<String> views,
    String renderPolicyText,
    String oefPolicyText,
    String xmiPolicyText,
    Set<String> emit,
    Path outDir,
    Map<String, String> env) {
  public BuildRequest {
    views = views == null ? List.of() : List.copyOf(dedupViewsPreservingOrder(views));
    emit = emit == null ? Set.of() : Set.copyOf(emit);
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  // De-duplicate and drop blank view ids while preserving first-seen order, so the CLI (raw
  // --views split, which keeps duplicates) and the MCP lane agree on which views are built; a
  // repeated id would otherwise build the same view twice on the CLI lane. The compact constructor
  // wraps this in List.copyOf, so the returned mutable list never escapes (and SpotBugs sees the
  // field take an immutable copy, as it did for the former inline List.copyOf(views)).
  private static List<String> dedupViewsPreservingOrder(List<String> views) {
    java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
    for (String view : views) {
      if (view != null && !view.isBlank()) {
        ordered.add(view);
      }
    }
    return new java.util.ArrayList<>(ordered);
  }

  /** The unconfined CLI/human lane: no fragment confinement root. */
  public BuildRequest(
      String sourceText,
      Path sourceBaseDir,
      List<String> views,
      String renderPolicyText,
      String oefPolicyText,
      String xmiPolicyText,
      Set<String> emit,
      Path outDir,
      Map<String, String> env) {
    this(
        sourceText,
        sourceBaseDir,
        null,
        views,
        renderPolicyText,
        oefPolicyText,
        xmiPolicyText,
        emit,
        outDir,
        env);
  }
}

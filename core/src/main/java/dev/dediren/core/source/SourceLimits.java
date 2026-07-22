package dev.dediren.core.source;

/**
 * Ceilings on model input, applied by {@link SourceValidator} (fragment count, merged element
 * count) and by the input read lanes via {@code BoundedReads} (file bytes). Deliberately generous:
 * they exist to turn pathological input into a clean diagnostic instead of an OOM or a wedged
 * layout run, not to police model size. Rationale and accepted residuals: docs/threat-model.md.
 */
public record SourceLimits(long maxInputFileBytes, int maxFragments, int maxElements) {
  public static final SourceLimits DEFAULT = new SourceLimits(64L * 1024 * 1024, 1_000, 100_000);
}

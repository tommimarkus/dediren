package dev.dediren.contracts.build;

/**
 * One on-disk output the build driver produced for a view: the notation-neutral {@code
 * artifactKind} (for example {@code svg}, {@code html}, or an export media type such as {@code
 * archimate+xml}) and the {@code path} where it was written, expressed relative to the build output
 * directory with {@code /} separators so the value is stable across platforms.
 */
public record BuildArtifact(String artifactKind, String path) {}

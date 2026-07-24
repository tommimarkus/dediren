package dev.dediren.contracts.pkg;

/**
 * The declared output path per artifact kind for one view. {@code diagram} is required; {@code
 * renderMetadata} and {@code layout} are opt-in, produced only when declared (mirroring today's
 * {@code --emit}). Dediren writes each artifact at its declared path rather than to a fixed
 * view-major staging layout.
 */
public record PackageOutputs(String diagram, String renderMetadata, String layout) {}

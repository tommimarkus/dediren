package dev.dediren.contracts.pkg;

/**
 * Opaque per-view presentation carried by a package: a human title, the architecture question the
 * view answers, and a diagram-kind label. Dediren carries and echoes it — feeding {@code title} and
 * {@code question} to the render lane as per-view accessible-name text — but never interprets it as
 * model semantics. Every field is optional.
 */
public record PackagePresentation(String title, String question, String diagramKind) {}

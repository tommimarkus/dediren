package dev.dediren.contracts.layout;

/**
 * Reading rules for {@link LaidOutGroup} provenance.
 *
 * <p>Deciding which source element a laid-out group stands for is a contract question, not an
 * export-format question: both export engines ask it and must answer it identically. It is owned
 * here because engines may not depend on each other, and a copy apiece is how the two answers
 * silently drift apart.
 */
public final class LaidOutGroups {

  private LaidOutGroups() {}

  /**
   * Returns the id of the source element {@code group} stands for, or {@code null} when the group
   * is visual-only and stands for no source element at all.
   */
  public static String semanticSourceId(LaidOutGroup group) {
    if (group.provenance() == null) {
      return group.sourceId();
    }
    if (group.provenance().visualOnly()) {
      return null;
    }
    String sourceId = group.provenance().semanticSourceId();
    return sourceId == null ? group.sourceId() : sourceId;
  }
}

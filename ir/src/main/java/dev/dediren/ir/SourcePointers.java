package dev.dediren.ir;

/** Builds {@link SourcePointer}s addressing the source model's top-level arrays. */
public final class SourcePointers {
  private SourcePointers() {}

  public static SourcePointer node(int sourceNodeIndex) {
    return new SourcePointer("/nodes/" + sourceNodeIndex);
  }

  public static SourcePointer relationship(int sourceRelationshipIndex) {
    return new SourcePointer("/relationships/" + sourceRelationshipIndex);
  }
}

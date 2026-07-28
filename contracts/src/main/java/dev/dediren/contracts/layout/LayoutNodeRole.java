package dev.dediren.contracts.layout;

/**
 * The layout-role vocabulary a node may carry.
 *
 * <p>A node's {@code role} tells the layout, quality and render stages what the node *is* for
 * placement purposes, independently of its notation type: a UML {@code Lifeline} and any future
 * notation's equivalent both project to {@link #LIFELINE}.
 *
 * <p>It was a bare {@code String} spelled out by hand in six modules — three producers (the
 * semantics front ends) and four consumers (core's quality checks, ir's sequence invariants, elk's
 * intent normaliser) — while the sibling field on the very same record, {@code layer_constraint},
 * is enum-typed. Every consumer compares with {@code equals} and fails soft, so a misspelled role
 * did not error: it silently stopped matching, and the behaviour it gates (sequence invariants,
 * interaction-frame enclosure, junction quality rules) quietly switched off with no diagnostic.
 *
 * <p>This is the single declaration. The wire form stays a plain string in the Java records, but
 * layout-request.schema.json and layout-result.schema.json both enum-constrain {@code role} to
 * exactly these five values, so {@code dediren validate} rejects an unrecognised role. The one
 * remaining silent lane is direct {@code dediren layout}: it gates the schema version only and then
 * Jackson-parses {@code role} into this String field, so a hand-authored request carrying an
 * unrecognised role is accepted there and ignored downstream.
 */
public final class LayoutNodeRole {

  private LayoutNodeRole() {}

  /** A sequence-diagram participant: messages anchor to its axis and it owns a lifeline stem. */
  public static final String LIFELINE = "lifeline";

  /** A frame that must enclose the lifelines it covers. */
  public static final String INTERACTION = "interaction";

  /** A relationship connector (ArchiMate junction): a routing point, not a real element. */
  public static final String JUNCTION = "junction";

  /** A UML execution specification: a bar sitting on a lifeline stem for the span it is active. */
  public static final String EXECUTION = "execution";

  /** A UML destruction occurrence: the marker terminating a lifeline stem. */
  public static final String DESTRUCTION = "destruction";

  public static boolean isLifeline(String role) {
    return LIFELINE.equals(role);
  }

  public static boolean isInteraction(String role) {
    return INTERACTION.equals(role);
  }

  public static boolean isJunction(String role) {
    return JUNCTION.equals(role);
  }

  public static boolean isExecution(String role) {
    return EXECUTION.equals(role);
  }

  public static boolean isDestruction(String role) {
    return DESTRUCTION.equals(role);
  }
}

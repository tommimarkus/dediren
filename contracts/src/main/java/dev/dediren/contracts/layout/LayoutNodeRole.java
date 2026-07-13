package dev.dediren.contracts.layout;

import java.util.Set;

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
 * <p>This is the single declaration. The wire form stays a plain string — {@code role} is not
 * constrained by an enum in layout-request.schema.json, so a hand-authored request can still carry
 * an unrecognised role and be silently ignored downstream. Constraining the schema is a contract
 * narrowing and therefore a maintainer's decision, not a cleanup's.
 */
public final class LayoutNodeRole {

  private LayoutNodeRole() {}

  /** A sequence-diagram participant: messages anchor to its axis and it owns a lifeline stem. */
  public static final String LIFELINE = "lifeline";

  /** A frame that must enclose the lifelines it covers. */
  public static final String INTERACTION = "interaction";

  /** A relationship connector (ArchiMate junction): a routing point, not a real element. */
  public static final String JUNCTION = "junction";

  /** Every role this product recognises. */
  public static final Set<String> ALL = Set.of(LIFELINE, INTERACTION, JUNCTION);

  public static boolean isLifeline(String role) {
    return LIFELINE.equals(role);
  }

  public static boolean isInteraction(String role) {
    return INTERACTION.equals(role);
  }

  public static boolean isJunction(String role) {
    return JUNCTION.equals(role);
  }
}

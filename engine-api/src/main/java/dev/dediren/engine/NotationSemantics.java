package dev.dediren.engine;

import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.ir.LayoutIntent;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One notation's semantic knowledge: legality plus the notation-specific projection decisions the
 * shared base projection in {@code semantics-graph} delegates. The profile router holds one
 * instance per {@link dev.dediren.contracts.source.GenericGraphSemanticProfile} and never switches
 * on a string. Introduced by Plan B P3 to replace the stringly {@code semanticProfile} threaded
 * through the old single {@code generic-graph} projection. Plan B P5 retyped layout constraints to
 * the neutral typed {@link LayoutIntent} vocabulary exposed by {@link #layoutIntents}.
 */
public interface NotationSemantics {

  /**
   * Notation legality; throws {@link EngineException} on the first violation, carrying the exact
   * diagnostic code / exit code the notation core raises. A no-op for the plain graph profile.
   */
  void validate(SourceDocument source, GenericGraphPluginData pluginData) throws EngineException;

  /**
   * Layout role for a source node type ({@code "lifeline"}/{@code "interaction"}/{@code
   * "junction"}), or {@code null} for none.
   */
  String layoutRole(String sourceType);

  /** Width sizing hint for a source node in this notation. */
  double widthHint(SourceNode node);

  /** Height sizing hint for a source node in this notation. */
  double heightHint(SourceNode node);

  /**
   * True when a source node is notation chrome that must not become a scene node (UML-sequence
   * {@code CombinedFragment}/{@code InteractionOperand}); false for notations that keep all nodes.
   */
  boolean isSourceOnlyNode(GenericGraphView view, SourceNode node);

  /**
   * Notation layout intents for the view, in the neutral {@link LayoutIntent} vocabulary, or empty.
   * The single live layout-constraint producer since the Plan B P5 cutover retyped {@code
   * SceneGraph.constraints} and wired elk onto the {@code LayoutIntentCodec}/{@code
   * LayoutIntentNormalizer} seam.
   */
  List<LayoutIntent> layoutIntents(SourceDocument source, GenericGraphView view);

  /** Per-node render-metadata selector properties (the {@code uml} subtree), or {@code null}. */
  JsonNode nodeRenderProperties(SourceNode node);

  /** Per-edge render-metadata selector properties (the {@code uml} subtree), or {@code null}. */
  JsonNode edgeRenderProperties(SourceRelationship relationship);
}

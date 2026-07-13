package dev.dediren.semantics.uml;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The UML notation: element/relationship/view legality against {@link Uml}, layout role for
 * lifelines/interactions/execution-specifications/destruction-occurrences (a {@code Gate} stays
 * role-less; out of scope for this slice), sequence-diagram/state-machine/use-case/component/
 * deployment/structural sizing via {@link UmlLayoutSizing}, sequence-fragment chrome filtering
 * ({@code CombinedFragment} / {@code InteractionOperand} are notation-only nodes in a {@code
 * uml-sequence} view), the four {@code uml.sequence.*} layout constraints via {@link
 * UmlSequenceConstraints}, and the {@code uml} render-metadata selector subtree. Relocated verbatim
 * from the old single generic-graph {@code GenericGraphEngine}'s UML branch (Plan B P3): {@code
 * validate} runs the exact same {@code Uml.validateSource} the old engine ran identically from both
 * its {@code validate()} command and its projection path, so a single hook here covers both call
 * sites without behavior drift.
 */
public final class UmlNotationSemantics implements NotationSemantics {

  @Override
  public void validate(SourceDocument source, GenericGraphPluginData pluginData)
      throws EngineException {
    try {
      Uml.validateSource(source, pluginData);
    } catch (UmlValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    }
  }

  // Carry roles into the layout-request so backend-neutral layout-quality checks can apply
  // role-aware geometry rules (lifeline message anchors, activation-bar/destruction-mark
  // placement). Gate stays role-less/out of scope for this slice; other source types stay
  // role-less too.
  @Override
  public String layoutRole(String sourceType) {
    if ("Lifeline".equals(sourceType)) {
      return "lifeline";
    }
    if ("Interaction".equals(sourceType)) {
      return "interaction";
    }
    if ("ExecutionSpecification".equals(sourceType)) {
      return "execution";
    }
    if ("DestructionOccurrenceSpecification".equals(sourceType)) {
      return "destruction";
    }
    return null;
  }

  @Override
  public double widthHint(SourceNode node) {
    return UmlLayoutSizing.widthHint(node);
  }

  @Override
  public double heightHint(SourceNode node) {
    return UmlLayoutSizing.heightHint(node);
  }

  @Override
  public boolean isSourceOnlyNode(GenericGraphView view, SourceNode node) {
    return view.kind() == GenericGraphViewKind.UML_SEQUENCE
        && (node.type().equals("CombinedFragment") || node.type().equals("InteractionOperand"));
  }

  @Override
  public List<LayoutIntent> layoutIntents(SourceDocument source, GenericGraphView view) {
    return UmlSequenceConstraints.lower(UmlSequenceConstraints.sequenceConstraints(source, view));
  }

  @Override
  public JsonNode nodeRenderProperties(SourceNode node) {
    return node.properties().get("uml");
  }

  @Override
  public JsonNode edgeRenderProperties(SourceRelationship relationship) {
    return relationship.properties().get("uml");
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }
}

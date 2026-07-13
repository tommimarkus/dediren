package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.SceneGraph;
import dev.dediren.ir.quality.SequenceInvariants;
import dev.dediren.plugins.elklayout.ElkEngine;
import dev.dediren.semantics.archimate.ArchimateNotationSemantics;
import dev.dediren.semantics.graph.GraphNotationSemantics;
import dev.dediren.semantics.graph.SemanticsRouterEngine;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property test for Plan B P2 task 8: generated, engine-valid UML-sequence models are pushed
 * through the real project&#8594;layout path (the same {@link SemanticsRouterEngine} / {@link
 * ElkEngine} objects {@code EngineWiring} constructs for the CLI) and the P2-T7 sequence invariants
 * ({@link SequenceInvariants}) must hold on every generated model, not just the hand-written oracle
 * fixtures.
 *
 * <p>{@code SceneProjection} and {@code ElkLayoutEngine} named in the task are package-private to
 * their own plugin packages, so this test drives their public wrappers instead — {@link
 * SemanticsRouterEngine#projectScene} and {@link ElkEngine#layout} — which run identical
 * validation/projection/layout logic and are the exact instances {@code EngineWiring} wires into
 * the CLI's in-memory dispatch.
 *
 * <p><strong>Generation.</strong> The {@value #TRIALS} models are drawn from a {@link Random} fixed
 * at {@link #GENERATOR_SEED} and replayed as JUnit {@link ParameterizedTest} cases, so trial
 * <em>n</em> is the same model on every machine and on every run. A failing trial names its own
 * index, and that index is the whole reproduction recipe: the corpus is a pure function of the
 * seed. This buys determinism rather than shrinking — a failure reports the model it found rather
 * than a minimised one (every {@code describedAs} below carries the record's {@code toString}) —
 * which is the trade that keeps this suite on the JUnit engine the rest of the reactor already
 * runs, with no second test engine on the classpath.
 *
 * <p>The generator emits an {@code Interaction} and 2-5 {@code Lifeline} nodes and 1-10 {@code
 * Message} relationships with unique strictly-increasing {@code uml.sequence} integers between two
 * lifelines — on roughly a {@code 1 / lifelineCount} share of generated messages the source and
 * target lifeline are the same one (a self-message), and otherwise they are distinct — and — Plan B
 * P5 task 7 (closing the W3 gap) — on a minority of trials a single {@code CombinedFragment} node
 * wrapping a contiguous span of those messages, split across one or two {@code InteractionOperand}
 * children ({@code opt} / {@code loop} for one operand, {@code alt} / {@code par} for two). The
 * fragment's operand(s) always own a contiguous, non-overlapping block of the generated messages
 * (no interior gaps and no shared messages) so {@code Uml.validateSource} accepts every generated
 * model, and so the fragment-open (first operand) / operand-open (later operands) leading-gap
 * constraints {@code UmlSequenceConstraints} derives are non-empty and actually move message
 * Y-positions when a fragment is present. Self-messages (see this test's sibling fixture {@code
 * valid-uml-sequence-self-message.json}) are generated and exercised end to end through the real
 * project&#8594;layout path, closing the coverage gap that let a self-call hard-fail {@code build}
 * ship unnoticed. Nested fragments and fragment {@code covered} lifeline sets are not needed to
 * exercise the leading-gap path and remain out of scope.
 *
 * <p>Task 8 closes the rest of the W1 coverage hole — the one that let an activation bar land at
 * the canvas origin and a delete-message hard-fail {@code build} (exit 2) ship unnoticed. A
 * minority of trials also carry an {@code ExecutionSpecification} (valid {@code covered}/{@code
 * start}/{@code finish}, resolving to a selected lifeline and two selected messages) and,
 * independently, a {@code DestructionOccurrenceSpecification} (valid {@code covered}) targeted by
 * exactly one generated {@code deleteMessage}. Neither {@link SequenceInvariants} nor {@link
 * #assertNoNodeRectsOverlap} treats a delete-message's endpoints as a "message" — its target's
 * {@code role} is {@code "destruction"}, not {@code "lifeline"} — so {@link
 * #assertDeleteMessageGeometry} asserts its geometry directly instead: the destruction marker
 * centred on its covered lifeline's stem, and the delete-message's last route point landing exactly
 * on the marker's near edge.
 *
 * <p>Whether the generator actually reaches all of those shapes is not left to a report a human has
 * to read: {@link #generatorCoversEveryShapeUnderTest} asserts the corpus hits every one of them,
 * so a generator regression that quietly stops emitting fragments (or self-messages, executions,
 * destructions) fails the build instead of silently shrinking what this suite covers.
 */
class SequenceLayoutPropertyTest {

  private static final List<String> MESSAGE_SORTS =
      List.of("synchCall", "asynchCall", "asynchSignal", "reply", "createMessage", "deleteMessage");

  /** How many generated models the invariants below are checked against. */
  private static final int TRIALS = 300;

  /**
   * Fixes the corpus. Every generated model — and therefore every assertion in this class — is a
   * pure function of this seed, so changing it means choosing a different {@value #TRIALS} models:
   * change it deliberately, and re-read the coverage assertion when you do.
   */
  private static final long GENERATOR_SEED = 1L;

  @ParameterizedTest(name = "trial {index}")
  @MethodSource("generatedSequenceModels")
  void generatedSequenceModelsSatisfySequenceInvariants(GeneratedSequenceModel model)
      throws Exception {
    SourceDocument source =
        JsonSupport.objectMapper().readValue(buildSourceJson(model), SourceDocument.class);

    SceneGraph sceneGraph =
        new SemanticsRouterEngine(
                Map.of(
                    GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics(),
                    GenericGraphSemanticProfile.ARCHIMATE, new ArchimateNotationSemantics(),
                    GenericGraphSemanticProfile.UML, new UmlNotationSemantics()))
            .projectScene(source, "sequence-view")
            .value();
    LaidOutScene scene = new ElkEngine().layout(sceneGraph).value();

    assertThat(SequenceInvariants.messageEndpointsOnLifelineAxis(scene))
        .describedAs("messageEndpointsOnLifelineAxis for %s", model)
        .isEmpty();
    assertThat(SequenceInvariants.messageYStrictlyIncreasing(scene))
        .describedAs("messageYStrictlyIncreasing for %s", model)
        .isEmpty();
    assertThat(SequenceInvariants.interactionFrameEnclosesLifelines(scene))
        .describedAs("interactionFrameEnclosesLifelines for %s", model)
        .isEmpty();
    assertNoNodeRectsOverlap(scene, model);
    assertDeleteMessageGeometry(scene, model);
  }

  // --- generator coverage ------------------------------------------------------------------------

  /**
   * The smallest share of the {@value #TRIALS} trials a shape must claim before it counts as
   * genuinely exercised rather than as a stray draw. Every shape below is gated on a
   * coin-flip-scale probability, not a rare corner, so each is expected to land far above this
   * floor; it fails only when a generator change collapses a shape — the exact failure the jqwik
   * {@code Statistics} report this replaces could describe but never catch.
   */
  private static final int MIN_TRIALS_PER_SHAPE = 10;

  @Test
  void generatorCoversEveryShapeUnderTest() {
    List<GeneratedSequenceModel> corpus = generatedSequenceModels().toList();
    assertThat(corpus).hasSize(TRIALS);

    Map<String, Integer> counts = new LinkedHashMap<>();
    for (GeneratedSequenceModel model : corpus) {
      counts.merge(fragmentShapeLabel(model.fragment()), 1, Integer::sum);
      counts.merge(selfMessageLabel(model), 1, Integer::sum);
      counts.merge(executionLabel(model), 1, Integer::sum);
      counts.merge(destructionLabel(model), 1, Integer::sum);
    }

    List<String> shapes =
        List.of(
            "no fragment",
            "fragment: 1 operand (fragment-open only)",
            "fragment: 2 operands (fragment-open + operand-open)",
            "self-message: present",
            "self-message: absent",
            "execution: present",
            "execution: absent",
            "destruction: present",
            "destruction: absent");
    for (String shape : shapes) {
      assertThat(counts.getOrDefault(shape, 0))
          .describedAs(
              "shape '%s' must be exercised by at least %d of the %d generated trials"
                  + " (full distribution: %s)",
              shape, MIN_TRIALS_PER_SHAPE, TRIALS, counts)
          .isGreaterThanOrEqualTo(MIN_TRIALS_PER_SHAPE);
    }
  }

  // --- generator --------------------------------------------------------------------------------

  // Probability a generated model has NO combined fragment at all: kept > 0.5 so the plain
  // message-ordering path (the P2 task 8 baseline) still dominates the 300 trials, while a solid
  // minority of trials exercise the W3 fragment/operand-open leading-gap path added for P5 task 7.
  private static final double NO_FRAGMENT_PROBABILITY = 0.55;

  // Probability a generated model has NO ExecutionSpecification / NO destruction, independent of
  // NO_FRAGMENT_PROBABILITY and of each other: kept > 0.5 so most trials stay free of either, while
  // a solid, independent minority of the 300 trials exercise each -- this is the Task 8 coverage
  // this class exists to add (see the class javadoc).
  private static final double NO_EXECUTION_PROBABILITY = 0.55;
  private static final double NO_DESTRUCTION_PROBABILITY = 0.55;

  // Probability a generated message omits uml.message_sort entirely, so the absent-sort default
  // path is exercised alongside the six explicit sorts.
  private static final double NO_MESSAGE_SORT_PROBABILITY = 0.3;

  /**
   * The corpus: {@value #TRIALS} models drawn from a {@link Random} fixed at {@link
   * #GENERATOR_SEED}. Drawn eagerly into a list rather than streamed lazily, so the draw order —
   * and therefore which model sits at a given trial index — cannot vary with how JUnit chooses to
   * consume the stream.
   */
  private static Stream<GeneratedSequenceModel> generatedSequenceModels() {
    Random random = new Random(GENERATOR_SEED);
    List<GeneratedSequenceModel> models = new ArrayList<>(TRIALS);
    for (int trial = 0; trial < TRIALS; trial++) {
      int lifelineCount = 2 + random.nextInt(4);
      List<GeneratedMessage> messages = generateMessages(random, lifelineCount);
      models.add(
          new GeneratedSequenceModel(
              lifelineCount,
              messages,
              generateFragment(random, messages.size()),
              generateExecution(random, lifelineCount, messages.size()),
              generateDestruction(random, lifelineCount)));
    }
    return models.stream();
  }

  /**
   * 1-10 messages among the {@code lifelineCount} lifelines. A shift in {@code [0, lifelineCount)}
   * applied mod {@code lifelineCount} lands back on the source lifeline itself when the shift is 0
   * — a self-message, source == target — and on some other distinct participant for every nonzero
   * shift. Both outcomes are valid Lifeline -&gt; Lifeline Message connections ({@code
   * Uml.validateSource} never compares source/target identity for Message endpoints — see {@code
   * Uml#isMessageEndpoint}), so including 0 needs no rejection filter to stay legal. This gives
   * each generated message a {@code 1 / lifelineCount} chance of being a self-message: a
   * non-trivial share across the trials (lifelineCount ranges 2-5) without dominating the plain
   * distinct-endpoint case.
   */
  private static List<GeneratedMessage> generateMessages(Random random, int lifelineCount) {
    int messageCount = 1 + random.nextInt(10);
    List<GeneratedMessage> messages = new ArrayList<>(messageCount);
    for (int i = 0; i < messageCount; i++) {
      int sourceIndex = random.nextInt(lifelineCount);
      int targetShift = random.nextInt(lifelineCount);
      String messageSort =
          random.nextDouble() < NO_MESSAGE_SORT_PROBABILITY
              ? null
              : MESSAGE_SORTS.get(random.nextInt(MESSAGE_SORTS.size()));
      // Sequence assigned by list position: unique and strictly increasing by construction,
      // matching the task's per-message uml.sequence requirement.
      messages.add(
          new GeneratedMessage(
              sourceIndex, (sourceIndex + targetShift) % lifelineCount, i + 1, messageSort));
    }
    return List.copyOf(messages);
  }

  /**
   * An optional {@link GeneratedFragment} covering a contiguous span of {@code [0, messageCount)}
   * message indices, split across one or two {@link GeneratedOperand}s: one contiguous block
   * ({@code opt}/{@code loop}, always legal since both allow exactly one operand) or, when the span
   * holds at least two messages, two contiguous, back-to-back blocks ({@code alt}/{@code par},
   * which require at least two operands). Contiguous, non-overlapping, back-to-back spans are what
   * keep every generated fragment {@code Uml.validateSource}-legal: no message is ever owned by two
   * operands, and no message inside the fragment's overall sequence range is left unowned by any
   * operand. Returning {@code null} at {@link #NO_FRAGMENT_PROBABILITY} keeps most trials on the
   * fragment-free baseline while still generating plenty of fragment-bearing trials across the
   * corpus.
   */
  private static GeneratedFragment generateFragment(Random random, int messageCount) {
    if (random.nextDouble() < NO_FRAGMENT_PROBABILITY) {
      return null;
    }
    int spanStart = random.nextInt(messageCount);
    int spanLength = 1 + random.nextInt(messageCount - spanStart);
    int spanEnd = spanStart + spanLength - 1;

    // A two-operand fragment needs at least two messages to split across. Where the span allows
    // both, the one- and two-operand shapes are drawn evenly so neither starves across the corpus.
    if (spanLength < 2 || random.nextBoolean()) {
      return new GeneratedFragment(
          random.nextBoolean() ? "opt" : "loop",
          List.of(new GeneratedOperand(1, spanStart, spanEnd)));
    }
    int split = 1 + random.nextInt(spanLength - 1);
    return new GeneratedFragment(
        random.nextBoolean() ? "alt" : "par",
        List.of(
            new GeneratedOperand(1, spanStart, spanStart + split - 1),
            new GeneratedOperand(2, spanStart + split, spanEnd)));
  }

  /**
   * An optional {@link GeneratedExecution}: an {@code ExecutionSpecification} covering a random
   * lifeline, anchored to two of the generated messages in chronological order ({@code
   * startMessageIndex} strictly before {@code finishMessageIndex}, both 0-based positions into the
   * enclosing model's messages list). Needs at least two generated messages to pick a distinct
   * start/finish pair, so trials with a single message never carry one. Returning {@code null} at
   * {@link #NO_EXECUTION_PROBABILITY} keeps most trials free of an activation bar while still
   * generating plenty across the corpus -- this closes the coverage gap that let a malformed
   * ExecutionSpecification (a {@code covered}/{@code start}/{@code finish} absent, or present but
   * not resolving to a selected lifeline/message) slip past {@code Uml.validateSource} and
   * hard-fail layout unnoticed (see {@code
   * UmlSequenceValidation#validateSelectedExecutionSpecificationProperties}).
   */
  private static GeneratedExecution generateExecution(
      Random random, int lifelineCount, int messageCount) {
    if (messageCount < 2 || random.nextDouble() < NO_EXECUTION_PROBABILITY) {
      return null;
    }
    int startMessageIndex = random.nextInt(messageCount - 1);
    int coveredLifelineIndex = random.nextInt(lifelineCount);
    int finishMessageIndex =
        startMessageIndex + 1 + random.nextInt(messageCount - 1 - startMessageIndex);
    return new GeneratedExecution(coveredLifelineIndex, startMessageIndex, finishMessageIndex);
  }

  /**
   * An optional {@link GeneratedDestruction}: a {@code DestructionOccurrenceSpecification} covering
   * a random lifeline, targeted by exactly one generated {@code deleteMessage} from a (possibly the
   * same) random lifeline. {@code Uml.validateSource} allows at most one Message to target a given
   * destruction ({@code UmlSequenceValidation#validateSelectedDestructionMessageUniqueness}), and
   * {@link #appendDestructionJson} only ever emits the single delete-message this record describes,
   * so every generated model stays valid. Returning {@code null} at {@link
   * #NO_DESTRUCTION_PROBABILITY} keeps most trials free of a destruction marker while still
   * generating plenty across the corpus.
   */
  private static GeneratedDestruction generateDestruction(Random random, int lifelineCount) {
    if (random.nextDouble() < NO_DESTRUCTION_PROBABILITY) {
      return null;
    }
    return new GeneratedDestruction(random.nextInt(lifelineCount), random.nextInt(lifelineCount));
  }

  private static String fragmentShapeLabel(GeneratedFragment fragment) {
    if (fragment == null) {
      return "no fragment";
    }
    return fragment.operands().size() == 1
        ? "fragment: 1 operand (fragment-open only)"
        : "fragment: 2 operands (fragment-open + operand-open)";
  }

  private static String executionLabel(GeneratedSequenceModel model) {
    return model.execution() == null ? "execution: absent" : "execution: present";
  }

  private static String destructionLabel(GeneratedSequenceModel model) {
    return model.destruction() == null ? "destruction: absent" : "destruction: present";
  }

  /**
   * Whether {@code model} contains at least one self-message (a {@code Message} whose source and
   * target lifeline are the same). A self-message never adds a scene node of its own — it is still
   * exactly one {@code Lifeline} pair like every other message — so it needs no counterpart in
   * {@link #assertNoNodeRectsOverlap}.
   */
  private static String selfMessageLabel(GeneratedSequenceModel model) {
    boolean hasSelfMessage =
        model.messages().stream()
            .anyMatch(message -> message.sourceLifelineIndex() == message.targetLifelineIndex());
    return hasSelfMessage ? "self-message: present" : "self-message: absent";
  }

  private record GeneratedMessage(
      int sourceLifelineIndex, int targetLifelineIndex, int sequence, String messageSort) {}

  // startMessageIndex/endMessageIndex are inclusive 0-based positions into the enclosing model's
  // messages list; the message at index i has uml.sequence == i + 1 and relationship id "m-i", so
  // an operand's span maps directly to a contiguous run of message ids/sequence numbers.
  private record GeneratedOperand(int order, int startMessageIndex, int endMessageIndex) {}

  private record GeneratedFragment(String operator, List<GeneratedOperand> operands) {}

  // coveredLifelineIndex is the ExecutionSpecification's uml.covered lifeline; startMessageIndex/
  // finishMessageIndex are 0-based positions into the enclosing model's messages list (same id
  // convention as GeneratedOperand), always startMessageIndex < finishMessageIndex so the bar spans
  // forward in time.
  private record GeneratedExecution(
      int coveredLifelineIndex, int startMessageIndex, int finishMessageIndex) {}

  // coveredLifelineIndex is the DestructionOccurrenceSpecification's uml.covered lifeline;
  // deleteSourceLifelineIndex is the source lifeline of the single generated deleteMessage that
  // targets it -- it may equal coveredLifelineIndex (a lifeline destroying itself is valid UML and
  // exercises the "source declared at or left of the destroyed lifeline" branch of the near-edge
  // routing rule just like a distinct, lower-indexed source would).
  private record GeneratedDestruction(int coveredLifelineIndex, int deleteSourceLifelineIndex) {}

  private record GeneratedSequenceModel(
      int lifelineCount,
      List<GeneratedMessage> messages,
      GeneratedFragment fragment,
      GeneratedExecution execution,
      GeneratedDestruction destruction) {}

  // --- source-document JSON assembly -------------------------------------------------------------

  private static String buildSourceJson(GeneratedSequenceModel model) {
    StringBuilder nodesJson = new StringBuilder();
    nodesJson.append(
        "{\"id\":\"interaction\",\"type\":\"Interaction\",\"label\":\"Interaction\","
            + "\"properties\":{\"uml\":{}}}");
    StringBuilder nodeIdsJson = new StringBuilder("\"interaction\"");
    for (int i = 0; i < model.lifelineCount(); i++) {
      nodesJson
          .append(",")
          .append(
              ("{\"id\":\"lifeline-%d\",\"type\":\"Lifeline\",\"label\":\"Lifeline %d\","
                      + "\"properties\":{\"uml\":{\"interaction\":\"interaction\"}}}")
                  .formatted(i, i));
      nodeIdsJson.append(",\"lifeline-").append(i).append("\"");
    }

    StringBuilder relationshipsJson = new StringBuilder();
    StringBuilder relationshipIdsJson = new StringBuilder();
    List<GeneratedMessage> messages = model.messages();
    for (int i = 0; i < messages.size(); i++) {
      GeneratedMessage message = messages.get(i);
      if (i > 0) {
        relationshipsJson.append(",");
        relationshipIdsJson.append(",");
      }
      String messageSortField =
          message.messageSort() == null
              ? ""
              : ",\"message_sort\":\"" + message.messageSort() + "\"";
      relationshipsJson.append(
          ("{\"id\":\"m-%d\",\"type\":\"Message\",\"source\":\"lifeline-%d\","
                  + "\"target\":\"lifeline-%d\",\"label\":\"m-%d\","
                  + "\"properties\":{\"uml\":{\"interaction\":\"interaction\",\"sequence\":%d%s}}}")
              .formatted(
                  i,
                  message.sourceLifelineIndex(),
                  message.targetLifelineIndex(),
                  i,
                  message.sequence(),
                  messageSortField));
      relationshipIdsJson.append("\"m-").append(i).append("\"");
    }

    appendFragmentJson(model.fragment(), nodesJson, nodeIdsJson);
    appendExecutionJson(model.execution(), nodesJson, nodeIdsJson);
    appendDestructionJson(
        model.destruction(),
        nodesJson,
        nodeIdsJson,
        relationshipsJson,
        relationshipIdsJson,
        messages.size());

    return """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [%s],
          "relationships": [%s],
          "plugins": {
            "generic-graph": {
              "semantic_profile": "uml",
              "views": [
                {
                  "id": "sequence-view",
                  "label": "Generated Sequence",
                  "kind": "uml-sequence",
                  "nodes": [%s],
                  "relationships": [%s],
                  "layout_preferences": {
                    "direction": "right",
                    "density": "readable",
                    "routing": {
                      "style": "orthogonal",
                      "endpoint_merging": "off"
                    }
                  }
                }
              ]
            }
          }
        }
        """
        .formatted(nodesJson, relationshipsJson, nodeIdsJson, relationshipIdsJson);
  }

  /**
   * Appends the {@code CombinedFragment} node (id {@code fragment-0}) and its {@code
   * InteractionOperand} children (ids {@code operand-0}, {@code operand-1}) to both the source
   * {@code nodes} array and the view's selected-node id list, when {@code fragment} is non-null.
   * {@code CombinedFragment}/{@code InteractionOperand} are notation-only source nodes ({@link
   * dev.dediren.semantics.uml.UmlNotationSemantics#isSourceOnlyNode}) — they never become scene
   * nodes, so they add no new geometry for {@link #assertNoNodeRectsOverlap} to check; their only
   * effect is the fragment-open/operand-open leading-gap constraints they let {@code
   * UmlSequenceConstraints} derive.
   */
  private static void appendFragmentJson(
      GeneratedFragment fragment, StringBuilder nodesJson, StringBuilder nodeIdsJson) {
    if (fragment == null) {
      return;
    }
    List<GeneratedOperand> operands = fragment.operands();
    StringBuilder operandIdsJson = new StringBuilder();
    for (int i = 0; i < operands.size(); i++) {
      if (i > 0) {
        operandIdsJson.append(",");
      }
      operandIdsJson.append("\"operand-").append(operands.get(i).order() - 1).append("\"");
    }
    nodesJson
        .append(",")
        .append(
            ("{\"id\":\"fragment-0\",\"type\":\"CombinedFragment\",\"label\":\"Fragment\","
                    + "\"properties\":{\"uml\":{\"interaction\":\"interaction\","
                    + "\"operator\":\"%s\",\"operands\":[%s]}}}")
                .formatted(fragment.operator(), operandIdsJson));
    nodeIdsJson.append(",\"fragment-0\"");

    for (GeneratedOperand operand : operands) {
      String operandId = "operand-" + (operand.order() - 1);
      StringBuilder operandMessageIdsJson = new StringBuilder();
      for (int messageIndex = operand.startMessageIndex();
          messageIndex <= operand.endMessageIndex();
          messageIndex++) {
        if (operandMessageIdsJson.length() > 0) {
          operandMessageIdsJson.append(",");
        }
        operandMessageIdsJson.append("\"m-").append(messageIndex).append("\"");
      }
      nodesJson
          .append(",")
          .append(
              ("{\"id\":\"%s\",\"type\":\"InteractionOperand\",\"label\":\"%s\","
                      + "\"properties\":{\"uml\":{\"interaction\":\"interaction\","
                      + "\"combined_fragment\":\"fragment-0\",\"order\":%d,\"fragments\":[%s]}}}")
                  .formatted(operandId, operandId, operand.order(), operandMessageIdsJson));
      nodeIdsJson.append(",\"").append(operandId).append("\"");
    }
  }

  /**
   * Appends the {@code ExecutionSpecification} node (id {@code execution-0}) to both the source
   * {@code nodes} array and the view's selected-node id list, when {@code execution} is non-null.
   * {@code uml.covered}/{@code uml.start}/{@code uml.finish} always resolve to a selected
   * lifeline/message, so {@code Uml.validateSource}'s occurrence-selection rules ({@code
   * UmlSequenceValidation#validateSelectedExecutionSpecificationProperties}) accept every generated
   * model. Unlike {@code CombinedFragment}/{@code InteractionOperand}, an ExecutionSpecification
   * DOES become a {@code role=="execution"} scene node ({@link
   * dev.dediren.semantics.uml.UmlNotationSemantics#layoutRole}) -- the activation bar -- which is
   * why {@link #assertNoNodeRectsOverlap} below exempts that role instead of skipping it.
   */
  private static void appendExecutionJson(
      GeneratedExecution execution, StringBuilder nodesJson, StringBuilder nodeIdsJson) {
    if (execution == null) {
      return;
    }
    nodesJson
        .append(",")
        .append(
            ("{\"id\":\"execution-0\",\"type\":\"ExecutionSpecification\",\"label\":\"Execution\","
                    + "\"properties\":{\"uml\":{\"covered\":\"lifeline-%d\","
                    + "\"start\":\"m-%d\",\"finish\":\"m-%d\"}}}")
                .formatted(
                    execution.coveredLifelineIndex(),
                    execution.startMessageIndex(),
                    execution.finishMessageIndex()));
    nodeIdsJson.append(",\"execution-0\"");
  }

  /**
   * Appends the {@code DestructionOccurrenceSpecification} node (id {@code destruction-0}) and the
   * single {@code deleteMessage} relationship (id {@code m-delete}) that targets it, to the source
   * {@code nodes}/{@code relationships} arrays and the view's selected-id lists, when {@code
   * destruction} is non-null. The delete-message's {@code uml.sequence} is {@code messageCount + 1}
   * -- strictly greater than every generated message's sequence, so it is always unique (see {@code
   * UmlSequenceValidation#validateMessageSequenceUniqueness}) and always sorts last, outside any
   * combined-fragment operand span. Exactly one Message targets {@code destruction-0}, satisfying
   * {@code UmlSequenceValidation#validateSelectedDestructionMessageUniqueness}.
   */
  private static void appendDestructionJson(
      GeneratedDestruction destruction,
      StringBuilder nodesJson,
      StringBuilder nodeIdsJson,
      StringBuilder relationshipsJson,
      StringBuilder relationshipIdsJson,
      int messageCount) {
    if (destruction == null) {
      return;
    }
    nodesJson
        .append(",")
        .append(
            ("{\"id\":\"destruction-0\",\"type\":\"DestructionOccurrenceSpecification\","
                    + "\"label\":\"Destruction\",\"properties\":{\"uml\":{\"covered\":\"lifeline-%d\"}}}")
                .formatted(destruction.coveredLifelineIndex()));
    nodeIdsJson.append(",\"destruction-0\"");

    relationshipsJson
        .append(",")
        .append(
            ("{\"id\":\"m-delete\",\"type\":\"Message\",\"source\":\"lifeline-%d\","
                    + "\"target\":\"destruction-0\",\"label\":\"m-delete\","
                    + "\"properties\":{\"uml\":{\"interaction\":\"interaction\",\"sequence\":%d,"
                    + "\"message_sort\":\"deleteMessage\"}}}")
                .formatted(destruction.deleteSourceLifelineIndex(), messageCount + 1));
    relationshipIdsJson.append(",\"m-delete\"");
  }

  // --- extra geometry invariant: no sibling node rects overlap -----------------------------------

  /**
   * Beyond the three named {@link SequenceInvariants}, pins that no two non-nesting {@link
   * PlacedNode} rects overlap. The interaction frame is expected to enclose every lifeline (that
   * containment is what {@link SequenceInvariants#interactionFrameEnclosesLifelines} checks
   * separately), so frame-vs-lifeline pairs are excluded here. An activation bar ({@code
   * role=="execution"}) and a destruction marker ({@code role=="destruction"}) are ALSO excluded
   * from every pair they appear in: both are anchored to sit ON their covered lifeline's stem by
   * construction (see {@code LayoutIntentNormalizer#placeStemSpanNodes}), not laid out
   * independently, so a rect "overlap" with the lifeline (or with each other, when they share a
   * covered lifeline) is sequence chrome, not a layout defect -- this mirrors {@code
   * LayoutQuality#isSequenceChrome}, the equivalent exemption on the {@code core} quality-check
   * path. Every remaining pair — lifeline-vs-lifeline in this generator's node set — must not
   * overlap.
   */
  private static void assertNoNodeRectsOverlap(LaidOutScene scene, GeneratedSequenceModel model) {
    List<PlacedNode> nodes = scene.nodes();
    for (int i = 0; i < nodes.size(); i++) {
      for (int j = i + 1; j < nodes.size(); j++) {
        PlacedNode a = nodes.get(i);
        PlacedNode b = nodes.get(j);
        if (isSequenceChromeRole(a.role()) || isSequenceChromeRole(b.role())) {
          continue;
        }
        assertThat(rectsOverlap(a, b))
            .describedAs(
                "nodes '%s' [%s,%s,w=%s,h=%s] and '%s' [%s,%s,w=%s,h=%s] overlap for %s",
                a.id(),
                a.x(),
                a.y(),
                a.width(),
                a.height(),
                b.id(),
                b.x(),
                b.y(),
                b.width(),
                b.height(),
                model)
            .isFalse();
      }
    }
  }

  private static boolean isSequenceChromeRole(String role) {
    return "interaction".equals(role) || "execution".equals(role) || "destruction".equals(role);
  }

  private static boolean rectsOverlap(PlacedNode a, PlacedNode b) {
    return a.x() < b.x() + b.width()
        && b.x() < a.x() + a.width()
        && a.y() < b.y() + b.height()
        && b.y() < a.y() + a.height();
  }

  // --- extra geometry invariant: delete-message reaches its destruction marker -------------------

  /**
   * A delete-message's endpoints are NOT covered by {@link SequenceInvariants}: its private {@code
   * isMessage} helper requires BOTH endpoints to be {@code role=="lifeline"}, and a destruction
   * marker's role is {@code "destruction"} -- so a regression in delete-message geometry would sail
   * through every invariant assertion above undetected. Pins the two facts {@code
   * LayoutIntentNormalizer#placeStemSpanNodes}/{@code #normalizedMessagePoints} guarantee directly
   * instead: the destruction marker is centred (x-axis) on its covered lifeline's stem, and the
   * delete-message's last route point lands exactly on the marker's near edge (left if the
   * delete-message's source lifeline is declared at or before the destroyed lifeline, right
   * otherwise) at the marker's vertical centre. A no-op when the trial carries no destruction.
   */
  private static void assertDeleteMessageGeometry(
      LaidOutScene scene, GeneratedSequenceModel model) {
    GeneratedDestruction destruction = model.destruction();
    if (destruction == null) {
      return;
    }
    Map<String, PlacedNode> nodesById = new HashMap<>();
    for (PlacedNode node : scene.nodes()) {
      nodesById.put(node.id(), node);
    }
    PlacedNode destructionNode = nodesById.get("destruction-0");
    PlacedNode coveredLifeline = nodesById.get("lifeline-" + destruction.coveredLifelineIndex());
    assertThat(destructionNode).describedAs("destruction-0 scene node for %s", model).isNotNull();
    assertThat(coveredLifeline)
        .describedAs("covered lifeline scene node for %s", model)
        .isNotNull();

    double destructionCenterX = destructionNode.x() + destructionNode.width() / 2.0;
    double lifelineCenterX = coveredLifeline.x() + coveredLifeline.width() / 2.0;
    assertThat(destructionCenterX)
        .describedAs("destruction-0 must be centred on its covered lifeline's stem for %s", model)
        .isCloseTo(lifelineCenterX, within(1e-6));

    var deleteMessage =
        scene.edges().stream()
            .filter(edge -> "m-delete".equals(edge.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no m-delete edge in scene for " + model));
    var lastPoint = deleteMessage.points().getLast();

    boolean sourceDeclaredRightOfCovered =
        destruction.deleteSourceLifelineIndex() > destruction.coveredLifelineIndex();
    double expectedEndX =
        sourceDeclaredRightOfCovered
            ? destructionNode.x() + destructionNode.width()
            : destructionNode.x();
    assertThat(lastPoint.x())
        .describedAs(
            "m-delete must terminate on destruction-0's near edge (not short of it) for %s", model)
        .isCloseTo(expectedEndX, within(1e-6));

    double destructionCenterY = destructionNode.y() + destructionNode.height() / 2.0;
    assertThat(lastPoint.y())
        .describedAs(
            "m-delete's endpoint must land on destruction-0's vertical centre for %s", model)
        .isCloseTo(destructionCenterY, within(1e-6));
  }
}

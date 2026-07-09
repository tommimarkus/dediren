package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.quality.SequenceInvariants;
import dev.dediren.plugins.elklayout.ElkEngine;
import dev.dediren.semantics.archimate.ArchimateNotationSemantics;
import dev.dediren.semantics.graph.GraphNotationSemantics;
import dev.dediren.semantics.graph.SemanticsRouterEngine;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property test for Plan B P2 task 8: generated, engine-valid UML-sequence models are pushed
 * through the real project&#8594;layout path (the same {@link SemanticsRouterEngine} / {@link
 * ElkEngine} objects {@code EngineWiring} constructs for the CLI) and the P2-T7 sequence invariants
 * ({@link SequenceInvariants}) must hold on every generated model, not just the hand-written oracle
 * fixtures.
 *
 * <p>{@code SceneProjection} and {@code ElkLayoutEngine} named in the task are package-private to
 * their own plugin packages, so this test drives their public wrappers instead — {@link
 * SemanticsRouterEngine#projectLayoutRequest} and {@link ElkEngine#layout} — which run identical
 * validation/projection/layout logic and are the exact instances {@code EngineWiring} wires into
 * the CLI's in-memory dispatch.
 *
 * <p>The generator is intentionally minimal (Plan B P2 task 8 scope): an {@code Interaction}, 2-5
 * {@code Lifeline} nodes, and 1-10 {@code Message} relationships with unique strictly-increasing
 * {@code uml.sequence} integers between two distinct lifelines. No {@code CombinedFragment} /
 * {@code InteractionOperand} and no self-messages (same lifeline as source and target) — both are
 * out of scope for this task and would add validation/geometry complexity that isn't needed to
 * exercise the three named invariants.
 */
class SequenceLayoutPropertyTest {

  private static final List<String> MESSAGE_SORTS =
      List.of("synchCall", "asynchCall", "asynchSignal", "reply", "createMessage", "deleteMessage");

  @Property(tries = 300, seed = "1")
  void generatedSequenceModelsSatisfySequenceInvariants(
      @ForAll("validSequenceModels") GeneratedSequenceModel model) throws Exception {
    SourceDocument source =
        JsonSupport.objectMapper().readValue(buildSourceJson(model), SourceDocument.class);

    LayoutRequest request =
        new SemanticsRouterEngine(
                Map.of(
                    GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics(),
                    GenericGraphSemanticProfile.ARCHIMATE, new ArchimateNotationSemantics(),
                    GenericGraphSemanticProfile.UML, new UmlNotationSemantics()))
            .projectLayoutRequest(source, "sequence-view")
            .value();
    LayoutResult result = new ElkEngine().layout(request).value();
    LaidOutScene scene = LaidOutSceneMapper.toScene(result);

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
  }

  // --- generator --------------------------------------------------------------------------------

  @Provide
  Arbitrary<GeneratedSequenceModel> validSequenceModels() {
    return Arbitraries.integers()
        .between(2, 5)
        .flatMap(
            lifelineCount ->
                generatedMessages(lifelineCount)
                    .map(messages -> new GeneratedSequenceModel(lifelineCount, messages)));
  }

  private static Arbitrary<List<GeneratedMessage>> generatedMessages(int lifelineCount) {
    Arbitrary<Integer> sourceIndex = Arbitraries.integers().between(0, lifelineCount - 1);
    // A shift in [1, lifelineCount - 1] applied mod lifelineCount guarantees target != source
    // without relying on a filter (and its discard-ratio risk) to keep every generated message a
    // valid Lifeline -> Lifeline connection between two distinct participants.
    Arbitrary<Integer> targetShift = Arbitraries.integers().between(1, lifelineCount - 1);
    Arbitrary<String> messageSort = Arbitraries.of(MESSAGE_SORTS).injectNull(0.3);
    Arbitrary<GeneratedEndpoints> endpoints =
        Combinators.combine(sourceIndex, targetShift)
            .as(
                (source, shift) ->
                    new GeneratedEndpoints(source, (source + shift) % lifelineCount));
    Arbitrary<UnsequencedMessage> unsequenced =
        Combinators.combine(endpoints, messageSort)
            .as(
                (pair, sort) ->
                    new UnsequencedMessage(pair.sourceIndex(), pair.targetIndex(), sort));

    return unsequenced
        .list()
        .ofMinSize(1)
        .ofMaxSize(10)
        .map(
            specs -> {
              List<GeneratedMessage> messages = new ArrayList<>();
              for (int i = 0; i < specs.size(); i++) {
                UnsequencedMessage spec = specs.get(i);
                // Sequence assigned by list position: unique and strictly increasing by
                // construction, matching the task's per-message uml.sequence requirement.
                messages.add(
                    new GeneratedMessage(
                        spec.sourceIndex(), spec.targetIndex(), i + 1, spec.messageSort()));
              }
              return messages;
            });
  }

  private record GeneratedEndpoints(int sourceIndex, int targetIndex) {}

  private record UnsequencedMessage(int sourceIndex, int targetIndex, String messageSort) {}

  private record GeneratedMessage(
      int sourceLifelineIndex, int targetLifelineIndex, int sequence, String messageSort) {}

  private record GeneratedSequenceModel(int lifelineCount, List<GeneratedMessage> messages) {}

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
                      "profile": "readable",
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

  // --- extra geometry invariant: no sibling node rects overlap -----------------------------------

  /**
   * Beyond the three named {@link SequenceInvariants}, pins that no two non-nesting {@link
   * PlacedNode} rects overlap. The interaction frame is expected to enclose every lifeline (that
   * containment is what {@link SequenceInvariants#interactionFrameEnclosesLifelines} checks
   * separately), so frame-vs-lifeline pairs are excluded here; every other pair — in this
   * generator's minimal node set, every lifeline-vs-lifeline pair — must not overlap.
   */
  private static void assertNoNodeRectsOverlap(LaidOutScene scene, GeneratedSequenceModel model) {
    List<PlacedNode> nodes = scene.nodes();
    for (int i = 0; i < nodes.size(); i++) {
      for (int j = i + 1; j < nodes.size(); j++) {
        PlacedNode a = nodes.get(i);
        PlacedNode b = nodes.get(j);
        if ("interaction".equals(a.role()) || "interaction".equals(b.role())) {
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

  private static boolean rectsOverlap(PlacedNode a, PlacedNode b) {
    return a.x() < b.x() + b.width()
        && b.x() < a.x() + a.width()
        && a.y() < b.y() + b.height()
        && b.y() < a.y() + a.height();
  }
}

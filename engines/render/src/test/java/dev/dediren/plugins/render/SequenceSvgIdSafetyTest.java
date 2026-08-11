package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sequence lane's counterpart to {@link SvgIdSafetyTest}.
 *
 * <p>{@code UmlSequenceRenderer} emits an independent document, so it owns its own {@code SvgIds}
 * instance: a second minter, with a second used-id set, that no generic-lane test can reach. Every
 * other id-safety test in this module renders a generic graph — {@code SvgIdSafetyTest} and {@code
 * RenderFuzzTest} both build plain node/edge layouts against {@code default-svg.json} and never
 * reach this renderer. Sequence fixtures do get audited by {@code SvgAuditTest}, but only with
 * well-formed golden ids, which exercise nothing but the minter's byte-identity no-op path.
 *
 * <p>So without this test a forgotten {@code ids.reference(...)} in the sequence lane would let two
 * messages sharing an edge id paint one arrowhead twice — precisely the defect the minter exists to
 * prevent — with the whole suite still green.
 *
 * <p>Built by rewriting the message ids of the real sequence fixtures rather than hand-authoring
 * lifeline/message geometry, so the renderer sees a genuinely sequence-shaped input.
 */
class SequenceSvgIdSafetyTest {

  private static final String LAYOUT = "fixtures/layout-result/uml-sequence-basic.json";
  private static final String METADATA = "fixtures/render-metadata/uml-sequence-basic.json";
  private static final String POLICY = "fixtures/render-policy/uml-svg.json";

  @Test
  void duplicateAndUnsafeMessageIdsStillYieldUniqueResolvableSvgIds() throws Exception {
    // m1 and m2 collide; m3 carries the ')' that truncates a url token.
    String svg = RenderTestSupport.render(withMessageIds("dup", "dup", "a)b"));

    SvgAudit.auditStructure(svg);
    assertThat(svg)
        .contains("id=\"marker-end-dup\"")
        .contains("id=\"marker-end-dup-2\"")
        .contains("id=\"marker-end-a_b\"");
    // The colliding second message points at its own marker, not its neighbour's.
    assertThat(svg).contains("marker-end=\"url(#marker-end-dup-2)\"");
    // Nothing is left referencing the id a ')' would have truncated away.
    assertThat(svg).doesNotContain("url(#marker-end-a)");
  }

  /** The sequence fixtures with their three message ids replaced, layout and metadata in step. */
  private static ObjectNode withMessageIds(String first, String second, String third)
      throws Exception {
    String[] replacements = {first, second, third};

    ObjectNode layout = (ObjectNode) RenderTestSupport.fixtureJson(LAYOUT);
    var edges = layout.withArray("edges");
    for (int index = 0; index < edges.size(); index++) {
      ((ObjectNode) edges.get(index)).put("id", replacements[index]);
    }

    ObjectNode metadataFixture = (ObjectNode) RenderTestSupport.fixtureJson(METADATA);
    ObjectNode metadataEdges = (ObjectNode) metadataFixture.get("edges");
    ObjectNode rekeyed = JsonSupport.objectMapper().createObjectNode();
    String[] original = {"m1", "m2", "m3"};
    for (int index = 0; index < original.length; index++) {
      // A duplicate id necessarily collapses two metadata entries into one key — that is what a
      // duplicate id *means* for a map-keyed selector, and the renderer must still emit two
      // distinct markers for the two layout edges.
      rekeyed.set(replacements[index], metadataEdges.get(original[index]));
    }
    metadataFixture.set("edges", rekeyed);

    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", layout);
    input.set("render_metadata", metadataFixture);
    input.set("policy", RenderTestSupport.fixtureJson(POLICY));
    return input;
  }
}

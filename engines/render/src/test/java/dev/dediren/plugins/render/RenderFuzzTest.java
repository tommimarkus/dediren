package dev.dediren.plugins.render;

import dev.dediren.contracts.json.JsonSupport;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Property-based render fuzz: seeded random layouts (varying node/edge/group counts, positions,
 * sizes, and adversarial labels) must all render to a structurally sound SVG. Catches render-layer
 * breakage on shape combinations no hand-written fixture covers — the renderer computes its own
 * viewBox from content, so containment, id uniqueness, reference resolution, finite coordinates,
 * and textLength pins must hold for <em>every</em> valid layout, not just the curated ones.
 *
 * <p>Deterministic: a fixed seed makes CI reproducible; a failing case dumps its input so it can be
 * promoted to a named fixture.
 */
class RenderFuzzTest {

  private static final long SEED = 20260707L;
  private static final int CASES = 60;
  private static final List<String> LABELS =
      List.of(
          "Order",
          "Payment Gateway Service",
          "注文管理サービス",
          "🚀 launch",
          "x".repeat(80),
          "</text>&\"<b>bad</b>",
          "",
          "   ");

  // Ids are drawn from this list, not generated as n0/e0/g0. Ids are not merely text: the renderer
  // mints SVG identifiers and url(#...) reference targets from them (marker ids per edge, gradient
  // ids per node/group), so the charset and uniqueness of a layout-result id is load-bearing in a
  // way a label's is not. layout-result.schema.json constrains neither, so an id like the ones
  // below is contract-valid input. Chosen deliberately:
  //   "dup" twice  -- duplicate SVG ids, and an ambiguous url(#...) target
  //   "a)b"        -- the CSS url token closes at the first ')', so the reference silently misses
  //   "a b"        -- whitespace ends the url token the same way
  //   "a\"b"       -- escaped in markup, still a quote inside the url token once unescaped
  //   "<x>"        -- markup characters, escaped by SvgWriter but never sanitized as an identifier
  //   ""           -- empty; a degenerate but schema-legal id
  // Indexed rather than randomly sampled so a given case index always yields the same ids and
  // edges can reference the node ids they were built against.
  private static final List<String> IDS = List.of("dup", "dup", "a)b", "a b", "a\"b", "<x>", "");

  @Test
  void randomLayoutsAllRenderStructurallySound() throws Exception {
    Random random = new Random(SEED);
    for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
      ObjectNode input = randomLayoutInput(random);
      String svg;
      try {
        svg = RenderTestSupport.render(input);
        SvgAudit.auditStructure(svg);
      } catch (AssertionError | Exception failure) {
        throw new AssertionError(
            "fuzz case " + caseIndex + " failed for input:\n" + input.toPrettyString(), failure);
      }
    }
  }

  private ObjectNode randomLayoutInput(Random random) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "fuzz");

    int nodeCount = random.nextInt(9); // 0..8
    ArrayNode nodes = layout.putArray("nodes");
    for (int index = 0; index < nodeCount; index++) {
      ObjectNode node = nodes.addObject();
      String id = id(index);
      node.put("id", id).put("source_id", id).put("projection_id", id);
      node.put("x", random.nextInt(1000)).put("y", random.nextInt(600));
      node.put("width", 60 + random.nextInt(180)).put("height", 40 + random.nextInt(80));
      node.put("label", label(random));
    }

    ArrayNode edges = layout.putArray("edges");
    int edgeCount = nodeCount < 2 ? 0 : random.nextInt(nodeCount);
    for (int index = 0; index < edgeCount; index++) {
      String id = id(index);
      String source = id(random.nextInt(nodeCount));
      String target = id(random.nextInt(nodeCount));
      ObjectNode edge = edges.addObject();
      edge.put("id", id).put("source", source).put("target", target);
      edge.put("source_id", id).put("projection_id", id);
      ArrayNode points = edge.putArray("points");
      points.addObject().put("x", random.nextInt(1000)).put("y", random.nextInt(600));
      points.addObject().put("x", random.nextInt(1000)).put("y", random.nextInt(600));
      edge.put("label", random.nextBoolean() ? label(random) : "");
    }

    ArrayNode groups = layout.putArray("groups");
    if (nodeCount > 0 && random.nextBoolean()) {
      String id = id(nodeCount);
      ObjectNode group = groups.addObject();
      group.put("id", id).put("source_id", id).put("projection_id", id);
      group.put("x", random.nextInt(400)).put("y", random.nextInt(300));
      group.put("width", 200 + random.nextInt(400)).put("height", 150 + random.nextInt(300));
      ArrayNode members = group.putArray("members");
      members.add(id(random.nextInt(nodeCount)));
      group.put("label", label(random));
    }

    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }

  private static String label(Random random) {
    return LABELS.get(random.nextInt(LABELS.size()));
  }

  /** Stable id for a slot: same index always yields the same id, so edge endpoints stay valid. */
  private static String id(int index) {
    return IDS.get(index % IDS.size());
  }
}

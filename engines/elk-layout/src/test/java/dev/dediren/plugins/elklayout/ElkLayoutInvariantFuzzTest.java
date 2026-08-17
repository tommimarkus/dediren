package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

// lean-audit:dup-intentional — the geometry helpers below (interior intersection, outline overlap,
// perimeter membership) deliberately re-implement the same math as core's LayoutQuality and as
// ElkLayoutEngineTest's metric helpers: plugins may depend only on `contracts` (§2), so core's
// LayoutQuality is unreachable from here, and `test-support` cannot host contracts-typed helpers
// without a reactor cycle. Tracked in docs/architecture-guidelines.md §12 (LA-CODE-DUP-2).
//
// WHY THIS TEST EXISTS
// --------------------
// A routing defect was found where an edge routes *through its own source and target node bodies*.
// Two hand-written repros of the SAME defect produced completely different broken geometry: one
// left the source's bottom, rode the border ~32 px and then ran vertically through both 72 px node
// bodies; the other left the EAST side and ran flat through both endpoint interiors as one 150 px
// segment with no border ride at all. Node size, direction and grouping shape all changed the
// failure geometry, so a fix validated against a single fixture ships the other case broken.
// Example-based fixtures under-sample this space badly; this sweep covers it.
//
// The pre-existing checker in ElkLayoutEngineTest#connectorThroughNodeCount cannot see this defect
// at all: it explicitly skips an edge's own source and target nodes. That blind spot is the bug's
// hiding place, so the interior check here deliberately includes the endpoint nodes.
class ElkLayoutInvariantFuzzTest {

  // Seeded generation rather than jazzer-junit @FuzzTest: `engines/elk-layout/pom.xml` does not
  // declare the jazzer-junit test dependency (the root pom manages the version; contracts,
  // dot-import and uml-xmi-export declare it, this module does not). This follows the other
  // seeded-generative precedent in the repo, engines/render RenderFuzzTest, which is a plain
  // seeded JUnit sweep. A fixed seed keeps the run reproducible.
  private static final long SEED = 20260817L;

  // Bounded so the default `./mvnw -pl engines/elk-layout -am test` lane stays fast: 100 layouts of
  // 2..6 nodes each. Every case draws from its own Random(SEED, caseIndex) stream, so a reported
  // case index reproduces standalone without replaying the whole sweep.
  private static final int CASES = 100;

  // Reporting bounds. A broken routing rule fails many cases at once; dumping all of them buries
  // the signal, so report the first few in full and count the rest.
  private static final int MAX_REPORTED_CASES = 4;
  private static final int MAX_VIOLATIONS_PER_CASE = 4;

  // A route point is "on the perimeter" / a segment is "collinear with an outline" within this
  // tolerance. Matches the 1.0 px PORT_SIDE_EPSILON scale already used for port-side assertions in
  // ElkLayoutEngineTest, widened slightly to absorb hierarchy-crossing coordinate translation.
  private static final double TOLERANCE = 1.5;

  // A segment must clear a node's body by more than this before it counts as "inside". Same 1.5 px
  // scale: an endpoint stub touching the boundary is legal, a segment 1.5 px in is not.
  private static final double INTERIOR_INSET = 1.5;

  // ELK is configured here with SPACING_EDGE_NODE = 24 (ElkLayoutEngineTest
  // #compactDensityUsesMeasuredSpacingBaseline), so a legal route keeps 24 px clear of any node
  // body. A segment lying *on* an outline has zero clearance. This threshold is deliberately
  // generous — a third of that channel — so it flags only rides that visibly hug a border and
  // never sub-pixel endpoint contact.
  private static final double MAX_OUTLINE_RIDE = 8.0;

  // Invariant 2 (outline rides) is a KNOWN, PRE-EXISTING, UNFIXED ELK defect, not the one this test
  // class was written to catch. ELK's hierarchy-crossing routing inside a compound node emits
  // segments ~1px off a member's face -- ignoring the configured 24px SPACING_EDGE_NODE -- and it
  // reproduces byte-identically on a strictly acyclic grouped graph and on the unmodified baseline
  // engine, so nothing in this module's port-planning code causes or fixes it. This constant is a
  // CEILING, not a target: it exists only so this defect cannot silently get worse while it stays
  // unfixed, and a future reader must not read it as "this many failures is fine". Follows the
  // pinned-map precedent in core's LayoutQualityFixtureSweepTest#EXPECTED_EDGE_CROSSING_COUNTS,
  // adapted to one count because a fuzz sweep has no stable per-fixture key to pin against.
  //
  // Measured at 15 of the 100 seeded cases (26 individual ride violations across those 15 cases) on
  // seed SEED above, the same run that confirmed body crossings (invariant 1) are now zero across
  // the whole corpus. A DROP in this count is good news -- re-pin it lower, do not leave the old
  // ceiling in place. A RISE means either a regression or a newly-generated case exposing more of
  // the same defect; either way it needs a human decision, which is why the sweep fails outright
  // rather than silently tolerating drift.
  //
  // RE-PINNED 15 -> 16 on 2026-08-17, and the reason matters: the engine did not get worse, the
  // CORPUS CHANGED. Adding the unclaimed-node coin flip to the generator consumes one more draw
  // from each case's seeded stream, so every case downstream of it is a different graph and the
  // 100-case sample is not the sample that measured 15. The engine change on this branch was
  // separately confirmed not to move this number: after the boundary-reversal fix the sweep was
  // identical to before it, down to the failing case indices. Re-pinning a ceiling is only ever
  // legitimate with that kind of evidence for what moved.
  private static final int MAX_OUTLINE_RIDE_FAILING_CASES = 16;

  // CEILING for invariant 1 (body-interior crossings), scoped to BOUNDARY-CROSSING edges only -- an
  // edge with exactly one endpoint claimed by a group and the other unclaimed by any group. This is
  // the generator gap a node-ranking regression hid in: every grouped case used to claim every
  // node, so no case ever produced a boundary-crossing edge, and the fuzz suite stayed green while
  // a ranking change reversed grouped->unclaimed edges and mirrored two published diagrams. Root
  // cause per the engine author: ELK interleaves a root-level (unclaimed) node into a compound
  // group's layer span, the same ELK hierarchy-routing defect family as the outline-ride ceiling
  // above (see docs/architecture-guidelines.md §12, "ELK compound-node face-spacing") -- NOT a
  // ranking fault and NOT something port planning can fix. Non-boundary-crossing edges are judged
  // separately, by their own ceiling; see MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES below.
  //
  // This is a CEILING, not a target: a DROP is good news -- re-pin it lower, do not leave the old
  // ceiling in place. A RISE needs a human decision before re-pinning, exactly like
  // MAX_OUTLINE_RIDE_FAILING_CASES above.
  //
  // MEASURED at 0 of the 100 seeded cases on seed SEED above, in the same
  // `./mvnw -pl engines/elk-layout -am test` run that measured
  // MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES below -- this generator extension's boundary
  // crossings did not exercise this defect in this corpus. The engine author's separate 1500-case
  // sweep of cyclic grouped graphs with unclaimed nodes saw 141 boundary-crossing interior
  // crossings total (not a failing-case count, and not this corpus size), for calibration only; it
  // does not contradict this file's 0, since it is a different, much larger sample.
  //
  // MEASURED at 5 of 100, and the earlier "0" was never a measurement. The four ceiling asserts
  // run in sequence, so while invariant-1 findings still landed in the strict `hard` bucket that
  // gate threw first and every assert after it was unreachable. Splitting the buckets is what let
  // this one evaluate at all. Treat a ceiling that has never actually been reached with suspicion:
  // an assert downstream of a failing assert reports nothing, not zero.
  private static final int MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES = 5;

  // CEILING for invariant 1 (body-interior crossings), scoped to the REMAINING edges -- every edge
  // that is NOT boundary-crossing (see MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES above). Adding
  // unclaimed nodes to the generator widened the sampled graph space enough to surface a second,
  // already-known residual: two seeded cases (fuzz-59, fuzz-72; both fully grouped, zero unclaimed
  // nodes) route a segment through a node body when a group's internal axis lands PERPENDICULAR to
  // the root direction (direction LEFT or UP with two groups). This is the exact residual class
  // recorded in docs/architecture-guidelines.md §12, "Residual perpendicular-group-axis body
  // crossings -- trialled and rejected 2026-08-17": a wider 600-case sweep found 28 such crossings,
  // and a variant that pins the intra-group port axis to the root direction unconditionally removes
  // them but fails two tests pinning deliberate reading-direction intent
  // (ElkLayoutEngineTest#groupedConnectorEdgesKeepHorizontalFlowInsideVerticalGroups,
  // #groupedPipelineKeepsReadableLeftToRightFlow) and is worse on ride metrics. The maintainer's
  // recorded decision was to keep the current variant, so this is NOT a regression from the
  // generator change above -- it is sample-dependent exposure of an already-accepted gap: the old
  // 100-case sample never happened to draw a perpendicular-axis root, the new one does.
  //
  // This is a CEILING, not a target, with the same discipline as the two ceilings above: a DROP is
  // good news -- re-pin it lower. A RISE needs a human decision before re-pinning, not a silent
  // bump.
  //
  // KNOWN WEAKNESS of a count-based ceiling: it counts FAILING CASES, not distinct crossings, and
  // it has no per-shape key. A genuinely new perpendicular-axis crossing could displace an accepted
  // one and hide under the same ceiling number. §12's row above, not this integer, is the actual
  // record of what is accepted and why; this constant only stops the count from silently climbing
  // while that acceptance stands.
  //
  // MEASURED at 2 of the 100 seeded cases on seed SEED above
  // (`./mvnw -pl engines/elk-layout -am test`).
  private static final int MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES = 2;

  // Routing style is pinned ORTHOGONAL for the whole sweep. POLYLINE and SPLINE legitimately cut
  // across a node's bounding box, so the interior invariant simply does not apply to them; mixing
  // them in would force the invariant to be broadened into uselessness.
  private static final LayoutRoutingStyle ROUTING_STYLE = LayoutRoutingStyle.ORTHOGONAL;

  private static final double ORTHOGONAL_TOLERANCE = 0.5;

  /** How a generated case arranges its nodes into groups. */
  private enum GroupingShape {
    /** No groups: the flat layered path. */
    NONE,
    /** Visual-only groups: laid out flat with ELK partition bands (layoutFlatBanded). */
    VISUAL_ONLY,
    /** Semantic-boundary groups: real ELK compound nodes with hierarchy-crossing edges. */
    SEMANTIC
  }

  // Node sizes span the range that changed the observed failure geometry: a ~40 px chiclet (the
  // side-by-side flat-through-interiors case) and a ~90 px tall body (the vertical-through-bodies
  // case), plus the ordinary 150x72 box in between.
  private record NodeSize(double width, double height) {}

  private static final List<NodeSize> NODE_SIZES =
      List.of(
          new NodeSize(40.0, 40.0),
          new NodeSize(36.0, 36.0),
          new NodeSize(150.0, 72.0),
          new NodeSize(160.0, 80.0),
          new NodeSize(150.0, 90.0),
          new NodeSize(120.0, 96.0));

  private static final List<LayoutDirection> DIRECTIONS =
      List.of(
          LayoutDirection.RIGHT, LayoutDirection.DOWN, LayoutDirection.LEFT, LayoutDirection.UP);

  private static final List<LayoutDensity> DENSITIES =
      List.of(LayoutDensity.COMPACT, LayoutDensity.READABLE, LayoutDensity.SPACIOUS);

  private static final List<GroupingShape> GROUPING_SHAPES =
      List.of(GroupingShape.NONE, GroupingShape.VISUAL_ONLY, GroupingShape.SEMANTIC);

  // Endpoint merging only engages for edges that carry a relationship type
  // (ElkLayoutEngine#flatEdgeEndpointMerges), so vary both together to reach the shared-port
  // routing path as well as the one-port-per-edge path.
  private static final List<LayoutEndpointMerging> ENDPOINT_MERGING =
      List.of(LayoutEndpointMerging.OFF, LayoutEndpointMerging.AUTO);

  @Test
  void generatedSmallGraphsSatisfyRouteGeometryInvariants() {
    // One generation sweep, one shared violation collector: every case is judged by both gates
    // below from the same run, so a reported case index reproduces identically for either gate.
    List<String> reportedHard = new ArrayList<>();
    int hardFailingCases = 0;
    List<String> reportedOutlineRides = new ArrayList<>();
    int outlineRideFailingCases = 0;
    List<String> reportedBoundaryCrossingInterior = new ArrayList<>();
    int boundaryCrossingInteriorFailingCases = 0;
    List<String> reportedGroupedInterior = new ArrayList<>();
    int groupedInteriorFailingCases = 0;

    for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
      LayoutRequest request = generateRequest(caseIndex);
      Violations violations;
      try {
        LayoutResult result = new ElkLayoutEngine().layout(request);
        violations = invariantViolations(request, result);
      } catch (RuntimeException failure) {
        // A valid small request must not blow up the engine. Record it rather than aborting the
        // sweep, so one crash does not hide the geometry findings in the remaining cases.
        violations =
            new Violations(List.of("engine threw " + failure), List.of(), List.of(), List.of());
      }
      if (!violations.hard().isEmpty()) {
        hardFailingCases++;
        if (reportedHard.size() < MAX_REPORTED_CASES) {
          reportedHard.add(describeFailure(caseIndex, request, violations.hard()));
        }
      }
      if (!violations.outlineRides().isEmpty()) {
        outlineRideFailingCases++;
        if (reportedOutlineRides.size() < MAX_REPORTED_CASES) {
          reportedOutlineRides.add(describeFailure(caseIndex, request, violations.outlineRides()));
        }
      }
      if (!violations.boundaryCrossingInterior().isEmpty()) {
        boundaryCrossingInteriorFailingCases++;
        if (reportedBoundaryCrossingInterior.size() < MAX_REPORTED_CASES) {
          reportedBoundaryCrossingInterior.add(
              describeFailure(caseIndex, request, violations.boundaryCrossingInterior()));
        }
      }
      if (!violations.groupedInterior().isEmpty()) {
        groupedInteriorFailingCases++;
        if (reportedGroupedInterior.size() < MAX_REPORTED_CASES) {
          reportedGroupedInterior.add(
              describeFailure(caseIndex, request, violations.groupedInterior()));
        }
      }
    }

    // HARD GATE: perimeter (invariant 3), diagonal segments and the vacuity guard must be ZERO.
    // These are the three genuinely, universally-zero invariants -- unlike invariant 1 (body
    // crossings), which now has two known, accepted residuals of its own and is judged separately
    // below (MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES,
    // MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES).
    int hardFailingTotal = hardFailingCases;
    int outlineRideFailingTotal = outlineRideFailingCases;
    int boundaryCrossingInteriorFailingTotal = boundaryCrossingInteriorFailingCases;
    int groupedInteriorFailingTotal = groupedInteriorFailingCases;

    // These four gates are wrapped in assertAll rather than run as four sequential asserts. A
    // sequential assertTrue that fails throws immediately, so every assert after it never runs and
    // reports NOTHING -- not zero, nothing. That is exactly what happened here: while invariant-1
    // findings still landed in the (then-undivided) hard bucket, its assert threw first and the
    // boundary-crossing ceiling below it was reported as "0 held" when it had never actually been
    // evaluated. assertAll runs and collects all four before failing, so a breach in one bucket can
    // no longer mask the other three, and a sweep that breaches multiple ceilings reports all of
    // them in one run instead of being fixed and re-run once per bucket. Do not "simplify"
    // this back into four sequential asserts.
    assertAll(
        () ->
            assertTrue(
                reportedHard.isEmpty(),
                () ->
                    "ELK layout violated a hard route geometry invariant in "
                        + hardFailingTotal
                        + " of "
                        + CASES
                        + " generated cases (seed "
                        + SEED
                        + "); showing the first "
                        + reportedHard.size()
                        + ":\n"
                        + String.join("\n\n", reportedHard)),

        // CEILING, not a target: invariant 2 (outline rides) is the unfixed pre-existing ELK
        // compound-node spacing defect described at MAX_OUTLINE_RIDE_FAILING_CASES above. This
        // assert exists only to stop that defect from silently getting worse; it must never be
        // read as "this many failures is fine".
        () ->
            assertTrue(
                outlineRideFailingTotal <= MAX_OUTLINE_RIDE_FAILING_CASES,
                () ->
                    "ELK layout outline-ride ceiling exceeded: "
                        + outlineRideFailingTotal
                        + " of "
                        + CASES
                        + " generated cases (seed "
                        + SEED
                        + ") now ride a node outline, more than the pinned ceiling of "
                        + MAX_OUTLINE_RIDE_FAILING_CASES
                        + ". This is a KNOWN pre-existing ELK defect (see"
                        + " MAX_OUTLINE_RIDE_FAILING_CASES); a rise needs a human decision before"
                        + " re-pinning, not a silent bump. Showing the first "
                        + reportedOutlineRides.size()
                        + ":\n"
                        + String.join("\n\n", reportedOutlineRides)),

        // CEILING, not a target: invariant 1 (body-interior crossings), but restricted to
        // boundary-crossing edges (one endpoint grouped, the other unclaimed) -- the unfixed
        // pre-existing ELK compound-node hierarchy defect described at
        // MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES above. This assert exists only to stop the
        // boundary-crossing defect from silently getting worse; it must never be read as "this
        // many failures is fine".
        () ->
            assertTrue(
                boundaryCrossingInteriorFailingTotal
                    <= MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES,
                () ->
                    "ELK layout boundary-crossing interior-crossing ceiling exceeded: "
                        + boundaryCrossingInteriorFailingTotal
                        + " of "
                        + CASES
                        + " generated cases (seed "
                        + SEED
                        + ") now route a boundary-crossing edge through a node body, more than the"
                        + " pinned ceiling of "
                        + MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES
                        + ". This is a KNOWN pre-existing ELK defect (see"
                        + " MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES); a rise needs a human"
                        + " decision before re-pinning, not a silent bump. Showing the first "
                        + reportedBoundaryCrossingInterior.size()
                        + ":\n"
                        + String.join("\n\n", reportedBoundaryCrossingInterior)),

        // CEILING, not a target: invariant 1 (body-interior crossings) for every remaining
        // (non-boundary-crossing) edge -- the accepted perpendicular-group-axis residual described
        // at MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES above. This assert exists only to stop
        // that accepted residual from silently getting worse; it must never be read as "this many
        // failures is fine", and docs/architecture-guidelines.md §12 is the actual record of what
        // is accepted.
        () ->
            assertTrue(
                groupedInteriorFailingTotal <= MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES,
                () ->
                    "ELK layout perpendicular-group-axis interior-crossing ceiling exceeded: "
                        + groupedInteriorFailingTotal
                        + " of "
                        + CASES
                        + " generated cases (seed "
                        + SEED
                        + ") now route a non-boundary-crossing edge through a node body, more than"
                        + " the pinned ceiling of "
                        + MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES
                        + ". This is a KNOWN accepted residual (see"
                        + " MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES and"
                        + " docs/architecture-guidelines.md §12); a rise needs a human decision"
                        + " before re-pinning, not a silent bump. Showing the first "
                        + reportedGroupedInterior.size()
                        + ":\n"
                        + String.join("\n\n", reportedGroupedInterior)));
  }

  // ---------------------------------------------------------------------------------------------
  // Named regression shapes.
  //
  // These are the two hand-written repros from the defect report, pinned as concrete deterministic
  // cases so whichever of them reproduces stays reproducible without the generator. They assert
  // through exactly the same invariant checker as the sweep, so a fix cannot satisfy one and miss
  // the other.
  //
  // Honest limitation: they are reconstructed from the report's description of the geometry, not
  // copied from the original repro sources, so their exact node/edge shape is approximate.
  // ---------------------------------------------------------------------------------------------

  /**
   * Tall-node state machine with a back edge. Reported geometry: the back edge left the source's
   * bottom, rode the border ~32 px, then ran vertically straight through both 72 px node bodies.
   */
  @Test
  void tallStateMachineBackEdgeStaysOutOfItsOwnEndpointBodies() {
    List<LayoutNode> nodes = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      String id = "s" + index;
      nodes.add(new LayoutNode(id, "State " + index, id, 150.0, 72.0));
    }
    List<LayoutEdge> edges = new ArrayList<>();
    for (int index = 0; index < 7; index++) {
      String id = "t" + index;
      edges.add(new LayoutEdge(id, "s" + index, "s" + (index + 1), "next", id));
    }
    // The back edge that closes the cycle -- the one that routed through the node bodies.
    edges.add(new LayoutEdge("t-back", "s7", "s2", "retry", "t-back"));

    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "state-machine",
            nodes,
            edges,
            List.of(),
            List.of(),
            preferences(LayoutDirection.DOWN, LayoutDensity.READABLE, LayoutEndpointMerging.OFF));

    assertNoInvariantViolations(request);
  }

  /**
   * Two small nodes in one visual-only group with a two-node cycle. Reported geometry: ELK placed
   * them side by side, the back edge left the EAST side and ran flat through both endpoint
   * interiors as a single ~150 px segment, with no border ride at all. The visual-only path
   * (layoutFlatBanded) is the one that differs from the compound-group path here.
   */
  @Test
  void twoSmallGroupedNodesBackEdgeStaysOutOfItsOwnEndpointBodies() {
    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "grouped-pair",
            List.of(
                new LayoutNode("a", "A", "a", 40.0, 40.0),
                new LayoutNode("b", "B", "b", 40.0, 40.0)),
            List.of(
                new LayoutEdge("a-b", "a", "b", "forward", "a-b"),
                new LayoutEdge("b-a", "b", "a", "back", "b-a")),
            List.of(
                new LayoutGroup(
                    "pair", "Pair", List.of("a", "b"), GroupProvenance.visualOnlyGroup())),
            List.of(),
            preferences(LayoutDirection.RIGHT, LayoutDensity.READABLE, LayoutEndpointMerging.OFF));

    assertNoInvariantViolations(request);
  }

  /**
   * The exact shape that shipped mirrored: a group holding {@code art-lib}, which reaches OUT to
   * two unclaimed nodes downstream ({@code comp-cli}, {@code comp-engines}). Node ids and edge
   * labels follow the published self-model's {@code distribution} view verbatim (see {@code
   * docs/architecture/dediren.dediren/model-deployment.json}), because that is the drawing that
   * regressed: it is the README hero and the Pages site.
   *
   * <p>{@code PortPlanTest#anEdgeReachingOutOfAGroupToAnUnclaimedNodeIsNotReversed} already pins
   * the ABSTRACT decision -- {@code PortPlan#reversed} returns {@code false} for these edges. That
   * test never runs ELK and never sees a coordinate. This test complements it by running the real
   * {@link ElkLayoutEngine} and asserting on the RESULTING ROUTE GEOMETRY: with the reading
   * direction DOWN, an unclaimed target laid out ABOVE its grouped source, or a route whose last
   * point sits above its first, is exactly what "mirrored" looks like in laid-out coordinates even
   * if the abstract reversal set was computed correctly -- a defect this test would catch that
   * {@code PortPlanTest} structurally cannot.
   */
  @Test
  void edgeReachingOutOfAGroupToAnUnclaimedNodeKeepsDeclaredDirectionInLaidOutGeometry() {
    List<LayoutNode> nodes =
        List.of(
            new LayoutNode("ee-jvm", "JVM", "ee-jvm", 150.0, 72.0),
            new LayoutNode("art-launcher", "Launcher", "art-launcher", 150.0, 72.0),
            new LayoutNode("art-lib", "Lib", "art-lib", 150.0, 72.0),
            new LayoutNode("comp-cli", "CLI", "comp-cli", 150.0, 72.0),
            new LayoutNode("comp-engines", "Engines", "comp-engines", 150.0, 72.0));
    List<LayoutEdge> edges =
        List.of(
            new LayoutEdge("dep-launcher", "art-launcher", "ee-jvm", "runs on", "d1"),
            new LayoutEdge("dep-lib", "art-lib", "ee-jvm", "hosted by", "d2"),
            new LayoutEdge("man-cli", "art-lib", "comp-cli", "manifests", "m1"),
            new LayoutEdge("man-engines", "art-lib", "comp-engines", "manifests", "m2"));
    List<LayoutGroup> groups =
        List.of(
            new LayoutGroup(
                "grp-host",
                "Host",
                List.of("ee-jvm", "art-launcher", "art-lib"),
                GroupProvenance.semanticBacked("host")));

    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "distribution-boundary",
            nodes,
            edges,
            groups,
            List.of(),
            preferences(LayoutDirection.DOWN, LayoutDensity.READABLE, LayoutEndpointMerging.OFF));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    LaidOutNode artLib = nodeById(result, "art-lib");
    LaidOutNode compCli = nodeById(result, "comp-cli");
    LaidOutNode compEngines = nodeById(result, "comp-engines");

    // Relative placement: with direction DOWN, a target reached by an outgoing edge belongs BELOW
    // its source in a later layer. A mirrored drawing would place the unclaimed targets above the
    // group instead, which is exactly what the ranking regression produced.
    assertTrue(
        compCli.y() > artLib.y(),
        () ->
            "comp-cli should lay out below its grouped source art-lib (direction DOWN), but is at"
                + " y="
                + compCli.y()
                + " vs art-lib y="
                + artLib.y()
                + "; result="
                + result);
    assertTrue(
        compEngines.y() > artLib.y(),
        () ->
            "comp-engines should lay out below its grouped source art-lib (direction DOWN), but is"
                + " at y="
                + compEngines.y()
                + " vs art-lib y="
                + artLib.y()
                + "; result="
                + result);

    // Route geometry: the route itself must read top-to-bottom, source to target, not the reverse.
    for (String edgeId : List.of("man-cli", "man-engines")) {
      LaidOutEdge edge = edgeById(result, edgeId);
      List<Point> points = edge.points();
      Point first = points.get(0);
      Point last = points.get(points.size() - 1);
      assertTrue(
          last.y() > first.y(),
          () ->
              "edge "
                  + edgeId
                  + " should read top-to-bottom from its grouped source to its unclaimed target,"
                  + " but its route runs "
                  + first
                  + " -> "
                  + last
                  + "; result="
                  + result);
    }
  }

  private static LaidOutEdge edgeById(LayoutResult result, String id) {
    for (LaidOutEdge edge : result.edges()) {
      if (edge.id().equals(id)) {
        return edge;
      }
    }
    throw new AssertionError("no edge with id " + id + " in " + result);
  }

  private static void assertNoInvariantViolations(LayoutRequest request) {
    LayoutResult result = new ElkLayoutEngine().layout(request);
    List<String> violations = invariantViolations(request, result).all();
    assertTrue(
        violations.isEmpty(),
        () -> String.join("\n  ", violations) + "\n  request=" + request + "\n  result=" + result);
  }

  // ---------------------------------------------------------------------------------------------
  // Generation
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds case {@code caseIndex} from its own {@link Random} stream, so the case is reproducible
   * in isolation: re-running one reported index yields exactly the same graph regardless of how
   * many cases ran before it.
   */
  private static LayoutRequest generateRequest(int caseIndex) {
    Random random = new Random(SEED * 31L + caseIndex);

    int nodeCount = 2 + random.nextInt(5); // 2..6
    List<LayoutNode> nodes = new ArrayList<>();
    for (int index = 0; index < nodeCount; index++) {
      String id = "n" + index;
      NodeSize size = NODE_SIZES.get(random.nextInt(NODE_SIZES.size()));
      nodes.add(new LayoutNode(id, "N" + index, id, size.width(), size.height()));
    }

    // Relationship type is what arms endpoint merging; carry it on every edge of a case or none, so
    // a case exercises one merge regime cleanly rather than a mixture.
    String relationshipType = random.nextBoolean() ? "Association" : null;
    List<LayoutEdge> edges = generateEdges(random, nodeCount, relationshipType);
    List<LayoutGroup> groups =
        generateGroups(
            random, GROUPING_SHAPES.get(random.nextInt(GROUPING_SHAPES.size())), nodeCount);

    LayoutPreferences preferences =
        preferences(
            DIRECTIONS.get(random.nextInt(DIRECTIONS.size())),
            DENSITIES.get(random.nextInt(DENSITIES.size())),
            ENDPOINT_MERGING.get(random.nextInt(ENDPOINT_MERGING.size())));

    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        "fuzz-" + caseIndex,
        nodes,
        edges,
        groups,
        List.of(),
        preferences);
  }

  /**
   * Every case carries at least one cycle — the back edge in a cycle is the segment that routes
   * backwards past its own endpoints, which is where the defect lives. Self-loops are deliberately
   * never generated: a source==target edge is withheld from the ELK graph and synthesized as a
   * stem-anchored hook whose endpoints sit on a lifeline stem rather than on a node perimeter
   * (ElkLayoutEngineTest#sequenceSelfMessageIsRenderedAsAStemAnchoredHook), so it is a genuinely
   * different code path with its own dedicated tests and would need invariant 3 relaxed for
   * everyone. Excluding it keeps the invariants sharp instead.
   */
  private static List<LayoutEdge> generateEdges(
      Random random, int nodeCount, String relationshipType) {
    int cycleLength = 2 + random.nextInt(nodeCount - 1); // 2..nodeCount
    List<Integer> ring = new ArrayList<>();
    for (int index = 0; index < nodeCount; index++) {
      ring.add(index);
    }
    shuffle(random, ring);
    ring = new ArrayList<>(ring.subList(0, cycleLength));

    Set<String> seen = new LinkedHashSet<>();
    List<LayoutEdge> edges = new ArrayList<>();
    for (int index = 0; index < cycleLength; index++) {
      addEdge(edges, seen, ring.get(index), ring.get((index + 1) % cycleLength), relationshipType);
    }
    // A couple of chords, so the graph is not always a bare ring.
    int extra = random.nextInt(3);
    for (int index = 0; index < extra; index++) {
      int source = random.nextInt(nodeCount);
      int target = random.nextInt(nodeCount);
      if (source != target) {
        addEdge(edges, seen, source, target, relationshipType);
      }
    }
    return edges;
  }

  private static void addEdge(
      List<LayoutEdge> edges, Set<String> seen, int source, int target, String relationshipType) {
    String key = source + "->" + target;
    if (!seen.add(key)) {
      return;
    }
    String id = "e" + source + "_" + target;
    edges.add(new LayoutEdge(id, "n" + source, "n" + target, "", id, relationshipType));
  }

  private static List<LayoutGroup> generateGroups(
      Random random, GroupingShape shape, int nodeCount) {
    if (shape == GroupingShape.NONE) {
      return List.of();
    }
    GroupProvenance first =
        shape == GroupingShape.VISUAL_ONLY
            ? GroupProvenance.visualOnlyGroup()
            : GroupProvenance.semanticBacked("g0");
    GroupProvenance second =
        shape == GroupingShape.VISUAL_ONLY
            ? GroupProvenance.visualOnlyGroup()
            : GroupProvenance.semanticBacked("g1");

    // Leave one or more trailing nodes UNCLAIMED by any group, about half the time, whenever there
    // is room for both a non-empty grouped set and a non-empty unclaimed one. This is the fix for
    // the generator's structural blind spot: every grouped case used to claim every node, so no
    // case ever produced a boundary-crossing edge (grouped <-> unclaimed) -- exactly the shape a
    // ranking regression exploited to silently mirror two published diagrams while this suite
    // stayed green. See MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES for how those edges are
    // judged.
    int unclaimedCount = 0;
    if (nodeCount >= 3 && random.nextBoolean()) {
      unclaimedCount = 1 + random.nextInt(nodeCount - 2); // leaves >=2 grouped, >=1 unclaimed
    }
    int groupedCount = nodeCount - unclaimedCount;

    // Split the grouped nodes into two non-empty groups when there is room, otherwise put them all
    // in one. An empty group is skipped on purpose: it yields DEDIREN_ELK_EMPTY_GROUP and drops the
    // group instead of exercising a routing path. Nodes at index >= groupedCount are simply never
    // added to a group's member list, which is what leaves them unclaimed.
    if (groupedCount < 4) {
      return List.of(new LayoutGroup("g0", "G0", memberIds(0, groupedCount), first));
    }
    int split = groupedCount / 2;
    return List.of(
        new LayoutGroup("g0", "G0", memberIds(0, split), first),
        new LayoutGroup("g1", "G1", memberIds(split, groupedCount), second));
  }

  private static List<String> memberIds(int fromInclusive, int toExclusive) {
    List<String> ids = new ArrayList<>();
    for (int index = fromInclusive; index < toExclusive; index++) {
      ids.add("n" + index);
    }
    return ids;
  }

  private static LayoutPreferences preferences(
      LayoutDirection direction, LayoutDensity density, LayoutEndpointMerging merging) {
    return new LayoutPreferences(
        direction, density, null, new LayoutRoutingPreferences(ROUTING_STYLE, merging));
  }

  /** Fisher-Yates over the supplied {@link Random}, so shuffling stays inside the seeded stream. */
  private static void shuffle(Random random, List<Integer> values) {
    for (int index = values.size() - 1; index > 0; index--) {
      int swap = random.nextInt(index + 1);
      Integer held = values.get(index);
      values.set(index, values.get(swap));
      values.set(swap, held);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Invariants
  // ---------------------------------------------------------------------------------------------

  /**
   * The violations found for one request/result pair, split by gate: {@link #hard} carries ONLY
   * invariant 3 (perimeter), diagonal segments and the vacuity guard -- the three genuinely,
   * universally-zero checks, which must always be empty. {@link #outlineRides} carries invariant 2
   * alone, the pinned pre-existing-defect ceiling gate; see {@link
   * #MAX_OUTLINE_RIDE_FAILING_CASES}. Invariant 1 (body crossings) is itself split two ways, since
   * it has two distinct known, accepted residuals rather than one: {@link
   * #boundaryCrossingInterior} carries it for boundary-crossing edges (one endpoint grouped, the
   * other unclaimed by any group), its own pinned ceiling gate, see {@link
   * #MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES}; {@link #groupedInterior} carries it for every
   * remaining edge, the accepted perpendicular-group-axis residual, see {@link
   * #MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES}.
   */
  private record Violations(
      List<String> hard,
      List<String> outlineRides,
      List<String> boundaryCrossingInterior,
      List<String> groupedInterior) {
    List<String> all() {
      List<String> combined = new ArrayList<>(hard);
      combined.addAll(outlineRides);
      combined.addAll(boundaryCrossingInterior);
      combined.addAll(groupedInterior);
      return combined;
    }
  }

  private static Violations invariantViolations(LayoutRequest request, LayoutResult result) {
    List<String> hard = new ArrayList<>();
    List<String> outlineRides = new ArrayList<>();
    List<String> boundaryCrossingInterior = new ArrayList<>();
    List<String> groupedInterior = new ArrayList<>();

    // Vacuity guard, not an aesthetic invariant: if the engine silently dropped edges the geometry
    // invariants below would pass over an empty set and prove nothing. No generated request has a
    // dangling or self edge, so every requested edge must come back routed.
    if (result.edges().size() != request.edges().size()) {
      hard.add(
          "engine returned "
              + result.edges().size()
              + " routed edges for "
              + request.edges().size()
              + " requested edges; warnings="
              + result.warnings());
      return new Violations(hard, outlineRides, boundaryCrossingInterior, groupedInterior);
    }

    // Nodes claimed by ANY group, used below to classify an edge as boundary-crossing: exactly one
    // endpoint claimed, the other unclaimed. That classification is what routes an invariant-1
    // finding to the boundary-crossing ceiling instead of the grouped-interior (perpendicular-axis)
    // ceiling; neither one is the hard gate any more (see the Violations javadoc above).
    Set<String> groupedNodeIds = new LinkedHashSet<>();
    for (LayoutGroup group : request.groups()) {
      groupedNodeIds.addAll(group.members());
    }

    for (LaidOutEdge edge : result.edges()) {
      boolean boundaryCrossing =
          groupedNodeIds.contains(edge.source()) != groupedNodeIds.contains(edge.target());
      LaidOutNode source = nodeById(result, edge.source());
      LaidOutNode target = nodeById(result, edge.target());
      if (source == null || target == null) {
        hard.add(
            "edge " + edge.id() + " references a node absent from the result: " + edge.points());
        continue;
      }
      List<Point> points = edge.points();
      if (points.size() < 2) {
        hard.add("edge " + edge.id() + " has no route: points=" + points);
        continue;
      }

      // Precondition for the interior check below: it treats a segment as its axis-aligned bounding
      // box, which is exact for an axis-aligned segment and an over-approximation for a diagonal
      // one. Under ORTHOGONAL routing a diagonal is itself a defect, so report it in its own right
      // rather than letting it produce a misleading interior finding.
      for (int index = 0; index < points.size() - 1; index++) {
        Point start = points.get(index);
        Point end = points.get(index + 1);
        if (Math.abs(start.x() - end.x()) > ORTHOGONAL_TOLERANCE
            && Math.abs(start.y() - end.y()) > ORTHOGONAL_TOLERANCE) {
          hard.add(
              "edge "
                  + edge.id()
                  + " segment "
                  + index
                  + " is diagonal under orthogonal routing: "
                  + start
                  + " -> "
                  + end);
        }
      }

      // INVARIANT 1 -- no segment crosses any node's interior, INCLUDING its own source and target.
      // The endpoint nodes are the whole point: ElkLayoutEngineTest#connectorThroughNodeCount and
      // OrthogonalRouteNormalizer#clearOfUnrelatedNodes both skip them, which is precisely why an
      // edge could route straight through its own endpoints unnoticed.
      for (int index = 0; index < points.size() - 1; index++) {
        Point start = points.get(index);
        Point end = points.get(index + 1);
        for (LaidOutNode node : result.nodes()) {
          if (segmentEntersInterior(start, end, node)) {
            String ownership =
                node.id().equals(edge.source())
                    ? " (its own SOURCE)"
                    : node.id().equals(edge.target()) ? " (its own TARGET)" : "";
            String message =
                "edge "
                    + edge.id()
                    + " segment "
                    + index
                    + " "
                    + start
                    + " -> "
                    + end
                    + " crosses the body of node "
                    + node.id()
                    + ownership
                    + " "
                    + describe(node);
            // A boundary-crossing edge (one endpoint grouped, the other unclaimed) routes to its
            // own ceiling: see MAX_BOUNDARY_CROSSING_INTERIOR_FAILING_CASES for why. Every other
            // edge routes to the grouped-interior (perpendicular-axis) ceiling instead of the hard
            // gate: see MAX_PERPENDICULAR_AXIS_INTERIOR_FAILING_CASES for why body crossings are no
            // longer asserted at a strict zero across the whole corpus.
            if (boundaryCrossing) {
              boundaryCrossingInterior.add(message);
            } else {
              groupedInterior.add(message);
            }
          }
        }
      }

      // INVARIANT 2 -- no segment rides a node outline. ELK is configured for 24 px edge-node
      // clearance, so a segment lying along a border has abandoned its channel; this is the second,
      // independent defect class seen in the ~32 px border ride.
      for (int index = 0; index < points.size() - 1; index++) {
        Point start = points.get(index);
        Point end = points.get(index + 1);
        for (LaidOutNode node : result.nodes()) {
          double ride = outlineRideLength(start, end, node);
          if (ride > MAX_OUTLINE_RIDE) {
            outlineRides.add(
                "edge "
                    + edge.id()
                    + " segment "
                    + index
                    + " "
                    + start
                    + " -> "
                    + end
                    + " rides the outline of node "
                    + node.id()
                    + " for "
                    + round(ride)
                    + "px "
                    + describe(node));
          }
        }
      }

      // INVARIANT 3 -- the route starts on the source perimeter and ends on the target perimeter.
      // Holds for every generated case because self-loops are never generated (see generateEdges):
      // the synthesized self-message hook is the one shape whose endpoints legitimately sit off a
      // node perimeter.
      if (!onPerimeter(points.get(0), source)) {
        hard.add(
            "edge "
                + edge.id()
                + " starts at "
                + points.get(0)
                + ", off the perimeter of its source "
                + describe(source));
      }
      Point last = points.get(points.size() - 1);
      if (!onPerimeter(last, target)) {
        hard.add(
            "edge "
                + edge.id()
                + " ends at "
                + last
                + ", off the perimeter of its target "
                + describe(target));
      }
    }
    return new Violations(hard, outlineRides, boundaryCrossingInterior, groupedInterior);
  }

  // ---------------------------------------------------------------------------------------------
  // Geometry helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * True when the segment penetrates the node's body inset by {@link #INTERIOR_INSET} on every
   * side. Written as a strict overlap of two axis-aligned boxes, which stays correct for the
   * zero-thickness box of an axis-aligned segment: a segment lying exactly on a border, or a stub
   * leaving a port perpendicular to it, never overlaps the inset body.
   */
  private static boolean segmentEntersInterior(Point start, Point end, LaidOutNode node) {
    double left = node.x() + INTERIOR_INSET;
    double right = node.x() + node.width() - INTERIOR_INSET;
    double top = node.y() + INTERIOR_INSET;
    double bottom = node.y() + node.height() - INTERIOR_INSET;
    if (right <= left || bottom <= top) {
      // A node thinner than two insets has no interior worth speaking of.
      return false;
    }
    return Math.max(start.x(), end.x()) > left
        && Math.min(start.x(), end.x()) < right
        && Math.max(start.y(), end.y()) > top
        && Math.min(start.y(), end.y()) < bottom;
  }

  /**
   * Length for which an axis-aligned segment lies along one of the node's four outlines (collinear
   * within {@link #TOLERANCE} and overlapping that side's span). Zero when the segment is not
   * collinear with any outline, so an endpoint merely touching a border scores 0, not a short ride.
   */
  private static double outlineRideLength(Point start, Point end, LaidOutNode node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    if (Math.abs(start.y() - end.y()) <= TOLERANCE
        && (Math.abs(start.y() - top) <= TOLERANCE || Math.abs(start.y() - bottom) <= TOLERANCE)) {
      return overlapLength(start.x(), end.x(), left, right);
    }
    if (Math.abs(start.x() - end.x()) <= TOLERANCE
        && (Math.abs(start.x() - left) <= TOLERANCE || Math.abs(start.x() - right) <= TOLERANCE)) {
      return overlapLength(start.y(), end.y(), top, bottom);
    }
    return 0.0;
  }

  private static double overlapLength(
      double firstStart, double firstEnd, double secondStart, double secondEnd) {
    double firstMin = Math.min(firstStart, firstEnd);
    double firstMax = Math.max(firstStart, firstEnd);
    return Math.max(0.0, Math.min(firstMax, secondEnd) - Math.max(firstMin, secondStart));
  }

  /** True when the point lies on the node's boundary rectangle within {@link #TOLERANCE}. */
  private static boolean onPerimeter(Point point, LaidOutNode node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    boolean withinX = point.x() >= left - TOLERANCE && point.x() <= right + TOLERANCE;
    boolean withinY = point.y() >= top - TOLERANCE && point.y() <= bottom + TOLERANCE;
    boolean onVerticalSide =
        Math.abs(point.x() - left) <= TOLERANCE || Math.abs(point.x() - right) <= TOLERANCE;
    boolean onHorizontalSide =
        Math.abs(point.y() - top) <= TOLERANCE || Math.abs(point.y() - bottom) <= TOLERANCE;
    return withinX && withinY && (onVerticalSide || onHorizontalSide);
  }

  private static LaidOutNode nodeById(LayoutResult result, String id) {
    for (LaidOutNode node : result.nodes()) {
      if (node.id().equals(id)) {
        return node;
      }
    }
    return null;
  }

  private static String describe(LaidOutNode node) {
    return "["
        + round(node.x())
        + ","
        + round(node.y())
        + " "
        + round(node.width())
        + "x"
        + round(node.height())
        + "]";
  }

  private static String round(double value) {
    return String.valueOf(Math.round(value * 100.0) / 100.0);
  }

  private static String describeFailure(
      int caseIndex, LayoutRequest request, List<String> violations) {
    List<String> shown =
        violations.subList(0, Math.min(violations.size(), MAX_VIOLATIONS_PER_CASE));
    String more =
        violations.size() > shown.size()
            ? "\n  ... and " + (violations.size() - shown.size()) + " more in this case"
            : "";
    // The request is dumped whole so a finding can be promoted verbatim into a named regression
    // test next to the two above; the case index alone also reproduces it via generateRequest.
    return "case "
        + caseIndex
        + ":\n  "
        + String.join("\n  ", shown)
        + more
        + "\n  request="
        + request;
  }
}

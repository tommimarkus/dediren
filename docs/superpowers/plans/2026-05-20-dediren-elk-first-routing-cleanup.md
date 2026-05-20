# Dediren ELK-First Routing Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove or justify the remaining Java helper layout/routing code that bypasses ELK Layered configuration, so Dediren expresses layout intent through ELK graph structure, ports, hierarchy, and options before any custom implementation.

**Architecture:** Keep `dediren-plugin-elk-layout` as a contract adapter from `layout-request.schema.v1` to ELK Layered and back to `layout-result.schema.v1`. Move route-quality fixes toward ELK options and generated graph shape; delete post-ELK route-point rewriting; keep custom code only where it represents Dediren semantic graph intent that ELK cannot express directly.

**Tech Stack:** Java 25 toolchain emitting Java 21 bytecode; Eclipse ELK Layered 0.11.0; Rust workspace CLI integration tests; ignored real-ELK render tests under `crates/dediren-cli/tests/real_elk_render.rs`.

---

## Scope

This plan addresses the drift identified after reverting from ELK Layered plus Libavoid back to ELK Layered only.

Fix in scope:

- strengthen agent guidance so future agents do not add route-point post-processing first;
- add tests that fail while the helper owns post-ELK route geometry;
- configure ELK Layered ordering and straightening options before custom routing logic;
- remove the custom route normalizer and connector dogleg snapper;
- simplify grouped port ordering so it is input-order graph shaping plus ELK options, not route-specific comparator policy;
- explicitly quarantine relationship-type endpoint merging as Dediren graph intent if it remains;
- verify with helper tests and real-render route-quality tests;
- bump the shipped product/plugin patch version because `elk-layout` plugin behavior changes.

Out of scope:

- reintroducing Libavoid or another routing backend;
- adding raw ELK option names to public JSON;
- adding authored coordinates to source graph JSON;
- changing SVG render styling or ArchiMate/OEF semantics;
- broad visual redesign of existing fixtures.

## ELK-First Rule

For every layout/routing problem in this plan, apply this order:

1. Try ELK Layered options on the ELK root or group roots.
2. Try generated ELK graph structure: node order, edge order, hierarchy, ports, port sides, and port indices.
3. Add or adjust regression tests against the real render artifact.
4. Keep custom Java logic only when it represents Dediren semantic graph intent, not a replacement router.
5. Never rewrite ELK edge points after layout unless the code documents the exact ELK options and graph-shaping attempts that failed.

Useful ELK Layered options available in the current helper dependency:

```java
LayeredOptions.PORT_SORTING_STRATEGY
LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY
LayeredOptions.CONSIDER_MODEL_ORDER_PORT_MODEL_ORDER
LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING
LayeredOptions.NODE_PLACEMENT_STRATEGY
LayeredOptions.UNNECESSARY_BENDPOINTS
LayeredOptions.MERGE_EDGES
LayeredOptions.MERGE_HIERARCHY_EDGES
```

## File Structure

- Modify `AGENTS.md`
  - Keep the ELK-first rule near the top-level Start Here and Architecture Rules guidance.
- Modify `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
  - Add ELK Layered ordering and straightening options in `configureLayeredRoot`.
  - Remove calls to `straightenConnectorEndpointDoglegs` and `normalizeExcessiveRoutes`.
  - Delete the post-ELK route replacement helpers.
  - Simplify grouped port index assignment to input-order graph shaping.
  - Document any retained endpoint-merge policy as semantic graph intent.
- Modify `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
  - Add source-level guard tests against post-ELK route geometry ownership.
  - Add option mapping tests for ELK-first ordering and straightening.
  - Keep focused event-bus and junction helper tests.
- Modify `crates/dediren-cli/tests/real_elk_render.rs`
  - Keep `real_elk_renders_complex_multi_layer_system` as the visual route-quality oracle.
  - Tighten assertions only around named route regressions, not global aesthetic guesses.
- Modify version surfaces if implementation changes route behavior:
  - `Cargo.toml`
  - `Cargo.lock`
  - `fixtures/plugins/*.manifest.json`
  - `fixtures/source/*.json`
  - README bundle version examples

No README prose change is expected unless the public `layout_preferences` semantics change. The route behavior itself still requires a patch version bump because `elk-layout` plugin semantics change.

## Task 1: Keep Agent Guidance ELK-First

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/plans/2026-05-20-dediren-elk-first-routing-cleanup.md`

- [ ] **Step 1: Verify the Start Here guardrail exists**

Confirm `AGENTS.md` contains this text under `## Start Here`:

```markdown
- For ELK layout/routing changes, start from the ELK-first rule before editing
  Java: try official ELK Layered options, generated graph structure, ports,
  hierarchy, and real-render evidence before adding custom placement or route
  geometry code. Custom post-processing of ELK edge points is a last resort and
  must document which ELK options or graph-shaping attempts failed.
```

- [ ] **Step 2: Verify the Architecture Rules guardrail exists**

Confirm `AGENTS.md` contains this text under `## Architecture Rules`:

```markdown
- Before adding or extending custom Java layout/routing heuristics, add a
  failing helper or real-render test for the exact visual problem, list the ELK
  options or graph-shaping alternatives tried, and keep any remaining custom
  logic isolated as Dediren graph intent rather than route-point rewriting.
```

- [ ] **Step 3: Run the docs-only check**

Run:

```bash
git diff --check -- AGENTS.md docs/superpowers/plans/2026-05-20-dediren-elk-first-routing-cleanup.md
```

Expected: no output and exit code `0`.

- [ ] **Step 4: Commit the guidance and plan**

```bash
git add AGENTS.md docs/superpowers/plans/2026-05-20-dediren-elk-first-routing-cleanup.md
git commit -m "docs: add ELK-first routing cleanup plan"
```

## Task 2: Add ELK-First Drift Guard Tests

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`

- [ ] **Step 1: Add imports for option assertions**

Add these imports near the existing ELK imports:

```java
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.alg.layered.options.PortSortingStrategy;
```

- [ ] **Step 2: Add a failing source guard for post-ELK route geometry**

Append this test after `elkHelperBuildUsesLayeredOnly()`:

```java
    @Test
    void elkHelperDoesNotOwnPostElkRouteGeometry() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/dev/dediren/elk/ElkLayoutEngine.java"));

        assertFalse(
            source.contains("straightenConnectorEndpointDoglegs("),
            "ELK helper must not snap connector doglegs after ELK has routed edges");
        assertFalse(
            source.contains("normalizeExcessiveRoutes("),
            "ELK helper must not replace ELK routes with a custom route normalizer");
        assertFalse(
            source.contains("shortestCleanOrthogonalRoute("),
            "ELK helper must not contain a fallback orthogonal router");
        assertFalse(
            source.contains("routeIntersectsUnrelatedNode("),
            "route intersection checks belong in validation diagnostics, not route replacement");
    }
```

- [ ] **Step 3: Add a failing option mapping test**

Append this test after `layeredRootDisablesElkMergeOptionsWhenEndpointMergingIsOff()`:

```java
    @Test
    void layeredRootUsesElkFirstOrderingAndStraighteningOptions() {
        ElkNode root = ElkLayoutEngine.configuredLayeredRoot(Direction.RIGHT, null);

        assertEquals(PortSortingStrategy.INPUT_ORDER, root.getProperty(LayeredOptions.PORT_SORTING_STRATEGY));
        assertEquals(OrderingStrategy.PREFER_EDGES, root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY));
        assertEquals(true, root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_PORT_MODEL_ORDER));
        assertEquals(NodePlacementStrategy.BRANDES_KOEPF, root.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));
        assertEquals(
            EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS,
            root.getProperty(LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING));
        assertEquals(true, root.getProperty(LayeredOptions.UNNECESSARY_BENDPOINTS));
    }
```

- [ ] **Step 4: Run the failing helper test**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected before implementation: Gradle test failure in `elkHelperDoesNotOwnPostElkRouteGeometry` and `layeredRootUsesElkFirstOrderingAndStraighteningOptions`.

- [ ] **Step 5: Keep the red tests uncommitted**

Do not commit after this task. These tests intentionally fail until Tasks 3 and
4 implement the boundary. Proceed directly to Task 3 with the red tests in the
working tree.

## Task 3: Configure ELK Layered Before Custom Logic

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`

- [ ] **Step 1: Add ELK option imports**

Add these imports in `ElkLayoutEngine.java`:

```java
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.alg.layered.options.PortSortingStrategy;
```

- [ ] **Step 2: Add the ELK-first options to `configureLayeredRoot`**

In `configureLayeredRoot`, after the existing spacing properties and before the endpoint merge properties, add:

```java
        root.setProperty(LayeredOptions.PORT_SORTING_STRATEGY, PortSortingStrategy.INPUT_ORDER);
        root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.PREFER_EDGES);
        root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_PORT_MODEL_ORDER, true);
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF);
        root.setProperty(
            LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING,
            EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS);
        root.setProperty(LayeredOptions.UNNECESSARY_BENDPOINTS, true);
```

- [ ] **Step 3: Run the focused option test**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: `layeredRootUsesElkFirstOrderingAndStraighteningOptions` passes. `elkHelperDoesNotOwnPostElkRouteGeometry` still fails until Task 4.

- [ ] **Step 4: Keep the option configuration uncommitted**

Do not commit after this task. The helper still contains post-ELK route
rewriting, so the full guard should remain red until Task 4.

## Task 4: Remove Post-ELK Route Geometry Rewriting

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-cli/tests/real_elk_render.rs`

- [ ] **Step 1: Delete route post-processing calls**

In `layoutGrouped`, replace:

```java
        edges = straightenConnectorEndpointDoglegs(edges, nodes);
        edges = normalizeExcessiveRoutes(edges, nodes, groups);
```

with:

```java
        // Route geometry belongs to ELK Layered. Keep route-quality concerns in
        // ELK graph construction, ELK options, and validation diagnostics.
```

- [ ] **Step 2: Delete post-routing helper methods**

Delete these methods from `ElkLayoutEngine.java`:

```java
normalizeExcessiveRoutes
straightenConnectorEndpointDoglegs
snapSmallTerminalDogleg
replaceTerminalPoint
normalizeExcessiveRoutesOnce
hasExcessiveDetour
shortestCleanOrthogonalRoute
routeViaX
routeViaY
compactPoints
routeIntersectsUnrelatedNode
routeIntersectsUnrelatedGroup
closeParallelRouteCount
```

Delete private records or helper types used only by those methods. Keep route-quality validation helpers only if they are used to report diagnostics without modifying route points.

- [ ] **Step 3: Run the source guard**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: `elkHelperDoesNotOwnPostElkRouteGeometry` passes.

- [ ] **Step 4: Run the complex real-render route oracle**

Run:

```bash
cargo test -p dediren --test real_elk_render real_elk_renders_complex_multi_layer_system -- --ignored --exact --test-threads=1
```

Expected: the test may fail on specific event-bus, junction, detour, or close-parallel assertions. Do not reintroduce route-point rewriting. Use the failure as input to Task 5.

- [ ] **Step 5: Commit post-router removal only if the real oracle passes**

If Step 4 passes, commit the red tests, option configuration, and post-router
removal together:

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java crates/dediren-cli/tests/real_elk_render.rs
git commit -m "fix: prefer ELK layered route geometry"
```

If Step 4 fails on the named event-bus, junction, detour, or close-parallel
assertions, do not commit. Keep the changes in the working tree and continue to
Task 5.

## Task 5: Simplify Port Ordering To Input-Order Graph Shaping

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-cli/tests/real_elk_render.rs`

- [ ] **Step 1: Remove the event-bus-specific source heuristic**

Delete the `horizontalFanoutSources(...)` method and remove the `horizontalFanoutSources` variable from `layoutGrouped`, `groupedEndpointMerges`, `groupedPortCounts`, and `groupedEdgePortIndexes`.

- [ ] **Step 2: Simplify same-owner `edgeDirection`**

Change `edgeDirection` so same-owner edges only use connector-sized endpoint detection, then group direction:

```java
        if (sourceOwner != null && sourceOwner.equals(targetOwner)) {
            if (isConnectorSizedRequestNode(nodes.get(edge.source()))
                || isConnectorSizedRequestNode(nodes.get(edge.target()))) {
                return Direction.RIGHT;
            }
            return groupDirectionById.getOrDefault(sourceOwner, Direction.RIGHT);
        }
```

- [ ] **Step 3: Replace grouped port comparator policy with input order**

In `groupedEdgePortIndexes`, keep one pass over `request.edges()` in input order. For each edge, compute source and target sides from `edgeDirection`, then assign the next index for each `(nodeId, side)` pair in that same input order. Remove sorting by remote node order and remove special-case event-bus fanout ordering.

Use this local key record if it does not already exist:

```java
    private record NodeSideKey(String nodeId, PortSide side) {
    }
```

Use this index assignment structure:

```java
        Map<NodeSideKey, Integer> nextIndexByNodeSide = new HashMap<>();
        Map<String, EdgePortIndexes> portIndexesByEdge = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction = edgeDirection(
                edge,
                nodes,
                ownerByNode,
                groupDirectionById,
                groupOrderById,
                rootDirection);
            PortSide sourceSide = sourcePortSide(direction);
            PortSide targetSide = targetPortSide(direction);
            int sourceIndex = nextIndexByNodeSide.merge(
                new NodeSideKey(edge.source(), sourceSide),
                1,
                Integer::sum) - 1;
            int targetIndex = nextIndexByNodeSide.merge(
                new NodeSideKey(edge.target(), targetSide),
                1,
                Integer::sum) - 1;
            portIndexesByEdge.put(edge.id(), new EdgePortIndexes(sourceIndex, targetIndex));
        }
        return portIndexesByEdge;
```

- [ ] **Step 4: Run helper tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: helper tests pass. If a helper-local port-order assertion fails, change the assertion only if the real-render assertion in Step 5 still proves the named visual requirement.

- [ ] **Step 5: Run the real event-bus/junction route oracle**

Run:

```bash
cargo test -p dediren --test real_elk_render real_elk_renders_complex_multi_layer_system -- --ignored --exact --test-threads=1
```

Expected:

- `event-bus-to-or-junction` leaves Event Bus on the right.
- `event-bus-to-or-junction` enters `event-dispatch-or-junction` on the left.
- `event-bus-to-or-junction` source port is above `event-bus-drives-order-worker` on Event Bus.
- `or-junction-drives-email-worker` leaves the junction on the right.
- `or-junction-drives-reporting` leaves the junction on the right.
- `email-worker-notifies` leaves Email Worker from the left and below the junction-driven target port.
- `or-junction-drives-email-worker` has at most two corners.

- [ ] **Step 6: Commit port-order simplification**

If Task 4 was not committed because the real oracle failed, include the Task 2,
Task 3, Task 4, and Task 5 changes in this commit:

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java crates/dediren-cli/tests/real_elk_render.rs
git commit -m "fix: express grouped port order through ELK input order"
```

## Task 6: Quarantine Endpoint Merging As Dediren Graph Intent

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`

- [ ] **Step 1: Add the explicit boundary comment**

Add this comment above `flatEndpointMerges`:

```java
    // Endpoint merging is Dediren graph shaping, not route geometry. ELK's
    // MERGE_EDGES and MERGE_HIERARCHY_EDGES are broad booleans; Dediren keeps
    // relationship-type scoped shared ports so intentional fan-in/fan-out
    // junctions remain readable without globally merging unrelated edges.
```

- [ ] **Step 2: Keep relationship-type tests focused**

Confirm these tests still exist in `ElkLayoutEngineTest.java`:

```java
groupedFanOutDoesNotMergeDifferentRelationshipTypes
sameGroupInternalEdgesDoNotUseSharedEndpointMerge
endpointMergingOffSuppressesSharedSourceHints
```

If any are missing, add them before changing merge policy.

- [ ] **Step 3: Run endpoint merge tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: endpoint merge tests pass and no route-point post-processing guard fails.

- [ ] **Step 4: Commit endpoint merge boundary documentation**

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git commit -m "docs: mark endpoint merging as graph shaping"
```

## Task 7: Revisit Generated Node Sizing After Real Port Dimensions

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`

- [ ] **Step 1: Give generated ports a small real size**

In `createEdgePort`, replace:

```java
        port.setDimensions(0.0, 0.0);
```

with:

```java
        port.setDimensions(1.0, 1.0);
```

- [ ] **Step 2: Add a comment to `setGeneratedDimensions`**

Add this comment above `setGeneratedDimensions`:

```java
    // ELK accounts for the generated ports, but Dediren still increases the
    // minimum node side length when many fixed-order ports would otherwise be
    // packed onto the same side. This is size intent, not route geometry.
```

- [ ] **Step 3: Run the sizing test**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: `spaciousDensityExpandsGeneratedNodeForExtraPorts` still passes.

- [ ] **Step 4: Run the real render oracle**

Run:

```bash
cargo test -p dediren --test real_elk_render real_elk_renders_complex_multi_layer_system -- --ignored --exact --test-threads=1
```

Expected: route-quality assertions still pass.

- [ ] **Step 5: Commit port sizing clarification**

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git commit -m "fix: make generated port sizing explicit"
```

## Task 8: Patch Version Surfaces

**Files:**
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`
- Modify: `fixtures/plugins/uml-xmi.manifest.json`
- Modify: `fixtures/source/valid-archimate-oef.json`
- Modify: `fixtures/source/valid-basic.json`
- Modify: `fixtures/source/valid-pipeline-archimate.json`
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `fixtures/source/valid-uml-basic.json`
- Modify: `fixtures/source/valid-uml-complex.json`
- Modify: `README.md`

- [ ] **Step 1: Bump product version**

If no newer version has landed since this plan was written, bump `0.14.3` to `0.14.4` in `Cargo.toml`:

```toml
version = "0.14.4"
```

- [ ] **Step 2: Refresh the lockfile**

Run:

```bash
cargo check --workspace
```

Expected: `Cargo.lock` package entries for workspace crates use `0.14.4`.

- [ ] **Step 3: Update first-party plugin manifest versions**

Set `"version": "0.14.4"` in every file under `fixtures/plugins/*.manifest.json`.

- [ ] **Step 4: Update source fixture required plugin versions**

Set every fixture `required_plugins[].version` value to `"0.14.4"` in:

```text
fixtures/source/valid-archimate-oef.json
fixtures/source/valid-basic.json
fixtures/source/valid-pipeline-archimate.json
fixtures/source/valid-pipeline-rich.json
fixtures/source/valid-uml-basic.json
fixtures/source/valid-uml-complex.json
```

- [ ] **Step 5: Update README bundle examples**

Replace `0.14.3` with `0.14.4` in README bundle paths and smoke commands.

- [ ] **Step 6: Run version checks**

Run:

```bash
rg -n "0\\.14\\.3|0\\.14\\.4" Cargo.toml Cargo.lock README.md fixtures/plugins fixtures/source
```

Expected: no `0.14.3` matches remain in these files; `0.14.4` matches appear on all product/plugin version surfaces.

- [ ] **Step 7: Commit version surfaces**

```bash
git add Cargo.toml Cargo.lock README.md fixtures/plugins/*.manifest.json fixtures/source/valid-*.json
git commit -m "chore: bump version for ELK routing cleanup"
```

## Task 9: Full Verification And Audit Gates

**Files:**
- No source edits unless verification exposes a defect.

- [ ] **Step 1: Run formatting and docs checks**

```bash
cargo fmt --all -- --check
git diff --check
```

Expected: both pass.

- [ ] **Step 2: Rebuild and test the Java helper**

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: Gradle `clean test installDist` passes.

- [ ] **Step 3: Run ignored real helper tests**

```bash
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test -p dediren --test real_elk_render real_elk_renders_complex_multi_layer_system -- --ignored --exact --test-threads=1
cargo test -p dediren --test real_elk_render -- --ignored --test-threads=1
```

Expected: all pass. Generated SVGs remain untracked artifacts under `.test-output/renders/real-elk/`.

- [ ] **Step 4: Run workspace tests**

```bash
cargo test --workspace --locked
```

Expected: pass. If plugin-runtime file contention appears, rerun the exact failed test once, then run:

```bash
cargo test --workspace --locked -- --test-threads=1
```

- [ ] **Step 5: Run audit gates named by AGENTS.md**

Run `souroldgeezer-audit:test-quality-audit` in Deep mode over the changed ELK helper tests and real-render tests.

Expected: no block findings. Fix warn/info findings or record accepted residual risk in the handoff.

Run `souroldgeezer-audit:devsecops-audit` in Quick mode over the Java helper diff.

Expected: no block findings around process boundary, dependency, or artifact posture.

- [ ] **Step 6: Final status check**

```bash
git status --short --branch
```

Expected: only intentional commits are ahead of `origin/main`; no generated SVGs, Gradle caches, or unrelated user files are staged.

## Self-Review

- Spec coverage: The plan covers the strongest ELK-first violations: post-ELK route replacement, connector dogleg snapping, event-bus fanout heuristic, grouped port ordering, endpoint merging, generated port sizing, agent guidance, versioning, and real-render verification.
- Placeholder scan: No placeholder markers or unspecified test steps remain. Experimental uncertainty is bounded to named ELK options and named real-render assertions.
- Type consistency: Java option constants were verified against the local ELK 0.11.0 dependency with `javap`; test names and current real-render route oracle names match the live tree.

# Routing acceptance — September 2026

The v3 routing change was compared with baseline `15089f3186c268d5f7d003e102bc2159f182cfd7` using the same authored inputs. All 21 layout fixtures have zero crossings, body hits, route-overlap warnings and node-clearance warnings. The 100-case seeded native geometry sweep also passes with its former five boundary-edge and two grouped-interior failure allowances reduced to zero. The rendered corpus now includes both compact control examples and both cycle examples. All 13 local `loops-of-play` views retain zero crossings and at least 24 layout units of unrelated rectangular-node clearance.

## Measured corpus

Values are **before → after**. Length is summed route length; bends count direction changes; extent is the node-and-route bounding rectangle, excluding paint margins. Independent geometry measurements use original polylines (or bounded de Casteljau subdivision for curves), separate from the product counters. Clearance excludes each edge's own endpoint nodes. Sequence lifelines and intentional shared realizations require topology classification, as explained below. These measurements accompany actual Firefox review; they are not visual proof by themselves.

| Package / view | Crossings | Length | Bends | Minimum clearance | Extent W×H |
|---|---:|---:|---:|---:|---|
| Campaign / `between-encounters` | 0 → 0 | 4802 → 5086 | 10 → 12 | 48 → 48 | 633×1806 → 716×1856 |
| Campaign / `campaign-setup` | 0 → 0 | 1980 → 2081 | 6 → 6 | 24 → 24 | 496×1653 → 516×1704 |
| Campaign / `character-advancement` | 0 → 0 | 7225 → 6471 | 26 → 24 | 48 → 48 | 502×2378 → 567×2463 |
| Campaign / `combat-encounter-difficulty` | 0 → 0 | 1746 → 1796 | 6 → 6 | 49 → 49 | 416×1691 → 452×1742 |
| Campaign / `encounter-preparation` | 0 → 0 | 2403 → 2515 | 6 → 6 | 49 → 49 | 769×1596 → 805×1621 |
| Campaign / `loops-of-play` | 0 → 0 | 3045 → 2260 | 14 → 12 | 49 → 48 | 608×1139 → 598×1124 |
| Campaign / `material-adaptation` | 0 → 0 | 3511 → 3173 | 10 → 6 | 48 → 48 | 608×1715 → 694×1718 |
| Campaign / `material-and-improvisation` | 0 → 0 | 5697 → 6262 | 36 → 26 | 24 → 25 | 1034×1452 → 1266×1453 |
| Campaign / `order-of-combat` | 0 → 0 | 2262 → 2182 | 8 → 8 | 49 → 124 | 376×1147 → 486×1223 |
| Campaign / `outside-your-turn` | 0 → 0 | 2464 → 2829 | 4 → 4 | 72 → 72 | 736×825 → 877×850 |
| Campaign / `rhythm-of-play` | 0 → 0 | 3391 → 3457 | 14 → 14 | 24 → 24 | 555×1244 → 540×1304 |
| Campaign / `session-preparation` | 0 → 0 | 2091 → 2012 | 16 → 16 | 24 → 24 | 464×908 → 464×958 |
| Campaign / `your-turn` | 0 → 0 | 5775 → 5742 | 26 → 26 | 48 → 48 | 852×1027 → 925×1027 |
| Self / `build-pipeline` | 0 → 0 | 5664 → 5664 | 0 → 0 | 0 → 0 | 1320×554 → 1320×554 |
| Self / `distribution` | 2 → 0 | 2328 → 2537 | 6 → 8 | 48 → 109 | 904×458 → 1122×404 |
| Self / `engine-seam` | 2 → 0 | 13610 → 12140 | 28 → 28 | 49 → 123 | 2618×2424 → 2527×2448 |
| Self / `module-architecture` | 26 → 18 | 23261 → 22631 | 44 → 38 | 48 → 48 | 3032×1460 → 3497×1588 |

The extra room reserves canonical edge-label dimensions in native ELK before placement. The renderer still selects a nearby position beside an actual route run. The intermediate build exposed five constrained campaign labels; the final build has none. The remaining campaign warning is the advisory detour metric in `order-of-combat`, whose outer return encloses the next-combatant return. This nested loop is visually legible and preserves the authored cycle; the heuristic is not a shorter-route oracle.

`build-pipeline` has zero rectangular clearance because sequence messages traverse lifeline rectangles by design; actual Firefox output preserves the lifeline/message semantics. `engine-seam` has ten raw coincident pairwise runs, all contiguous shared suffixes into the same realization target. They are intentional shared topology, not ten accidental overlaps. Its former two crossings are gone. `distribution` also drops from two crossings to zero.

## Residual module crossings

The module view retains 18 crossing events in the reviewed package, down from 26. All lie in the dependency fan-out between the notation/engine-api band and the implementation band, with 48 units minimum unrelated-node clearance. Native ELK ordering is retained; there is no runtime retry, custom router, or model-order rewrite.

An exact independent two-band ordering calculation has a lower bound of 17 **only for monotone routes with these two fixed bands**. The underlying graph is planar: neither 17 nor 18 is globally unavoidable, and the current layout is not claimed optimal. Repeated calls on identical partitioned input within one JVM produced 17 or 18 after intervening allocations. Inspection of pinned ELK 0.12.0 `PartitionMidprocessor` and its `HashMultimap` over identity-based `LNode` objects supports an allocation-order explanation; this is a localized inference, not an instrumented upstream diagnosis. The remaining excess event is accepted as a pinned native-engine limitation under the approved no-second-router boundary. Revisit with an upstream fix or a separately approved change to partition intent.

Each retained event is identified below in layout coordinates. The shared reason is inversion of endpoint order across the fixed native bands described above; jumps distinguish crossings where the complete arc fits.

| Edge pair | Location (x, y) |
|---|---|
| `serv-engine-api-semantics-archimate` / `serv-uml-semantics-uml` | (1412, 869) |
| `serv-engine-api-render` / `serv-archimate-semantics-archimate` | (1077, 789) |
| `serv-engine-api-render` / `serv-uml-semantics-uml` | (1412, 789) |
| `serv-engine-api-render` / `serv-uml-uml-xmi-export` | (786, 789) |
| `serv-engine-api-render` / `serv-schema-cache-uml-xmi-export` | (744, 789) |
| `serv-engine-api-archimate-oef-export` / `serv-archimate-semantics-archimate` | (1077, 749) |
| `serv-engine-api-archimate-oef-export` / `serv-archimate-render` | (446, 749) |
| `serv-engine-api-archimate-oef-export` / `serv-uml-semantics-uml` | (1412, 749) |
| `serv-engine-api-archimate-oef-export` / `serv-uml-render` | (487, 749) |
| `serv-engine-api-archimate-oef-export` / `serv-uml-uml-xmi-export` | (786, 749) |
| `serv-engine-api-archimate-oef-export` / `serv-schema-cache-uml-xmi-export` | (744, 749) |
| `serv-engine-api-uml-xmi-export` / `serv-archimate-semantics-archimate` | (1077, 829) |
| `serv-engine-api-uml-xmi-export` / `serv-uml-semantics-uml` | (1412, 829) |
| `serv-archimate-semantics-archimate` / `serv-uml-render` | (745, 589) |
| `serv-archimate-semantics-archimate` / `serv-uml-uml-xmi-export` | (786, 589) |
| `serv-archimate-render` / `serv-schema-cache-uml-xmi-export` | (446, 669) |
| `serv-archimate-archimate-oef-export` / `serv-schema-cache-uml-xmi-export` | (405, 669) |
| `serv-uml-render` / `serv-schema-cache-uml-xmi-export` | (487, 669) |

The core `group_label_band_issue_count=24` warning counts routes traversing the module band's reserved top strip. Firefox review confirms the actual painted titles were placed in free parts of those strips. It remains a conservative layout risk signal, distinct from a rendered text collision.

## Render and export acceptance

The parent reviewed actual Firefox 146 output for all four self-model views and all 13 campaign views, plus the eight-example regression gallery. The review covered route continuity, loop direction, node clearance, nearby labels, group titles, jumps and viewport edges. Compact campaign fork/join shapes and the sibling-fan-out diamond retain their intended orientation and dimensions. Existing dense authored note text in the campaign overview notes is outside the routing change; no campaign source was edited.

The parent imported the actual draw.io export of two parallel attached edges into diagrams.net in Firefox, then dragged the target node by approximately (40, 160) pixels. Both edges retained separate attachment positions and distinct routes before and after movement. The export also has behavioral coverage for self-loops, unattached endpoints and bounded curve approximation; OEF/XMI and ASCII tests check their respective geometry and quantization behavior. OEF self-model export validated against the pinned real Open Group schema; XMI validation retains its documented UML-namespace limitation.

Raster calibration passed against the **unchanged** calibration SVG and PNG before any baseline replacement. All eight raster scenarios were then regenerated with Playwright Java 1.62.0 / Chromium 151.0.7922.34 revision 1234 and the pinned Liberation Sans bytes. No tooling-only calibration pixel change was observed. The exact CI image digest and the independent calibration gate are both retained.

## Verification and evidence

Final parent gates passed: Behavioral RED/GREEN runs preceded the production fixes; geometry's dedicated exact-cubic-bounds test and one early quality-refinement test were added after their implementation and are recorded as TDD deviations, not presented as test-first evidence. The corrected OEF obstacle witness and independent audit additions also do not claim a production RED cycle. Deep test-quality and Quick DevSecOps/source-hygiene reviews found no remaining blocker. A minor accepted coverage limit is that the compact-node follow-up directly tests outgoing binary fan-out sizing, while its symmetric two-incoming branch has no separate focused size regression.

Local evidence is retained under `.cache/routing-acceptance-2026-09-08/`: baseline and accepted packages, all 34 Firefox screenshots, independent measurements, draw.io import/movement screenshots, the native partition probe, and gate logs. These are local verification artifacts, not published assets. Generated self-model outputs and reviewed fixture goldens are tracked in the repository.

The distribution size guard was deliberately adjusted from 3,400,000 to 3,450,000 bytes after the full smoke build measured 3,410,572 bytes. The bundle still contains one shrunk jar with STORED entries; inspection confirmed only parameter locals remain in representative class attributes, consistent with the existing keep-parameter-names rule. No third-party dependency or shrink configuration changed. This small allowance covers the typed geometry and routing implementation while remaining below the documented approximately 3.49 MB attribute-stripping regression shape.

| Final gate | Result |
|---|---|
| `./mvnw -Pquality verify` | PASS — 42.045 seconds; full reactor tests, formatting and SpotBugs |
| `./mvnw -pl dist-tool -am verify -Pdist-smoke` | PASS — 28.521 seconds; actual packaged runtime smoke |
| `./scripts/test-render-paint.sh` | PASS — installer 3 tests plus 61 paint/raster tests; 1 minute 58 seconds for paint |
| Firefox corpus and draw.io movement | PASS — 17 final views plus eight regression examples; distinct parallel attachments retained |
| `git diff --check` | PASS |

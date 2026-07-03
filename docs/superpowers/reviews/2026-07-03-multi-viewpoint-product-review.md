# Multi-Viewpoint Product Review — 2026-07-03

Baseline: `main` @ `4619be2` (v2026.06.10). The spec pinned `71676a6`; the
review was re-baselined with owner consent after two behavior commits
(`c686252` uml-sequence lifeline columns, `4619be2` SVG accessible name)
landed. Release `2026.07.0` (`d53d8f3`, version strings only, 39 files
±66 lines) landed mid-review and does not change any reviewed behavior.
Spec: `docs/superpowers/specs/2026-07-03-multi-viewpoint-product-review-design.md`.

## Executive Summary

The product substance is strong: a genuinely cold-start agent completed the
full author→validate→layout→render→export scenario on the first attempt
deciding every step from stdout JSON, performance scales sub-linearly to
1000-element models, and the "Files That Move Together" discipline turned
out to be machine-enforced contract cohesion, not tribal knowledge. The
review's serious findings sit at the edges, not the core. Three are
block-severity: the export result contract is closed to first-party artifact
kinds, so a third-party export plugin can only ship by lying about its
artifact kind (PA-1); dist packaging tars whatever has accumulated in
`cli/target/appassembler/lib`, so any non-clean local build ships stale
prior-version jars — reproduced live twice during this review (SEED-1); and
the in-process transport design spec was lost with the working tree because
it was never committed (MT-6). Below those, a consistent theme: the
published documentation serves the agent-consumer well but the
plugin-author not at all, and several doc gaps burn exactly the
first-contact audience the product targets. The clearest strength worth
preserving: every failure envelope reviewers hit was structured, precise,
and agent-decidable — the error contract is the product's best feature.

## Findings Ranked by Product Impact

Verdicts: 22 qualitative findings verified by independent reproduction —
22 confirmed, 0 plausible, 0 refuted (nothing was dropped). The 6
performance findings are measurements and carry their own re-runnable
commands per the spec.

| Rank | ID | Severity | Verdict | Title | Viewpoint |
| ---- | -- | -------- | ------- | ----- | --------- |
| 1 | PA-1 | block | confirmed | Closed export-result schema: third-party export plugins cannot declare their own artifact kind | Plugin author |
| 2 | SEED-1 | block | confirmed | dist packaging is not hermetic: stale prior-version jars ship in locally built archives | Groundwork |
| 3 | MT-6 | block | confirmed | In-process transport spec lost: existed only as an untracked file, now gone | Maintainer 2027 |
| 4 | PA-3 | warn | confirmed | Documented `.dediren/plugins` project directory actually resolves against the bundle root | Plugin author |
| 5 | CS-2 | warn | confirmed | Export envelope artifact shape undocumented and inconsistent with render (silent empty extraction) | Cold-start agent |
| 6 | PA-2 | warn | confirmed | Per-operation plugin executable contract entirely undocumented (argv, stdin, cwd, env filtering) | Plugin author |
| 7 | CS-1 | warn | confirmed | Accepted ArchiMate type vocabulary documented nowhere in the bundle | Cold-start agent |
| 8 | MT-1 | warn | confirmed | model.schema.v2 is a ~30-file big-bang; dual-version support undesigned | Maintainer 2027 |
| 9 | MT-2 | warn | confirmed | Jackson is the costliest pending upgrade: 60 main-source files, two Jackson majors already on the classpath | Maintainer 2027 |
| 10 | PF-1 | warn | measurement | Fixed JVM/process spawn overhead dominates pipeline latency at realistic model sizes | Performance |
| 11 | PF-3 | warn | measurement | CDS archive contents locked by whichever command seeds them; probe-first warmup forfeits ~29% per call | Performance |
| 12 | CS-3 | warn | confirmed | Default OEF export policy stamps fixture identity into user exports | Cold-start agent |
| 13 | PA-4 | warn | confirmed | export-request schema closes `policy` to first-party shapes but the CLI passes arbitrary policy through | Plugin author |
| 14 | PA-5 | warn | confirmed | Manifest executable resolution rule for third-party manifests undocumented | Plugin author |
| 15 | PA-6 | warn | confirmed | `DEDIREN_PLUGIN_<PLUGIN_ID>` normalization for ids with dashes undocumented | Plugin author |
| 16 | MT-5 | warn | confirmed | Plan-only knowledge: Rust origin, unexplained Gradle→Maven pivot, JVM-flag rollback conditions | Maintainer 2027 |
| 17 | CS-4 | info | confirmed | Offline schema directory contents must be guessed; failure envelope when unset is excellent | Cold-start agent |
| 18 | CS-5 | info | confirmed | `validate --profile archimate` usage never shown in docs (six UML examples, zero ArchiMate) | Cold-start agent |
| 19 | CS-6 | info | confirmed | Default-policy SVG accessibility title is the view id, not the view label | Cold-start agent |
| 20 | PA-7 | info | confirmed | Plugin failure signaling and negotiation diagnostics behave well but are undocumented | Plugin author |
| 21 | PA-8 | info | confirmed | `dediren export --help` exits 2 with "Missing required options" instead of clean help | Plugin author |
| 22 | MT-3 | info | confirmed | ELK, JUnit, picocli, Java target are well-contained upgrade surfaces (preserve the adapter discipline) | Maintainer 2027 |
| 23 | MT-4 | info | confirmed | "Files That Move Together" is enforced contract discipline, not tribal knowledge (strength) | Maintainer 2027 |
| 24 | MT-7 | info | confirmed | Maintainer verdict: don't revive the transport initiative; its motivation is mitigated in-boundary | Maintainer 2027 |
| 25 | PF-2 | info | measurement | `DEDIREN_TRUST_MANIFEST_CAPABILITIES` works as documented; ~50 ms/op lever | Performance |
| 26 | PF-4 | info | measurement | Scale ceiling comfortable: sub-linear growth in every stage to 1000 nodes | Performance |
| 27 | PF-5 | info | measurement | Cold start and footprint small (19 MB tar, ~450 ms one-time CDS penalty); seeded CDS adds ~48 MB | Performance |
| 28 | PF-6 | info | measurement | Developer loop healthy: 26.1 s test suite, 51.2 s full quality gate | Performance |

## Viewpoint: Cold-Start AI Agent

Scenario completed: **yes, first attempt end-to-end**, deciding every step
from stdout envelopes only. An agent with zero prior knowledge, given only
the bundle and `docs/agent-usage.md`, authored a valid 8-node/10-relationship
TicketFlow model, laid it out, passed `validate-layout` with all quality
counts zero, rendered SVG, and produced an OEF export that independently
validates against the ArchiMate 3.1 XSD. The friction was all documentation,
not product behavior.

**CS-1 (warn, confirmed) — ArchiMate vocabulary undocumented.** The guide
says "use ArchiMate type names" but never lists them; fixtures demonstrate
~10 types, none technology-layer. The reviewer recovered the vocabulary by
grepping a render-policy styling fixture. Repro:
`grep -o '"[A-Z][A-Za-z]*"' <bundle>/fixtures/render-policy/archimate-svg.json | sort -u`.

**CS-2 (warn, confirmed) — export envelope shape inconsistent with render.**
Render artifacts live at `.data.artifacts[]` (documented, with jq example);
export puts a single artifact at `.data.artifact_kind`/`.data.content`.
Reusing the documented render extraction on a status-ok export silently
yields an empty file. Repro: run the OEF export and compare
`jq '[.data.artifacts[]?]'` (→ `[]`) with `jq '.data | keys'`.

**CS-3 (warn, confirmed) — default OEF policy stamps fixture identity.**
`fixtures/export-policy/default-oef.json` hard-codes
`id-dediren-oef-basic-model` / "Dediren OEF Basic"; the Artifact Map says
"usually reuse", so a doc-following agent ships OEF branded as the fixture
model, status ok, no diagnostic. Repro: `head -c 400` of any export made
with the default policy.

**CS-4 (info, confirmed)** — offline schema dir contents/granularity are
guesswork, but the no-network failure envelope
(`DEDIREN_OEF_SCHEMA_UNAVAILABLE`, naming both env vars) is exemplary.
**CS-5 (info, confirmed)** — six `--profile uml` examples, zero
`--profile archimate`. **CS-6 (info, confirmed)** — default-policy SVG
`<title>` is the view id (`main`), not the authored label, with no warning
diagnostic.

## Viewpoint: Community Plugin Author

Scenario completed: **yes, but only dishonestly** — the working
`ticket-stats` plugin ships with `artifact_kind` mislabeled as
`"uml-xmi+xml"` because the contract admits nothing else. The capability
handshake worked first try from the published schemas (credit:
`runtime-capability.schema.json` and the probe docs are sufficient), but
everything past the probe had to be reverse-engineered with a logging shim.

**PA-1 (block, confirmed) — closed export-result contract.** The CLI
validates a successful export envelope's `.data` against
`export-result.schema.json`: `additionalProperties: false` and an
`artifact_kind` enum closed to `["archimate-oef+xml", "uml-xmi+xml"]`. An
honest third-party `artifact_kind` or free-form data is rejected with
`DEDIREN_PLUGIN_OUTPUT_INVALID_DATA`, exit 3. The extensibility story
(explicit discovery, capability probes, protocol versioning) is contradicted
by a result contract only first-party plugins can satisfy honestly. The
error message itself quoted the exact schema violation — which is the only
reason the workaround was findable.

**PA-3 (warn, confirmed) — `.dediren/plugins` is not project-relative.**
README and the repair rules present `.dediren/plugins` as project-level
registration; in reality discovery resolves it against `DEDIREN_BUNDLE_ROOT`
(always the bundle installation root under packaged launchers). Registering
in your project fails with `DEDIREN_PLUGIN_UNKNOWN`; the same files inside
`<bundle>/.dediren/plugins` are discovered. `DEDIREN_PLUGIN_DIRS` is the
only mechanism that actually works for a project.

**PA-2 (warn, confirmed) — the executable lifecycle is undocumented.**
Learned only via shim: operations invoke `<executable> <capability-name>`
with the assembled request JSON on stdin, expect one envelope on stdout, run
the child with cwd = bundle root, and strip env to manifest `allowed_env`.
A plugin-author contract page is the single biggest missing document.

**PA-4 (warn, confirmed)** — `export-request.schema.json` closes `policy` to
the two first-party policy shapes, but the CLI forwards arbitrary policy
JSON verbatim to third-party plugins; the schema misdescribes the real
contract. **PA-5 (warn, confirmed)** — manifest `executable` resolves
against the manifest's own directory; no doc states this. **PA-6 (warn,
confirmed)** — `DEDIREN_PLUGIN_TICKET_STATS` (uppercase, dash→underscore)
works but the normalization rule is unstated. **PA-7 (info, confirmed)** —
failure signaling behaves well (error envelopes preserved verbatim, CLI exit
non-zero either way) and `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` /
`DEDIREN_PLUGIN_ID_MISMATCH` diagnostics are precise, but none of this is
documented for authors. **PA-8 (info, confirmed)** — `export --help` exits 2
complaining about missing required options.

## Viewpoint: Maintainer in 2027

All five analyses completed with evidence; every factual claim survived
independent reproduction, down to grep counts and commit fan-outs.

**MT-6 (block, confirmed) — the transport spec is lost.** The 2026-07-01
in-process transport design existed only as an untracked file and is gone
from the working tree: never committed on any ref, not recoverable from any
dangling git object. Two mitigations exist outside git, though: this review
session still holds the spec's Purpose, all six Primary Decisions, and part
of its Scope in context (readable at session start), and the agent memory
file summarizing the initiative survives — partial re-materialization is
possible **now**, but only now. Whether the deletion was deliberate is an
open question for the owner; see the synthesis.

**MT-1 (warn, confirmed) — schema v2 is an undesigned big-bang.** Exactly 30
files reference `model.schema.v1` (schema const, `ContractVersions`, 16
fixtures, 8 test files, 5 docs). Internal mechanics are safe — round-trip
tests fail loudly, three prior schema-id bumps show the recipe — but there
is no dual-read, no `migrate` subcommand, no deprecation window, and the
declared product surface is agent-authored JSON, so every downstream v1
document breaks on upgrade day.

**MT-2 (warn, confirmed) — Jackson is the costliest upgrade.** Jackson 2
(2.22.0) in 60 main-source files across 11 modules (contracts alone: 30);
Jackson 3 (3.1.4) already on the classpath transitively via the CVE-pinned
networknt validator, making every routine bump a two-stack convergence
exercise and the eventual 2→3 migration a repo-wide sweep.

**MT-5 (warn, confirmed) — plan-only knowledge.** The Rust origin
(`4933d79`), the Gradle→Maven pivot (`657c4fa`, whole message: "build:
switch to maven wrapper"; "Gradle" appears in no durable doc), the measured
justification and rollback conditions for the enforced launcher flags
(`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`), and the Tier-2/Tier-4 CDS
supersession live only in `docs/superpowers/plans/`. 486 of 530 commits are
one author.

**MT-3 (info, confirmed)** — ELK 0.11 (a 0.x API) is contained to 5 files in
one module; picocli to 1 file; JUnit 6 already current (mind the documented
ArchUnit workaround); Java floor `[21,)`. **MT-4 (info, confirmed,
strength)** — the "Files That Move Together" lists match real commit
fan-outs and are enforced by `ContractRoundTripTest`,
`AgentUsageDocConsistencyTest`, `ArchitectureRulesTest`, and `DistTool`'s
launcher-flag assertions: forget a surface, get a failing test. **MT-7
(info, confirmed)** — the maintainer would not own the transport initiative:
its motivation (JVM starts) has been attacked in-boundary by three shipped
tiers with Leyden as the documented successor, and the architecture
guidelines gate in-process migration behind a measured latency requirement.

## Viewpoint: Performance-Conscious Developer

All four measure sets completed; 77 measurements with exact commands in the
appendix. Every timed stage returned `status: ok` at every scale (at 1000
nodes, `validate-layout`'s inner quality status is `warning` —
`route_detour_count=2` — a layout-quality observation, not performance).

**PF-1 (warn) — spawn overhead dominates.** Stage wall time is nearly flat
from 10 to 1000 nodes (validate: 385→388→409 ms), so at realistic scales
almost all latency is per-invocation process overhead: a 6-stage model-100
pipeline costs ~2.5 s to move <300 KB of JSON. Plugin-backed stages cost
385–654 ms where the plugin-less `validate-layout` costs 162–176 ms on the
same inputs; the JVM floor is ~66 ms (CLI) + ~46 ms (probe).

**PF-3 (warn) — CDS seeding order matters and is sticky.** The
`AutoCreateSharedArchive`-based archive is created by whichever command runs
first and never regenerated: a probe-seeded `cli.jsa` (1.5 MB — exactly what
the docs' "Runtime Probes" first-run sequence produces) gives 385 ms
validates; reseeding with a real workload produces a 16.5 MB archive and
274 ms — ~29% faster, permanently. The documented warmup recipe locks users
into the slow archive. (Provenance note: the review bundle's archives were
probe-seeded by groundwork in the same order the docs recommend, so Set A/B
numbers carry ~110 ms/call of recoverable headroom — the finding's point.)

**PF-2 (info)** — the trust flag saves 49.5–55.4 ms/op, matching the probe
floor; real but minor. **PF-4 (info)** — growth 1000-vs-100 is sub-linear
everywhere (worst: validate-layout 2.37×, render 2.10×); render is the
absolute ceiling (952.7 ms, 340.8 MB peak RSS, 1.78 MB SVG at 1000 nodes).
**PF-5 (info)** — 19.1 MB tarball, 21.1 MB unpacked, one-time ~450 ms CDS
penalty; a fully seeded CDS cache adds ~48 MB (>2× the bundle), relevant for
disk-quota-constrained agent sandboxes. **PF-6 (info)** — `./mvnw test`
26.13 s, `./mvnw -Pquality verify` 51.16 s, both green.

## Groundwork Seed Findings

**SEED-1 (block, confirmed) — dist packaging is not hermetic.** The
packaging tars `cli/target/appassembler/lib` wholesale; Maven never removes
prior-version jars from `target/` on a version bump, so any `-Pdist-build`
not preceded by `clean` ships the residue. Observed three ways: the
pre-review local archive contained 11 stale `2026.06.9` jars (plus stale
`.jsa` files); rebuilding without `clean` reproduced all 11; and the
verifier found the lib dir already re-polluted with mixed
`2026.06.10`+`2026.07.0` jars after the mid-review release build — then
proved the mechanism by planting a fake jar that a non-clean build shipped.
Exposure: locally built archives. CI-built releases on fresh runners are
clean only by accident of environment; nothing in the packaging guards the
shipped `lib/` against its declared dependency set.

## Cross-Viewpoint Synthesis

Refuted findings dropped: **zero** — every qualitative claim survived
independent reproduction.

**Cluster 1 — the third-party plugin contract is under-published and partly
self-contradictory** (PA-1 block; PA-2..PA-7; CS-2 adjacent). Two viewpoints
converge here: the plugin author couldn't be honest (PA-1) and the
cold-start agent was silently misled by the same envelope-shape gap (CS-2).
Root cause: the published surface documents the agent-consumer perspective
only, and the request/result schemas encode first-party closed-world
assumptions. This is the highest-impact fix cluster because it blocks the
product's stated extensibility story.
→ Plan: `docs/superpowers/plans/2026-07-03-third-party-plugin-contract.md`
(needs owner decision — changes public contract surfaces).

**Cluster 2 — bundle-doc gaps that burn first-contact agents** (CS-1, CS-3,
CS-5, CS-4, CS-2's doc side, PF-3's warmup recipe). Every one is a
documentation-shaped hole around behavior that works; five of six were hit
by the actual target audience on its very first run.
→ Plan: `docs/superpowers/plans/2026-07-03-agent-usage-doc-gaps.md`.

**Cluster 3 — release/dist hermeticity** (SEED-1 block). Small fix, block
because it can silently corrupt the shipped product and recurred during the
review itself.
→ Plan: `docs/superpowers/plans/2026-07-03-dist-hermeticity.md`.

**Cluster 4 — evolution readiness** (MT-1, MT-2, MT-5). No code is wrong
today; the cost is deferred and grows. Documentation and decision-record
work, not urgent, high leverage for bus-factor 1.
→ Plan: `docs/superpowers/plans/2026-07-03-evolution-readiness-docs.md`.

**Decision point (not a plan) — the lost transport spec** (MT-6 block, MT-7,
PF-1/PF-2). Three viewpoints triangulate: the spec is gone (MT-6), the
maintainer wouldn't revive the initiative (MT-7), and the performance
baseline now provides exactly the "measured latency requirement" the
architecture guidelines demand for any future in-process decision — ~330 ms
irreducible per-stage process overhead after all three shipped mitigation
tiers, ~2.5 s per 6-stage pipeline (PF-1), with the trust flag worth only
~50 ms (PF-2). Owner options: (a) accept the loss and record the initiative
as closed in `docs/architecture-guidelines.md` §5 citing these numbers, or
(b) re-materialize the spec now from this session's context (Purpose, all
six Primary Decisions, and partial Scope are still recoverable) and commit
it as a draft. Option (b) expires with this session.

**Strengths to preserve** (explicitly reported by reviewers): the structured
failure-envelope contract (CS-4, PA-1/PA-7 notes: every error quoted precise,
actionable diagnostics — "the only reason the workaround was findable");
machine-enforced contract cohesion (MT-4); ELK adapter containment (MT-3);
sub-linear scaling and healthy dev loop (PF-4, PF-6).

## Measured Baselines (Appendix)

Baseline provenance: warm bundle with probe-seeded CDS archives (see PF-3),
Fedora Linux 7.0.14, 16 CPUs, 64 GB RAM, tmpfs /tmp, Temurin 21.0.10.
Medians of 5 runs unless stated. These are the baseline numbers for any
future plugin-transport / startup-latency decision.

| Metric | Value | Unit | Command |
| --- | --- | --- | --- |
| `A.validate.100` | 386.8 | ms | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-100.json"` |
| `A.validate.stdout.100` | 244 | bytes | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-100.json" > "$PERF/out-validate-100.json" && wc -c < "$PERF/out-validate-100.json"` |
| `A.project.100` | 403 | ms | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-100.json"` |
| `A.project.stdout.100` | 27,970 | bytes | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-100.json" > "$PERF/lr-100.json" && wc -c < "$PERF/lr-100.json"` |
| `A.layout.100` | 645.8 | ms | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-100.json"` |
| `A.layout.stdout.100` | 43,220 | bytes | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-100.json" > "$PERF/lres-100.json" && wc -c < "$PERF/lres-100.json"` |
| `A.validate-layout.100` | 172.7 | ms | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-100.json"` |
| `A.validate-layout.stdout.100` | 389 | bytes | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-100.json" \| wc -c` |
| `A.render.100` | 451.9 | ms | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-100.json"` |
| `A.render.stdout.100` | 136,269 | bytes | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-100.json" > "$PERF/out-render-100.json" && wc -c < "$PERF/out-render-100.json"` |
| `A.export.100` | 437.7 | ms | `DEDIREN_SCHEMA_CACHE_DIR=/tmp/claude-1000/dediren-review-2026-07-03/schema-cache "$BUNDLE/bin/dediren" export --plugin archimate-oef --policy "$BUNDLE/fixtures/export-policy/default-oef.json" --source "$MODELS/model-100.json" --layout "$PERF/lres-100.json"` |
| `A.export.stdout.100` | 79,701 | bytes | `DEDIREN_SCHEMA_CACHE_DIR=/tmp/claude-1000/dediren-review-2026-07-03/schema-cache "$BUNDLE/bin/dediren" export --plugin archimate-oef --policy "$BUNDLE/fixtures/export-policy/default-oef.json" --source "$MODELS/model-100.json" --layout "$PERF/lres-100.json" \| wc -c` |
| `A.jvm-floor.version` | 65.9 | ms | `"$BUNDLE/bin/dediren" --version` |
| `A.jvm-floor.plugin-capabilities` | 45.7 | ms | `"$BUNDLE/bin/dediren-plugin-generic-graph" capabilities` |
| `A.trust.validate.100` | 331.4 | ms | `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1 "$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-100.json"` |
| `A.trust.project.100` | 353 | ms | `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1 "$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-100.json"` |
| `A.trust.layout.100` | 596.3 | ms | `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1 "$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-100.json"` |
| `A.trust-delta.validate.100` | 55.4 | ms | `derived: median(A.validate.100) - median(A.trust.validate.100) = 386.8 - 331.4` |
| `A.trust-delta.project.100` | 50 | ms | `derived: median(A.project.100) - median(A.trust.project.100) = 403.0 - 353.0` |
| `A.trust-delta.layout.100` | 49.5 | ms | `derived: median(A.layout.100) - median(A.trust.layout.100) = 645.8 - 596.3` |
| `B.validate.10` | 385.2 | ms | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-10.json"` |
| `B.validate.rss.10` | 112,472,064 (112.5 MB) | bytes | `/usr/bin/time -v "$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-10.json" 2>&1 >/dev/null \| grep 'Maximum resident set size'` |
| `B.validate.stdout.10` | 242 | bytes | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-10.json" \| wc -c` |
| `B.project.10` | 393.2 | ms | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-10.json"` |
| `B.project.rss.10` | 113,078,272 (113.1 MB) | bytes | `/usr/bin/time -v <B.project.10 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.project.stdout.10` | 2,877 | bytes | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-10.json" > "$PERF/lr-10.json" && wc -c < "$PERF/lr-10.json"` |
| `B.layout.10` | 588.3 | ms | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-10.json"` |
| `B.layout.rss.10` | 114,094,080 (114.1 MB) | bytes | `/usr/bin/time -v <B.layout.10 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.layout.stdout.10` | 4,180 | bytes | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-10.json" > "$PERF/lres-10.json" && wc -c < "$PERF/lres-10.json"` |
| `B.validate-layout.10` | 162.1 | ms | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-10.json"` |
| `B.validate-layout.rss.10` | 80,637,952 (80.6 MB) | bytes | `/usr/bin/time -v <B.validate-layout.10 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.validate-layout.stdout.10` | 388 | bytes | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-10.json" \| wc -c` |
| `B.render.10` | 405 | ms | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-10.json"` |
| `B.render.rss.10` | 114,757,632 (114.8 MB) | bytes | `/usr/bin/time -v <B.render.10 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.render.stdout.10` | 14,204 | bytes | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-10.json" > "$PERF/out-render-10.json" && wc -c < "$PERF/out-render-10.json"` |
| `B.render.svg.10` | 12,972 | bytes | `jq -r '.data.artifacts[] \| select(.artifact_kind=="svg") \| .content' "$PERF/out-render-10.json" > "$PERF/diagram-10.svg" && wc -c < "$PERF/diagram-10.svg"` |
| `B.validate.100` | 388 | ms | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-100.json"` |
| `B.validate.rss.100` | 113,364,992 (113.4 MB) | bytes | `/usr/bin/time -v <B.validate.100 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.validate.stdout.100` | 244 | bytes | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-100.json" \| wc -c` |
| `B.project.100` | 399.8 | ms | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-100.json"` |
| `B.project.rss.100` | 115,572,736 (115.6 MB) | bytes | `/usr/bin/time -v <B.project.100 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.project.stdout.100` | 27,970 | bytes | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-100.json" \| wc -c` |
| `B.layout.100` | 653.6 | ms | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-100.json"` |
| `B.layout.rss.100` | 124,596,224 (124.6 MB) | bytes | `/usr/bin/time -v <B.layout.100 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.layout.stdout.100` | 43,220 | bytes | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-100.json" \| wc -c` |
| `B.validate-layout.100` | 176.1 | ms | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-100.json"` |
| `B.validate-layout.rss.100` | 83,263,488 (83.3 MB) | bytes | `/usr/bin/time -v <B.validate-layout.100 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.validate-layout.stdout.100` | 389 | bytes | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-100.json" \| wc -c` |
| `B.render.100` | 452.9 | ms | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-100.json"` |
| `B.render.rss.100` | 120,057,856 (120.1 MB) | bytes | `/usr/bin/time -v <B.render.100 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.render.stdout.100` | 136,269 | bytes | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-100.json" \| wc -c` |
| `B.render.svg.100` | 125,635 | bytes | `jq -r '.data.artifacts[] \| select(.artifact_kind=="svg") \| .content' "$PERF/out-render-100.json" > "$PERF/diagram-100.svg" && wc -c < "$PERF/diagram-100.svg"` |
| `B.validate.1000` | 409 | ms | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-1000.json"` |
| `B.validate.rss.1000` | 124,211,200 (124.2 MB) | bytes | `/usr/bin/time -v <B.validate.1000 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.validate.stdout.1000` | 246 | bytes | `"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-1000.json" \| wc -c` |
| `B.project.1000` | 454.7 | ms | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-1000.json"` |
| `B.project.rss.1000` | 137,019,392 (137.0 MB) | bytes | `/usr/bin/time -v <B.project.1000 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.project.stdout.1000` | 286,242 | bytes | `"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$MODELS/model-1000.json" > "$PERF/lr-1000.json" && wc -c < "$PERF/lr-1000.json"` |
| `B.layout.1000` | 945.9 | ms | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-1000.json"` |
| `B.layout.rss.1000` | 234,024,960 (234.0 MB) | bytes | `/usr/bin/time -v <B.layout.1000 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.layout.stdout.1000` | 457,541 | bytes | `"$BUNDLE/bin/dediren" layout --plugin elk-layout --input "$PERF/lr-1000.json" > "$PERF/lres-1000.json" && wc -c < "$PERF/lres-1000.json"` |
| `B.validate-layout.1000` | 417.1 | ms | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-1000.json"` |
| `B.validate-layout.rss.1000` | 90,804,224 (90.8 MB) | bytes | `/usr/bin/time -v <B.validate-layout.1000 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.validate-layout.stdout.1000` | 396 | bytes | `"$BUNDLE/bin/dediren" validate-layout --input "$PERF/lres-1000.json" \| wc -c` |
| `B.render.1000` | 952.7 | ms | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-1000.json"` |
| `B.render.rss.1000` | 340,783,104 (340.8 MB) | bytes | `/usr/bin/time -v <B.render.1000 command>, grep 'Maximum resident set size', max of 5 runs` |
| `B.render.stdout.1000` | 1,914,668 (1.9 MB) | bytes | `"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input "$PERF/lres-1000.json" > "$PERF/out-render-1000.json" && wc -c < "$PERF/out-render-1000.json"` |
| `B.render.svg.1000` | 1,784,892 (1.8 MB) | bytes | `jq -r '.data.artifacts[] \| select(.artifact_kind=="svg") \| .content' "$PERF/out-render-1000.json" > "$PERF/diagram-1000.svg" && wc -c < "$PERF/diagram-1000.svg"` |
| `C.tarball` | 19,072,855 (19.1 MB) | bytes | `wc -c < /home/souroldgeezer/repos/dediren/dist/dediren-agent-bundle-2026.06.10.tar.gz` |
| `C.unpacked` | 21,101,583 (21.1 MB) | bytes | `tar -xzf /home/souroldgeezer/repos/dediren/dist/dediren-agent-bundle-2026.06.10.tar.gz -C "$PERF/coldstart" && du -sb "$PERF/coldstart/dediren-agent-bundle-2026.06.10"` |
| `C.coldstart.first-call` | 715.1 | ms | `mkdir -p "$PERF/coldstart/cds-fresh"; DEDIREN_CDS_DIR="$PERF/coldstart/cds-fresh" "$PERF/coldstart/dediren-agent-bundle-2026.06.10/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-10.json"  # run 1 of 5 sequential` |
| `C.coldstart.fifth-call` | 264.6 | ms | `DEDIREN_CDS_DIR="$PERF/coldstart/cds-fresh" "$PERF/coldstart/dediren-agent-bundle-2026.06.10/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-10.json"  # run 5 of 5 sequential (all 5: 715.1, 257.2, 258.9, 260.3, 264.6)` |
| `C.cds-reseed.validate.10` | 273.9 | ms | `mkdir -p "$PERF/cds-reseed"; DEDIREN_CDS_DIR="$PERF/cds-reseed" "$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$MODELS/model-10.json"  # 6 sequential runs (586.3, 255.7, 273.9, 280.9, 258.1, 281.6); median of runs 2-6` |
| `C.cds.cli-jsa.shipped-warm` | 1,540,096 (1.5 MB) | bytes | `stat -c %s "$BUNDLE/cds/cli.jsa"` |
| `C.cds.cli-jsa.validate-seeded` | 16,551,936 (16.6 MB) | bytes | `stat -c %s "$PERF/cds-reseed/cli.jsa"` |
| `D.mvnw-test` | 26.13 | s | `cd /home/souroldgeezer/repos/dediren && /usr/bin/time -f '%e' ./mvnw test` |
| `D.mvnw-quality-verify` | 51.16 | s | `cd /home/souroldgeezer/repos/dediren && /usr/bin/time -f '%e' ./mvnw -Pquality verify` |

## Review Artifacts & Environment Caveats

- Workspace: `/tmp/claude-1000/dediren-review-2026-07-03` (clean-built
  bundle, scaled models `model-{10,100,1000}.json`, TicketFlow scenario
  artifacts under `cold-start/`, the `ticket-stats` toy plugin under
  `plugin-author/project/`, perf harness + raw runs under `perf/`,
  per-viewpoint findings JSON and verdicts under `findings/`). Nothing from
  the workspace is committed.
- The plan's original workspace `/tmp/claude/...` was unwritable (read-only
  mount despite sandbox allowlist); relocated to `/tmp/claude-1000/...` and
  substituted into all reviewer briefs.
- Reviewer sandboxes had no network; the offline schema cache was
  pre-provisioned and its location disclosed to the cold-start reviewer — a
  small, unavoidable contamination of the cold-start premise (the docs still
  had to explain the consuming env var).
- Verification was batched: three verifier agents (cold-start+seed, plugin
  author, maintainer) each reproduced findings independently per finding,
  rather than 22 single-finding agents; verifiers saw only claim fields
  (title/summary/evidence/repro).
- Subagent role isolation is prompt-enforced; both restricted reviewers
  reported respecting their boundaries and their claims reproduced under
  verification.
- Set A/B perf numbers carry ~110 ms/call recoverable headroom from CDS
  seeding order (PF-3); developer-loop timings are single runs by design.

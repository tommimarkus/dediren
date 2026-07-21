# Dediren — Future-Feature Roadmap Survey (Clean-Slate, Product-Owner View)

Status: decision record — settled 2026-07-21. This document is the survey and
the owner decisions, not an execution plan: per repo planning-policy, each
green-lit feature gets its own implementation plan (with its afternoon-scale
probe run first) before any code.

## Context

The owner asked for a product-owner exploration of future-feature avenues. An
earlier survey attempt was aborted; this one was rebuilt **from a clean
slate** — no avenue, decision, or sequencing from that attempt carries
authority here. Evidence comes only from the repo as it stood at v2026.07.22
plus general market knowledge explicitly labeled as assumption. Mid-survey the
owner added one directed deep-dive: re-examine the "no `dediren migrate`"
rule, "just in case I was wrong earlier when setting this rule."

## Method

- Three read-only evidence sweeps over the repo only: (1) current product
  surface and agent workflow, (2) spec-normative boundaries/non-goals/open
  doors, (3) recorded gaps and manual burdens. Key claims spot-verified in
  code (file:line cited where load-bearing; line numbers are as of
  v2026.07.22 and will rot).
- Three independent product-owner ideation lenses, each constrained by the
  same forbidden list and invariants: **the agent as user**, **the human
  adopter (architect/EA/platform team)**, **market strategy**.
- **Convergence across independently-prompted lenses is the prioritization
  signal.** Where two or three lenses derived the same avenue from different
  stakeholder interests, that is marked (×2)/(×3).

## The product, as evidenced today

A contract-first, single-JVM diagram **compiler for agentic tools** (Java 21+,
MIT, v2026.07.22): semantic JSON model (generic / ArchiMate 3.2 / UML 2.5.1
with 8 view kinds) → validate → project → ELK layout → layout-quality gate →
static SVG render and/or standards export (ArchiMate OEF XML, UML XMI). The
product surface is JSON schemas + CLI commands + stdout envelopes + ~70
structured diagnostics with documented repair rules — served identically over
CLI and a 3-tool stdio MCP server, byte-identical by a pinned parity gate. It
is built for redistribution inside other agent tools; all consumers are peers.

**Positioning (market lens):** the only diagram engine built as a compiler for
agents rather than an editor for humans. Best-in-world at letting a coding
agent **maintain a verifiable architecture model inside a repository** —
against Mermaid (ubiquitous sketches, no semantics), Structurizr (model/view
separation but C4-only, human-DSL-first), Archi/Sparx EA (GUI consumers an
agent cannot operate). **The wedge is not "draw a diagram"; it is "keep the
model"** — CI-gradeable, PR-diffable, exportable into tools enterprises
already trust. Do not fight Mermaid for quick sketches; own the moment a
sketch must become an asset.

## Rails every proposal respects

**Forbidden by spec** (proposable only as a consciously reopened door):
authored/seed geometry & interactive/incremental layout; third-party plugin
protocol & any engine discovery; in-process PNG/raster; interactive/scripted
SVG; network/daemon/multi-client serving (stdio MCP only); raw ELK option
passthrough; `dediren migrate` auto-rewriter & lenient auto-upgrade-on-read;
commands mutating source documents; second/parallel layout engine, post-ELK
geometry rewriting, renderer tricks masking bad layout; core-owned semantic
vocabulary.

**Invariants:** contract-first (schemas/envelopes are the product);
compatibility signaled by schema id only — a bump is big-bang and warranted
only when working documents must change; ELK-first; every command decidable
from stdout JSON alone; deterministic byte-stable outputs; CLI/MCP parity;
MCP workspace-root confinement; debug/trace-only logging; strict module DAG.

**Open doors the specs already left:** MCP resources ("additive option
later"); per-stage MCP tools; content-addressed build cache + watch (deferred,
unscheduled); `dediren_validate` widened to policy files; real-standards-schema
validation lane ("candidate follow-up, accepted 2026-07"); render backends
beyond SVG (non-raster); schema-to-Java codegen; package-manager publishing;
contract cleanup (orphaned schemas, unenforced `required_plugins[]`).

---

## Avenue map

### Theme 1 — Contract bedrock *(small, trust-repairing; do first)*

**1.1 Close the stdout envelope gap — S** *(agent lens's "fund only one")*
Structural failures (missing `plugins.generic-graph`, unknown view,
unsupported target in `validate --plugin`/`project`) today bypass the envelope:
cause on stderr, stdout **empty**, exit 2 — verified at `cli/.../Main.java:579`
(`printStructuralFailure`) while envelope-on-failure writers exist at `:563`.
One silent-on-stdout failure class forces defensive stderr-scraping around all
eight commands. Shape: route the three classes through the existing envelope
writers with error diagnostics (new codes or reused), keep exit 2, extend the
CLI/MCP parity gate with fixtures for all three. Wiring, not architecture.
Risk: consumers keyed on "empty stdout + exit 2" — release-note it.
Probe: write the parity fixtures first; failing tests size the blast radius.

**1.2 Placeholder-identity tripwire + policy validation — S (×3)**
Shipped default export policies hard-code fixture identity
(`fixtures/export-policy/default-oef.json` — including a wrong
`viewpoint: "Application Cooperation"` claim) and export **succeeds** silently.
Shape: (a) warning diagnostic `DEDIREN_EXPORT_IDENTITY_PLACEHOLDER` from
`export`/`build` when identity fields equal shipped defaults (optional
`--strict-identity` escalation); (b) widen `validate`/`dediren_validate` to
policy documents, dispatching on schema id — a pre-legitimized open door.
Pure diagnostics; fixture-namespaced values make false positives implausible.
"Highest prevented-defect-per-line item" — agent lens. The adopter framing:
a deliverable introducing itself to the EA team as "Dediren OEF Basic" undoes
every other trust investment.

**1.3 Contract truthfulness sweep — S**
The schema is ground truth agents generate handling code from; where it lies,
trust dies. `schemas/render-result.schema.json` still allows `html`/`base64`
though both are retired; two orphaned schemas (`plugin-manifest`,
`runtime-capability`) await deletion; `required_plugins[]` is
informational-only. Shape: render-result vNext drops dead values (output
envelope — no working-document migration cost); delete orphaned schemas.
**Trap flagged by agent lens:** removing `required_plugins[]` from the model
schema WOULD force working documents to change — big-bang territory; document
it as dead now, fold removal into the next already-warranted model bump only.

**1.4 MCP resources + machine-readable discovery — S/M (×2)**
Discovery today is prose-only (`dediren_guide`, 23 topics). Shape: serve the
bundle's actual bytes as read-only MCP resources — `dediren://schema/<family>`
(17 schemas), `dediren://fixture/<path>`, `dediren://policy/default-*`,
`dediren://guide/<topic>`, plus a dist-build-generated diagnostics catalog
(`{code, severity, summary, repair_rule}`). Serving the shipped files is the
anti-drift strategy; confinement and `--read-only` untouched. Every later
feature ships self-describing. (Capability discovery, if added, is a fresh
design — the orphaned `runtime-capability` schema stays scheduled for
deletion, not revival.)

*Cross-cutting quick win:* repeated `--target` on `project`
(`--target layout-request --target render-metadata` in one call) kills the
documented UML double-pass — smallest piece of decomposed-mode ergonomics;
`--artifact-out` on decomposed commands and per-stage MCP tools wait for
transcript evidence of decomposed-mode frequency, and any MCP surface widening
should first pay the recorded mcp-server branch-coverage debt (0.67 < 0.70).

### Theme 2 — Model intelligence & artifact trust *(the platform bet)*

**2.1 `dediren diff` + fixed impact queries — M (×2; market lens's #1)**
Determinism is the rarest asset and it is currently invisible — nothing
converts byte-stable semantics into a recurring human-visible moment.
Comparing two model revisions is raw-JSON work today. Shape:
`dediren diff <old> <new>` emitting an envelope of change records keyed on
stable ids (elements/relationships/views, property-level detail, profile-aware
type names; optional derived markdown summary for PR bots), and
`dediren query --kind dependents|orphans|view-coverage` — a **fixed query
vocabulary, not a query language** (resist Cypher-envy). v1 same-schema-id
only; read-only; deterministic ordering; MCP twins later under the per-stage
door. Explicitly **not** a merge tool (merge drifts toward source mutation).
This is the retention mechanism that makes the kept model load-bearing:
every PR becomes an advertisement. Risk: scope creep — cap at 3-4 query kinds.
Probe: 50-line jq prototype over two fixture revisions pasted into a mock PR
("would you approve on this evidence?"); publish the manual recipe as a guide
topic and watch for native-command demand.

**2.2 Artifact trust chain: provenance + `verify` + `status` — M** *(adopter
lens's "fund only one"; novel to this survey)*
Artifacts circulate with no provenance; "is this SVG current?" is answered by
eyeballing. Shape: (a) writers embed a deterministic provenance block — model
schema id, canonical content hash, view id, policy hash, tool version, **no
timestamps** (byte-stability preserved) — as inert SVG `<metadata>` (no
script/style; "static, inert SVG" holds) and XML comment/vendor extension in
OEF/XMI; (b) `dediren verify <model> --artifacts <out>` recomputes and emits
ARTIFACT_CURRENT / STALE / UNSTAMPED diagnostics, exit-decidable; (c)
`dediren status --root <dir>` one-shot workspace freshness index (no watch, no
daemon). Converts release-grade supply-chain trust (SBOM/attestations) into
per-artifact trust, and enables the CI gate "diagrams may not be stale on
main." The canonical-hash substrate is exactly what the deferred
content-addressed cache and 2.1 reuse — the spend compounds. Risk: the hash
definition becomes a mini-contract; stamps must survive external converters.
Probe (an afternoon): hand-stamp a fixture SVG, push through
rsvg-convert/Inkscape and an Archi import; prototype the canonical hash over
fixtures.

### Theme 3 — Interchange completion *(the enterprise bridge)*

**3.1 Whole-model export + per-view identity — M(OEF)→L(XMI) (×3)**
Exports are single-view (rest of model disclosed via `*_OMITTED` info
diagnostics); OEF identity is per-build ("Phase-1 limitation") so multi-view
builds emit N files with colliding identity. Shape: `export --scope model`
(default stays `view` — zero behavior change) emitting one OEF with all views
(OEF natively supports multi-diagram models), later whole-model XMI;
`oef-export-policy` gains an **additive-optional** per-view identity map —
additive-optional forces no working-document change, so no schema-id bump
(agent-lens reading; if identity-derivation precedence changes defaults, THAT
is bump territory — decide in the feature plan). Reuses per-view ELK results
verbatim (no second layout, no geometry rewriting). OEF first; whole-model
XMI collides with per-family deferred constructs, so it trails.
Probe: hand-merge two single-view OEF outputs, import into Archi; if the merge
is mechanical, the feature is serialization plumbing. Also import today's N
files and put the breakage writeup in front of two practicing EA users.

**3.2 Real-standards validation lane — M (×2; pre-accepted 2026-07)**
Today: OEF validates against ArchiMate 3.1 stubs-or-supplied XSDs, no OMG UML
XSD exists, and `xmllint`/`curl` are external dependencies that silently
degrade validation to "skipped" (`*_VALIDATOR_UNAVAILABLE`) — worst-case false
assurance, and sandboxed agents (the actual target user) are exactly who lacks
those binaries. Shape: in-JVM `javax.xml.validation` (removes xmllint from the
trust path); adopter-populated, checksum-pinned schema-cache layout — dediren
never fetches (network invariant intact; diagnostics name exactly which file
to place where); a conformance-report diagnostic stating precisely what was
validated against what; for UML, honest labeling + a Papyrus/EA import-smoke
recipe, since no normative XSD exists. Risks: Open Group XSD redistribution
licensing (solved by user-populated cache, never bundling); JDK validator vs
XSD 1.1 features. Probe (an afternoon): run the ArchiMate 3.1 diagram XSD set
through `javax.xml.validation` against an existing OEF fixture output.

**3.3 UMLDI diagram interchange — L, later, evidence-gated**
Exported XMI is model-only; layout dies on import — no workaround exists.
Legality argued precisely: serializing **ELK-computed** geometry into
UMLDI/DI/DC sections is neither authored geometry nor post-ELK rewriting — it
is a second derived syntax for the same layout result. One family at a time
(class first); target Eclipse Papyrus conformance, treat Sparx EA as
smoke-tested best-effort (DI dialect variance is the risk).
Probe **before any commitment**: hand-author a minimal class-diagram DI
fragment for one fixture; import into Papyrus and EA; measure what renders.

### Theme 4 — Reach & adoption *(PARKED by owner decision 2026-07-21 — retained for the record; revisit demand-led)*

**4.1 Mermaid/PlantUML lift kits — S** *(market lens; the no-parser reframe)*
The migration story for the ubiquitous sketch corpus — **without building
parsers**: the translating agent is the importer; dediren's validate-repair
loop is the convergence guarantee; a lift's success criterion is "passes
validate." Shape: guide topics + fixture pairs (Mermaid/PlantUML source →
expected model) any consumer can follow; output is always a new derived model
file. A native `lift` command only if agent-translation quality plateaus.
Explicitly not Mermaid *compatibility* — the value is the upgrade, not parity;
a grammar-chasing parser is the wrong maintenance bet. Risk: lift quality
blamed on dediren — publish the validate gate as the definition of success.
Probe: 20 real-world Mermaid diagrams lifted by an agent using only the guide;
measure validate pass rate within k repair iterations.

**4.2 CI & distribution reach — M (×2)**
Java-21 + hand-downloaded Linux tar.xz filters the funnel before evaluation
starts; CI is where humans meet dediren without choosing it. Shape: one GHCR
OCI image (packaging, **not serving** — entrypoint execs the one-shot CLI /
stdio MCP; no ports, ever) with the same SBOM/attestation rigor extended to
the image digest; one thin GitHub Action unpacking the attested bundle;
SDKMAN first among package managers (cheapest JVM-native channel, points at
existing release); **cross-OS CI lanes running the fixture suite — byte-stable
envelopes across OSes is a determinism claim worth proving, not assuming.**
Risk: distribution-surface maintenance on a small team — exactly one image,
one action, manifests generated from the release pipeline.
Probe: publish copy-paste workflow YAML + Dockerfile as docs only; issues
about a doc are the cheapest demand signal that exists.

**4.3 Ungate the built ELK algorithms — S-M each, opportunistic**
`tree/radial/force/stress` are already built and enum-ready behind a dormant,
tested compatibility gate; only the public accepted-set blocks them. "It can't
do a radial landscape map" is a cheap bounce reason. Shape: ungate **one
algorithm at a time, each with its own tuned layout-quality-gate profile** —
never ungate without the metrics story; the gate is the differentiator.
Probe: run gated algorithms over the existing fixture corpus, publish the
metric comparison, ungate whichever clears thresholds with least tuning.
(Aligns with the recorded ELK deferrals; node `layer_choice` /
`position_choice` / priority hints remain the legal answer to "let me fix the
layout myself" — see tempting-but-wrong.)

**4.4 Dev-portal (Backstage/TechDocs) recipe — S, docs-only, deliberately
last** — downstream of the CI story; one blog-style doc, revisit on inbound.

---

## Owner-directed re-examination — the "no `dediren migrate`" rule

Requested mid-survey by the owner ("just in case I was wrong earlier when
setting this rule"). Rule source:
`docs/superpowers/specs/2026-07-14-dediren-schema-migration-design.md`.

**The standing rule is two decisions of different kinds:**

- **Decision 5 — no lenient / auto-upgrade-on-read.** "Silently accepting a
  stale file is the defect being removed." *Principled; stands under every
  outcome below.* The loud gate is the product.
- **Decision 2 — no `dediren migrate` auto-rewriter; "documentation is the
  migration tool."* A cost/benefit judgment* on 2026-07-14 facts: "a rewriter
  is a large amount of code to save an agent an edit it is already good at."

**Verdict on "was I wrong": mostly no.** Decision 5 is untouchable. Decision
2's *ban on a rewriter* also survives re-examination — but its *delivery
mechanism* ("prose is the migration tool") is the part that no longer holds
up, and all three lenses converged on the same correction.

**What shifted since 2026-07-14:** the registry + `MigrationRegistryTest` +
Files-That-Move-Together discipline now maintain migration knowledge as
structured, test-pinned data — only its delivery is prose; the entire shipped
corpus (3 render-policy steps) is mechanical rename/delete ops; "the agent
already has the file open" is consumer-specific (CI jobs and stranded humans
don't); and `model.schema` is still v1 — the highest-stakes migration (the
first model bump, touching the user's largest hand-authored asset) is still
ahead, which is when executable steps matter most.

**Shapes considered:**
- **A. Full `dediren migrate`** emitting a *derived* upgraded document (never
  mutating input, self-validating, warning-residue for non-mechanical steps).
  Legal on its face — but the agent lens surfaced the trap: the agent
  immediately copies the emitted document over the source, making dediren a
  **de facto rewriter of record** and dissolving the review discipline the
  big-bang philosophy exists to force. The tool's diff replaces the agent's
  diff. (Initial single-viewpoint analysis favored A; the survey moves off it
  for this reason plus the forever transform-engine tax.)
- **B. Machine-readable migration plan — recommended (×2 lenses + fits the
  owner's original instinct).** On `DEDIREN_SCHEMA_VERSION_OUTDATED`, the
  envelope (and/or an MCP resource / read-only plan surface) carries the
  documented steps as **structured operations** (RFC-6902-style:
  `rename_field` / `remove_key` / `set_version`, JSON-Pointer targets) instead
  of prose pointers. dediren never applies them; the file still fails until
  migrated; the agent remains the hands and the diff remains the agent's diff.
  Authored once per bump by the party that defined the bump, fixture-pinned
  (stale doc + expected outcome) in CI. Kills the prose-transcription error
  class at ~zero philosophical cost. Decision 2 is *amended* (prose → data),
  not reversed. Cost S-M + small per-bump authoring obligation.
- **C. Ban stands, made evidence-gated.** Record explicit reopen triggers.
  Cost 0; keeps prose.

**Outcome: B adopted (owner, 2026-07-21), with escalation triggers recorded
in the spec amendment:** revisit A only if (i) a future bump's steps cannot be
expressed as mechanical operations, or (ii) the first `model.schema` bump is
contemplated — decided then with the evidence in hand. The dated amendment
lives in the schema-migration spec so the record shows the judgment was
re-made, not forgotten.

---

## Tempting-but-wrong (all lenses, consolidated)

- **Hosted/interactive viewer or stakeholder portal** — forbidden twice over
  (scripted SVG retired; no serving), moves the fight onto Mermaid's turf, and
  converts compiler discipline into ops burden. The legal 80%: provenance-
  stamped static artifacts + whole-model interchange into tools that own
  interactivity. Presentation layers belong to consumers.
- **Plugin marketplace / bring-your-own-engine** — forbidden, and rightly:
  the moat *is* the curated single contract (one diagnostics vocabulary, one
  quality gate, byte-stable outputs). The ecosystem grows *around* the
  redistributable bundle, not *inside* the JVM.
- **In-process PNG/PDF** — forbidden; a native raster stack bloats the
  attested SBOM, adds CVE surface, threatens determinism. Instead: make
  provenance stamps survive external converters; bless one converter recipe
  per platform.
- **Resident daemon to amortize JVM start** — forbidden; state across calls
  kills determinism and muddies confinement. The legal answer to latency is
  fewer calls (whole-model export, repeated `--target`), plus the already-open
  AOT/jlink doors.
- **Raw ELK passthrough** — forbidden; welds the contract to one engine's
  namespace. The legal answer to "let me fix the layout" is growing the intent
  vocabulary (deferred `layer_choice`/`position_choice`/priority hints).
- **Full-apply migrate / lenient reads / emitted migrated copies** — see the
  re-examination; the derived-copy variant is the subtle trap.

## Recommended sequencing

| Wave | Ships | Why here |
|---|---|---|
| **0 — bedrock** (all S) | 1.1 envelope gap, 1.2 identity tripwire + policy validate, 1.3 truthfulness sweep, repeated `--target`; migrate **shape B** rides the same diagnostics work | Repairs the core promise everything else inherits; highest prevented-defect-per-line; no schema-family bumps except the cost-free render-result tightening |
| **1 — self-describing channel** | 1.4 MCP resources (schemas first, diagnostics catalog second) | Smallest pre-legitimized door; every later feature ships discoverable; probe = fewer guide calls |
| **2 — the flagship** | 2.1 diff + fixed queries; 2.2 trust chain (verify/status/provenance) sharing the canonical-hash substrate | Converts determinism into weekly human-visible value; creates the CI drift gate; substrate reused by the deferred cache |
| **3 — enterprise bridge** | 3.1 whole-model OEF + per-view identity → 3.2 real-schema lane; whole-model XMI trails | The "agents feed the EA repository" story; probes (Archi import, javax.xml.validation spike) are afternoon-scale and go first |
| **4 — reach (PARKED)** | *Theme 4 not green-lit (owner, 2026-07-21).* 4.1-4.4 stay recorded here; revisit demand-led once Wave 2 gives CI a recurring reason | Distribution harvests demand instead of preceding it |
| **later, evidence-gated** | 3.3 UMLDI (Papyrus probe first); per-stage MCP tools (transcript evidence + coverage debt paid); native `lift` (only if agent lifting plateaus) | Largest permanent surfaces; commit on evidence, not hope |

Every wave is independently shippable. Nothing here requires reopening a
forbidden door except as explicitly decided in the migrate re-examination.

## De-risking probes

Afternoon-scale, before any feature plan is written: 1.1 parity fixtures
(blast radius); 2.1 jq diff prototype in a mock PR; 2.2 stamped-SVG survival
through rsvg/Inkscape/Archi + canonical-hash determinism over fixtures; 3.1
hand-merged two-view OEF into Archi; 3.2 `javax.xml.validation` against the
ArchiMate 3.1 XSD set; 3.3 hand-authored DI fragment into Papyrus/EA; 4.1
20-diagram lift trial; 4.2 docs-only workflow/Dockerfile demand signal; 4.3
gated-algorithm metric run over the fixture corpus.

When implementation starts, standard CLAUDE.md lanes apply per touched module
(`./mvnw test`, module `-pl … -am` lanes, `-Pquality verify`, MCP/dist-smoke
for MCP-surface changes), and each feature gets its own TDD plan per repo
planning-policy.

## Settled decisions (owner, 2026-07-21)

1. **Migrate rule → Shape B.** Structured migration operations delivered as
   data (envelope on `DEDIREN_SCHEMA_VERSION_OUTDATED`; guide prose retained
   for humans, generated from the same data); dediren never applies them;
   Decision 2 of the schema-migration spec is amended prose→data, with
   escalation triggers to a full migrate recorded (first non-mechanical bump,
   or first `model.schema` bump contemplated). Decision 5 untouched.
2. **Green-light scope: Themes 1, 2, 3.** Theme 4 (reach & adoption) is
   parked — retained in this survey, revisit demand-led.
3. **Residency:** this document is the durable record of the survey and its
   decisions; the schema-migration spec carries the dated Decision-2
   amendment.

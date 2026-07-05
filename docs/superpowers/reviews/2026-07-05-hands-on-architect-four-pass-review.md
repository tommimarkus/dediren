# Hands-On Architect Review (Four-Pass) — 2026-07-05

Baseline: `main` @ `4603c33` (v2026.07.3). Single-reviewer sweep from one
viewpoint — a hands-on software architect with a deep background in Java-based
tool development — across four passes, each a different lens: **structure**
(the module graph and docs at rest), **runtime** (the guarantees the code
must honor when it runs), **strategy** (architect-altitude fitness and bets),
and **feel** (the developer-experience "feng-shui": flow versus friction).
Verification layers used: `[static]`, `[graph]`, `[runtime]` (evidence on
Temurin/OpenJDK 21.0.10, the CI JDK), and `[felt]`/`[ergo]` for Pass 4.
Roadmap-dependent items (`[human]`) are framed as forks, not verdicts.
**Advisory only:** no remediation executed and no follow-up plans authored;
where the live code and this report disagree, the code and its tests are the
current truth.

## Executive Summary

The architecture is genuinely strong and needs no rebuild. There are **no
block-severity findings**: the internal dependency graph is an enforced DAG
rooted at `contracts` with no cycles or inversions, `contracts` is a truly
pure record/enum kernel, the plugin process boundary is textbook (concurrent
stdin/stdout/stderr draining, hermetic env, reproducible cwd), the god-files
the guidelines used to apologize for are actually paid down, and the build has
zero-ceremony ergonomics that make the repo *feel good* to work in — one clone,
one command, seconds to first test feedback, and enforcement rules that teach
the guideline they protect on failure.

Every finding of substance lands on a single fault line, seen from four
heights: **the engineering craft is excellent, and the written-and-verified
layer around it lags behind.** The canonical architecture doc has a
load-bearing section (the Jackson migration playbook) that is now *inverted*
relative to the code (P1-1); the four quality attributes the design names as
owned requirements are half-unverified, and the two that are untested are
exactly the runtime promises an agent consumes (P3-1); a confirmed,
runtime-proven determinism defect sits directly under the "byte-stable
envelope" guarantee, invisible to the existing same-JVM tests (P2-1); and the
documentation corpus that is the project's operating system for AI-agent
development has outgrown the coherence index meant to keep its tiers in sync,
so the newest doc tier is a real move-with-the-change touch-point that
`CLAUDE.md` routing does not know exists (P4-1). The through-line prescription
is one idea at four altitudes: **make the load-bearing claims executable so the
documentation cannot silently lie** (P3-4).

This is a finishing job, not a rebuild. The discipline that produced the
module graph and the zero-ceremony build has simply not yet been turned on the
quality scenarios and the doc corpus's own coherence.

## Findings Ranked by Leverage

Thirteen ranked findings, all confirmed by inspection or reproduction (P2-1 is
runtime-proven with a re-runnable demo), plus four roadmap **forks** and a set
of preserved strengths. No block severity.

| Rank | ID | Severity | Verdict | Title | Pass / lens |
| ---- | -- | -------- | ------- | ----- | ----------- |
| 1 | P2-1 | warn | confirmed | `Map.copyOf` salted iteration order breaks the §9 byte-stable-envelope guarantee | 2 · runtime |
| 2 | P3-1 | warn | confirmed | The §9 quality scenarios are documented as owned requirements but never became acceptance tests | 3 · strategy |
| 3 | P1-1 | warn | confirmed | The canonical Jackson §10 migration playbook is inverted relative to the code | 1 · structure |
| 4 | P4-1 | warn | confirmed | The documentation corpus has outgrown its own coherence index | 4 · feel |
| 5 | P3-4 | warn | actionable | Load-bearing prose claims are not executable; drift is unchecked | 3 · strategy |
| 6 | P2-2 | warn | confirmed | Unbounded plugin-output read undercuts the §5 fault-isolation scenario | 2 · runtime |
| 7 | P4-2 | warn | confirmed | The most-visible onboarding paths silently run a stale bundle | 4 · feel |
| 8 | P1-2 | warn | confirmed | Coverage, mutation, and SpotBugs are advisory, not CI-gated — they will rot | 1 · structure |
| 9 | P1-3 | warn | confirmed | ArchUnit enforces the invariants but not the §2 edge table (latent, not live) | 1 · structure |
| 10 | P2-3 | info | confirmed | The product version is projected into ~30 sites with partly-manual reconciliation | 2 · runtime |
| 11 | P4-3 | info | confirmed | "Make a build" hides behind a two-pom `-pl dist-tool -am` incantation | 4 · feel |
| 12 | P1-4 | info | confirmed | Notation vocabulary is duplicated because the source copy is `private` (documented debt) | 1 · structure |
| 13 | P4-4 | info | confirmed | The fast test lane is not a clean lane — harness noise in the quiet path | 4 · feel |

Forks (need an owner / roadmap call, not a fix): **P3-2** extensibility axis,
**P3-3** isolation total-cost-of-ownership, **P3-5** dependency footprint,
**Fork-1** doc-heaviness ceiling and coherence owner.

---

## Pass 1 — structure: the module graph and the docs at rest

The module topology is clean and, importantly, *enforced*. `ArchitectureRulesTest`
(`dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java`)
runs on the default `./mvnw test` path — so the dependency spine is CI-gated —
and it even guards against vacuous passing. The value in this pass is not in
rediscovering documented debt; it is in the drift between the documented ideal
and the code.

**P1-1 — the canonical Jackson §10 playbook is inverted. `[static]`/`[graph]`, warn.**
`docs/architecture-guidelines.md §10` states Jackson 2 is "the product JSON
stack" (60 files, `contracts` alone 30) and the 2→3 migration is a *future*
"dedicated slice, contracts module first." The code says the opposite: Jackson 3
(`tools.jackson.databind`) is the **sole** databind/core stack — `core`, `cli`,
`uml`, and all five plugins are 100% on it, with **zero** `com.fasterxml.jackson.databind`/
`.core` coordinates in any POM. The 34 residual `com.fasterxml.jackson.*`
imports in `contracts` are annotations only (`JsonProperty`/`JsonInclude`/
`JsonValue`), the namespace Jackson 3 consumes natively (`contracts/json/JsonSupport.java`
is a Jackson-3 `ObjectMapper`). The pom.xml comment ("Jackson 3 … is the
project's sole Jackson stack") is correct; §10 is stale and would send an agent
asked to "bump Jackson" hunting a Jackson-2 stack that is not there. §10 also
claims `jackson-annotations` is "pinned separately" — it is not, in the root
POM. **Action:** rewrite §10 to describe a completed migration; the residual
concern is keeping `jackson-annotations` explicit/pinned, not a future rename
sweep. (An ArchUnit rule banning `com.fasterxml.jackson.databind`/`.core`
imports would make the claim self-enforcing — see P3-4.)

**P1-2 — the strongest quality signals are advisory and will rot. `[static]`, warn.**
CI (`.github/workflows/ci.yml`) gates only the deterministic, cheap, high-signal
checks: `mvnw test` (which includes the ArchUnit spine and the Enforcer
convergence rules), `git diff --check`, and a blocking Grype/SBOM supply-chain
scan (`fail-build: true`). SpotBugs and Spotless run `continue-on-error: true`,
report-only. The JaCoCo 70% line+branch gate (`-Pcoverage`) and mutation testing
(`-Pmutation`) are **absent from CI entirely** — no workflow references either
profile. The tiering is defensible (noisy linters gating PRs is worse than the
bugs), but coverage and mutation numbers now exist only if a developer remembers
to run them locally; `CLAUDE.md` itself concedes coverage is "not run in CI."
**Action:** enforce coverage/mutation in a tracked nightly (even non-blocking,
but visible) or stop presenting 70% as a gate. Deeper CI/CD-posture judgment is
`devsecops-audit`'s.

**P1-3 — ArchUnit enforces the invariants but not the §2 edge table. `[graph]`, warn (latent).**
The test enforces the load-bearing negatives — acyclicity, `contracts`→nothing-
internal, `plugins`↛`core`, `core`↛`plugins`, `cli`↛`plugins`. It does **not**
enforce the granular §2 edge table, so `elk-layout` (spec'd `contracts`-only)
could add a `uml` dependency and pass. The POMs currently comply
(`elk-layout` → `contracts` only; `render`/`generic-graph` → `contracts`+
`archimate`+`uml`), so this is a latent gap, not a live violation — but the
doc's own standard is "if a guideline is worth stating, it is worth an ArchUnit
test." **Action:** add per-plugin `onlyDependOnClassesThat` rules, or soften the
§2 claim.

**P1-4 — notation vocabulary is duplicated because the source copy is `private`. `[static]`, info.**
Still live: `uml/UmlSequenceValidation.java:30` holds `private static final Set<String> MESSAGE_SORTS`,
and `render/node/uml/RenderInputValidator.java:26` re-declares
`UML_SEQUENCE_MESSAGE_SORTS`. Two copies that must be edited in lockstep. §6/§12
already own this with the correct fix (export it from `uml`). No new action;
confirming the debt register is accurate. Clean up when either file is next
touched.

**Cleared on inspection (empiricism check):** the "98 Java files in a contracts
module" alarm is a false positive — it is 83 records/enums plus four boring
utilities (`JsonSupport`, `ContractCollections`, `ContractVersions`,
`ContractsModule`), largest file a 198-LOC record. And an initial read of the
mixed Jackson imports as a dangerous half-migrated classpath is the reverse: a
*completed* migration with stale docs (P1-1). The god-files are genuinely gone
— `render/Main.java` is 124 LOC (not the 3,851 §8's prose still quotes),
`uml-xmi-export/Main.java` 230, `cli` 448; only §8's prose is stale, §12 records
the resolution.

---

## Pass 2 — runtime: the guarantees the code must honor

**P2-1 — `Map.copyOf` salted order breaks the §9 byte-stable guarantee. `[runtime]`, warn (confirmed, runtime-proven).**
`contracts/util/ContractCollections.java:15` — the single helper every contract
map flows through — is `Map.copyOf(new LinkedHashMap<>(values))`. `Map.copyOf`
returns a JDK immutable map whose iteration order is **salted by
`System.nanoTime()` at JVM start**, so it differs between CLI invocations; the
`new LinkedHashMap<>(values)` wrapper is pure waste (its order is discarded).
Proven on the CI JDK across three separate JVM starts:

```
run 1  Map.copyOf keys: [delta, bravo, alpha, charlie, foxtrot, golf, echo, hotel]
run 2  Map.copyOf keys: [bravo, delta, hotel, echo, golf, foxtrot, charlie, alpha]   ← differs
run 1  HashMap keys:    [bravo, golf, foxtrot, alpha, delta, hotel, echo, charlie]
run 2  HashMap keys:    [bravo, golf, foxtrot, alpha, delta, hotel, echo, charlie]   ← identical
```

`HashMap<String,_>` is run-stable (String's hashCode is specified), so this is
*not* a broad HashMap alarm — the hazard is specifically the JDK immutable-
collection salt in this one helper. It is reachable to serialized output:
`RenderMetadata.nodes/edges/groups` (the `projection --target render-metadata`
output; fixtures under `fixtures/render-metadata/` carry maps up to **15 keys**)
route through `mapOrEmpty`, and `SourceNode.properties` /
`SourceRelationship.properties` / `SourceDocument.plugins` do too (source
fixtures ≤1 key today, so that path is latent). §9 makes "byte-stable envelopes
and artifacts an agent can branch on" an owned requirement; run-varying key
order violates it. Critically, **same-JVM tests cannot detect this** — the salt
is fixed within one process — so existing round-trip/golden tests give false
confidence. **Fix:** one line — `new TreeMap<>(values)` wrapped unmodifiable
(canonical, input-independent; best for a contract tool) or an unmodifiable
`LinkedHashMap` (preserve document order) — fixes all reachable maps at once.
The companion cross-process byte-stability test is `test-quality-audit`'s to
design.

**P2-2 — unbounded plugin-output read undercuts the §5 fault-isolation scenario. `[static]`, warn.**
`PluginRunner.readAll` (`core/plugins/PluginRunner.java:345`) does
`stream.readAllBytes()` on plugin stdout/stderr with no cap. §5 promises "a
plugin crash/hang/OOM cannot take down core," but a plugin that streams
unbounded output makes *core* buffer it to heap until *core* OOMs — the failure
crosses the very boundary the design exists to hold. The timeout does not bound
memory: bytes accumulate on the stdout/stderr futures for the entire timeout
window before `waitFor` trips. The isolation is real for a plugin that
*crashes*, not for one that is merely *loud*. **Fix:** a bounded read that caps
at N MB and kills with a structured `DEDIREN_PLUGIN_OUTPUT_TOO_LARGE`
diagnostic, consistent with how the boundary already normalizes timeout/IO.
(Repro limit: reasoned from the code path; a hostile-plugin OOM was not
reproduced.) Security-hardening depth is `devsecops-audit`'s. *Minor, same
method:* the timeout is soft — worst-case wall time is `waitFor(timeout)` plus
three `get(timeout)` calls ≈ up to 4× nominal (benign in practice, since
post-exit drains return instantly).

**P2-3 — the product version is projected into ~30 sites, reconciled partly by hand. `[static]`, info.**
`pom.xml` is the single source, but the version is copied into `README.md`,
`docs/agent-usage.md`, `fixtures/plugins/*.manifest.json`, `fixtures/source`
`required_plugins[].version`, dist metadata, and several version-assertion
tests. `CLAUDE.md` then mandates a manual "stale-version search" after every
bump. Some projections are test-enforced (`AgentUsageDocConsistencyTest`,
`MainTest`, `ContractRoundTripTest`); the manual grep is the fragile link — one
piece of knowledge owned by many files with a human as the convergence step.
Not urgent (CalVer + the tests catch most drift, and the surface was verified
clean at v2026.07.3), but this is where a stale release would originate.
Delegate specifics to `release-policy`.

*Pass-2 credit:* the subprocess boundary is otherwise textbook — `stdin`/
`stdout`/`stderr` each drained on their own `CompletableFuture` (the
concurrent-drain pattern that avoids the classic pipe-buffer deadlock),
`environment().clear()` for a hermetic env, a reproducible working directory,
and clean structured `PLUGIN_TIMEOUT`/`PLUGIN_IO_ERROR` diagnostics. Determinism
is clearly *intended* (collections normalized; `FAIL_ON_NULL_FOR_PRIMITIVES`
pinned back to the Jackson-2 default) — P2-1 is a subtle JDK gotcha, not
carelessness.

---

## Pass 3 — strategy: architect-altitude fitness and bets

**P3-1 — the quality scenarios are documented as owned requirements but never became acceptance tests. `[static]`/`[runtime]`, warn (the synthesis of passes 1–2).**
§9 names four owned quality attributes; mapped to their actual verification:

| §9 attribute | Owner | Verified? |
| --- | --- | --- |
| Determinism / byte-stable envelopes | core + plugins | **No** — no cross-process/ordering test; P2-1 is a live hole under it |
| Fault isolation | core plugin runner | **Partial** — timeout/crash tested (`PluginRuntimeTest`); resource-exhaustion (P2-2) not |
| Offline capability | schema-cache + export | Yes (`SchemaCacheModuleTest`, cli tests) |
| Reproducible build | reactor / dist-tool | Yes (`DistHermeticityTest`, `DistModuleTest`) |

The two build/packaging attributes (visible, static) are well covered; the two
runtime attributes an agent actually depends on (determinism, full isolation)
are exactly the under-verified ones. The architecture states its forces
unusually well and then does not turn them into gates. **Move:** a determinism
test (two separate-JVM runs, byte-diff) and a resource-exhaustion isolation test
promote §9 from prose to acceptance criteria — and would have caught both
Pass-2 findings.

**P3-4 — make the load-bearing claims executable. `[static]`, warn (actionable through-line).**
The knowledge corpus is the project's operating system for AI-agent development
— its great strength and its primary liability. ArchUnit enforces *structure*;
the *claims* are prose and they drift (P1-1 is the proof). Shrink the drift
surface by making the highest-value claims self-verifying: §9 scenarios →
acceptance tests (P3-1); the §2 edge table → complete ArchUnit rules (P1-3);
"Jackson 3 is the sole stack" → an ArchUnit rule banning
`com.fasterxml.jackson.databind`/`.core` so §10 cannot silently lie again (P1-1);
version projections → extend the existing consistency test (P2-3). The repo
already believes this ("worth stating → worth an ArchUnit test"); it applies it
unevenly.

### Forks (need roadmap/owner input, framed not decided)

**P3-2 — extensibility is invested on the "backend" axis, not the "notation" axis. `[human]`.**
The process-isolated plugin split makes **backends cheap** to add (a new
render/export/layout plugin is additive and isolated) but **notations
expensive**: two cores (`archimate`, `uml`) × consumers (`render`,
`generic-graph`, matching export). A third notation is a new core module plus
edits to every consumer plus the vocabulary-export discipline §6 keeps policing
— coupling O(notations × consumers). If the product grows by backends, the
architecture is beautifully shaped; if by notations, the extensibility is on the
wrong axis. **Fork:** is multi-notation a growth axis (invest in a notation SPI
so notations become additive like backends) or is 2–3 the ceiling (the shared
kernel is fine forever)? Name it. Shape work delegates to `architecture-design`.

**P3-3 — the isolation bet's total cost of ownership vs its one realized benefit. `[human]`/`[runtime]`.**
§5 justifies out-of-process plugins by fault isolation + trust boundary +
language independence. Today: five plugins, all first-party Java, zero non-Java
sources; the third-party plugin dir is opt-in and off by default. So language
independence and the trust boundary are provisioned-but-unused — only fault
isolation is consumed — while the cost ladder to make the boundary affordable
keeps growing (Tier 1 launcher flags → Tier 2 AppCDS → Tier 3 manifest-trust →
Tier 4 Leyden AOT, planned). The team already analyzed this and *closed*
in-process transport on measured cost/benefit (a considered position, not an
oversight). The standing obligation is lighter than "reconsider": treat the two
unused benefits as a tracked bet — either cultivate the third-party/polyglot
plugin story that justifies the isolation, or keep re-confirming that
fault-isolation-alone still outweighs the escalating startup-mitigation stack.

**P3-5 — the dependency footprint is a standing maintenance bet. `[human]` + devsecops.**
Heavy runtime deps: Batik (`codec`+`transcoder`, PNG raster), full ELK (four
artifacts), Guava, Jackson. The team already hand-pins `commons-io` and Jackson
for CVEs — that toil scales with footprint, and Batik is a large CVE/size
surface for what it buys (PNG). **Fork:** is PNG worth carrying Batik in a
per-command agent bundle, or should rasterization be optional/external (ship
SVG; let the consumer rasterize)? Cost/benefit, not a defect; CVE specifics are
`devsecops-audit`'s.

---

## Pass 4 — feel: flow, friction, and the feng-shui of the work

*Delegated to a subagent (opus, high effort) on the reviewer's "feng-shui" lens:
does the project flow without friction across architecture tuning, development,
testing, and building? Layers: `[felt]` (ran the workflow), `[ergo]` (inspected
the ergonomics). Findings distinguish real developer friction from
agent-sandbox artifacts.*

### Where the energy flows (credit — this is the target)

- **Zero-ceremony build. `[felt]`** A fresh clone runs `./mvnw test` with no
  setup ritual: `.mvn/maven.config` pins the repo-local cache
  (`-Dmaven.repo.local=.cache/maven/repository`, no `~/.m2` pollution) and
  `.mvn/jvm.config` pre-wires `-Xmx1g`, UTF-8, and the seven `--add-exports/
  --add-opens jdk.compiler` flags google-java-format needs on JDK 21 — so
  `spotless:apply` never explodes with a module-access error. Clone → build is
  one command. The single biggest reason the repo feels good to start in.
- **The narrow lane is real and fast. `[felt]`** `./mvnw -o -pl contracts -am
  test -q` returns in **3.07 s** warm — the per-area lanes CLAUDE.md promises
  deliver seconds-to-feedback, not a reactor drag.
- **Enforcement that teaches. `[ergo]`** `ArchitectureRulesTest` fails with
  `.because("…§2, ADP")` rationale on every rule; a contributor who trips a
  boundary reads *why* and *which guideline*. `DiagnosticCode` is one clean enum
  owner — adding a diagnostic is one constant + one assertion, no scattering.
- **A cared-for, dogfooded showcase. `[ergo]`** The README pipeline diagram is
  generated *by the tool* from tracked inputs, and `.gitignore` ignores
  `**/*.svg` but explicitly un-ignores `!/docs/assets/pipeline.svg` — the one
  artifact that IS the demo. Coherent and intentional.
- **The release version-surface stayed consistent. `[felt]`** ~30 projection
  sites all read `2026.07.3`, matching HEAD `Release 2026.07.3`; the manual
  ritual produced a genuinely clean state.

### Where the energy pools (friction)

**P4-1 — the documentation corpus has outgrown its own coherence index. `[ergo]`, warn (the defining flow finding).**
The live front-of-house is 8 tiers / ~3,310 lines atop a ~53k-line / 90-file
`docs/superpowers/{plans,specs,reviews}` history. The newest tier,
`docs/features/` (~1,115 lines, added this month), is **not mentioned anywhere
in `CLAUDE.md`** — not in "Start Here" routing, not in "Files That Move
Together" — yet tracing a real render-policy field shows it *is* a genuine
move-with-the-change touch-point. It even ships its own "Keeping This
Documentation Current" section, an admission it is drift-prone by construction.
This is the P1-1 Jackson-drift trap re-created one tier up: a load-bearing
derived doc added faster than the index that keeps tiers coherent. The felt cost
is at onboarding — a newcomer's "where do I look for X" now has 8 candidate
answers with overlapping scope, and the router meant to disambiguate does not
know the newest tier exists. The move-together render row also omits
`docs/agent-usage.md` even though it is a real touch-point.

**P4-2 — the most-visible onboarding paths can silently run a stale bundle. `[felt]`, warn (least-astonishment).**
README "First Run" and the diagram-regen both select the bundle with
`ls -d dist/dediren-agent-bundle-* | … | tail -1` (documented as
"version-agnostic" — it is actually newest-*built*). Source is `2026.07.3` but
only `dediren-agent-bundle-2026.07.2` exists under `dist/`, so both flows
transparently exercise the *previous* release. Root cause is a CalVer
interaction: the artifact is renamed every release and `./mvnw test` never
cleans, so `dist/` keeps the last-built bundle. `dist-build` itself is hermetic
(the SEED-1 `clean-appassembler-staging` guard), but the ambient `dist/` a human
globs against is not, and the docs point the glob straight at it. **Fix:** make
the glob version-aware (read the target version from `pom.xml`) or clean stale
bundles.

**P4-3 — "make a build" hides behind a two-pom incantation. `[ergo]`, info.**
Build verbs are split: five profiles in the root pom (`quality`, `coverage`,
`sbom`, `security-sca`, `mutation`), three in `dist-tool` (`third-party-notices`,
`dist-build`, `dist-smoke`) reachable only via `-pl dist-tool -am`. There is no
`./mvnw`-level catalog; nobody guesses that "build the distributable" is
`./mvnw -pl dist-tool -am verify -Pdist-build`. Discoverable, not discovered.

**P4-4 — the fast lane is not a clean lane. `[felt]`, info (legibility).**
Even `-q` narrow `contracts` tests print ~30 Jazzer `INFO: Instrumented …`
lines (the fuzz-regression harness) before the timing. Product stderr is
disciplined (agents branch on stdout JSON — a real strength); the *test* console
leaks harness chatter into the quiet path.

### Fork-1 — doc-heaviness is a deliberate bet; it needs a ceiling and a coherence owner. `[human]`.

The corpus is the project's superpower — why an agent can onboard here at all —
and the coherence net covers a fraction: the move-together index does not list
`docs/features/`, and the one executable check (`AgentUsageDocConsistencyTest`)
guards only 2 of 8 tiers along 2 axes (`DEDIREN_*` tokens exist in source;
CalVer strings match). Two coherent resolutions: **(a) shrink** — declare a
smaller set of load-bearing live tiers and let `docs/features/` be explicitly
ephemeral/regenerable; or **(b) extend the net** — wire `docs/features/` into
"Files That Move Together" and grow the executable-doc check to every
load-bearing tier. This is P3-4 seen from the felt altitude: the thing that
makes this repo legible is also the thing accumulating the most un-indexed,
un-tested surface.

---

## Strengths worth preserving

- The internal dependency graph is an **enforced** acyclic DAG rooted at
  `contracts`, gated on the default `./mvnw test` path — architecture rules are
  real, not folklore.
- `contracts` is a **genuinely pure data kernel** (83 records/enums + four
  boring utilities); the schema is the abstraction, the records its typed
  projection. Do not wrap it in interfaces.
- The **plugin process boundary** is the best-argued decision in the repo —
  measured (~330 ms/stage), with in-process transport closed on that evidence
  (MT-7). Do not reopen without a `[runtime]` scenario these numbers fail.
- The **subprocess mechanics** are textbook (concurrent drain, hermetic env,
  reproducible cwd, structured diagnostics) — P2-2 is the only gap in them.
- The **god-files are actually paid down** (`render/Main.java` 124 LOC), and the
  debt register in §12 is real, not decorative.
- The **developer experience flows** — zero-ceremony build, seconds-to-feedback
  narrow lanes, teaching enforcement, a dogfooded showcase.

## The four-pass arc

Pass 1 found the map drifting from the code in one section (docs). Pass 2 found
the code not fully honoring two stated guarantees (runtime). Pass 3 found those
guarantees untested and the growth axis mis-bet (strategy). Pass 4 confirmed the
same personality from inside the work (feel): the *doing* flows beautifully and
the *documenting* is where the current stagnates. Every pass landed on the same
fault line from a different height — **excellent engineering craft, under-tended
coherence in the written-and-verified layer** — which is a strong signal it is
the real one, and a finishing job rather than a rebuild.

## Method, layers, and limits

- **Method:** read the reactor graph and dependency management, the intended §2
  edge table, per-module Jackson imports, the ArchUnit test, plugin POM edges,
  the three CI workflows, `PluginRunner`, `ContractCollections`, the render-
  metadata and source fixtures, and the eight live doc tiers. Ran a narrow
  `contracts` test lane, a version-surface grep, and a JDK-21.0.10 salt demo
  reproducing P2-1. Pass 4 was delegated to a subagent (opus, high effort) that
  walked the onboarding/change/test/build/release workflows.
- **Layers:** `[static]`, `[graph]`, `[runtime]`, `[felt]`, `[ergo]`. Not
  gathered: `[history]` (churn) and full `[human]`/roadmap input — the four
  forks depend on product direction and usage telemetry and are framed as
  decisions, not verdicts.
- **Delegations:** `test-quality-audit` (P3-1 acceptance tests, P2-1 cross-
  process determinism test), `devsecops-audit` (P1-2 CI posture, P2-2 output-cap
  hardening, P3-5 footprint), `release-policy` (P2-3 version projection),
  `architecture-design` (P3-2 notation-SPI shape if pursued).
- **Limits:** P2-2's core-OOM consequence is reasoned from the code path, not
  reproduced with a hostile plugin; the render-metadata golden-comparison path
  was inferred (byte-stability untested) rather than confirmed line-by-line; the
  stale `target/`/`dist/` artifacts behind P4-2 are partly agent-environment
  leftovers, but the CalVer-rename accumulation mechanism is genuine.

# Wave 2 — Model Intelligence Implementation Plan (diff, query, trust chain)

Status: in progress (started 2026-07-22). Parent:
`2026-07-21-future-feature-roadmap-survey.md` (avenues 2.1 + 2.2, Wave 2).

## Part A — `dediren diff` + `dediren query` (survey 2.1)

**Goal:** convert determinism into visible value: compare two model revisions
and answer fixed impact questions as structured envelopes, deterministically.
Read-only; never a merge tool; CLI-only for now (MCP twins stay behind the
per-stage-tools evidence gate).

**Contract design (v1, deliberately tight):**

- `schemas/diff-result.schema.json` (`diff-result.schema.v1`, generated
  family): `{diff_result_schema_version, nodes, relationships, views}` where
  `nodes`/`relationships` = `{added[], removed[], changed[]}` — added/removed
  entries `{id, type, label}`; changed entries
  `{id, changes[{field, from, to}]}` with `field` ∈ `type`, `label`, or
  `properties.<top-level-key>` (shallow property compare; `from`/`to` carry
  the JSON values). `views` = `{added[], removed[], changed[]}`; a changed
  view lists `{id, nodes_added[], nodes_removed[], relationships_added[],
  relationships_removed[]}`. Every list sorted by id — byte-stable.
- `schemas/query-result.schema.json` (`query-result.schema.v1`):
  `{query_result_schema_version, kind, dependents?|orphans?|view_coverage?}`
  (exactly the block matching `kind`):
  - `dependents` (requires `--id`): `{id, inbound[], outbound[]}` — inbound =
    `{relationship_id, type, node_id}` for relationships targeting the id
    (fan-in), outbound likewise for relationships sourced at it (fan-out).
  - `orphans`: `{relationship_orphans[], view_orphans[]}` (node ids with no
    incident relationships; node ids in no view).
  - `view_coverage`: `{views[{id, node_count, relationship_count}],
    model_node_count, model_relationship_count, uncovered_node_ids[]}`.
- Both inputs to `diff` must be valid current-schema source models
  (`loadAndValidateSourceDocument` per side; a failing side's diagnostics are
  the error envelope, path-prefixed `old`/`new`). Same for `query`'s input.
- `contracts` records mirror both shapes (NON_NULL optionals for the
  per-kind query blocks); `ContractVersions` consts; `ContractVersionsTest`
  map entries; `SchemaValidatorTest` list; round-trip coverage.
- CLI: `dediren diff --old <file> --new <file>`,
  `dediren query --kind dependents|orphans|view-coverage [--id <node-id>]
  --input <file>`. Pure core functions (`core/analysis/ModelDiff`,
  `core/analysis/ModelQuery`), cli stays thin. Unknown `--kind` /
  missing `--id` → `DEDIREN_COMMAND_INPUT_INVALID` usage envelopes (exit 2).

**Tasks:**

- [ ] RED: CLI tests — diff of two inline model revisions pins added /
      removed / retyped / relabeled / property-changed / view-membership
      changes and deterministic ordering; identical inputs → all-empty
      result, status ok; stale-schema input → the gate's OUTDATED envelope;
      query tests per kind incl. `--id` fan-in/fan-out, orphan both-kinds,
      coverage with an uncovered node; usage-error envelopes.
- [ ] GREEN: schemas + consts + records + core functions + subcommands.
- [ ] Docs together: agent-usage new `## Diff & Query` section (+ the
      matching `GuideCatalog` topic — `GuideCatalogTest` is bidirectional),
      README command mention, `docs/features/pipeline-and-commands.md`.
- [ ] Module gates: `-pl contracts,core,cli -am test`; full gates deferred
      (see Coordination).

## Part B — Artifact trust chain (survey 2.2)

**Probes: PASSED (2026-07-22).** Canonical-hash determinism holds (sorted-key
compact serialization → identical digests across runs and key-reordered
inputs). Stamped-SVG survival: a `<metadata id="dediren-provenance">` block
injected after the root tag renders **byte-identical** PNGs through `resvg`
(one of the guide's blessed converters; rsvg-convert/Inkscape absent here,
Archi likewise — comment-carried OEF/XMI stamps are parser-transparent by XML
conformance, so the SVG leg was the risky one and it passed).

**V1 design (decided before code):**

- **Canonical hash contract:** SHA-256 over the canonical JSON of a document
  — the parsed document re-serialized with recursively sorted object keys,
  compact separators, UTF-8 (`core/analysis/CanonicalJson`). The model hash
  is taken over the assembled, validated source document's serialized form
  (so formatting/key order/fragment layout never change it); policy hashes
  over the policy documents likewise. No timestamps anywhere — byte-stable.
- **Stamp carriers:** SVG gets an inert `<metadata id="dediren-provenance">`
  element holding compact JSON `{model_schema_version, model_sha256,
  view_id, render_policy_sha256, dediren_version}`; OEF/XMI get an XML
  comment `<!-- dediren-provenance {…} -->` after the XML declaration
  (`oef_policy_sha256` / `xmi_policy_sha256` respectively). All values are
  product-generated or charset-constrained (view ids), XML-escaped at
  injection.
- **Where stamping happens: the `build` lane only.** Core injects the stamp
  into artifact content before writing — the one place where model, policies,
  view id, and version are all naturally in scope. Engine seams stay
  untouched, engine-level goldens stay byte-stable, and decomposed
  single-stage outputs remain unstamped (`verify` reports them UNSTAMPED —
  honest, and recorded as the follow-up seam if decomposed-stamping demand
  appears).
- **`dediren verify --input model.json --artifacts <dir>`:** recompute the
  model hash, walk the artifact tree for stamps, and report per artifact:
  stale (stamp's model_sha256 ≠ recomputed) → error diagnostic
  `DEDIREN_ARTIFACT_STALE`, exit 2 — the CI drift gate; unstamped
  recognized artifact kinds → warning `DEDIREN_ARTIFACT_UNSTAMPED`; current
  → listed in data only. Envelope data = `verify-result.schema.v1`
  (`{artifacts[{path, status}], model_sha256}`).
- **`dediren status --root <dir>`:** workspace freshness index — scan for
  model documents (files carrying `model_schema_version`) and stamped
  artifacts; an artifact is `current` if its stamp matches any present
  model's hash, else `stale`; unstamped artifacts listed as such. Data =
  `status-result.schema.v1` (`{models[{path, sha256}], artifacts[{path,
  status, model_sha256?}]}`). Read-only, no watch, no daemon.

**Tasks:**

- [x] RED→GREEN: `CliProvenanceTest` pins stamping, verify
      current/stale/unstamped (incl. exit-2 drift gate), status indexing,
      and two-build byte-determinism; `BuildCommandTest` +
      `CliBuildCommandTest` pins updated to expect stamps (incl. the
      documented build-vs-decomposed parity, now modulo-stamp).
- [x] GREEN: `CanonicalJson` (recursively sorted, compact, UTF-8 SHA-256),
      `Provenance` (SVG metadata incl. the self-closing-root edge, XML
      comment, extraction), build-lane injection via a per-build `Stamps`
      record, `ProvenanceCheck` verify/status (status loads candidates
      through the full source path so its hashes equal the stamps' —
      fragments included), two schemas/records/consts, `verify`/`status`
      subcommands. Codes `DEDIREN_ARTIFACT_STALE` (error) /
      `DEDIREN_ARTIFACT_UNSTAMPED` (warning).
- [x] Docs: `## Provenance & Verify` + GuideCatalog topic, README, features
      page, threat-model stamp note, Repair Rules entries. New records join
      the recorded EI_EXPOSE_REP suppression set.
- [x] Full `-Pquality verify` + dist-smoke green.

**Wave 2 status: complete** — Part A (diff/query, 97e4eee) + Part B (trust
chain) both shipped; probes recorded above.

## Coordination note

The working tree carries concurrent in-flight licence-verification work
(root/cli/dist-tool poms + `DistTool`), currently non-compiling. All
full-reactor gates (`./mvnw test`, `-Pquality verify`, dist-smoke) are
deferred until that lands; each part ships on module-scoped gates and the
deferred gates run at the end of the push.

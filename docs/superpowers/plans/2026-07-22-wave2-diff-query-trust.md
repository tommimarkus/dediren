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

## Part B — Artifact trust chain (survey 2.2): probe-gated

The survey orders afternoon probes BEFORE committing this feature: canonical
content-hash determinism (runnable here) and stamped-SVG survival through the
external converters the guide blesses (rsvg-convert/Inkscape — availability to
be checked in this environment; Archi import is not available here at all).

- [ ] Run the canonical-hash determinism probe over fixtures; record result.
- [ ] Check converter availability; if absent, record 2.2 as
      **probe-blocked in this environment** per the survey's own discipline
      (the hash definition is a permanent mini-contract; it does not get
      improvised without its survival evidence) and hand the remaining probe
      to the owner as the unblocking step.

## Coordination note

The working tree carries concurrent in-flight licence-verification work
(root/cli/dist-tool poms + `DistTool`), currently non-compiling. All
full-reactor gates (`./mvnw test`, `-Pquality verify`, dist-smoke) are
deferred until that lands; each part ships on module-scoped gates and the
deferred gates run at the end of the push.

# Third-Party Plugin Contract Publication & Extensibility Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** complete — implemented on main (owner chose Task 1 option (b), Task 2 option (a), Task 4 separate docs/plugin-authoring.md).

**Goal:** Make the third-party plugin story honest: an outside author can build,
register, and ship a working plugin from the published surface alone, without
mislabeling artifact kinds or reverse-engineering the executable protocol.

**Source findings:** PA-1 (block: closed export-result `artifact_kind` enum),
PA-2 (executable lifecycle undocumented), PA-3 (`.dediren/plugins` resolves
against bundle root, contradicting docs), PA-4 (export-request `policy`
schema misdescribes reality), PA-5 (manifest executable resolution
undocumented), PA-6 (env-override normalization undocumented), PA-7 (failure
signaling undocumented), CS-2 (export envelope shape undocumented) — all
confirmed; evidence and repro commands in
`docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md`.

**Architecture:** Split the export-result contract into a stable base
(envelope + `artifact_kind` string pattern + `content`) that any plugin can
satisfy honestly, with the strict first-party enum enforced only for
first-party plugin ids; align the export-request `policy` schema with the
real pass-through behavior; make project plugin discovery match its
documentation (or vice versa — owner decision); and publish a plugin-author
contract document covering the full executable lifecycle.

**Tech Stack:** JSON Schema, Java 21 (`core` PluginRunner/validation), docs.

## Global Constraints

- Public JSON shape changes move together: `schemas/`, `contracts`, fixtures,
  plugin mapping code, and schema/round-trip tests in the same change
  (CLAUDE.md "Files That Move Together").
- Plugin protocol changes move together: manifests, runtime capability
  handling, envelope validation, CLI behavior, README notes, compatibility
  tests.
- Schema ids (`export-result.schema.v1` etc.) change only if compatibility
  intentionally breaks; loosening an enum to a pattern is backward-compatible
  for existing documents — confirm before bumping any id.
- `AgentUsageDocConsistencyTest` must stay green for every `DEDIREN_*` token
  added to `docs/agent-usage.md`.
- Verification lane: `./mvnw -pl core,cli -am test`, then full
  `./mvnw test` and `./mvnw -pl dist-tool -am verify -Pdist-smoke` before
  calling the plan done.

---

### Task 1: Open the export-result contract for third-party plugins (OWNER DECISION)

**Files:**
- Modify: `schemas/export-result.schema.json`
- Modify: `contracts` export-result record/validation if it mirrors the enum
- Modify: `core` plugin output validation (the path that raises
  `DEDIREN_PLUGIN_OUTPUT_INVALID_DATA`)
- Test: contracts round-trip tests + a new core test with a third-party
  artifact kind

**Decision to make first:** (a) relax `artifact_kind` from the closed enum to
a pattern such as `^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$` for all plugins,
or (b) keep the strict enum for bundled first-party plugin ids and validate
third-party export output against the relaxed base only. Option (b) keeps
first-party regression protection; recommend (b).

- [x] Step 1: Reproduce PA-1 (failing state): run the review's PA-1 repro —
      a plugin emitting `artifact_kind: "ticket-stats+json"` is rejected with
      `DEDIREN_PLUGIN_OUTPUT_INVALID_DATA`. Expected: exit 3.
- [x] Step 2: Write a failing core test: third-party manifest + export output
      with a non-first-party `artifact_kind` must be accepted.
- [x] Step 3: Implement the chosen option across schema + contracts + core.
- [x] Step 4: Re-run the PA-1 repro — expected: status ok, exit 0, with the
      honest artifact kind. First-party export tests stay green.
- [x] Step 5: `./mvnw -pl contracts,core,cli -am test`; commit.

### Task 2: Make project plugin discovery match its documentation (OWNER DECISION)

**Files:**
- Modify: `core` plugin discovery (bundle-root `.dediren/plugins` resolution)
  — or —
- Modify: `README.md` + `docs/agent-usage.md` discovery/repair-rule wording

**Decision to make first:** (a) implement true project-level discovery
(resolve `.dediren/plugins` against the caller's cwd, keeping bundle-root and
`DEDIREN_PLUGIN_DIRS` lookups), or (b) document reality: project registration
is `DEDIREN_PLUGIN_DIRS` only. (a) matches the long-stated design intent
("project plugin directories such as `.dediren/plugins`" in CLAUDE.md);
recommend (a) with cwd lookup ordered after bundled plugins and before
user-configured dirs.

- [x] Step 1: Reproduce PA-3 (failing state): the review's PA-3 repro from a
      project dir fails with `DEDIREN_PLUGIN_UNKNOWN`.
- [x] Step 2: Failing test for the chosen behavior (core discovery test or
      doc-consistency assertion).
- [x] Step 3: Implement; keep the no-PATH-discovery rule intact.
- [x] Step 4: PA-3 repro now passes from the project directory (option a) or
      docs no longer promise it (option b).
- [x] Step 5: `./mvnw -pl core,cli -am test`; update README + agent-usage in
      the same commit; commit.

### Task 3: Align export-request `policy` schema with the pass-through contract

**Files:**
- Modify: `schemas/export-request.schema.json` (policy: keep the first-party
  `oneOf` branches but add an open-object branch, or scope strictness to
  first-party plugin requests)
- Test: contracts round-trip + a request fixture with a free-form policy

- [x] Step 1: Reproduce PA-4: a request the CLI actually sends a third-party
      plugin fails validation against the published request schema.
- [x] Step 2: Failing round-trip test with a third-party policy document.
- [x] Step 3: Schema change; Step 4: repro validates; Step 5:
      `./mvnw -pl contracts -am test`; commit.

### Task 4: Publish the plugin-author contract document

**Files:**
- Create: `docs/plugin-authoring.md` (bundle-shipped; add to dist-tool bundle
  copy list) — or a dedicated section in `docs/agent-usage.md`
- Modify: `README.md` (pointer), `dist-tool` (ship the doc + consistency test
  coverage)

Content checklist (each item cites its finding): operation invocation
`<executable> <capability>` with request JSON on stdin and one envelope on
stdout (PA-2); child cwd = bundle root and env stripped to manifest
`allowed_env` (PA-2); manifest `executable` resolution relative to the
manifest's directory, no PATH lookup (PA-5); `DEDIREN_PLUGIN_<ID>`
normalization uppercase + non-alphanumerics→underscore (PA-6); failure
signaling — error envelope preserved verbatim, CLI exit non-zero either way,
plus `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` / `DEDIREN_PLUGIN_ID_MISMATCH`
in the repair rules (PA-7); export envelope shape `.data.artifact_kind` /
`.data.content` with a jq extraction example (CS-2); mandatory per-call probe
for non-bundled manifests (PA-7).

- [x] Step 1: Failing check — the review's PA-2 repro grep finds no
      executable-contract documentation.
- [x] Step 2: Write the document; wire bundle shipping + consistency tests.
- [x] Step 3: PA-2/PA-5/PA-6 repro greps now hit documentation.
- [x] Step 4: `./mvnw -pl dist-tool -am verify -Pdist-smoke` and
      `git diff --check`; commit.

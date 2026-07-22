# Wave 3 — Interchange Completion Implementation Plan

Status: complete (2026-07-22 — Part A and Part B both shipped). Parent:
`2026-07-21-future-feature-roadmap-survey.md` (avenues 3.1 + 3.2, Wave 3).
Out of this wave by survey decision: whole-model XMI (trails; collides with
per-family deferred constructs) and UMLDI (evidence-gated on a Papyrus
probe).

## Part A — Whole-model OEF + per-view identity (survey 3.1)

OEF already exports the FULL element/relationship model — only the diagram
section is single-view (`DEDIREN_OEF_VIEWS_OMITTED`), and identity is
per-build ("Phase-1 limitation"). Whole-model = same elements + ALL view
diagrams, each with its own identity.

**Design:**

- **Home: the build lane.** `dediren build --oef-policy …` keeps writing
  per-view `<view>/oef.xml` unchanged AND additionally writes one
  `model.oef.xml` at the out root containing every built view's diagram —
  reusing each view's already-computed ELK layout verbatim. (A standalone
  `export --scope model` would need N `--layout` inputs; build is where all
  the laid-out views naturally exist. Recorded as the follow-up if
  standalone demand appears.)
- **Engine seam:** `engine-api` `ExportEngine` gains
  `default Optional<EngineResult<ExportResult>> exportModel(ModelExportRequest, env, productRoot)`
  returning empty (XMI keeps trailing by simply not overriding);
  `OefExportEngine` overrides it. New `contracts.export.ModelExportRequest`:
  `{export_request_schema_version, source, views[{view_id, layout_result}],
  policy}`. Build calls it through the interface only (module rules hold).
- **OEF composition:** extract the per-view `<view>` builder inside
  `OefExportEngine.buildOef` and loop it over the supplied views; elements/
  relationships emitted once (they already cover the whole source). Official
  XSD validation runs on the composed document (the diagram XSD supports
  multiple `<view>` children).
- **Per-view identity:** `oef-export-policy.schema.json` gains an
  **additive-optional** `views` object map
  (`{"views": {"<view-id>": {"view_identifier"?, "view_name"?,
  "viewpoint"?}}}`) — optional addition, existing policies stay valid byte
  for byte, no schema-id bump. Resolution order per exported view (both the
  single-view lane and the whole-model composition): explicit `views[id]`
  override → source-derived default (`id-view-<view-id>` + the view's own
  label; viewpoint falls back to the policy's top-level `viewpoint`). The
  legacy top-level `view_identifier`/`view_name` remain what the SINGLE-view
  lane uses when no override exists (unchanged behavior); the whole-model
  document never reuses one identity for two views — that was the defect.
  Identity-tripwire: unchanged (keys on `model_identifier`).
- Diagnostics: the whole-model artifact emits no `OEF_VIEWS_OMITTED` (nothing
  omitted); build result lists it as a build-level artifact entry (path
  `model.oef.xml`) on a synthetic `model` scope — simplest: append it to the
  build result as an extra view outcome with `view_id: "model"`? NO — keep
  the build-result shape untouched: add the artifact to the FIRST view's
  artifacts? Also no. Decision: `build-result.schema.json` gains an
  additive-optional top-level `model_artifacts[]` (same artifact shape);
  absent when no whole-model artifact was produced — output schema, additive,
  no bump. Provenance stamp: stamped like other build artifacts (view_id
  `"model"`).

**Tasks:**

- [x] RED→GREEN: `CliBuildCommandTest` pins the aggregate (both view
      diagrams, override honored + source-derived default, `model_artifacts`
      entry, provenance stamp, per-view artifacts intact); the OEF module's
      full suite (incl. the byte-golden) stayed green through the extraction
      refactor — single-view output is byte-stable.
- [x] GREEN: `ExportEngine.exportModel` default + `ModelExportRequest` seam
      type, `OefExportEngine` refactor (openModel/writeViewBody extraction,
      `buildModelOef`, `resolveViewIdentity`), additive `views` map on the
      policy schema/record, additive `model_artifacts` on
      build-result schema/record, build-driver composition with stamping
      (view id `model`).
- [x] Docs: agent-usage OEF paragraph, features exports page. New
      collection-bearing records joined the recorded suppression set.

## Part B — Real-standards validation lane, in-JVM (survey 3.2)

**Spike result (2026-07-22): PASSED.** `fixtures/export/oef-basic.xml`
validates against the REAL Open Group ArchiMate 3.1 XSD set (the pinned
downloads, sha-verified) via `javax.xml.validation`, fully offline, provided
the W3C `xml.xsd` import is resolved locally (`LSResourceResolver`; xml.xsd
sha256 61960fb3…). Two design facts follow: xml.xsd joins the pinned schema
set, and the resolver keeps the lane hermetic (no network at validation
time; the existing pinned `curl` fetch/offline-dir behavior for OBTAINING
schemas is unchanged).

**Design:**

- `schema-cache` gains an in-JVM `XmlSchemaValidation` used by the OEF
  engine in place of the `xmllint` subprocess: compile the schema set with a
  local-only resolver (deny anything not present in the supplied directory),
  validate content, map failures to the existing
  `DEDIREN_OEF_SCHEMA_INVALID` diagnostics; `xmllint` disappears from the
  OEF trust path (the `DEDIREN_OEF_SCHEMA_VALIDATOR` override env and its
  UNAVAILABLE diagnostic retire from that lane). XMI keeps xmllint for now
  (its driver-schema flow differs; follow-up recorded in
  `2026-07-15-recorded-deferrals.md`) — so `xmllint` stays a documented
  dependency only for the XMI lane.
- xml.xsd: fetched alongside the three OEF XSDs with its own pinned SHA-256
  and accepted in `DEDIREN_OEF_SCHEMA_DIR`; absence yields the existing
  SCHEMA_UNAVAILABLE remediation message extended to name it.
- Conformance-report: the OEF success path emits an `info` diagnostic naming
  exactly what was validated against what ("validated against ArchiMate 3.1
  archimate3_Diagram.xsd; 3.2 XSDs unpublished by The Open Group").
  **Decided-not-built in this wave (recorded 2026-07-22):** a new `DEDIREN_*`
  code touches every OEF golden envelope, the guide's Repair Rules, and the
  ownership/doc-consistency guards — a slice of its own, deferred to
  `2026-07-15-recorded-deferrals.md`, not silently dropped.
- Docs/threat-model: XML-parsing rows updated — JDK validator with secure
  local-only resolution replaces the xmllint subprocess for OEF; README
  requirement line adjusts (`xmllint` needed for XMI lane only).

**Tasks:**

- [x] RED: OEF engine tests — valid content passes in-JVM against the
      supplied XSD dir (stub set still works); invalid content yields
      `DEDIREN_OEF_SCHEMA_INVALID`; a broken/incomplete schema set (missing
      xml.xsd import) yields the structured UNAVAILABLE-lane failure naming
      the missing import. (The conformance info diagnostic is decided-not-built
      — see the design bullet above.)
- [x] GREEN per the design: `schemacache.InJvmXmlValidator` (secure
      processing, local-only `LSResourceResolver`), OEF engine flipped off
      the xmllint subprocess, xml.xsd joined the pinned fetch set
      (sha256 61960fb3…), `DEDIREN_OEF_SCHEMA_VALIDATOR` env override and
      `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` retired from the contracts
      vocabulary and ownership allowlist.
- [x] Docs + threat model + README requirements + Repair Rules touch-ups
      (agent-usage validator bullet now XMI-only, README requirement line,
      CLAUDE.md engine-runtime example, features engine-runtime/exports
      pages, threat-model OEF rows, architecture-guidelines).

## Verification

Per part: `-pl engines/archimate-oef-export,engine-api,contracts,core,cli -am
test`, then full `-Pquality verify` + dist-smoke (bundle smoke's OEF lanes
exercise both changes end to end).

## Post-wave review remediation (2026-07-22)

A ten-angle code review of the wave-3 commits surfaced and fixed, same day:
validator-setup failures now throw to the UNAVAILABLE lane instead of being
misreported as `DEDIREN_OEF_SCHEMA_INVALID`; the in-JVM lane regained the
subprocess lane's 60s wall-clock ceiling (bounded daemon worker) and its
debug-level trace; compile failures name each unresolved schema reference
(relative subdirectory references now resolve; unreadable and empty files are
reported, not conflated with absent); the compiled `Schema` is memoized per
(path, size, mtime); offline-lane failures carry placement advice instead of
download/proxy advice; the pinned fetch set is one table
(`PINNED_OEF_SCHEMA_SET`); and `InJvmXmlValidatorTest` restores the
schema-cache `-Pcoverage` gate. Doc trues-ups rode along (agent-usage offline
xml.xsd requirement, threat-model runner/pin rows, distribution-and-runtime
prerequisites, guidelines §12 rows).

## Follow-on slice (2026-07-22): the validator story finished

Executed same day on owner go-ahead: the XMI lane joined the in-JVM validator
(spike against real OMG `XMI.xsd` first; xmllint-shaped UML-gap tolerance
rewritten for the Xerces wording), the whole subprocess stack was deleted
(`xmllint` is gone from the product), both lanes gained the
`DEDIREN_EXPORT_SCHEMA_CONFORMANCE` info diagnostic, and the opt-in
real-schema test lane (`RealSchemaConformanceTest`, `-Ddediren.real-schemas=true`)
now proves both emitters against the pinned real Open Group / OMG downloads —
closing the test-quality audit's P0 remainder. Deferral-register entries
resolved accordingly.

## Release-note obligations (next release)

- `xmllint` is no longer needed for anything: both export lanes validate
  in-JVM. `DEDIREN_OEF_SCHEMA_VALIDATOR`, `DEDIREN_XMI_SCHEMA_VALIDATOR`, and
  the codes `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` are retired.
- Every successful export now carries a `DEDIREN_EXPORT_SCHEMA_CONFORMANCE`
  `info` diagnostic (rides `ok`); consumers asserting exact diagnostics
  arrays on export envelopes will see one new trailing entry.
- A warm pre-upgrade `DEDIREN_SCHEMA_CACHE_DIR` (three OEF XSDs) performs a
  one-time fetch of the newly pinned W3C `xml.xsd` on the first post-upgrade
  OEF export; network-denied environments must add that file to the cache (or
  use `DEDIREN_OEF_SCHEMA_DIR`) before upgrading.
- Hand-populated `DEDIREN_OEF_SCHEMA_DIR` directories holding the real Open
  Group XSDs must now include the W3C `xml.xsd` those XSDs import; an XMI
  driver schema's imports resolve local-only from the driver's directory.

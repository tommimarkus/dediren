# Wave 1 — MCP Resources Implementation Plan

Status: in progress (started 2026-07-21). Parent:
`2026-07-21-future-feature-roadmap-survey.md` (avenue 1.4, Wave 1). The MCP
server design left resources as "an additive option later" — this takes the
option.

**Goal:** machine-readable discovery. The bundle's actual bytes become
read-only MCP resources so an agent fetches ground truth (a schema, a fixture,
a default policy, a guide topic, the diagnostics catalog) instead of pulling
prose through `dediren_guide` and re-parsing it every session. Serving the
shipped files verbatim is the anti-drift strategy.

**Surface** (all read-only, product-owned bytes; available identically under
`--read-only` — no workspace paths, so `--root` confinement is untouched):

- `dediren://schema/<file>` — every `schemas/*.json` (15), `application/json`.
- `dediren://guide/<topic>` — the guide topics `GuideCatalog` already serves,
  `text/markdown`; the resource text must equal the `dediren_guide` tool's
  text for the same topic (anti-drift pin).
- `dediren://fixture/<relative-path>` — every file under `fixtures/`,
  mime by extension.
- `dediren://diagnostics/catalog` — generated at serve time from the two
  compile-time/shipped truths: `DiagnosticCode.values()` × the bundled
  guide's `## Repair Rules` bullets. One JSON array of
  `{code, repair_rule|null}`; a test pins that every code the guide documents
  explicitly appears with its text.

**Tasks:**

- [x] Tests: resource list covers every schema/guide-topic URI; schema and
      fixture reads are byte-identical to the shipped files; guide resources
      equal `GuideCatalog.section` (= the `dediren_guide` tool text, pinned
      transitively via `GuideCatalogTest`); the diagnostics catalog covers
      every `DiagnosticCode`, attaches explicit repair bullets (including
      multi-code bullets), and nulls the self-repairing rest.
- [x] `DedirenResources` (startup enumeration from the product root, lazy
      reads, SpotBugs-clean) registered by `DedirenMcpServer` with the
      resources capability, both modes.
- [x] Packaged stdio smoke extension AUTHORED (resources/list + schema read +
      catalog read assertions in `DistTool`) but **uncommitted**: the same
      file carries concurrent in-flight licence-verification work that does
      not compile yet, so the hunk stays in the working tree until that work
      lands; the full dist-smoke run is deferred with it.
- [x] Docs: agent-usage `## MCP Server` resources paragraph; threat-model MCP
      controls gained the "resources serve product bytes only" row.
- [ ] DEFERRED to end-of-push (blocked by the concurrent dist-tool edit):
      full `-Pquality verify`, dist-smoke, and the coverage-number check.
      Module-scoped `-pl mcp-server,cli -am test` is green.

**Verification:** `./mvnw -pl mcp-server,cli -am test`, full
`-Pquality verify`, `-pl dist-tool -am verify -Pdist-smoke`.

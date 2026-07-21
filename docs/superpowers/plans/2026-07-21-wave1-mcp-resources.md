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

- [ ] RED: mcp-server tests — resource list contains the expected URI sets;
      `resources/read` of a schema returns the exact file bytes; a guide
      resource equals the `dediren_guide` tool output for the same topic;
      the diagnostics catalog covers every `DiagnosticCode` and carries
      repair text for the explicitly documented ones.
- [ ] GREEN: `DedirenResources` (enumerate at startup from
      `DedirenPaths.productRoot()`, read lazily in handlers);
      `DedirenMcpServer` registers them and advertises the resources
      capability in both modes.
- [ ] Packaged stdio smoke: extend the dist-tool MCP smoke with
      `resources/list` + one `resources/read`.
- [ ] Docs together per CLAUDE.md MCP row: agent-usage `## MCP Server`
      resources paragraph; threat-model MCP rows note the new surface serves
      product-owned bundle bytes only (no workspace reads, no new write
      primitive).
- [ ] Coverage: new code fully covered; the pre-existing mcp-server branch
      shortfall (0.67 < 0.70, stdio transport) is a recorded deferral — check
      whether the new tests move it, record the number either way.

**Verification:** `./mvnw -pl mcp-server,cli -am test`, full
`-Pquality verify`, `-pl dist-tool -am verify -Pdist-smoke`.

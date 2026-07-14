# Threat Model

This page enumerates Dediren's trust boundaries, the controls that guard
them, and the incident-response runbook, in one auditable place. It replaces
scattered code comments and design-spec asides as the standing reference for
security-posture questions (audit finding F8).

## Assets

- Released agent-bundle archives (`dediren-agent-bundle-*.tar.gz`).
- `main` branch and `v*` tag integrity.
- Schema cache content (downloaded OMG XMI / OEF ArchiMate schemas).
- The envelope JSON contract surface (stdin/stdout between agents and the
  CLI).

## Trust Boundaries

### Envelope JSON stdin/stdout (agents -> CLI)

Parsing goes through `contracts/src/main/java/dev/dediren/contracts/json/JsonSupport.java`'s
Jackson 3 (`tools.jackson`) `ObjectMapper`: snake_case properties, fail on
unknown properties, non-null-only output. Fuzz-regression targets pin the
failure contract: `JsonSupportFuzzTest` (`contracts`) and
`SchemaValidationFuzzTest` (`engines/uml-xmi-export`) assert only
`JacksonException` / `XmiValidationException` may escape parsing, running in
deterministic regression mode over checked-in seed corpora in CI.

Plan B P5 added a post-parse validation layer on top of that Jackson
contract: `ir.LayoutIntentCodec.decode` rejects an unrecognized
layout-constraint `kind` (or a malformed gap encoding) on the
`layout-request` wire fail-closed, surfacing as the clean
`DEDIREN_ELK_INPUT_INVALID_JSON` / exit-3 error envelope in place of the
former fail-open silent-ignore in the deleted `SequenceLayoutConstraints`;
and `validate-layout`/`build` now run `ir.quality.SequenceInvariants` against
an agent-suppliable `LayoutResult`, folding any violation into the hard-error
lane as `DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED`.

### Single-JVM engine runtime (no plugin execution surface)

The runtime is a single JVM with no plugin discovery or execution surface:
the five first-party engines (`generic-graph`, `elk-layout`, `render`,
`archimate-oef`, `uml-xmi`) are compile-time library modules behind the
`engine-api` interfaces, constructed explicitly in one named cli class
(`cli/.../EngineWiring.java`) and dispatched in-process by `core`'s
`EngineDispatch` (`core/.../engine/EngineDispatch.java`). Core never resolves
an executable, spawns a child process, or reads a plugin path/trust
environment variable; an unknown engine id is answered from the in-memory
registry (`DEDIREN_PLUGIN_UNKNOWN`), not from any filesystem lookup. The
former manifest env allowlist is gone because no child processes exist —
the export engines receive the CLI's env map explicitly (schema-path
variables) and read nothing else, pinned by the engines' no-`getenv` guard
tests (Task 4).

### MCP stdio server (`dediren mcp`)

`dediren mcp` (module `mcp`, launched by `cli`'s `McpCommand`) is a long-lived,
model-driven process holding a filesystem write primitive. It is the one boundary
where a *model* — not a human — chooses the paths, and MCP clients frequently
auto-approve tool calls, so the CLI's "a human typed this path" posture does not
transfer.

There is no network surface: stdio transport only, no port, no HTTP/SSE listener,
no multi-client daemon. The MCP client spawns the process and owns its lifetime,
so there is no daemon lifecycle to supervise.

Controls:

- **Workspace-root confinement.** Every tool path argument is resolved against the
  `--root` (default: cwd) and real-path-resolved *before* the containment check
  (`mcp/src/main/java/dev/dediren/mcp/WorkspacePaths.java`). Normalization alone is
  insufficient — a symlink inside the root pointing outside is the interesting case,
  and only `toRealPath()` catches it. For an output directory that need not exist,
  the nearest existing ancestor is resolved instead. An escaping path yields a
  `DEDIREN_MCP_PATH_OUTSIDE_ROOT` error envelope. Pinned by `WorkspacePathsTest`.
- **Read-only mode.** `--read-only` does not register `dediren_build` at all, so the
  write primitive is absent rather than present-and-refusing.
- **stdout integrity.** In stdio MCP, stdout *is* the JSON-RPC channel; a stray
  `System.out` write anywhere in core, an engine, or a dependency would corrupt a
  frame and the client would silently go dark. `StdoutIntegrity.claimStdout()` takes
  the real file descriptor for the transport and redirects `System.out` to stderr, so
  a stray print degrades to log noise. Pinned by the dist-smoke assertion
  `assertMcpServesToolsOverStdio`, which requires every stdout line to be a JSON-RPC
  frame.

Accepted residual — **TOCTOU**: real-path-resolve-then-open is not atomic, so a local
attacker able to create symlinks inside the root during the window can defeat the
confinement. Accepted: the server runs with the spawning user's authority, so this
grants an attacker nothing they did not already have. The confinement exists to stop
a *model* writing outside the workspace, not to contain a hostile local user.

### Schema cache + runtime download

Runtime schema fetches go through
`schema-cache/src/main/java/dev/dediren/schemacache/SchemaCacheModule.java`'s `curlFetcher`,
which forces `--proto '=https'` (no protocol downgrade on redirect), bounds the whole
transfer at 60 seconds to prevent stalled downloads from blocking the export lane
indefinitely, and verifies the download's SHA-256 against a pinned value before trusting it:
the single `OMG_XMI_SCHEMA_SHA256` constant
(`engines/uml-xmi-export/.../schema/SchemaValidation.java`) and the per-file
`OFFICIAL_OEF_SCHEMA_SHA256` map (`engines/archimate-oef-export/.../OefExportEngine.java`).
The offline overrides `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` bypass the
SHA-256 check by design — they only require the supplied file to be non-empty.

### XML parsing & external validator

Generated UML/XMI XML is parsed with a hardened `DocumentBuilderFactory`
(`SchemaValidation.secureXmiDocumentBuilderFactory()`): DOCTYPE declarations
disallowed, `FEATURE_SECURE_PROCESSING` on, XInclude and entity-reference
expansion off. The external `xmllint` schema validator runs with `--nonet`
for both the OMG XMI schema (`engines/uml-xmi-export`) and the OEF
ArchiMate schemas (`engines/archimate-oef-export`).

Both engines invoke that validator through one shared runner,
`schemacache.XmlSchemaValidator` — engines may not depend on each other, so
before it each kept its own copy of the subprocess block and every hardening
fix had to be applied twice. The runner owns two availability properties the
copies did not have:

- **No drain deadlock.** stdout and stderr are drained concurrently. The
  previous code read stdout to EOF *before* stderr, with an unbounded
  `waitFor()` after: a validator that fills the ~64 KiB stderr pipe blocks in
  `write(2)`, so it never exits, so stdout never reaches EOF. A schema-invalid
  document with a systematic per-element violation reaches that volume easily —
  the export hung precisely when it should have reported the schema error, and
  a hang is invisible to an agent that decides from stdout JSON.
- **Bounded wall clock.** A run that exceeds `XmlSchemaValidator.DEFAULT_TIMEOUT`
  (60s) is force-killed and reported as
  `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE`, so a wedged or malicious validator
  binary degrades to a structured non-zero envelope rather than an indefinite
  stall.

The runner returns a code-free outcome; each engine maps it onto its own
published diagnostic codes, so `schema-cache` stays notation-neutral.
`XmlSchemaValidatorTest` pins both properties, including a stderr-flooding
validator as an explicit deadlock regression.

### SVG output escaping

Untrusted model text (node/edge/group labels and ids) flows into the SVG
render surface. Every value is XML-escaped at emission by `SvgWriter`
(`engines/render/.../svg/SvgWriter.java`), the engine's StAX-backed emitter:
element content goes through `text()` and attribute values through
`attr()`/`attrIf()`, which escape structurally. Escaping is therefore a
property of the writer, not a call-site invariant a caller can forget — the
hand-rolled string emitter and its `Svg.attr`/`Svg.text` helpers are gone, and
there is no verbatim-injection path. So a label such as
`</text><script>alert(1)</script>` reaches the output only in escaped form and
cannot break out of its host element. `LabelInjectionTest` (`engines/render`)
drives a breakout payload through a full render and asserts both that the
payload never appears unescaped and that the label round-trips back to the exact
authored string; `SvgAudit` re-parses the emitted SVG and fails on any
ill-formed markup. The rendered SVG carries no `<script>` or `<style>` block at
all — it is inert, fully escaped markup (interactive-svg was retired), so there
is no CSS/script sink for policy values or model text to reach.

The `render` plugin emits SVG as text and no longer bundles Apache Batik /
XML Graphics. Earlier versions round-tripped the emitted SVG through a Batik
SVG DOM and PNG transcoder to produce a `png` artifact, re-parsing the
rendered markup (and its CSS/font references) a second time inside the plugin.
That path is removed: the plugin never re-parses its own output and carries no
Batik XML-parsing surface. PNG output is now produced out of process by a
user-chosen external converter (`rsvg-convert`, `resvg`, ImageMagick, or
Inkscape) that runs outside the dediren trust boundary.

### Build & release chain

`.github/workflows/release.yml` and `ci.yml` pin every GitHub Action to a
commit SHA; the Maven Wrapper is SHA-256-pinned in
`.mvn/wrapper/maven-wrapper.properties`. Every push, pull request, and
release runs a blocking Grype/SBOM scan (`anchore/scan-action`,
`fail-build: true`, `severity-cutoff: high`). The release `build` job
generates artifact provenance attestation (`attest-build-provenance`); the
`publish` job verifies it (`gh attestation verify`) before creating the
GitHub release. Unprotected `main` (no required-review branch protection) is
a documented accepted risk — see `SECURITY.md`.

## Attacker Goals -> Controls

| Attacker goal | Primary control | Residual risk |
| --- | --- | --- |
| Poison a release artifact | SHA-pinned Actions, blocking Grype/SBOM gate, attestation generated and verified before publish | Single-maintainer `main` has no required review (accepted risk, `SECURITY.md`) |
| Tamper `main` or `v*` tags | `release.yml` cross-checks the tag version against `pom.xml`; attestation binds the published archive to its build | No branch protection on `main`; a bad commit is caught only by tests/scans, not review |
| Tampered SBOM / SHA256SUMS after build | Bundle archive carries build provenance attestation | The SBOM and SHA256SUMS themselves are unattested, and the SBOM is regenerated in the publish job rather than carried from the attested build (accepted 2026-07) |
| Malicious schema substitution | HTTPS-only curl plus SHA-256 pin verified before use (`SchemaCacheModule`) | `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` offline overrides bypass the SHA-256 check by design |
| Malicious envelope input | Jackson 3 parsing plus fuzz-regression targets pinning the only-`JacksonException`/`XmiValidationException` invariant; hardened DOM factory blocks DOCTYPE/XXE | Fuzz targets run in deterministic regression mode over a fixed seed corpus in CI, not continuous coverage-guided fuzzing |
| Inject markup into a rendered SVG via model labels/ids | `SvgWriter` (StAX) structurally escapes every attribute value and text node at emission, with no verbatim-injection path; `LabelInjectionTest` proves an end-to-end breakout payload stays escaped and round-trips; `SvgAudit` rejects ill-formed output | The SVG is inert markup with no embedded script; a consumer that embeds it must still apply its own context's policy (e.g. CSP) |
| Dependency compromise | Blocking Grype/SBOM gate on every push/PR/release (`ci.yml`, `release.yml`) plus weekly Dependabot updates (`.github/dependabot.yml`) | The weekly OWASP Dependency-Check cross-check (`dependency-audit.yml`) is intentionally non-blocking (`continue-on-error`), so advisories only it surfaces never gate a merge or release |
| JVM-argument injection via `DEDIREN_LOG_LEVEL` | The launcher interpolates this env var into `JAVA_OPTS`, so it accepts only the six literals `trace\|debug\|info\|warn\|error\|off`; anything else is dropped with a note on stderr. A `-Pdist-smoke` probe asserts a smuggled `-XshowSettings:properties` neither reaches the JVM nor switches logging on | The guard is a shell `case` in the generated launcher; a caller who can already set arbitrary `JAVA_OPTS` needs no such trick, so this only closes the narrower "can set DEDIREN_* but not JAVA_OPTS" path |
| Sensitive data disclosed in debug logs | Logging is `off` by default and must be switched on per run; first-party code cannot log above `debug` (ArchUnit-enforced), and logs go to stderr, never the stdout envelope | With `DEDIREN_LOG_LEVEL=debug` a log line carries filesystem paths (schema cache, schema files), schema URLs, engine ids, and node/edge **counts**. No current call site logs model element ids, labels, or document content — keep it that way: a label is author-supplied text, and logging one would turn a debug switch into a content-disclosure channel. Debug output should still not be pasted into a public issue unreviewed, since the paths alone can leak a local directory layout |

## Incident Response Runbook

1. **Intake**: report through GitHub private vulnerability reporting — same
   URL as `SECURITY.md`.
2. **Triage** against the SLAs in `SECURITY.md`'s "Response Expectations"
   section; do not re-derive them here.
3. **Fix on `main`** — this repo takes direct commits to `main` per
   `git-workflow-policy` in `CLAUDE.md`; branches are optional.
4. **Release** through the attested release workflow
   (`.github/workflows/release.yml`): SBOM + Grype gate, build, attest
   provenance, verify attestation, publish.
5. **Disclose** via the GitHub release notes and, for a confirmed
   vulnerability, a GitHub security advisory.
6. **Post-fix**: add a regression test for the failure class (fuzz seed
   corpus entry, unit test, or fixture) and update this threat model in the
   same change if a described surface changed.

## Maintenance Rule

This page changes in the same commit/PR as any change to the trust
boundaries it describes: the single-JVM engine runtime, schema-cache
fetching, envelope validation, XML parser hardening, or release workflows.

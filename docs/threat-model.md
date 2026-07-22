# Threat Model

This page enumerates Dediren's trust boundaries, the controls that guard
them, and the incident-response runbook, in one auditable place. It replaces
scattered code comments and design-spec asides as the standing reference for
security-posture questions (audit finding F8).

## Assets

- Released agent-bundle archives (`dediren-agent-bundle-*.tar.xz`).
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

Build artifacts additionally carry a provenance stamp (wave 2): core injects
an inert SVG `<metadata>` element / leading XML comment holding
product-generated JSON (canonical hashes, schema id, tool version, a
charset-constrained view id) after each engine has validated its own content.
Every stamped value is product-generated or constrained and the JSON is
XML-escaped at injection, so the stamp introduces no untrusted verbatim path
into any artifact; SVG output remains inert (no script/style).

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
the export engines receive the CLI's env map explicitly (schema-path and
validator-override variables) and read nothing else, pinned by the engines'
no-`getenv` guard tests (Task 4).

### MCP stdio server (`dediren mcp`)

`dediren mcp` (module `mcp-server`, launched by `cli`'s `McpCommand`) is a
long-lived, model-driven process holding a filesystem write primitive
(`dediren_build`) alongside read primitives over source models and workspace
directories. It is the one boundary where a *model* — not a human — chooses the
paths, and MCP clients
frequently auto-approve tool calls, so the CLI's "a human typed this path"
posture does not transfer.

There is no network *listener*: stdio transport only, no port, no HTTP/SSE
listener, no multi-client daemon, and the MCP client spawns the process and owns
its lifetime, so there is no daemon lifecycle to supervise. That is inbound-only,
though: a `dediren_build` call whose policy selects the OEF or XMI export lane
reaches the same outbound-HTTPS boundary as the CLI's `build` and
`export` commands (see "Schema cache + runtime download" and "XML parsing &
schema validation" below) — absent a cached or offline schema, a `curl` fetch;
schema validation itself runs in-JVM on both lanes with no subprocess.
`--read-only` withholds `dediren_build` entirely. Short of that, the
`DEDIREN_OEF_SCHEMA_DIR` / `DEDIREN_XMI_SCHEMA_PATH` offline overrides remove
the outbound fetch.

Controls:

- **Workspace-root confinement.** Every tool path argument is resolved against the
  `--root` (default: cwd) and real-path-resolved *before* the containment check.
  The algorithm has exactly one implementation —
  `core/src/main/java/dev/dediren/core/io/ConfinedPaths.java` — which
  `mcp-server`'s `WorkspacePaths` adapts onto the sanitized MCP error surface
  (previously two hand-mirrored copies that had already diverged on
  normalization order). Normalization alone is
  insufficient — a symlink inside the root pointing outside is the interesting case,
  and only `toRealPath()` catches it; the path stays *unnormalized* until it is
  anchored on a real existing ancestor, so a `link/..` sequence is resolved
  physically, never collapsed lexically. For an output directory that need not
  exist, the nearest existing ancestor is resolved instead (the walk does not
  follow symlinks). An escaping path yields a
  `DEDIREN_MCP_PATH_OUTSIDE_ROOT` error envelope. Pinned by `WorkspacePathsTest`.
  The confinement also covers the *second* class of model-supplied paths: a source
  document's `fragments[]` entries. The MCP handlers pass `--root` as an optional
  confinement root into core's source loader (`SourceValidator`), which applies the
  same shared `ConfinedPaths` containment to each fragment before
  reading it (the CLI/human lane passes no root and is unconfined). An escaping
  fragment yields the same `DEDIREN_MCP_PATH_OUTSIDE_ROOT` envelope, and both the
  escape and any in-root read failure are sanitized to echo only the model's own
  relative fragment string — never the resolved absolute path and never a
  distinguishable exists-vs-not-exists signal. Reachable under `--read-only`,
  since `dediren_validate`, `dediren_diff`, `dediren_query`, and `dediren_verify`
  all load source models (and thus fragments). The read-only `dediren_verify` and
  `dediren_status` add a second read shape — a *directory* argument (`artifacts` /
  `dir`) — confined by the same `WorkspacePaths.resolveExisting` real-path check;
  their result envelopes report artifact paths relative to that directory, so no
  absolute server path is echoed back to the model. `dediren_status` in
  particular *enumerates* the whole confined subtree (every model and stamped
  artifact under `dir`, default the root) — a workspace-structure disclosure
  broader in shape than reading a single named path, but still bounded by the
  `--root` the model is already trusted to read within. Pinned by
  `DedirenToolsTest`, `SourceValidatorTest`, and `CliMcpParityTest`.
- **Read-only mode.** `--read-only` does not register `dediren_build` at all, so the
  write primitive is absent rather than present-and-refusing. The four analysis
  tools (`dediren_diff`, `dediren_query`, `dediren_verify`, `dediren_status`) are
  read-only and stay registered in both modes.
- **Resources serve product bytes only.** The MCP resources surface
  (`dediren://schema/…`, `dediren://fixture/…`, `dediren://guide/…`,
  `dediren://diagnostics/catalog`) enumerates and reads exclusively under the
  product root (the bundle's own `schemas/`, `fixtures/`, and packaged guide) —
  never a workspace path, never a model-supplied path: `resources/read` resolves
  a URI against the startup-enumerated set, so there is no path parameter to
  confine and no new read primitive into the workspace. Served identically under
  `--read-only`.
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
indefinitely — backed by a Java-side 75-second `waitFor` that `destroyForcibly`-kills a
fetcher binary that ignores or lacks the `--max-time` flag, so a hung child degrades to a
structured fetch failure instead of blocking the export thread forever (an interrupt during
the wait also kills the child and re-sets the flag, so cancellation propagates) — and
verifies the download's SHA-256 against a pinned value before trusting it:
the single `OMG_XMI_SCHEMA_SHA256` constant
(`engines/uml-xmi-export/.../schema/SchemaValidation.java`) and the per-file
`PINNED_OEF_SCHEMA_SET` table (`engines/archimate-oef-export/.../OefExportEngine.java`),
which pins the three Open Group OEF XSDs (opengroup.org) plus the W3C
`xml.xsd` they import (www.w3.org — the one non-standards-body-of-origin
endpoint in the fetch set; the in-JVM validator resolves the import from the
local copy, never from the network).
The offline overrides `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` bypass the
SHA-256 check by design — they only require the supplied file to be non-empty.

### XML parsing & schema validation

Generated UML/XMI XML is parsed with a hardened `DocumentBuilderFactory`
(`SchemaValidation.secureXmiDocumentBuilderFactory()`): DOCTYPE declarations
disallowed, `FEATURE_SECURE_PROCESSING` on, XInclude and entity-reference
expansion off.

Standards-schema validation runs **in-JVM on both export lanes** via the one
shared validator, `schemacache.InJvmXmlValidator` (engines may not depend on
each other, so hardening lives once): `javax.xml.validation` with secure
processing on, external DTD/schema access denied, and schema imports (the W3C
`xml.xsd` the ArchiMate XSDs reference; a UML XSD beside an XMI driver schema)
resolved **local-only** from the schema file's own directory, confinement
enforced by a normalize/startsWith check — nothing is fetched at validation
time and no validator subprocess exists anywhere in the product. The retired
`xmllint` lane's guards carry over in in-process form:

- **Bounded wall clock.** Compile+validate run on a bounded daemon worker;
  exceeding `InJvmXmlValidator.DEFAULT_TIMEOUT` (60s) is reported as a
  structured `DEDIREN_OEF_SCHEMA_UNAVAILABLE` / `DEDIREN_XMI_SCHEMA_UNAVAILABLE`
  rather than an indefinite stall (a pathological schema in a hand-supplied
  directory cannot hang the envelope; the abandoned worker thread ends with the
  process, since an in-process computation cannot be force-killed the way a
  subprocess could). Because such a worker cannot be reclaimed, a concurrency
  gate (`InJvmXmlValidator.MAX_CONCURRENT_VALIDATIONS`) bounds how many run at
  once: a submission that would exceed it fails fast to the same
  `*_SCHEMA_UNAVAILABLE` lane, so repeated timeouts degrade to unavailability
  instead of leaking worker threads without limit.
- **Broken-validator vs invalid-document split.** Schema-set problems
  (unresolved import, unreadable file, validator configuration failure,
  timeout) throw to the engines' `*_SCHEMA_UNAVAILABLE` lane; only genuine
  document-validity findings become `*_SCHEMA_INVALID`, so an environment
  problem is never misreported as a defect in the generated XML. The thrown
  `SchemaCacheException` carries a failure `Kind` (schema-set / config /
  timeout / saturated / fetch) and the engines append class-appropriate advice:
  schema-placement/proxy remediation only for a missing or broken schema set,
  "transient, retry" for saturation — so an agent is never told to reconfigure
  a proxy for a capacity blip.
- **Compiled-grammar reuse.** A compiled grammar is memoized per top-file path
  and served only while every file that shaped it — the top schema and the
  imports it resolved — still matches its (size, mtime) stamp. Each stamp is
  captured when the file is *read* (top schema before compile, each import
  before its bytes are served), never after the compile, so a file rewritten
  mid-compile can only trigger an extra recompile on the next call — it can
  never pin a stale grammar as fresh; the pinned cache-lane contents are
  additionally SHA-256-verified at fetch time.

The validator returns a code-free outcome the calling engine maps onto its own
published diagnostic codes, so `schema-cache` stays notation-neutral, and every
successful export declares what it was validated against via the
`DEDIREN_EXPORT_SCHEMA_CONFORMANCE` info diagnostic. `InJvmXmlValidatorTest`
pins the seam: local-only resolution and traversal confinement, the
unavailable-vs-invalid split, single-recording of fatal errors, dependency-aware
memoization, the bounded-run timeout, and the saturation fail-fast. The only subprocess left in the product is the
schema-cache `curl` fetch (previous section), which keeps its own concurrent
drain, 60-second transfer bound, and Java-side 75-second kill backstop.

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
`.mvn/wrapper/maven-wrapper.properties`. Every pull request and tagged release
runs a blocking Grype/SBOM scan (`anchore/scan-action`, `fail-build: true`,
`severity-cutoff: high`); direct pushes to `main` carry no CI (lean-CI
decision) — they rely on the local verification lanes and are re-gated at the
next pull request or release. The release `build` job generates
one provenance attestation covering the archive, both CycloneDX SBOM
serializations, **and** the `SHA256SUMS` checksum file
(`attest-build-provenance`), so the SBOM and checksums are bound to the attested
build rather than regenerated downstream; the `publish` job verifies every
attested subject (`gh attestation verify`), checks the staged assets against the
attested `SHA256SUMS` (`sha256sum -c`), and then creates the release as a
draft with all assets attached before flipping it to published. The attested
`build` job restores no cross-run cache: every dependency cold-resolves from
Maven Central under Maven's strict-checksums flag, so the provenance's input
set is the tagged commit plus checksum-verified downloads. Repository
release immutability is enabled, so publishing freezes the complete, attested
asset set (releases published before 2026-07-23 predate the setting and remain
mutable). Repository rulesets (declarative source: `.github/rulesets/`) block
force-pushes and deletion of `main` and moving or deleting `v*` tags, and
require the `test`/`vulnerability-scan` checks before `main` moves — with a
recorded repository-admin bypass preserving the solo direct-push workflow. The
absence of required review on `main` remains a documented accepted risk — see
`SECURITY.md`.

The bundle `lib/` jar is produced by a shrink ProGuard pass over the staged
launcher classpath (`dist-tool`, keep rules checked in at
`dist-tool/src/main/resources/dev/dediren/tools/dist/bundle-shrink.pro`); the
pass does no optimization and no renaming — ProGuard's obfuscation phase runs
only as an attribute filter with every class and member name pinned.
ProGuard is a pinned, SBOM-scanned build-time dependency and never ships. The
`.tar.xz` archive is compressed by the build machine's distro `xz` (a
standard, distro-verified package on the runner; consumers extract with
their own `tar`/`xz` or libarchive). The `-Pdist-smoke` gate exercises every
pipeline against the packaged shrunk archive, and the smoke's archive-size
ceiling trips if shrinking or attribute stripping silently degrades.

## Attacker Goals -> Controls

| Attacker goal | Primary control | Residual risk |
| --- | --- | --- |
| Poison a release artifact | SHA-pinned Actions, blocking Grype/SBOM gate, attestation generated and verified before publish | Single-maintainer `main` has no required review (accepted risk, `SECURITY.md`) |
| Tamper `main` or `v*` tags | Rulesets block force-pushes/deletion of `main` and moving/deleting `v*` tags (`.github/rulesets/`); `test` + `vulnerability-scan` are required checks on `main` (admin bypass, recorded); release immutability freezes released tags; `release.yml` cross-checks the tag version against `pom.xml`; attestation binds the published archive to its build | No required review on `main`, and the maintainer's admin bypass skips the required checks on direct pushes (each bypass is recorded); a bad commit is caught by tests/scans, not review |
| Tampered SBOM / SHA256SUMS after build | The archive, both CycloneDX SBOM serializations, and `SHA256SUMS` are subjects of one build-provenance attestation, each verified before publish; the publish job additionally checks the staged assets against the attested `SHA256SUMS` (`sha256sum -c`); repository release immutability (enabled) freezes the published asset set | Immutability covers only releases published after it was enabled (2026-07-22); earlier releases remain mutable and rest on their attestations alone |
| Shipped `THIRD-PARTY-NOTICES.md` misstates an upstream licence after a dependency bump, or a bump drags in a licence outside the approved set | cli's `license-maven-plugin` execution resolves every runtime dependency's effective-pom licence, normalizes it, and gates it against an approved allowlist; `DistTool` refuses to write notices when its hand-curated attribution map disagrees with that resolved report or the report is stale (`resolved-licence-report`, dist lanes) | Effective-pom licences are upstream-declared metadata, not scanned artifact contents; a pom that misstates its own jar's licence passes (mitigate with an `about.html`/`META-INF` spot-check when adopting a new dependency) |
| Malicious schema substitution | HTTPS-only curl plus SHA-256 pin verified before use (`SchemaCacheModule`) | `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` offline overrides bypass the SHA-256 check by design |
| Malicious envelope input | Jackson 3 parsing plus fuzz-regression targets pinning the only-`JacksonException`/`XmiValidationException` invariant; hardened DOM factory blocks DOCTYPE/XXE | Fuzz targets run in deterministic regression mode over a fixed seed corpus in CI, not continuous coverage-guided fuzzing |
| Inject markup into a rendered SVG via model labels/ids | `SvgWriter` (StAX) structurally escapes every attribute value and text node at emission, with no verbatim-injection path; `LabelInjectionTest` proves an end-to-end breakout payload stays escaped and round-trips; `SvgAudit` rejects ill-formed output | The SVG is inert markup with no embedded script; a consumer that embeds it must still apply its own context's policy (e.g. CSP) |
| Dependency compromise | Blocking Grype/SBOM gate on every pull request and tagged release (`ci.yml`, `release.yml`); weekly grouped Dependabot updates plus event-driven Dependabot alerts (`.github/dependabot.yml`) | Direct pushes to `main` are not CI-scanned (lean-CI decision), so an advisory published between releases surfaces via Dependabot alerts or the next PR/release gate rather than a push-time scan; the scheduled OWASP Dependency-Check cross-check was retired with the same decision (`-Psecurity-sca` remains an on-demand local second opinion) |
| JVM-argument injection via `DEDIREN_LOG_LEVEL` | The launcher interpolates this env var into `JAVA_OPTS`, so it accepts only the six literals `trace\|debug\|info\|warn\|error\|off`; anything else is dropped with a note on stderr. A `-Pdist-smoke` probe asserts a smuggled `-XshowSettings:properties` neither reaches the JVM nor switches logging on | The guard is a shell `case` in the generated launcher; a caller who can already set arbitrary `JAVA_OPTS` needs no such trick, so this only closes the narrower "can set DEDIREN_* but not JAVA_OPTS" path |
| A model reads or writes outside the workspace via an MCP tool (`dediren mcp`) | Every tool path argument resolves against `--root` and is `toRealPath()`-resolved *before* the containment check, so a symlink inside the root pointing outside it is rejected, not followed (`WorkspacePaths`); an escape yields `DEDIREN_MCP_PATH_OUTSIDE_ROOT`. Model-supplied source `fragments[]` paths — the second read primitive — are confined the same way by core's `SourceValidator` under the same `--root`, with fragment errors sanitized so they cannot leak an absolute path or an exists-vs-not oracle; this covers the read-only `dediren_validate`/`dediren_diff`/`dediren_query`/`dediren_verify` source loads (and the directory reads of `dediren_verify`/`dediren_status`, whose result envelopes report paths relative to the passed directory), so it holds under `--read-only`. `--read-only` withholds `dediren_build` entirely, so the *write* primitive is absent rather than present-and-refusing | Resolve-then-open is not atomic (TOCTOU): a local attacker able to create symlinks inside the root during the window can defeat the check. Accepted — the server already runs with the spawning user's authority, so this grants nothing they did not already have. The control exists to stop a *model* reading or writing outside the workspace, not to contain a hostile local user |
| A stray write corrupts the MCP JSON-RPC frame stream | In stdio MCP stdout *is* the protocol channel, so `StdoutIntegrity.claimStdout()` hands the transport the real stdout file descriptor and repoints `System.out` at stderr — a stray print anywhere in `core`, an engine, or a dependency degrades to log noise instead of corrupting a frame. The `-Pdist-smoke` probe asserts every stdout line of a real `bin/dediren mcp` run is a JSON-RPC frame | A frame silently lost after the session closes is the failure mode this boundary is most exposed to; requests are id-correlated and held open until answered, and an expired backstop names the unanswered ids on stderr rather than exiting quietly |
| Shipped classes silently diverge from vetted dependencies (shrinker defect or compromised ProGuard) | ProGuard version pinned in root `dependencyManagement` and resolved from Maven Central like every dependency (aggregate SBOM + Grype gate scan it); the pass does no optimization and no renaming (the obfuscation phase runs only as an attribute filter, names pinned) with keep rules reviewed in-repo and warning suppression scoped to named optional platforms (an unexpected unresolved reference fails the dist build); `-Pdist-smoke` drives layout, render, both exports, and MCP stdio against the packaged shrunk archive on every pull request (`ci.yml`) and release build | Reachability shrinking can drop reflection-only code paths the smoke never exercises; the SBOM lists upstream components while shipped bytes are shrunk subsets, so per-jar upstream hash comparison no longer applies — the bundle-level provenance attestation remains the integrity signal |
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

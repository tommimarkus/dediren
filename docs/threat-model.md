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

### Schema cache + runtime download

Runtime schema fetches go through
`schema-cache/src/main/java/dev/dediren/schemacache/SchemaCacheModule.java`'s
`curlFetcher`, which forces `--proto '=https'` (no protocol downgrade on
redirect) and verifies the download's SHA-256 against a pinned value before
trusting it: the single `OMG_XMI_SCHEMA_SHA256` constant
(`engines/uml-xmi-export/.../schema/SchemaValidation.java`) and the per-file
`OFFICIAL_OEF_SCHEMA_SHA256` map (`engines/archimate-oef-export/.../OefExportEngine.java`).
The offline overrides `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR`
bypass the SHA-256 check by design — they only require the supplied file to
be non-empty.

### XML parsing & external validator

Generated UML/XMI XML is parsed with a hardened `DocumentBuilderFactory`
(`SchemaValidation.secureXmiDocumentBuilderFactory()`): DOCTYPE declarations
disallowed, `FEATURE_SECURE_PROCESSING` on, XInclude and entity-reference
expansion off. The external `xmllint` schema validator runs with `--nonet`
for both the OMG XMI schema (`engines/uml-xmi-export`) and the OEF
ArchiMate schemas (`engines/archimate-oef-export`).

### SVG output escaping

Untrusted model text (node/edge/group labels and ids) flows into the SVG
render surface. Every value is XML-escaped at emission — `Svg.text()` for
element content and `Svg.attr()` for attribute values — so a label such as
`</text><script>alert(1)</script>` reaches the output only in escaped form and
cannot break out of its host element. `LabelInjectionTest` (`engines/render`)
drives a breakout payload through a full render and asserts both that the
payload never appears unescaped and that the label round-trips back to the exact
authored string; `SvgAudit` re-parses the emitted SVG and fails on any
ill-formed markup. The interaction `<script>` layer is opt-in (`interactive`
policy other than the default `none`) and never carries model text.

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
| Malicious schema substitution | HTTPS-only curl plus SHA-256 pin verified before use (`SchemaCacheModule`) | `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` offline overrides bypass the SHA-256 check by design |
| Malicious envelope input | Jackson 3 parsing plus fuzz-regression targets pinning the only-`JacksonException`/`XmiValidationException` invariant; hardened DOM factory blocks DOCTYPE/XXE | Fuzz targets run in deterministic regression mode over a fixed seed corpus in CI, not continuous coverage-guided fuzzing |
| Inject markup into a rendered SVG via model labels/ids | `Svg.text()`/`Svg.attr()` XML-escape all model text at emission; `LabelInjectionTest` proves an end-to-end breakout payload stays escaped and round-trips; `SvgAudit` rejects ill-formed output | The SVG is inert markup; a consumer that embeds it must still apply its own context's policy (e.g. CSP), and the opt-in interaction `<script>` runs in the viewer |
| Dependency compromise | Blocking Grype/SBOM gate on every push/PR/release (`ci.yml`, `release.yml`) plus weekly Dependabot updates (`.github/dependabot.yml`) | The weekly OWASP Dependency-Check cross-check (`dependency-audit.yml`) is intentionally non-blocking (`continue-on-error`), so advisories only it surfaces never gate a merge or release |

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

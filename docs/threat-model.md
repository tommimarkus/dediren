# Threat Model

This page enumerates Dediren's trust boundaries, the controls that guard
them, and the incident-response runbook, in one auditable place. It replaces
scattered code comments and design-spec asides as the standing reference for
security-posture questions (audit finding F8).

## Assets

- Released agent-bundle archives (`dediren-agent-bundle-*.tar.gz`).
- `main` branch and `v*` tag integrity.
- The plugin execution boundary on user machines.
- Schema cache content (downloaded OMG XMI / OEF ArchiMate schemas).
- The envelope JSON contract surface (stdin/stdout between agents, CLI, and
  plugins).

## Trust Boundaries

### Envelope JSON stdin/stdout (agents -> CLI/plugins)

Parsing goes through `contracts/src/main/java/dev/dediren/contracts/json/JsonSupport.java`'s
Jackson 3 (`tools.jackson`) `ObjectMapper`: snake_case properties, fail on
unknown properties, non-null-only output. Fuzz-regression targets pin the
failure contract: `JsonSupportFuzzTest` (`contracts`) and
`SchemaValidationFuzzTest` (`plugins/uml-xmi-export`) assert only
`JacksonException` / `XmiValidationException` may escape parsing, running in
deterministic regression mode over checked-in seed corpora in CI.

### Plugin process boundary

Discovery is explicit only, never `PATH`
(`core/src/main/java/dev/dediren/core/plugins/PluginRegistry.java`): bundled
first-party plugins, then (opt-in via `DEDIREN_ALLOW_PROJECT_PLUGINS`) the
caller's cwd `.dediren/plugins`, then the bundle-root `.dediren/plugins`,
then `DEDIREN_PLUGIN_DIRS`. Only the bundled directory is trusted; a
manifest found elsewhere can never claim `DEDIREN_TRUST_MANIFEST_CAPABILITIES`
to skip the runtime capabilities probe
(`core/.../plugins/PluginRunner.java`). `PluginRunner` also enforces a
manifest-declared env allowlist and always runs the child from a
deterministic working directory (the product root), not the caller's cwd.

### Schema cache + runtime download

Runtime schema fetches go through
`schema-cache/src/main/java/dev/dediren/schemacache/SchemaCacheModule.java`'s
`curlFetcher`, which forces `--proto '=https'` (no protocol downgrade on
redirect) and verifies the download's SHA-256 against a pinned value before
trusting it: the single `OMG_XMI_SCHEMA_SHA256` constant
(`plugins/uml-xmi-export/.../schema/SchemaValidation.java`) and the per-file
`OFFICIAL_OEF_SCHEMA_SHA256` map (`plugins/archimate-oef-export/.../Main.java`).
The offline overrides `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR`
bypass the SHA-256 check by design — they only require the supplied file to
be non-empty.

### XML parsing & external validator

Generated UML/XMI XML is parsed with a hardened `DocumentBuilderFactory`
(`SchemaValidation.secureXmiDocumentBuilderFactory()`): DOCTYPE declarations
disallowed, `FEATURE_SECURE_PROCESSING` on, XInclude and entity-reference
expansion off. The external `xmllint` schema validator runs with `--nonet`
for both the OMG XMI schema (`plugins/uml-xmi-export`) and the OEF
ArchiMate schemas (`plugins/archimate-oef-export`).

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
| Malicious plugin on a user machine | Explicit discovery only (never `PATH`); project-plugin dirs opt-in via `DEDIREN_ALLOW_PROJECT_PLUGINS`; env allowlist + deterministic cwd in `PluginRunner` | Enabling `DEDIREN_ALLOW_PROJECT_PLUGINS` or configuring `DEDIREN_PLUGIN_DIRS` is a user decision to execute plugins found there; the capabilities probe still runs for those manifests, but the probe itself launches the discovered executable, so discovery of a malicious directory is code execution by configuration |
| Malicious schema substitution | HTTPS-only curl plus SHA-256 pin verified before use (`SchemaCacheModule`) | `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` offline overrides bypass the SHA-256 check by design |
| Malicious envelope input | Jackson 3 parsing plus fuzz-regression targets pinning the only-`JacksonException`/`XmiValidationException` invariant; hardened DOM factory blocks DOCTYPE/XXE | Fuzz targets run in deterministic regression mode over a fixed seed corpus in CI, not continuous coverage-guided fuzzing |
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
boundaries it describes: plugin discovery/execution, schema-cache fetching,
envelope validation, XML parser hardening, or release workflows.

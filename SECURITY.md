# Security Policy

## Reporting A Vulnerability

Report suspected vulnerabilities through GitHub private vulnerability reporting:

https://github.com/tommimarkus/dediren/security/advisories/new

Do not open public issues for exploitable vulnerabilities. Include the affected
version or commit, a minimal reproduction, expected impact, and any known
workarounds.

## Response Expectations

This repository is pre-release. Maintainers should acknowledge valid reports
within 7 calendar days, triage severity, and document the fix or accepted risk
before publishing a release that includes the affected code.

Critical vulnerabilities should be fixed or have an explicitly documented
release-blocking exception before the next release. High-severity
vulnerabilities should be fixed within 30 days or documented as accepted risk
with reachability analysis.

## Supply-Chain Target

GitHub release archives target SLSA Build Level 2 evidence:

- release workflow jobs run on GitHub-hosted runners with SHA-pinned actions;
- archive provenance is generated with GitHub artifact attestations;
- each release publishes CycloneDX SBOMs and a `SHA256SUMS` checksum file that
  is itself an attested build subject;
- the publish job verifies every asset's attestation before creating the
  release, and repository release immutability freezes the published asset set;
- first-party Java is statically analyzed by CodeQL on pull requests, pushes to
  `main`, and weekly; high-severity alerts surface in the repository's code
  scanning view.

See `docs/threat-model.md` for the full trust-boundary breakdown behind these
controls.

## Branch Protection (partial)

While the project is pre-release and maintained by a single author, `main` has
no required-review protection and the maintainer keeps a direct-push workflow.
Repository rulesets (declarative source: `.github/rulesets/`) bound that
accepted risk:

- `main protection` blocks force-pushes and deletion of `main`, with no bypass;
- `main required checks` requires the `test` and `vulnerability-scan` checks
  (`ci.yml`) before `main` moves — repository admins bypass it, and every
  bypass is recorded as a bypass event;
- `release tags` blocks moving or deleting `v*` tags; released tags are
  additionally frozen by release immutability.

Compensating controls still run on every push and pull request: the blocking
Grype/SBOM supply-chain gate (fails on High/Critical advisories), the full test
suite, and the whitespace check (`ci.yml`); `CODEOWNERS` records review
ownership.

This posture is revisited when the project starts accepting external
contributions or reaches a stable release. At that point, drop the admin
bypass from `main required checks` (and, optionally, require `CODEOWNERS`
review).

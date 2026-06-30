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
- each release publishes archive checksums and CycloneDX SBOMs;
- the publish job verifies archive attestations before creating the release;
- first-party Java is statically analyzed by CodeQL on pull requests, pushes to
  `main`, and weekly; high-severity alerts surface in the repository's code
  scanning view.

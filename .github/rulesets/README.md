# Repository rulesets

Declarative source for the GitHub rulesets active on this repository. GitHub
does not read this directory automatically: after editing a file, re-import it
(**Settings → Rules → Rulesets → New ruleset → Import a ruleset**) and delete
the superseded ruleset, so the live state and these files stay in lockstep.

- `main-protection.json` — blocks force-pushes and deletion of `main`; no
  bypass, so it also guards against maintainer accidents.
- `main-required-checks.json` — requires the `test` and `vulnerability-scan`
  checks (`ci.yml`) before `main` moves; repository admins bypass with a
  recorded bypass event, preserving the solo direct-push workflow
  (`SECURITY.md`).
- `release-tags.json` — blocks moving or deleting `v*` tags (creation stays
  free); released tags are additionally frozen by release immutability.

The bypass and the split into three rulesets are deliberate: the accident
guards must bind even repository admins, while the required-checks rule keeps
an admin bypass, so they cannot share one ruleset.

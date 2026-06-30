# DevSecOps Audit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the actionable findings from the 2026-06-30 DevSecOps deep audit — harden the XMI validation parser, add first-party static analysis (SAST), make release notes informative, and record the deliberately-deferred branch-protection control as an explicit accepted risk.

**Architecture:** Four independent changes plus a manual-settings appendix. One internal code hardening (`plugins/uml-xmi-export`), one new CI workflow (CodeQL), one one-line release-workflow change, and one security-docs change. None alter the public JSON/XMI contract, so no schema/fixture churn.

**Tech Stack:** Java 21 (Maven Wrapper `./mvnw`), JUnit 5 + AssertJ, GitHub Actions, CodeQL, `gh` CLI.

**Source of truth:** Findings F1–F6 from the deep audit. F1 (branch protection) is **deferred per maintainer decision** — Task 4 records that deferral instead of enabling protection. F2 (Dependabot alerts) and secret-scanning confirmation are GitHub-settings toggles with no repo file → Appendix A.

## Global Constraints

- **Java floor:** Java 21+, no upper bound. Build only via the checked-in Maven Wrapper (`./mvnw`). Never edit `mvnw`, `mvnw.cmd`, or `.mvn/wrapper/*` (protected surfaces).
- **Action pinning invariant:** every `uses:` in any workflow is pinned to a 40-char commit SHA with a trailing `# vX.Y.Z` comment. The repo is currently 26/26 pinned — do not regress. Reuse the exact SHAs already in `ci.yml` for shared actions.
- **Per-job workflow hygiene:** declare minimal `permissions:` (default `contents: read`), set `timeout-minutes:`, and `persist-credentials: false` on `actions/checkout`.
- **Cost stance: free.** Task 2 uses CodeQL default code scanning (free on **public** repos). If `tommimarkus/dediren` is private without GitHub Advanced Security, the SARIF upload step will fail — in that case use the SpotBugs+FindSecBugs Maven alternative documented in Task 2's note instead of CodeQL.
- **No contract change:** the Task 1 hardening is parser-internal. The generated XMI bytes are unchanged, so `schemas/`, `contracts`, fixtures, README, and `docs/agent-usage.md` stay untouched. Do **not** refresh any `.xmi` golden fixture for this change.
- **Tests:** JUnit 5 (Jupiter) + AssertJ. Same-package tests may call package-private members.
- **Environment caveat (Claude Code sandbox):** `./mvnw` test runs need the sandbox **disabled** — JUnit `@TempDir` (used in `MainTest`) requires a writable `/tmp`. Module-scoped single-test runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. SSH-signed commits also need the sandbox disabled.
- **Git hygiene:** direct commits to `main` are allowed; keep the worktree clean; stage **explicit paths only** (never `git add -A`); one scoped commit per task. **No version bump** — these are non-release content changes; leave the `pom.xml` version untouched (release-policy governs bumps separately).

---

### Task 1: Harden the XMI validation parser against DTD / XXE (F5)

The XMI export self-validates its generated output by DOM-parsing it (`validateXmiDocumentAndIds`, `plugins/uml-xmi-export/.../Main.java:1132`). The parser is created with `DocumentBuilderFactory.newInstance()` + `setNamespaceAware(true)` only — no DTD/entity hardening. Today the parsed input is always Dediren's own generated XMI (no DOCTYPE), so this is **defense-in-depth, not a live vulnerability** — but it is cheap insurance against XXE/entity-expansion if that parser is ever pointed at external input. We extract a package-private secure-factory helper and test its DTD-rejection behavior directly.

**Files:**
- Modify: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java` (imports near line 42; method at line 1132; new helper after line 1175)
- Test: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `static DocumentBuilderFactory secureXmiDocumentBuilderFactory() throws ParserConfigurationException` (package-private) in `dev.dediren.plugins.umlxmi.Main`.

- [ ] **Step 1: Write the failing test**

Add to `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`. Add these imports (after the existing imports at the top of the file, lines 3–14):

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import org.xml.sax.SAXParseException;
```

Add this test method inside the `class MainTest { ... }` body:

```java
    @Test
    void xmiValidationParserRejectsDoctype() throws Exception {
        var builder = Main.secureXmiDocumentBuilderFactory().newDocumentBuilder();
        String hostile = "<!DOCTYPE XMI [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<XMI xmlns=\"http://www.omg.org/spec/XMI/20131001\">&xxe;</XMI>";

        assertThatThrownBy(() ->
                builder.parse(new ByteArrayInputStream(hostile.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(SAXParseException.class)
                .hasMessageContaining("DOCTYPE");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run (disable the sandbox if prompted):

```bash
./mvnw -pl plugins/uml-xmi-export -am test \
  -Dtest='MainTest#xmiValidationParserRejectsDoctype' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **compile failure** — `cannot find symbol: method secureXmiDocumentBuilderFactory()`.

- [ ] **Step 3: Add the secure-factory helper and use it**

In `Main.java`, add two imports next to the existing XML imports (currently lines 42–43):

```java
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
```

Add this package-private helper immediately after the `validateXmiDocumentAndIds` method (i.e. after line 1175, before `validateOmgXmiSchema`):

```java
    // Defense in depth: this validator only ever parses Dediren's own generated
    // XMI, which never contains a DOCTYPE. Hardening the factory keeps XXE and
    // entity-expansion classes off the table regardless of what the parser is
    // ever pointed at. Package-private so the same-package test can exercise it.
    static DocumentBuilderFactory secureXmiDocumentBuilderFactory() throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
```

Then replace the inline factory creation in `validateXmiDocumentAndIds`. Change these two lines (currently 1134–1135):

```java
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
```

to:

```java
            var factory = secureXmiDocumentBuilderFactory();
```

(The surrounding `try { ... } catch (Exception error) { ... }` already catches the declared `ParserConfigurationException`, so no other change is needed.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw -pl plugins/uml-xmi-export -am test \
  -Dtest='MainTest#xmiValidationParserRejectsDoctype' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: **PASS**.

- [ ] **Step 5: Run the module + CLI lane to confirm no regression**

This proves the existing full-export tests (which parse real generated XMI through the now-hardened factory) still pass:

```bash
./mvnw -pl plugins/uml-xmi-export,cli -am test
```

Expected: **BUILD SUCCESS**, `MainTest` green including `outputsXmi` and the new test.

- [ ] **Step 6: Commit**

```bash
git add plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java \
        plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java
git commit -m "fix(uml-xmi): harden XMI validation parser against DTD/XXE"
```

---

### Task 2: Add CodeQL static analysis (SAST) for first-party code (F3)

The repo gates dependencies (Grype + OWASP Dependency-Check) but runs no static analysis of its own Java — the code that parses untrusted model JSON, generates XML/SVG/XMI, and spawns subprocesses. Add a CodeQL workflow that runs on PRs, pushes to `main`, and weekly, matching the repo's existing workflow conventions. Record the new control in `SECURITY.md`.

**Files:**
- Create: `.github/workflows/codeql.yml`
- Modify: `SECURITY.md` (append a bullet to the `## Supply-Chain Target` list)

**Interfaces:**
- Consumes: nothing. Produces: nothing other tasks depend on.

> **Note — public vs private repo.** CodeQL default code scanning is free for **public** repos. `tommimarkus/dediren` publishes public releases, so it is almost certainly public. If it is private without GitHub Advanced Security, the `analyze` step's SARIF upload will fail with "Advanced Security must be enabled". In that case, instead of this workflow, add `com.github.spotbugs:spotbugs-maven-plugin` with the `findsecbugs-plugin` to the Maven build under a `security-sast` profile and run it in `ci.yml` (gates without any GitHub-side feature). Pick one approach; do not ship both.

- [ ] **Step 1: Resolve the CodeQL action SHA**

The pinning invariant requires a 40-char SHA. Resolve the commit the `v3` major tag points to:

```bash
gh api repos/github/codeql-action/commits/v3 --jq '.sha'
```

Copy the printed 40-char SHA — you will paste it into both `github/codeql-action/*` lines below (same repo, same SHA). If `gh` is unavailable, open <https://github.com/github/codeql-action/releases>, take the latest v3 release's target commit SHA.

- [ ] **Step 2: Create the workflow**

Create `.github/workflows/codeql.yml`. Replace `RESOLVE_SHA` (both occurrences) with the SHA from Step 1. The `actions/checkout`, `actions/setup-java`, and `actions/cache` SHAs below are copied verbatim from `ci.yml` — keep them identical.

```yaml
name: CodeQL

on:
  pull_request:
    branches:
      - main
  push:
    branches:
      - main
  schedule:
    # Weekly, Tuesday 06:00 UTC — offset from Dependabot (Mon) and the OWASP
    # dependency-audit cross-check (Thu).
    - cron: "0 6 * * 2"

permissions:
  contents: read

env:
  SEGMENT_DOWNLOAD_TIMEOUT_MINS: "5"
  MAVEN_USER_HOME: ${{ github.workspace }}/.cache/maven/user-home

jobs:
  analyze:
    name: Analyze Java
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    permissions:
      contents: read
      security-events: write # upload SARIF to GitHub code scanning
    steps:
      - name: Checkout
        uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7.0.0
        with:
          persist-credentials: false

      - name: Set up Java
        uses: actions/setup-java@1bcf9fb12cf4aa7d266a90ae39939e61372fe520 # v5.4.0
        with:
          distribution: temurin
          java-version: "21"

      - name: Cache Maven artifacts
        uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
        with:
          path: .cache/maven
          key: maven-${{ runner.os }}-${{ hashFiles('**/pom.xml', '.mvn/wrapper/maven-wrapper.properties') }}
          restore-keys: |
            maven-${{ runner.os }}-

      - name: Initialize CodeQL
        uses: github/codeql-action/init@RESOLVE_SHA # v3
        with:
          languages: java-kotlin
          build-mode: manual

      - name: Build for analysis
        run: ./mvnw -B -ntp -DskipTests clean compile

      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@RESOLVE_SHA # v3
        with:
          category: "/language:java-kotlin"
```

- [ ] **Step 3: Verify the workflow is valid and fully SHA-pinned**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/codeql.yml')); print('yaml ok')"
rg -No 'uses:[[:space:]]*[^[:space:]]+@[^[:space:]]+' .github/workflows/codeql.yml | grep -vE '@[0-9a-f]{40}$' && echo 'FLOATING TAG FOUND — fix it' || echo 'all SHA-pinned'
```

Expected: `yaml ok` and `all SHA-pinned`. If `RESOLVE_SHA` is still present, the second check prints `FLOATING TAG FOUND` — go back to Step 1.

- [ ] **Step 4: Record the new control in SECURITY.md**

In `SECURITY.md`, under the `## Supply-Chain Target` section, append one bullet to the existing list (after the `- the publish job verifies archive attestations...` line):

```markdown
- first-party Java is statically analyzed by CodeQL on pull requests, pushes to
  `main`, and weekly; high-severity alerts surface in the repository's code
  scanning view.
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/codeql.yml SECURITY.md
git commit -m "build(security): add CodeQL SAST workflow and document it"
```

- [ ] **Step 6: Confirm enforcement after push (manual, post-merge)**

Static checks cannot prove a workflow runs. After this lands on `main` (or on the first PR), open **GitHub → Actions → CodeQL** and confirm a green run, then **Security → Code scanning** shows the analysis. If the `analyze` step fails with an Advanced-Security error, the repo is private without GHAS — switch to the SpotBugs+FindSecBugs alternative in this task's note.

---

### Task 3: Make release notes informative (F6)

Every release body is the static string `"Dediren agent bundle release."`, even though releases actively ship security-relevant dependency bumps (e.g. Jackson, commons-io CVE fixes). Switch the publish step to GitHub's auto-generated notes so each release lists its merged PRs/commits and a changelog link — giving consumers visible "what changed / what was fixed" evidence.

**Files:**
- Modify: `.github/workflows/release.yml` (the `gh release create` invocation in the `publish` job, ~lines 225–232)

**Interfaces:** none.

- [ ] **Step 1: Change the release-notes flag**

In `.github/workflows/release.yml`, find the final `gh release create` command in the `publish` job. Replace this line:

```yaml
            --notes "Dediren agent bundle release."
```

with:

```yaml
            --generate-notes
```

Leave every other line of the command (the asset list, `--verify-tag`, `--title`) unchanged.

- [ ] **Step 2: Verify the workflow still parses and the change is exact**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml')); print('yaml ok')"
rg -n -- '--generate-notes' .github/workflows/release.yml && echo 'flag present'
rg -n 'Dediren agent bundle release\.' .github/workflows/release.yml && echo 'OLD STRING STILL PRESENT — remove it' || echo 'old notes string removed'
```

Expected: `yaml ok`, `flag present`, and `old notes string removed`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): auto-generate release notes from commits"
```

> Real proof is the next tagged release: its GitHub release page should show an auto-generated changelog instead of the one-line body. No local test exercises `gh release create`.

---

### Task 4: Record deferred branch protection as an explicit accepted risk (F1)

The maintainer is **deliberately deferring** required-review branch protection on `main` to keep solo iteration fast until the project stabilizes. This task converts that silent gap into a documented, revisitable decision — which is the disciplined way to carry an accepted risk — and names the compensating controls and the re-evaluation trigger.

**Files:**
- Modify: `SECURITY.md` (append a new section at end of file)

**Interfaces:** none.

- [ ] **Step 1: Append the accepted-risk section**

Add to the **end** of `SECURITY.md`:

```markdown

## Branch Protection (deferred)

While the project is pre-release and maintained by a single author, the default
branch (`main`) is intentionally left without required-review branch protection
to keep solo iteration fast. Direct pushes to `main` are an accepted risk for
now. Compensating controls run on every push and pull request:

- the blocking Grype/SBOM supply-chain gate (`ci.yml`) fails on High/Critical
  advisories;
- the full test suite and whitespace check run in the same workflow;
- `CODEOWNERS` records review ownership for when protection is enabled.

This decision is revisited when the project starts accepting external
contributions or reaches a stable release. At that point, enable a `main`
ruleset that requires the `test` and `vulnerability-scan` status checks (and,
optionally, `CODEOWNERS` review).
```

- [ ] **Step 2: Verify**

```bash
git diff --check
rg -n 'Branch Protection \(deferred\)' SECURITY.md && echo 'section added'
```

Expected: no whitespace errors; `section added`.

- [ ] **Step 3: Commit**

```bash
git add SECURITY.md
git commit -m "docs(security): record deferred branch protection as accepted risk"
```

---

## Appendix A: Manual GitHub settings (no repo file)

These findings have no committable artifact — they are repository settings. Do them in the GitHub UI (or note them as accepted). Not part of the task commits.

- [ ] **F2 — Enable Dependabot alerts + security updates.** Settings → Code security → **Dependabot alerts: Enable** and **Dependabot security updates: Enable**. (The existing `dependabot.yml` only schedules *version* updates; alerts/security PRs are a separate toggle and are currently **disabled** — confirmed live during the audit.) Free; complements the Grype gate by watching for CVEs disclosed *after* a build.
- [ ] **Confirm secret scanning + push protection.** Settings → Code security → **Secret scanning: Enable** and **Push protection: Enable**. The audit could not verify these (API returned 403) and found no committed secret; push protection is preventive.
- [ ] **F4 — CI log forwarding (accept-risk).** Off-platform SIEM forwarding is not warranted at solo-OSS scale. GitHub's own audit log is the realistic control; no action beyond acknowledging the accepted risk.
- [ ] **F1 — Branch protection (deferred).** Captured as an accepted risk in Task 4. When ready to enable: create a `main` ruleset requiring the `test` and `vulnerability-scan` status checks.

---

## Self-Review

- **Spec coverage:** F1 → Task 4 + Appendix A (deferred, documented). F2 → Appendix A. F3 → Task 2. F4 → Appendix A. F5 → Task 1. F6 → Task 3. All six findings mapped.
- **Placeholder scan:** the only deferred literal is the CodeQL SHA, which Task 2 Step 1 resolves with an exact command before use; Step 3 fails the build if it is left unresolved. No vague "add error handling" steps.
- **Type consistency:** the helper `secureXmiDocumentBuilderFactory()` is referenced identically in Task 1 Steps 1, 3, and 4; signature matches the Interfaces block.
- **No-contract-change guard:** Task 1 changes only parser configuration, not emitted XML — existing golden `.xmi` fixtures are deliberately untouched (Global Constraints).

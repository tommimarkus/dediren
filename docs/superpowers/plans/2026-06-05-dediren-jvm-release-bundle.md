# Dediren JVM Release Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stale native architecture release matrix with one JVM distribution archive because Dediren now ships Java launcher scripts and jars rather than native binaries.

**Architecture:** This plan assumes the top-level Maven reactor layout from `codex/java-structure-cleanup`: `cli/`, `contracts/`, `core/`, `plugins/*`, and `dist-tool/`. Keep the existing bundle schema compatible by retaining `bundle.json.target`, but set it to the logical value `jvm`. Remove native target selection from the Java dist tool, GitHub release workflow, README, and agent guide. Keep release provenance, SBOM, checksums, and smoke coverage for the single archive.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5, AssertJ, GitHub Actions, Bash, `tar`, `jq`, CycloneDX Maven plugin.

---

## File Structure

- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  - Owns distribution archive naming, bundle metadata, build, and smoke behavior.
  - Replace native triples with one logical `jvm` target.
  - Remove host OS and architecture validation because the archive no longer contains native executables.
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  - Adds focused regression tests for JVM archive naming, metadata target choice, and release workflow policy.
- Modify: `.github/workflows/release.yml`
  - Remove the native target matrix and publish exactly one JVM archive.
  - Preserve validation, smoke, archive attestation, SBOM generation, checksum generation, and release upload.
- Modify: `README.md`
  - Replace target-triple build and release examples with the JVM archive surface.
  - Clarify that the bundle requires Java 21 and POSIX shell launchers, with no bundled JRE.
- Modify: `docs/agent-usage.md`
  - Keep bundle-local agent instructions current with the JVM archive name and runtime probe examples.
- Modify: root and module POM files
  - `pom.xml`
  - `archimate/pom.xml`
  - `cli/pom.xml`
  - `contracts/pom.xml`
  - `core/pom.xml`
  - `schema-cache/pom.xml`
  - `uml/pom.xml`
  - `plugins/*/pom.xml`
  - `test-support/pom.xml`
  - `testbeds/plugin-runtime/pom.xml`
  - `dist-tool/pom.xml`
  - Apply a patch version bump for the public artifact naming change.
- Modify: `fixtures/plugins/*.manifest.json`
  - Keep first-party plugin manifest versions synchronized with the product version.
- Modify: `fixtures/source/*.json`
  - Keep `required_plugins[].version` entries synchronized with the product version.
- Modify as identified by stale-version search:
  - `cli/src/test/java/dev/dediren/cli/MainTest.java`
  - `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
  - `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`
  - `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
  - `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

## Design Decisions

- The public bundle schema stays at `dediren-bundle.schema.v1`; `target` remains required and valid because changing the schema shape is unnecessary for this compatibility fix.
- The only supported distribution target value after this change is `jvm`.
- The archive name becomes `dediren-agent-bundle-<version>-jvm.tar.gz`.
- `DEDIREN_DIST_TARGET`, `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`, and `aarch64-apple-darwin` are removed from active release and documentation surfaces.
- The release build runs once on `ubuntu-24.04`. The archive is Java bytecode plus POSIX launch scripts, so building separate native architecture assets no longer adds product value.
- This is a patch release because it removes duplicate release assets while keeping the product commands, schemas, plugin protocol, and archive contents compatible.

---

### Task 1: Add JVM Distribution Regression Tests

**Files:**
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Add imports for filesystem assertions**

Edit `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java` and add these imports below the existing AssertJ import:

```java
import java.nio.file.Files;
import java.nio.file.Path;
```

- [ ] **Step 2: Add failing JVM target tests**

Add these tests after `moduleLoads()`:

```java
    @Test
    void distributionTargetIsJvm() {
        assertThat(DistTool.distributionTarget()).isEqualTo("jvm");
    }

    @Test
    void bundleNameUsesJvmTarget() {
        assertThat(DistTool.bundleName("0.21.1"))
            .isEqualTo("dediren-agent-bundle-0.21.1-jvm");
    }
```

- [ ] **Step 3: Add a failing release workflow policy test**

Add this test and helper methods before the final closing brace of `DistModuleTest`:

```java
    @Test
    void releaseWorkflowPublishesSingleJvmArchive() throws Exception {
        String workflow = Files.readString(workspaceRoot().resolve(".github/workflows/release.yml"));

        assertThat(workflow)
            .contains("runs-on: ubuntu-24.04")
            .contains("path: dist/dediren-agent-bundle-*-jvm.tar.gz")
            .contains("name: dediren-agent-bundle-jvm")
            .contains("bundle=\"dediren-agent-bundle-${VERSION}-jvm\"")
            .contains("archive=\"release-assets/dediren-agent-bundle-${VERSION}-jvm.tar.gz\"")
            .contains(".version == $version and .target == \"jvm\"")
            .doesNotContain("DEDIREN_DIST_TARGET")
            .doesNotContain("x86_64-unknown-linux-gnu")
            .doesNotContain("aarch64-unknown-linux-gnu")
            .doesNotContain("aarch64-apple-darwin");
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".github/workflows/release.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("workspace root not found");
    }
```

- [ ] **Step 4: Run the focused test and confirm RED**

Run:

```bash
./mvnw -pl dist-tool -am test -Dtest=DistModuleTest
```

Expected: the test run fails during compilation with messages equivalent to:

```text
cannot find symbol
  symbol:   method distributionTarget()
cannot find symbol
  symbol:   method bundleName(java.lang.String)
```

- [ ] **Step 5: Commit the failing tests**

```bash
git add dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "test: define jvm-only distribution surface"
```

---

### Task 2: Collapse DistTool To One JVM Target

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Remove native target state**

In `DistTool.java`, remove the `java.util.Locale` import and replace the current target constants:

```java
    private static final String DEFAULT_TARGET = "x86_64-unknown-linux-gnu";
    private static final List<DistTarget> TARGETS = List.of(
        new DistTarget("x86_64-unknown-linux-gnu", "linux", "x86_64"),
        new DistTarget("aarch64-unknown-linux-gnu", "linux", "aarch64"),
        new DistTarget("aarch64-apple-darwin", "macos", "aarch64"));
```

with:

```java
    private static final String DISTRIBUTION_TARGET = "jvm";
```

- [ ] **Step 2: Remove target resolution from command dispatch**

In the `build` case, replace:

```java
                DistTarget target = resolveTarget(options.get("target"));
                Path notices = Path.of(required(options, "notices")).toAbsolutePath().normalize();
                build(root, version, target, notices);
```

with:

```java
                Path notices = Path.of(required(options, "notices")).toAbsolutePath().normalize();
                build(root, version, notices);
```

In the `smoke` case, replace:

```java
                DistTarget target = resolveTarget(options.get("target"));
                Path archive = options.containsKey("archive")
                    ? Path.of(options.get("archive"))
                    : root.resolve("dist").resolve(bundleName(version, target.triple()) + ".tar.gz");
```

with:

```java
                Path archive = options.containsKey("archive")
                    ? Path.of(options.get("archive"))
                    : root.resolve("dist").resolve(bundleName(version) + ".tar.gz");
```

- [ ] **Step 3: Update build and metadata methods**

Replace the `build` signature and first lines:

```java
    private static void build(Path root, String version, DistTarget target, Path notices) throws Exception {
        ensureHostCanBuild(target);
        Path dist = root.resolve("dist");
        Path bundle = dist.resolve(bundleName(version, target.triple()));
        Path archive = dist.resolve(bundle.getFileName() + ".tar.gz");
```

with:

```java
    private static void build(Path root, String version, Path notices) throws Exception {
        Path dist = root.resolve("dist");
        Path bundle = dist.resolve(bundleName(version));
        Path archive = dist.resolve(bundle.getFileName() + ".tar.gz");
```

Replace:

```java
        writeBundleMetadata(bundle, version, target.triple());
```

with:

```java
        writeBundleMetadata(bundle, version);
```

Replace:

```java
    private static void writeBundleMetadata(Path bundle, String version, String target) throws IOException {
```

with:

```java
    private static void writeBundleMetadata(Path bundle, String version) throws IOException {
```

Replace:

```java
        metadata.put("target", target);
```

with:

```java
        metadata.put("target", DISTRIBUTION_TARGET);
```

- [ ] **Step 4: Replace target helper methods**

Delete these methods and record from `DistTool.java`:

```java
    private static DistTarget resolveTarget(String requested) {
        String value = requested;
        if (value == null || value.isBlank()) {
            value = System.getenv("DEDIREN_DIST_TARGET");
        }
        if (value == null || value.isBlank()) {
            DistTarget current = currentHostTarget();
            value = current == null ? DEFAULT_TARGET : current.triple();
        }
        String selected = value;
        return TARGETS.stream()
            .filter(target -> target.triple().equals(selected))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unsupported distribution target: " + selected));
    }

    private static void ensureHostCanBuild(DistTarget target) {
        String os = normalizedOs();
        String arch = normalizedArch();
        if (!target.hostOs().equals(os) || !target.hostArch().equals(arch)) {
            throw new IllegalStateException("distribution target " + target.triple() + " must be built on "
                + target.hostOs() + " " + target.hostArch() + "; current host is " + os + " " + arch);
        }
    }

    private static DistTarget currentHostTarget() {
        String os = normalizedOs();
        String arch = normalizedArch();
        return TARGETS.stream()
            .filter(target -> target.hostOs().equals(os) && target.hostArch().equals(arch))
            .findFirst()
            .orElse(null);
    }

    private static String normalizedOs() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os;
    }

    private static String normalizedArch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private static String bundleName(String version, String target) {
        return "dediren-agent-bundle-" + version + "-" + target;
    }
```

Add these package-private methods in the same location:

```java
    static String distributionTarget() {
        return DISTRIBUTION_TARGET;
    }

    static String bundleName(String version) {
        return "dediren-agent-bundle-" + version + "-" + DISTRIBUTION_TARGET;
    }
```

Delete the record at the bottom of `DistTool.java`:

```java
    private record DistTarget(String triple, String hostOs, String hostArch) {
    }
```

- [ ] **Step 5: Update usage output**

Replace:

```java
        System.err.println("usage: DistTool notices|build|smoke --root PATH [--version VERSION] [--target TRIPLE]");
```

with:

```java
        System.err.println("usage: DistTool notices|build|smoke --root PATH [--version VERSION]");
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./mvnw -pl dist-tool -am test -Dtest=DistModuleTest
```

Expected: the JVM target tests pass, and `releaseWorkflowPublishesSingleJvmArchive` still fails because the workflow has not been updated.

- [ ] **Step 7: Commit the dist tool change**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "fix: collapse distribution target to jvm"
```

---

### Task 3: Publish One JVM Archive In GitHub Actions

**Files:**
- Modify: `.github/workflows/release.yml`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Simplify the build job**

In `.github/workflows/release.yml`, replace the current `build` job runner and matrix block:

```yaml
    runs-on: ${{ matrix.os }}
    timeout-minutes: 60
    permissions:
      contents: read
      id-token: write
      attestations: write
    strategy:
      fail-fast: false
      matrix:
        include:
          - target: x86_64-unknown-linux-gnu
            os: ubuntu-24.04
          - target: aarch64-unknown-linux-gnu
            os: ubuntu-24.04-arm
          - target: aarch64-apple-darwin
            os: macos-15
```

with:

```yaml
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    permissions:
      contents: read
      id-token: write
      attestations: write
```

- [ ] **Step 2: Remove macOS-only setup**

Delete this macOS install step from the `build` job:

```yaml
      - name: Install macOS tools
        if: runner.os == 'macOS'
        run: |
          brew install libxml2 jq
          echo "/opt/homebrew/opt/libxml2/bin" >> "$GITHUB_PATH"
```

Replace the Linux install step:

```yaml
      - name: Install Linux tools
        if: runner.os == 'Linux'
        run: sudo apt-get update && sudo apt-get install -y libxml2-utils jq
```

with:

```yaml
      - name: Install XML tools
        run: sudo apt-get update && sudo apt-get install -y libxml2-utils jq
```

- [ ] **Step 3: Remove target environment from the dist smoke step**

Replace:

```yaml
      - name: Build and smoke distribution
        env:
          DEDIREN_DIST_TARGET: ${{ matrix.target }}
        run: ./mvnw -pl dist-tool -am verify -Pdist-smoke
```

with:

```yaml
      - name: Build and smoke distribution
        run: ./mvnw -pl dist-tool -am verify -Pdist-smoke
```

- [ ] **Step 4: Update attestation and artifact upload paths**

Replace:

```yaml
          subject-path: dist/dediren-agent-bundle-*-${{ matrix.target }}.tar.gz
```

with:

```yaml
          subject-path: dist/dediren-agent-bundle-*-jvm.tar.gz
```

Replace:

```yaml
          name: dediren-agent-bundle-${{ matrix.target }}
          path: dist/dediren-agent-bundle-*-${{ matrix.target }}.tar.gz
```

with:

```yaml
          name: dediren-agent-bundle-jvm
          path: dist/dediren-agent-bundle-*-jvm.tar.gz
```

- [ ] **Step 5: Verify one release archive in the publish job**

In the `Verify release assets` Bash block, replace:

```bash
          expected_targets=(
            x86_64-unknown-linux-gnu
            aarch64-unknown-linux-gnu
            aarch64-apple-darwin
          )

          for target in "${expected_targets[@]}"; do
            bundle="dediren-agent-bundle-${VERSION}-${target}"
            archive="release-assets/dediren-agent-bundle-${VERSION}-${target}.tar.gz"
            if [[ ! -f "$archive" ]]; then
              echo "Missing release archive: $archive" >&2
              exit 1
            fi

            tar -tzf "$archive" "$bundle/LICENSE" >/dev/null
            tar -tzf "$archive" "$bundle/docs/agent-usage.md" >/dev/null
            tar -xOf "$archive" "$bundle/bundle.json" \
              | jq -e --arg version "$VERSION" --arg target "$target" \
                  '.version == $version and .target == $target' >/dev/null
          done
```

with:

```bash
          bundle="dediren-agent-bundle-${VERSION}-jvm"
          archive="release-assets/dediren-agent-bundle-${VERSION}-jvm.tar.gz"
          if [[ ! -f "$archive" ]]; then
            echo "Missing release archive: $archive" >&2
            exit 1
          fi

          tar -tzf "$archive" "$bundle/LICENSE" >/dev/null
          tar -tzf "$archive" "$bundle/docs/agent-usage.md" >/dev/null
          tar -xOf "$archive" "$bundle/bundle.json" \
            | jq -e --arg version "$VERSION" \
                '.version == $version and .target == "jvm"' >/dev/null
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
./mvnw -pl dist-tool -am test -Dtest=DistModuleTest
```

Expected: all `DistModuleTest` tests pass.

- [ ] **Step 7: Commit the release workflow change**

```bash
git add .github/workflows/release.yml dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "ci: publish one jvm distribution archive"
```

---

### Task 4: Bump Patch Version And Synchronize Version Surfaces

**Files:**
- Modify: root and module POM files listed in File Structure.
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `fixtures/source/*.json`
- Modify as stale-version search identifies: Java tests and docs that assert `0.21.0`

- [ ] **Step 1: Bump Maven project version to the next patch**

Run:

```bash
./mvnw build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.minorVersion}.${parsedVersion.nextIncrementalVersion}' -DprocessAllModules=true -DgenerateBackupPoms=false
```

Expected: root and module POM versions change from `0.21.0` to `0.21.1`.

- [ ] **Step 2: Replace plugin manifest versions**

Run this search:

```bash
rg -n '"version": "0\.21\.0"' fixtures/plugins
```

For every match under `fixtures/plugins/*.manifest.json`, replace:

```json
"version": "0.21.0"
```

with:

```json
"version": "0.21.1"
```

- [ ] **Step 3: Replace fixture required plugin versions**

Run this search:

```bash
rg -n '"version": "0\.21\.0"' fixtures/source
```

For every `required_plugins[]` match under `fixtures/source/*.json`, replace:

```json
"version": "0.21.0"
```

with:

```json
"version": "0.21.1"
```

- [ ] **Step 4: Update Java version assertion surfaces**

Run:

```bash
rg -n '0\.21\.0' archimate cli contracts core schema-cache uml plugins test-support testbeds dist-tool
```

Update test expectations that assert the product or plugin version. Known examples include these replacements:

```java
assertThat(output).contains("dediren 0.21.1");
```

```java
assertThat(manifest.version()).isEqualTo("0.21.1");
```

```java
assertThat(DistTool.bundleName("0.21.1"))
    .isEqualTo("dediren-agent-bundle-0.21.1-jvm");
```

- [ ] **Step 5: Run focused version tests**

Run:

```bash
./mvnw -pl contracts,cli,plugins/generic-graph,plugins/archimate-oef-export,dist-tool -am test
```

Expected: version-sensitive contract, CLI, plugin, and dist tests pass.

- [ ] **Step 6: Commit the version synchronization**

```bash
git add pom.xml archimate/pom.xml cli/pom.xml contracts/pom.xml core/pom.xml schema-cache/pom.xml uml/pom.xml plugins test-support/pom.xml testbeds/plugin-runtime/pom.xml dist-tool/pom.xml fixtures/plugins fixtures/source
git commit -m "chore: bump release version to 0.21.1"
```

---

### Task 5: Update User-Facing Bundle Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/agent-usage.md`

- [ ] **Step 1: Update README distribution examples**

In `README.md`, replace the archive example:

```text
dist/dediren-agent-bundle-0.21.0-x86_64-unknown-linux-gnu/
dist/dediren-agent-bundle-0.21.0-x86_64-unknown-linux-gnu.tar.gz
```

with:

```text
dist/dediren-agent-bundle-0.21.1-jvm/
dist/dediren-agent-bundle-0.21.1-jvm.tar.gz
```

Replace the target-specific build section:

````markdown
Set a supported target with `DEDIREN_DIST_TARGET` when needed:

```bash
DEDIREN_DIST_TARGET=x86_64-unknown-linux-gnu ./mvnw -pl dist-tool -am verify -Pdist-build
DEDIREN_DIST_TARGET=x86_64-unknown-linux-gnu ./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Supported targets are:

- `x86_64-unknown-linux-gnu`
- `aarch64-unknown-linux-gnu`
- `aarch64-apple-darwin`

The Java archive contains launch scripts and jars, not a bundled JRE. The host
target must match the build host.
````

with:

```markdown
The Java archive contains POSIX launch scripts and jars, not a bundled JRE or
native executables. One `jvm` archive is published for hosts that provide Java
21 or newer and a POSIX-compatible shell.
```

- [ ] **Step 2: Update README bundle layout and first-run examples**

Replace:

```text
dediren-agent-bundle-0.21.0-x86_64-unknown-linux-gnu/
```

with:

```text
dediren-agent-bundle-0.21.1-jvm/
```

Replace:

```bash
VERSION=0.21.0
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}
```

with:

```bash
VERSION=0.21.1
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-jvm
```

- [ ] **Step 3: Update README release section**

Replace:

````markdown
GitHub Releases publish target-specific archives, `SHA256SUMS`, and CycloneDX
SBOMs. The release workflow generates GitHub artifact attestations for archives
and verifies those attestations before publishing. Verify a downloaded archive
with:

```bash
gh attestation verify dediren-agent-bundle-<version>-<target>.tar.gz \
  --repo tommimarkus/dediren
```
````

with:

````markdown
GitHub Releases publish the JVM archive, `SHA256SUMS`, and CycloneDX SBOMs.
The release workflow generates a GitHub artifact attestation for the archive
and verifies that attestation before publishing. Verify a downloaded archive
with:

```bash
gh attestation verify dediren-agent-bundle-<version>-jvm.tar.gz \
  --repo tommimarkus/dediren
```
````

- [ ] **Step 4: Update agent guide runtime probes**

In `docs/agent-usage.md`, replace:

```bash
VERSION=0.21.0
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}
```

with:

```bash
VERSION=0.21.1
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-jvm
```

- [ ] **Step 5: Run active stale-target documentation search**

Run:

```bash
rg -n 'DEDIREN_DIST_TARGET|x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu|aarch64-apple-darwin|target-specific archives|<target>' README.md docs/agent-usage.md .github/workflows/release.yml dist-tool/src
```

Expected: no matches in active surfaces.

- [ ] **Step 6: Commit the documentation change**

```bash
git add README.md docs/agent-usage.md
git commit -m "docs: document jvm distribution archive"
```

---

### Task 6: Run Distribution Verification And Audits

**Files:**
- Verify only unless a check fails.

- [ ] **Step 1: Run the narrow distribution lane**

Run:

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected:

```text
distribution smoke test passed: .../dist/dediren-agent-bundle-0.21.1-jvm.tar.gz
```

- [ ] **Step 2: Inspect generated bundle metadata**

Run:

```bash
tar -xOf dist/dediren-agent-bundle-0.21.1-jvm.tar.gz dediren-agent-bundle-0.21.1-jvm/bundle.json | jq -e '.version == "0.21.1" and .target == "jvm"'
```

Expected: `jq` exits with status `0` and prints `true`.

- [ ] **Step 3: Run the broader release-equivalent Java test lane**

Run:

```bash
./mvnw test
```

Expected: Maven exits with status `0`.

- [ ] **Step 4: Run whitespace verification**

Run:

```bash
git diff --check
```

Expected: no output and exit status `0`.

- [ ] **Step 5: Run stale native-target and version searches**

Run:

```bash
rg -n 'DEDIREN_DIST_TARGET|x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu|aarch64-apple-darwin|target-specific archives|<target>' README.md docs/agent-usage.md .github/workflows/release.yml dist-tool/src
```

Expected: no matches.

Run:

```bash
rg -n '0\.21\.0' pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source archimate cli contracts core schema-cache uml plugins test-support testbeds dist-tool .github/workflows
```

Expected: no active release-surface matches. Historical plan references may remain outside this search scope.

- [ ] **Step 6: Run the plan-required DevSecOps quick audit**

Use `souroldgeezer-audit:devsecops-audit` in Quick mode for:

```text
.github/workflows/release.yml
dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
README.md
docs/agent-usage.md
```

Expected: no block findings. Fix block findings before continuing. Fix warn/info findings or document accepted residual risk in the handoff.

- [ ] **Step 7: Run the plan-required test-quality quick audit**

Use `souroldgeezer-audit:test-quality-audit` in Quick mode for:

```text
dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
distribution smoke verification
release workflow policy coverage
```

Expected: no block findings. Fix block findings before continuing. Fix warn/info findings or document accepted residual risk in the handoff.

- [ ] **Step 8: Commit verification-driven fixes if any were needed**

If Tasks 6.1 through 6.7 required edits, commit only those intentional edits:

```bash
git add <changed-files>
git commit -m "fix: address jvm release verification findings"
```

If no files changed, do not create an empty commit.

---

### Task 7: Tag And Handoff

**Files:**
- No file edits expected.

- [ ] **Step 1: Review final status**

Run:

```bash
git status --short --branch
```

Expected: clean working tree on the implementation branch.

- [ ] **Step 2: Create the annotated release tag**

Run:

```bash
git tag -a v0.21.1 -m "Release 0.21.1"
```

Expected: tag `v0.21.1` points to the commit containing the version bump and JVM archive release change.

- [ ] **Step 3: Capture final evidence for handoff**

Run:

```bash
git log --oneline --decorate -5
git status --short --branch
```

Expected: the recent log shows the scoped commits from this plan, `v0.21.1` decorates the final release commit, and status is clean.

- [ ] **Step 4: Handoff summary**

Report these items:

```text
Implemented JVM-only release archive.
Archive name: dediren-agent-bundle-0.21.1-jvm.tar.gz
Bundle metadata target: jvm
Release workflow: one build job, one archive artifact, one attestation, one release asset.
Verification run:
- ./mvnw -pl dist-tool -am verify -Pdist-smoke
- ./mvnw test
- git diff --check
- stale native-target search
- stale version search
Audits:
- devsecops-audit Quick: <result>
- test-quality-audit Quick: <result>
Tag: v0.21.1
```

## Self-Review

- Spec coverage: the plan covers root cause removal from `DistTool`, release workflow publication, README, agent guide, synchronized version surfaces, verification, audits, and release tag creation.
- Placeholder scan: no task relies on deferred implementation text; code and command steps include concrete replacements and expected results.
- Type consistency: `DistTool.distributionTarget()` and `DistTool.bundleName(String)` are introduced in Task 2 and used consistently by tests from Task 1.
- Scope check: schema shape is intentionally unchanged; the logical target value changes to `jvm`, which is valid under the existing schema pattern.

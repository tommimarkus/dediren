# Dediren Java Release Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stale Rust-style native architecture release matrix with one platform-neutral Java distribution archive.

**Architecture:** Dediren now ships Java launcher scripts and jars, not native binaries, so the release workflow should look like a normal Maven/Java release job: one Ubuntu build, one smoke-tested archive, one attestation, one release asset. Remove public distribution target selection from `DistTool`, GitHub Actions, README, and the bundle-local agent guide. Keep `bundle.json.target` only as a schema-v1 compatibility field and set it to `java`; do not include a target or runtime suffix in archive names.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5, AssertJ, GitHub Actions, Bash, `tar`, `jq`, CycloneDX Maven plugin.

---

## File Structure

- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  - Owns distribution archive naming, bundle metadata, build, smoke, launcher copying, and stale artifact pruning.
  - Replace native target selection with one Java bundle name: `dediren-agent-bundle-<version>`.
  - Reject the retired `--target` option so stale scripts fail clearly instead of silently building a platform-neutral archive.
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  - Add focused tests for version-only archive naming, compatibility metadata target value, retired target option rejection, and release workflow shape.
- Modify: `.github/workflows/release.yml`
  - Remove the matrix and all native architecture values.
  - Build once on `ubuntu-24.04` with Java 21 and Maven.
  - Discover exactly one generated archive path before attestation and upload.
  - Publish exactly `dediren-agent-bundle-<version>.tar.gz`, CycloneDX SBOMs, and `SHA256SUMS`.
- Modify: `README.md`
  - Replace target-triple examples with Java archive examples.
  - State that the archive contains launch scripts and jars, requires Java 21+, and is not tied to CPU architecture.
- Modify: `docs/agent-usage.md`
  - Keep bundle-local probe examples aligned with the version-only bundle directory.
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
  - Apply a patch version bump because the public release artifact name changes.
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

- Recommended approach: publish `dediren-agent-bundle-<version>.tar.gz` with no target suffix. This matches Java distribution norms and avoids preserving old Rust target language in release assets.
- Rejected approach: keep the native matrix and rename targets. Java bytecode plus POSIX launch scripts do not need separate x86_64, ARM Linux, and macOS ARM assets.
- Rejected approach: publish `dediren-agent-bundle-<version>-jvm.tar.gz`. It removes architecture triples, but still models the bundle as a target-specific asset.
- `schemas/bundle.schema.json` remains unchanged. The required `target` field is retained for `dediren-bundle.schema.v1` compatibility and written as `"java"`.
- `DEDIREN_DIST_TARGET`, `--target TRIPLE`, `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`, and `aarch64-apple-darwin` are removed from active release and documentation surfaces.
- Stale artifact pruning must keep only the exact current bundle directory and exact current archive. Prefix matching is not enough after removing the target suffix because `dediren-agent-bundle-0.22.1-x86_64-unknown-linux-gnu.tar.gz` would otherwise look like a current artifact.
- The release workflow keeps existing supply-chain posture: pinned actions, least-privilege permissions, Maven cache, Dependency-Check, CycloneDX SBOMs, build provenance attestation, checksum generation, and tag-to-POM version validation.
- This is a patch release under the current pre-1.0 SemVer rules because commands, schemas, plugin protocol, and bundle contents remain compatible while duplicate native release assets are removed.
- Do not create intermediate commits while executing Tasks 1-6. The repository requires version bumps to live in the same commit as the release-surface change that requires them, so Task 7 creates one scoped implementation commit before tagging.

---

### Task 1: Add Java Archive Regression Tests

**Files:**
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Add imports for filesystem and exception assertions**

Edit `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java` and replace the current static import block:

```java
import static org.assertj.core.api.Assertions.assertThat;
```

with:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
```

- [ ] **Step 2: Add failing Java archive naming and metadata tests**

Add these tests after `moduleLoads()`:

```java
    @Test
    void bundleNameUsesVersionOnlyForJavaArchive() {
        assertThat(DistTool.bundleName("0.22.1"))
            .isEqualTo("dediren-agent-bundle-0.22.1");
    }

    @Test
    void bundleMetadataTargetIsJavaForSchemaCompatibility() {
        assertThat(DistTool.bundleMetadataTarget()).isEqualTo("java");
    }
```

- [ ] **Step 3: Add a failing retired target option test**

Add this test after `bundleMetadataTargetIsJavaForSchemaCompatibility()`:

```java
    @Test
    void retiredTargetOptionFailsClearly() {
        assertThatThrownBy(() -> DistTool.run(new String[] {
            "smoke",
            "--root", ".",
            "--version", "0.22.1",
            "--target", "x86_64-unknown-linux-gnu"
        }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--target is no longer supported");
    }
```

- [ ] **Step 4: Add a failing Java-shaped release workflow policy test**

Add this test and helper before the final closing brace of `DistModuleTest`:

```java
    @Test
    void releaseWorkflowPublishesSingleJavaArchive() throws Exception {
        String workflow = Files.readString(workspaceRoot().resolve(".github/workflows/release.yml"));

        assertThat(workflow)
            .contains("runs-on: ubuntu-24.04")
            .contains("name: Capture archive path")
            .contains("name: dediren-agent-bundle")
            .contains("path: ${{ steps.archive.outputs.path }}")
            .contains("path: release-artifacts")
            .contains("bundle=\"dediren-agent-bundle-${VERSION}\"")
            .contains("archive=\"release-assets/dediren-agent-bundle-${VERSION}.tar.gz\"")
            .contains(".version == $version and .target == \"java\"")
            .doesNotContain("matrix.target")
            .doesNotContain("DEDIREN_DIST_TARGET")
            .doesNotContain("x86_64-unknown-linux-gnu")
            .doesNotContain("aarch64-unknown-linux-gnu")
            .doesNotContain("aarch64-apple-darwin")
            .doesNotContain("dediren-agent-bundle-${VERSION}-")
            .doesNotContain("dediren-agent-bundle-*-jvm")
            .doesNotContain("pattern: dediren-agent-bundle-*")
            .doesNotContain("expected_targets");
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

- [ ] **Step 5: Run the focused test and confirm RED**

Run:

```bash
./mvnw -pl dist-tool -am test -Dtest=DistModuleTest
```

Expected: the test run fails during compilation with messages equivalent to:

```text
cannot find symbol
  symbol:   method bundleName(java.lang.String)
cannot find symbol
  symbol:   method bundleMetadataTarget()
```

- [ ] **Step 6: Check the test diff and defer committing**

```bash
git diff -- dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
```

Expected: only the intentional failing regression tests are present. Do not commit yet.

---

### Task 2: Collapse DistTool To A Platform-Neutral Java Archive

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Remove native target constants**

In `DistTool.java`, remove this import:

```java
import java.util.Locale;
```

Replace the current target constants:

```java
    private static final String DEFAULT_TARGET = "x86_64-unknown-linux-gnu";
    private static final List<DistTarget> TARGETS = List.of(
        new DistTarget("x86_64-unknown-linux-gnu", "linux", "x86_64"),
        new DistTarget("aarch64-unknown-linux-gnu", "linux", "aarch64"),
        new DistTarget("aarch64-apple-darwin", "macos", "aarch64"));
```

with:

```java
    private static final String BUNDLE_METADATA_TARGET = "java";
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
                rejectRetiredTargetOption(options);
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
                rejectRetiredTargetOption(options);
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
        metadata.put("target", BUNDLE_METADATA_TARGET);
```

- [ ] **Step 4: Replace target helper methods**

Delete these methods from `DistTool.java`:

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

Add these methods in the same location:

```java
    static String bundleName(String version) {
        return "dediren-agent-bundle-" + version;
    }

    static String bundleMetadataTarget() {
        return BUNDLE_METADATA_TARGET;
    }

    private static void rejectRetiredTargetOption(Map<String, String> options) {
        if (options.containsKey("target")) {
            throw new IllegalArgumentException(
                "--target is no longer supported; Java distribution archives are platform-neutral");
        }
    }
```

Delete the record at the bottom of `DistTool.java`:

```java
    private record DistTarget(String triple, String hostOs, String hostArch) {
    }
```

- [ ] **Step 5: Tighten stale artifact pruning**

Replace this block in `pruneStaleArtifacts`:

```java
                if (!name.startsWith("dediren-agent-bundle-") || name.startsWith(currentBundle)) {
                    continue;
                }
```

with:

```java
                boolean isCurrent = name.equals(currentBundle) || name.equals(currentBundle + ".tar.gz");
                if (!name.startsWith("dediren-agent-bundle-") || isCurrent) {
                    continue;
                }
```

- [ ] **Step 6: Update usage output**

Replace:

```java
        System.err.println("usage: DistTool notices|build|smoke --root PATH [--version VERSION] [--target TRIPLE]");
```

with:

```java
        System.err.println("usage: DistTool notices|build|smoke --root PATH [--version VERSION]");
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./mvnw -pl dist-tool -am test -Dtest=DistModuleTest
```

Expected: the Java archive naming, metadata target, and retired target option tests pass; `releaseWorkflowPublishesSingleJavaArchive` still fails because the workflow has not been updated.

- [ ] **Step 8: Check the dist tool diff and defer committing**

```bash
git diff -- dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
```

Expected: only the intentional Java archive implementation and tests are present. Do not commit yet.

---

### Task 3: Publish One Java Archive In GitHub Actions

**Files:**
- Modify: `.github/workflows/release.yml`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Simplify the build job runner**

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

- [ ] **Step 4: Capture the single archive path before attestation**

Add this step after `Build and smoke distribution`:

```yaml
      - name: Capture archive path
        id: archive
        shell: bash
        run: |
          set -euo pipefail
          mapfile -t archives < <(find dist -maxdepth 1 -type f -name 'dediren-agent-bundle-*.tar.gz' | sort)
          if [[ "${#archives[@]}" -ne 1 ]]; then
            echo "Expected exactly one Java archive, found ${#archives[@]}" >&2
            find dist -maxdepth 1 -type f -name 'dediren-agent-bundle-*.tar.gz' -print >&2
            exit 1
          fi
          echo "path=${archives[0]}" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 5: Update attestation and artifact upload**

Replace:

```yaml
          subject-path: dist/dediren-agent-bundle-*-${{ matrix.target }}.tar.gz
```

with:

```yaml
          subject-path: ${{ steps.archive.outputs.path }}
```

Replace:

```yaml
          name: dediren-agent-bundle-${{ matrix.target }}
          path: dist/dediren-agent-bundle-*-${{ matrix.target }}.tar.gz
```

with:

```yaml
          name: dediren-agent-bundle
          path: ${{ steps.archive.outputs.path }}
```

- [ ] **Step 6: Download the single archive artifact in the publish job**

In the `Download artifacts` step, replace:

```yaml
        with:
          path: release-artifacts
          pattern: dediren-agent-bundle-*
          merge-multiple: true
```

with:

```yaml
        with:
          name: dediren-agent-bundle
          path: release-artifacts
```

- [ ] **Step 7: Verify exactly one release archive in the publish job**

In the `Verify release assets` Bash block, replace the current `expected_targets` array and loop:

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
          tar_count=$(find release-artifacts -maxdepth 1 -type f -name '*.tar.gz' | wc -l)
          if [[ "$tar_count" -ne 1 ]]; then
            echo "Expected exactly one release archive, found $tar_count" >&2
            find release-artifacts -maxdepth 1 -type f -print >&2
            exit 1
          fi

          bundle="dediren-agent-bundle-${VERSION}"
          archive="release-assets/dediren-agent-bundle-${VERSION}.tar.gz"
          if [[ ! -f "$archive" ]]; then
            echo "Missing release archive: $archive" >&2
            exit 1
          fi

          tar -tzf "$archive" "$bundle/LICENSE" >/dev/null
          tar -tzf "$archive" "$bundle/docs/agent-usage.md" >/dev/null
          tar -xOf "$archive" "$bundle/bundle.json" \
            | jq -e --arg version "$VERSION" \
                '.version == $version and .target == "java"' >/dev/null
```

- [ ] **Step 8: Run focused tests**

Run:

```bash
./mvnw -pl dist-tool -am test -Dtest=DistModuleTest
```

Expected: all `DistModuleTest` tests pass.

- [ ] **Step 9: Check the release workflow diff and defer committing**

```bash
git diff -- .github/workflows/release.yml dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
```

Expected: only the intentional single-archive Java workflow and policy test changes are present. Do not commit yet.

---

### Task 4: Bump Patch Version And Synchronize Version Surfaces

**Files:**
- Modify: root and module POM files listed in File Structure.
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `fixtures/source/*.json`
- Modify as stale-version search identifies: Java tests that assert `0.22.0`

- [ ] **Step 1: Bump Maven project version to the next patch**

Run:

```bash
./mvnw build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.minorVersion}.${parsedVersion.nextIncrementalVersion}' -DprocessAllModules=true -DgenerateBackupPoms=false
```

Expected: root and module POM versions change from `0.22.0` to `0.22.1`.

- [ ] **Step 2: Replace plugin manifest versions**

Run:

```bash
rg -n '"version": "0\.22\.0"' fixtures/plugins
```

For every match under `fixtures/plugins/*.manifest.json`, replace:

```json
"version": "0.22.0"
```

with:

```json
"version": "0.22.1"
```

- [ ] **Step 3: Replace fixture required plugin versions**

Run:

```bash
rg -n '"version": "0\.22\.0"' fixtures/source
```

For every `required_plugins[]` match under `fixtures/source/*.json`, replace:

```json
"version": "0.22.0"
```

with:

```json
"version": "0.22.1"
```

- [ ] **Step 4: Update Java version assertion surfaces**

Run:

```bash
rg -n '0\.22\.0' archimate cli contracts core schema-cache uml plugins test-support testbeds dist-tool
```

Update test expectations that assert the product or plugin version. Known examples include these replacements:

```java
assertThat(output).contains("dediren 0.22.1");
```

```java
assertThat(manifest.version()).isEqualTo("0.22.1");
```

```java
DEFAULT_JVM_OPTS='"-Ddediren.version=0.22.1"'
```

- [ ] **Step 5: Run focused version tests**

Run:

```bash
./mvnw -pl contracts,cli,plugins/generic-graph,plugins/archimate-oef-export,dist-tool -am test
```

Expected: version-sensitive contract, CLI, plugin, and dist tests pass.

- [ ] **Step 6: Check the version synchronization diff and defer committing**

```bash
git diff -- pom.xml archimate/pom.xml cli/pom.xml contracts/pom.xml core/pom.xml schema-cache/pom.xml uml/pom.xml plugins test-support/pom.xml testbeds/plugin-runtime/pom.xml dist-tool/pom.xml fixtures/plugins fixtures/source cli/src/test/java/dev/dediren/cli/MainTest.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
```

Expected: product/plugin versions are synchronized to `0.22.1`. Do not commit yet; docs still need the Java archive rewrite in Task 5.

---

### Task 5: Update User-Facing Bundle Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/agent-usage.md`

- [ ] **Step 1: Update README distribution examples**

In `README.md`, replace the archive example:

```text
dist/dediren-agent-bundle-0.22.0-x86_64-unknown-linux-gnu/
dist/dediren-agent-bundle-0.22.0-x86_64-unknown-linux-gnu.tar.gz
```

with:

```text
dist/dediren-agent-bundle-0.22.1/
dist/dediren-agent-bundle-0.22.1.tar.gz
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
native executables. One platform-neutral archive is published for hosts that
provide Java 21 or newer and a POSIX-compatible shell.
```

- [ ] **Step 2: Update README bundle layout and first-run examples**

Replace:

```text
dediren-agent-bundle-0.22.0-x86_64-unknown-linux-gnu/
```

with:

```text
dediren-agent-bundle-0.22.1/
```

Replace:

```bash
VERSION=0.22.0
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}
```

with:

```bash
VERSION=0.22.1
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}
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
GitHub Releases publish one Java archive, `SHA256SUMS`, and CycloneDX SBOMs.
The release workflow generates a GitHub artifact attestation for the archive
and verifies that attestation before publishing. Verify a downloaded archive
with:

```bash
gh attestation verify dediren-agent-bundle-<version>.tar.gz \
  --repo tommimarkus/dediren
```
````

- [ ] **Step 4: Update agent guide runtime probes**

In `docs/agent-usage.md`, replace:

```bash
VERSION=0.22.0
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}
```

with:

```bash
VERSION=0.22.1
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}
```

- [ ] **Step 5: Run active stale-target documentation search**

Run:

```bash
rg -n 'DEDIREN_DIST_TARGET|x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu|aarch64-apple-darwin|target-specific archives|<target>|dediren-agent-bundle-[^[:space:]]+-jvm|dediren-agent-bundle-\$\{VERSION\}-' README.md docs/agent-usage.md .github/workflows/release.yml dist-tool/src
```

Expected: no matches in active release, dist tool, or documentation surfaces. Matches for CLI `project --target` are outside this release-target scope and should not be changed.

- [ ] **Step 6: Check the documentation diff and defer committing**

```bash
git diff -- README.md docs/agent-usage.md
```

Expected: documentation uses version `0.22.1`, version-only archive names, and no release target triples. Do not commit yet.

---

### Task 6: Run Distribution Verification And Audits

**Files:**
- Verify only unless a check fails.

- [ ] **Step 1: Run the narrow distribution lane**

Run:

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected output includes one archive path equivalent to:

```text
dist/dediren-agent-bundle-0.22.1.tar.gz
```

- [ ] **Step 2: Inspect generated bundle metadata**

Run:

```bash
tar -xOf dist/dediren-agent-bundle-0.22.1.tar.gz dediren-agent-bundle-0.22.1/bundle.json | jq -e '.version == "0.22.1" and .target == "java"'
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
rg -n 'DEDIREN_DIST_TARGET|x86_64-unknown-linux-gnu|aarch64-unknown-linux-gnu|aarch64-apple-darwin|target-specific archives|<target>|dediren-agent-bundle-[^[:space:]]+-jvm|dediren-agent-bundle-\$\{VERSION\}-' README.md docs/agent-usage.md .github/workflows/release.yml dist-tool/src
```

Expected: no active release-target matches. Do not treat CLI command examples such as `project --target layout-request` as release-target matches.

Run:

```bash
rg -n '0\.22\.0' pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source archimate cli contracts core schema-cache uml plugins test-support testbeds dist-tool .github/workflows
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

Expected: no block findings. Pay particular attention to these GitHub Actions controls from the loaded GitHub Actions extension:

```text
gha.HC-1: workflow and write jobs keep explicit minimum permissions.
gha.HC-2: third-party actions remain commit-SHA pinned.
gha.HC-5: Dependency-Check remains a failing release gate.
gha.HC-6: run blocks do not interpolate untrusted event or input expressions.
gha.HC-13: all release jobs keep timeout-minutes.
```

Fix block findings before continuing. Fix warn/info findings or document accepted residual risk in the handoff.

- [ ] **Step 7: Run the plan-required test-quality quick audit**

Use `souroldgeezer-audit:test-quality-audit` in Quick mode for:

```text
dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
distribution smoke verification
release workflow policy coverage
```

Expected: no block findings. Fix block findings before continuing. Fix warn/info findings or document accepted residual risk in the handoff.

- [ ] **Step 8: Check verification-driven fixes if any were needed**

If Tasks 6.1 through 6.7 required edits, inspect only those intentional edits:

```bash
git diff -- <changed-files>
```

Do not commit yet.

---

### Task 7: Commit, Tag, And Handoff

**Files:**
- Stage and commit only intentional files changed by Tasks 1-6.

- [ ] **Step 1: Review final status**

Run:

```bash
git status --short --branch
```

Expected: modified tracked files are limited to the release workflow, dist tool, tests, version surfaces, README, and agent guide. Pre-existing unrelated files, such as `docs/superpowers/plans/2026-06-05-dediren-uml-state-machine.md`, remain unstaged.

- [ ] **Step 2: Review the full intentional diff**

Run:

```bash
git diff -- .github/workflows/release.yml dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java README.md docs/agent-usage.md pom.xml archimate/pom.xml cli/pom.xml contracts/pom.xml core/pom.xml schema-cache/pom.xml uml/pom.xml plugins test-support/pom.xml testbeds/plugin-runtime/pom.xml dist-tool/pom.xml fixtures/plugins fixtures/source cli/src/test/java/dev/dediren/cli/MainTest.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java
```

Expected: only intentional Java release archive, documentation, and version synchronization changes are present.

- [ ] **Step 3: Stage only intentional release files**

Run:

```bash
git add .github/workflows/release.yml \
  dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java \
  dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java \
  README.md \
  docs/agent-usage.md \
  pom.xml \
  archimate/pom.xml \
  cli/pom.xml \
  contracts/pom.xml \
  core/pom.xml \
  schema-cache/pom.xml \
  uml/pom.xml \
  plugins \
  test-support/pom.xml \
  testbeds/plugin-runtime/pom.xml \
  dist-tool/pom.xml \
  fixtures/plugins \
  fixtures/source \
  cli/src/test/java/dev/dediren/cli/MainTest.java \
  contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java \
  plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java \
  plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java
```

Expected: generated outputs under `dist/`, `target/`, and `.cache/` remain unstaged.

- [ ] **Step 4: Commit the release change**

Run:

```bash
git commit -m "fix: publish java release archive"
```

Expected: one scoped commit contains the dist tool change, workflow change, documentation update, version bump, and synchronized version surfaces.

- [ ] **Step 5: Create the annotated release tag**

Run:

```bash
git tag -a v0.22.1 -m "Release 0.22.1"
```

Expected: tag `v0.22.1` points to the commit containing the version bump and Java archive release change.

- [ ] **Step 6: Capture final evidence for handoff**

Run:

```bash
git log --oneline --decorate -5
git status --short --branch
```

Expected: the recent log shows the scoped commit from this plan, `v0.22.1` decorates that release commit, and status is clean except ignored generated outputs and unrelated pre-existing user work.

- [ ] **Step 7: Handoff summary**

Report these items:

```text
Implemented Java-style release archive.
Archive name: dediren-agent-bundle-0.22.1.tar.gz
Bundle directory: dediren-agent-bundle-0.22.1
Bundle metadata target: java
Release workflow: one Ubuntu build job, one archive artifact, one attestation, one release asset.
Verification run:
- ./mvnw -pl dist-tool -am verify -Pdist-smoke
- tar -xOf dist/dediren-agent-bundle-0.22.1.tar.gz dediren-agent-bundle-0.22.1/bundle.json | jq -e '.version == "0.22.1" and .target == "java"'
- ./mvnw test
- git diff --check
- stale native-target search
- stale version search
Audits:
- devsecops-audit Quick: <result>
- test-quality-audit Quick: <result>
Tag: v0.22.1
```

## Self-Review

- Spec coverage: the plan removes native release-target behavior from `DistTool`, release workflow publication, README, and the agent guide while preserving bundle schema compatibility.
- Placeholder scan: no task relies on placeholder text, deferred implementation, or unspecified tests.
- Type consistency: `DistTool.bundleName(String)` and `DistTool.bundleMetadataTarget()` are introduced in Task 2 and used by tests from Task 1.
- Scope check: schema shape is intentionally unchanged; only compatibility metadata value changes from native triple to `java`.
- DevSecOps check: the workflow keeps explicit permissions, pinned third-party actions, job timeouts, failing SCA, SBOM, attestation, and checksum controls.

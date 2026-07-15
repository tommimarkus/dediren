# Bundle Shrink Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the release bundle as one ProGuard-shrunk merged jar with STORED entries (dead resources stripped, YAML stack dropped), cutting `dediren-agent-bundle-<v>.tar.gz` from 15.15 MB to ~5.4 MB with no consumer-facing format change.

**Architecture:** DistTool's `build` gains a shrink stage between appassembler staging and archiving: the staged launcher classpath jars are merged and reachability-shrunk (shrink-only ProGuard — no optimization, no obfuscation) into `lib/dediren-bundle-<version>.jar`, a post-processor unions `META-INF/services` files, relocates embedded licence texts, and repacks every entry STORED (uncompressed) so the archive's outer gzip compresses raw class bytes instead of already-deflated ones, and the launcher's CLASSPATH line is rewritten to the single jar. The shrinker is injected behind a `LibShrinker` seam so the existing hermeticity tests (which fabricate text-file jars) keep working with a fake.

**Tech Stack:** Java 21, Maven (reactor build, enforcer `dependencyConvergence`), ProGuard 7.7.0 (`com.guardsquare:proguard-base`, build-time only), `java.util.zip` for post-processing, existing DistTool smoke/bench harness.

**Origin:** PoC-validated by `docs/superpowers/plans/2026-07-15-bundle-size-reduction-research.md`. The keep rules, resource filter, and every failure mode cited below (ELK ServiceLoader loss, MCP ServiceLoader loss, picocli CDS stderr noise) were observed and fixed in that PoC; the final configuration passed the full `DistTool smoke` (layout, render, OEF+XMI export, MCP stdio, CDS, quiet-stderr gates) and ran ~30% faster than baseline in `DistTool bench`.

## Global Constraints

- Branch: `worktree-bundle-size-research` (this worktree). No version bump — `release-policy` bumps at release time in its own commit, not here.
- Java 21+; dist build additionally needs a JDK with `jmods/` (ProGuard `-libraryjars`); CI's temurin JDK has it.
- Format all Java with `./mvnw -Pquality spotless:apply` before each commit; the quality gate also runs SpotBugs (Max effort, correctness).
- No `Logger.info/warn/error` in first-party code; no new SLF4J bindings anywhere (LoggingProviderLocalityTest).
- Archive format stays `.tar.gz`; the merged jar's entries are STORED (uncompressed) so the outer gzip does the compressing — the PoC measured 5.43 MB vs 7.18 MB deflated, with the STORED variant also the faster one in bench. Only the tar.xz/zstd format switch remains out of scope (recorded follow-up in the research doc).
- Sandbox: Maven runs sandboxed (`sandbox-tmpdir` profile). The two `*FuzzTest` classes fail only under the sandbox — exclude with `-Dtest='!*FuzzTest' -Dsurefire.failIfNoSpecifiedTests=false` or run that lane sandbox-disabled. Cold-cache artifact fetches need sandbox disabled once (`proguard-base` 7.7.0 and transitives are already in `~/.m2` from the PoC).
- Stage with explicit paths only; never `git add -A`. Protected surfaces (`mvnw`, wrapper properties, `LICENSE`, generated notices under `target/`) untouched.
- Audit gates for this plan (run at Task 8, fix blocks): `souroldgeezer-audit:test-quality-audit` quick pass over the changed dist-tool tests; `souroldgeezer-audit:devsecops-audit` quick pass over the dependency/toolchain/artifact changes; `souroldgeezer-audit:ip-hygiene` over the notices/licence handling.

---

### Task 1: Drop the YAML stack

**Files:**
- Modify: `core/pom.xml` (json-schema-validator dependency, ~line 36)
- Modify: `pom.xml` (root: remove `jackson-dataformat-yaml` dependencyManagement entry ~line 163 and trim its mention from the Jackson pin comment ~line 137)
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (THIRD_PARTY_ATTRIBUTIONS ~lines 102, 137)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: a runtime classpath with no `jackson-dataformat-yaml-*.jar` / `snakeyaml-engine-*.jar`; later tasks assume the staged lib no longer contains them.

- [ ] **Step 1: Exclude the YAML transitives in core**

In `core/pom.xml`, replace the bare json-schema-validator dependency:

```xml
    <dependency>
      <groupId>com.networknt</groupId>
      <artifactId>json-schema-validator</artifactId>
      <!-- YAML input support is unused: no dediren code path feeds YAML to the validator or
           to Jackson (verified 2026-07: zero production/test references). Excluding the YAML
           stack keeps ~0.36 MB of deflated classes off every runtime classpath and out of the
           bundle. Restoring YAML support must also restore the DistTool
           THIRD_PARTY_ATTRIBUTIONS entries removed with this exclusion. -->
      <exclusions>
        <exclusion>
          <groupId>tools.jackson.dataformat</groupId>
          <artifactId>jackson-dataformat-yaml</artifactId>
        </exclusion>
        <exclusion>
          <groupId>org.snakeyaml</groupId>
          <artifactId>snakeyaml-engine</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
```

- [ ] **Step 2: Remove the dead root-pom pin**

In root `pom.xml`, delete the `tools.jackson.dataformat:jackson-dataformat-yaml` dependencyManagement entry, and in the Jackson pin comment above the databind entry change "Pin databind, core, and the yaml dataformat to one patched line." to "Pin databind and core to one patched line."

- [ ] **Step 3: Remove the now-unshipped attributions**

In `DistTool.java` THIRD_PARTY_ATTRIBUTIONS, delete the two entries:

```java
          Map.entry("jackson-dataformat-yaml", attribution("FasterXML Jackson", "Apache-2.0")),
```
and
```java
          Map.entry("snakeyaml-engine", attribution("SnakeYAML Engine", "Apache-2.0")),
```

(Leaving them would hide a silent YAML-stack return: with them gone, a reappearing yaml jar fails `notices` loudly as unattributed.)

- [ ] **Step 4: Verify the jars are gone from the launcher classpath**

Run: `./mvnw -B -ntp -pl cli -am -Dmaven.test.skip=true package && grep -c 'yaml' cli/target/appassembler/bin/cli`
Expected: `grep` exits 1 with count `0` (no yaml jar on the CLASSPATH line). If a yaml jar is still present, a second dependency path pulls it (candidate: the MCP SDK's own json-schema-validator reference) — find it with `./mvnw -pl cli -am dependency:tree -Dincludes=tools.jackson.dataformat,org.snakeyaml` and add the same exclusion at that declaration.

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw -B -ntp test -Dtest='!*FuzzTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS (proves nothing needs the YAML stack).

- [ ] **Step 6: Commit**

```bash
git add core/pom.xml pom.xml dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "build(core,dist): drop the unused YAML stack from runtime classpaths"
```

---

### Task 2: MergedJarPostProcessor (service-file union + licence relocation)

**Files:**
- Create: `dist-tool/src/main/java/dev/dediren/tools/dist/MergedJarPostProcessor.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/MergedJarPostProcessorTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `static void MergedJarPostProcessor.apply(Path mergedJar, List<Path> originalJars) throws IOException` — Task 3's shrinker calls it after ProGuard.

- [ ] **Step 1: Write the failing tests**

```java
package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergedJarPostProcessorTest {

  @Test
  void unionsServiceFilesAcrossOriginalJarsFilteredToSurvivingClasses(@TempDir Path dir)
      throws Exception {
    Path a =
        jar(
            dir.resolve("a.jar"),
            Map.of(
                "META-INF/services/com.example.Spi", "com.example.AlphaImpl\n",
                "com/example/AlphaImpl.class", "x"));
    Path b =
        jar(
            dir.resolve("b.jar"),
            Map.of(
                "META-INF/services/com.example.Spi",
                "com.example.BetaImpl\n# comment\ncom.example.GoneImpl\n",
                "com/example/BetaImpl.class",
                "x"));
    // The merged jar as a first-wins merge leaves it: only a.jar's service file survived,
    // and GoneImpl's class was shrunk away.
    Path merged =
        jar(
            dir.resolve("merged.jar"),
            Map.of(
                "META-INF/services/com.example.Spi", "com.example.AlphaImpl\n",
                "com/example/AlphaImpl.class", "x",
                "com/example/BetaImpl.class", "x"));

    MergedJarPostProcessor.apply(merged, List.of(a, b));

    assertThat(entryText(merged, "META-INF/services/com.example.Spi"))
        .isEqualTo("com.example.AlphaImpl\ncom.example.BetaImpl\n");
  }

  @Test
  void relocatesEmbeddedLicenceFilesUnderThirdPartyNamespace(@TempDir Path dir) throws Exception {
    Path guava =
        jar(
            dir.resolve("guava-33.6.0-jre.jar"),
            Map.of("META-INF/LICENSE", "apache text", "com/G.class", "x"));
    Path elk =
        jar(dir.resolve("elk-core-0.11.0.jar"), Map.of("about.html", "epl text", "org/E.class", "x"));
    Path merged = jar(dir.resolve("merged.jar"), Map.of("com/G.class", "x", "org/E.class", "x"));

    MergedJarPostProcessor.apply(merged, List.of(guava, elk));

    assertThat(entryText(merged, "META-INF/third-party/guava-33.6.0-jre/LICENSE"))
        .isEqualTo("apache text");
    assertThat(entryText(merged, "META-INF/third-party/elk-core-0.11.0/about.html"))
        .isEqualTo("epl text");
    // Non-licence resources are not relocated, and existing class entries survive untouched.
    assertThat(entryText(merged, "com/G.class")).isEqualTo("x");
  }

  @Test
  void repacksEveryEntryStoredSoTheArchiveGzipCompressesRawBytes(@TempDir Path dir)
      throws Exception {
    Path merged =
        jar(
            dir.resolve("merged.jar"),
            Map.of("com/example/AlphaImpl.class", "x".repeat(4096), "resource.txt", "data"));

    MergedJarPostProcessor.apply(merged, List.of());

    try (ZipFile zip = new ZipFile(merged.toFile())) {
      var names = zip.entries();
      while (names.hasMoreElements()) {
        ZipEntry entry = names.nextElement();
        assertThat(entry.getMethod()).as(entry.getName()).isEqualTo(ZipEntry.STORED);
      }
    }
  }

  private static Path jar(Path path, Map<String, String> entries) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (Map.Entry<String, String> entry : new java.util.TreeMap<>(entries).entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return path;
  }

  private static String entryText(Path jar, String name) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(name);
      assertThat(entry).as("entry %s in %s", name, jar).isNotNull();
      try (var in = zip.getInputStream(entry)) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='MergedJarPostProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: COMPILATION ERROR (`MergedJarPostProcessor` does not exist).

- [ ] **Step 3: Implement MergedJarPostProcessor**

```java
package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Repairs what a first-wins jar merge cannot preserve. ServiceLoader registrations are unioned
 * across the original jars (filtered to classes that survived shrinking): ELK's layered algorithm
 * and the MCP SDK's JSON mapper/schema validator all register through {@code META-INF/services},
 * and losing a single line fails them at runtime, not at build time. Embedded licence texts are
 * copied from every original jar to {@code META-INF/third-party/<jar-name>/} so no jar's licence
 * file collides with another's (the injar filter keeps the originals out of the ProGuard output).
 * Every entry is rewritten STORED (uncompressed): the bundle archive's outer gzip then compresses
 * raw class bytes instead of already-deflated ones, which is worth ~1.7 MB on the shipped tarball
 * and measurably speeds classloading.
 */
final class MergedJarPostProcessor {
  private MergedJarPostProcessor() {}

  static void apply(Path mergedJar, List<Path> originalJars) throws IOException {
    Set<String> keptClasses = new HashSet<>();
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(mergedJar.toFile())) {
      var names = zip.entries();
      while (names.hasMoreElements()) {
        ZipEntry entry = names.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        byte[] data;
        try (var in = zip.getInputStream(entry)) {
          data = in.readAllBytes();
        }
        entries.put(entry.getName(), data);
        if (entry.getName().endsWith(".class")) {
          String className =
              entry
                  .getName()
                  .substring(0, entry.getName().length() - ".class".length())
                  .replace('/', '.');
          keptClasses.add(className);
        }
      }
    }

    Map<String, LinkedHashSet<String>> services = new TreeMap<>();
    Map<String, byte[]> licences = new TreeMap<>();
    for (Path original : originalJars) {
      String jarBase = original.getFileName().toString().replaceFirst("\\.jar$", "");
      try (ZipFile zip = new ZipFile(original.toFile())) {
        var names = zip.entries();
        while (names.hasMoreElements()) {
          ZipEntry entry = names.nextElement();
          if (entry.isDirectory()) {
            continue;
          }
          String name = entry.getName();
          if (name.startsWith("META-INF/services/")) {
            String content;
            try (var in = zip.getInputStream(entry)) {
              content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String line : content.split("\\R")) {
              String impl = line.split("#", 2)[0].strip();
              if (!impl.isEmpty() && keptClasses.contains(impl)) {
                services.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(impl);
              }
            }
          } else if (isLicenceFile(name)) {
            byte[] data;
            try (var in = zip.getInputStream(entry)) {
              data = in.readAllBytes();
            }
            String file = name.substring(name.lastIndexOf('/') + 1);
            licences.put("META-INF/third-party/" + jarBase + "/" + file, data);
          }
        }
      }
    }

    Path repacked = Files.createTempFile(mergedJar.getParent(), "merged-post", ".jar");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(repacked))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        if (entry.getKey().startsWith("META-INF/services/")) {
          continue; // replaced by the union below
        }
        writeEntry(out, entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, LinkedHashSet<String>> service : services.entrySet()) {
        writeEntry(
            out,
            service.getKey(),
            (String.join("\n", service.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
      }
      for (Map.Entry<String, byte[]> licence : licences.entrySet()) {
        writeEntry(out, licence.getKey(), licence.getValue());
      }
    }
    Files.move(repacked, mergedJar, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Root-level or META-INF-root licence artifacts; deeper paths are content, not licences. */
  private static boolean isLicenceFile(String name) {
    String file = name.substring(name.lastIndexOf('/') + 1);
    boolean metaInfRoot =
        name.startsWith("META-INF/") && name.indexOf('/', "META-INF/".length()) < 0;
    boolean atRoot = name.indexOf('/') < 0;
    return (metaInfRoot || atRoot)
        && (file.startsWith("LICENSE") || file.startsWith("NOTICE") || file.equals("about.html"));
  }

  /** STORED entries must carry size and CRC up front; java.util.zip enforces it. */
  private static void writeEntry(ZipOutputStream out, String name, byte[] data)
      throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(data.length);
    CRC32 crc = new CRC32();
    crc.update(data);
    entry.setCrc(crc.getValue());
    out.putNextEntry(entry);
    out.write(data);
    out.closeEntry();
  }
}
```

(add `import java.util.zip.CRC32;` to the import block)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='MergedJarPostProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 3 tests PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/main/java/dev/dediren/tools/dist/MergedJarPostProcessor.java dist-tool/src/test/java/dev/dediren/tools/dist/MergedJarPostProcessorTest.java
git commit -m "feat(dist): add MergedJarPostProcessor for service-file union and licence relocation"
```

---

### Task 3: ProGuard shrinker, keep rules, and dependency wiring

**Files:**
- Modify: `pom.xml` (root: properties + dependencyManagement)
- Modify: `dist-tool/pom.xml` (dependencies)
- Create: `dist-tool/src/main/resources/dev/dediren/tools/dist/bundle-shrink.pro`
- Create: `dist-tool/src/main/java/dev/dediren/tools/dist/LibShrinker.java`
- Create: `dist-tool/src/main/java/dev/dediren/tools/dist/ProGuardLibShrinker.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/ProGuardLibShrinkerTest.java`

**Interfaces:**
- Consumes: `MergedJarPostProcessor.apply(Path, List<Path>)` from Task 2.
- Produces: `interface LibShrinker { void shrink(List<Path> stagedJars, Path mergedJar) throws IOException; }` and `final class ProGuardLibShrinker implements LibShrinker` (both package-private). Also `static List<String> ProGuardLibShrinker.proGuardArgs(List<Path>, Path, Path)` and `static final String ProGuardLibShrinker.INJAR_FILTER`. Task 4 injects `LibShrinker` into `DistTool.build`.

- [ ] **Step 1: Pin ProGuard and error-prone in the root pom**

In root `pom.xml` properties (alphabetical position among the existing entries):

```xml
    <error-prone-annotations.version>2.47.0</error-prone-annotations.version>
    <proguard.version>7.7.0</proguard.version>
```

In root `pom.xml` dependencyManagement (near the guava entry):

```xml
      <!-- guava resolves error_prone_annotations 2.47.0 at runtime scope; dist-tool's
           proguard-base pulls 2.27.0 through gson. Pin the newer line so the
           dependencyConvergence enforcer stays green. -->
      <dependency>
        <groupId>com.google.errorprone</groupId>
        <artifactId>error_prone_annotations</artifactId>
        <version>${error-prone-annotations.version}</version>
      </dependency>
      <!-- Build-time only: dist-tool's bundle shrinker. The shrunk output ships; this library
           never does (it is not on any launcher classpath). -->
      <dependency>
        <groupId>com.guardsquare</groupId>
        <artifactId>proguard-base</artifactId>
        <version>${proguard.version}</version>
      </dependency>
```

In `dist-tool/pom.xml` dependencies:

```xml
    <!-- Bundle shrinker (ProGuardLibShrinker). Build-time tool dependency: the dist build runs
         it over the staged launcher classpath; nothing shipped depends on it. -->
    <dependency>
      <groupId>com.guardsquare</groupId>
      <artifactId>proguard-base</artifactId>
    </dependency>
```

- [ ] **Step 2: Verify convergence and resolution**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dmaven.test.skip=true package`
Expected: BUILD SUCCESS. If `dependencyConvergence` reports another divergent artifact from the proguard tree (kotlin-stdlib, gson, log4j-api/-core, org.json, jetbrains annotations), pin the newest line in root dependencyManagement with a one-line comment naming both requesters, and re-run. (If resolution fails offline, re-run once sandbox-disabled to warm `~/.m2`.)

- [ ] **Step 3: Add the keep-rules resource**

Create `dist-tool/src/main/resources/dev/dediren/tools/dist/bundle-shrink.pro` with exactly:

```
# Shrink-only pass over the staged launcher classpath (no optimization, no obfuscation):
# unreachable classes and members are removed, nothing is altered or renamed. Reachability
# starts from dev.dediren.** (CLI main, MCP server, engines are all entry points).
#
# Keep-rule inventory — every rule exists because removing it breaks a validated runtime path:
# - picocli.**: shrinking picocli makes CDS archive creation emit [warning][cds] lines on
#   stderr ("super class ... is excluded"), failing the bundle's quiet-stderr contract.
# - org.slf4j.**: backend wired by ServiceLoader + string-configured log levels.
# - com.fasterxml.jackson.**: Jackson annotations drive reflection on kept classes.
# - tools.jackson.databind.ext.**: optional/ext handlers (java.time etc.) wired semi-lazily.
# - io.modelcontextprotocol.spec/json: databind introspects the MCP POJOs reflectively.
# - ILayoutMetaDataProvider / SLF4JServiceProvider / McpJsonMapperSupplier /
#   JsonSchemaValidatorSupplier / TokenStreamFactory / ObjectMapper impls: ServiceLoader.
# - IDataObject impls + enum values/valueOf: ELK parses layout option values reflectively.
-dontoptimize
-dontobfuscate
-keepattributes *
-keepparameternames
-keepdirectories META-INF/services

-keep class dev.dediren.** { *; }

-keep class picocli.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class tools.jackson.databind.ext.** { *; }
-keep class io.modelcontextprotocol.spec.** { *; }
-keep class io.modelcontextprotocol.json.** { *; }

-keep class * implements org.eclipse.elk.core.data.ILayoutMetaDataProvider { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
-keep class * implements io.modelcontextprotocol.json.McpJsonMapperSupplier { *; }
-keep class * implements io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier { *; }
-keep class * implements tools.jackson.core.TokenStreamFactory { *; }
-keep class * extends tools.jackson.databind.ObjectMapper { *; }
-keep class * implements org.eclipse.elk.core.util.IDataObject { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ELK/EMF/reactor reference OSGi, Eclipse runtime, micrometer, blockhound and other optional
# platforms the plain-Java bundle intentionally omits; those references are dead code here.
-dontwarn **
-dontnote **
```

- [ ] **Step 4: Write the failing args-assembly test**

```java
package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProGuardLibShrinkerTest {

  @Test
  void proGuardArgsListInjarsInClasspathOrderThenOutjarLibraryjarsAndKeepRules() {
    List<Path> staged = List.of(Path.of("/lib/dep-alpha-1.0.0.jar"), Path.of("/lib/cli-1.jar"));

    List<String> args =
        ProGuardLibShrinker.proGuardArgs(staged, Path.of("/out/merged.jar"), Path.of("/keep.pro"));

    assertThat(args)
        .containsSubsequence(
            "-injars",
            "/lib/dep-alpha-1.0.0.jar" + ProGuardLibShrinker.INJAR_FILTER,
            "-injars",
            "/lib/cli-1.jar" + ProGuardLibShrinker.INJAR_FILTER,
            "-outjars",
            "/out/merged.jar",
            "-libraryjars",
            "<java.home>/jmods(!**.jar;!module-info.class)",
            "@/keep.pro");
  }

  @Test
  void injarFilterStripsSignaturesEclipseResourcesAndEmbeddedLicences() {
    // Licence files are stripped from the ProGuard input because MergedJarPostProcessor
    // re-adds them collision-free under META-INF/third-party/<jar>/.
    assertThat(ProGuardLibShrinker.INJAR_FILTER)
        .contains("!META-INF/*.SF")
        .contains("!META-INF/LICENSE*")
        .contains("!META-INF/NOTICE*")
        .contains("!META-INF/versions/**")
        .contains("!images/**")
        .contains("!model/**")
        .contains("!schema/**")
        .contains("!**.melk")
        .contains("!**._trace")
        .contains("!module-info.class");
  }
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='ProGuardLibShrinkerTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: COMPILATION ERROR (classes do not exist).

- [ ] **Step 6: Implement LibShrinker and ProGuardLibShrinker**

`LibShrinker.java`:

```java
package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Merges the staged launcher classpath jars into the single bundle lib jar. Production uses
 * {@link ProGuardLibShrinker}; hermeticity tests inject a fake because their staged "jars" are
 * text fixtures that must never reach the real shrinker.
 */
interface LibShrinker {
  void shrink(List<Path> stagedJars, Path mergedJar) throws IOException;
}
```

`ProGuardLibShrinker.java`:

```java
package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

/**
 * Shrink-only ProGuard pass (no optimization, no obfuscation) over the staged launcher
 * classpath, merged into one output jar. Keep rules live in the checked-in
 * {@code bundle-shrink.pro} resource next to this class; the rule-by-rule rationale is
 * documented there. {@link MergedJarPostProcessor} then unions ServiceLoader registrations and
 * relocates embedded licence files — both things a first-wins merge silently drops.
 */
final class ProGuardLibShrinker implements LibShrinker {

  /**
   * Strips content that is dead outside Eclipse/OSGi hosts (UI icons under {@code images/},
   * {@code .ecore}/{@code .xsd} model sources, xtext {@code ._trace}, {@code .melk}, OSGi
   * manifest extras), multi-release variants, signature files that cannot survive a merge, and
   * embedded licence files (re-added collision-free by {@link MergedJarPostProcessor}).
   */
  static final String INJAR_FILTER =
      "(!META-INF/*.SF,!META-INF/*.RSA,!META-INF/*.DSA,!META-INF/LICENSE*,!META-INF/NOTICE*,"
          + "!module-info.class,!META-INF/versions/**,!META-INF/maven/**,!**.melk,!**._trace,"
          + "!images/**,!model/**,!schema/**,!plugin.xml,!plugin.properties,!about.html,"
          + "!about_files/**,!.api_description,!.options,!profile.list,!systembundle.properties)";

  private static final String KEEP_RULES_RESOURCE = "bundle-shrink.pro";

  @Override
  public void shrink(List<Path> stagedJars, Path mergedJar) throws IOException {
    Path keepRules = Files.createTempFile("bundle-shrink", ".pro");
    try (var in = ProGuardLibShrinker.class.getResourceAsStream(KEEP_RULES_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing keep-rules resource: " + KEEP_RULES_RESOURCE);
      }
      Files.copy(in, keepRules, StandardCopyOption.REPLACE_EXISTING);
    }
    try {
      String[] args = proGuardArgs(stagedJars, mergedJar, keepRules).toArray(String[]::new);
      Configuration configuration = new Configuration();
      ConfigurationParser parser = new ConfigurationParser(args, System.getProperties());
      try {
        parser.parse(configuration);
      } finally {
        parser.close();
      }
      new ProGuard(configuration).execute();
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException("ProGuard shrink failed for " + mergedJar, error);
    } finally {
      Files.deleteIfExists(keepRules);
    }
    if (!Files.isRegularFile(mergedJar)) {
      throw new IOException("ProGuard reported success but wrote no jar at " + mergedJar);
    }
    MergedJarPostProcessor.apply(mergedJar, stagedJars);
  }

  /** Package-private for tests: full argument list, injars in launcher classpath order. */
  static List<String> proGuardArgs(List<Path> stagedJars, Path mergedJar, Path keepRules) {
    List<String> args = new ArrayList<>();
    for (Path jar : stagedJars) {
      args.add("-injars");
      args.add(jar + INJAR_FILTER);
    }
    args.add("-outjars");
    args.add(mergedJar.toString());
    args.add("-libraryjars");
    args.add("<java.home>/jmods(!**.jar;!module-info.class)");
    args.add("@" + keepRules);
    return args;
  }
}
```

(If ProGuard 7.7's `ConfigurationParser` constructor signature differs — it is `ConfigurationParser(String[] args, java.util.Properties baseProperties)` in 7.x, used exactly this way by `proguard.ProGuard.main` — mirror whatever `proguard.ProGuard.main` does in the resolved jar rather than inventing a variant.)

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='ProGuardLibShrinkerTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: 2 tests PASS.

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add pom.xml dist-tool/pom.xml dist-tool/src/main/resources/dev/dediren/tools/dist/bundle-shrink.pro dist-tool/src/main/java/dev/dediren/tools/dist/LibShrinker.java dist-tool/src/main/java/dev/dediren/tools/dist/ProGuardLibShrinker.java dist-tool/src/test/java/dev/dediren/tools/dist/ProGuardLibShrinkerTest.java
git commit -m "feat(dist): add ProGuard shrink-only bundle-lib shrinker and keep rules"
```

---

### Task 4: Wire the shrinker into DistTool build

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (`build` ~line 218, `installLauncher` ~line 761, `verifyPackagedLib` call ~line 248; add `withMergedClasspath` + `mergedJarName` near `withCdsArchive` ~line 864)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java` (`buildProducesVersionOnlyJavaArchive` ~line 57, `writeMinimalDistributionRoot`/`writeLauncher` ~lines 349, 400; add `withMergedClasspath` tests)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistHermeticityTest.java` (packaging test ~line 47)

**Interfaces:**
- Consumes: `LibShrinker`, `ProGuardLibShrinker` (Task 3).
- Produces: `static String DistTool.mergedJarName(String version)` returning `"dediren-bundle-" + version + ".jar"`; `static String DistTool.withMergedClasspath(String script, String mergedJarName)`; seam overload `static void DistTool.build(Path root, String version, Path notices, java.util.function.Consumer<Path> afterStage, LibShrinker shrinker)`. Task 6's smoke assertion uses `mergedJarName`.

- [ ] **Step 1: Write the failing tests**

Add to `DistModuleTest`:

```java
  @Test
  void withMergedClasspathReplacesClasspathLineWithSingleJar() {
    String script =
        "#!/bin/sh\n"
            + "CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/a-1.jar:\"$REPO\"/b-2.jar\n"
            + "exec java\n";

    String rewritten = DistTool.withMergedClasspath(script, "dediren-bundle-2026.06.0.jar");

    assertThat(rewritten)
        .contains("CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/dediren-bundle-2026.06.0.jar")
        .doesNotContain("a-1.jar")
        .doesNotContain("b-2.jar")
        .contains("exec java");
  }

  @Test
  void withMergedClasspathFailsWithoutClasspathLine() {
    assertThatThrownBy(() -> DistTool.withMergedClasspath("#!/bin/sh\nexec java\n", "x.jar"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CLASSPATH");
  }

  @Test
  void mergedJarNameCarriesTheProductVersion() {
    assertThat(DistTool.mergedJarName("2026.06.0")).isEqualTo("dediren-bundle-2026.06.0.jar");
  }
```

Rewrite `DistHermeticityTest.buildPackagesExactlyDeclaredJarsAndExcludesRuntimeGeneratedCdsContent` (same @TempDir + CDS-residue seam, new shrinker seam and expectations):

```java
  @Test
  void buildPackagesSingleShrunkJarAndExcludesRuntimeGeneratedCdsContent(@TempDir Path root)
      throws Exception {
    writeDistributionRoot(root);
    List<Path> shrunk = new java.util.ArrayList<>();
    LibShrinker fakeShrinker =
        (stagedJars, mergedJar) -> {
          shrunk.addAll(stagedJars);
          Files.writeString(mergedJar, "merged jar");
        };
    DistTool.build(
        root,
        VERSION,
        root.resolve("THIRD-PARTY-NOTICES.md"),
        bundle -> {
          try {
            Files.createDirectories(bundle.resolve("cds"));
            Files.writeString(bundle.resolve("cds/cli.jsa"), "cds archive");
            Files.writeString(bundle.resolve("stray.jsa"), "cds archive");
          } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
          }
        },
        fakeShrinker);

    Path archive = root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.gz");
    assertThat(archive).isRegularFile();
    List<String> entries = archiveEntries(archive);
    assertThat(entries)
        .noneMatch(entry -> entry.endsWith(".jsa"))
        .noneMatch(entry -> entry.contains("/cds"));
    List<String> libJars =
        entries.stream()
            .filter(entry -> entry.startsWith("dediren-agent-bundle-" + VERSION + "/lib/"))
            .filter(entry -> !entry.endsWith("/"))
            .map(entry -> entry.substring(entry.lastIndexOf('/') + 1))
            .sorted()
            .toList();
    // The packaged lib/ holds exactly the shrunk merged jar; the staged inputs never ship.
    assertThat(libJars).containsExactly("dediren-bundle-" + VERSION + ".jar");
    // The shrinker saw every staged jar, in launcher classpath order.
    assertThat(shrunk)
        .extracting(path -> path.getFileName().toString())
        .containsExactly(
            "dep-alpha-1.0.0.jar",
            "cli-module-" + VERSION + ".jar",
            "generic-graph-module-" + VERSION + ".jar",
            "elk-layout-module-" + VERSION + ".jar",
            "render-module-" + VERSION + ".jar",
            "archimate-oef-export-module-" + VERSION + ".jar",
            "uml-xmi-export-module-" + VERSION + ".jar");
    // The shipped launcher classpath is the single jar.
    String script =
        readArchiveEntry(archive, "dediren-agent-bundle-" + VERSION + "/bin/dediren");
    assertThat(script)
        .contains("CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/dediren-bundle-" + VERSION + ".jar")
        .doesNotContain("dep-alpha-1.0.0.jar");
  }

  private static String readArchiveEntry(Path archive, String entry) throws Exception {
    Process process =
        new ProcessBuilder("tar", "-xOf", archive.toString(), entry)
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("tar -xOf %s %s%n%s", archive, entry, output).isZero();
    return output;
  }
```

Update `DistModuleTest.buildProducesVersionOnlyJavaArchive` to use the seam (the minimal root's launcher now needs a CLASSPATH line and a staged jar so `declaredClasspathJars`/`verifyStagedLib` pass):

```java
    // in writeMinimalDistributionRoot / writeLauncher: launcher script becomes
    Files.writeString(
        install.resolve("bin").resolve(sourceScript),
        """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            REPO="$BASEDIR"/lib
            CLASSPATH="$BASEDIR"/etc:"$REPO"/cli-2026.06.0.jar
            exec "$JAVACMD" -classpath "$CLASSPATH" "$@"
            """);
    Files.writeString(install.resolve("lib/cli-2026.06.0.jar"), "module jar");
```

and the test invokes:

```java
    DistTool.build(
        root,
        "2026.06.0",
        notices,
        bundle -> {},
        (stagedJars, mergedJar) -> Files.writeString(mergedJar, "merged jar"));
```

(keep all its existing bundle.json / stale-artifact / agent-usage assertions unchanged).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='DistModuleTest,DistHermeticityTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: COMPILATION ERROR (`withMergedClasspath`, `mergedJarName`, 5-arg `build` do not exist).

- [ ] **Step 3: Implement the DistTool changes**

Add near `withCdsArchive`:

```java
  /** The single shrunk classpath jar the bundle ships in {@code lib/}. */
  static String mergedJarName(String version) {
    return "dediren-bundle-" + version + ".jar";
  }

  /**
   * Replaces the appassembler CLASSPATH line (the resolved multi-jar runtime classpath) with the
   * single shrunk bundle jar. Must run AFTER {@link #declaredClasspathJars} has captured the
   * original jar set: the original line is the hermeticity input contract; the rewritten line is
   * what ships.
   */
  static String withMergedClasspath(String script, String mergedJarName) {
    Matcher matcher = Pattern.compile("(?m)^CLASSPATH=.*$").matcher(script);
    if (!matcher.find()) {
      throw new IllegalArgumentException("launcher script has no CLASSPATH line to rewrite");
    }
    return matcher.replaceFirst(
        Matcher.quoteReplacement("CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/" + mergedJarName));
  }
```

Change `build` (3-arg and seam overloads; the 4-arg overload is replaced by the 5-arg one — update its two test callers):

```java
  private static void build(Path root, String version, Path notices) throws Exception {
    build(root, version, notices, staged -> {}, new ProGuardLibShrinker());
  }

  /**
   * Staging seam (package-private for tests): {@code afterStage} runs against the fully staged
   * bundle directory immediately before it is archived (CDS-residue injection), and
   * {@code shrinker} produces the single packaged lib jar so tests with text-file jar fixtures
   * never invoke the real ProGuard pass.
   */
  static void build(
      Path root,
      String version,
      Path notices,
      java.util.function.Consumer<Path> afterStage,
      LibShrinker shrinker)
      throws Exception {
```

and inside it replace the staging block:

```java
    String mergedJarName = mergedJarName(version);
    List<Path> stagedJars = new ArrayList<>();
    for (Launcher launcher : LAUNCHERS) {
      stagedJars.addAll(installLauncher(root, bundle, launcher, mergedJarName));
    }
    shrinker.shrink(stagedJars, bundle.resolve("lib").resolve(mergedJarName));
    verifyPackagedLib(bundle.resolve("lib"), Set.of(mergedJarName));
```

Change `installLauncher` to rewrite the classpath and return staged jar paths in classpath order (it no longer copies jars):

```java
  private static List<Path> installLauncher(
      Path root, Path bundle, Launcher launcher, String mergedJarName) throws IOException {
    Path install = root.resolve(launcher.installDir());
    Path sourceBin = install.resolve("bin").resolve(launcher.sourceScript());
    if (!Files.isRegularFile(sourceBin)) {
      throw new IOException("missing installed launcher: " + sourceBin);
    }
    String sourceScript = Files.readString(sourceBin, StandardCharsets.UTF_8);
    Set<String> declaredJars = declaredClasspathJars(sourceScript);
    verifyStagedLib(install.resolve("lib"), declaredJars, launcher);
    Path targetBin = bundle.resolve("bin").resolve(launcher.bundleScript());
    String script = withBundleRootExport(sourceScript);
    script = withCdsArchive(script, launcher.sourceScript());
    script = withMergedClasspath(script, mergedJarName);
    Files.writeString(targetBin, script, StandardCharsets.UTF_8);
    makeExecutable(targetBin);
    List<Path> stagedJars = new ArrayList<>();
    for (String jar : declaredJars) {
      stagedJars.add(install.resolve("lib").resolve(jar));
    }
    return stagedJars;
  }
```

Delete `copyDeclaredJars` (now unused). `verifyStagedLib`, `declaredClasspathJars`, and `verifyPackagedLib` stay as they are.

- [ ] **Step 4: Run the dist-tool tests**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='!*FuzzTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: all dist-tool tests PASS (including the two untouched divergence tests, which throw in `verifyStagedLib` before any shrinking).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java dist-tool/src/test/java/dev/dediren/tools/dist/DistHermeticityTest.java
git commit -m "feat(dist): package a single shrink-merged lib jar and rewrite the launcher classpath"
```

---

### Task 5: Notices wording for modified redistribution

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (`writeThirdPartyNotices` ~lines 328-334)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java` (`noticesIdentifyLicencesAndEmbedTexts` ~line 117)

**Interfaces:**
- Consumes: nothing new.
- Produces: notices text later copied verbatim into the bundle; Task 7's docs reference its `META-INF/third-party/` location.

- [ ] **Step 1: Extend the failing test**

In `noticesIdentifyLicencesAndEmbedTexts`, add to the existing assertion chain:

```java
        .contains("modified object form")
        .contains("shrink-only")
        .contains("`META-INF/third-party/<jar-name>/`")
        .doesNotContain("unmodified object form")
```

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='DistModuleTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL (old wording).

- [ ] **Step 2: Rewrite the redistribution paragraph**

In `writeThirdPartyNotices`, replace:

```java
    notice.append("## Redistributed Third-Party Libraries\n\n");
    notice.append("Each launcher `lib/` directory redistributes the libraries below in\n");
    notice.append("unmodified object form. Every library remains covered by its upstream\n");
    notice.append("licence, identified per jar as `(project, licence)`. Licence and notice\n");
    notice.append("files embedded inside the jars (`META-INF/LICENSE`, `META-INF/NOTICE`,\n");
    notice.append("`about.html`) are redistributed unchanged and remain authoritative for\n");
    notice.append("their jar.\n\n");
```

with:

```java
    notice.append("## Redistributed Third-Party Libraries\n\n");
    notice.append("The bundle `lib/` jar redistributes the libraries below in modified\n");
    notice.append("object form: classes and resources unreachable from Dediren's entry\n");
    notice.append("points are removed (a shrink-only pass — no surviving class is altered,\n");
    notice.append("optimized, or renamed) and the remainder is merged into the single\n");
    notice.append("bundle jar. Every library remains covered by its upstream licence,\n");
    notice.append("identified per input jar as `(project, licence)`. Licence and notice\n");
    notice.append("files embedded inside the input jars (`META-INF/LICENSE`,\n");
    notice.append("`META-INF/NOTICE`, `about.html`) are carried unchanged inside the bundle\n");
    notice.append("jar under `META-INF/third-party/<jar-name>/`.\n\n");
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dtest='DistModuleTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 4: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "feat(dist): update third-party notices for shrink-merged redistribution"
```

---

### Task 6: Smoke gates — single-jar lib, archive-size ceiling, full dist-smoke

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (`smoke` ~line 385)

**Interfaces:**
- Consumes: `mergedJarName(String)` from Task 4.
- Produces: smoke failures on lib divergence or archive-size regression.

- [ ] **Step 1: Add the two smoke assertions**

In `smoke(...)`, right after the `Files.isRegularFile(archive)` guard:

```java
    long archiveBytes = Files.size(archive);
    if (archiveBytes > MAX_ARCHIVE_BYTES) {
      throw new IllegalStateException(
          "release archive is "
              + archiveBytes
              + " bytes; the "
              + MAX_ARCHIVE_BYTES
              + "-byte ceiling exists to catch a silent shrink regression (unshrunk baseline"
              + " ~15.1 MB, shrunk-but-deflated ~7.2 MB, shrunk+stored ~5.4 MB) — if legitimate"
              + " dependency growth trips it, raise the ceiling deliberately in the same change");
    }
```

and after `Path bundle = findBundleDir(temp);`:

```java
    assertSingleShrunkLibJar(bundle, version);
```

with, alongside the other private helpers:

```java
  /**
   * Ceiling between the shipped size (~5.4 MB, shrunk + STORED repack) and the two regression
   * shapes above it — STORED silently degrading to deflated entries (~7.2 MB) and the shrink
   * pass degrading to pass-through (~15.1 MB). Trips on either regression, not ordinary growth.
   */
  private static final long MAX_ARCHIVE_BYTES = 7_000_000L;

  /** The packaged lib/ must hold exactly the shrunk bundle jar — anything else means the
   * CLASSPATH rewrite and the shrinker disagree about what ships. */
  private static void assertSingleShrunkLibJar(Path bundle, String version) throws IOException {
    List<String> jars;
    try (var entries = Files.list(bundle.resolve("lib"))) {
      jars = entries.map(path -> path.getFileName().toString()).sorted().toList();
    }
    if (!jars.equals(List.of(mergedJarName(version)))) {
      throw new IllegalStateException(
          "bundle lib/ must hold exactly " + mergedJarName(version) + " but holds " + jars);
    }
  }
```

- [ ] **Step 2: Run the full dist-smoke (the real integration gate)**

Run: `./mvnw -B -ntp -pl dist-tool -am -Dmaven.test.skip=true verify -Pdist-smoke`
Expected: BUILD SUCCESS with `distribution smoke test passed`, `mcp stdio smoke passed`, `cold-CDS mcp stdio smoke passed` — the packaged archive now carries the ProGuard-shrunk single STORED-entry jar and every pipeline (layout, render, OEF+XMI export, MCP stdio, CDS creation, logging gates) runs against it. Record `ls -l dist/*.tar.gz` — expect ~5.4 MB (vs 15,150,460 baseline).

If smoke fails with an ELK "algorithm not found" or MCP mapper error, a ServiceLoader union regressed (check `MergedJarPostProcessor`); with `[warning][cds]` stderr noise, a keep rule for a CDS-linked library was lost (check `picocli.**` keep); with a `ClassNotFoundException` in a validator format path, extend `bundle-shrink.pro` with a keep for the named class's package and re-run.

- [ ] **Step 3: Commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "test(dist): guard single-jar lib and archive-size ceiling in dist smoke"
```

---

### Task 7: Documentation — README, threat model, CLAUDE.md, research-doc stamp

**Files:**
- Modify: `README.md` (Bundle Layout ~line 152; `dist-build` hermeticity paragraph ~line 208)
- Modify: `docs/threat-model.md` (`### Build & release chain` ~line 192; Attacker Goals table ~line 206)
- Modify: `CLAUDE.md` (`## Files That Move Together`)
- Modify: `docs/superpowers/plans/2026-07-15-bundle-size-reduction-research.md` (status stamp under the title)

**Interfaces:**
- Consumes: notices location `META-INF/third-party/<jar-name>/` (Task 5), merged jar name scheme (Task 4).
- Produces: user-facing and threat-model truth for the shipped layout.

- [ ] **Step 1: README bundle layout + hermeticity wording**

Change the layout line:

```text
  lib/            one shrink-merged classpath jar (no bundled JRE)
```

and the `dist-build` paragraph sentence to:

```markdown
`dist-build` is hermetic and self-verifying — it regenerates each module's
staging directory, verifies the staged jars against the launcher classpath,
shrinks them into the single `lib/dediren-bundle-<version>.jar` (a shrink-only
ProGuard pass; third-party licence files ride inside it under
`META-INF/third-party/`), and fails if the packaged `lib/` diverges from that
one jar — so a locally built archive is safe to distribute without a preceding
`clean`.
```

- [ ] **Step 2: Threat model — build-chain section and attacker-goals row**

Read the current `### Build & release chain` section first and append a paragraph in its
style:

```markdown
The bundle `lib/` jar is produced by a shrink-only ProGuard pass over the staged
launcher classpath (`dist-tool`, keep rules checked in at
`dist-tool/src/main/resources/dev/dediren/tools/dist/bundle-shrink.pro`).
ProGuard is a pinned, SBOM-scanned build-time dependency and never ships; the
`-Pdist-smoke` gate exercises every pipeline against the packaged shrunk
archive, and the smoke's archive-size ceiling trips if shrinking silently
degrades.
```

Add an Attacker Goals row:

```markdown
| Shipped classes silently diverge from vetted dependencies (shrinker defect or compromised ProGuard) | ProGuard version pinned in root `dependencyManagement` and resolved from Maven Central like every dependency (aggregate SBOM + Grype gate scan it); the pass is shrink-only — no optimization or obfuscation — with keep rules reviewed in-repo; `-Pdist-smoke` drives layout, render, both exports, and MCP stdio against the packaged shrunk archive on every release build | Reachability shrinking can drop reflection-only code paths the smoke never exercises; the SBOM lists upstream components while shipped bytes are shrunk subsets, so per-jar upstream hash comparison no longer applies — the bundle-level provenance attestation remains the integrity signal |
```

- [ ] **Step 3: CLAUDE.md co-change rule**

Add to `## Files That Move Together`:

```markdown
- Runtime dependencies or reflective surfaces on the cli classpath: the bundle
  ships one shrink-merged `lib/` jar, so a new ServiceLoader registration,
  annotation-driven library, or reflection-reached class needs a matching keep
  rule in `dist-tool` `bundle-shrink.pro` (and a licence attribution in
  `DistTool.THIRD_PARTY_ATTRIBUTIONS`) in the same change — `-Pdist-smoke` is
  the gate that catches a miss.
```

- [ ] **Step 4: Stamp the research doc**

Under the title of `docs/superpowers/plans/2026-07-15-bundle-size-reduction-research.md` add:

```markdown
> **Status 2026-07-16:** adopted through "shrink + merge + strip + no-YAML + STORED repack,
> `.tar.gz` retained" — implemented by `2026-07-16-bundle-shrink-implementation.md`. The tar.xz
> format switch (measured 3.50 MB) remains the unadopted follow-up.
```

- [ ] **Step 5: Verify and commit**

Run: `git diff --check`
Expected: no output.

```bash
git add README.md docs/threat-model.md CLAUDE.md docs/superpowers/plans/2026-07-15-bundle-size-reduction-research.md
git commit -m "docs: sync README, threat model, and CLAUDE.md with the shrink-merged bundle"
```

---

### Task 8: Full verification and audit gates

**Files:** none (verification only; fixes go where the failure is).

- [ ] **Step 1: Full test suite + quality gate**

```bash
./mvnw -Pquality spotless:apply
./mvnw -B -ntp -Pquality verify -Dtest='!*FuzzTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: BUILD SUCCESS (formatting clean, SpotBugs clean, all tests green). The two FuzzTest classes are excluded only because they fail under the local sandbox; they are unaffected by this change — optionally run the full suite once sandbox-disabled to include them.

- [ ] **Step 2: Dist smoke + size measurement**

```bash
./mvnw -B -ntp -pl dist-tool -am -Dmaven.test.skip=true verify -Pdist-smoke
ls -l dist/*.tar.gz
git diff --check
```
Expected: smoke passes; archive ~5.4 MB (was 15,150,460 bytes); no whitespace errors. Record the actual byte count for the handoff.

- [ ] **Step 3: Audit gates (fix blocks; accept-or-fix warns in the handoff)**

- `souroldgeezer-audit:test-quality-audit` — quick pass over `MergedJarPostProcessorTest`, `ProGuardLibShrinkerTest`, and the modified `DistModuleTest`/`DistHermeticityTest`.
- `souroldgeezer-audit:devsecops-audit` — quick pass over the proguard-base/error-prone pom wiring, the shrink step's place in the release chain, and the threat-model updates.
- `souroldgeezer-audit:ip-hygiene` — the notices wording change and `META-INF/third-party/` licence relocation. One known open question for it: `about.html` files reference licence texts under `about_files/**`, which the injar filter strips — decide whether `about_files/**` must be relocated alongside `about.html` in `MergedJarPostProcessor`.

- [ ] **Step 4: Handoff**

Report: final archive size vs 15,150,460-byte baseline, bench medians if re-run, accepted audit findings, and the explicit non-goal (tar.xz format switch) with its measured potential (3.50 MB) from the research doc.

---

## Self-Review Notes

- Spec coverage: research-doc adoption step 1 (shrink+merge in `.tar.gz` form) → Tasks 2-4, 6; step 2 (notices + threat model + ip-hygiene) → Tasks 5, 7, 8; step 3 (broader smoke insurance) → Task 6's lib/size gates plus the existing full-pipeline smoke (fixture-sweep broadening deliberately deferred — recorded in Task 8 handoff if audits flag it); step 4 (tar.xz) → explicitly out of scope. YAML drop → Task 1.
- The two `DistHermeticityTest` divergence tests keep using `DistTool.run` (production shrinker constructed, never reached — `verifyStagedLib` throws first); only the packaging test needed the seam.
- `declaredClasspathJars` returns a `LinkedHashSet`, so classpath order is preserved into the shrinker's injar order (first-wins duplicate-resource semantics stay deterministic).
- `simplelogger.properties` sits at the cli-jar root; the injar filter strips only the exact name `plugin.properties`, so logging config survives (smoke asserts the logging contract end-to-end).
- Merged-jar entry names that the filter strips (`about.html`, `about_files/**`, `META-INF/LICENSE*`, …) are re-added only under `META-INF/third-party/`, so no first-wins ambiguity exists anywhere in the shipped jar.

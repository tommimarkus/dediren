# Dediren MCP Server Implementation Plan

Status: complete — merged 51d7f33, released 2026.07.17.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `dediren mcp` — an MCP stdio server exposing `dediren_validate`, `dediren_build`, and a sectioned `dediren_guide` as typed tools, so coding agents drive Dediren without shelling out to the CLI.

**Architecture:** A new tier-3 `mcp` module holds the protocol wiring, tool schemas, handlers, workspace-root path confinement, and the guide topic map. It compile-depends on `contracts`, `core`, and `engine-api`, and **never** on an engine implementation — it receives an `Engines` registry through its constructor. `cli` gains one thin `McpCommand` that calls the existing `EngineWiring.defaults()` and hands the registry over, adding a `cli → mcp` edge. Handlers are thin: they resolve and confine paths, call the same `CoreCommands` / `BuildCommand` entry points the CLI calls, and return the resulting envelope JSON verbatim as the tool result text.

**Tech Stack:** Java 21, Maven (checked-in wrapper), picocli (CLI), official MCP Java SDK `io.modelcontextprotocol.sdk:mcp` 2.0.0 (stdio transport, Jackson 3), JUnit 5 + AssertJ, ArchUnit (dependency rules, in `dist-tool`).

**Spec:** `docs/superpowers/specs/2026-07-14-dediren-mcp-server-design.md`

## Global Constraints

- Java 21+ (`maven.compiler.release` 21). Build with the checked-in `./mvnw`.
- Product version is `2026.07.16`; all new module POMs inherit it from the root parent.
- Code style: google-java-format (GOOGLE), enforced by Spotless. Run `./mvnw -Pquality spotless:apply` before every commit that touches Java.
- SpotBugs (Max effort, Medium threshold, correctness only) must stay clean. No silent suppressions.
- The MCP module must **never** compile-depend on an engine implementation (`dev.dediren.plugins..`, `dev.dediren.semantics..`) or on `cli`. ArchUnit enforces this (Task 5).
- Every `DEDIREN_*` diagnostic code must be a `DiagnosticCode` enum constant, never a string literal (`DiagnosticCodeOwnershipTest` enforces).
- Every `DEDIREN_*` token appearing in `docs/agent-usage.md` must exist in source (`AgentUsageDocConsistencyTest` enforces).
- Tool results carry the **existing envelope JSON verbatim**. No second result format is introduced.
- stdio MCP: **stdout is the JSON-RPC channel.** Nothing but protocol frames may reach it.
- New module directory: `mcp/`, artifactId `mcp`, package `dev.dediren.mcp`.

---

## File Structure

| File | Responsibility |
|---|---|
| `mcp/pom.xml` | Module POM; declares the MCP SDK dependency. |
| `mcp/src/main/java/dev/dediren/mcp/WorkspacePaths.java` | Workspace-root path confinement (real-path resolution + containment check). |
| `mcp/src/main/java/dev/dediren/mcp/PathOutsideRootException.java` | Signals a path that escapes the root. |
| `mcp/src/main/java/dev/dediren/mcp/GuideCatalog.java` | The curated topic map and section extraction over the bundled `agent-usage.md`. |
| `mcp/src/main/java/dev/dediren/mcp/DedirenTools.java` | The three tool handlers: validate, build, guide. Envelope passthrough + `isError`. |
| `mcp/src/main/java/dev/dediren/mcp/ToolSchemas.java` | The three tools' JSON input schemas. |
| `mcp/src/main/java/dev/dediren/mcp/DedirenMcpServer.java` | Server assembly: transport, capabilities, tool registration, `--read-only`. |
| `mcp/src/main/java/dev/dediren/mcp/StdoutIntegrity.java` | Claims the real stdout for the protocol; redirects `System.out` to stderr. |
| `mcp/src/main/resources/dediren/agent-usage.md` | Build-time copy of `docs/agent-usage.md` (via `maven-resources-plugin`). |
| `cli/src/main/java/dev/dediren/cli/Main.java` | Adds the `mcp` subcommand (thin: parse flags, wire engines, run server). |
| `pom.xml` | New `<module>mcp</module>`; `mcp.sdk.version` + `slf4j.version` properties; `dependencyManagement` pins. |
| `dist-tool/.../ArchitectureRulesTest.java` | ArchUnit rules for the new module and the `cli → mcp` edge. |
| `dist-tool/.../DistTool.java` | dist-smoke: real JSON-RPC over a spawned `bin/dediren mcp`. |
| `docs/threat-model.md`, `docs/architecture-guidelines.md`, `README.md`, `docs/agent-usage.md` | The docs that move with this change. |

---

### Task 1: Module scaffold, dependency pins, and path confinement

Delivers the `mcp` module with a green build (including the two `dependencyConvergence` breaks the SDK causes) and the security control the whole design rests on.

**Files:**
- Modify: `pom.xml` (modules list, properties, `dependencyManagement`)
- Create: `mcp/pom.xml`
- Modify: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`
- Create: `mcp/src/main/java/dev/dediren/mcp/PathOutsideRootException.java`
- Create: `mcp/src/main/java/dev/dediren/mcp/WorkspacePaths.java`
- Test: `mcp/src/test/java/dev/dediren/mcp/WorkspacePathsTest.java`

**Interfaces:**
- Produces: `WorkspacePaths.resolveExisting(Path root, String candidate) throws PathOutsideRootException` → `Path` (must exist); `WorkspacePaths.resolveForWrite(Path root, String candidate) throws PathOutsideRootException` → `Path` (need not exist); `PathOutsideRootException.getMessage()`; `DiagnosticCode.MCP_PATH_OUTSIDE_ROOT`.

- [ ] **Step 1: Add the module and the dependency pins to the root POM**

In `pom.xml`, add `<module>mcp</module>` to `<modules>` immediately after `<module>cli</module>`.

Add these two properties alongside the existing version properties (near `<json-schema-validator.version>`):

```xml
    <mcp.sdk.version>2.0.0</mcp.sdk.version>
    <!-- slf4j arrives transitively from ELK (2.0.17) and from the MCP SDK (2.0.16).
         dependencyConvergence fails on the disagreement unless it is managed here. -->
    <slf4j.version>2.0.17</slf4j.version>
```

Add these entries to the root `<dependencyManagement><dependencies>` block:

```xml
      <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-bom</artifactId>
        <version>${mcp.sdk.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>${slf4j.version}</version>
      </dependency>
```

Note: `json-schema-validator` (3.0.6), `jackson-databind`/`jackson-core` (3.2.1) and `jackson-annotations` (2.22) are **already** managed in the root POM and will override the SDK's older requests. Only `slf4j-api` was unmanaged.

- [ ] **Step 2: Create the module POM**

Create `mcp/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>dev.dediren</groupId>
    <artifactId>dediren</artifactId>
    <version>2026.07.16</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>mcp</artifactId>

  <dependencies>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>contracts</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>engine-api</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp</artifactId>
    </dependency>
    <dependency>
      <groupId>tools.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: Add the diagnostic code**

In `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`, add a constant in the enum, keeping the existing alphabetical grouping (place it with the other `M`/`P` prefixed entries — the enum is alphabetical, so it goes before `SEMANTIC_PROFILE_UNSUPPORTED`):

```java
  MCP_PATH_OUTSIDE_ROOT("DEDIREN_MCP_PATH_OUTSIDE_ROOT"),
```

- [ ] **Step 4: Write the failing test**

Create `mcp/src/test/java/dev/dediren/mcp/WorkspacePathsTest.java`:

```java
package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTest {

  @Test
  void resolvesRelativePathInsideRoot(@TempDir Path root) throws Exception {
    Path source = Files.createFile(root.resolve("model.json"));

    Path resolved = WorkspacePaths.resolveExisting(root, "model.json");

    assertThat(resolved).isEqualTo(source.toRealPath());
  }

  @Test
  void resolvesAbsolutePathInsideRoot(@TempDir Path root) throws Exception {
    Path source = Files.createFile(root.resolve("model.json"));

    Path resolved = WorkspacePaths.resolveExisting(root, source.toString());

    assertThat(resolved).isEqualTo(source.toRealPath());
  }

  @Test
  void rejectsTraversalOutsideRoot(@TempDir Path root) {
    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "../../etc/passwd"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void rejectsSymlinkEscapingRoot(@TempDir Path root, @TempDir Path outside) throws Exception {
    Path secret = Files.writeString(outside.resolve("secret.json"), "{}");
    Path link = root.resolve("link.json");
    try {
      Files.createSymbolicLink(link, secret);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support; the traversal test still covers containment.
    }

    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "link.json"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveExistingRejectsMissingFile(@TempDir Path root) {
    assertThatThrownBy(() -> WorkspacePaths.resolveExisting(root, "absent.json"))
        .isInstanceOf(PathOutsideRootException.class);
  }

  @Test
  void resolveForWriteAcceptsNonExistentPathInsideRoot(@TempDir Path root) throws Exception {
    Path resolved = WorkspacePaths.resolveForWrite(root, "out/nested/dir");

    assertThat(resolved).isEqualTo(root.toRealPath().resolve("out/nested/dir"));
    assertThat(resolved).doesNotExist();
  }

  @Test
  void resolveForWriteRejectsNonExistentPathOutsideRoot(@TempDir Path root) {
    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "../escape/out"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveForWriteRejectsWriteThroughEscapingSymlinkDirectory(
      @TempDir Path root, @TempDir Path outside) throws Exception {
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return;
    }

    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "link/out"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./mvnw -pl mcp -am test -Dtest=WorkspacePathsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `WorkspacePaths` / `PathOutsideRootException` do not exist.

- [ ] **Step 6: Write the implementation**

Create `mcp/src/main/java/dev/dediren/mcp/PathOutsideRootException.java`:

```java
package dev.dediren.mcp;

/**
 * A tool argument named a path that does not resolve inside the server's workspace root.
 *
 * <p>This is the model-facing control, not a defence against a hostile local user: the server runs
 * with the spawning user's authority, and MCP clients frequently auto-approve tool calls, so an
 * unconfined {@code out} would let a model write anywhere the user can.
 */
public final class PathOutsideRootException extends Exception {
  private static final long serialVersionUID = 1L;

  private final String candidate;

  public PathOutsideRootException(String candidate, String reason) {
    super("path '" + candidate + "' is outside the workspace root: " + reason);
    this.candidate = candidate;
  }

  public String candidate() {
    return candidate;
  }
}
```

Create `mcp/src/main/java/dev/dediren/mcp/WorkspacePaths.java`:

```java
package dev.dediren.mcp;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Confines every model-supplied path to the server's workspace root.
 *
 * <p>Normalization alone is not enough. A symlink <em>inside</em> the root pointing outside it is
 * exactly the interesting case, and only real-path resolution catches it — so both entry points
 * resolve symlinks before the containment check. For a path that need not exist yet (an output
 * directory), the nearest existing ancestor is real-path-resolved instead, and the remaining
 * segments are appended to it.
 *
 * <p>Known and accepted residual: resolve-then-open is not atomic, so a local attacker who can
 * create symlinks inside the root during the window can defeat this. That grants them nothing they
 * did not already have — see docs/threat-model.md.
 */
public final class WorkspacePaths {
  private WorkspacePaths() {}

  /** Resolves a path that must already exist, confined to {@code root}. */
  public static Path resolveExisting(Path root, String candidate) throws PathOutsideRootException {
    Path realRoot = realRoot(root);
    Path resolved = realRoot.resolve(candidate).normalize();
    Path real;
    try {
      real = resolved.toRealPath();
    } catch (IOException error) {
      throw new PathOutsideRootException(candidate, "cannot be resolved (" + error.getMessage() + ")");
    }
    return confine(realRoot, real, candidate);
  }

  /** Resolves a path that need not exist yet (an output directory), confined to {@code root}. */
  public static Path resolveForWrite(Path root, String candidate) throws PathOutsideRootException {
    Path realRoot = realRoot(root);
    Path resolved = realRoot.resolve(candidate).normalize();

    Path existing = resolved;
    while (existing != null && !existing.toFile().exists()) {
      existing = existing.getParent();
    }
    if (existing == null) {
      throw new PathOutsideRootException(candidate, "has no existing ancestor to anchor");
    }
    Path realExisting;
    try {
      realExisting = existing.toRealPath();
    } catch (IOException error) {
      throw new PathOutsideRootException(candidate, "cannot be resolved (" + error.getMessage() + ")");
    }
    confine(realRoot, realExisting, candidate);

    Path remainder = existing.relativize(resolved);
    Path target = realExisting.resolve(remainder).normalize();
    return confine(realRoot, target, candidate);
  }

  private static Path realRoot(Path root) throws PathOutsideRootException {
    try {
      return root.toRealPath();
    } catch (IOException error) {
      throw new PathOutsideRootException(
          root.toString(), "workspace root cannot be resolved (" + error.getMessage() + ")");
    }
  }

  private static Path confine(Path realRoot, Path target, String candidate)
      throws PathOutsideRootException {
    if (!target.startsWith(realRoot)) {
      throw new PathOutsideRootException(candidate, "resolves to " + target);
    }
    return target;
  }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw -pl mcp -am test -Dtest=WorkspacePathsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — 8 tests.

- [ ] **Step 8: Verify the dependency pins hold**

Run: `./mvnw -pl mcp -am verify -DskipTests`
Expected: BUILD SUCCESS. If Enforcer reports a `dependencyConvergence` failure, add the offending coordinate to the root `dependencyManagement` — do not disable the rule.

- [ ] **Step 9: Format, then commit**

```bash
./mvnw -Pquality spotless:apply
git add pom.xml mcp/pom.xml contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java mcp/src/main/java/dev/dediren/mcp mcp/src/test/java/dev/dediren/mcp
git commit -m "feat(mcp): scaffold the mcp module and confine tool paths to a workspace root"
```

---

### Task 2: The sectioned guide catalog

**Files:**
- Modify: `mcp/pom.xml` (add the `maven-resources-plugin` copy)
- Create: `mcp/src/main/java/dev/dediren/mcp/GuideCatalog.java`
- Test: `mcp/src/test/java/dev/dediren/mcp/GuideCatalogTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `GuideCatalog.topics()` → `List<String>` (sorted topic ids); `GuideCatalog.index()` → `String` (markdown list of topics); `GuideCatalog.section(String topic)` → `String` (the section markdown, or an "unknown topic" message listing valid topics); `GuideCatalog.headings()` → `List<String>` (every `##` heading in the bundled guide).

- [ ] **Step 1: Copy the guide into the jar at build time**

In `mcp/pom.xml`, add a `<build>` block before `</project>`:

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-resources-plugin</artifactId>
        <executions>
          <execution>
            <!-- The guide ships inside the jar rather than being read from the product root at
                 runtime: it removes a filesystem read and the DEDIREN_PRODUCT_ROOT_UNRESOLVED
                 failure lane from the guide tool, and copying at build time means the shipped
                 guide cannot drift from docs/agent-usage.md. -->
            <id>copy-agent-usage-guide</id>
            <phase>generate-resources</phase>
            <goals>
              <goal>copy-resources</goal>
            </goals>
            <configuration>
              <outputDirectory>${project.build.outputDirectory}/dediren</outputDirectory>
              <resources>
                <resource>
                  <directory>${maven.multiModuleProjectDirectory}/docs</directory>
                  <includes>
                    <include>agent-usage.md</include>
                  </includes>
                </resource>
              </resources>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

- [ ] **Step 2: Write the failing test**

Create `mcp/src/test/java/dev/dediren/mcp/GuideCatalogTest.java`:

```java
package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuideCatalogTest {

  @Test
  void indexListsEveryTopic() {
    String index = GuideCatalog.index();

    assertThat(GuideCatalog.topics()).isNotEmpty();
    for (String topic : GuideCatalog.topics()) {
      assertThat(index).contains(topic);
    }
  }

  @Test
  void sectionReturnsTheRequestedSection() {
    String section = GuideCatalog.section("render-policy");

    assertThat(section).contains("Render Policy Options");
    assertThat(section).doesNotContain("## Repair Rules");
  }

  @Test
  void unknownTopicListsTheValidTopics() {
    String section = GuideCatalog.section("no-such-topic");

    assertThat(section).contains("unknown topic 'no-such-topic'");
    assertThat(section).contains("render-policy");
  }

  @Test
  void everyTopicResolvesToARealHeading() {
    List<String> headings = GuideCatalog.headings();

    for (String topic : GuideCatalog.topics()) {
      assertThat(GuideCatalog.section(topic))
          .as("topic '%s' must resolve to a real section", topic)
          .doesNotContain("unknown topic");
    }
    assertThat(headings).isNotEmpty();
  }

  @Test
  void everyHeadingIsReachableFromSomeTopic() {
    List<String> covered =
        GuideCatalog.topics().stream().map(GuideCatalog::headingFor).toList();

    assertThat(covered)
        .as(
            "every ## heading in docs/agent-usage.md must be reachable from at least one guide"
                + " topic — add a topic to GuideCatalog.TOPICS when you add a section")
        .containsAll(GuideCatalog.headings());
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -pl mcp -am test -Dtest=GuideCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `GuideCatalog` does not exist.

- [ ] **Step 4: Write the implementation**

Create `mcp/src/main/java/dev/dediren/mcp/GuideCatalog.java`:

```java
package dev.dediren.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves docs/agent-usage.md to agents one section at a time.
 *
 * <p>The guide is 800+ lines (~12-15k tokens). Returning it whole on every call would defeat the
 * token efficiency the document is explicitly written for, so the tool is a progressive-disclosure
 * surface: the agent asks for the topic it needs.
 *
 * <p>{@code TOPICS} is curated rather than derived from the headings, so topic ids stay stable and
 * agent-facing even when a heading is reworded. {@code GuideCatalogTest} pins the map against the
 * document in both directions: every topic must resolve to a real heading, and every heading must
 * be reachable from some topic — so a newly added section cannot go silently unreachable.
 */
public final class GuideCatalog {
  private static final String RESOURCE = "/dediren/agent-usage.md";

  private static final Map<String, String> TOPICS = topicMap();

  private static final Map<String, String> SECTIONS = loadSections();

  private GuideCatalog() {}

  private static Map<String, String> topicMap() {
    Map<String, String> topics = new LinkedHashMap<>();
    topics.put("fast-path", "Fast Path");
    topics.put("artifacts", "Artifact Map");
    topics.put("source-json", "Minimal Source JSON");
    topics.put("profiles", "Semantic Profiles");
    topics.put("archimate", "ArchiMate Handoff");
    topics.put("commands", "Command Handoff");
    topics.put("build", "Build");
    topics.put("render-policy", "Render Policy Options");
    topics.put("uml-sequence", "UML Sequence Handoff");
    topics.put("uml-state-machine", "UML State Machine Handoff");
    topics.put("uml-use-case", "UML Use Case Handoff");
    topics.put("uml-component", "UML Component Handoff");
    topics.put("uml-deployment", "UML Deployment Handoff");
    topics.put("runtime-probes", "Runtime Probes");
    topics.put("smoke", "Bundle Smoke Workflow");
    topics.put("export", "Export");
    topics.put("repair", "Repair Rules");
    topics.put("environment", "Plugin Environment");
    return Map.copyOf(topics);
  }

  /** The heading this topic maps to. */
  public static String headingFor(String topic) {
    return TOPICS.get(topic);
  }

  /** Every topic id, in declaration order. */
  public static List<String> topics() {
    return List.copyOf(TOPICS.keySet());
  }

  /** Every {@code ##} heading in the bundled guide, in document order. */
  public static List<String> headings() {
    return List.copyOf(SECTIONS.keySet());
  }

  /** A short markdown index of the available topics. */
  public static String index() {
    StringBuilder out = new StringBuilder();
    out.append("# Dediren agent guide — topics\n\n");
    out.append("Call `dediren_guide` again with one of these `topic` values:\n\n");
    TOPICS.forEach((topic, heading) -> out.append("- `").append(topic).append("` — ").append(heading).append('\n'));
    return out.toString();
  }

  /** The markdown for one topic, or an "unknown topic" message naming the valid topics. */
  public static String section(String topic) {
    String heading = TOPICS.get(topic);
    if (heading == null) {
      return "unknown topic '" + topic + "'. Valid topics: " + String.join(", ", topics());
    }
    String body = SECTIONS.get(heading);
    if (body == null) {
      return "unknown topic '" + topic + "'. Valid topics: " + String.join(", ", topics());
    }
    return body;
  }

  private static Map<String, String> loadSections() {
    String guide = read();
    Map<String, String> sections = new LinkedHashMap<>();
    String heading = null;
    List<String> body = new ArrayList<>();
    for (String line : guide.split("\n", -1)) {
      if (line.startsWith("## ")) {
        if (heading != null) {
          sections.put(heading, String.join("\n", body));
        }
        heading = line.substring(3).strip();
        body = new ArrayList<>();
        body.add(line);
      } else if (heading != null) {
        body.add(line);
      }
    }
    if (heading != null) {
      sections.put(heading, String.join("\n", body));
    }
    return Map.copyOf(sections);
  }

  private static String read() {
    try (InputStream stream = GuideCatalog.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException(
            "bundled guide " + RESOURCE + " is missing — the maven-resources-plugin copy of"
                + " docs/agent-usage.md did not run");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl mcp -am test -Dtest=GuideCatalogTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — 5 tests.

If `everyHeadingIsReachableFromSomeTopic` fails, the heading list in `docs/agent-usage.md` differs from the `TOPICS` values above. Run `grep -n '^## ' docs/agent-usage.md` and reconcile `TOPICS` against the actual headings — the test is the source of truth, not this plan.

- [ ] **Step 6: Format, then commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp/pom.xml mcp/src/main/java/dev/dediren/mcp/GuideCatalog.java mcp/src/test/java/dev/dediren/mcp/GuideCatalogTest.java
git commit -m "feat(mcp): serve the agent guide by topic from a bundled copy"
```

---

### Task 3: The three tool handlers

**Files:**
- Create: `mcp/src/main/java/dev/dediren/mcp/ToolSchemas.java`
- Create: `mcp/src/main/java/dev/dediren/mcp/DedirenTools.java`
- Test: `mcp/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`

**Interfaces:**
- Consumes: `WorkspacePaths.resolveExisting` / `resolveForWrite`, `PathOutsideRootException`, `DiagnosticCode.MCP_PATH_OUTSIDE_ROOT` (Task 1); `GuideCatalog.index` / `section` (Task 2).
- Produces: `new DedirenTools(Path root, Engines engines, Map<String,String> env)`; `DedirenTools.validate(CallToolRequest)` → `CallToolResult`; `DedirenTools.build(CallToolRequest)` → `CallToolResult`; `DedirenTools.guide(CallToolRequest)` → `CallToolResult`. `ToolSchemas.VALIDATE`, `ToolSchemas.BUILD`, `ToolSchemas.GUIDE` (JSON schema strings).

- [ ] **Step 1: Write the failing test**

Create `mcp/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`:

```java
package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DedirenToolsTest {

  /** The checked-in fixtures are the authority on the source shape — never hand-roll one. */
  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static JsonNode envelopeOf(CallToolResult result) {
    return JsonSupport.objectMapper().readTree(textOf(result));
  }

  /**
   * An empty registry. None of these cases reaches an engine: schema validation is engine-free
   * (SourceValidator), the guide never touches core, and the path-escape and missing-argument cases
   * fail before dispatch. Real-engine coverage lives in CliMcpParityTest (Task 6), which is in cli
   * because only cli may construct engines — and mcp must not depend on cli, which depends on mcp.
   */
  private static Engines noEngines() {
    return Engines.of(List.of(), List.of(), List.of(), List.of());
  }

  private DedirenTools toolsIn(Path root) {
    return new DedirenTools(root, noEngines(), Map.of());
  }

  @Test
  void guideWithoutTopicReturnsTheIndex() {
    CallToolResult result =
        toolsIn(Path.of(".")).guide(new CallToolRequest("dediren_guide", Map.of()));

    assertThat(textOf(result)).contains("topics");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  @Test
  void guideWithTopicReturnsThatSection() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "render-policy")));

    assertThat(textOf(result)).contains("Render Policy Options");
  }

  @Test
  void validateReturnsTheEnvelopeVerbatim(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root).validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("ok");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  @Test
  void validateFlagsAnErrorEnvelopeAsIsError(@TempDir Path root) throws Exception {
    Files.copy(fixture("invalid-duplicate-id.json"), root.resolve("broken.json"));

    CallToolResult result =
        toolsIn(root).validate(new CallToolRequest("dediren_validate", Map.of("source", "broken.json")));

    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("error");
    assertThat(result.isError()).isTrue();
  }

  @Test
  void validateRejectsASourceOutsideTheRoot(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "../../etc/passwd")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  @Test
  void buildRejectsAnOutDirOutsideTheRoot(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build", Map.of("source", "model.json", "out", "../escape")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  @Test
  void buildRequiresASource(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root).build(new CallToolRequest("dediren_build", Map.of("out", "out")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }
}
```

**Do not add a `cli` dependency to `mcp/pom.xml`, in any scope.** `cli` compile-depends on `mcp` (Task 5), so an `mcp → cli` test edge is a reactor cycle and Maven will refuse to build it. That is why these tests use an empty `Engines` registry: none of them reaches an engine. Real-engine coverage is `CliMcpParityTest` (Task 6), which lives in `cli` precisely because only `cli` may construct engines.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl mcp -am test -Dtest=DedirenToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `DedirenTools` / `ToolSchemas` do not exist.

- [ ] **Step 3: Write the tool schemas**

Create `mcp/src/main/java/dev/dediren/mcp/ToolSchemas.java`:

```java
package dev.dediren.mcp;

/** The JSON input schemas advertised for the three tools. */
final class ToolSchemas {
  private ToolSchemas() {}

  static final String VALIDATE =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to the source JSON, relative to the workspace root."
          },
          "profile": {
            "type": "string",
            "description": "Optional semantic profile (for example 'archimate' or 'uml'). When set, runs semantic profile validation in addition to schema validation."
          }
        },
        "required": ["source"]
      }
      """;

  static final String BUILD =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to the source JSON, relative to the workspace root."
          },
          "out": {
            "type": "string",
            "description": "Output directory for the generated artifacts, relative to the workspace root."
          },
          "views": {
            "type": "array",
            "items": {"type": "string"},
            "description": "View ids to build. Omit to build every view in model order."
          },
          "render_policy": {"type": "string", "description": "Path to a render policy JSON. Selects the SVG lane."},
          "oef_policy": {"type": "string", "description": "Path to an OEF export policy JSON. Selects the ArchiMate OEF lane."},
          "xmi_policy": {"type": "string", "description": "Path to a UML XMI export policy JSON. Selects the UML XMI lane."},
          "emit": {
            "type": "array",
            "items": {"type": "string", "enum": ["layout-request", "layout-result", "render-metadata"]},
            "description": "Optional stage envelopes to also write under 'out', for debugging."
          }
        },
        "required": ["source", "out"]
      }
      """;

  static final String GUIDE =
      """
      {
        "type": "object",
        "properties": {
          "topic": {
            "type": "string",
            "description": "Guide topic to fetch. Omit to get the index of available topics."
          }
        }
      }
      """;
}
```

- [ ] **Step 4: Write the handlers**

Create `mcp/src/main/java/dev/dediren/mcp/DedirenTools.java`:

```java
package dev.dediren.mcp;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.ProductRootException;
import dev.dediren.core.commands.BuildCommand;
import dev.dediren.core.commands.BuildRequest;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three tool handlers.
 *
 * <p>Each is a thin shell: confine the model-supplied paths, call the same {@code core} entry point
 * the CLI calls, and hand the resulting envelope JSON back verbatim as the tool result's text. The
 * envelope is already the agent contract — "decide success or failure from the JSON alone" — so the
 * MCP layer adds no second result format, only MCP's native {@code isError} flag on top.
 */
public final class DedirenTools {
  /** The semantics engine's wire id. A public contract string, like a schema id — not a class. */
  private static final String SEMANTICS_ENGINE = "generic-graph";

  private final Path root;
  private final Engines engines;
  private final Map<String, String> env;

  public DedirenTools(Path root, Engines engines, Map<String, String> env) {
    this.root = root;
    this.engines = engines;
    this.env = Map.copyOf(env);
  }

  public CallToolResult guide(CallToolRequest request) {
    String topic = stringArg(request, "topic");
    String body = topic == null ? GuideCatalog.index() : GuideCatalog.section(topic);
    return CallToolResult.builder().addTextContent(body).isError(false).build();
  }

  public CallToolResult validate(CallToolRequest request) {
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "validate requires 'source'", null);
    }
    Path sourcePath;
    try {
      sourcePath = WorkspacePaths.resolveExisting(root, source);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    String text;
    try {
      text = Files.readString(sourcePath);
    } catch (IOException error) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID, "failed to read source: " + error.getMessage(), source);
    }
    Path baseDir = sourcePath.getParent();

    String profile = stringArg(request, "profile");
    try {
      if (profile != null) {
        EngineRunOutcome outcome =
            CoreCommands.semanticValidateCommand(
                SEMANTICS_ENGINE, profile, text, baseDir, env, engines);
        return envelope(outcome.stdout(), outcome.exitCode() != 0);
      }
      ValidationResult result = SourceValidator.validateSourceJson(text, baseDir);
      return envelope(serialize(result.envelope()), result.exitCode() != 0);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, failure.getMessage(), source);
    }
  }

  public CallToolResult build(CallToolRequest request) {
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "build requires 'source'", null);
    }
    String out = stringArg(request, "out");
    if (out == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "build requires 'out'", null);
    }

    Path sourcePath;
    Path outPath;
    String renderPolicy;
    String oefPolicy;
    String xmiPolicy;
    try {
      sourcePath = WorkspacePaths.resolveExisting(root, source);
      outPath = WorkspacePaths.resolveForWrite(root, out);
      renderPolicy = readOptionalPolicy(request, "render_policy");
      oefPolicy = readOptionalPolicy(request, "oef_policy");
      xmiPolicy = readOptionalPolicy(request, "xmi_policy");
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    } catch (IOException error) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID, "failed to read policy: " + error.getMessage(), null);
    }

    String sourceText;
    try {
      sourceText = Files.readString(sourcePath);
    } catch (IOException error) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID, "failed to read source: " + error.getMessage(), source);
    }

    BuildRequest buildRequest =
        new BuildRequest(
            sourceText,
            sourcePath.getParent(),
            stringListArg(request, "views"),
            renderPolicy,
            oefPolicy,
            xmiPolicy,
            Set.copyOf(stringListArg(request, "emit")),
            outPath,
            env);
    try {
      EngineRunOutcome outcome = BuildCommand.run(buildRequest, engines);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return error(DiagnosticCode.COMMAND_IO_FAILED, failure.getMessage(), null);
    }
  }

  private String readOptionalPolicy(CallToolRequest request, String argument)
      throws PathOutsideRootException, IOException {
    String value = stringArg(request, argument);
    if (value == null) {
      return null;
    }
    return Files.readString(WorkspacePaths.resolveExisting(root, value));
  }

  private static String stringArg(CallToolRequest request, String name) {
    Object value = request.arguments().get(name);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  private static List<String> stringListArg(CallToolRequest request, String name) {
    Object value = request.arguments().get(name);
    if (!(value instanceof List<?> raw)) {
      return List.of();
    }
    Set<String> items = new LinkedHashSet<>();
    for (Object item : raw) {
      if (item instanceof String text && !text.isBlank()) {
        items.add(text);
      }
    }
    return List.copyOf(items);
  }

  private static CallToolResult envelope(String json, boolean isError) {
    return CallToolResult.builder().addTextContent(json).isError(isError).build();
  }

  private static CallToolResult pathEscape(PathOutsideRootException escape) {
    return error(DiagnosticCode.MCP_PATH_OUTSIDE_ROOT, escape.getMessage(), escape.candidate());
  }

  private static CallToolResult engineFailure(EngineExecutionException failure) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    diagnostics.add(failure.diagnostic());
    return envelope(serialize(CommandEnvelope.error(diagnostics)), true);
  }

  private static CallToolResult error(DiagnosticCode code, String message, String path) {
    return envelope(
        serialize(
            CommandEnvelope.error(
                List.of(new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, path)))),
        true);
  }

  private static String serialize(Object envelope) {
    return JsonSupport.objectMapper().writeValueAsString(envelope);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl mcp -am test -Dtest=DedirenToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — 7 tests.

- [ ] **Step 6: Format, then commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp/pom.xml mcp/src/main/java/dev/dediren/mcp/ToolSchemas.java mcp/src/main/java/dev/dediren/mcp/DedirenTools.java mcp/src/test/java/dev/dediren/mcp/DedirenToolsTest.java
git commit -m "feat(mcp): add the validate, build, and guide tool handlers"
```

---

### Task 4: Server assembly, read-only mode, and stdout integrity

**Files:**
- Create: `mcp/src/main/java/dev/dediren/mcp/StdoutIntegrity.java`
- Create: `mcp/src/main/java/dev/dediren/mcp/DedirenMcpServer.java`
- Test: `mcp/src/test/java/dev/dediren/mcp/DedirenMcpServerTest.java`

**Interfaces:**
- Consumes: `DedirenTools` (Task 3), `ToolSchemas` (Task 3).
- Produces: `DedirenMcpServer.create(Path root, Engines engines, Map<String,String> env, boolean readOnly, InputStream in, OutputStream out)` → `McpSyncServer`; `DedirenMcpServer.serve(Path root, Engines engines, Map<String,String> env, boolean readOnly)` → `void` (blocks until stdin closes); `StdoutIntegrity.claimStdout()` → `OutputStream`.

- [ ] **Step 1: Write the failing test**

Create `mcp/src/test/java/dev/dediren/mcp/DedirenMcpServerTest.java`:

```java
package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DedirenMcpServerTest {

  /** Tool registration is engine-independent; mcp must not depend on cli to get a real registry. */
  private McpSyncServer serverIn(Path root, boolean readOnly) {
    return DedirenMcpServer.create(
        root,
        Engines.of(List.of(), List.of(), List.of(), List.of()),
        Map.of(),
        readOnly,
        new ByteArrayInputStream(new byte[0]),
        new ByteArrayOutputStream());
  }

  @Test
  void registersAllThreeToolsByDefault(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names)
          .containsExactlyInAnyOrder("dediren_validate", "dediren_build", "dediren_guide");
    } finally {
      server.close();
    }
  }

  @Test
  void readOnlyModeOmitsTheBuildTool(@TempDir Path root) {
    McpSyncServer server = serverIn(root, true);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names).containsExactlyInAnyOrder("dediren_validate", "dediren_guide");
      assertThat(names).doesNotContain("dediren_build");
    } finally {
      server.close();
    }
  }

  @Test
  void everyToolAdvertisesAnInputSchema(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      for (Tool tool : server.listTools()) {
        assertThat(tool.inputSchema()).as("tool %s must advertise an input schema", tool.name()).isNotNull();
        assertThat(tool.description()).as("tool %s must have a description", tool.name()).isNotBlank();
      }
    } finally {
      server.close();
    }
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl mcp -am test -Dtest=DedirenMcpServerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `DedirenMcpServer` does not exist.

- [ ] **Step 3: Write the stdout-integrity control**

Create `mcp/src/main/java/dev/dediren/mcp/StdoutIntegrity.java`:

```java
package dev.dediren.mcp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Claims the process's real stdout for the JSON-RPC channel and makes stray prints harmless.
 *
 * <p>In stdio MCP, stdout <em>is</em> the protocol. One {@code System.out.println} anywhere in
 * core, an engine, or a transitive dependency corrupts a frame, and the failure mode is the client
 * silently going dark — no error surfaces, the tools simply stop working. Today's SLF4J and CDS
 * notices happen to go to stderr, but that is luck, not a guarantee, and it will not survive the
 * next dependency.
 *
 * <p>So: take the real file descriptor for the transport, then point {@code System.out} at stderr.
 * A stray print then degrades to log noise instead of protocol corruption. The dist-smoke test
 * ({@code DistTool.assertMcpStdoutIsProtocolOnly}) is this control's gate — it is the only place
 * the real process streams are observable.
 */
public final class StdoutIntegrity {
  private StdoutIntegrity() {}

  /** Returns the real stdout for the transport, and redirects {@code System.out} to stderr. */
  public static OutputStream claimStdout() {
    OutputStream protocolChannel = new FileOutputStream(FileDescriptor.out);
    System.setOut(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    return protocolChannel;
  }
}
```

- [ ] **Step 4: Write the server assembly**

Create `mcp/src/main/java/dev/dediren/mcp/DedirenMcpServer.java`:

```java
package dev.dediren.mcp;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assembles the {@code dediren mcp} stdio server.
 *
 * <p>The server is spawned by an MCP client, which owns its lifetime and reaps it — there is no
 * daemon here: no port, no PID file, no idle timeout, no concurrent-client arbitration. That is
 * why this needs no lifecycle design of its own.
 *
 * <p>Under {@code readOnly}, {@code dediren_build} is not registered at all. An absent tool is a
 * better contract than a tool that exists and refuses: the model never sees a capability it cannot
 * use, and it costs nothing in the client's context window.
 */
public final class DedirenMcpServer {
  private static final String SERVER_NAME = "dediren";

  private DedirenMcpServer() {}

  /** Builds the server over the supplied streams. Used by {@code serve} and by tests. */
  public static McpSyncServer create(
      Path root,
      Engines engines,
      Map<String, String> env,
      boolean readOnly,
      InputStream in,
      OutputStream out) {
    // The protocol mapper serializes MCP frames, not Dediren envelopes: envelopes are produced by
    // core and passed through as text, so this mapper never touches the product contract.
    McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, in, out);
    DedirenTools tools = new DedirenTools(root, engines, env);

    var specification =
        McpServer.sync(transport)
            .serverInfo(SERVER_NAME, System.getProperty("dediren.version", "unknown"))
            .capabilities(ServerCapabilities.builder().tools(false).build())
            .toolCall(
                Tool.builder()
                    .name("dediren_validate")
                    .description(
                        "Validate a Dediren source JSON model. Returns the validation envelope:"
                            + " status 'ok' means the model is legal, status 'error' carries the"
                            + " diagnostics to repair. Call dediren_guide with topic 'repair' for"
                            + " the repair rules.")
                    .inputSchema(mapper, ToolSchemas.VALIDATE)
                    .build(),
                (exchange, request) -> tools.validate(request))
            .toolCall(
                Tool.builder()
                    .name("dediren_guide")
                    .description(
                        "Fetch a section of the Dediren agent authoring guide by topic. Omit"
                            + " 'topic' to list the available topics. Start here when authoring a"
                            + " model: topic 'source-json' is the minimal source shape.")
                    .inputSchema(mapper, ToolSchemas.GUIDE)
                    .build(),
                (exchange, request) -> tools.guide(request));

    if (!readOnly) {
      specification =
          specification.toolCall(
              Tool.builder()
                  .name("dediren_build")
                  .description(
                      "Compile a Dediren source model into artifacts (SVG render, ArchiMate OEF,"
                          + " and/or UML XMI) under an output directory. Select a lane by passing"
                          + " its policy: render_policy, oef_policy, xmi_policy. Returns the"
                          + " build-result envelope, which names every artifact written.")
                  .inputSchema(mapper, ToolSchemas.BUILD)
                  .build(),
              (exchange, request) -> tools.build(request));
    }
    return specification.build();
  }

  /** Runs the server on the process's real stdio, blocking until the client closes stdin. */
  public static void serve(Path root, Engines engines, Map<String, String> env, boolean readOnly)
      throws InterruptedException {
    OutputStream protocolChannel = StdoutIntegrity.claimStdout();
    McpSyncServer server = create(root, engines, env, readOnly, System.in, protocolChannel);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    // The stdio transport reads System.in on its own thread and completes when the client closes
    // it. Park until the JVM is torn down by that completion or by the shutdown hook.
    Thread.currentThread().join();
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl mcp -am test -Dtest=DedirenMcpServerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — 3 tests.

- [ ] **Step 6: Run the whole module suite**

Run: `./mvnw -pl mcp -am test`
Expected: PASS — all of `WorkspacePathsTest`, `GuideCatalogTest`, `DedirenToolsTest`, `DedirenMcpServerTest`.

- [ ] **Step 7: Format, then commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp/src/main/java/dev/dediren/mcp/StdoutIntegrity.java mcp/src/main/java/dev/dediren/mcp/DedirenMcpServer.java mcp/src/test/java/dev/dediren/mcp/DedirenMcpServerTest.java
git commit -m "feat(mcp): assemble the stdio server, read-only mode, and the stdout-integrity control"
```

---

### Task 5: Wire `dediren mcp` into the CLI, and pin the new edges in ArchUnit

**Files:**
- Modify: `cli/pom.xml` (add the `mcp` dependency)
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java`
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java`
- Test: `cli/src/test/java/dev/dediren/cli/McpCommandTest.java`

**Interfaces:**
- Consumes: `DedirenMcpServer.serve` (Task 4), `EngineWiring.defaults()` (existing).
- Produces: the `dediren mcp` subcommand.

- [ ] **Step 1: Add the dependency**

In `cli/pom.xml`, add alongside the other `dev.dediren` dependencies:

```xml
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>mcp</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

Create `cli/src/test/java/dev/dediren/cli/McpCommandTest.java`:

```java
package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpCommandTest {

  @Test
  void mcpIsAdvertisedInTheTopLevelHelp() {
    CliResult result = Main.executeForTesting(new String[] {"--help"}, "");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("mcp");
  }

  @Test
  void mcpHelpDocumentsRootAndReadOnly() {
    CliResult result = Main.executeForTesting(new String[] {"mcp", "--help"}, "");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("--root");
    assertThat(result.stdout()).contains("--read-only");
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -pl cli -am test -Dtest=McpCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `mcpHelpDocumentsRootAndReadOnly` fails; picocli reports `Unmatched argument: 'mcp'`.

- [ ] **Step 4: Add the subcommand**

In `cli/src/main/java/dev/dediren/cli/Main.java`, register the subcommand inside `commandLine(...)`, after the `build` line:

```java
    commandLine.addSubcommand("mcp", new McpCommand(env, engines));
```

Then add this nested class alongside the other command classes (for example after `BuildCommand`):

```java
  @Command(
      name = "mcp",
      description =
          "Run the Model Context Protocol stdio server, exposing validate, build, and the agent"
              + " guide as tools. The MCP client spawns and owns this process; stdout carries"
              + " JSON-RPC only.")
  static final class McpCommand implements Callable<Integer> {
    private final Map<String, String> env;
    private final Engines engines;

    @Option(
        names = "--root",
        description =
            "Workspace root. Every tool path must resolve inside it. Defaults to the working"
                + " directory.")
    private Path root = Path.of(".");

    @Option(
        names = "--read-only",
        description = "Do not register the build tool; serve only validate and the guide.")
    private boolean readOnly;

    McpCommand(Map<String, String> env, Engines engines) {
      this.env = env;
      this.engines = engines;
    }

    @Override
    public Integer call() throws Exception {
      dev.dediren.mcp.DedirenMcpServer.serve(root, engines, env, readOnly);
      return CommandExitCode.OK.code();
    }
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl cli -am test -Dtest=McpCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — 2 tests.

- [ ] **Step 6: Pin the new module's edges in ArchUnit**

In `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java`, add the package constant alongside the existing `CLI` constant:

```java
  private static final String MCP = "dev.dediren.mcp..";
```

Add these two tests:

```java
  @Test
  void mcpDependsOnNoEngineImplementationAndNoCli() {
    // §2: mcp is a tier-3 protocol adapter over contracts/core/engine-api. It receives an Engines
    // registry through its constructor and must never name an engine implementation — that edge
    // stays confined to cli's EngineWiring. It must also not depend on cli, which depends on it.
    noClasses()
        .that()
        .resideInAPackage(MCP)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("dev.dediren.plugins..", "dev.dediren.semantics..", CLI)
        .because(
            "mcp is an adapter over the engine-api seam: it takes an Engines registry from cli's"
                + " EngineWiring and never constructs or names an engine, and cli depends on mcp"
                + " so the reverse edge would be a cycle")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void mcpDependsOnNoNotationCore() {
    // §3 thin-adapter charter, mirroring cliDependsOnNoNotationOrUtilityCore: notation semantics
    // belong in the semantics-* front ends, never in a protocol adapter.
    noClasses()
        .that()
        .resideInAPackage(MCP)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("dev.dediren.archimate..", "dev.dediren.uml..")
        .because("mcp marshals tool calls into core commands; notation logic belongs in semantics-*")
        .check(PRODUCTION_CLASSES);
  }
```

`PRODUCTION_CLASSES` is the existing `JavaClasses` constant (`ArchitectureRulesTest.java:51`, importing `dev.dediren..`), and `noClasses` is already statically imported — no new imports needed.

- [ ] **Step 7: Run the architecture rules**

Run: `./mvnw -pl dist-tool -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — including the two new rules.

- [ ] **Step 8: Format, then commit**

```bash
./mvnw -Pquality spotless:apply
git add cli/pom.xml cli/src/main/java/dev/dediren/cli/Main.java cli/src/test/java/dev/dediren/cli/McpCommandTest.java dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java
git commit -m "feat(cli): add the dediren mcp subcommand and pin the mcp module's edges"
```

---

### Task 6: The CLI/MCP parity test

This is the load-bearing test of the whole design: it is what stops the MCP surface quietly becoming a second, subtly different product.

**Files:**
- Test: `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java`

**Interfaces:**
- Consumes: `Main.executeForTesting` (existing), `DedirenTools` (Task 3).

- [ ] **Step 1: Write the failing test**

Create `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java`:

```java
package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.mcp.DedirenTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MCP surface must not drift from the CLI. Same inputs, same envelope — byte for byte.
 *
 * <p>Both lanes call the same core entry points, so a divergence here means the MCP layer has
 * started interpreting, reformatting, or re-deriving something it should be passing through.
 */
class CliMcpParityTest {

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static Path policy(String name) {
    return Path.of("..", "fixtures", "render-policy", name).toAbsolutePath().normalize();
  }

  @Test
  void validateProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);

    CliResult cli =
        Main.executeForTesting(new String[] {"validate", "--input", source.toString()}, "");

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }

  @Test
  void validateProducesTheSameErrorEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("invalid-duplicate-id.json"), source);

    CliResult cli =
        Main.executeForTesting(new String[] {"validate", "--input", source.toString()}, "");

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isTrue();
  }

  @Test
  void buildProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input", source.toString(),
              "--out", cliOut.toString(),
              "--render-policy", renderPolicy.toString()
            },
            "");

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json")));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(mcpOut.resolve("main")).isDirectory();
  }

  /** The envelope names the artifacts it wrote, so the out dir differs between the two lanes. */
  private static String normalizePaths(String envelope, Path out) {
    return envelope.replace(out.toString(), "<OUT>").strip();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails or passes**

Run: `./mvnw -pl cli -am test -Dtest=CliMcpParityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS if the handlers pass envelopes through faithfully. If it FAILS, the diff is the finding — the MCP lane is transforming something it should be passing through. Fix `DedirenTools`, not the test.

- [ ] **Step 3: Commit**

```bash
./mvnw -Pquality spotless:apply
git add cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java
git commit -m "test(cli,mcp): pin the MCP surface to the CLI's envelopes"
```

---

### Task 7: dist-smoke — real JSON-RPC over the packaged launcher

Unit tests cannot catch a broken classpath in the shipped bundle, nor observe the real process streams. This is the gate for both the protocol wiring and the stdout-integrity control.

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`

**Interfaces:**
- Consumes: `runBundleCommand(Path executable, Path bundle, List<String> args, Path stdin)` (existing private helper), `assertContains` (existing).

- [ ] **Step 1: Add the smoke assertion**

In `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`, add this method next to `assertFirstLaunchStdoutClean`:

```java
  /**
   * Drives the packaged MCP server over real stdio with real JSON-RPC.
   *
   * <p>Two things are only observable here. First, the protocol actually working through the
   * bundled classpath. Second — and this is the gate for the stdout-integrity control — that the
   * server's stdout carries protocol frames and <em>nothing else</em>: the JVM's CDS notices and
   * SLF4J's provider warning share this process, and if any of them (or a stray print from an
   * engine) reached stdout, the frame stream would be corrupt and a real client would silently go
   * dark.
   */
  private static void assertMcpServesToolsOverStdio(Path bundle, Path temp) throws Exception {
    Path dediren = bundle.resolve("bin/dediren");
    Path requests = temp.resolve("mcp-requests.jsonl");
    Files.writeString(
        requests,
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"dist-smoke","version":"1"}}}
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dediren_guide","arguments":{"topic":"source-json"}}}
        """,
        StandardCharsets.UTF_8);

    String stdout =
        runBundleCommand(
            dediren, bundle, List.of("mcp", "--root", bundle.toString()), requests);

    // Every non-blank stdout line must be a JSON-RPC frame. Nothing else may share this channel.
    for (String line : stdout.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode frame;
      try {
        frame = JsonSupport.objectMapper().readTree(line);
      } catch (RuntimeException notJson) {
        throw new IllegalStateException(
            "mcp stdout must carry JSON-RPC frames only; found a non-JSON line: " + line);
      }
      if (!"2.0".equals(frame.path("jsonrpc").asText())) {
        throw new IllegalStateException("mcp stdout line is not a JSON-RPC frame: " + line);
      }
    }

    assertContains(stdout, "\"serverInfo\"", "mcp initialize response");
    assertContains(stdout, "dediren_validate", "mcp tools/list response");
    assertContains(stdout, "dediren_build", "mcp tools/list response");
    assertContains(stdout, "dediren_guide", "mcp tools/list response");
    assertContains(stdout, "Minimal Source JSON", "mcp guide tool call response");
    System.out.println("mcp stdio smoke passed: 3 tools, protocol-only stdout");
  }
```

- [ ] **Step 2: Call it from the smoke run**

In `smoke(...)`, add the call after `assertBuildRendersAndExports(dediren, bundle, temp, oefSchemas);`:

```java
      assertMcpServesToolsOverStdio(bundle, temp);
```

- [ ] **Step 3: Build the bundle and run the smoke**

Run:
```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```
Expected: BUILD SUCCESS, with `mcp stdio smoke passed: 3 tools, protocol-only stdout` in the output.

If the server does not exit when stdin reaches EOF, the run will hang. The stdio transport completes on stdin close; if it does not, fix `DedirenMcpServer.serve` to exit when the transport closes rather than parking forever — do not add a timeout to the smoke test to paper over it.

- [ ] **Step 4: Commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "test(dist): drive the packaged MCP server over real stdio and pin stdout to protocol frames"
```

---

### Task 8: The documentation that moves together

**Files:**
- Modify: `docs/threat-model.md`
- Modify: `docs/architecture-guidelines.md`
- Modify: `docs/agent-usage.md`
- Modify: `README.md`
- Modify: `mcp/src/main/java/dev/dediren/mcp/GuideCatalog.java` (new topic for the new section)

- [ ] **Step 1: Add the trust boundary to the threat model**

In `docs/threat-model.md`, add a new `###` section under `## Trust Boundaries`, after `### Single-JVM engine runtime (no plugin execution surface)`:

```markdown
### MCP stdio server (`dediren mcp`)

`dediren mcp` (module `mcp`, launched by `cli`'s `McpCommand`) is a long-lived,
model-driven process holding a filesystem write primitive. It is the one boundary
where a *model* — not a human — chooses the paths, and MCP clients frequently
auto-approve tool calls, so the CLI's "a human typed this path" posture does not
transfer.

There is no network surface: stdio transport only, no port, no HTTP/SSE listener,
no multi-client daemon. The MCP client spawns the process and owns its lifetime,
so there is no daemon lifecycle to supervise.

Controls:

- **Workspace-root confinement.** Every tool path argument is resolved against the
  `--root` (default: cwd) and real-path-resolved *before* the containment check
  (`mcp/src/main/java/dev/dediren/mcp/WorkspacePaths.java`). Normalization alone is
  insufficient — a symlink inside the root pointing outside is the interesting case,
  and only `toRealPath()` catches it. For an output directory that need not exist,
  the nearest existing ancestor is resolved instead. An escaping path yields a
  `DEDIREN_MCP_PATH_OUTSIDE_ROOT` error envelope. Pinned by `WorkspacePathsTest`.
- **Read-only mode.** `--read-only` does not register `dediren_build` at all, so the
  write primitive is absent rather than present-and-refusing.
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
```

- [ ] **Step 2: Add the module to the architecture guidelines**

In `docs/architecture-guidelines.md` §2, add these rows to the allowed-edges table — `mcp` immediately before `cli`, and update the `cli` row:

```markdown
| `mcp` | `contracts`, `core`, `engine-api` | 3 — protocol adapter |
| `cli` | `contracts`, `core`, `engine-api`, `ir`, `mcp`; engine implementations **only in `EngineWiring`** | 3 — entrypoint + wiring |
```

Add a bullet to the rules beneath the table:

```markdown
- **`mcp` is an adapter over the engine seam, not an engine consumer.** It receives an
  `Engines` registry through its constructor from `cli`'s `EngineWiring` and never names
  an engine implementation, a notation core, or `cli`. ArchUnit pins both
  (`mcpDependsOnNoEngineImplementationAndNoCli`, `mcpDependsOnNoNotationCore`). The
  `cli → mcp` edge is the entrypoint wiring its adapter; the reverse would be a cycle.
```

In §3 (module responsibility charter), add an entry for `mcp`: the MCP stdio protocol surface — tool schemas, handlers, path confinement, and the guide topic map. It marshals tool calls into `core` commands and returns their envelopes verbatim; it owns no orchestration, no notation semantics, and no engine construction.

- [ ] **Step 3: Add the MCP section to the agent guide**

In `docs/agent-usage.md`, add a `## MCP Server` section (place it after `## Fast Path`):

```markdown
## MCP Server

`dediren mcp` runs an MCP stdio server so an agent can drive Dediren as tools
instead of shelling out. Register it once:

    claude mcp add dediren -- /path/to/bundle/bin/dediren mcp --root .

Three tools:

- `dediren_guide` — this document, one section at a time. Pass `topic`, or omit
  it to list the topics. Start with `topic: "source-json"`.
- `dediren_validate` — `source` (path). Returns the validation envelope.
- `dediren_build` — `source`, `out`, and at least one policy (`render_policy`,
  `oef_policy`, `xmi_policy`). Returns the build-result envelope, which names
  every artifact written.

Every tool path must resolve inside `--root` (default: the working directory).
A path that escapes it returns a `DEDIREN_MCP_PATH_OUTSIDE_ROOT` error envelope.
Launch with `--read-only` to serve only `dediren_validate` and `dediren_guide`.

Tool results carry the same envelope JSON the CLI prints on stdout, so the
handoff rules in `## Command Handoff` apply unchanged.
```

- [ ] **Step 4: Add the topic for the new section**

Adding a `##` heading to the guide **breaks `GuideCatalogTest.everyHeadingIsReachableFromSomeTopic` until a topic maps to it.** That is the guard working as designed, not a defect. In `GuideCatalog.topicMap()`, add:

```java
    topics.put("mcp", "MCP Server");
```

- [ ] **Step 5: Update the README**

In `README.md`, add a short subsection under the usage/commands area (keep it compact — the README is the human front door and defers detail to `docs/agent-usage.md`):

```markdown
### MCP server

Agents can drive Dediren as MCP tools instead of the CLI:

    claude mcp add dediren -- "$BUNDLE/bin/dediren" mcp --root .

This serves `dediren_validate`, `dediren_build`, and `dediren_guide` (the agent
guide, one section at a time) over stdio. Tool paths are confined to `--root`;
`--read-only` withholds the build tool. See `docs/agent-usage.md`.
```

- [ ] **Step 6: Verify the doc-consistency gates**

Run: `./mvnw -pl dist-tool,mcp -am test -Dtest='AgentUsageDocConsistencyTest+GuideCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `AgentUsageDocConsistencyTest` confirms `DEDIREN_MCP_PATH_OUTSIDE_ROOT` exists in source (added in Task 1); `GuideCatalogTest` confirms the new heading is reachable.

- [ ] **Step 7: Commit**

```bash
git add docs/threat-model.md docs/architecture-guidelines.md docs/agent-usage.md README.md mcp/src/main/java/dev/dediren/mcp/GuideCatalog.java
git commit -m "docs(mcp): document the MCP server, its trust boundary, and its module edges"
```

---

### Task 9: Full verification and audit gates

**Files:** none (verification only, plus any fixes the audits demand).

- [ ] **Step 1: Run the full reactor**

Run: `./mvnw test`
Expected: BUILD SUCCESS, all modules.

- [ ] **Step 2: Run the quality gate**

Run: `./mvnw -Pquality verify`
Expected: BUILD SUCCESS — Spotless clean, SpotBugs clean. If SpotBugs flags something in `mcp`, fix it; do not add a suppression without recording it as known debt in `docs/architecture-guidelines.md §12`.

- [ ] **Step 3: Run the distribution smoke**

Run: `./mvnw -pl dist-tool -am verify -Pdist-smoke`
Expected: BUILD SUCCESS, including the MCP stdio smoke.

- [ ] **Step 4: Run the deep test-quality audit**

Invoke `souroldgeezer-audit:test-quality-audit`, deep mode, scoped to the `mcp` module tests, `CliMcpParityTest`, `McpCommandTest`, and the new dist-smoke assertion.

Expected result: no block findings. Fix any block finding. Warn/info findings may remain only if explicitly accepted in the handoff, with the reason.

- [ ] **Step 5: Run the deep DevSecOps audit**

Invoke `souroldgeezer-audit:devsecops-audit`, **deep** mode. Focus: the new trust boundary (path confinement, read-only mode, stdout integrity, the accepted TOCTOU residual), the new transitive dependency tree (`io.modelcontextprotocol.sdk` 2.0.0 and `io.projectreactor:reactor-core` 3.7.0 — licences, CVEs, provenance), the SBOM's new entries, and the `dependencyManagement` pins.

Expected result: no block findings. Fix any block finding. Warn/info findings may remain only if explicitly accepted in the handoff, with the reason.

- [ ] **Step 6: Confirm the working tree is clean and report**

Run: `git status --short --branch`
Expected: clean (bar pre-existing untracked user files, which are not ours to touch).

Report in the handoff: the audit outcomes, any accepted warn/info findings with reasons, and the fact that **no version bump has been made** — per `## Versioning`, the bump is a separate commit sequenced after integration, only if this change is being released.

---

## Deferred (explicitly out of scope)

These were part of the original Plan C stub and are **not** in this plan. Do not build them here:

- The content-addressed build cache (hash of model slice + policy + version → artifact).
- Incremental re-layout of changed views.
- `watch` mode.
- Any network transport (HTTP/SSE/Streamable HTTP).
- MCP *resources* and *prompts* surfaces.
- Per-stage tools (`project`, `layout`, `render`, `export`) — these stay CLI-only.

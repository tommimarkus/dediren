# Dediren Real ELK Layout Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real Java-based ELK layered layout helper that consumes `layout-request.schema.v1` JSON and produces `layout-result.schema.v1` JSON for the existing Rust `elk-layout` plugin adapter.

**Architecture:** Keep the Rust `dediren-plugin-elk-layout` crate as the stable process adapter and add a plugin-owned Java helper under `crates/dediren-plugin-elk-layout/java`. The helper is built and run with SDKMAN-managed Java and Gradle, uses pinned Maven dependencies, and remains an external command invoked through `DEDIREN_ELK_COMMAND`. Fixture mode stays in the Rust adapter for deterministic compatibility tests.

**Tech Stack:** Rust 1.93, Java 25 LTS via SDKMAN (`25.0.3-tem`), Gradle 9.5.0 via SDKMAN, JUnit 5, Jackson, Eclipse ELK `org.eclipse.elk.alg.layered` 0.11.0, JSON stdin/stdout command envelopes.

---

## Source Notes

- Eclipse documents the layered algorithm id as `org.eclipse.elk.layered` and describes it as the layer-based layout algorithm for directed diagrams.
- Maven Central lists `org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0` as the current published artifact and shows EPL-2.0 licensing.
- Eclipse's plain Java layout docs recommend `RecursiveGraphLayoutEngine` with `BasicProgressMonitor`.
- SDKMAN's current catalog lists Java `25.0.3-tem` and Gradle `9.5.0`.
- Gradle's current compatibility matrix is version `9.5.0`; it supports running Gradle on Java 25 starting with Gradle `9.1.0`, so Gradle `9.5.0` is compatible with the latest LTS Java target.

## File Structure

- Create: `crates/dediren-plugin-elk-layout/java/.sdkmanrc` - SDKMAN toolchain contract for the plugin-owned Java helper.
- Modify: `.gitignore` - ignore Java build outputs and repo-local Gradle cache.
- Create: `crates/dediren-plugin-elk-layout/java/settings.gradle.kts` - Java helper Gradle project name.
- Create: `crates/dediren-plugin-elk-layout/java/build.gradle.kts` - Java application dependencies, locking, and test configuration.
- Create: `crates/dediren-plugin-elk-layout/java/gradle.properties` - deterministic Java/Gradle defaults.
- Create: `crates/dediren-plugin-elk-layout/java/gradle.lockfile` - locked Maven dependency graph.
- Create: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh` - SDKMAN-aware build script.
- Create: `crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh` - SDKMAN-aware runtime script for `DEDIREN_ELK_COMMAND`.
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/Main.java` - CLI entrypoint.
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java` - local Java records for public JSON contracts.
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java` - Dediren-to-ELK mapping and result extraction.
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/EnvelopeWriter.java` - command envelope and diagnostic output helpers.
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java` - JSON contract round-trip tests.
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java` - real ELK layout behavior tests.
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java` - CLI stdin/stdout tests.
- Modify: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs` - keep adapter tests and add one ignored/local test for the SDKMAN Java helper command.
- Modify: `crates/dediren-cli/tests/cli_layout.rs` - keep command-adapter coverage and add one ignored/local end-to-end test using the built Java helper.
- Modify: `README.md` - document SDKMAN build/run flow and `DEDIREN_ELK_COMMAND=crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh`.

The Java helper is deliberately colocated with `dediren-plugin-elk-layout` because it is plugin-specific implementation. It is not a separate Cargo package and does not add vendored binary content.

---

### Task 1: Add SDKMAN And Gradle Skeleton

**Files:**
- Create: `crates/dediren-plugin-elk-layout/java/.sdkmanrc`
- Modify: `.gitignore`
- Create: `crates/dediren-plugin-elk-layout/java/settings.gradle.kts`
- Create: `crates/dediren-plugin-elk-layout/java/build.gradle.kts`
- Create: `crates/dediren-plugin-elk-layout/java/gradle.properties`
- Create: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`

- [ ] **Step 1: Verify the missing Java project fails**

Run:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
gradle -p crates/dediren-plugin-elk-layout/java test
```

Expected: FAIL because `crates/dediren-plugin-elk-layout/java` does not contain a Gradle build yet.

- [ ] **Step 2: Add SDKMAN toolchain pin**

Create `crates/dediren-plugin-elk-layout/java/.sdkmanrc`:

```properties
java=25.0.3-tem
gradle=9.5.0
```

- [ ] **Step 3: Ignore Java build output and repo-local Gradle cache**

Update `.gitignore` to this complete content:

```gitignore
/target/
/.cache/
crates/dediren-plugin-elk-layout/java/.gradle/
crates/dediren-plugin-elk-layout/java/build/
**/*.svg
!.github/**/*.svg
```

- [ ] **Step 4: Create the Gradle settings file**

Create `crates/dediren-plugin-elk-layout/java/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dediren-elk-layout-java"
```

- [ ] **Step 5: Create the Gradle build file**

Create `crates/dediren-plugin-elk-layout/java/build.gradle.kts`:

```kotlin
plugins {
    application
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("dev.dediren.elk.Main")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.3")
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.graph:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0")
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.32.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 6: Add Gradle defaults**

Create `crates/dediren-plugin-elk-layout/java/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
```

- [ ] **Step 7: Add the SDKMAN-aware build script**

Create `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
REPO_ROOT=$(cd -- "$PROJECT_DIR/../../.." && pwd -P)

if [[ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
  echo "SDKMAN is required: install SDKMAN, then run sdk env install from $PROJECT_DIR" >&2
  exit 2
fi

source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$PROJECT_DIR"
sdk env

export GRADLE_USER_HOME="$REPO_ROOT/.cache/gradle/user-home"
mkdir -p "$GRADLE_USER_HOME" "$REPO_ROOT/.cache/gradle/project-cache"

gradle \
  --project-cache-dir "$REPO_ROOT/.cache/gradle/project-cache/elk-layout-java" \
  -p "$PROJECT_DIR" \
  clean test installDist
```

- [ ] **Step 8: Make the build script executable**

Run:

```bash
chmod +x crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

- [ ] **Step 9: Verify the skeleton still fails for missing Java sources**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: FAIL with a Gradle compile or application main-class error because `dev.dediren.elk.Main` does not exist yet.

- [ ] **Step 10: Commit the skeleton**

Run:

```bash
git add .gitignore crates/dediren-plugin-elk-layout/java/.sdkmanrc crates/dediren-plugin-elk-layout/java/settings.gradle.kts crates/dediren-plugin-elk-layout/java/build.gradle.kts crates/dediren-plugin-elk-layout/java/gradle.properties crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
git commit -m "Add SDKMAN Java ELK helper skeleton"
```

---

### Task 2: Add Java JSON Contracts And Envelope Output

**Files:**
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java`
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/EnvelopeWriter.java`
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/Main.java`
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`

- [ ] **Step 1: Write the failing contract round-trip tests**

Create `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`:

```java
package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonContractsTest {
    private final ObjectMapper mapper = JsonContracts.objectMapper();

    @Test
    void readsLayoutRequestAndWritesLayoutResultEnvelope() throws Exception {
        String json = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": []
            }
            """;

        JsonContracts.LayoutRequest request =
            mapper.readValue(json, JsonContracts.LayoutRequest.class);

        JsonContracts.LayoutResult result = new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            List.of(new JsonContracts.LaidOutNode(
                "client", "client", "client", 12.0, 24.0, 160.0, 80.0, "Client")),
            List.of(),
            List.of(),
            List.of());

        String envelope = EnvelopeWriter.ok(mapper, result);

        assertEquals("main", request.view_id());
        assertTrue(envelope.contains("\"envelope_schema_version\":\"envelope.schema.v1\""));
        assertTrue(envelope.contains("\"status\":\"ok\""));
        assertTrue(envelope.contains("\"layout_result_schema_version\":\"layout-result.schema.v1\""));
    }
}
```

- [ ] **Step 2: Write the failing CLI error-envelope test**

Create `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`:

```java
package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void invalidJsonReturnsStructuredErrorEnvelope() throws Exception {
        ByteArrayInputStream stdin =
            new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        assertTrue(text.contains("\"status\":\"error\""));
        assertTrue(text.contains("DEDIREN_ELK_INPUT_INVALID_JSON"));
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
```

- [ ] **Step 3: Run the Java tests and verify they fail**

Run:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd crates/dediren-plugin-elk-layout/java
sdk env
cd ../../..
export GRADLE_USER_HOME="$PWD/.cache/gradle/user-home"
gradle --project-cache-dir "$PWD/.cache/gradle/project-cache/elk-layout-java" -p crates/dediren-plugin-elk-layout/java test
```

Expected: FAIL because `JsonContracts`, `EnvelopeWriter`, and `Main` do not exist.

- [ ] **Step 4: Add the Java contract records**

Create `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java`:

```java
package dev.dediren.elk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class JsonContracts {
    private JsonContracts() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    record LayoutRequest(
        String layout_request_schema_version,
        String view_id,
        List<LayoutNode> nodes,
        List<LayoutEdge> edges,
        List<LayoutGroup> groups,
        List<LayoutLabel> labels,
        List<LayoutConstraint> constraints) {
    }

    record LayoutNode(
        String id,
        String label,
        String source_id,
        Double width_hint,
        Double height_hint) {
    }

    record LayoutEdge(
        String id,
        String source,
        String target,
        String label,
        String source_id) {
    }

    record LayoutGroup(
        String id,
        String label,
        List<String> members,
        Object provenance) {
    }

    record LayoutLabel(String owner_id, String text) {
    }

    record LayoutConstraint(String id, String kind, List<String> subjects) {
    }

    record LayoutResult(
        String layout_result_schema_version,
        String view_id,
        List<LaidOutNode> nodes,
        List<LaidOutEdge> edges,
        List<LaidOutGroup> groups,
        List<Diagnostic> warnings) {
    }

    record LaidOutNode(
        String id,
        String source_id,
        String projection_id,
        double x,
        double y,
        double width,
        double height,
        String label) {
    }

    record LaidOutEdge(
        String id,
        String source,
        String target,
        String source_id,
        String projection_id,
        List<Point> points,
        String label) {
    }

    record LaidOutGroup(
        String id,
        String source_id,
        String projection_id,
        double x,
        double y,
        double width,
        double height,
        List<String> members,
        String label) {
    }

    record Point(double x, double y) {
    }

    record Diagnostic(String code, String severity, String message, String path) {
    }

    record CommandEnvelope<T>(
        String envelope_schema_version,
        String status,
        T data,
        List<Diagnostic> diagnostics) {
    }
}
```

- [ ] **Step 5: Add envelope output helpers**

Create `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/EnvelopeWriter.java`:

```java
package dev.dediren.elk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class EnvelopeWriter {
    private EnvelopeWriter() {
    }

    static String ok(ObjectMapper mapper, JsonContracts.LayoutResult result)
        throws JsonProcessingException {
        return mapper.writeValueAsString(new JsonContracts.CommandEnvelope<>(
            "envelope.schema.v1",
            "ok",
            result,
            List.of()));
    }

    static String error(ObjectMapper mapper, String code, String message)
        throws JsonProcessingException {
        JsonContracts.Diagnostic diagnostic =
            new JsonContracts.Diagnostic(code, "error", message, null);
        return mapper.writeValueAsString(new JsonContracts.CommandEnvelope<>(
            "envelope.schema.v1",
            "error",
            null,
            List.of(diagnostic)));
    }
}
```

- [ ] **Step 6: Add a temporary Main that handles malformed input**

Create `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/Main.java`:

```java
package dev.dediren.elk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.PrintStream;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(System.in, System.out));
    }

    static int run(InputStream stdin, PrintStream stdout) throws Exception {
        ObjectMapper mapper = JsonContracts.objectMapper();
        try {
            mapper.readValue(stdin, JsonContracts.LayoutRequest.class);
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_NOT_IMPLEMENTED",
                "ELK layout engine has not been wired yet"));
            return 3;
        } catch (Exception error) {
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_INPUT_INVALID_JSON",
                "layout request JSON is invalid: " + error.getMessage()));
            return 3;
        }
    }
}
```

- [ ] **Step 7: Run the Java contract tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: PASS for `JsonContractsTest` and `MainTest`; the build now produces `crates/dediren-plugin-elk-layout/java/build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java`.

- [ ] **Step 8: Commit Java contract plumbing**

Run:

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk
git commit -m "Add Java ELK helper JSON contracts"
```

---

### Task 3: Add Real ELK Layout Translation

**Files:**
- Create: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Create: `crates/dediren-plugin-elk-layout/java/gradle.lockfile`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/Main.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`

- [ ] **Step 1: Write the failing two-node real layout test**

Create `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`:

```java
package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ElkLayoutEngineTest {
    @Test
    void layeredLayoutPlacesTargetToTheRightAndRoutesTheEdge() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
                new JsonContracts.LayoutNode("api", "API", "api", 160.0, 80.0)),
            List.of(new JsonContracts.LayoutEdge(
                "client-calls-api", "client", "api", "calls", "client-calls-api")),
            List.of(),
            List.of(),
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutNode client = result.nodes().stream()
            .filter(node -> node.id().equals("client"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutNode api = result.nodes().stream()
            .filter(node -> node.id().equals("api"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutEdge edge = result.edges().get(0);

        assertEquals("layout-result.schema.v1", result.layout_result_schema_version());
        assertEquals("main", result.view_id());
        assertEquals("client", client.source_id());
        assertEquals("api", api.projection_id());
        assertTrue(api.x() > client.x(), "layered layout should place target after source");
        assertTrue(edge.points().size() >= 2, "layout must include start and end points");
        assertEquals(List.of(), result.warnings());
    }
}
```

- [ ] **Step 2: Run the Java tests and verify the layout test fails**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: FAIL because `ElkLayoutEngine` does not exist.

- [ ] **Step 3: Add the real ELK layout engine**

Create `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`:

```java
package dev.dediren.elk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;

final class ElkLayoutEngine {
    private static final double DEFAULT_WIDTH = 160.0;
    private static final double DEFAULT_HEIGHT = 80.0;
    private static final double GROUP_PADDING = 24.0;

    JsonContracts.LayoutResult layout(JsonContracts.LayoutRequest request) {
        ElkNode root = ElkGraphUtil.createGraph();
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);

        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            elkNode.setDimensions(
                positiveOrDefault(node.width_hint(), DEFAULT_WIDTH),
                positiveOrDefault(node.height_hint(), DEFAULT_HEIGHT));
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        List<JsonContracts.Diagnostic> warnings = new ArrayList<>();
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkNode source = elkNodes.get(edge.source());
            ElkNode target = elkNodes.get(edge.target());
            if (source == null || target == null) {
                warnings.add(new JsonContracts.Diagnostic(
                    "DEDIREN_ELK_DANGLING_EDGE",
                    "warning",
                    "edge " + edge.id() + " references a missing endpoint",
                    "$.edges." + edge.id()));
                continue;
            }
            ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(source, target);
            elkEdge.setIdentifier(edge.id());
            ElkGraphUtil.createLabel(elkEdge).setText(edge.label());
            elkEdges.put(edge.id(), elkEdge);
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        List<JsonContracts.LaidOutNode> nodes = new ArrayList<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = elkNodes.get(node.id());
            if (elkNode != null) {
                nodes.add(new JsonContracts.LaidOutNode(
                    node.id(),
                    node.source_id(),
                    node.id(),
                    elkNode.getX(),
                    elkNode.getY(),
                    elkNode.getWidth(),
                    elkNode.getHeight(),
                    node.label()));
            }
        }

        List<JsonContracts.LaidOutEdge> edges = new ArrayList<>();
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    points(elkEdge),
                    edge.label()));
            }
        }

        List<JsonContracts.LaidOutGroup> groups =
            groups(request, nodes, warnings);

        return new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private static List<JsonContracts.Point> points(ElkEdge edge) {
        List<JsonContracts.Point> points = new ArrayList<>();
        for (ElkEdgeSection section : edge.getSections()) {
            if (points.isEmpty()) {
                points.add(new JsonContracts.Point(section.getStartX(), section.getStartY()));
            }
            section.getBendPoints().forEach(bend ->
                points.add(new JsonContracts.Point(bend.getX(), bend.getY())));
            points.add(new JsonContracts.Point(section.getEndX(), section.getEndY()));
        }
        return points;
    }

    private static List<JsonContracts.LaidOutGroup> groups(
        JsonContracts.LayoutRequest request,
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.Diagnostic> warnings) {
        Map<String, JsonContracts.LaidOutNode> byId = new HashMap<>();
        for (JsonContracts.LaidOutNode node : nodes) {
            byId.put(node.id(), node);
        }

        List<JsonContracts.LaidOutGroup> groups = new ArrayList<>();
        for (JsonContracts.LayoutGroup group : list(request.groups())) {
            List<JsonContracts.LaidOutNode> members = list(group.members()).stream()
                .map(byId::get)
                .filter(node -> node != null)
                .toList();
            if (members.isEmpty()) {
                warnings.add(new JsonContracts.Diagnostic(
                    "DEDIREN_ELK_EMPTY_GROUP",
                    "warning",
                    "group " + group.id() + " has no laid out members",
                    "$.groups." + group.id()));
                continue;
            }

            double minX = members.stream().mapToDouble(JsonContracts.LaidOutNode::x).min().orElse(0.0);
            double minY = members.stream().mapToDouble(JsonContracts.LaidOutNode::y).min().orElse(0.0);
            double maxX = members.stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
            double maxY = members.stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);

            groups.add(new JsonContracts.LaidOutGroup(
                group.id(),
                group.id(),
                group.id(),
                minX - GROUP_PADDING,
                minY - GROUP_PADDING,
                (maxX - minX) + (GROUP_PADDING * 2.0),
                (maxY - minY) + (GROUP_PADDING * 2.0),
                group.members(),
                group.label()));
        }
        return groups;
    }

    private static double positiveOrDefault(Double value, double fallback) {
        return value != null && value > 0.0 ? value : fallback;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }
}
```

- [ ] **Step 4: Wire Main to the real layout engine**

Replace `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/Main.java` with:

```java
package dev.dediren.elk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.PrintStream;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(System.in, System.out));
    }

    static int run(InputStream stdin, PrintStream stdout) throws Exception {
        ObjectMapper mapper = JsonContracts.objectMapper();
        JsonContracts.LayoutRequest request;
        try {
            request = mapper.readValue(stdin, JsonContracts.LayoutRequest.class);
        } catch (Exception error) {
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_INPUT_INVALID_JSON",
                "layout request JSON is invalid: " + error.getMessage()));
            return 3;
        }

        try {
            JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
            stdout.println(EnvelopeWriter.ok(mapper, result));
            return 0;
        } catch (Exception error) {
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_LAYOUT_FAILED",
                "ELK layout failed: " + error.getMessage()));
            return 3;
        }
    }
}
```

- [ ] **Step 5: Add a CLI success test**

Append this test to `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`:

```java
    @Test
    void validRequestReturnsOkEnvelopeWithLayoutResult() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80},
                {"id": "api", "label": "API", "source_id": "api", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [
                {"id": "client-calls-api", "source": "client", "target": "api", "label": "calls", "source_id": "client-calls-api"}
              ],
              "groups": [],
              "labels": [],
              "constraints": []
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(text.contains("\"status\":\"ok\""));
        assertTrue(text.contains("\"layout_result_schema_version\":\"layout-result.schema.v1\""));
        assertTrue(text.contains("\"client-calls-api\""));
    }
```

- [ ] **Step 6: Run the Java ELK tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: PASS. If Gradle cannot download dependencies in the sandbox, rerun the same command with sandbox escalation for network and file locks.

- [ ] **Step 7: Generate and commit dependency locks**

Run:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd crates/dediren-plugin-elk-layout/java
sdk env
cd ../../..
export GRADLE_USER_HOME="$PWD/.cache/gradle/user-home"
gradle --project-cache-dir "$PWD/.cache/gradle/project-cache/elk-layout-java" -p crates/dediren-plugin-elk-layout/java dependencies --write-locks
git add crates/dediren-plugin-elk-layout/java
git commit -m "Add real Java ELK layout engine"
```

---

### Task 4: Add SDKMAN Runtime Script And Rust/CLI Integration Evidence

**Files:**
- Create: `crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh`
- Modify: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_layout.rs`

- [ ] **Step 1: Add the runtime script**

Create `crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
APP="$PROJECT_DIR/build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java"

if [[ ! -x "$APP" ]]; then
  echo "ELK helper is not built; run crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh" >&2
  exit 2
fi

if [[ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
  echo "SDKMAN is required to run the ELK helper" >&2
  exit 2
fi

source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$PROJECT_DIR"
sdk env >/dev/null
exec "$APP"
```

- [ ] **Step 2: Make the runtime script executable**

Run:

```bash
chmod +x crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh
```

- [ ] **Step 3: Add an ignored Rust plugin test for the real Java helper**

Append this test to `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`:

```rust
#[test]
#[ignore = "requires SDKMAN Java helper build"]
fn elk_plugin_invokes_real_java_helper() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("\"client-calls-api\""));
}
```

- [ ] **Step 4: Add an ignored CLI test for the real Java helper**

Append this test to `crates/dediren-cli/tests/cli_layout.rs`:

```rust
#[test]
#[ignore = "requires SDKMAN Java helper build"]
fn layout_invokes_real_java_elk_helper() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_COMMAND", helper)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("\"client-calls-api\""));
}
```

- [ ] **Step 5: Build the helper and run the ignored integration tests explicitly**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin -- --ignored
cargo test -p dediren --test cli_layout -- --ignored
```

Expected: PASS. These tests are ignored during normal `cargo test` because they depend on the SDKMAN Java helper build artifact.

- [ ] **Step 6: Commit runtime script and integration evidence**

Run:

```bash
git add crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs crates/dediren-cli/tests/cli_layout.rs
git commit -m "Verify Rust adapter with Java ELK helper"
```

---

### Task 5: Document The Real ELK Runtime Flow

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update README ELK runtime docs**

Replace the `## ELK Runtime` section in `README.md` with:

````markdown
## ELK Runtime

The bundled `elk-layout` plugin is a Rust external-process adapter. The real
ELK layered runtime is a Java helper under `crates/dediren-plugin-elk-layout/java` and is built
with SDKMAN-managed Java and Gradle.

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd crates/dediren-plugin-elk-layout/java
sdk env install
sdk env
cd ../../..
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
DEDIREN_ELK_COMMAND=crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh \
  dediren layout --plugin elk-layout --input fixtures/layout-request/basic.json
```

The Java helper reads a `layout-request.schema.v1` document from stdin and
returns a JSON command envelope whose `.data` is a `layout-result.schema.v1`
document. The helper uses Eclipse ELK Layered (`org.eclipse.elk.layered`) and
the Gradle build pins Maven dependencies through dependency locking.

Tests may still use `DEDIREN_ELK_RESULT_FIXTURE` to exercise the Rust plugin
contract without Java. Fixture mode takes precedence over `DEDIREN_ELK_COMMAND`
for deterministic compatibility tests.
````

- [ ] **Step 2: Run docs and formatting checks**

Run:

```bash
cargo fmt
git diff --check
```

Expected: PASS.

- [ ] **Step 3: Run the complete verification set**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin -- --ignored
cargo test -p dediren --test cli_layout -- --ignored
DEDIREN_ELK_COMMAND=crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh cargo run -q -p dediren -- layout --plugin elk-layout --input fixtures/layout-request/basic.json
```

Expected: all commands exit `0`; the final command prints a JSON envelope with `"status":"ok"` and `"layout_result_schema_version":"layout-result.schema.v1"`.

- [ ] **Step 4: Commit docs**

Run:

```bash
git add README.md
git commit -m "Document SDKMAN ELK runtime flow"
```

---

### Task 6: Run Required Audit Validation

**Files:**
- Inspect: `crates/dediren-plugin-elk-layout/java/**`
- Inspect: `crates/dediren-plugin-elk-layout/src/main.rs`
- Inspect: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- Inspect: `crates/dediren-cli/tests/cli_layout.rs`
- Inspect: `README.md`
- Modify: only the files implicated by audit findings, if any.

- [ ] **Step 1: Run the DevSecOps audit validation**

Ask Codex to run this exact skill-scoped validation:

```text
[$souroldgeezer-audit:devsecops-audit]
Quick audit the ELK layout runtime implementation diff.

Scope:
- crates/dediren-plugin-elk-layout/java/.sdkmanrc
- crates/dediren-plugin-elk-layout/java/build.gradle.kts
- crates/dediren-plugin-elk-layout/java/gradle.properties
- crates/dediren-plugin-elk-layout/java/gradle.lockfile
- crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
- crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh
- crates/dediren-plugin-elk-layout/src/main.rs
- README.md

Mode: Quick.
Cost stance: full.
Evidence: static repository evidence only; do not require GitHub, release, MCP, or live deployment probes for this local plugin slice.
Focus: SDKMAN script behavior, Gradle dependency provenance and locking, generated/build output exclusions, external process invocation, stderr/stdout handling, environment-variable contract, and whether any runtime artifact is vendored.
Expected result: no block findings. Fix any block finding before continuing. Warn/info findings may remain only if they are explicitly accepted in the final handoff with the reason.
```

- [ ] **Step 2: Run the test-quality audit validation**

Ask Codex to run this exact skill-scoped validation:

```text
[$souroldgeezer-audit:test-quality-audit]
Deep audit the ELK layout test changes.

Scope:
- crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java
- crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
- crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java
- crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs
- crates/dediren-cli/tests/cli_layout.rs
- fixtures/layout-request/basic.json

Rubrics:
- Unit/component for Java JSON contract and layout-engine tests.
- Integration for Java CLI stdin/stdout tests, Rust plugin adapter tests, and CLI layout end-to-end tests.

Mode: Deep for this bounded suite.
Evidence: static test/source inspection plus the verification command results from this plan.
Focus: whether tests prove the public layout contract rather than private implementation, whether ignored real-helper tests are clearly separated from deterministic fixture tests, whether assertions avoid false confidence from incidental coordinates, whether failure-path coverage exists for invalid JSON/runtime failures, and whether Java/Rust integration tests exercise the actual command envelope.
Expected result: no block findings. Fix any block finding before continuing. Warn/info findings may remain only if they are explicitly accepted in the final handoff with the reason.
```

- [ ] **Step 3: Re-run affected verification after audit fixes**

If either audit caused code, script, test, or documentation edits, run the smallest affected verification first, then run the full final verification set below.

Expected: the affected check and the full final verification pass with no block findings from either audit.

- [ ] **Step 4: Commit audit-driven fixes if any were needed**

If audit validation produced implementation changes, run:

```bash
git add crates/dediren-plugin-elk-layout/java crates/dediren-plugin-elk-layout/src/main.rs crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs crates/dediren-cli/tests/cli_layout.rs README.md
git commit -m "Address ELK runtime audit findings"
```

Expected: commit is created only when audit-driven file changes exist. If both audits return clean with no file changes, skip this commit.

---

## Final Verification

After all tasks are complete, run:

```bash
git status --short --branch
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd crates/dediren-plugin-elk-layout/java
sdk env
sdk current java
sdk current gradle
cd ../../..
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin -- --ignored
cargo test -p dediren --test cli_layout -- --ignored
DEDIREN_ELK_COMMAND=crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh cargo run -q -p dediren -- layout --plugin elk-layout --input fixtures/layout-request/basic.json
```

Then run the required audit validations from Task 6:

- `[$souroldgeezer-audit:devsecops-audit]` quick audit for the ELK runtime implementation diff.
- `[$souroldgeezer-audit:test-quality-audit]` deep audit for the bounded ELK test suite.

Expected:

- `git status --short --branch` shows only the expected branch/ahead state and no uncommitted files.
- `sdk current java` reports `25.0.3-tem`.
- `sdk current gradle` reports `9.5.0`.
- Java helper build and tests pass.
- Normal Rust workspace tests pass.
- Ignored real-helper integration tests pass when explicitly requested.
- The manual CLI command returns an `ok` envelope containing a `layout-result.schema.v1` payload.
- DevSecOps audit reports no block findings; warn/info findings are fixed or explicitly accepted in the final handoff.
- Test-quality audit reports no block findings; warn/info findings are fixed or explicitly accepted in the final handoff.

## Design Notes

- Rejected bundling a JAR in git: this would turn a third-party runtime artifact into repository content and make dependency provenance harder to inspect.
- Rejected a Gradle wrapper in this slice: the user explicitly requested SDKMAN for Java and Gradle, and the plan pins the latest SDKMAN catalog entries for Java 25 LTS and Gradle 9.5.0.
- Rejected moving ELK into the Rust plugin process: the plugin contract is process-based, and keeping Java outside Rust preserves language-neutral plugin mechanics.
- Deferred richer constraint semantics: the current public `LayoutConstraint` contract is only `{id, kind, subjects}`. This plan emits real ELK layout geometry first and can add explicit constraint kinds in a later contract revision.

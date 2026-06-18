# Render Plugin Rename + PNG Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the `svg-render` plugin to `render` (module, Maven coordinates, Java package, plugin id, executable, policy schema family) and have that same plugin emit a PNG artifact alongside the SVG, rasterized with Apache Batik and sized by a scale factor.

**Architecture:** The renamed `render` plugin keeps producing SVG exactly as today. When the render policy carries a `raster` block, the plugin rasterizes its own generated SVG with Batik's `PNGTranscoder` and appends a base64-encoded `png` artifact to the `render-result` envelope. The policy schema family is renamed `svg-render-policy` → `render-policy` and `render-result` is bumped `v2` → `v3` to carry the new `png` kind and an `encoding` field.

**Tech Stack:** Java 21+, Maven (checked-in wrapper `./mvnw`), Jackson (`SNAKE_CASE`, `FAIL_ON_UNKNOWN_PROPERTIES=true`), JUnit 5 + AssertJ, Apache Batik (`batik-transcoder`, `batik-codec`), appassembler launcher.

## Global Constraints

- Java 21+, built with the checked-in Maven Wrapper `./mvnw`. Never reintroduce pre-Maven guidance.
- Product version is `2026.06.6` and is **not** bumped in this plan. The version bump + `v<version>` tag is a separate follow-on commit per `release-policy`, sequenced after this work integrates. Leave every version string at `2026.06.6`.
- `./mvnw test` must run with the command sandbox disabled — JUnit `@TempDir` needs a writable temp dir (repo memory: "Maven tests need sandbox disabled").
- Module-scoped runs need `-am` (siblings are not installed): `./mvnw -pl <module> -am test`.
- Staging is explicit-path only; never `git add -A`. Untracked dotfiles in the worktree are pre-existing user work — never stage them. Do not stage generated/ignored outputs (`target/`, `dist/`, `*.svg`).
- Schema ids are the durable compatibility signal. `render-policy.schema.v1` and `render-result.schema.v3` are intentional contract changes.
- The diagnostic code `DEDIREN_SVG_POLICY_INVALID` is **kept unchanged** (renaming a diagnostic token is a separate, unrequested contract break). Do not rename it.
- Files under `docs/superpowers/plans/` are history — do not rewrite old `svg-render`/`DEDIREN_PLUGIN_SVG_RENDER` references there.
- Jackson uses `SNAKE_CASE`: record field `fooBar` ⇄ JSON `foo_bar`.

---

### Task 1: Rename plugin module, Maven coordinates, Java package, and id literals

**Files:**
- Rename dir: `plugins/svg-render/` → `plugins/render/`
- Rename dir: `plugins/render/src/main/java/dev/dediren/plugins/svgrender/` → `.../plugins/render/`
- Rename dir: `plugins/render/src/test/java/dev/dediren/plugins/svgrender/` → `.../plugins/render/`
- Modify: `plugins/render/pom.xml` (artifactId, appassembler ids, mainClass)
- Modify: every `*.java` under `plugins/render/src` (package + imports `dev.dediren.plugins.svgrender` → `dev.dediren.plugins.render`)
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java` (id literals)
- Modify: root `pom.xml:22` (`<module>`) and `pom.xml:233` (jdeps `<param>`)

**Interfaces:**
- Produces: Maven module `render` (artifactId `render`), Java package `dev.dediren.plugins.render`, plugin runtime id string `render`, appassembler program `dediren-plugin-render`. Later tasks reference these names.

- [ ] **Step 1: Move the module and package directories with git**

```bash
cd /home/souroldgeezer/repos/dediren
git mv plugins/svg-render plugins/render
git mv plugins/render/src/main/java/dev/dediren/plugins/svgrender plugins/render/src/main/java/dev/dediren/plugins/render
git mv plugins/render/src/test/java/dev/dediren/plugins/svgrender plugins/render/src/test/java/dev/dediren/plugins/render
```

- [ ] **Step 2: Rewrite the Java package/import references**

```bash
cd /home/souroldgeezer/repos/dediren
grep -rl 'dev\.dediren\.plugins\.svgrender' plugins/render/src | xargs sed -i 's/dev\.dediren\.plugins\.svgrender/dev.dediren.plugins.render/g'
```

- [ ] **Step 3: Update the two id literals in `Main.java`**

In `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`:
- `moduleName()` returns `"render"` (was `"svg-render"`).
- `capabilitiesJson()` sets `root.put("id", "render");` (was `"svg-render"`).

```bash
cd /home/souroldgeezer/repos/dediren
sed -i 's/return "svg-render";/return "render";/; s/root.put("id", "svg-render")/root.put("id", "render")/' \
  plugins/render/src/main/java/dev/dediren/plugins/render/Main.java
```

- [ ] **Step 4: Update the plugin `pom.xml`**

In `plugins/render/pom.xml`:
- `<artifactId>svg-render</artifactId>` → `<artifactId>render</artifactId>`
- `<id>assemble-svg-render</id>` → `<id>assemble-render</id>`
- `<mainClass>dev.dediren.plugins.svgrender.Main</mainClass>` → `<mainClass>dev.dediren.plugins.render.Main</mainClass>`
- inner `<id>svg-render</id>` (the program id) → `<id>render</id>`

```bash
cd /home/souroldgeezer/repos/dediren
sed -i \
  -e 's#<artifactId>svg-render</artifactId>#<artifactId>render</artifactId>#' \
  -e 's#<id>assemble-svg-render</id>#<id>assemble-render</id>#' \
  -e 's#dev\.dediren\.plugins\.svgrender\.Main#dev.dediren.plugins.render.Main#' \
  -e 's#<id>svg-render</id>#<id>render</id>#' \
  plugins/render/pom.xml
```

- [ ] **Step 5: Update root `pom.xml` module list and jdeps param**

```bash
cd /home/souroldgeezer/repos/dediren
sed -i \
  -e 's#<module>plugins/svg-render</module>#<module>plugins/render</module>#' \
  -e 's#dev\.dediren\.plugins\.svgrender\.\*#dev.dediren.plugins.render.*#' \
  pom.xml
```

- [ ] **Step 6: Verify the renamed module compiles and its tests pass**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test`
Expected: BUILD SUCCESS; the existing render tests pass under the new package/coordinates. (The manifest fixture still says `svg-render`; tests that assert the manifest id are updated in Task 2 — if a manifest-id assertion fails here, it is fixed there.)

- [ ] **Step 7: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add plugins/render pom.xml
git commit -m "refactor(render): rename svg-render plugin module to render"
```

---

### Task 2: Rename manifest, source-fixture plugin id, dist-tool launcher and env token

**Files:**
- Rename: `fixtures/plugins/svg-render.manifest.json` → `fixtures/plugins/render.manifest.json`
- Modify: `fixtures/source/**` entries with `required_plugins[].id == "svg-render"`
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (lines ~33-34 launcher, ~45 env token, ~198 env token, ~213 render command)
- Modify: any dist-tool test asserting the old id/executable/env token

**Interfaces:**
- Consumes: plugin id `render`, executable `dediren-plugin-render` (Task 1).
- Produces: manifest fixture `fixtures/plugins/render.manifest.json` (id `render`, executable `dediren-plugin-render`); env override token `DEDIREN_PLUGIN_RENDER`.

- [ ] **Step 1: Rename the manifest fixture and update its fields**

```bash
cd /home/souroldgeezer/repos/dediren
git mv fixtures/plugins/svg-render.manifest.json fixtures/plugins/render.manifest.json
sed -i -e 's/"id": "svg-render"/"id": "render"/' \
       -e 's/"executable": "dediren-plugin-svg-render"/"executable": "dediren-plugin-render"/' \
  fixtures/plugins/render.manifest.json
```

Confirm capabilities stays `["render"]` (unchanged).

- [ ] **Step 2: Update source fixtures that require the plugin by id**

```bash
cd /home/souroldgeezer/repos/dediren
grep -rln '"id": *"svg-render"' fixtures/source | xargs --no-run-if-empty sed -i 's/"id": *"svg-render"/"id": "render"/'
```

(Leave any `version` values at `2026.06.6`.)

- [ ] **Step 3: Update dist-tool launcher, env token, and render command**

In `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`, replace the svg-render launcher/paths/env/command references:

```bash
cd /home/souroldgeezer/repos/dediren
sed -i \
  -e 's#plugins/svg-render/target/appassembler#plugins/render/target/appassembler#g' \
  -e 's#dediren-plugin-svg-render#dediren-plugin-render#g' \
  -e 's#DEDIREN_PLUGIN_SVG_RENDER#DEDIREN_PLUGIN_RENDER#g' \
  -e 's#missing-svg-render#missing-render#g' \
  -e 's#"svg-render"#"render"#g' \
  dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
```

Then read the file around the edited lines to confirm the `Launcher(...)` arguments and the `render --plugin render` smoke command are coherent (the launcher tuple should read `("plugins/render/target/appassembler", "render", "dediren-plugin-render", "render")`).

- [ ] **Step 4: Update any dist-tool tests asserting the old names**

```bash
cd /home/souroldgeezer/repos/dediren
grep -rln 'svg-render\|DEDIREN_PLUGIN_SVG_RENDER\|dediren-plugin-svg-render' dist-tool/src/test | \
  xargs --no-run-if-empty sed -i \
  -e 's#dediren-plugin-svg-render#dediren-plugin-render#g' \
  -e 's#DEDIREN_PLUGIN_SVG_RENDER#DEDIREN_PLUGIN_RENDER#g' \
  -e 's#svg-render#render#g'
```

Review each touched test for false replacements (e.g. comments) before continuing.

- [ ] **Step 5: Verify dist-tool builds and unit tests pass**

Run (sandbox disabled): `./mvnw -pl dist-tool -am test`
Expected: BUILD SUCCESS. (The dist-smoke profile is exercised in Task 7 once the launcher is fully built.)

- [ ] **Step 6: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add fixtures/plugins fixtures/source dist-tool
git commit -m "refactor(render): point manifest, fixtures, and dist-tool at the render plugin id"
```

---

### Task 3: Rename the policy schema family to `render-policy.schema.v1`

**Files:**
- Rename: `schemas/svg-render-policy.schema.json` → `schemas/render-policy.schema.json`
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`
- Modify: `fixtures/render-policy/*.json` (5 files)
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractVersionsTest.java`, `ContractRoundTripTest.java`
- Modify: `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: schema id `render-policy.schema.v1`; JSON discriminator field `render_policy_schema_version`; `ContractVersions.RENDER_POLICY_SCHEMA_VERSION`; `RenderPolicy.renderPolicySchemaVersion()`.

- [ ] **Step 1: Rename the schema file and update its `$id`, field name, and const**

```bash
cd /home/souroldgeezer/repos/dediren
git mv schemas/svg-render-policy.schema.json schemas/render-policy.schema.json
sed -i \
  -e 's#schemas/svg-render-policy.schema.json#schemas/render-policy.schema.json#' \
  -e 's#"svg_render_policy_schema_version"#"render_policy_schema_version"#' \
  -e 's#"svg-render-policy.schema.v1"#"render-policy.schema.v1"#' \
  schemas/render-policy.schema.json
```

- [ ] **Step 2: Rename the contracts constant**

In `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`, replace the line:

```java
    public static final String SVG_RENDER_POLICY_SCHEMA_VERSION = "svg-render-policy.schema.v1";
```

with:

```java
    public static final String RENDER_POLICY_SCHEMA_VERSION = "render-policy.schema.v1";
```

- [ ] **Step 3: Rename the `RenderPolicy` record field**

In `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`, rename the first component:

```java
public record RenderPolicy(
        String renderPolicySchemaVersion,
        String semanticProfile,
        Page page,
        Margin margin,
        SvgStylePolicy style,
        String interactive) {
}
```

- [ ] **Step 4: Update the policy fixtures' discriminator field**

```bash
cd /home/souroldgeezer/repos/dediren
sed -i \
  -e 's#"svg_render_policy_schema_version": *"svg-render-policy.schema.v1"#"render_policy_schema_version": "render-policy.schema.v1"#' \
  fixtures/render-policy/*.json
```

Verify all 5 (`default-svg.json`, `uml-svg.json`, `rich-svg.json`, `archimate-svg.json`, `interactive-svg.json`) now carry `render_policy_schema_version`.

- [ ] **Step 5: Update the test references**

```bash
cd /home/souroldgeezer/repos/dediren
grep -rln 'SVG_RENDER_POLICY_SCHEMA_VERSION\|svgRenderPolicySchemaVersion\|svg_render_policy_schema_version\|svg-render-policy' \
  contracts/src/test plugins/render/src/test | \
  xargs --no-run-if-empty sed -i \
  -e 's/SVG_RENDER_POLICY_SCHEMA_VERSION/RENDER_POLICY_SCHEMA_VERSION/g' \
  -e 's/svgRenderPolicySchemaVersion/renderPolicySchemaVersion/g' \
  -e 's/svg_render_policy_schema_version/render_policy_schema_version/g' \
  -e 's/svg-render-policy\.schema\.v1/render-policy.schema.v1/g'
```

Also fix any manual `new RenderPolicy(...)` constructions if a test passed the version positionally — the argument value changes from `"svg-render-policy.schema.v1"` to `"render-policy.schema.v1"` (the position is unchanged). Find them:

```bash
cd /home/souroldgeezer/repos/dediren
grep -rn 'new RenderPolicy(' contracts/src plugins/render/src core/src cli/src
```

- [ ] **Step 6: Verify contracts and plugin tests pass**

Run (sandbox disabled):
```bash
./mvnw -pl contracts -am test
./mvnw -pl plugins/render -am test
```
Expected: BUILD SUCCESS for both. A render against a fixture whose field is still the old name would now fail to deserialize (`FAIL_ON_UNKNOWN_PROPERTIES`); confirm no fixture or test still uses `svg_render_policy_schema_version`.

- [ ] **Step 7: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add schemas/render-policy.schema.json contracts fixtures/render-policy plugins/render/src/test
git commit -m "feat(contracts)!: rename svg-render-policy schema family to render-policy.schema.v1"
```

---

### Task 4: `render-result.schema.v3` — add `png` kind and `encoding` field

**Files:**
- Modify: `schemas/render-result.schema.json`
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/RenderArtifact.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/render/RenderArtifactTest.java` (create if absent) and `ContractRoundTripTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `render-result.schema.v3`; `ContractVersions.RENDER_RESULT_SCHEMA_VERSION = "render-result.schema.v3"`; `RenderArtifact(String artifactKind, String content, String encoding)` (canonical) plus convenience `RenderArtifact(String artifactKind, String content)` defaulting `encoding` to `null` (omitted on serialize). `encoding` is `"base64"` for PNG.

- [ ] **Step 1: Write the failing contract test for the PNG artifact shape**

Create `contracts/src/test/java/dev/dediren/contracts/render/RenderArtifactTest.java`:

```java
package dev.dediren.contracts.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class RenderArtifactTest {
    @Test
    void textArtifactOmitsEncoding() throws Exception {
        String json = JsonSupport.objectMapper().writeValueAsString(new RenderArtifact("svg", "<svg/>"));
        assertThat(json).contains("\"artifact_kind\":\"svg\"");
        assertThat(json).doesNotContain("encoding");
    }

    @Test
    void pngArtifactCarriesBase64Encoding() throws Exception {
        RenderArtifact png = new RenderArtifact("png", "aGVsbG8=", "base64");
        String json = JsonSupport.objectMapper().writeValueAsString(png);
        assertThat(json).contains("\"artifact_kind\":\"png\"");
        assertThat(json).contains("\"encoding\":\"base64\"");
        RenderArtifact round = JsonSupport.objectMapper().readValue(json, RenderArtifact.class);
        assertThat(round.encoding()).isEqualTo("base64");
        assertThat(round.content()).isEqualTo("aGVsbG8=");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (sandbox disabled): `./mvnw -pl contracts -am test -Dtest=RenderArtifactTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `RenderArtifact` has no 3-arg constructor / no `encoding()` accessor.

- [ ] **Step 3: Add the `encoding` component to `RenderArtifact`**

Replace `contracts/src/main/java/dev/dediren/contracts/render/RenderArtifact.java`:

```java
package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonInclude;

public record RenderArtifact(
        String artifactKind,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) String encoding) {
    public RenderArtifact(String artifactKind, String content) {
        this(artifactKind, content, null);
    }
}
```

- [ ] **Step 4: Bump the render-result schema and constant**

In `schemas/render-result.schema.json`:
- `"render_result_schema_version": { "const": "render-result.schema.v2" }` → `"render-result.schema.v3"`
- `"artifact_kind": { "enum": ["svg", "html"] }` → `"enum": ["svg", "html", "png"]`
- Add an optional `encoding` property to the artifact item (alongside `artifact_kind`/`content`):

```json
          "artifact_kind": { "enum": ["svg", "html", "png"] },
          "content": { "type": "string" },
          "encoding": { "enum": ["utf-8", "base64"] }
```

In `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`:

```java
    public static final String RENDER_RESULT_SCHEMA_VERSION = "render-result.schema.v3";
```

- [ ] **Step 5: Run the tests to verify they pass**

Run (sandbox disabled): `./mvnw -pl contracts -am test`
Expected: PASS, including `RenderArtifactTest`. Update `ContractRoundTripTest` / `ContractVersionsTest` if they assert the literal `render-result.schema.v2` — change the expected value to `render-result.schema.v3`.

- [ ] **Step 6: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add schemas/render-result.schema.json contracts
git commit -m "feat(contracts)!: add png artifact_kind and encoding to render-result.schema.v3"
```

---

### Task 5: Add the `raster` policy block (contract + schema + validation)

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/render/RasterPolicy.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`
- Modify: `schemas/render-policy.schema.json`
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/RenderInputValidator.java`
- Test: `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`

**Interfaces:**
- Consumes: `RenderPolicy` (Task 3).
- Produces: `RasterPolicy(Double scale, String background)`; `RenderPolicy.raster()` returning `RasterPolicy` (nullable); validator rejects `scale <= 0` or `scale > 8` with `PolicyValidationException` at path `raster.scale`.

- [ ] **Step 1: Write the failing validator test for raster scale bounds**

Add to `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java` (a focused test; adjust the helper that builds a policy JSON string to your existing test style):

```java
    @Test
    void rejectsRasterScaleAboveMax() throws Exception {
        String policy = """
            {"render_policy_schema_version":"render-policy.schema.v1",
             "page":{"width":800,"height":600},
             "margin":{"top":0,"right":0,"bottom":0,"left":0},
             "raster":{"scale":99}}""";
        String stdin = renderInputJson(MINIMAL_LAYOUT, null, policy);
        PluginResult result = Main.executeForTesting(new String[] {"render"}, stdin);
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).contains("DEDIREN_SVG_POLICY_INVALID");
        assertThat(result.stdout()).contains("raster.scale");
    }
```

(Use the existing minimal-layout and `renderInputJson(...)` helpers in `MainTest`; if their names differ, match the file's conventions.)

- [ ] **Step 2: Run the test to verify it fails**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test -Dtest=MainTest#rejectsRasterScaleAboveMax -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `raster` is an unknown property (`FAIL_ON_UNKNOWN_PROPERTIES`) or the bound is not enforced.

- [ ] **Step 3: Add the `RasterPolicy` record**

Create `contracts/src/main/java/dev/dediren/contracts/render/RasterPolicy.java`:

```java
package dev.dediren.contracts.render;

public record RasterPolicy(Double scale, String background) {
}
```

- [ ] **Step 4: Add `raster` to `RenderPolicy`**

In `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`, append the `raster` component:

```java
public record RenderPolicy(
        String renderPolicySchemaVersion,
        String semanticProfile,
        Page page,
        Margin margin,
        SvgStylePolicy style,
        String interactive,
        RasterPolicy raster) {
}
```

Find and update existing positional constructions:

```bash
cd /home/souroldgeezer/repos/dediren
grep -rn 'new RenderPolicy(' contracts/src plugins/render/src core/src cli/src
```

For each, add a trailing `, null` (no raster) unless the test specifically exercises raster.

- [ ] **Step 5: Add the `raster` block to the policy schema**

In `schemas/render-policy.schema.json`, add to the top-level `properties` (after `style`):

```json
    "raster": { "$ref": "#/$defs/raster" }
```

and add to `$defs`:

```json
    "raster": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "scale": { "type": "number", "exclusiveMinimum": 0, "maximum": 8 },
        "background": { "$ref": "#/$defs/color" }
      }
    }
```

- [ ] **Step 6: Enforce scale bounds in the validator**

In `RenderInputValidator.validateRenderPolicy(...)`, before the `SvgStylePolicy style = policy.style();` line, add:

```java
        RasterPolicy raster = policy.raster();
        if (raster != null && raster.scale() != null) {
            double scale = raster.scale();
            if (scale <= 0 || scale > 8) {
                throw new PolicyValidationException(
                        "raster.scale", "render policy raster.scale must be greater than 0 and at most 8");
            }
        }
```

Add the import `import dev.dediren.contracts.render.RasterPolicy;` to the validator.

- [ ] **Step 7: Run the tests to verify they pass**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test`
Expected: PASS, including `rejectsRasterScaleAboveMax`.

- [ ] **Step 8: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add contracts schemas/render-policy.schema.json plugins/render/src
git commit -m "feat(render): add raster policy block with scale and background"
```

---

### Task 6: Rasterize SVG to PNG with Batik and emit the `png` artifact

**Files:**
- Modify: root `pom.xml` (`<dependencyManagement>`: pin Batik version)
- Modify: `plugins/render/pom.xml` (add `batik-transcoder`, `batik-codec`)
- Create: `plugins/render/src/main/java/dev/dediren/plugins/render/SvgRasterizer.java`
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java` (emit png when policy has `raster`)
- Test: `plugins/render/src/test/java/dev/dediren/plugins/render/SvgRasterizerTest.java` (create) and `MainTest.java`

**Interfaces:**
- Consumes: `RasterPolicy` (Task 5), `RenderArtifact(kind, content, encoding)` (Task 4).
- Produces: `SvgRasterizer.toPngBase64(String svg, RasterPolicy raster)` returning a base64 PNG `String`; `Main` appends `new RenderArtifact("png", base64, "base64")` after the SVG artifact whenever `policy.raster() != null`.

- [ ] **Step 1: Pin Batik in root dependencyManagement and declare it in the plugin**

In root `pom.xml`, inside `<dependencyManagement><dependencies>`, add:

```xml
      <dependency>
        <groupId>org.apache.xmlgraphics</groupId>
        <artifactId>batik-transcoder</artifactId>
        <version>1.17</version>
      </dependency>
      <dependency>
        <groupId>org.apache.xmlgraphics</groupId>
        <artifactId>batik-codec</artifactId>
        <version>1.17</version>
      </dependency>
```

In `plugins/render/pom.xml`, add to `<dependencies>` (before the test-scoped ones):

```xml
    <dependency>
      <groupId>org.apache.xmlgraphics</groupId>
      <artifactId>batik-transcoder</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.xmlgraphics</groupId>
      <artifactId>batik-codec</artifactId>
    </dependency>
```

(`batik-codec` provides the PNG image writer that `PNGTranscoder` needs at runtime.)

- [ ] **Step 2: Write the failing rasterizer test**

Create `plugins/render/src/test/java/dev/dediren/plugins/render/SvgRasterizerTest.java`:

```java
package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.render.RasterPolicy;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SvgRasterizerTest {
    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"40\" viewBox=\"0 0 100 40\">"
            + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"40\" fill=\"#ffffff\"/></svg>\n";

    @Test
    void producesPngOfIntrinsicSizeAtScaleOne() throws Exception {
        byte[] png = Base64.getDecoder().decode(SvgRasterizer.toPngBase64(SVG, new RasterPolicy(null, null)));
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(100);
        assertThat(image.getHeight()).isEqualTo(40);
    }

    @Test
    void scalesDimensionsByScaleFactor() throws Exception {
        byte[] png = Base64.getDecoder().decode(SvgRasterizer.toPngBase64(SVG, new RasterPolicy(2.0, null)));
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(80);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test -Dtest=SvgRasterizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `SvgRasterizer` does not exist.

- [ ] **Step 4: Implement `SvgRasterizer`**

Create `plugins/render/src/main/java/dev/dediren/plugins/render/SvgRasterizer.java`:

```java
package dev.dediren.plugins.render;

import dev.dediren.contracts.render.RasterPolicy;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

final class SvgRasterizer {
    private static final Pattern WIDTH = Pattern.compile("<svg[^>]*\\bwidth=\"([0-9.]+)\"");
    private static final Pattern HEIGHT = Pattern.compile("<svg[^>]*\\bheight=\"([0-9.]+)\"");

    private SvgRasterizer() {
    }

    static String toPngBase64(String svg, RasterPolicy raster) {
        double scale = raster == null || raster.scale() == null ? 1.0 : raster.scale();
        float width = (float) (intrinsic(WIDTH, svg) * scale);
        float height = (float) (intrinsic(HEIGHT, svg) * scale);

        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height);
        if (raster != null && raster.background() != null) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, parseColor(raster.background()));
        }

        var output = new ByteArrayOutputStream();
        try {
            transcoder.transcode(
                    new TranscoderInput(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))),
                    new TranscoderOutput(output));
        } catch (org.apache.batik.transcoder.TranscoderException error) {
            throw new RasterizationException(error);
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static double intrinsic(Pattern pattern, String svg) {
        Matcher matcher = pattern.matcher(svg);
        if (!matcher.find()) {
            throw new RasterizationException("svg root is missing an intrinsic size attribute");
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static Color parseColor(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return new Color(rgb, false);
    }

    static final class RasterizationException extends RuntimeException {
        RasterizationException(String message) {
            super(message);
        }

        RasterizationException(Throwable cause) {
            super(cause);
        }
    }
}
```

- [ ] **Step 5: Run the rasterizer test to verify it passes**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test -Dtest=SvgRasterizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — both dimensions and PNG magic bytes match.

- [ ] **Step 6: Write the failing `Main` test for png emission**

Add to `MainTest.java`:

```java
    @Test
    void emitsPngArtifactWhenRasterRequested() throws Exception {
        String policy = """
            {"render_policy_schema_version":"render-policy.schema.v1",
             "page":{"width":800,"height":600},
             "margin":{"top":0,"right":0,"bottom":0,"left":0},
             "raster":{"scale":1}}""";
        String stdin = renderInputJson(MINIMAL_LAYOUT, null, policy);
        PluginResult result = Main.executeForTesting(new String[] {"render"}, stdin);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("\"artifact_kind\":\"png\"");
        assertThat(result.stdout()).contains("\"encoding\":\"base64\"");
    }

    @Test
    void omitsPngArtifactWithoutRaster() throws Exception {
        String policy = """
            {"render_policy_schema_version":"render-policy.schema.v1",
             "page":{"width":800,"height":600},
             "margin":{"top":0,"right":0,"bottom":0,"left":0}}""";
        String stdin = renderInputJson(MINIMAL_LAYOUT, null, policy);
        PluginResult result = Main.executeForTesting(new String[] {"render"}, stdin);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).doesNotContain("\"artifact_kind\":\"png\"");
    }
```

- [ ] **Step 7: Run to verify the new `Main` tests fail**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test -Dtest=MainTest#emitsPngArtifactWhenRasterRequested+omitsPngArtifactWithoutRaster -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — png artifact is never emitted.

- [ ] **Step 8: Wire PNG emission into `Main.renderFromStdin`**

In `Main.java`, change `renderFromStdin` so it appends a png artifact when the policy has a `raster` block. Replace the artifact-building block:

```java
        String svg = renderSvg(input.layoutResult(), input.renderMetadata(), input.policy());
        List<RenderArtifact> artifacts = new ArrayList<>(buildArtifacts(interactiveMode(input.policy()), svg));
        if (input.policy().raster() != null) {
            String pngBase64 = SvgRasterizer.toPngBase64(svg, input.policy().raster());
            artifacts.add(new RenderArtifact("png", pngBase64, "base64"));
        }
        var result = new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts);
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
        return 0;
```

`ArrayList` and `List` are already imported. Catch rasterization failure as a structured diagnostic: wrap the `SvgRasterizer.toPngBase64` call so a `SvgRasterizer.RasterizationException` becomes `exitWithDiagnostic(stdout, "DEDIREN_SVG_RASTERIZE_FAILED", error.getMessage(), "raster")` returning exit 3. Add that catch around the rasterize call:

```java
        if (input.policy().raster() != null) {
            try {
                artifacts.add(new RenderArtifact("png", SvgRasterizer.toPngBase64(svg, input.policy().raster()), "base64"));
            } catch (SvgRasterizer.RasterizationException error) {
                return exitWithDiagnostic(stdout, "DEDIREN_SVG_RASTERIZE_FAILED", error.getMessage(), "raster");
            }
        }
```

- [ ] **Step 9: Run the full plugin test suite**

Run (sandbox disabled): `./mvnw -pl plugins/render -am test`
Expected: PASS, including the two new `Main` tests and `SvgRasterizerTest`. PNG rasterization for the UML sequence path also works (it produces a sized `<svg>` root).

- [ ] **Step 10: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add pom.xml plugins/render/pom.xml plugins/render/src
git commit -m "feat(render): rasterize svg to png via batik when raster policy is set"
```

---

### Task 7: Update user-facing and agent docs, then verify end to end

**Files:**
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: `docs/features/distribution-and-runtime.md`, `docs/features/plugin-runtime.md`
- Verify: `dist-tool/src/test/java/dev/dediren/tools/dist/AgentUsageDocConsistencyTest.java` stays green

**Interfaces:**
- Consumes: all renamed names and the new png/raster behavior.
- Produces: docs consistent with source (token `DEDIREN_PLUGIN_RENDER`, plugin id `render`, version `2026.06.6`).

- [ ] **Step 1: Update README command/token/path references**

In `README.md` replace, reviewing each hit for context:
- `--plugin svg-render` → `--plugin render`
- `dediren-plugin-svg-render` → `dediren-plugin-render`
- `DEDIREN_PLUGIN_SVG_RENDER` → `DEDIREN_PLUGIN_RENDER`
- `svg_render_policy_schema_version` → `render_policy_schema_version` and `svg-render-policy.schema.v1` → `render-policy.schema.v1` in any inline policy example

```bash
cd /home/souroldgeezer/repos/dediren
sed -i \
  -e 's#--plugin svg-render#--plugin render#g' \
  -e 's#dediren-plugin-svg-render#dediren-plugin-render#g' \
  -e 's#DEDIREN_PLUGIN_SVG_RENDER#DEDIREN_PLUGIN_RENDER#g' \
  -e 's#svg_render_policy_schema_version#render_policy_schema_version#g' \
  -e 's#svg-render-policy\.schema\.v1#render-policy.schema.v1#g' \
  README.md
```

- [ ] **Step 2: Add a PNG example to the README render section**

After the existing `jq ... select(.artifact_kind=="svg") ... > diagram.svg` example, add a PNG variant showing the `raster` policy and base64 decode:

```markdown
To also get a PNG, add a `raster` block to the render policy
(`"raster": { "scale": 2 }`) and decode the base64 `png` artifact:

\```bash
jq -r '.data.artifacts[] | select(.artifact_kind=="png") | .content' render-result.json \
  | base64 -d > diagram.png
\```
```

(Use real backticks in the file; the escaped fences above are only for this plan.)

- [ ] **Step 3: Update `docs/agent-usage.md`**

```bash
cd /home/souroldgeezer/repos/dediren
sed -i \
  -e 's#--plugin svg-render#--plugin render#g' \
  -e 's#dediren-plugin-svg-render#dediren-plugin-render#g' \
  -e 's#DEDIREN_PLUGIN_SVG_RENDER#DEDIREN_PLUGIN_RENDER#g' \
  -e 's#svg_render_policy_schema_version#render_policy_schema_version#g' \
  -e 's#svg-render-policy\.schema\.v1#render-policy.schema.v1#g' \
  docs/agent-usage.md
```

Then add a one-line note that the `render` plugin emits a base64 `png` artifact (`encoding: base64`) when the policy includes a `raster` block, decoded with `base64 -d`. Keep every CalVer string at `2026.06.6`.

- [ ] **Step 4: Update the feature docs' env-token examples**

```bash
cd /home/souroldgeezer/repos/dediren
sed -i 's#DEDIREN_PLUGIN_SVG_RENDER#DEDIREN_PLUGIN_RENDER#g' \
  docs/features/distribution-and-runtime.md docs/features/plugin-runtime.md
```

- [ ] **Step 5: Confirm no stale `svg-render` references remain in live surfaces**

```bash
cd /home/souroldgeezer/repos/dediren
grep -rn 'svg-render\|svgrender\|DEDIREN_PLUGIN_SVG_RENDER\|svg_render_policy' \
  --include=*.java --include=*.json --include=*.md --include=*.xml . \
  | grep -v '/target/' | grep -v 'docs/superpowers/plans/'
```
Expected: no matches (plans are intentionally excluded). Investigate and fix any that appear in live source/docs.

- [ ] **Step 6: Run the consistency test, full suite, and dist smoke**

Run (sandbox disabled):
```bash
./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```
Expected: all green; `AgentUsageDocConsistencyTest` confirms `DEDIREN_PLUGIN_RENDER` resolves and versions match; dist-smoke launches `dediren-plugin-render` and renders via `--plugin render`.

- [ ] **Step 7: Commit**

```bash
cd /home/souroldgeezer/repos/dediren
git add README.md docs/agent-usage.md docs/features
git commit -m "docs(render): document render plugin rename and png output"
```

---

## Audit Gates

This work is a plugin-runtime + contract change. Before calling it complete, run:
- `souroldgeezer-audit:test-quality-audit` — deep on the new rasterizer/policy/render tests and the rename regression tests.
- `souroldgeezer-audit:devsecops-audit` — quick on the new Batik dependency (supply-chain posture, transitive footprint) and the plugin process boundary.

Fix block findings; fix or explicitly accept warn/info findings in the handoff.

## Self-Review Notes

- Spec §"Rename map" → Tasks 1, 2, 7. §"Policy schema" → Task 3 + Task 5 (`raster`). §"render-result schema" → Task 4. §"Rasterization" → Task 6. §"Testing" → tests in Tasks 4–6 + Task 7 verification. §"Files that move together" → grouped commits per task.
- Type consistency: `RenderArtifact(kind, content, encoding)` (Task 4) is used by `Main` in Task 6; `RasterPolicy(Double scale, String background)` (Task 5) is consumed by `SvgRasterizer.toPngBase64` (Task 6) and `RenderPolicy.raster()` (Task 5); `render_policy_schema_version` / `render-policy.schema.v1` are used consistently across Tasks 3, 5, 7.
- Decision recorded: `DEDIREN_SVG_POLICY_INVALID` and the new `DEDIREN_SVG_RASTERIZE_FAILED` keep the `DEDIREN_SVG_` prefix; the rename targets plugin identity, not diagnostic-code identity.

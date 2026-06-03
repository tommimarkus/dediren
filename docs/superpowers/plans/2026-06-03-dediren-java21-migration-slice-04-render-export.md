# Dediren Java 21 Migration Slice 04: Render And Export Plugins

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Validate generated visual and XML artifacts against current fixtures.

**Goal:** Port SVG rendering, ArchiMate OEF export, and UML XMI export to Java first-party plugins.

**Architecture:** Rendering policy stays in SVG render plugin/config. ArchiMate/OEF semantics stay in the ArchiMate OEF export plugin. UML/XMI semantics stay in the UML XMI export plugin. Core only invokes plugins and validates envelopes.

**Tech Stack:** Java 21, Jackson, JUnit 5, AssertJ, XMLUnit, JAXP, checked-in schemas, OEF/XMI schema cache support.

---

## Files

- Create: `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/*.java`
- Create: `modules/plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/*Test.java`
- Create: `modules/plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/*.java`
- Create: `modules/plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/*Test.java`
- Create: `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/*.java`
- Create: `modules/plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/*Test.java`
- Read parity source: `crates/dediren-plugin-svg-render`
- Read parity source: `crates/dediren-plugin-archimate-oef-export`
- Read parity source: `crates/dediren-plugin-uml-xmi-export`
- Read parity tests: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Read parity tests: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/*.rs`
- Read parity tests: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- Read parity tests: `crates/dediren-plugin-uml-xmi-export/tests/uml_xmi_export_plugin.rs`
- Read parity tests: `crates/dediren-cli/tests/cli_render.rs`
- Read parity tests: `crates/dediren-cli/tests/cli_export.rs`

## Tasks

- [ ] **Task 1: Port SVG render policy parsing**
  - Model: `gpt-5-codex`
  - Add Java records/helpers for render policy inputs where contracts do not already cover behavior.
  - Preserve policy validation for unsafe colors, unknown decorators, profile mismatch, type overrides, group styles, and rich styles.
  - Add tests equivalent to `render_contracts` sections in Rust SVG tests.
  - Run: `./gradlew :modules:plugins:svg-render:test --tests '*RenderContract*'`
  - Expected: pass.

- [ ] **Task 2: Port SVG node, edge, label, and viewbox rendering**
  - Model: `gpt-5-codex`
  - Preserve ArchiMate decorators, UML symbols, edge markers, edge labels, shared endpoint route behavior, line jumps, and viewbox cropping/expansion.
  - Add tests equivalent to every Rust SVG test module: `archimate_groups`, `archimate_nodes`, `archimate_relationships`, `edge_labels`, `render_contracts`, `uml_activity`, `uml_nodes`, `uml_relationships`, and `viewbox_routes`.
  - Expected: generated SVG is stable enough for current tests and remains valid XML.

- [x] **Task 3: Port ArchiMate OEF export**
  - Model: `gpt-5-codex`
  - Generate ArchiMate 3.2 OEF XML from source, policy, and generated layout results.
  - Preserve geometry rounding, semantic grouping behavior, junction handling, endpoint validation, and official schema validation.
  - Add tests equivalent to `oef_export_plugin.rs`.
  - Run: `./gradlew :modules:plugins:archimate-oef-export:test`
  - Expected: pass.

### 2026-06-03 OEF Checkpoint

Implemented Java OEF export in `modules/plugins/archimate-oef-export`:

- `capabilities` with official OEF schema validation runtime metadata.
- `export` command envelope output for ArchiMate OEF XML.
- ArchiMate type, relationship endpoint, junction, and group-source validation.
- Generated identifiers, geometry rounding, semantic grouping view nodes, relationship connector/junction nodes, and containment-through-junction handling.
- Offline Java tests with injected minimal OEF XSDs plus xmllint validation.
- Schema-cache helper wiring for `DEDIREN_OEF_SCHEMA_DIR`, `DEDIREN_SCHEMA_CACHE_DIR`, and curl-backed runtime downloads.

Verification:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:plugins:archimate-oef-export:test
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked
git diff --check
```

Result: all passed.

- [ ] **Task 4: Port UML XMI export**
  - Model: `gpt-5-codex`
  - Generate UML 2.5.1 XMI XML for the current class, data, and activity slice.
  - Preserve scoped model export, id collision deduplication, relationship endpoint validation, and XML id checks.
  - Add tests equivalent to `uml_xmi_export_plugin.rs`.
  - Run: `./gradlew :modules:plugins:uml-xmi-export:test`
  - Expected: pass.

- [ ] **Task 5: Add CLI integration parity**
  - Model: `gpt-5-codex`
  - Run Java CLI against Java plugins for render and export workflows.
  - Preserve stdout envelope shapes and non-zero structured error handling.
  - Add tests equivalent to `cli_render.rs` and `cli_export.rs`.
  - Expected: pass.

- [ ] **Task 6: Add render/export Rust coverage note**
  - Model: `gpt-5-mini`
  - In the slice handoff, map Rust SVG/OEF/XMI source files, test files, render policies, export policies, layout-result fixtures, and render-metadata fixtures to their Java successor tests.
  - Expected: no render/export Rust test module remains without a named Java successor.

- [ ] **Task 7: Run architecture and test audits**
  - Model: `gpt-5`
  - Use `souroldgeezer-architecture:architecture-design` Review for ArchiMate/OEF/UML evidence and render/export readiness.
  - Use `souroldgeezer-audit:test-quality-audit` Deep for SVG/OEF/XMI plugin tests.
  - Use `souroldgeezer-design:software-design` Review for plugin ownership and duplication risks.
  - Fix block findings before commit.

- [ ] **Task 8: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew :modules:plugins:svg-render:test :modules:plugins:archimate-oef-export:test :modules:plugins:uml-xmi-export:test :apps:cli:test`
  - Run: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked`
  - Run: `cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked`
  - Run: `cargo test -p dediren-plugin-uml-xmi-export --test uml_xmi_export_plugin --locked`
  - Run: `git diff --check`
  - Commit message: `feat: port render and export plugins to java`

# Dediren Test Quality Gap Matrix

Date: 2026-05-12

## Scope

This matrix records the post-cleanup test confidence for the test-quality cleanup series. It is not a product contract and does not change user-facing behavior.

## Matrix

| Area | Primary Tests | Confidence After Cleanup | Remaining Gap | Action |
| --- | --- | --- | --- | --- |
| CLI command envelopes | `crates/dediren-cli/tests/cli_*.rs` | Parsed JSON envelope assertions cover status, data, diagnostics, and plugin boundary failures. | Pipeline tests remain smoke-level by design. | Keep narrow CLI tests authoritative. |
| CLI fixture pipeline | `crates/dediren-cli/tests/cli_pipeline.rs::fixture_*` | Deterministic fixture-backed tests cover CLI command wiring across project, layout fixture input, render, OEF export, and ArchiMate node/relationship notation through projected metadata. | Fixture layout does not prove ELK geometry quality and is intentionally not the render-confidence lane. | Keep this lane lean, but preserve default ArchiMate node and relationship render checks; add broad render confidence only under `real_elk_render.rs`. |
| ELK fixture adapter lane | `crates/dediren-cli/tests/cli_layout.rs`, `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs` | Fixture and fake-command tests prove wrapper behavior, envelope normalization, and no-Java failure handling. | This lane cannot prove grouped routing quality or final render quality. | Do not expand fixture coverage as a substitute for real helper evidence. |
| Real ELK render lane | `crates/dediren-cli/tests/real_elk_render.rs::real_elk_*`, renamed real helper tests in `cli_layout.rs` and `elk_layout_plugin.rs` | Dedicated ignored tests run source or inline layout requests through the Java helper, validate layout quality, render SVG, and write artifacts under `.test-output/renders/real-elk/`. Basic, grouped-rich, and cross-group route cases require clean layout quality; the richer ArchiMate metadata case intentionally pins one known connector-through-node warning while proving semantic notation over real ELK output. | Opt-in because SDKMAN/Gradle helper setup is required; tests should be run serially and include an in-binary mutex for the new render suite. The accepted ArchiMate route warning remains debt until the real helper or source/layout intent produces a clean rich ArchiMate route. | Run for ELK changes, render quality work, release checks, and suspected layout regressions. Do not substitute fixture results for this lane. |
| Generic graph plugin | `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs` | Parsed plugin envelopes cover projection output and ArchiMate validation failures. | Tests still use fixture-scale graphs. | Add larger graph fixture only when projection logic changes. |
| OEF export plugin | `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs` | Parsed envelopes plus XML parsing cover success and ArchiMate failures. | XML assertions are structural, not visual. | Keep OEF semantics under architecture review when export behavior changes. |
| SVG renderer plugin | `crates/dediren-plugin-svg-render/tests/svg_render_plugin/*.rs` | Split modules cover render contracts, ArchiMate nodes, relationships, labels, viewBox, and routes. | The suite is still integration-heavy. | Add smaller unit tests only when renderer internals become stable enough to expose. |
| ArchiMate relationship rules | `crates/dediren-archimate/tests/relationship_rules.rs` | Curated oracle cases plus rule-table invariants cover drift and validator consistency. | The test does not reproduce the full licensed standard table. | Verify against official ArchiMate 3.2 source before rule-data changes. |
| Java ELK helper | `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk` | Parsed JSON assertions cover helper envelopes and deterministic diagnostics. | Real helper behavior still depends on Java/Gradle availability. | Use the existing helper build script for ELK changes and release checks. |

## ELK Lane Vocabulary

- `real_elk_*`: invokes the Java ELK helper through `DEDIREN_ELK_COMMAND`.
- `fixture_elk_*` or `fixture_pipeline_*`: consumes static layout fixtures or `DEDIREN_ELK_RESULT_FIXTURE`.
- `fake_elk_*`: uses a shell helper to simulate external runtime envelopes or failures.
- ArchiMate node/relationship fixture tests: deterministic semantic matrix coverage, intentionally retained as fixture-based tests.
- `.test-output/renders/real-elk/*.svg`: real helper render artifacts.
- `.test-output/renders/fixture-pipeline/*.svg`: fixture-backed CLI pipeline artifacts, including deterministic ArchiMate node and relationship render notation.
- `.test-output/renders/svg-render-plugin/*.svg`: renderer policy artifacts from static layout inputs, not ELK geometry evidence.

## Accepted Residual Risk

- The broad workspace suite still has expensive integration tests; keep narrow package tests as the first signal during development.
- Fixture-backed ELK tests are intentionally lean and are not accepted as evidence of real layout/render quality.
- Generated render artifacts remain untracked; report their paths when useful rather than committing them.
- The ignored real-ELK Rust lane remains opt-in because it requires the SDKMAN/Gradle helper setup documented in `AGENTS.md`; if it cannot run, record the exact blocker instead of replacing it with fixture results.

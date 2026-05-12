# Dediren Test Quality Gap Matrix

Date: 2026-05-12

## Scope

This matrix records the post-cleanup test confidence for the test-quality cleanup series. It is not a product contract and does not change user-facing behavior.

## Matrix

| Area | Primary Tests | Confidence After Cleanup | Remaining Gap | Action |
| --- | --- | --- | --- | --- |
| CLI command envelopes | `crates/dediren-cli/tests/cli_*.rs` | Parsed JSON envelope assertions cover status, data, diagnostics, and plugin boundary failures. | Pipeline tests remain smoke-level by design. | Keep narrow CLI tests authoritative. |
| CLI fixture pipeline | `crates/dediren-cli/tests/cli_pipeline.rs` non-ignored tests | Shared binary setup and SVG XML assertions cover fixture-backed happy paths. | Fixture-backed layout does not prove ELK geometry quality. | Keep this lane lean and deterministic. |
| ELK fixture adapter lane | `crates/dediren-cli/tests/cli_layout.rs`, `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs` | Fixture and fake-command tests prove wrapper behavior, envelope normalization, and no-Java failure handling. | This lane cannot prove grouped routing quality or final render quality. | Do not expand fixture coverage as a substitute for real helper evidence. |
| Real ELK render lane | `crates/dediren-cli/tests/cli_pipeline.rs::real_elk_pipeline_renders_rich_source`, ignored real-helper layout tests | Real helper output is validated by `validate-layout` and rendered to SVG for artifact inspection. | Opt-in because SDKMAN/Gradle helper setup is required and real helper runs must stay serial. | Run serially for this cleanup closeout, ELK changes, release checks, and suspected layout regressions. |
| Generic graph plugin | `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs` | Parsed plugin envelopes cover projection output and ArchiMate validation failures. | Tests still use fixture-scale graphs. | Add larger graph fixture only when projection logic changes. |
| OEF export plugin | `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs` | Parsed envelopes plus XML parsing cover success and ArchiMate failures. | XML assertions are structural, not visual. | Keep OEF semantics under architecture review when export behavior changes. |
| SVG renderer plugin | `crates/dediren-plugin-svg-render/tests/svg_render_plugin/*.rs` | Split modules cover render contracts, ArchiMate nodes, relationships, labels, viewBox, and routes. | The suite is still integration-heavy. | Add smaller unit tests only when renderer internals become stable enough to expose. |
| ArchiMate relationship rules | `crates/dediren-archimate/tests/relationship_rules.rs` | Curated oracle cases plus rule-table invariants cover drift and validator consistency. | The test does not reproduce the full licensed standard table. | Verify against official ArchiMate 3.2 source before rule-data changes. |
| Java ELK helper | `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk` | Parsed JSON assertions cover helper envelopes and deterministic diagnostics. | Real helper behavior still depends on Java/Gradle availability. | Use the existing helper build script for ELK changes and release checks. |

## Accepted Residual Risk

- The broad workspace suite still has expensive integration tests; keep narrow package tests as the first signal during development.
- Fixture-backed ELK tests are intentionally lean and are not accepted as evidence of real layout/render quality.
- Generated render artifacts remain untracked; report their paths when useful rather than committing them.
- The ignored real-ELK Rust lane remains opt-in because it requires the SDKMAN/Gradle helper setup documented in `AGENTS.md`; if it cannot run, record the exact blocker instead of replacing it with fixture results.

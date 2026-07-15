# Recorded deferrals (2026-07-15)

Status: open — parked items with no other in-repo record, surfaced by the
2026-07-15 coherence review. Entries are deliberate scope-cutting or newly
recorded pre-existing gaps, not a defect list; pick up individually.

- **Sequence-edge bend jitter** (visual-defect suite, 2026-07 era): minor
  route-bend wobble on dense sequence diagrams; deferred when the SvgAudit
  items shipped. Start from the SvgAudit render-layer tests in
  `engines/render` and real-render evidence, per the ELK-first rule.
- **Label/lane padding polish** (same era): spacing between labels and
  lane/box edges reads tight in dense diagrams; express any fix through ELK
  spacing options, not custom geometry.

- **mcp-server branch coverage below the local gate** (found 2026-07-15,
  pre-existing): `./mvnw -Pcoverage verify` fails on `mcp-server` — branches
  covered 0.67 vs the profile's 0.70 minimum — verified identical at base
  `a75154c` and on the coherence-remediation branch, so the gap dates from the
  module's landing (the gate is local/opt-in, not in CI, so it went unnoticed).
  Missed branches concentrate in the stdio transport hardening
  (`EofSignalingInputStream` 14, `PendingRequests` 11, `DedirenTools` 13).
  Lift with targeted transport/tool-lane tests (covering ~4 more branches
  reaches 0.70) or set an explicit, commented module threshold — do not lower
  the shared gate silently.

ELK vocabulary deferrals (node/edge `priority`, `layer_choice`,
`position_choice`, alternate layout algorithms) are already recorded in
`2026-07-05-elk-node-placement-hints.md` and `2026-07-05-elk-algorithm-gate.md`
and are not duplicated here.

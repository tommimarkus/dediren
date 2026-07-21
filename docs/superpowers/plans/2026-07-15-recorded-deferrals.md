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

- **mcp-server branch coverage below the local gate** — RESOLVED 2026-07-22:
  the wave-0/wave-1 test additions (structural-envelope parity fixtures,
  policy-validate parity, `DedirenResourcesTest`) lifted branch coverage over
  the 0.70 floor; `./mvnw -pl mcp-server -am -Pcoverage verify` passes. (Was:
  0.67 at module landing, concentrated in the stdio transport hardening.)

ELK vocabulary deferrals (node/edge `priority`, `layer_choice`,
`position_choice`, alternate layout algorithms) are already recorded in
`2026-07-05-elk-node-placement-hints.md` and `2026-07-05-elk-algorithm-gate.md`
and are not duplicated here.

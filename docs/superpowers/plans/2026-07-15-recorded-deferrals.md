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

- **XMI lane migration onto the in-JVM validator** — RESOLVED 2026-07-22
  (same day): a spike proved the JDK validator against the real OMG `XMI.xsd`
  (one tolerated finding: the strict-wildcard no-UML-declaration gap, Xerces
  wording), the lane flipped to `schemacache.InJvmXmlValidator`, and the
  entire subprocess stack (`XmlSchemaValidator`, its test, the
  `DEDIREN_XMI_SCHEMA_VALIDATOR` override, `configuredValidator`) was
  deleted — `xmllint` is no longer a product dependency on any lane.
- **OEF conformance-report diagnostic** — RESOLVED 2026-07-22 (same day),
  broadened to both lanes: every successful export now carries
  `DEDIREN_EXPORT_SCHEMA_CONFORMANCE` (`info`) naming the schema validated
  against and its provenance; the XMI variant discloses when UML-namespace
  content rode the no-normative-UML-XSD gap.

ELK vocabulary deferrals (node/edge `priority`, `layer_choice`,
`position_choice`, alternate layout algorithms) are already recorded in
`2026-07-05-elk-node-placement-hints.md` and `2026-07-05-elk-algorithm-gate.md`
and are not duplicated here.

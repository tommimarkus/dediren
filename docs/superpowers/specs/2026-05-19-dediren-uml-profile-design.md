# Dediren UML Profile Design

Date: 2026-05-19

## Purpose

Add UML as the first detailed-design companion to Dediren's current
ArchiMate-level architecture support. UML should be implemented the same way
ArchiMate is implemented today: as a first-party semantic profile over the
existing Dediren source graph, with validation, layout projection, render
metadata, SVG notation, and an optional compatibility export path.

The goal is one coherent UML companion, not a fragmented set of separate ERD,
class, and process plugins. UML covers the initial detailed modeling needs:

- data model;
- class model;
- active process model.

## Primary Decisions

- UML is the named modeling standard for the detailed companion.
- UML is not a Dediren-native replacement vocabulary and not a loose visual
  style.
- UML uses the existing `generic-graph` semantic profile mechanism first:
  `plugins.generic-graph.semantic_profile = "uml"`.
- Dediren JSON remains the canonical agent-authoring format.
- UML/XMI is the OEF-like compatibility export target for UML.
- UMLDI can be added later when generated layout-to-diagram-interchange mapping
  is stable enough.
- ArchiMate and UML remain separate profiles with separate semantic validation,
  render metadata, notation policy, and export semantics.

## Product Shape

The source graph shape stays familiar:

```json
{
  "plugins": {
    "generic-graph": {
      "semantic_profile": "uml",
      "views": []
    }
  }
}
```

The UML pipeline mirrors the ArchiMate pipeline:

```text
validate --plugin generic-graph --profile uml
project --target layout-request --plugin generic-graph --view <view-id>
layout --plugin elk-layout
project --target render-metadata --plugin generic-graph --view <view-id>
render --plugin svg-render --metadata <render-metadata> --policy <uml-svg-policy>
```

The export path should mirror ArchiMate's OEF exporter with a UML-specific
export plugin:

```text
export --plugin uml-xmi
```

The initial export target is UML/XMI model interchange. UMLDI should be treated
as a later extension for diagram interchange and generated geometry.

## Initial UML Scope

The first UML slice should be bounded enough to ship as a coherent profile
without attempting all UML diagram families.

In scope:

- packages, classes, interfaces, data types, and enumerations;
- attributes, operations, parameters, and visibility;
- associations, association ends, multiplicities, qualifiers, and navigability;
- generalization, realization, dependency, composition, and aggregation;
- stereotypes and tagged values where needed for practical data modeling;
- activities, actions, object nodes, control flows, and object flows;
- initial and final nodes, decisions, merges, forks, and joins;
- activity partitions for actor, system, or lane ownership;
- pins where they are needed to show data moving through an activity.

The first view kinds are:

```text
uml-class
uml-data
uml-activity
```

Out of scope for the first slice:

- sequence diagrams;
- state machines;
- use cases;
- deployment diagrams;
- timing diagrams;
- full UML profile authoring;
- UML import from arbitrary vendor XMI.

These can be added later under the same `uml` profile if the core contract
remains stable.

## ArchiMate And UML Boundary

ArchiMate owns architecture-level meaning:

- enterprise, business, application, and technology architecture story;
- capabilities, services, components, business processes, data objects, and
  technology nodes;
- cross-domain dependencies and stakeholder-facing views;
- ArchiMate SVG notation and ArchiMate OEF export.

UML owns detailed design beneath those architecture elements:

- class and data structure;
- activity and process mechanics;
- package and module internals;
- object and data flow;
- local code-facing dependencies and behavior details.

The handover is optional upward context from UML to ArchiMate, not mandatory
two-way traceability. A UML package, view, or high-level element may declare
that it elaborates an ArchiMate element or view, but UML validity must not
require ArchiMate data.

Example:

```json
{
  "properties": {
    "uml": {
      "architecture_context": {
        "profile": "archimate",
        "element_id": "application-component-billing",
        "relationship": "elaborates"
      }
    }
  }
}
```

Boundary rule:

```text
If the question is "what is this system in the enterprise and how does it
relate?", use ArchiMate.

If the question is "what is inside this system, process, or data shape and how
does it work?", use UML.
```

## Contract Shape

UML-specific meaning lives in plugin-owned semantic data, not in Dediren core.
The core remains responsible for contracts, orchestration, plugin execution,
schema validation, command envelopes, diagnostics, layout quality validation,
and backend-neutral generated artifacts.

The UML profile owns:

- supported UML vocabulary and relationship legality;
- UML view-kind validation;
- mapping source graph selections into layout requests;
- mapping source graph selections into UML render metadata;
- UML SVG notation semantics;
- UML/XMI export semantics when the exporter is added.

Projection targets:

- `layout-request`: selected UML view becomes graph structure for ELK;
- `render-metadata`: selected UML view becomes semantic selectors for UML SVG
  notation;
- later `export-request`: source plus generated layout becomes UML/XMI output.

Render policy:

- add a first-party UML policy fixture such as
  `fixtures/render-policy/uml-svg.json`;
- keep colors, fonts, spacing, and visual styling in SVG render policy;
- keep source graph JSON free of authored geometry and presentation styling.

Export:

- add a first-party `uml-xmi` export plugin after the UML source, validation,
  projection, and SVG notation contracts settle;
- treat XMI as compatibility output, not as Dediren's source of truth;
- add UMLDI only when the generated layout mapping can be made deterministic and
  useful enough for downstream UML tools.

## Diagnostics And Errors

UML validation should follow the existing profile-owned diagnostic model.
Profile diagnostics should be deterministic and repair-oriented:

- unknown UML element or relationship type;
- relationship source or target type not legal for the selected relationship;
- invalid or malformed multiplicity;
- missing association end metadata where required;
- invalid activity flow between incompatible node kinds;
- view kind containing unsupported UML element kinds;
- unsupported export feature when the source uses UML constructs outside the
  exporter slice.

Plugin runtime failures should continue to use the existing structured envelope
behavior for missing executable, timeout, invalid JSON, schema mismatch,
unsupported capability, id mismatch, and missing runtime dependency cases.

## Implementation Slices

1. UML profile validation.
   Add `semantic_profile = "uml"` to the same first-party path that handles
   ArchiMate today. Cover the initial UML vocabulary, relationship legality,
   multiplicities, and required view-kind constraints.

2. UML projection and layout.
   Project UML views to layout requests using the existing ELK path. Keep layout
   type-agnostic; UML semantics may influence node size hints, ports, labels,
   containment, and grouping, but not core layout ownership.

3. UML render metadata and SVG notation.
   Add UML render metadata and UML SVG policy support for class compartments,
   interfaces, enumerations, association markers, inheritance and realization
   arrows, composition and aggregation diamonds, and activity notation.

4. UML/XMI export.
   Add a separate `uml-xmi` export plugin once the profile and render contract
   have settled. Start with model interchange, then add UMLDI diagram
   interchange later if the geometry mapping is strong enough.

5. Docs, fixtures, and agent workflow.
   Mirror the ArchiMate documentation and fixture shape: UML source fixture,
   render policy, layout result fixture, README examples, schema tests, plugin
   compatibility tests, and agent guidance.

## Verification Strategy

Use the narrow lane for each slice, then broaden when the change crosses public
contracts, plugins, CLI behavior, or docs.

Expected lanes:

```text
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_export
cargo test --workspace --locked
```

When the UML work changes public behavior, update the versioned release
surfaces in the same content change according to the repository versioning
rules.

## References

- OMG UML 2.5.1 specification and machine-readable UML/UMLDI documents:
  `https://www.omg.org/spec/UML`
- OMG XMI 2.5.1 specification:
  `https://www.omg.org/spec/XMI`
- OMG UML Diagram Interchange 1.0:
  `https://www.omg.org/spec/UMLDI/1.0/`

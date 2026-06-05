# Dediren UML Component Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add the next UML roadmap slice: a usable `uml-component` workflow with source validation, projection, ELK layout, SVG rendering, UML/XMI export, CLI coverage, docs, version metadata, and focused verification.

**Architecture:** Keep Dediren JSON as authored source and UML/XMI as compatibility output. Add component semantics inside `uml`, keep contracts/schema additive, reuse `generic-graph` projection and ELK layout with port-shaped nodes, render component notation through the existing SVG decorator path, and export deterministic component model content from `uml-xmi`.

**Tech Stack:** Java 21, Maven Wrapper, Jackson, JSON Schema, first-party process-boundary plugins, Eclipse ELK layered layout, SVG, UML 2.5.1 XMI.

---

## Scope Decision

This plan refines roadmap Task 5 into one executable vertical slice.

Included now:

- New view kind: `uml-component`.
- UML node types: `Component`, `Port`, and existing structural classifiers `Interface`, `Class`, `Package`.
- UML relationship types: existing `Dependency`, existing `Realization`, plus new `Usage`.
- Source conventions for component-owned ports under `properties.uml.component`, optional `Port.properties.uml.provided`, and optional `Port.properties.uml.required`.
- View convention: `Component` and `Package` frames are semantic-backed groups; component nodes and ports selected in the view render as layout nodes.
- SVG notation: component nodes with UML component glyphs, port squares, interface lollipop/socket notation where metadata is present, dependency/usage dashed arrows, realization dashed hollow-triangle arrows, and stable `data-dediren-*` attributes.
- UML/XMI export for components, owned ports, interfaces/classes in scope, dependencies, usages, and realizations.

Deferred beyond this slice:

- Separate `uml-composite-structure` view kind.
- `Connector`, `ConnectorEnd`, `ConnectorKind`, `Collaboration`, `CollaborationUse`, and nested part/property modeling.
- Full provided/required interface geometry with ELK port constraints; this slice renders port nodes first and exports owned ports deterministically.
- UMLDI and full UML 2.5.1 conformance claims.

## Source Conventions

Component source uses existing model shapes. UML-specific authoring stays under `properties.uml`.

Component node:

```json
{
  "id": "component-order-api",
  "type": "Component",
  "label": "Order API",
  "properties": {
    "uml": {
      "package": "pkg-orders"
    }
  }
}
```

Port node:

```json
{
  "id": "port-rest-api",
  "type": "Port",
  "label": "REST",
  "properties": {
    "uml": {
      "component": "component-order-api",
      "provided": ["interface-order-api"],
      "required": ["interface-payment-gateway"]
    }
  }
}
```

Usage relationship:

```json
{
  "id": "order-api-uses-payment",
  "type": "Usage",
  "source": "component-order-api",
  "target": "interface-payment-gateway",
  "label": "uses",
  "properties": {
    "uml": {}
  }
}
```

Rules:

- `Port.properties.uml.component` is required and must reference a `Component`.
- `Port.properties.uml.provided` and `Port.properties.uml.required`, when present, must be arrays of interface ids.
- `Usage` may connect a component or port to an interface, component, class, data type, enumeration, or package.
- `Realization` may connect a component or class to an interface.
- `Dependency` may connect UML structural/component elements.
- A `uml-component` view may select components, ports, interfaces, classes, packages, and component relationships.
- A `uml-component` relationship selected into the view must have both endpoints selected unless the endpoint is represented by a semantic-backed group source.

## File Responsibility Map

- `docs/superpowers/plans/2026-06-05-dediren-uml-component.md`: executable slice plan and implementation record.
- `schemas/model.schema.json`: add `uml-component` to the public view kind enum.
- `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`: add `UML_COMPONENT`.
- `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`: add `UML_COMPONENT` and `UML_PORT`.
- `schemas/svg-render-policy.schema.json`: add `uml_component` and `uml_port`.
- `fixtures/source/valid-uml-component-basic.json`: source fixture for validation and CLI workflow.
- `fixtures/render-metadata/uml-component-basic.json`: expected render metadata artifact.
- `fixtures/layout-result/uml-component-basic.json`: stable layout fixture generated from the component layout request.
- `fixtures/export/uml-component-basic.xmi`: deterministic UML/XMI output fixture.
- `fixtures/render-policy/uml-svg.json`: style overrides for component nodes, ports, packages, and component relationships.
- `uml/src/main/java/dev/dediren/uml/Uml.java`: UML vocabulary and semantic validation.
- `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`: component validation tests and mutation helpers.
- `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`: component and port sizing hints.
- `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`: projection and render metadata coverage.
- `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`: component and port SVG decorator shapes.
- `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`: SVG render contract coverage.
- `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`: component XMI export.
- `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`: export fixture and rejection coverage.
- `cli/src/test/java/dev/dediren/cli/MainTest.java`: documented CLI workflow for the component fixture.
- `README.md` and `docs/agent-usage.md`: user-facing and bundle-local workflow docs.
- Root and child `pom.xml`, `fixtures/plugins/*.manifest.json`, source fixture `required_plugins[].version`, and version assertion tests: synchronize version `0.25.0`.

## Task 1: Contract Surface And Fixture

**Files:**

- Modify: `schemas/model.schema.json`
- Modify: `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- Create: `fixtures/source/valid-uml-component-basic.json`

- [x] Write `umlComponentSourceDocumentPreservesPublicComponentSurface()` against the new fixture.
- [x] Add `uml-component` to schema and `GenericGraphViewKind`.
- [x] Create the source fixture with one package, two components, two ports, two interfaces, one class implementation detail, dependency/usage/realization relationships, and package/component semantic groups.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest`.

## Task 2: UML Vocabulary And Validation

**Files:**

- Modify: `uml/src/main/java/dev/dediren/uml/Uml.java`
- Modify: `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

- [x] Add failing tests for accepted component vocabulary, invalid port owner, malformed provided/required arrays, invalid usage endpoints, and invalid component view relationship selection.
- [x] Add `Component` and `Port` element types.
- [x] Add `Usage` relationship type.
- [x] Validate port ownership and interface references.
- [x] Keep component validation inside `uml`.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl uml -am test -Dtest=UmlValidationTest`.

## Task 3: Projection, Sizing, And Fixtures

**Files:**

- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- Modify: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- Create: `fixtures/render-metadata/uml-component-basic.json`
- Create: `fixtures/layout-result/uml-component-basic.json`

- [x] Add failing projection tests for layout request and render metadata.
- [x] Add size hints for `Component` and `Port`.
- [x] Generate stable render metadata and layout result fixtures through CLI/plugin commands.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/generic-graph,plugins/elk-layout -am test`.

## Task 4: SVG Component Notation

**Files:**

- Modify: `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `fixtures/render-policy/uml-svg.json`
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [x] Add failing SVG render assertions for component glyphs, port squares, dependency/usage arrows, and package/component groups.
- [x] Add decorators and render them through the existing UML decorator path.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/svg-render -am test -Dtest=MainTest`.

## Task 5: UML/XMI Export

**Files:**

- Modify: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Modify: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`
- Create: `fixtures/export/uml-component-basic.xmi`

- [x] Add failing export fixture coverage and invalid port owner rejection coverage.
- [x] Export components with owned ports, interfaces/classes, usages, dependencies, and realizations deterministically.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/uml-xmi-export -am test -Dtest=MainTest`.

## Task 6: CLI, Docs, Versioning, And Verification

**Files:**

- Modify: `cli/src/test/java/dev/dediren/cli/MainTest.java`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: root and child `pom.xml` files
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `fixtures/source/*.json`
- Modify: version assertion tests named in `AGENTS.md`

- [x] Add documented CLI workflow coverage.
- [x] Document component authoring and commands.
- [x] Bump product/plugin version to `0.25.0` and update synchronized version strings.
- [x] Run the focused UML verification lane:

```bash
MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl contracts,uml,plugins/generic-graph,plugins/elk-layout,plugins/svg-render,plugins/uml-xmi-export,cli -am test
git diff --check
```

## Audit Gates

- Run `souroldgeezer-audit:test-quality-audit` Quick over the changed tests before final handoff.
- Run `souroldgeezer-audit:devsecops-audit` Quick over the implementation diff because this slice changes process-boundary plugin artifacts and release surfaces.
- Fix block findings. Fix warn/info findings or explicitly accept them in the handoff, then rerun affected checks.

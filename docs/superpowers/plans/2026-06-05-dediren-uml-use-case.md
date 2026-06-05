# Dediren UML Use Case Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add the next UML roadmap slice: a usable `uml-use-case` workflow with source validation, projection, ELK layout, SVG rendering, UML/XMI export, CLI coverage, docs, version metadata, and focused verification.

**Architecture:** Keep Dediren JSON as the authored source and UML/XMI as compatibility output. Add use-case semantics inside `uml`, keep contracts/schema additive, reuse `generic-graph` projection and ELK layout, render use-case notation through existing SVG decorators, and export deterministic use-case model content from `uml-xmi`.

**Tech Stack:** Java 21, Maven Wrapper, Jackson, JSON Schema, first-party process-boundary plugins, Eclipse ELK layered layout, SVG, UML 2.5.1 XMI.

---

## Scope Decision

Included now:

- New view kind: `uml-use-case`.
- UML node types: `Actor`, `UseCase`, and `ExtensionPoint`.
- UML relationship types: `Include` and `Extend`.
- Existing UML `Association` relationships can connect actors and use cases.
- Source convention for subject boundaries: a semantic-backed view group points at an existing UML classifier node, usually `Class`, and selected `UseCase` nodes declare `properties.uml.subject`.
- SVG notation: actor stick figures, use-case ellipses, extension-point labels, subject boundary groups, actor associations, dashed include/extend arrows, and stable `data-dediren-*` attributes.
- UML/XMI export for actors, use cases, extension points, include relationships, extend relationships, and subject classifier context.

Deferred beyond this slice:

- Use-case generalization, collaboration use-case realizations, rich classifier subjects, constraints beyond extension-point references, UMLDI, and full UML 2.5.1 conformance claims.

## Source Conventions

Use case source uses existing model shapes. UML-specific authoring stays under `properties.uml`.

Subject classifier node:

```json
{
  "id": "order-service",
  "type": "Class",
  "label": "Order Service",
  "properties": {
    "uml": {
      "use_case_subject": true
    }
  }
}
```

Use case node:

```json
{
  "id": "place-order",
  "type": "UseCase",
  "label": "Place Order",
  "properties": {
    "uml": {
      "subject": "order-service"
    }
  }
}
```

Extension point node:

```json
{
  "id": "payment-extension",
  "type": "ExtensionPoint",
  "label": "payment authorized",
  "properties": {
    "uml": {
      "use_case": "place-order"
    }
  }
}
```

Include relationship:

```json
{
  "id": "include-authentication",
  "type": "Include",
  "source": "place-order",
  "target": "authenticate-customer",
  "label": "include",
  "properties": {
    "uml": {}
  }
}
```

Extend relationship:

```json
{
  "id": "extend-discount",
  "type": "Extend",
  "source": "apply-discount",
  "target": "place-order",
  "label": "extend",
  "properties": {
    "uml": {
      "extension_point": "payment-extension"
    }
  }
}
```

Rules:

- `UseCase.properties.uml.subject`, when present, must reference an existing UML structural classifier node.
- `ExtensionPoint.properties.uml.use_case` is required and must reference a `UseCase`.
- `Include` relationships must connect `UseCase -> UseCase`.
- `Extend` relationships must connect `UseCase -> UseCase`.
- `Extend.properties.uml.extension_point`, when present, must reference an `ExtensionPoint` whose `use_case` is the extended target use case.
- `Association` relationships in use-case scope may connect `Actor` to `UseCase` in either direction.
- A `uml-use-case` view should select actor, use-case, and extension-point nodes plus use-case relationships. Represent the subject as a semantic-backed view group with `semantic_source_id` pointing at the subject classifier.

## Task 1: Contract Surface And Fixture

**Files:**

- Modify: `schemas/model.schema.json`
- Modify: `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- Create: `fixtures/source/valid-uml-use-case-basic.json`

- [x] Add the use-case fixture.
- [x] Write the failing contract round-trip test.
- [x] Add `uml-use-case` to schema and contract enum.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest`.

## Task 2: UML Vocabulary And Validation

**Files:**

- Modify: `uml/src/main/java/dev/dediren/uml/Uml.java`
- Modify: `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

- [x] Add failing validation tests for valid use-case vocabulary, bad include endpoints, bad extend extension points, and invalid use-case view contents.
- [x] Add `Actor`, `UseCase`, `ExtensionPoint`, `Include`, and `Extend`.
- [x] Keep use-case validation inside `uml`.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl uml -am test`.

## Task 3: Projection, Sizing, And Fixtures

**Files:**

- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- Modify: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- Create: `fixtures/render-metadata/uml-use-case-basic.json`
- Create: `fixtures/layout-result/uml-use-case-basic.json`

- [x] Add failing projection tests for layout request and render metadata.
- [x] Add size hints for actors, use cases, and extension points.
- [x] Generate stable render metadata and layout result fixtures through CLI/plugin commands.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/generic-graph,plugins/elk-layout -am test`.

## Task 4: SVG Use Case Notation

**Files:**

- Modify: `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `fixtures/render-policy/uml-svg.json`
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [x] Add failing SVG render assertions for actor stick figures, use-case ellipses, subject group, and include/extend edge styling.
- [x] Add decorators and render them through the existing UML decorator path.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/svg-render -am test`.

## Task 5: UML/XMI Export

**Files:**

- Modify: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Modify: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`
- Create: `fixtures/export/uml-use-case-basic.xmi`

- [x] Add failing export fixture coverage and extension-point rejection coverage.
- [x] Export actors, use cases, extension points, includes, and extends deterministically.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/uml-xmi-export -am test`.

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
- [x] Document use-case authoring and commands.
- [x] Bump product/plugin version to `0.24.0` and update synchronized version strings.
- [x] Run the focused UML verification lane, stale-version search, `git diff --check`, and final `git status --short --branch`.

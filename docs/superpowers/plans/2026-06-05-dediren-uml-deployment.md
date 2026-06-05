# Dediren UML Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the next UML roadmap slice: a usable `uml-deployment` workflow with source validation, projection, ELK layout, SVG rendering, UML/XMI export, CLI coverage, docs, version metadata, and focused verification.

**Architecture:** Keep Dediren JSON as authored source and UML/XMI as compatibility output. Add deployment semantics inside `uml`, keep contracts/schema additive, reuse `generic-graph` projection and ELK layout with semantic-backed deployment groups, render deployment notation through SVG decorators, and export deterministic deployment model content from `uml-xmi`.

**Tech Stack:** Java 21, Maven Wrapper, Jackson, JSON Schema, first-party process-boundary plugins, Eclipse ELK layered layout, SVG, UML 2.5.1 XMI.

---

## Scope Decision

This plan refines roadmap Task 6 into one executable vertical slice.

Included now:

- New view kind: `uml-deployment`.
- UML node types: `Node`, `Device`, `ExecutionEnvironment`, `Artifact`, and `DeploymentSpecification`.
- UML relationship types: `Deployment`, `Manifestation`, and `CommunicationPath`.
- Source convention for nested execution environments: optional `ExecutionEnvironment.properties.uml.node` references a `Node` or `Device`.
- View convention: deployment targets may be semantic-backed groups; selected deployment relationships must have both endpoints selected or represented by a group semantic source.
- SVG notation: UML node/device/execution-environment cuboids, artifact folded-corner notation, deployment-specification artifact notation, dependency-like deployment/manifestation arrows, communication paths, and stable `data-dediren-*` attributes.
- UML/XMI export for deployment targets, artifacts, deployment specifications, deployments, manifestations, and communication paths.

Deferred beyond this slice:

- Full nested part/property modeling, deployment slots, deployed artifact multiplicities, communication path association ends beyond source/target ids, UMLDI, and full UML 2.5.1 conformance claims.

## Source Conventions

Deployment source uses existing model shapes. UML-specific authoring stays under `properties.uml`.

Device node:

```json
{
  "id": "device-prod-node",
  "type": "Device",
  "label": "Production Node",
  "properties": {
    "uml": {
      "kind": "device"
    }
  }
}
```

Execution environment node:

```json
{
  "id": "ee-orders-runtime",
  "type": "ExecutionEnvironment",
  "label": "Orders Runtime",
  "properties": {
    "uml": {
      "node": "device-prod-node"
    }
  }
}
```

Artifact node:

```json
{
  "id": "artifact-orders-service",
  "type": "Artifact",
  "label": "orders-service.jar",
  "properties": {
    "uml": {}
  }
}
```

Deployment relationship:

```json
{
  "id": "deploy-orders-service",
  "type": "Deployment",
  "source": "artifact-orders-service",
  "target": "ee-orders-runtime",
  "label": "deploy",
  "properties": {
    "uml": {}
  }
}
```

Rules:

- `ExecutionEnvironment.properties.uml.node`, when present, must reference a `Node` or `Device`.
- `Deployment` connects `Artifact` or `DeploymentSpecification` to `Node`, `Device`, or `ExecutionEnvironment`.
- `Manifestation` connects `Artifact` or `DeploymentSpecification` to a UML structural classifier such as `Component`, `Class`, `Interface`, `DataType`, `Enumeration`, or `Package`.
- `CommunicationPath` connects deployment targets in either direction.
- A `uml-deployment` view may select deployment targets, artifacts, deployment specifications, and manifested structural classifiers.
- A `uml-deployment` relationship selected into the view must have both endpoints selected unless the endpoint is represented by a semantic-backed group source.

## File Responsibility Map

- `docs/superpowers/plans/2026-06-05-dediren-uml-deployment.md`: executable slice plan and implementation record.
- `schemas/model.schema.json`: add `uml-deployment` to the public view kind enum.
- `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`: add `UML_DEPLOYMENT`.
- `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`: add deployment decorators.
- `schemas/svg-render-policy.schema.json`: add deployment decorator policy keys.
- `fixtures/source/valid-uml-deployment-basic.json`: source fixture for validation and CLI workflow.
- `fixtures/render-metadata/uml-deployment-basic.json`: expected render metadata artifact.
- `fixtures/layout-result/uml-deployment-basic.json`: stable layout fixture generated from the deployment layout request.
- `fixtures/export/uml-deployment-basic.xmi`: deterministic UML/XMI output fixture.
- `fixtures/render-policy/uml-svg.json`: style overrides for deployment targets, artifacts, deployment specifications, and deployment relationships.
- `uml/src/main/java/dev/dediren/uml/Uml.java`: UML vocabulary and semantic validation.
- `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`: deployment validation tests and mutation helpers.
- `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`: deployment sizing hints.
- `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`: projection and render metadata coverage.
- `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`: deployment SVG decorator shapes.
- `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`: SVG render contract coverage.
- `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`: deployment XMI export.
- `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`: export fixture and rejection coverage.
- `cli/src/test/java/dev/dediren/cli/MainTest.java`: documented CLI workflow for the deployment fixture.
- `README.md` and `docs/agent-usage.md`: user-facing and bundle-local workflow docs.
- Root and child `pom.xml`, `fixtures/plugins/*.manifest.json`, source fixture `required_plugins[].version`, and version assertion tests: synchronize version `0.26.0`.

## Task 1: Contract Surface And Fixture

**Files:**

- Modify: `schemas/model.schema.json`
- Modify: `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- Create: `fixtures/source/valid-uml-deployment-basic.json`

- [x] Write `umlDeploymentSourceDocumentPreservesPublicDeploymentSurface()` against the new fixture.
- [x] Add `uml-deployment` to schema and `GenericGraphViewKind`.
- [x] Create the source fixture with one device, one execution environment, one infrastructure node, two artifacts, one deployment specification, one component, deployment/manifestation/communication-path relationships, and semantic deployment groups.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest`.

## Task 2: UML Vocabulary And Validation

**Files:**

- Modify: `uml/src/main/java/dev/dediren/uml/Uml.java`
- Modify: `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

- [x] Add failing tests for accepted deployment vocabulary, invalid execution-environment parent, invalid deployment endpoints, invalid manifestation endpoints, invalid communication-path endpoints, and invalid deployment view relationship selection.
- [x] Add deployment element and relationship types.
- [x] Validate execution-environment parent references.
- [x] Validate deployment, manifestation, and communication-path endpoints.
- [x] Keep deployment validation inside `uml`.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl uml -am test -Dtest=UmlValidationTest`.

## Task 3: Projection, Sizing, And Fixtures

**Files:**

- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- Modify: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- Create: `fixtures/render-metadata/uml-deployment-basic.json`
- Create: `fixtures/layout-result/uml-deployment-basic.json`

- [x] Add failing projection tests for layout request and render metadata.
- [x] Add size hints for deployment targets, artifacts, and deployment specifications.
- [x] Generate stable render metadata and layout result fixtures through CLI/plugin commands.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/generic-graph,plugins/elk-layout -am test`.

## Task 4: SVG Deployment Notation

**Files:**

- Modify: `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `fixtures/render-policy/uml-svg.json`
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [x] Add failing SVG render assertions for deployment cuboids, artifact folded corners, deployment arrows, manifestation arrows, and communication paths.
- [x] Add decorators and render them through the existing UML decorator path.
- [x] Run `MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl plugins/svg-render -am test -Dtest=MainTest`.

## Task 5: UML/XMI Export

**Files:**

- Modify: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Modify: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`
- Create: `fixtures/export/uml-deployment-basic.xmi`

- [x] Add failing export fixture coverage and invalid endpoint rejection coverage.
- [x] Export deployment targets, artifacts, deployment specifications, deployments, manifestations, and communication paths deterministically.
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
- [x] Document deployment authoring and commands.
- [x] Bump product/plugin version to `0.26.0` and update synchronized version strings.
- [x] Run the focused UML verification lane:

```bash
MAVEN_USER_HOME=/home/souroldgeezer/repos/dediren/.cache/maven/user-home ./mvnw -pl contracts,uml,plugins/generic-graph,plugins/elk-layout,plugins/svg-render,plugins/uml-xmi-export,cli -am test
git diff --check
```

## Audit Gates

- Run `souroldgeezer-audit:test-quality-audit` Quick over the changed tests before final handoff.
- Run `souroldgeezer-audit:devsecops-audit` Quick over the implementation diff because this slice changes process-boundary plugin artifacts and release surfaces.
- Fix block findings. Fix warn/info findings or explicitly accept them in the handoff, then rerun affected checks.

Audit result:

- Test-quality Quick: no block/warn findings. New JUnit tests assert public JSON/SVG/XMI contracts, include negative deployment endpoint coverage, and avoid Java-specific flake or interaction-pinning smells.
- DevSecOps Quick: no block/warn findings. The diff does not add CI workflow privileges, unpinned release inputs, credential material, new runtime environment variables, or plugin manifest permissions beyond synchronized version metadata.

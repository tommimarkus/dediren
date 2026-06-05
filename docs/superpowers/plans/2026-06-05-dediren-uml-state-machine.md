# Dediren UML State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the next UML roadmap slice: a usable `uml-state-machine` workflow with source validation, projection, ELK layout, SVG rendering, UML/XMI export, CLI coverage, docs, version metadata, and distribution smoke coverage.

**Architecture:** Keep Dediren JSON as the authored source and UML/XMI as compatibility output. Add state-machine semantics inside `uml`, keep contracts/schema additive, reuse `generic-graph` projection and ELK layout, render state notation through the existing SVG renderer decorators rather than a new renderer path, and export state-machine model content from `uml-xmi`.

**Tech Stack:** Java 21, Maven Wrapper, Jackson, JSON Schema, first-party process-boundary plugins, Eclipse ELK layered layout, SVG, UML 2.5.1 XMI.

---

## Scope Decision

This plan refines roadmap Task 3 into one executable vertical slice.

Included now:

- New view kind: `uml-state-machine`.
- UML node types: `StateMachine`, `Region`, `State`, `FinalState`, and `Pseudostate`.
- UML relationship type: `Transition`.
- Pseudostate kinds from UML 2.5.1: `initial`, `deepHistory`, `shallowHistory`, `join`, `fork`, `junction`, `choice`, `entryPoint`, `exitPoint`, and `terminate`.
- Transition kinds from UML 2.5.1: `internal`, `local`, and `external`.
- Source conventions for state-machine ownership under `properties.uml`.
- Region and state-machine frames represented as semantic-backed view groups.
- SVG state notation using the existing graph renderer: rounded states, initial/final nodes, choice/junction diamonds, fork/join bars, history markers, entry/exit/terminate markers, transition labels, and stable `data-dediren-*` attributes.
- UML/XMI export for one state machine, its region, subvertices, and transitions.

Deferred beyond this slice:

- `ConnectionPointReference`, `ProtocolStateMachine`, `ProtocolTransition`, submachine states, orthogonal multi-region state internals, trigger event metaclasses, effects as behavior nodes, UMLDI, and full UML 2.5.1 conformance claims.
- A dedicated state-machine renderer. The generic renderer plus decorators is the smaller move for this graph-shaped diagram family.

## Source Conventions

State machine source uses existing model shapes. UML-specific authoring stays under `properties.uml`.

State machine node:

```json
{
  "id": "order-lifecycle",
  "type": "StateMachine",
  "label": "Order Lifecycle",
  "properties": {
    "uml": {}
  }
}
```

Region node:

```json
{
  "id": "main-region",
  "type": "Region",
  "label": "Main Region",
  "properties": {
    "uml": {
      "state_machine": "order-lifecycle"
    }
  }
}
```

State node:

```json
{
  "id": "draft",
  "type": "State",
  "label": "Draft",
  "properties": {
    "uml": {
      "region": "main-region"
    }
  }
}
```

Pseudostate node:

```json
{
  "id": "initial",
  "type": "Pseudostate",
  "label": "",
  "properties": {
    "uml": {
      "region": "main-region",
      "kind": "initial"
    }
  }
}
```

Transition relationship:

```json
{
  "id": "t-submit",
  "type": "Transition",
  "source": "draft",
  "target": "submitted",
  "label": "submit",
  "properties": {
    "uml": {
      "region": "main-region",
      "kind": "external",
      "trigger": "submit"
    }
  }
}
```

Rules:

- `Region.properties.uml.state_machine` is required and must reference a `StateMachine`.
- `State`, `FinalState`, and `Pseudostate` require `properties.uml.region` and the referenced node must be a `Region`.
- `Pseudostate.properties.uml.kind` is required and must be one of the supported pseudostate kinds.
- `Transition.properties.uml.region` is required and must reference a `Region`.
- `Transition.properties.uml.kind`, when present, must be `internal`, `local`, or `external`; missing kind is treated as `external` in export.
- `Transition.properties.uml.trigger`, `guard`, and `effect`, when present, must be strings.
- `Transition` sources may be `State` or `Pseudostate`.
- `Transition` targets may be `State`, `FinalState`, or `Pseudostate`.
- `FinalState` must not have outgoing `Transition` relationships.
- `Pseudostate` kind `initial` must not have incoming `Transition` relationships.
- `Transition` endpoints must belong to the same region as the transition.
- A `uml-state-machine` view should include state vertex nodes and transition relationships. Represent `StateMachine` and `Region` frames as `groups` with `semantic_source_id` pointing at the source `StateMachine` and `Region` nodes.

## File Responsibility Map

- `schemas/model.schema.json`: add `uml-state-machine` to public view kind enum.
- `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`: add `UML_STATE_MACHINE`.
- `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`: add UML state-machine decorator enum values.
- `schemas/svg-render-policy.schema.json`: add matching decorator strings.
- `fixtures/source/valid-uml-state-machine-basic.json`: source fixture for validation and CLI workflow.
- `fixtures/render-metadata/uml-state-machine-basic.json`: expected render metadata artifact for tests and packaged examples.
- `fixtures/layout-result/uml-state-machine-basic.json`: stable layout fixture generated from the state-machine layout request.
- `fixtures/export/uml-state-machine-basic.xmi`: deterministic UML/XMI output fixture.
- `fixtures/render-policy/uml-svg.json`: style overrides for state-machine nodes, groups, and transitions.
- `uml/src/main/java/dev/dediren/uml/Uml.java`: UML vocabulary and semantic validation.
- `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`: state-machine validation tests and mutation helpers.
- `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`: UML state-machine sizing hints.
- `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`: projection and render metadata coverage.
- `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java`: focused state-machine layout sanity check if generic layout regresses on the fixture.
- `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`: UML state-machine decorator shapes.
- `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`: SVG render contract coverage.
- `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`: state-machine XMI export.
- `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`: export fixture and rejection coverage.
- `cli/src/test/java/dev/dediren/cli/MainTest.java`: documented CLI workflow for the state-machine fixture.
- `README.md` and `docs/agent-usage.md`: user-facing and bundle-local workflow docs.
- `pom.xml`, child POMs, plugin manifests, version assertion tests, and source fixtures with `required_plugins[].version`: version bump surfaces for `0.23.0`.
- `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` and `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`: distribution smoke fixture coverage only if the smoke workflow is expanded to state machines.

## Task 0: Preflight, Branch Setup, And Version Target

**Files:**

- Read: `AGENTS.md`
- Read: `docs/superpowers/plans/2026-06-04-dediren-uml-expansion-roadmap.md`
- Read: `docs/superpowers/plans/2026-06-04-dediren-uml-sequence-mvp.md`
- Read: `docs/superpowers/plans/2026-06-04-dediren-uml-sequence-combined-fragments.md`

- [ ] **Step 1: Check repository state**

```bash
git status --short --branch
```

Expected: record current branch and every pre-existing changed or untracked file. Treat pre-existing work as user-owned.

- [ ] **Step 2: Confirm implementation workspace**

If still on `main`, ask the user whether to create or use an isolated worktree. Do not implement directly on `main` without explicit consent.

Suggested worktree branch:

```bash
git worktree add .worktrees/uml-state-machine -b feature/uml-state-machine main
```

Expected: implementation runs from a clean feature branch or from the user-approved current branch.

- [ ] **Step 3: Confirm the next minor version tag is free**

```bash
git tag --list v0.23.0
```

Expected: no output. If `v0.23.0` exists, stop and select the next SemVer-compatible minor version before editing version surfaces.

## Task 1: Add Contract Surface And Source Fixture

**Files:**

- Modify: `schemas/model.schema.json`
- Modify: `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- Create: `fixtures/source/valid-uml-state-machine-basic.json`

- [ ] **Step 1: Create the state-machine source fixture**

Create `fixtures/source/valid-uml-state-machine-basic.json`:

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    {
      "id": "generic-graph",
      "version": "0.22.0"
    }
  ],
  "nodes": [
    {
      "id": "order-lifecycle",
      "type": "StateMachine",
      "label": "Order Lifecycle",
      "properties": {
        "uml": {}
      }
    },
    {
      "id": "main-region",
      "type": "Region",
      "label": "Main Region",
      "properties": {
        "uml": {
          "state_machine": "order-lifecycle"
        }
      }
    },
    {
      "id": "initial",
      "type": "Pseudostate",
      "label": "",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "initial"
        }
      }
    },
    {
      "id": "draft",
      "type": "State",
      "label": "Draft",
      "properties": {
        "uml": {
          "region": "main-region"
        }
      }
    },
    {
      "id": "submitted",
      "type": "State",
      "label": "Submitted",
      "properties": {
        "uml": {
          "region": "main-region"
        }
      }
    },
    {
      "id": "payment-choice",
      "type": "Pseudostate",
      "label": "",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "choice"
        }
      }
    },
    {
      "id": "fulfilled",
      "type": "State",
      "label": "Fulfilled",
      "properties": {
        "uml": {
          "region": "main-region"
        }
      }
    },
    {
      "id": "closed",
      "type": "FinalState",
      "label": "",
      "properties": {
        "uml": {
          "region": "main-region"
        }
      }
    },
    {
      "id": "rejected",
      "type": "FinalState",
      "label": "",
      "properties": {
        "uml": {
          "region": "main-region"
        }
      }
    }
  ],
  "relationships": [
    {
      "id": "t-create",
      "type": "Transition",
      "source": "initial",
      "target": "draft",
      "label": "create",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "external",
          "trigger": "create"
        }
      }
    },
    {
      "id": "t-submit",
      "type": "Transition",
      "source": "draft",
      "target": "submitted",
      "label": "submit",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "external",
          "trigger": "submit"
        }
      }
    },
    {
      "id": "t-check-payment",
      "type": "Transition",
      "source": "submitted",
      "target": "payment-choice",
      "label": "authorize",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "external",
          "trigger": "authorizePayment"
        }
      }
    },
    {
      "id": "t-approve",
      "type": "Transition",
      "source": "payment-choice",
      "target": "fulfilled",
      "label": "[paymentAuthorized]",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "external",
          "guard": "paymentAuthorized"
        }
      }
    },
    {
      "id": "t-reject",
      "type": "Transition",
      "source": "payment-choice",
      "target": "rejected",
      "label": "[paymentRejected]",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "external",
          "guard": "paymentRejected"
        }
      }
    },
    {
      "id": "t-close",
      "type": "Transition",
      "source": "fulfilled",
      "target": "closed",
      "label": "deliver",
      "properties": {
        "uml": {
          "region": "main-region",
          "kind": "external",
          "trigger": "deliver"
        }
      }
    }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "uml",
      "views": [
        {
          "id": "state-machine-view",
          "label": "Order Lifecycle State Machine",
          "kind": "uml-state-machine",
          "nodes": ["initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected"],
          "relationships": ["t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close"],
          "layout_preferences": {
            "direction": "right",
            "density": "readable",
            "routing": {
              "style": "orthogonal",
              "profile": "readable",
              "endpoint_merging": "off"
            }
          },
          "groups": [
            {
              "id": "order-lifecycle-frame",
              "label": "Order Lifecycle",
              "role": "semantic-boundary",
              "semantic_source_id": "order-lifecycle",
              "members": ["initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected"]
            },
            {
              "id": "main-region-frame",
              "label": "Main Region",
              "role": "semantic-boundary",
              "semantic_source_id": "main-region",
              "members": ["initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected"]
            }
          ]
        }
      ]
    }
  }
}
```

- [ ] **Step 2: Add `uml-state-machine` to the model schema**

In `schemas/model.schema.json`, extend the `genericGraphView.kind` enum:

```json
"kind": { "enum": ["generic", "archimate", "uml-class", "uml-data", "uml-activity", "uml-sequence", "uml-state-machine"] }
```

- [ ] **Step 3: Add the contract enum value**

In `contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`, add after `UML_SEQUENCE`:

```java
@JsonProperty("uml-state-machine")
UML_STATE_MACHINE
```

Add a comma after `UML_SEQUENCE`.

- [ ] **Step 4: Add a contract round-trip test**

In `ContractRoundTripTest`, add:

```java
@Test
void umlStateMachineSourceDocumentPreservesPublicStateMachineSurface() throws Exception {
    String fixture = "fixtures/source/valid-uml-state-machine-basic.json";

    assertThat(SchemaAssertions.validateFixture(workspaceRoot(), "schemas/model.schema.json", fixture))
            .describedAs(fixture)
            .isEmpty();

    SourceDocument source = readFixture(fixture, SourceDocument.class);
    GenericGraphPluginData genericGraph = JsonSupport.objectMapper()
            .treeToValue(source.plugins().get("generic-graph"), GenericGraphPluginData.class);
    var view = genericGraph.views().getFirst();

    assertThat(genericGraph.semanticProfile()).isEqualTo(GenericGraphSemanticProfile.UML);
    assertThat(view.kind()).isEqualTo(GenericGraphViewKind.UML_STATE_MACHINE);
    assertThat(JsonSupport.objectMapper().valueToTree(genericGraph).at("/views/0/kind").asText())
            .isEqualTo("uml-state-machine");
    assertThat(view.nodes()).containsExactly(
            "initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected");
    assertThat(source.relationships()).extracting(SourceRelationship::id)
            .containsExactly("t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");

    SourceDocument reparsed = JsonSupport.objectMapper()
            .treeToValue(JsonSupport.objectMapper().valueToTree(source), SourceDocument.class);

    assertThat(reparsed.relationships())
            .extracting(relationship -> relationship.properties().get("uml").get("kind").textValue())
            .containsExactly("external", "external", "external", "external", "external", "external");
    assertThat(JsonSupport.objectMapper().valueToTree(reparsed).at("/nodes/5/properties/uml/kind").asText())
            .isEqualTo("choice");
}
```

- [ ] **Step 5: Run the contract lane**

```bash
./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected after this task: `ContractRoundTripTest` passes and the new fixture is schema-valid.

- [ ] **Step 6: Commit the contract surface**

```bash
git add schemas/model.schema.json contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java fixtures/source/valid-uml-state-machine-basic.json
git commit -m "feat: add UML state machine contract"
```

## Task 2: Add UML State Machine Vocabulary And Validation

**Files:**

- Modify: `uml/src/main/java/dev/dediren/uml/Uml.java`
- Modify: `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

- [ ] **Step 1: Write failing vocabulary and fixture acceptance tests**

In `UmlValidationTest`, add:

```java
@Test
void acceptsUmlStateMachineVocabulary() throws Exception {
    Fixture fixture = loadUmlStateMachineFixture();

    for (String type : new String[]{
            "StateMachine",
            "Region",
            "State",
            "FinalState",
            "Pseudostate"}) {
        Uml.validateElementType(type, "$.type");
    }
    Uml.validateRelationshipType("Transition", "$.type");
    Uml.validateRelationshipEndpointTypes("Transition", "Pseudostate", "State", "$.relationship");
    Uml.validateRelationshipEndpointTypes("Transition", "State", "FinalState", "$.relationship");
    Uml.validateSource(fixture.source(), fixture.pluginData());
}
```

Add helper:

```java
private static Fixture loadUmlStateMachineFixture() throws Exception {
    return fixture(
            Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-state-machine-basic.json")),
            "generic-graph");
}
```

- [ ] **Step 2: Add failing rejection tests**

In `UmlValidationTest`, add:

```java
@Test
void rejectsUnknownPseudostateKind() throws Exception {
    Fixture fixture = loadMutatedUmlStateMachineFixture(
            source -> nodeUmlProperties(source, "initial").put("kind", "unknownKind"));

    UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
            UmlValidationException.class,
            () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

    assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
    assertThat(error.path()).contains("properties.uml.kind");
}

@Test
void rejectsTransitionAcrossRegions() throws Exception {
    Fixture fixture = loadMutatedUmlStateMachineFixture(source -> {
        var nodes = (ArrayNode) source.get("nodes");
        nodes.add(JsonSupport.objectMapper().readTree("""
                {
                  "id": "other-region",
                  "type": "Region",
                  "label": "Other Region",
                  "properties": {
                    "uml": {
                      "state_machine": "order-lifecycle"
                    }
                  }
                }
                """));
        nodeUmlProperties(source, "fulfilled").put("region", "other-region");
    });

    UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
            UmlValidationException.class,
            () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

    assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_UNSUPPORTED");
    assertThat(error.path()).contains("properties.uml.region");
}

@Test
void rejectsOutgoingTransitionFromFinalState() throws Exception {
    Fixture fixture = loadMutatedUmlStateMachineFixture(source -> {
        var relationships = (ArrayNode) source.get("relationships");
        relationships.add(JsonSupport.objectMapper().readTree("""
                {
                  "id": "t-reopen",
                  "type": "Transition",
                  "source": "closed",
                  "target": "draft",
                  "label": "reopen",
                  "properties": {
                    "uml": {
                      "region": "main-region",
                      "kind": "external",
                      "trigger": "reopen"
                    }
                  }
                }
                """));
        ((ArrayNode) source.at("/plugins/generic-graph/views/0/relationships")).add("t-reopen");
    });

    UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
            UmlValidationException.class,
            () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

    assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    assertThat(error.path()).contains("$.relationships[6]");
}
```

Add helper:

```java
private static Fixture loadMutatedUmlStateMachineFixture(Consumer<ObjectNode> mutate) throws Exception {
    ObjectNode source = (ObjectNode) JsonSupport.objectMapper().readTree(
            Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-state-machine-basic.json")));
    mutate.accept(source);
    return fixture(JsonSupport.objectMapper().writeValueAsString(source), "generic-graph");
}
```

- [ ] **Step 3: Run the failing UML lane**

```bash
./mvnw -pl uml -am test -Dtest=UmlValidationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected before implementation: failures for unsupported `StateMachine` or `Transition`.

- [ ] **Step 4: Add vocabulary constants in `Uml.java`**

Add near the existing UML type constants:

```java
private static final Set<String> STATE_MACHINE_TYPES = Set.of(
        "StateMachine",
        "Region",
        "State",
        "FinalState",
        "Pseudostate");
private static final Set<String> STATE_VERTEX_TYPES = Set.of(
        "State",
        "FinalState",
        "Pseudostate");
private static final Set<String> TRANSITION_SOURCE_TYPES = Set.of(
        "State",
        "Pseudostate");
private static final Set<String> TRANSITION_TARGET_TYPES = Set.of(
        "State",
        "FinalState",
        "Pseudostate");
private static final Set<String> PSEUDOSTATE_KINDS = Set.of(
        "initial",
        "deepHistory",
        "shallowHistory",
        "join",
        "fork",
        "junction",
        "choice",
        "entryPoint",
        "exitPoint",
        "terminate");
private static final Set<String> TRANSITION_KINDS = Set.of(
        "internal",
        "local",
        "external");
```

Add `"Transition"` to `RELATIONSHIP_TYPES`.

- [ ] **Step 5: Extend element and relationship validation**

Update `validateElementType` to include `isStateMachineType(value)`.

Add:

```java
private static boolean isStateMachineType(String value) {
    return STATE_MACHINE_TYPES.contains(value);
}

private static boolean isStateVertexType(String value) {
    return STATE_VERTEX_TYPES.contains(value);
}
```

Update `validateRelationshipEndpointTypes`:

```java
} else if ("Transition".equals(relationshipType)) {
    endpointsSupported = TRANSITION_SOURCE_TYPES.contains(sourceType)
            && TRANSITION_TARGET_TYPES.contains(targetType);
```

- [ ] **Step 6: Validate state-machine node properties**

Add state-machine node validation in `validateSequenceNodeProperties` by renaming it to `validateUmlNodeProperties` and calling it for every node:

```java
private static void validateUmlNodeProperties(
        String nodeId,
        String nodeType,
        JsonNode umlProperties,
        String path,
        ValidationContext context) throws UmlValidationException {
    if ("CombinedFragment".equals(nodeType)) {
        validateCombinedFragmentProperties(nodeId, umlProperties, path, context);
    } else if ("InteractionOperand".equals(nodeType)) {
        validateInteractionOperandProperties(umlProperties, path, context);
    } else if ("Region".equals(nodeType)) {
        validateRegionProperties(umlProperties, path, context);
    } else if (isStateVertexType(nodeType)) {
        validateStateVertexProperties(nodeType, umlProperties, path, context);
    }
}
```

Add:

```java
private static void validateRegionProperties(
        JsonNode umlProperties,
        String path,
        ValidationContext context) throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String stateMachine = requiredTextProperty(
            umlProperties,
            "state_machine",
            "Region.state_machine",
            umlPath);
    requireNodeType(stateMachine, "StateMachine", context.nodeTypes(), umlPath + ".state_machine");
}

private static void validateStateVertexProperties(
        String nodeType,
        JsonNode umlProperties,
        String path,
        ValidationContext context) throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String region = requiredTextProperty(umlProperties, "region", nodeType + ".region", umlPath);
    requireNodeType(region, "Region", context.nodeTypes(), umlPath + ".region");
    if ("Pseudostate".equals(nodeType)) {
        String kind = requiredTextProperty(umlProperties, "kind", "Pseudostate.kind", umlPath);
        if (!PSEUDOSTATE_KINDS.contains(kind)) {
            throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, kind, umlPath + ".kind");
        }
    }
}
```

- [ ] **Step 7: Validate transition properties and region consistency**

Extend `validateRelationshipProperties`:

```java
if ("Transition".equals(relationshipType)) {
    validateTransitionProperties(umlProperties, path);
}
```

Add:

```java
private static void validateTransitionProperties(JsonNode umlProperties, String path)
        throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    if (umlProperties == null || !umlProperties.isObject()) {
        throw new UmlValidationException(
                UmlTypeKind.RELATIONSHIP_PROPERTY,
                "Transition.region",
                umlPath + ".region");
    }
    JsonNode region = umlProperties.get("region");
    if (region == null || !region.isTextual()) {
        throw new UmlValidationException(
                UmlTypeKind.RELATIONSHIP_PROPERTY,
                "Transition.region",
                umlPath + ".region");
    }
    JsonNode kind = umlProperties.get("kind");
    if (kind != null && (!kind.isTextual() || !TRANSITION_KINDS.contains(kind.asText()))) {
        throw new UmlValidationException(
                UmlTypeKind.RELATIONSHIP_PROPERTY,
                propertyValue(kind, "Transition.kind"),
                umlPath + ".kind");
    }
    for (String field : List.of("trigger", "guard", "effect")) {
        JsonNode value = umlProperties.get(field);
        if (value != null && !value.isTextual()) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_PROPERTY,
                    value.toString(),
                    umlPath + "." + field);
        }
    }
}
```

Add a validation pass after relationship endpoint checks:

```java
validateTransitionRegionConsistency(source.relationships(), context);
```

Add:

```java
private static void validateTransitionRegionConsistency(
        List<SourceRelationship> relationships,
        ValidationContext context) throws UmlValidationException {
    for (int relationshipIndex = 0; relationshipIndex < relationships.size(); relationshipIndex++) {
        SourceRelationship relationship = relationships.get(relationshipIndex);
        if (!"Transition".equals(relationship.type())) {
            continue;
        }
        String path = "$.relationships[" + relationshipIndex + "]";
        String region = readTextProperty(context.relationshipUmlProperties().get(relationship.id()), "region");
        requireNodeType(region, "Region", context.nodeTypes(), path + ".properties.uml.region");
        String sourceType = context.nodeTypes().get(relationship.source());
        String targetType = context.nodeTypes().get(relationship.target());
        if ("FinalState".equals(sourceType)) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_ENDPOINT,
                    "Transition: FinalState -> " + targetType,
                    path);
        }
        if ("Pseudostate".equals(targetType)
                && "initial".equals(readTextProperty(
                        context.nodeUmlProperties().get(relationship.target()),
                        "kind"))) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_ENDPOINT,
                    "Transition target initial Pseudostate",
                    path);
        }
        for (String endpoint : List.of(relationship.source(), relationship.target())) {
            String endpointRegion = readTextProperty(context.nodeUmlProperties().get(endpoint), "region");
            if (!region.equals(endpointRegion)) {
                throw new UmlValidationException(
                        UmlTypeKind.RELATIONSHIP_PROPERTY,
                        region,
                        path + ".properties.uml.region");
            }
        }
    }
}
```

- [ ] **Step 8: Validate state-machine view selections**

In the view loop, add:

```java
if (view.kind() == GenericGraphViewKind.UML_STATE_MACHINE) {
    validateUmlStateMachineViewProperties(viewIndex, view, context);
}
```

Add:

```java
private static void validateUmlStateMachineViewProperties(
        int viewIndex,
        GenericGraphView view,
        ValidationContext context) throws UmlValidationException {
    var selectedNodeIds = new HashSet<>(view.nodes());
    for (int relationshipIndex = 0; relationshipIndex < view.relationships().size(); relationshipIndex++) {
        String relationshipId = view.relationships().get(relationshipIndex);
        if (!"Transition".equals(context.relationshipTypes().get(relationshipId))) {
            continue;
        }
        String source = context.relationshipSources().get(relationshipId);
        String target = context.relationshipTargets().get(relationshipId);
        if (!selectedNodeIds.contains(source) || !selectedNodeIds.contains(target)) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_ENDPOINT,
                    relationshipId,
                    "$.plugins.generic-graph.views[" + viewIndex + "].relationships[" + relationshipIndex + "]");
        }
    }
}
```

- [ ] **Step 9: Run the UML lane**

```bash
./mvnw -pl uml -am test
```

Expected: all UML validation tests pass.

- [ ] **Step 10: Commit UML validation**

```bash
git add uml/src/main/java/dev/dediren/uml/Uml.java uml/src/test/java/dev/dediren/uml/UmlValidationTest.java
git commit -m "feat: validate UML state machines"
```

## Task 3: Project State Machine Metadata And Layout Inputs

**Files:**

- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- Modify: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- Create: `fixtures/render-metadata/uml-state-machine-basic.json`
- Create: `fixtures/layout-result/uml-state-machine-basic.json`

- [ ] **Step 1: Add projection tests**

In `GenericGraphPluginTest`, add:

```java
@Test
void projectsUmlStateMachineViewKind() throws Exception {
    PluginResult result = Main.executeForTesting(
            new String[]{"project", "--target", "layout-request", "--view", "state-machine-view"},
            fixture("fixtures/source/valid-uml-state-machine-basic.json"));

    JsonNode data = okData(result);

    assertThat(data.at("/view_id").asText()).isEqualTo("state-machine-view");
    assertThat(jsonTexts(data.get("nodes"), "id"))
            .containsExactly("initial", "draft", "submitted", "payment-choice", "fulfilled", "closed", "rejected");
    assertThat(jsonTexts(data.get("edges"), "id"))
            .containsExactly("t-create", "t-submit", "t-check-payment", "t-approve", "t-reject", "t-close");
    assertThat(jsonTexts(data.get("groups"), "id"))
            .containsExactly("order-lifecycle-frame", "main-region-frame");
    assertSchemaValid("schemas/layout-request.schema.json", data);
}

@Test
void projectsUmlStateMachineRenderMetadata() throws Exception {
    PluginResult result = Main.executeForTesting(
            new String[]{"project", "--target", "render-metadata", "--view", "state-machine-view"},
            fixture("fixtures/source/valid-uml-state-machine-basic.json"));

    JsonNode data = okData(result);

    assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(data.at("/nodes/payment-choice/type").asText()).isEqualTo("Pseudostate");
    assertThat(data.at("/nodes/payment-choice/properties/kind").asText()).isEqualTo("choice");
    assertThat(data.at("/edges/t-approve/type").asText()).isEqualTo("Transition");
    assertThat(data.at("/edges/t-approve/properties/guard").asText()).isEqualTo("paymentAuthorized");
    assertThat(data.at("/groups/order-lifecycle-frame/type").asText()).isEqualTo("StateMachine");
    assertThat(data.at("/groups/main-region-frame/type").asText()).isEqualTo("Region");
    assertSchemaValid("schemas/render-metadata.schema.json", data);
}
```

Expected before implementation: these tests fail if `uml-state-machine` is not wired everywhere.

- [ ] **Step 2: Add state-machine sizing hints**

In `GenericGraphLayoutSizing`, add state-machine hints before large structural sizing:

```java
Double umlStateMachineHint = umlStateMachineWidthHint(sourceNode.type());
if (semanticProfile.equals("uml") && umlStateMachineHint != null) {
    return umlStateMachineHint;
}
```

and in `heightHint`:

```java
Double umlStateMachineHint = umlStateMachineHeightHint(sourceNode.type());
if (semanticProfile.equals("uml") && umlStateMachineHint != null) {
    return umlStateMachineHint;
}
```

Add:

```java
private static Double umlStateMachineWidthHint(String nodeType) {
    return switch (nodeType) {
        case "State" -> 150.0;
        case "FinalState", "Pseudostate" -> 36.0;
        default -> null;
    };
}

private static Double umlStateMachineHeightHint(String nodeType) {
    return switch (nodeType) {
        case "State" -> 72.0;
        case "FinalState", "Pseudostate" -> 36.0;
        default -> null;
    };
}
```

- [ ] **Step 3: Generate render metadata fixture**

Run:

```bash
./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#projectsUmlStateMachineRenderMetadata -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl cli -am package -DskipTests
./cli/target/appassembler/bin/dediren project --plugin generic-graph --target render-metadata --view state-machine-view --input fixtures/source/valid-uml-state-machine-basic.json > /tmp/uml-state-machine-render-metadata-envelope.json
jq '.data' /tmp/uml-state-machine-render-metadata-envelope.json > fixtures/render-metadata/uml-state-machine-basic.json
```

Expected fixture content has `"semantic_profile": "uml"`, `Pseudostate` node metadata, `Transition` edge metadata, and group selectors for `StateMachine` and `Region`.

- [ ] **Step 4: Generate layout fixture**

Run:

```bash
./cli/target/appassembler/bin/dediren project --plugin generic-graph --target layout-request --view state-machine-view --input fixtures/source/valid-uml-state-machine-basic.json > /tmp/uml-state-machine-layout-request-envelope.json
jq '.data' /tmp/uml-state-machine-layout-request-envelope.json > /tmp/uml-state-machine-layout-request.json
./cli/target/appassembler/bin/dediren layout --plugin elk-layout --input /tmp/uml-state-machine-layout-request.json > /tmp/uml-state-machine-layout-envelope.json
jq '.data' /tmp/uml-state-machine-layout-envelope.json > fixtures/layout-result/uml-state-machine-basic.json
./cli/target/appassembler/bin/dediren validate-layout --input fixtures/layout-result/uml-state-machine-basic.json
```

Expected: layout validation returns an ok envelope. If ELK emits warnings, inspect them and fix source/grouping/layout preferences before saving the fixture.

- [ ] **Step 5: Run projection tests**

```bash
./mvnw -pl plugins/generic-graph -am test
```

Expected: all generic-graph tests pass.

- [ ] **Step 6: Commit projection and generated fixtures**

```bash
git add plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java fixtures/render-metadata/uml-state-machine-basic.json fixtures/layout-result/uml-state-machine-basic.json
git commit -m "feat: project UML state machine views"
```

## Task 4: Render UML State Machine SVG

**Files:**

- Modify: `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `fixtures/render-policy/uml-svg.json`
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Add failing SVG render tests**

In `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`, add under `RenderContracts`:

```java
@Test
void rendersUmlStateMachineNotation() throws Exception {
    String content = okContent(render(renderInput(
            "fixtures/layout-result/uml-state-machine-basic.json",
            "fixtures/render-policy/uml-svg.json",
            "fixtures/render-metadata/uml-state-machine-basic.json")));
    Document document = svgDocument(content);

    assertThat(content).contains(
            "data-dediren-node-id=\"draft\"",
            "data-dediren-node-id=\"initial\"",
            "data-dediren-node-id=\"payment-choice\"",
            "data-dediren-edge-id=\"t-approve\"",
            "data-dediren-group-id=\"order-lifecycle-frame\"");
    assertThat(groupWithAttribute(document, "data-dediren-node-id", "draft").getTextContent())
            .contains("Draft");
    assertThat(firstChildElement(groupWithAttribute(document, "data-dediren-node-id", "initial"), "circle")
            .getAttribute("data-dediren-node-shape"))
            .isEqualTo("uml_pseudostate");
    assertThat(firstChildElement(groupWithAttribute(document, "data-dediren-node-id", "payment-choice"), "path")
            .getAttribute("data-dediren-node-shape"))
            .isEqualTo("uml_pseudostate");
}
```

If the helper overload does not exist, add:

```java
private static JsonNode renderInput(String layoutFixture, String policyFixture, String metadataFixture) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson(layoutFixture));
    input.set("policy", fixtureJson(policyFixture));
    input.set("render_metadata", fixtureJson(metadataFixture));
    return input;
}
```

- [ ] **Step 2: Add decorator contract values**

In `SvgNodeDecorator`, add:

```java
@JsonProperty("uml_state_machine")
UML_STATE_MACHINE,
@JsonProperty("uml_region")
UML_REGION,
@JsonProperty("uml_state")
UML_STATE,
@JsonProperty("uml_final_state")
UML_FINAL_STATE,
@JsonProperty("uml_pseudostate")
UML_PSEUDOSTATE
```

Add the same strings to the decorator enum in `schemas/svg-render-policy.schema.json`.

- [ ] **Step 3: Update UML render policy**

In `fixtures/render-policy/uml-svg.json`, add node overrides:

```json
"State": { "fill": "#ffffff", "stroke": "#000000", "label_fill": "#000000", "rx": 16, "decorator": "uml_state" },
"FinalState": { "fill": "#ffffff", "stroke": "#000000", "label_fill": "#000000", "decorator": "uml_final_state" },
"Pseudostate": { "fill": "#000000", "stroke": "#000000", "label_fill": "#000000", "decorator": "uml_pseudostate" }
```

Add edge override:

```json
"Transition": {
  "marker_end": "open_arrow",
  "label_horizontal_position": "center",
  "label_horizontal_side": "auto"
}
```

Add group type overrides:

```json
"StateMachine": {
  "fill": "#ffffff",
  "stroke": "#000000",
  "label_fill": "#000000",
  "rx": 0,
  "decorator": "uml_state_machine"
},
"Region": {
  "fill": "#ffffff",
  "stroke": "#000000",
  "label_fill": "#000000",
  "rx": 0,
  "decorator": "uml_region"
}
```

- [ ] **Step 4: Render state-machine shapes in `Main.java`**

In `umlNodeShape`, add cases:

```java
case UML_FINAL_STATE -> {
    double radius = Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    double innerRadius = Math.max(3.0, radius * 0.48);
    yield String.format(
            Locale.ROOT,
            "<g data-dediren-node-shape=\"%s\"><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#ffffff\" stroke=\"%s\" stroke-width=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/></g>",
            shapeName,
            node.x() + node.width() / 2.0,
            node.y() + node.height() / 2.0,
            radius,
            attr(style.stroke()),
            styleNumber(style.strokeWidth()),
            node.x() + node.width() / 2.0,
            node.y() + node.height() / 2.0,
            innerRadius,
            attr(style.stroke()));
}
case UML_PSEUDOSTATE -> umlPseudostateShape(node, style, selector);
case UML_STATE -> String.format(
        Locale.ROOT,
        "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        shapeName,
        node.x(),
        node.y(),
        node.width(),
        node.height(),
        styleNumber(Math.max(style.rx(), 14.0)),
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
```

Change `umlNodeShape` signature to accept `RenderMetadataSelector selector`, and pass selector from `nodeShape`.

Add helper:

```java
private static String umlPseudostateShape(
        LaidOutNode node,
        ResolvedNodeStyle style,
        RenderMetadataSelector selector) {
    String kind = selector == null || selector.properties() == null
            ? "initial"
            : selector.properties().path("kind").asText("initial");
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    double radius = Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    if (kind.equals("choice") || kind.equals("junction")) {
        return String.format(
                Locale.ROOT,
                "<path data-dediren-node-shape=\"uml_pseudostate\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                centerX,
                node.y(),
                node.x() + node.width(),
                centerY,
                centerX,
                node.y() + node.height(),
                node.x(),
                centerY,
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()));
    }
    if (kind.equals("fork") || kind.equals("join")) {
        boolean horizontal = node.width() >= node.height();
        double width = horizontal ? node.width() : Math.min(node.width(), 14.0);
        double height = horizontal ? Math.min(node.height(), 14.0) : node.height();
        double x = node.x() + (node.width() - width) / 2.0;
        double y = node.y() + (node.height() - height) / 2.0;
        return String.format(
                Locale.ROOT,
                "<rect data-dediren-node-shape=\"uml_pseudostate\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                x,
                y,
                width,
                height,
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()));
    }
    String fill = kind.equals("initial") ? style.fill() : "#ffffff";
    String marker = switch (kind) {
        case "deepHistory" -> "H*";
        case "shallowHistory" -> "H";
        case "entryPoint" -> "E";
        case "exitPoint" -> "X";
        case "terminate" -> "X";
        default -> "";
    };
    String circle = String.format(
            Locale.ROOT,
            "<circle data-dediren-node-shape=\"uml_pseudostate\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            centerX,
            centerY,
            radius,
            attr(fill),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
    if (marker.isEmpty()) {
        return circle;
    }
    return circle + String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"%s\">%s</text>",
            centerX,
            centerY + 4.0,
            attr(style.stroke()),
            styleNumber(Math.max(10.0, radius * 0.72)),
            text(marker));
}
```

- [ ] **Step 5: Keep labels off compact nodes**

In the node render loop, replace the plain label condition:

```java
if (!umlDecoratorSuppliesNodeLabel(style.decorator())) {
```

with:

```java
if (plainNodeLabelVisible(style.decorator(), node.label())) {
```

Add helper:

```java
private static boolean plainNodeLabelVisible(SvgNodeDecorator decorator, String label) {
    if (label == null || label.isEmpty()) {
        return false;
    }
    return decorator != SvgNodeDecorator.UML_FINAL_STATE
            && decorator != SvgNodeDecorator.UML_PSEUDOSTATE
            && !umlDecoratorSuppliesNodeLabel(decorator);
}
```

Keep `UML_STATE` labels centered.

Expected behavior: `Draft`, `Submitted`, and `Fulfilled` are visible; initial, choice, and final marker nodes do not show empty text artifacts.

- [ ] **Step 6: Run SVG tests**

```bash
./mvnw -pl plugins/svg-render -am test
```

Expected: SVG render tests pass and `fixtures/render-policy/uml-svg.json` validates against `schemas/svg-render-policy.schema.json`.

- [ ] **Step 7: Commit SVG state rendering**

```bash
git add contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java schemas/svg-render-policy.schema.json fixtures/render-policy/uml-svg.json plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "feat: render UML state machine notation"
```

## Task 5: Export UML State Machine XMI

**Files:**

- Modify: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Modify: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`
- Create: `fixtures/export/uml-state-machine-basic.xmi`

- [ ] **Step 1: Add failing export tests**

In `MainTest`, add:

```java
@Test
void exportsStateMachineRegionVerticesAndTransitions() throws Exception {
    JsonNode input = exportInput(
            fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"),
            fixtureJson("fixtures/layout-result/uml-state-machine-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-state-machine-basic.xmi"));
    assertThat(xml).contains(
            "xmi:type=\"uml:StateMachine\"",
            "<region xmi:id=\"id-main-region\" name=\"Main Region\"",
            "xmi:type=\"uml:Pseudostate\"",
            "kind=\"choice\"",
            "xmi:type=\"uml:FinalState\"",
            "<transition xmi:id=\"id-t-submit\"");
}

@Test
void rejectsSelectedStateMachineTransitionWithoutRegion() throws Exception {
    JsonNode input = exportInput(
            fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"),
            fixtureJson("fixtures/layout-result/uml-state-machine-basic.json"));
    ((ObjectNode) input.at("/source/relationships/0/properties/uml")).remove("region");

    PluginResult result = Main.executeForTesting(
            new String[]{"export"},
            JsonSupport.objectMapper().writeValueAsString(input),
            envWithXmiSchema());

    assertThat(result.exitCode()).isEqualTo(3);
    assertError(result, "DEDIREN_UML_RELATIONSHIP_PROPERTY_UNSUPPORTED", "$.relationships[0].properties.uml.region");
}
```

- [ ] **Step 2: Add state-machine export helpers**

In `Main.java`, extend the `buildXmi` switch:

```java
case "StateMachine" -> writeStateMachine(
        xml,
        ids,
        node,
        elementId,
        selectedNodes,
        selectedRelationships,
        nodeIds,
        relationshipIds);
```

Add:

```java
private static void writeStateMachine(
        StringBuilder xml,
        IdentifierMap ids,
        SourceNode stateMachine,
        String elementId,
        List<SourceNode> selectedNodes,
        List<SourceRelationship> selectedRelationships,
        Map<String, String> nodeIds,
        Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:StateMachine\" xmi:id=\"")
            .append(attr(elementId))
            .append("\" name=\"")
            .append(attr(stateMachine.label()))
            .append("\">");
    for (SourceNode region : selectedNodes.stream()
            .filter(node -> node.type().equals("Region"))
            .filter(node -> stateMachine.id().equals(umlString(node, "state_machine")))
            .toList()) {
        writeStateMachineRegion(xml, ids, region, selectedNodes, selectedRelationships, nodeIds, relationshipIds);
    }
    xml.append("</packagedElement>");
}
```

Add:

```java
private static void writeStateMachineRegion(
        StringBuilder xml,
        IdentifierMap ids,
        SourceNode region,
        List<SourceNode> selectedNodes,
        List<SourceRelationship> selectedRelationships,
        Map<String, String> nodeIds,
        Map<String, String> relationshipIds) {
    xml.append("<region xmi:id=\"")
            .append(attr(nodeIds.get(region.id())))
            .append("\" name=\"")
            .append(attr(region.label()))
            .append("\">");
    for (SourceNode vertex : selectedNodes.stream()
            .filter(node -> region.id().equals(umlString(node, "region")))
            .toList()) {
        writeStateMachineVertex(xml, vertex, nodeIds.get(vertex.id()));
    }
    for (SourceRelationship transition : selectedRelationships.stream()
            .filter(relationship -> relationship.type().equals("Transition"))
            .filter(relationship -> region.id().equals(umlString(relationship, "region")))
            .toList()) {
        writeTransition(xml, transition, relationshipIds.get(transition.id()), nodeIds);
    }
    xml.append("</region>");
}
```

Add:

```java
private static void writeStateMachineVertex(StringBuilder xml, SourceNode vertex, String elementId) {
    switch (vertex.type()) {
        case "State" -> xml.append("<subvertex xmi:type=\"uml:State\" xmi:id=\"")
                .append(attr(elementId))
                .append("\" name=\"")
                .append(attr(vertex.label()))
                .append("\"/>");
        case "FinalState" -> xml.append("<subvertex xmi:type=\"uml:FinalState\" xmi:id=\"")
                .append(attr(elementId))
                .append("\" name=\"")
                .append(attr(vertex.label()))
                .append("\"/>");
        case "Pseudostate" -> xml.append("<subvertex xmi:type=\"uml:Pseudostate\" xmi:id=\"")
                .append(attr(elementId))
                .append("\" name=\"")
                .append(attr(vertex.label()))
                .append("\" kind=\"")
                .append(attr(umlString(vertex, "kind")))
                .append("\"/>");
        default -> {
        }
    }
}
```

Add:

```java
private static void writeTransition(
        StringBuilder xml,
        SourceRelationship transition,
        String elementId,
        Map<String, String> nodeIds) {
    xml.append("<transition xmi:id=\"")
            .append(attr(elementId))
            .append("\" name=\"")
            .append(attr(transition.label()))
            .append("\" source=\"")
            .append(attr(nodeIds.get(transition.source())))
            .append("\" target=\"")
            .append(attr(nodeIds.get(transition.target())))
            .append("\" kind=\"")
            .append(attr(Optional.ofNullable(umlString(transition, "kind")).orElse("external")))
            .append("\">");
    String guard = umlString(transition, "guard");
    if (guard != null && !guard.isBlank()) {
        String guardId = elementId + "-guard";
        xml.append("<guard xmi:type=\"uml:Constraint\" xmi:id=\"")
                .append(attr(guardId))
                .append("\" name=\"")
                .append(attr(guard))
                .append("\"><specification xmi:type=\"uml:OpaqueExpression\" xmi:id=\"")
                .append(attr(guardId))
                .append("-specification\"><body>")
                .append(text(guard))
                .append("</body></specification></guard>");
    }
    xml.append("</transition>");
}
```

- [ ] **Step 3: Add export-scope regression for semantic-backed groups**

In `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`, add:

```java
@Test
void exportsStateMachineFrameNodesFromSemanticBackedGroups() throws Exception {
    JsonNode input = exportInput(
            fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"),
            fixtureJson("fixtures/layout-result/uml-state-machine-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).contains(
            "xmi:id=\"id-order-lifecycle\"",
            "xmi:id=\"id-main-region\"");
}
```

Expected: the state-machine layout fixture groups include `semantic_backed.source_id` for `order-lifecycle` and `main-region`, so export scope includes both source-only frame nodes.

- [ ] **Step 4: Create the expected XMI fixture**

Create `fixtures/export/uml-state-machine-basic.xmi` from the deterministic exporter output. The fixture must be a single XML line ending with a newline and must include this subsequence:

```xml
<packagedElement xmi:type="uml:StateMachine" xmi:id="id-order-lifecycle" name="Order Lifecycle"><region xmi:id="id-main-region" name="Main Region"><subvertex xmi:type="uml:Pseudostate" xmi:id="id-initial" name="" kind="initial"/><subvertex xmi:type="uml:State" xmi:id="id-draft" name="Draft"/><subvertex xmi:type="uml:State" xmi:id="id-submitted" name="Submitted"/><subvertex xmi:type="uml:Pseudostate" xmi:id="id-payment-choice" name="" kind="choice"/><subvertex xmi:type="uml:State" xmi:id="id-fulfilled" name="Fulfilled"/><subvertex xmi:type="uml:FinalState" xmi:id="id-closed" name=""/><subvertex xmi:type="uml:FinalState" xmi:id="id-rejected" name=""/>
```

The transition sequence must follow source relationship order: `t-create`, `t-submit`, `t-check-payment`, `t-approve`, `t-reject`, `t-close`.

- [ ] **Step 5: Run export tests**

```bash
./mvnw -pl plugins/uml-xmi-export -am test
```

Expected: all UML/XMI export tests pass with the state-machine fixture matching exactly.

- [ ] **Step 6: Commit state-machine export**

```bash
git add plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java fixtures/export/uml-state-machine-basic.xmi
git commit -m "feat: export UML state machines"
```

## Task 6: Add CLI Workflow Coverage And Docs

**Files:**

- Modify: `cli/src/test/java/dev/dediren/cli/MainTest.java`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`

- [ ] **Step 1: Add CLI workflow test**

In `cli/src/test/java/dev/dediren/cli/MainTest.java`, add:

```java
@Test
void stateMachineFixtureRunsThroughDocumentedCliWorkflow() throws Exception {
    Map<String, String> env = sequenceWorkflowEnv();
    Path root = workspaceRoot();
    Path source = root.resolve("fixtures/source/valid-uml-state-machine-basic.json");

    CliResult validate = Main.executeForTesting(new String[]{
            "validate",
            "--plugin",
            "generic-graph",
            "--profile",
            "uml",
            "--input",
            source.toString()
    }, "", env);

    JsonNode validateData = okData(validate);
    assertThat(validateData.at("/semantic_profile").asText()).isEqualTo("uml");
    assertThat(validateData.at("/node_count").asInt()).isEqualTo(9);
    assertThat(validateData.at("/relationship_count").asInt()).isEqualTo(6);

    CliResult layoutRequest = Main.executeForTesting(new String[]{
            "project",
            "--plugin",
            "generic-graph",
            "--target",
            "layout-request",
            "--view",
            "state-machine-view",
            "--input",
            source.toString()
    }, "", env);

    JsonNode layoutRequestData = okData(layoutRequest);
    assertThat(layoutRequestData.at("/view_id").asText()).isEqualTo("state-machine-view");
    Path layoutRequestFile = writeStdout("state-machine-layout-request.json", layoutRequest);

    CliResult renderMetadata = Main.executeForTesting(new String[]{
            "project",
            "--plugin",
            "generic-graph",
            "--target",
            "render-metadata",
            "--view",
            "state-machine-view",
            "--input",
            source.toString()
    }, "", env);

    JsonNode renderMetadataData = okData(renderMetadata);
    assertThat(renderMetadataData.at("/nodes/payment-choice/type").asText()).isEqualTo("Pseudostate");
    assertThat(renderMetadataData.at("/edges/t-approve/type").asText()).isEqualTo("Transition");
    Path renderMetadataFile = writeStdout("state-machine-render-metadata.json", renderMetadata);

    CliResult layout = Main.executeForTesting(new String[]{
            "layout",
            "--plugin",
            "elk-layout",
            "--input",
            layoutRequestFile.toString()
    }, "", env);

    JsonNode layoutData = okData(layout);
    assertThat(layoutData.at("/view_id").asText()).isEqualTo("state-machine-view");
    Path layoutFile = writeStdout("state-machine-layout-result.json", layout);

    CliResult render = Main.executeForTesting(new String[]{
            "render",
            "--plugin",
            "svg-render",
            "--policy",
            root.resolve("fixtures/render-policy/uml-svg.json").toString(),
            "--metadata",
            renderMetadataFile.toString(),
            "--input",
            layoutFile.toString()
    }, "", env);

    JsonNode renderData = okData(render);
    String svg = renderData.at("/content").asText();
    assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
    assertThat(svg).contains(
            "<svg",
            "Order Lifecycle",
            "data-dediren-node-id=\"draft\"",
            "data-dediren-edge-id=\"t-submit\"");

    CliResult export = Main.executeForTesting(new String[]{
            "export",
            "--plugin",
            "uml-xmi",
            "--policy",
            root.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
            "--source",
            source.toString(),
            "--layout",
            layoutFile.toString()
    }, "", env);

    JsonNode exportData = okData(export);
    String xmi = exportData.at("/content").asText();
    assertThat(exportData.at("/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
    assertThat(xmi).contains("uml:StateMachine", "id-order-lifecycle", "id-t-submit");
}
```

- [ ] **Step 2: Update README**

In `README.md`, update the supported UML view kinds to include `uml-state-machine`.

Add a `## UML State Machine Workflow` section after the sequence workflow with command examples:

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input state-machine-layout-request.json \
  > state-machine-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata state-machine-render-metadata.json \
  --input state-machine-layout-result.json \
  > state-machine-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  --layout state-machine-layout-result.json \
  > state-machine-xmi-result.json
```

Document the supported vocabulary and explicitly name deferred items:

```markdown
The UML state-machine vocabulary is `StateMachine`, `Region`, `State`,
`FinalState`, `Pseudostate`, and `Transition`. Supported pseudostate kinds are
`initial`, `deepHistory`, `shallowHistory`, `join`, `fork`, `junction`,
`choice`, `entryPoint`, `exitPoint`, and `terminate`. Supported transition
kinds are `internal`, `local`, and `external`.
```

- [ ] **Step 3: Update agent guide**

In `docs/agent-usage.md`, update the UML view kind list and add a compact `## UML State Machine Handoff` section with the same commands and the rule that `StateMachine`/`Region` are represented as semantic-backed groups in the view.

- [ ] **Step 4: Run CLI and docs checks**

```bash
./mvnw -pl cli -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false
git diff --check
```

Expected: CLI workflow passes and markdown has no whitespace errors.

- [ ] **Step 5: Commit CLI and docs**

```bash
git add cli/src/test/java/dev/dediren/cli/MainTest.java README.md docs/agent-usage.md
git commit -m "docs: publish UML state machine workflow"
```

## Task 7: Bump Product Version To 0.23.0

**Files:**

- Modify: `pom.xml`
- Modify: child `pom.xml` files updated by Maven Versions Plugin
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `fixtures/source/*.json`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: `cli/src/test/java/dev/dediren/cli/MainTest.java`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
- Modify tests that assert plugin/source versions under `plugins/*/src/test/java`

- [ ] **Step 1: Use Maven-calculated minor bump**

Run from the repo root:

```bash
./mvnw build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.nextMinorVersion}.0' -DprocessAllModules=true -DgenerateBackupPoms=false
```

Expected: root and child POMs move from `0.22.0` to `0.23.0`, with no backup POMs.

- [ ] **Step 2: Update manifest and source fixture versions**

Replace `0.22.0` with `0.23.0` in:

```text
fixtures/plugins/*.manifest.json
fixtures/source/*.json
README.md
docs/agent-usage.md
cli/src/test/java/dev/dediren/cli/MainTest.java
contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
plugins/*/src/test/java/**/*.java
```

Use targeted edits, not `git add -A`.

- [ ] **Step 3: Run stale-version search**

```bash
rg -n "0\\.22\\.0|v0\\.22\\.0|dediren-agent-bundle-0\\.22\\.0" pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source cli contracts plugins dist-tool
```

Expected: no stale `0.22.0` references in current product/version assertion surfaces. Historical plan files under `docs/superpowers/plans` may still mention old versions and should not be mechanically rewritten.

- [ ] **Step 4: Run version-focused tests**

```bash
./mvnw -pl contracts,cli,dist-tool -am test
```

Expected: version assertions pass.

- [ ] **Step 5: Commit version bump**

```bash
git add pom.xml archimate/pom.xml cli/pom.xml contracts/pom.xml core/pom.xml dist-tool/pom.xml plugins/archimate-oef-export/pom.xml plugins/elk-layout/pom.xml plugins/generic-graph/pom.xml plugins/svg-render/pom.xml plugins/uml-xmi-export/pom.xml schema-cache/pom.xml test-support/pom.xml uml/pom.xml fixtures/plugins fixtures/source README.md docs/agent-usage.md cli/src/test/java/dev/dediren/cli/MainTest.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "chore: bump version for UML state machines"
```

Adjust the explicit file list to match actual changed files after reviewing `git diff --name-only`.

## Task 8: Full Verification, Audits, And Tag

**Files:**

- Read changed files through `git diff --stat`
- Read exact diffs before staging missed changes
- Create tag: `v0.23.0`

- [ ] **Step 1: Run focused module checks**

```bash
./mvnw -pl contracts -am test
./mvnw -pl uml,plugins/generic-graph -am test
./mvnw -pl plugins/svg-render,cli -am test
./mvnw -pl plugins/uml-xmi-export,cli -am test
./mvnw -pl plugins/elk-layout -am test
```

Expected: all focused lanes pass.

- [ ] **Step 2: Run broad checks**

```bash
./mvnw test
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```

Expected: full Maven tests pass, distribution smoke passes, and whitespace check passes.

- [ ] **Step 3: Run repo audit gates**

Use `souroldgeezer-audit:test-quality-audit` Deep for changed Java tests and fixtures.

Use `souroldgeezer-audit:devsecops-audit` Quick for plugin process-boundary, dependency, release, and distribution posture.

Use `souroldgeezer-architecture:architecture-design` Review for UML render/export evidence and supported-slice claims.

Expected: no block findings remain. Fix warn/info findings or explicitly accept them in the handoff with evidence.

- [ ] **Step 4: Review final git state and create tag**

```bash
git status --short --branch
git log --oneline -8
git tag -a v0.23.0 -m "Dediren 0.23.0"
```

Expected: `v0.23.0` points at the commit containing the version bump.

- [ ] **Step 5: Final handoff**

Report:

- Changed public UML surface.
- Version and tag created.
- Verification commands and results.
- Audit findings fixed or accepted.
- Any generated artifacts intentionally left untracked.

## Self-Review

- Spec coverage: this plan implements roadmap Task 3 with a vertical source-to-validate-to-project-to-layout-to-render-to-export-to-docs-to-dist path.
- Scope check: state machines are isolated from use cases, component/composite structure, deployment, richer classification, and UMLDI.
- Placeholder scan: every task has exact files, commands, expected results, and concrete snippets.
- Type consistency: view kind is `uml-state-machine`; UML metaclass names match the roadmap and local UML 2.5.1 research; property names are `state_machine`, `region`, `kind`, `trigger`, `guard`, and `effect`.
- Boundary decision: no new renderer path is planned because ordinary state diagrams are graph-shaped and can use existing renderer decorators; the sequence renderer remains sequence-specific.
- Verification plan: focused tests run before full Maven, dist smoke, whitespace, and audit gates.

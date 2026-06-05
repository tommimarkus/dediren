package dev.dediren.uml;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.SourceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class UmlValidationTest {
    @Test
    void validatesUmlFixture() throws Exception {
        Fixture fixture = loadUmlFixture();

        Uml.validateSource(fixture.source(), fixture.pluginData());
    }

    @Test
    void exposesPublicUmlVocabulary() {
        assertThat(Uml.structuralTypes()).containsExactly("Package", "Class", "Interface", "DataType", "Enumeration");
        assertThat(Uml.activityTypes()).containsExactly(
                "Activity",
                "Action",
                "InitialNode",
                "ActivityFinalNode",
                "DecisionNode",
                "MergeNode",
                "ForkNode",
                "JoinNode",
                "ObjectNode");
        assertThat(Uml.relationshipTypes()).containsExactly(
                "Association",
                "Composition",
                "Aggregation",
                "Generalization",
                "Realization",
                "Dependency",
                "ControlFlow",
                "ObjectFlow",
                "Message");
    }

    @Test
    void acceptsUmlSequenceVocabulary() throws Exception {
        Fixture fixture = loadUmlSequenceFixture();

        for (String type : new String[]{
                "Interaction",
                "Lifeline",
                "ExecutionSpecification",
                "DestructionOccurrenceSpecification",
                "Gate",
                "CombinedFragment",
                "InteractionOperand"}) {
            Uml.validateElementType(type, "$.type");
        }
        Uml.validateRelationshipType("Message", "$.type");
        Uml.validateRelationshipEndpointTypes(
                "Message",
                "Lifeline",
                "DestructionOccurrenceSpecification",
                "$.relationship");
        Uml.validateSource(fixture.source(), fixture.pluginData());
    }

    @Test
    void acceptsAdditionalSequenceNodeTypesInSequenceViews() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(source -> {
            addSequenceNode(source, "execution-service", "ExecutionSpecification", "Service execution");
            addSequenceNode(source, "gate-inbound", "Gate", "Inbound gate");
        });

        Uml.validateSource(fixture.source(), fixture.pluginData());
    }

    @Test
    void acceptsUmlSequenceCombinedFragments() throws Exception {
        Fixture fixture = loadUmlSequenceFragmentsFixture();

        Uml.validateSource(fixture.source(), fixture.pluginData());
    }

    @Test
    void rejectsUnknownCombinedFragmentKeyword() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> nodeUmlProperties(source, "cf-availability").put("operator", "unknownOperator"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).contains("properties.uml.operator");
    }

    @Test
    void rejectsOptFragmentWithMultipleOperands() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> replaceTextArray(
                        nodeUmlProperties(source, "cf-coupon"),
                        "operands",
                        "op-coupon",
                        "op-in-stock"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.path()).contains("properties.uml.operands");
    }

    @Test
    void rejectsLoopFragmentWithMultipleOperands() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> replaceTextArray(
                        nodeUmlProperties(source, "cf-retry"),
                        "operands",
                        "op-retry",
                        "op-charge"),
                "$.nodes[10].properties.uml.operands");
    }

    @Test
    void rejectsAltFragmentWithOneOperand() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> replaceTextArray(
                        nodeUmlProperties(source, "cf-availability"),
                        "operands",
                        "op-in-stock"),
                "$.nodes[5].properties.uml.operands");
    }

    @Test
    void rejectsParFragmentWithOneOperand() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> replaceTextArray(
                        nodeUmlProperties(source, "cf-parallel-closeout"),
                        "operands",
                        "op-charge"),
                "$.nodes[12].properties.uml.operands");
    }

    @Test
    void rejectsMalformedCombinedFragmentRequiredProperties() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").remove("interaction"),
                "$.nodes[5].properties.uml.interaction");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").put("interaction", 3),
                "$.nodes[5].properties.uml.interaction");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").remove("operator"),
                "$.nodes[5].properties.uml.operator");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").put("operator", 3),
                "$.nodes[5].properties.uml.operator");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").remove("operands"),
                "$.nodes[5].properties.uml.operands");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").put("operands", "op-in-stock"),
                "$.nodes[5].properties.uml.operands");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").putArray("operands"),
                "$.nodes[5].properties.uml.operands");
    }

    @Test
    void rejectsMalformedCombinedFragmentCoveredProperty() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "cf-availability").put("covered", "customer"),
                "$.nodes[5].properties.uml.covered");
        assertUmlSequenceFragmentsMutationRejected(
                source -> {
                    var covered = JsonSupport.objectMapper().createArrayNode();
                    covered.add(3);
                    nodeUmlProperties(source, "cf-availability").set("covered", covered);
                },
                "$.nodes[5].properties.uml.covered[0]");
    }

    @Test
    void rejectsMalformedInteractionOperandRequiredProperties() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").remove("interaction"),
                "$.nodes[6].properties.uml.interaction");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").put("interaction", 3),
                "$.nodes[6].properties.uml.interaction");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").remove("combined_fragment"),
                "$.nodes[6].properties.uml.combined_fragment");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").put("combined_fragment", 3),
                "$.nodes[6].properties.uml.combined_fragment");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").remove("order"),
                "$.nodes[6].properties.uml.order");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").put("order", 1.5),
                "$.nodes[6].properties.uml.order");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").remove("fragments"),
                "$.nodes[6].properties.uml.fragments");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").put("fragments", "m1"),
                "$.nodes[6].properties.uml.fragments");
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").putArray("fragments"),
                "$.nodes[6].properties.uml.fragments");
    }

    @Test
    void rejectsMalformedInteractionOperandGuard() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> nodeUmlProperties(source, "op-in-stock").put("guard", 3),
                "$.nodes[6].properties.uml.guard");
    }

    @Test
    void rejectsOperandListedByDifferentCombinedFragment() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> replaceTextArray(
                        nodeUmlProperties(source, "cf-availability"),
                        "operands",
                        "op-in-stock",
                        "op-coupon"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.path()).contains("properties.uml.operands[1]");
    }

    @Test
    void rejectsOperandMessageFromDifferentInteraction() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            addSequenceNode(source, "interaction-return-order", "Interaction", "Return Order");
            relationshipUmlProperties(source, "m5").put("interaction", "interaction-return-order");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[9].properties.uml.fragments[0]");
    }

    @Test
    void rejectsCombinedFragmentOperandMissingFromSequenceView() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> removeViewNode(source, "op-backorder"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path())
                .isEqualTo("$.nodes[5].properties.uml.operands[1]");
    }

    @Test
    void rejectsOperandMessageFragmentMissingFromSequenceView() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> removeViewRelationship(source, "m5"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path())
                .isEqualTo("$.nodes[9].properties.uml.fragments[0]");
    }

    @Test
    void rejectsOperandOwningCombinedFragmentMissingFromSequenceView() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> removeViewNode(source, "cf-availability"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path())
                .isEqualTo("$.nodes[6].properties.uml.combined_fragment");
    }

    @Test
    void rejectsCombinedFragmentCoveredLifelineMissingFromSequenceView() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> removeViewNode(source, "inventory"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path())
                .isEqualTo("$.nodes[5].properties.uml.covered[2]");
    }

    @Test
    void rejectsSelectedOperandMissingFromOwningCombinedFragmentOperands() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "m2");
            addInteractionOperand(
                    source,
                    "op-availability-extra",
                    "Availability Extra",
                    "cf-availability",
                    2,
                    "m1");
            nodeUmlProperties(source, "op-backorder").put("order", 1);
            replaceTextArray(
                    nodeUmlProperties(source, "cf-availability"),
                    "operands",
                    "op-backorder",
                    "op-availability-extra");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.combined_fragment");
    }

    @Test
    void rejectsOperandInteractionDifferentFromOwningFragment() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            addSequenceNode(source, "interaction-return-order", "Interaction", "Return Order");
            nodeUmlProperties(source, "op-in-stock").put("interaction", "interaction-return-order");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[5].properties.uml.operands[0]");
    }

    @Test
    void rejectsCoveredLifelineInteractionDifferentFromCombinedFragment() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            addSequenceNode(source, "interaction-return-order", "Interaction", "Return Order");
            nodeUmlProperties(source, "inventory").put("interaction", "interaction-return-order");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[5].properties.uml.covered[2]");
    }

    @Test
    void rejectsNestedCombinedFragmentInteractionDifferentFromOperand() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            addSequenceNode(source, "interaction-return-order", "Interaction", "Return Order");
            nodeUmlProperties(source, "cf-coupon").put("interaction", "interaction-return-order");
            replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "cf-coupon");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.fragments[0]");
    }

    @Test
    void rejectsOperandDirectlyNestingOwningCombinedFragment() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "cf-availability"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.fragments[0]");
    }

    @Test
    void rejectsNestedCombinedFragmentCycle() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "cf-coupon");
            replaceTextArray(nodeUmlProperties(source, "op-coupon"), "fragments", "cf-availability");
            replaceTextArray(nodeUmlProperties(source, "cf-coupon"), "covered", "customer", "service", "inventory");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.fragments[0]");
    }

    @Test
    void rejectsMessageFragmentEndpointOutsideOwningFragmentCoverage() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> replaceTextArray(nodeUmlProperties(source, "cf-coupon"), "covered", "customer"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[9].properties.uml.fragments[0]");
    }

    @Test
    void rejectsNestedCombinedFragmentCoverageOutsideOwningFragmentCoverage() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "cf-coupon");
            replaceTextArray(nodeUmlProperties(source, "cf-availability"), "covered", "customer");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.fragments[0]");
    }

    @Test
    void rejectsRepeatedMessageFragmentWithinOperand() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "m1", "m1"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.fragments[1]");
    }

    @Test
    void rejectsOperandMessageFragmentsOutOfSequenceOrder() throws Exception {
        assertUmlSequenceFragmentsMutationRejected(
                source -> replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "m2", "m1"),
                "$.nodes[6].properties.uml.fragments[1]");
    }

    @Test
    void rejectsMessageFragmentReusedAcrossSiblingOperands() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> replaceTextArray(nodeUmlProperties(source, "op-backorder"), "fragments", "m1"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[7].properties.uml.fragments[0]");
    }

    @Test
    void rejectsNestedCombinedFragmentReusedAcrossSiblingOperands() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            replaceTextArray(nodeUmlProperties(source, "op-in-stock"), "fragments", "cf-coupon");
            replaceTextArray(nodeUmlProperties(source, "op-backorder"), "fragments", "cf-coupon");
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[7].properties.uml.fragments[0]");
    }

    @Test
    void rejectsMalformedOperandOrderAtOperandPropertyPath() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> nodeUmlProperties(source, "op-in-stock").put("order", 0));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[6].properties.uml.order");
    }

    @Test
    void rejectsDuplicateOperandOrder() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(
                source -> nodeUmlProperties(source, "op-backorder").put("order", 1));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[5].properties.uml.operands[1]");
    }

    @Test
    void rejectsOperandOrderDifferentFromListOrder() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(source -> {
            nodeUmlProperties(source, "op-in-stock").put("order", 2);
            nodeUmlProperties(source, "op-backorder").put("order", 1);
        });

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[5].properties.uml.operands[0]");
    }

    @Test
    void rejectsUnknownUmlNodeType() throws Exception {
        Fixture fixture = loadUmlFixture();
        var nodes = new java.util.ArrayList<>(fixture.source().nodes());
        var first = nodes.getFirst();
        nodes.set(0, new dev.dediren.contracts.source.SourceNode(
                first.id(),
                "Service",
                first.label(),
                first.properties()));
        var source = new SourceDocument(
                fixture.source().modelSchemaVersion(),
                fixture.source().fragments(),
                fixture.source().requiredPlugins(),
                nodes,
                fixture.source().relationships(),
                fixture.source().plugins());

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(source, fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.nodes[0].type");
    }

    @Test
    void rejectsInvalidMultiplicity() {
        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateMultiplicity("2..1", "$.multiplicity"));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_MULTIPLICITY_INVALID");
    }

    @Test
    void acceptsValidMultiplicities() throws Exception {
        for (String value : new String[]{"1..*", "0..1", "1", "*"}) {
            Uml.validateMultiplicity(value, "$.multiplicity");
        }
    }

    @Test
    void rejectsClassViewWithActivityNode() throws Exception {
        Fixture fixture = loadUmlFixture();
        var views = new java.util.ArrayList<>(fixture.pluginData().views());
        var view = views.getFirst();
        var nodes = new java.util.ArrayList<>(view.nodes());
        nodes.add("action-submit");
        views.set(0, new dev.dediren.contracts.source.GenericGraphView(
                view.id(),
                view.label(),
                view.kind(),
                nodes,
                view.relationships(),
                view.layoutPreferences(),
                view.groups()));
        var data = new GenericGraphPluginData(fixture.pluginData().semanticProfile(), views);

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), data));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT");
    }

    @Test
    void rejectsSequenceViewWithStructuralNode() throws Exception {
        Fixture fixture = loadUmlFixture();
        var views = new java.util.ArrayList<>(fixture.pluginData().views());
        var view = views.getFirst();
        views.set(0, new dev.dediren.contracts.source.GenericGraphView(
                view.id(),
                view.label(),
                dev.dediren.contracts.source.GenericGraphViewKind.UML_SEQUENCE,
                view.nodes(),
                view.relationships(),
                view.layoutPreferences(),
                view.groups()));
        var data = new GenericGraphPluginData(fixture.pluginData().semanticProfile(), views);

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), data));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT");
        assertThat(error.value()).isEqualTo("Package in uml-sequence");
        assertThat(error.path()).isEqualTo("$.plugins.generic-graph.views[0].nodes[0]");
    }

    @Test
    void rejectsMessageToClassEndpoint() throws Exception {
        Fixture fixture = loadUmlSequenceFixture();
        var nodes = new java.util.ArrayList<>(fixture.source().nodes());
        var service = nodes.get(2);
        nodes.set(2, new dev.dediren.contracts.source.SourceNode(
                service.id(),
                "Class",
                service.label(),
                service.properties()));
        var source = new SourceDocument(
                fixture.source().modelSchemaVersion(),
                fixture.source().fragments(),
                fixture.source().requiredPlugins(),
                nodes,
                fixture.source().relationships(),
                fixture.source().plugins());

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(source, fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(error.value()).isEqualTo("Message: Lifeline -> Class");
        assertThat(error.path()).isEqualTo("$.relationships[0]");
    }

    @Test
    void rejectsMessageWithoutSequenceOrder() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).remove("sequence"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("Message.sequence");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.sequence");
    }

    @Test
    void rejectsMessageWithNonObjectUmlProperties() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageProperties(source).put("uml", "not-an-object"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("Message.sequence");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.sequence");
    }

    @Test
    void rejectsMessageWithNonPositiveSequenceOrder() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).put("sequence", 0));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("0");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.sequence");
    }

    @Test
    void rejectsMessageWithNonIntegerSequenceOrder() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).put("sequence", 1.5));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("1.5");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.sequence");
    }

    @Test
    void rejectsMessageWithNullSequenceOrder() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).set("sequence", NullNode.getInstance()));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("null");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.sequence");
    }

    @Test
    void rejectsUnknownMessageSort() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).put("message_sort", "lostMessage"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("lostMessage");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.message_sort");
    }

    @Test
    void rejectsMessageWithNonTextualMessageSort() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).put("message_sort", 1));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID");
        assertThat(error.value()).isEqualTo("1");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.message_sort");
    }

    @Test
    void acceptsMessageWithoutMessageSort() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).remove("message_sort"));

        Uml.validateSource(fixture.source(), fixture.pluginData());
    }

    @Test
    void acceptsSupportedMessageSorts() throws Exception {
        for (String sort : new String[]{
                "synchCall",
                "asynchCall",
                "asynchSignal",
                "reply",
                "createMessage",
                "deleteMessage"}) {
            Fixture fixture = loadMutatedUmlSequenceFixture(
                    source -> firstMessageUmlProperties(source).put("message_sort", sort));

            Uml.validateSource(fixture.source(), fixture.pluginData());
        }
    }

    private static Fixture loadUmlFixture() throws Exception {
        var source = JsonSupport.objectMapper().readValue(
                Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-basic.json")),
                SourceDocument.class);
        var data = JsonSupport.objectMapper().treeToValue(
                source.plugins().get("generic-graph"),
                GenericGraphPluginData.class);
        return new Fixture(source, data);
    }

    private static Fixture loadUmlSequenceFixture() throws Exception {
        var source = JsonSupport.objectMapper().readValue(
                Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-sequence-basic.json")),
                SourceDocument.class);
        var data = JsonSupport.objectMapper().treeToValue(
                source.plugins().get("generic-graph"),
                GenericGraphPluginData.class);
        return new Fixture(source, data);
    }

    private static Fixture loadUmlSequenceFragmentsFixture() throws Exception {
        var source = JsonSupport.objectMapper().readValue(
                Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-sequence-fragments.json")),
                SourceDocument.class);
        var data = JsonSupport.objectMapper().treeToValue(
                source.plugins().get("generic-graph"),
                GenericGraphPluginData.class);
        return new Fixture(source, data);
    }

    private static Fixture loadMutatedUmlSequenceFixture(java.util.function.Consumer<ObjectNode> mutate)
            throws Exception {
        var sourceJson = (ObjectNode) JsonSupport.objectMapper().readTree(
                Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-sequence-basic.json")));
        mutate.accept(sourceJson);
        var source = JsonSupport.objectMapper().treeToValue(sourceJson, SourceDocument.class);
        var data = JsonSupport.objectMapper().treeToValue(
                source.plugins().get("generic-graph"),
                GenericGraphPluginData.class);
        return new Fixture(source, data);
    }

    private static Fixture loadMutatedUmlSequenceFragmentsFixture(Consumer<ObjectNode> mutate)
            throws Exception {
        var sourceJson = (ObjectNode) JsonSupport.objectMapper().readTree(
                Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-sequence-fragments.json")));
        mutate.accept(sourceJson);
        var source = JsonSupport.objectMapper().treeToValue(sourceJson, SourceDocument.class);
        var data = JsonSupport.objectMapper().treeToValue(
                source.plugins().get("generic-graph"),
                GenericGraphPluginData.class);
        return new Fixture(source, data);
    }

    private static void assertUmlSequenceFragmentsMutationRejected(
            Consumer<ObjectNode> mutate,
            String expectedPath) throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFragmentsFixture(mutate);

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
        assertThat(error.path()).isEqualTo(expectedPath);
    }

    private static ObjectNode firstMessageUmlProperties(ObjectNode source) {
        return (ObjectNode) source.get("relationships").get(0).get("properties").get("uml");
    }

    private static ObjectNode firstMessageProperties(ObjectNode source) {
        return (ObjectNode) source.get("relationships").get(0).get("properties");
    }

    private static ObjectNode nodeUmlProperties(ObjectNode source, String id) {
        return (ObjectNode) nodeById(source, id).get("properties").get("uml");
    }

    private static ObjectNode nodeById(ObjectNode source, String id) {
        for (var node : source.get("nodes")) {
            if (id.equals(node.get("id").asText())) {
                return (ObjectNode) node;
            }
        }
        throw new IllegalArgumentException("Unknown source node id: " + id);
    }

    private static ObjectNode relationshipUmlProperties(ObjectNode source, String id) {
        return (ObjectNode) relationshipById(source, id).get("properties").get("uml");
    }

    private static ObjectNode relationshipById(ObjectNode source, String id) {
        for (var relationship : source.get("relationships")) {
            if (id.equals(relationship.get("id").asText())) {
                return (ObjectNode) relationship;
            }
        }
        throw new IllegalArgumentException("Unknown source relationship id: " + id);
    }

    private static void replaceTextArray(ObjectNode object, String field, String... values) {
        var array = JsonSupport.objectMapper().createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        object.set(field, array);
    }

    private static void removeViewNode(ObjectNode source, String id) {
        removeTextValue((ArrayNode) source.get("plugins").get("generic-graph").get("views").get(0).get("nodes"), id);
    }

    private static void removeViewRelationship(ObjectNode source, String id) {
        removeTextValue(
                (ArrayNode) source.get("plugins").get("generic-graph").get("views").get(0).get("relationships"),
                id);
    }

    private static void removeTextValue(ArrayNode values, String id) {
        for (int index = 0; index < values.size(); index++) {
            if (id.equals(values.get(index).asText())) {
                values.remove(index);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown array value: " + id);
    }

    private static void addSequenceNode(ObjectNode source, String id, String type, String label) {
        var node = JsonSupport.objectMapper().createObjectNode();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.set("properties", JsonSupport.objectMapper().createObjectNode()
                .set("uml", JsonSupport.objectMapper().createObjectNode()));
        ((ArrayNode) source.get("nodes")).add(node);
        ((ArrayNode) source.get("plugins").get("generic-graph").get("views").get(0).get("nodes")).add(id);
    }

    private static void addInteractionOperand(
            ObjectNode source,
            String id,
            String label,
            String combinedFragment,
            int order,
            String... fragments) {
        addSequenceNode(source, id, "InteractionOperand", label);
        ObjectNode umlProperties = nodeUmlProperties(source, id);
        umlProperties.put("interaction", "interaction-place-order");
        umlProperties.put("combined_fragment", combinedFragment);
        umlProperties.put("order", order);
        replaceTextArray(umlProperties, "fragments", fragments);
    }

    private static Path workspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("schemas/model.schema.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }

    private record Fixture(SourceDocument source, GenericGraphPluginData pluginData) {
    }
}

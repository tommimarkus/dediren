package dev.dediren.uml;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.SourceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Uml.validateElementType("Lifeline", "$.type");
        Uml.validateRelationshipType("Message", "$.type");
        Uml.validateRelationshipEndpointTypes(
                "Message",
                "Lifeline",
                "DestructionOccurrenceSpecification",
                "$.relationship");
        Uml.validateSource(fixture.source(), fixture.pluginData());
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

        assertThat(error.value()).isEqualTo("1.5");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.sequence");
    }

    @Test
    void rejectsUnknownMessageSort() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).put("message_sort", "lostMessage"));

        UmlValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                UmlValidationException.class,
                () -> Uml.validateSource(fixture.source(), fixture.pluginData()));

        assertThat(error.value()).isEqualTo("lostMessage");
        assertThat(error.path()).isEqualTo("$.relationships[0].properties.uml.message_sort");
    }

    @Test
    void acceptsMessageWithoutMessageSort() throws Exception {
        Fixture fixture = loadMutatedUmlSequenceFixture(
                source -> firstMessageUmlProperties(source).remove("message_sort"));

        Uml.validateSource(fixture.source(), fixture.pluginData());
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

    private static ObjectNode firstMessageUmlProperties(ObjectNode source) {
        return (ObjectNode) source.get("relationships").get(0).get("properties").get("uml");
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

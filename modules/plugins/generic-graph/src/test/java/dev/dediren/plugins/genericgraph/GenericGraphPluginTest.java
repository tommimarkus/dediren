package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GenericGraphPluginTest {
    @Test
    void capabilitiesReportSemanticValidationAndProjection() throws Exception {
        PluginResult result = Main.executeForTesting(new String[]{"capabilities"}, "");

        JsonNode capabilities = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(capabilities.get("id").asText()).isEqualTo("generic-graph");
        assertThat(capabilities.get("capabilities")).extracting(JsonNode::toString)
                .asString()
                .contains("semantic-validation", "projection");
    }

    @Test
    void projectsBasicViewToLayoutRequest() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                fixture("fixtures/source/valid-basic.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/layout_request_schema_version").asText()).isEqualTo("layout-request.schema.v1");
        assertThat(data.at("/view_id").asText()).isEqualTo("main");
        assertThat(data.get("nodes")).hasSize(2);
        assertThat(data.get("edges")).hasSize(1);
        assertThat(data.at("/edges/0/relationship_type").asText()).isEqualTo("generic.calls");
    }

    @Test
    void rejectsDuplicateViewIds() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        { "id": "main", "label": "First", "nodes": ["client"], "relationships": [] },
                        { "id": "main", "label": "Second", "nodes": ["api"], "relationships": [] }
                      ]
                    }
                  }
                }
                """);

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(envelope.at("/diagnostics/0/code").asText())
                .isEqualTo("DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID");
    }

    @Test
    void validatesArchimateSourceSemantics() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"},
                fixture("fixtures/source/valid-archimate-oef.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/semantic_validation_result_schema_version").asText())
                .isEqualTo("semantic-validation-result.schema.v1");
        assertThat(data.at("/semantic_profile").asText()).isEqualTo("archimate");
        assertThat(data.at("/node_count").asInt()).isEqualTo(2);
        assertThat(data.at("/relationship_count").asInt()).isEqualTo(1);
    }

    @Test
    void validatesUmlProfile() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "uml"},
                fixture("fixtures/source/valid-uml-basic.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/semantic_profile").asText()).isEqualTo("uml");
        assertThat(data.at("/node_count").asInt()).isEqualTo(10);
        assertThat(data.at("/relationship_count").asInt()).isEqualTo(6);
    }

    @Test
    void projectsArchimateRenderMetadata() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "render-metadata", "--view", "main"},
                fixture("fixtures/source/valid-archimate-oef.json"));

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/render_metadata_schema_version").asText()).isEqualTo("render-metadata.schema.v1");
        assertThat(data.at("/semantic_profile").asText()).isEqualTo("archimate");
        assertThat(data.at("/nodes/orders-component/type").asText()).isEqualTo("ApplicationComponent");
        assertThat(data.at("/edges/orders-realizes-service/type").asText()).isEqualTo("Realization");
    }

    private static JsonNode okData(PluginResult result) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        return envelope.get("data");
    }

    private static String fixture(String path) throws Exception {
        return Files.readString(workspaceRoot().resolve(path));
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
}

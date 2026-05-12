package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonContractsTest {
    private final ObjectMapper mapper = JsonContracts.objectMapper();

    @Test
    void readsLayoutRequestAndWritesLayoutResultEnvelope() throws Exception {
        String json = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": []
            }
            """;

        JsonContracts.LayoutRequest request =
            mapper.readValue(json, JsonContracts.LayoutRequest.class);

        JsonContracts.LayoutResult result = new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            List.of(new JsonContracts.LaidOutNode(
                "client", "client", "client", 12.0, 24.0, 160.0, 80.0, "Client")),
            List.of(),
            List.of(),
            List.of());

        String envelope = EnvelopeWriter.ok(mapper, result);

        assertEquals("main", request.view_id());
        JsonNode data = EnvelopeAssertions.okData(envelope);
        assertEquals("layout-result.schema.v1", data.path("layout_result_schema_version").asText());
    }
}

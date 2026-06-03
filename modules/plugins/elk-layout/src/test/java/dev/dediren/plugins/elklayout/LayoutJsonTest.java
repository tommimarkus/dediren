package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutJsonTest {
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

        LayoutRequest request =
            JsonSupport.objectMapper().readValue(json, LayoutRequest.class);

        LayoutResult result = new LayoutResult(
            "layout-result.schema.v1",
            request.viewId(),
            List.of(new LaidOutNode(
                "client", "client", "client", 12.0, 24.0, 160.0, 80.0, "Client")),
            List.of(),
            List.of(),
            List.of());
        ElkLayoutRenderArtifacts.write(result);

        String envelope = EnvelopeWriter.ok(result);

        assertEquals("main", request.viewId());
        JsonNode data = EnvelopeAssertions.okData(envelope);
        assertEquals("layout-result.schema.v1", data.path("layout_result_schema_version").asText());
    }

    @Test
    void readsLayoutPreferences() throws Exception {
        String json = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": [],
              "layout_preferences": {
                "direction": "down",
                "density": "readable",
                "wrapping": "off",
                "routing": {
                  "style": "orthogonal",
                  "profile": "spacious",
                  "endpoint_merging": "off"
                }
              }
            }
            """;

        LayoutRequest request =
            JsonSupport.objectMapper().readValue(json, LayoutRequest.class);

        assertEquals(LayoutDirection.DOWN, request.layoutPreferences().direction());
        assertEquals(LayoutDensity.READABLE, request.layoutPreferences().density());
        assertEquals(LayoutWrapping.OFF, request.layoutPreferences().wrapping());
        assertEquals(LayoutRoutingStyle.ORTHOGONAL, request.layoutPreferences().routing().style());
        assertEquals(LayoutRoutingProfile.SPACIOUS, request.layoutPreferences().routing().profile());
        assertEquals(LayoutEndpointMerging.OFF, request.layoutPreferences().routing().endpointMerging());
    }
}

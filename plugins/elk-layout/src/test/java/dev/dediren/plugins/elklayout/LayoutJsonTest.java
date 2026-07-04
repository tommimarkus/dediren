package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.*;
import org.junit.jupiter.api.Test;

class LayoutJsonTest {
  @Test
  void readsLayoutPreferences() throws Exception {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": {
                "mode": "packed",
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

    LayoutRequest request = JsonSupport.objectMapper().readValue(json, LayoutRequest.class);

    assertEquals(LayoutMode.PACKED, request.layoutPreferences().mode());
    assertEquals(LayoutDirection.DOWN, request.layoutPreferences().direction());
    assertEquals(LayoutDensity.READABLE, request.layoutPreferences().density());
    assertEquals(LayoutWrapping.OFF, request.layoutPreferences().wrapping());
    assertEquals(LayoutRoutingStyle.ORTHOGONAL, request.layoutPreferences().routing().style());
    assertEquals(LayoutRoutingProfile.SPACIOUS, request.layoutPreferences().routing().profile());
    assertEquals(
        LayoutEndpointMerging.OFF, request.layoutPreferences().routing().endpointMerging());
  }

  @Test
  void readsSplineRoutingStyle() throws Exception {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "routing": { "style": "spline" } }
            }
            """;

    LayoutRequest request =
        LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes()));

    assertEquals(LayoutRoutingStyle.SPLINE, request.layoutPreferences().routing().style());
  }
}

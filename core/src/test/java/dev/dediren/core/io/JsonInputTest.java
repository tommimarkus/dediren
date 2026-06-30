package dev.dediren.core.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LayoutRequest;
import org.junit.jupiter.api.Test;

class JsonInputTest {
    @Test
    void parseCommandDataReadsRawJson() throws Exception {
        LayoutRequest request = JsonInput.parseCommandData("""
                {
                  "layout_request_schema_version": "layout-request.schema.v1",
                  "view_id": "main",
                  "nodes": [],
                  "edges": [],
                  "groups": [],
                  "constraints": []
                }
                """, LayoutRequest.class);

        assertThat(request.layoutRequestSchemaVersion()).isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
        assertThat(request.viewId()).isEqualTo("main");
    }

    @Test
    void parseCommandDataReadsDataFromSuccessEnvelope() throws Exception {
        LayoutRequest request = JsonInput.parseCommandData("""
                {
                  "envelope_schema_version": "envelope.schema.v1",
                  "status": "ok",
                  "data": {
                    "layout_request_schema_version": "layout-request.schema.v1",
                    "view_id": "main",
                    "nodes": [],
                    "edges": [],
                    "groups": [],
                    "constraints": []
                  },
                  "diagnostics": []
                }
                """, LayoutRequest.class);

        assertThat(request.viewId()).isEqualTo("main");
    }

    @Test
    void parseCommandDataRejectsEnvelopeWithoutData() {
        assertThatThrownBy(() -> JsonInput.parseCommandData("""
                {
                  "envelope_schema_version": "envelope.schema.v1",
                  "status": "ok",
                  "diagnostics": []
                }
                """, LayoutRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not contain data");
    }
}

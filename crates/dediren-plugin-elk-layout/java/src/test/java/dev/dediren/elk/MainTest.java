package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void invalidJsonReturnsStructuredErrorEnvelope() throws Exception {
        ByteArrayInputStream stdin =
            new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_INPUT_INVALID_JSON");
    }

    @Test
    void validRequestReturnsOkEnvelopeWithLayoutResult() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80},
                {"id": "api", "label": "API", "source_id": "api", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [
                {"id": "client-calls-api", "source": "client", "target": "api", "label": "calls", "source_id": "client-calls-api"}
              ],
              "groups": [],
              "labels": [],
              "constraints": []
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        JsonNode data = EnvelopeAssertions.okData(text);
        assertEquals("layout-result.schema.v1", data.path("layout_result_schema_version").asText());
        assertEquals("client-calls-api", data.path("edges").get(0).path("id").asText());
    }

    @Test
    void requestMissingRequiredNodeLabelReturnsErrorEnvelope() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "source_id": "client", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": []
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_LAYOUT_FAILED");
    }

    @Test
    void requestWithScalarGroupProvenanceReturnsErrorEnvelope() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [],
              "groups": [
                {"id": "group-1", "label": "Group", "members": ["client"], "provenance": "not-object"}
              ],
              "labels": [],
              "constraints": []
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_INPUT_INVALID_JSON");
    }

    @Test
    void requestWithUnknownGroupProvenanceShapeReturnsErrorEnvelope() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [],
              "groups": [
                {"id": "group-1", "label": "Group", "members": ["client"], "provenance": {"unknown": {}}}
              ],
              "labels": [],
              "constraints": []
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_INPUT_INVALID_JSON");
    }

    @Test
    void requestWithOverflowingWidthHintReturnsErrorEnvelope() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [
                {"id": "client", "label": "Client", "source_id": "client", "width_hint": 1e309, "height_hint": 80}
              ],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": []
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        JsonNode envelope = EnvelopeAssertions.parseJson(text);
        assertEquals("error", envelope.path("status").asText());
        List<String> codes = EnvelopeAssertions.diagnosticCodes(envelope);
        assertTrue(
            codes.contains("DEDIREN_ELK_LAYOUT_FAILED")
                || codes.contains("DEDIREN_ELK_INPUT_INVALID_JSON"),
            "expected ELK layout or input diagnostic, got " + codes
        );
    }
}

package dev.dediren.elk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class JsonContracts {
    private JsonContracts() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    record LayoutRequest(
        String layout_request_schema_version,
        String view_id,
        List<LayoutNode> nodes,
        List<LayoutEdge> edges,
        List<LayoutGroup> groups,
        List<LayoutLabel> labels,
        List<LayoutConstraint> constraints) {
    }

    record LayoutNode(
        String id,
        String label,
        String source_id,
        Double width_hint,
        Double height_hint) {
    }

    record LayoutEdge(
        String id,
        String source,
        String target,
        String label,
        String source_id) {
    }

    record LayoutGroup(
        String id,
        String label,
        List<String> members,
        GroupProvenance provenance) {
    }

    record GroupProvenance(Boolean visual_only, SemanticBacked semantic_backed) {
    }

    record SemanticBacked(String source_id) {
    }

    record LayoutLabel(String owner_id, String text) {
    }

    record LayoutConstraint(String id, String kind, List<String> subjects) {
    }

    record LayoutResult(
        String layout_result_schema_version,
        String view_id,
        List<LaidOutNode> nodes,
        List<LaidOutEdge> edges,
        List<LaidOutGroup> groups,
        List<Diagnostic> warnings) {
    }

    record LaidOutNode(
        String id,
        String source_id,
        String projection_id,
        double x,
        double y,
        double width,
        double height,
        String label) {
    }

    record LaidOutEdge(
        String id,
        String source,
        String target,
        String source_id,
        String projection_id,
        List<String> routing_hints,
        List<Point> points,
        String label) {
    }

    record LaidOutGroup(
        String id,
        String source_id,
        String projection_id,
        GroupProvenance provenance,
        double x,
        double y,
        double width,
        double height,
        List<String> members,
        String label) {
    }

    record Point(double x, double y) {
    }

    record Diagnostic(String code, String severity, String message, String path) {
    }

    record CommandEnvelope<T>(
        String envelope_schema_version,
        String status,
        T data,
        List<Diagnostic> diagnostics) {
    }
}

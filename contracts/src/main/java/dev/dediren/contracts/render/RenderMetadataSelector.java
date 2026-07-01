package dev.dediren.contracts.render;

import com.fasterxml.jackson.databind.JsonNode;

public record RenderMetadataSelector(String type, String sourceId, JsonNode properties) {}

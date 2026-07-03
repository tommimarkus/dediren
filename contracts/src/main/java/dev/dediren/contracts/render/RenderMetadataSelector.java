package dev.dediren.contracts.render;

import tools.jackson.databind.JsonNode;

public record RenderMetadataSelector(String type, String sourceId, JsonNode properties) {}

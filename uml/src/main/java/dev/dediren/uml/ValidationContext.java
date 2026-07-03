package dev.dediren.uml;

import java.util.Map;
import tools.jackson.databind.JsonNode;

record ValidationContext(
    Map<String, String> nodeTypes,
    Map<String, JsonNode> nodeUmlProperties,
    Map<String, String> nodePaths,
    Map<String, String> relationshipTypes,
    Map<String, JsonNode> relationshipUmlProperties,
    Map<String, String> relationshipSources,
    Map<String, String> relationshipTargets) {}

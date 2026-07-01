package dev.dediren.uml;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

record ValidationContext(
    Map<String, String> nodeTypes,
    Map<String, JsonNode> nodeUmlProperties,
    Map<String, String> nodePaths,
    Map<String, String> relationshipTypes,
    Map<String, JsonNode> relationshipUmlProperties,
    Map<String, String> relationshipSources,
    Map<String, String> relationshipTargets) {}

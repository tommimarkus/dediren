package dev.dediren.plugins.mermaid;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Maps parsed Mermaid identity and grouping into the generic-graph source contract. */
final class MermaidMapper {
  private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

  SourceDocument map(ParsedDiagram parsed) {
    Map<String, String> nodeIds =
        normalizedIds(parsed.nodes().stream().map(ParsedNode::id).toList());
    List<SourceNode> nodes = new ArrayList<>();
    for (ParsedNode node : parsed.nodes()) {
      String id = nodeIds.get(node.id());
      Map<String, JsonNode> properties = Map.of();
      if (!id.equals(node.id())) {
        ObjectNode mermaid = JsonSupport.objectMapper().createObjectNode();
        mermaid.put("original_id", node.id());
        properties = Map.of("mermaid", mermaid);
      }
      nodes.add(new SourceNode(id, "generic.node", node.label(), properties));
    }

    Set<String> occupied = new HashSet<>(nodeIds.values());
    List<SourceRelationship> relationships = new ArrayList<>();
    List<String> relationshipIds = new ArrayList<>();
    int nextLink = 1;
    for (ParsedEdge edge : parsed.edges()) {
      String id;
      do {
        id = "link-" + nextLink++;
      } while (!occupied.add(id));
      relationshipIds.add(id);
      relationships.add(
          new SourceRelationship(
              id,
              "generic.link",
              nodeIds.get(edge.source()),
              nodeIds.get(edge.target()),
              edge.label() == null ? "" : edge.label(),
              Map.of()));
    }

    List<String> groupIds =
        normalizedOccurrences(parsed.groups().stream().map(ParsedGroup::id).toList());
    List<GenericGraphViewGroup> groups = new ArrayList<>();
    for (int index = 0; index < parsed.groups().size(); index++) {
      ParsedGroup group = parsed.groups().get(index);
      List<String> members = group.members().stream().map(nodeIds::get).toList();
      groups.add(
          new GenericGraphViewGroup(
              groupIds.get(index),
              group.label(),
              members,
              GenericGraphViewGroupRole.LAYOUT_ONLY,
              null));
    }

    GenericGraphView view =
        new GenericGraphView(
            "main",
            "Imported Mermaid flowchart",
            GenericGraphViewKind.GENERIC,
            nodes.stream().map(SourceNode::id).toList(),
            relationshipIds,
            new LayoutPreferences(parsed.direction(), null, null, null),
            groups);
    JsonNode plugin =
        JsonSupport.objectMapper()
            .valueToTree(
                new GenericGraphPluginData(
                    GenericGraphSemanticProfile.GENERIC_GRAPH, List.of(view)));
    return new SourceDocument(
        "model.schema.v1",
        List.of(),
        List.of(),
        nodes,
        relationships,
        Map.of("generic-graph", plugin));
  }

  private static Map<String, String> normalizedIds(List<String> originals) {
    Set<String> reserved = new LinkedHashSet<>();
    originals.stream().filter(MermaidMapper::valid).forEach(reserved::add);
    Map<String, String> result = new HashMap<>();
    for (String original : originals) {
      if (result.containsKey(original)) {
        continue;
      }
      if (valid(original)) {
        result.put(original, original);
        continue;
      }
      String base = normalize(original);
      String candidate = base;
      int suffix = 2;
      while (!reserved.add(candidate)) {
        candidate = base + "-" + suffix++;
      }
      result.put(original, candidate);
    }
    return Map.copyOf(result);
  }

  private static List<String> normalizedOccurrences(List<String> originals) {
    Set<String> reserved = new LinkedHashSet<>();
    originals.stream().filter(MermaidMapper::valid).forEach(reserved::add);
    List<String> result = new ArrayList<>();
    Set<String> emittedValid = new HashSet<>();
    for (String original : originals) {
      if (valid(original) && emittedValid.add(original)) {
        result.add(original);
        continue;
      }
      String base = valid(original) ? original : normalize(original);
      String candidate = base;
      int suffix = 2;
      while (!reserved.add(candidate)) {
        candidate = base + "-" + suffix++;
      }
      result.add(candidate);
    }
    return List.copyOf(result);
  }

  private static boolean valid(String id) {
    return VALID_ID.matcher(id).matches();
  }

  private static String normalize(String original) {
    StringBuilder normalized = new StringBuilder();
    boolean separator = false;
    for (int index = 0; index < original.length(); ) {
      int codePoint = original.codePointAt(index);
      if (codePoint < 128
          && (Character.isLetterOrDigit(codePoint)
              || codePoint == '.'
              || codePoint == '_'
              || codePoint == '-')) {
        normalized.appendCodePoint(codePoint);
        separator = false;
      } else if (codePoint < 128) {
        if (!separator
            && !normalized.isEmpty()
            && normalized.charAt(normalized.length() - 1) != '-') {
          normalized.append('-');
        }
        separator = true;
      } else {
        if (normalized.isEmpty() || normalized.charAt(normalized.length() - 1) != '-') {
          normalized.append('-');
        }
        normalized.append('u').append(String.format(Locale.ROOT, "%04x", codePoint));
        separator = false;
      }
      index += Character.charCount(codePoint);
    }
    while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '-') {
      normalized.setLength(normalized.length() - 1);
    }
    if (normalized.isEmpty()) {
      normalized.append("node");
    }
    if (!Character.isLetterOrDigit(normalized.charAt(0))) {
      normalized.insert(0, "node-");
    }
    return normalized.toString();
  }
}

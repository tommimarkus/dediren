package dev.dediren.core.analysis;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.analysis.DiffResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * Deterministic semantic diff between two validated source models, keyed on stable ids. A pure
 * function of the two documents: same inputs, byte-identical result — every list is sorted by id.
 * Field vocabulary: {@code type}, {@code label}, {@code source}, {@code target}, and {@code
 * properties.<top-level-key>} (shallow property compare). A report, never a merge.
 */
public final class ModelDiff {

  private ModelDiff() {}

  public static DiffResult diff(SourceDocument oldModel, SourceDocument newModel) {
    return new DiffResult(
        ContractVersions.DIFF_RESULT_SCHEMA_VERSION,
        entityChanges(
            byId(oldModel.nodes(), SourceNode::id),
            byId(newModel.nodes(), SourceNode::id),
            ModelDiff::nodeRef,
            ModelDiff::nodeChanges),
        entityChanges(
            byId(oldModel.relationships(), SourceRelationship::id),
            byId(newModel.relationships(), SourceRelationship::id),
            ModelDiff::relationshipRef,
            ModelDiff::relationshipChanges),
        viewChanges(views(oldModel), views(newModel)));
  }

  private static <T> DiffResult.EntityChanges entityChanges(
      Map<String, T> oldById,
      Map<String, T> newById,
      Function<T, DiffResult.EntityRef> ref,
      java.util.function.BiFunction<T, T, List<DiffResult.FieldChange>> changesOf) {
    var added = new ArrayList<DiffResult.EntityRef>();
    var removed = new ArrayList<DiffResult.EntityRef>();
    var changed = new ArrayList<DiffResult.ChangedEntity>();
    for (Map.Entry<String, T> entry : newById.entrySet()) {
      if (!oldById.containsKey(entry.getKey())) {
        added.add(ref.apply(entry.getValue()));
      }
    }
    for (Map.Entry<String, T> entry : oldById.entrySet()) {
      T replacement = newById.get(entry.getKey());
      if (replacement == null) {
        removed.add(ref.apply(entry.getValue()));
      } else {
        List<DiffResult.FieldChange> changes = changesOf.apply(entry.getValue(), replacement);
        if (!changes.isEmpty()) {
          changed.add(new DiffResult.ChangedEntity(entry.getKey(), changes));
        }
      }
    }
    return new DiffResult.EntityChanges(added, removed, changed);
  }

  private static DiffResult.EntityRef nodeRef(SourceNode node) {
    return new DiffResult.EntityRef(node.id(), node.type(), node.label());
  }

  private static DiffResult.EntityRef relationshipRef(SourceRelationship relationship) {
    return new DiffResult.EntityRef(relationship.id(), relationship.type(), relationship.label());
  }

  private static List<DiffResult.FieldChange> nodeChanges(SourceNode oldNode, SourceNode newNode) {
    var changes = new TreeMap<String, DiffResult.FieldChange>();
    stringChange(changes, "type", oldNode.type(), newNode.type());
    stringChange(changes, "label", oldNode.label(), newNode.label());
    propertyChanges(changes, oldNode.properties(), newNode.properties());
    return List.copyOf(changes.values());
  }

  private static List<DiffResult.FieldChange> relationshipChanges(
      SourceRelationship oldRelationship, SourceRelationship newRelationship) {
    var changes = new TreeMap<String, DiffResult.FieldChange>();
    stringChange(changes, "type", oldRelationship.type(), newRelationship.type());
    stringChange(changes, "label", oldRelationship.label(), newRelationship.label());
    stringChange(changes, "source", oldRelationship.source(), newRelationship.source());
    stringChange(changes, "target", oldRelationship.target(), newRelationship.target());
    propertyChanges(changes, oldRelationship.properties(), newRelationship.properties());
    return List.copyOf(changes.values());
  }

  private static void stringChange(
      Map<String, DiffResult.FieldChange> changes, String field, String from, String to) {
    if (!java.util.Objects.equals(from, to)) {
      changes.put(field, new DiffResult.FieldChange(field, textOrNull(from), textOrNull(to)));
    }
  }

  private static JsonNode textOrNull(String value) {
    return value == null ? null : StringNode.valueOf(value);
  }

  private static void propertyChanges(
      Map<String, DiffResult.FieldChange> changes,
      Map<String, JsonNode> oldProperties,
      Map<String, JsonNode> newProperties) {
    var keys = new TreeSet<>(oldProperties.keySet());
    keys.addAll(newProperties.keySet());
    for (String key : keys) {
      JsonNode from = oldProperties.get(key);
      JsonNode to = newProperties.get(key);
      if (!java.util.Objects.equals(from, to)) {
        String field = "properties." + key;
        changes.put(field, new DiffResult.FieldChange(field, from, to));
      }
    }
  }

  private static DiffResult.ViewChanges viewChanges(
      Map<String, GenericGraphView> oldViews, Map<String, GenericGraphView> newViews) {
    var added = new ArrayList<String>(new TreeSet<>(minus(newViews, oldViews)));
    var removed = new ArrayList<String>(new TreeSet<>(minus(oldViews, newViews)));
    var changed = new ArrayList<DiffResult.ChangedView>();
    for (Map.Entry<String, GenericGraphView> entry : oldViews.entrySet()) {
      GenericGraphView replacement = newViews.get(entry.getKey());
      if (replacement == null) {
        continue;
      }
      List<String> nodesAdded = sortedMinus(replacement.nodes(), entry.getValue().nodes());
      List<String> nodesRemoved = sortedMinus(entry.getValue().nodes(), replacement.nodes());
      List<String> relationshipsAdded =
          sortedMinus(replacement.relationships(), entry.getValue().relationships());
      List<String> relationshipsRemoved =
          sortedMinus(entry.getValue().relationships(), replacement.relationships());
      if (!nodesAdded.isEmpty()
          || !nodesRemoved.isEmpty()
          || !relationshipsAdded.isEmpty()
          || !relationshipsRemoved.isEmpty()) {
        changed.add(
            new DiffResult.ChangedView(
                entry.getKey(),
                nodesAdded,
                nodesRemoved,
                relationshipsAdded,
                relationshipsRemoved));
      }
    }
    return new DiffResult.ViewChanges(added, removed, changed);
  }

  private static List<String> minus(
      Map<String, GenericGraphView> left, Map<String, GenericGraphView> right) {
    return left.keySet().stream().filter(id -> !right.containsKey(id)).toList();
  }

  private static List<String> sortedMinus(List<String> left, List<String> right) {
    var result = new TreeSet<>(left);
    right.forEach(result::remove);
    return List.copyOf(result);
  }

  private static <T> Map<String, T> byId(List<T> entities, Function<T, String> id) {
    var byId = new TreeMap<String, T>();
    for (T entity : entities) {
      byId.put(id.apply(entity), entity);
    }
    return byId;
  }

  static Map<String, GenericGraphView> views(SourceDocument document) {
    JsonNode pluginValue = document.plugins().get("generic-graph");
    if (pluginValue == null) {
      return Map.of();
    }
    GenericGraphPluginData data =
        JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
    var byId = new TreeMap<String, GenericGraphView>();
    for (GenericGraphView view : data.views()) {
      byId.put(view.id(), view);
    }
    return byId;
  }
}

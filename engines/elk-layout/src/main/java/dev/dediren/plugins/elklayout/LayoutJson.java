package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;

final class LayoutJson {
  private LayoutJson() {}

  static LayoutRequest readLayoutRequest(InputStream source) throws IOException {
    JsonNode root = JsonSupport.objectMapper().readTree(source);
    rejectExplicitPreferenceNulls(root);
    rejectUnsupportedPreferenceValues(root);
    return JsonSupport.objectMapper().treeToValue(root, LayoutRequest.class);
  }

  /**
   * Runs the layout-preference value validation on an already-parsed, in-memory request. The stream
   * reader ({@link #readLayoutRequest}) performs this while parsing; extracting it here lets the
   * in-memory engine path reject the same inputs with the same {@link
   * LayoutPreferenceValidationException}. The explicit-null rejection is a parse-only concern (a
   * typed request cannot carry explicit JSON nulls) and stays in the stream reader.
   */
  static void validatePreferences(LayoutRequest request) {
    rejectUnsupportedPreferenceValues(JsonSupport.objectMapper().valueToTree(request));
  }

  private static void rejectExplicitPreferenceNulls(JsonNode root) throws MismatchedInputException {
    if (root == null || !root.isObject()) {
      return;
    }

    JsonNode preferences = root.get("layout_preferences");
    if (preferences == null) {
      return;
    }
    rejectNull(preferences, "$.layout_preferences");
    if (!preferences.isObject()) {
      return;
    }

    rejectNull(preferences.get("mode"), "$.layout_preferences.mode");
    rejectNull(preferences.get("direction"), "$.layout_preferences.direction");
    rejectNull(preferences.get("density"), "$.layout_preferences.density");
    rejectNull(preferences.get("wrapping"), "$.layout_preferences.wrapping");

    rejectNull(preferences.get("cycle_breaking"), "$.layout_preferences.cycle_breaking");

    JsonNode layering = preferences.get("layering");
    if (layering != null) {
      rejectNull(layering, "$.layout_preferences.layering");
      if (layering.isObject()) {
        rejectNull(layering.get("strategy"), "$.layout_preferences.layering.strategy");
      }
    }

    JsonNode crossing = preferences.get("crossing");
    if (crossing != null) {
      rejectNull(crossing, "$.layout_preferences.crossing");
      if (crossing.isObject()) {
        rejectNull(crossing.get("strategy"), "$.layout_preferences.crossing.strategy");
        rejectNull(crossing.get("greedy_switch"), "$.layout_preferences.crossing.greedy_switch");
      }
    }

    JsonNode placement = preferences.get("placement");
    if (placement != null) {
      rejectNull(placement, "$.layout_preferences.placement");
      if (placement.isObject()) {
        rejectNull(placement.get("strategy"), "$.layout_preferences.placement.strategy");
      }
    }

    rejectNull(preferences.get("compaction"), "$.layout_preferences.compaction");
    rejectNull(preferences.get("high_degree_nodes"), "$.layout_preferences.high_degree_nodes");
    rejectNull(preferences.get("thoroughness"), "$.layout_preferences.thoroughness");

    rejectNull(preferences.get("algorithm"), "$.layout_preferences.algorithm");

    JsonNode components = preferences.get("components");
    if (components != null) {
      rejectNull(components, "$.layout_preferences.components");
      if (components.isObject()) {
        rejectNull(components.get("separate"), "$.layout_preferences.components.separate");
        rejectNull(components.get("spacing"), "$.layout_preferences.components.spacing");
      }
    }

    JsonNode routing = preferences.get("routing");
    if (routing == null) {
      return;
    }
    rejectNull(routing, "$.layout_preferences.routing");
    if (!routing.isObject()) {
      return;
    }

    rejectNull(routing.get("style"), "$.layout_preferences.routing.style");
    rejectNull(routing.get("profile"), "$.layout_preferences.routing.profile");
    rejectNull(routing.get("endpoint_merging"), "$.layout_preferences.routing.endpoint_merging");
  }

  private static void rejectUnsupportedPreferenceValues(JsonNode root) {
    if (root == null || !root.isObject()) {
      return;
    }
    JsonNode preferences = root.get("layout_preferences");
    if (preferences == null || !preferences.isObject()) {
      return;
    }

    rejectUnsupportedText(
        preferences.get("mode"), "$.layout_preferences.mode", Set.of("auto", "flow", "packed"));
    rejectUnsupportedText(
        preferences.get("direction"),
        "$.layout_preferences.direction",
        Set.of("right", "left", "down", "up"));
    rejectUnsupportedText(
        preferences.get("density"),
        "$.layout_preferences.density",
        Set.of("compact", "readable", "spacious"));
    rejectUnsupportedText(
        preferences.get("wrapping"),
        "$.layout_preferences.wrapping",
        Set.of("auto", "off", "multi-edge"));

    rejectUnsupportedText(
        preferences.get("cycle_breaking"),
        "$.layout_preferences.cycle_breaking",
        Set.of("greedy", "depth-first", "model-order"));

    JsonNode layering = preferences.get("layering");
    if (layering != null && layering.isObject()) {
      rejectUnsupportedText(
          layering.get("strategy"),
          "$.layout_preferences.layering.strategy",
          Set.of(
              "network-simplex",
              "longest-path",
              "coffman-graham",
              "min-width",
              "stretch-width",
              "breadth-first",
              "depth-first"));
    }

    JsonNode crossing = preferences.get("crossing");
    if (crossing != null && crossing.isObject()) {
      rejectUnsupportedText(
          crossing.get("strategy"),
          "$.layout_preferences.crossing.strategy",
          Set.of("layer-sweep", "none"));
      rejectUnsupportedText(
          crossing.get("greedy_switch"),
          "$.layout_preferences.crossing.greedy_switch",
          Set.of("off", "one-sided", "two-sided"));
    }

    JsonNode placement = preferences.get("placement");
    if (placement != null && placement.isObject()) {
      rejectUnsupportedText(
          placement.get("strategy"),
          "$.layout_preferences.placement.strategy",
          Set.of("brandes-koepf", "network-simplex", "linear-segments", "simple"));
    }

    rejectUnsupportedText(
        preferences.get("compaction"),
        "$.layout_preferences.compaction",
        Set.of("off", "left", "right", "balanced"));
    rejectUnsupportedText(
        preferences.get("high_degree_nodes"),
        "$.layout_preferences.high_degree_nodes",
        Set.of("off", "on"));
    rejectUnsupportedText(
        preferences.get("thoroughness"),
        "$.layout_preferences.thoroughness",
        Set.of("low", "normal", "high"));

    rejectUnsupportedText(
        preferences.get("algorithm"), "$.layout_preferences.algorithm", Set.of("layered"));

    JsonNode components = preferences.get("components");
    if (components != null && components.isObject()) {
      rejectUnsupportedText(
          components.get("spacing"),
          "$.layout_preferences.components.spacing",
          Set.of("compact", "readable", "spacious"));
    }

    JsonNode routing = preferences.get("routing");
    if (routing == null || !routing.isObject()) {
      return;
    }
    rejectUnsupportedText(
        routing.get("style"),
        "$.layout_preferences.routing.style",
        Set.of("orthogonal", "polyline", "spline"));
    rejectUnsupportedText(
        routing.get("profile"),
        "$.layout_preferences.routing.profile",
        Set.of("compact", "readable", "spacious"));
    rejectUnsupportedText(
        routing.get("endpoint_merging"),
        "$.layout_preferences.routing.endpoint_merging",
        Set.of("off", "local", "auto"));
  }

  private static void rejectUnsupportedText(JsonNode value, String path, Set<String> accepted) {
    if (value == null || !value.isTextual()) {
      return;
    }
    String text = value.asText();
    if (!accepted.contains(text)) {
      throw new LayoutPreferenceValidationException(path + " has unsupported value: " + text);
    }
  }

  private static void rejectNull(JsonNode value, String path) throws MismatchedInputException {
    if (value != null && value.isNull()) {
      throw MismatchedInputException.from(
          null, LayoutRequest.class, "explicit null is not allowed at " + path);
    }
  }

  static final class LayoutPreferenceValidationException extends RuntimeException {
    LayoutPreferenceValidationException(String message) {
      super(message);
    }
  }
}

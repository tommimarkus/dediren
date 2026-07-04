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

package dev.dediren.core.io;

import dev.dediren.contracts.json.JsonSupport;
import tools.jackson.databind.JsonNode;

public final class JsonInput {
  private JsonInput() {}

  public static <T> T parseCommandData(String text, Class<T> type) {
    JsonNode value = JsonSupport.objectMapper().readTree(text);
    JsonNode data = value.has("envelope_schema_version") ? value.get("data") : value;
    if (data == null) {
      throw new IllegalArgumentException("command envelope does not contain data");
    }
    return JsonSupport.objectMapper().treeToValue(data, type);
  }
}

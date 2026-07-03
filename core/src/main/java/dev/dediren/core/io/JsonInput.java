package dev.dediren.core.io;

import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

public final class JsonInput {
  private JsonInput() {}

  public static String readJsonInput(Path path) throws IOException {
    return Files.readString(path);
  }

  public static <T> T parseCommandData(String text, Class<T> type) {
    JsonNode value = JsonSupport.objectMapper().readTree(text);
    JsonNode data = value.has("envelope_schema_version") ? value.get("data") : value;
    if (data == null) {
      throw new IllegalArgumentException("command envelope does not contain data");
    }
    return JsonSupport.objectMapper().treeToValue(data, type);
  }
}

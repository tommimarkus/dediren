package dev.dediren.contracts.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

public final class JsonSupport {
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .changeDefaultPropertyInclusion(
              incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
          // Jackson 3 flipped FAIL_ON_NULL_FOR_PRIMITIVES to true by default; pin the
          // Jackson 2 default (false) so a null JSON value for a primitive field maps to
          // the type default, preserving the contract fixtures' round-trip behavior.
          .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
          .build();

  private JsonSupport() {}

  public static ObjectMapper objectMapper() {
    return OBJECT_MAPPER;
  }

  public static JsonNode readTree(String json) {
    return OBJECT_MAPPER.readTree(json);
  }

  public static <T> T readValue(String json, Class<T> type) {
    return OBJECT_MAPPER.readValue(json, type);
  }

  public static <T> T readValue(String json, TypeReference<T> type) {
    return OBJECT_MAPPER.readValue(json, type);
  }

  public static String writeValueAsString(Object value) {
    return OBJECT_MAPPER.writeValueAsString(value);
  }
}

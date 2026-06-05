package dev.dediren.contracts.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class JsonSupport {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    private JsonSupport() {
    }

    public static ObjectMapper objectMapper() {
        return OBJECT_MAPPER;
    }

    public static JsonNode readTree(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readTree(json);
    }

    public static <T> T readValue(String json, Class<T> type) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, type);
    }

    public static <T> T readValue(String json, TypeReference<T> type) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, type);
    }
}

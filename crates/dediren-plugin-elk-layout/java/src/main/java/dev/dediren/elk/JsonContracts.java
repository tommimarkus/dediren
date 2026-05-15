package dev.dediren.elk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.NullValueProvider;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.exc.InvalidNullException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.AccessPattern;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class JsonContracts {
    private JsonContracts() {
    }

    static ObjectMapper objectMapper() {
        SimpleModule module = new SimpleModule()
            .setDeserializerModifier(new ExplicitNullDeserializerModifier());
        ObjectMapper mapper = new LayoutRequestObjectMapper();
        mapper.registerModule(module)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return mapper;
    }

    record LayoutRequest(
        String layout_request_schema_version,
        String view_id,
        List<LayoutNode> nodes,
        List<LayoutEdge> edges,
        List<LayoutGroup> groups,
        List<LayoutLabel> labels,
        List<LayoutConstraint> constraints,
        @JsonSetter(nulls = Nulls.FAIL)
        LayoutPreferences layout_preferences) {
    }

    record LayoutNode(
        String id,
        String label,
        String source_id,
        Double width_hint,
        Double height_hint) {
    }

    record LayoutEdge(
        String id,
        String source,
        String target,
        String label,
        String source_id,
        String relationship_type) {
        LayoutEdge(
            String id,
            String source,
            String target,
            String label,
            String source_id) {
            this(id, source, target, label, source_id, null);
        }
    }

    record LayoutGroup(
        String id,
        String label,
        List<String> members,
        GroupProvenance provenance) {
    }

    record GroupProvenance(Boolean visual_only, SemanticBacked semantic_backed) {
    }

    record SemanticBacked(String source_id) {
    }

    record LayoutLabel(String owner_id, String text) {
    }

    record LayoutConstraint(String id, String kind, List<String> subjects) {
    }

    record LayoutPreferences(
        @JsonSetter(nulls = Nulls.FAIL)
        String direction,
        @JsonSetter(nulls = Nulls.FAIL)
        String density,
        @JsonSetter(nulls = Nulls.FAIL)
        String wrapping,
        @JsonSetter(nulls = Nulls.FAIL)
        LayoutRoutingPreferences routing) {
    }

    record LayoutRoutingPreferences(
        @JsonSetter(nulls = Nulls.FAIL)
        String style,
        @JsonSetter(nulls = Nulls.FAIL)
        String profile,
        @JsonSetter(nulls = Nulls.FAIL)
        String endpoint_merging) {
    }

    record LayoutResult(
        String layout_result_schema_version,
        String view_id,
        List<LaidOutNode> nodes,
        List<LaidOutEdge> edges,
        List<LaidOutGroup> groups,
        List<Diagnostic> warnings) {
    }

    record LaidOutNode(
        String id,
        String source_id,
        String projection_id,
        double x,
        double y,
        double width,
        double height,
        String label) {
    }

    record LaidOutEdge(
        String id,
        String source,
        String target,
        String source_id,
        String projection_id,
        List<String> routing_hints,
        List<Point> points,
        String label) {
    }

    record LaidOutGroup(
        String id,
        String source_id,
        String projection_id,
        GroupProvenance provenance,
        double x,
        double y,
        double width,
        double height,
        List<String> members,
        String label) {
    }

    record Point(double x, double y) {
    }

    record Diagnostic(String code, String severity, String message, String path) {
    }

    record CommandEnvelope<T>(
        String envelope_schema_version,
        String status,
        T data,
        List<Diagnostic> diagnostics) {
    }

    private static final class LayoutRequestObjectMapper extends ObjectMapper {
        @Override
        public <T> T readValue(String content, Class<T> valueType)
            throws JsonProcessingException {
            if (valueType == LayoutRequest.class) {
                JsonNode root = readTree(content);
                rejectExplicitPreferenceNulls(root);
                return treeToValue(root, valueType);
            }
            return super.readValue(content, valueType);
        }

        @Override
        public <T> T readValue(InputStream source, Class<T> valueType)
            throws IOException {
            if (valueType == LayoutRequest.class) {
                JsonNode root = readTree(source);
                rejectExplicitPreferenceNulls(root);
                return treeToValue(root, valueType);
            }
            return super.readValue(source, valueType);
        }
    }

    private static void rejectExplicitPreferenceNulls(JsonNode root)
        throws MismatchedInputException {
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
        rejectNull(
            routing.get("endpoint_merging"),
            "$.layout_preferences.routing.endpoint_merging");
    }

    private static void rejectNull(JsonNode value, String path)
        throws MismatchedInputException {
        if (value != null && value.isNull()) {
            throw MismatchedInputException.from(
                null,
                LayoutRequest.class,
                "explicit null is not allowed at " + path);
        }
    }

    private static final class ExplicitNullDeserializerModifier
        extends BeanDeserializerModifier {
        @Override
        public BeanDeserializerBuilder updateBuilder(
            DeserializationConfig config,
            BeanDescription beanDescription,
            BeanDeserializerBuilder builder) {
            if (!isPreferenceContract(beanDescription.getBeanClass())) {
                return builder;
            }

            List<SettableBeanProperty> replacements = new ArrayList<>();
            Iterator<SettableBeanProperty> properties = builder.getProperties();
            while (properties.hasNext()) {
                SettableBeanProperty property = properties.next();
                JsonSetter setter = property.getAnnotation(JsonSetter.class);
                if (setter != null && setter.nulls() == Nulls.FAIL) {
                    replacements.add(property.withNullProvider(
                        new ExplicitNullOnlyFailProvider(
                            property.getFullName(),
                            property.getType())));
                }
            }
            for (SettableBeanProperty replacement : replacements) {
                if (replacement.getCreatorIndex() >= 0) {
                    builder.removeProperty(replacement.getFullName());
                    builder.addCreatorProperty(replacement);
                } else {
                    builder.addOrReplaceProperty(replacement, true);
                }
            }
            return builder;
        }

        private static boolean isPreferenceContract(Class<?> type) {
            return type == LayoutRequest.class
                || type == LayoutPreferences.class
                || type == LayoutRoutingPreferences.class;
        }
    }

    private record ExplicitNullOnlyFailProvider(PropertyName propertyName, JavaType type)
        implements NullValueProvider {
        @Override
        public Object getNullValue(DeserializationContext context)
            throws InvalidNullException {
            throw InvalidNullException.from(context, propertyName, type);
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }

        @Override
        public AccessPattern getNullAccessPattern() {
            return AccessPattern.DYNAMIC;
        }
    }
}

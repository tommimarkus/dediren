package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonInclude;

public record RenderArtifact(
        String artifactKind,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) String encoding) {
    public RenderArtifact(String artifactKind, String content) {
        this(artifactKind, content, null);
    }
}

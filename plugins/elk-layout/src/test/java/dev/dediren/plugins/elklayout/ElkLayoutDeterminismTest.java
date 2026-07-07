package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Layout determinism guard: the same request must lay out to a byte-identical {@code LayoutResult}
 * every time. ELK Layered with a fixed input order is deterministic in practice, but nothing pinned
 * it — a stray {@code HashMap} iteration or unseeded choice in the engine would surface only as
 * flapping golden snapshots and unreviewable diffs downstream. This is the precondition every
 * geometry-digest golden depends on. Render-side determinism is guarded in the render plugin.
 */
class ElkLayoutDeterminismTest {

  @Test
  void sameRequestLaysOutToByteIdenticalResultAcrossRuns() throws Exception {
    LayoutRequest request =
        JsonSupport.objectMapper()
            .readValue(
                Files.readString(workspaceRoot().resolve("fixtures/layout-request/basic.json")),
                LayoutRequest.class);

    String first =
        JsonSupport.objectMapper().writeValueAsString(new ElkLayoutEngine().layout(request));
    String second =
        JsonSupport.objectMapper().writeValueAsString(new ElkLayoutEngine().layout(request));
    String third =
        JsonSupport.objectMapper().writeValueAsString(new ElkLayoutEngine().layout(request));

    assertEquals(first, second);
    assertEquals(first, third);
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

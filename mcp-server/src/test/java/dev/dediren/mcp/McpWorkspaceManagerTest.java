package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.mcp.McpWorkspaceManager.WorkspaceException;
import dev.dediren.mcp.McpWorkspaceManager.WorkspaceLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpWorkspaceManagerTest {
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
  private static final Instant START = Instant.parse("2026-08-11T09:00:00Z");

  @Test
  void createsResumesAndRefreshesAWorkspace(@TempDir Path root) throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager manager = McpWorkspaceManager.writable(root, clock, false);

    String id;
    Path workspace;
    try (WorkspaceLease lease = manager.open(null)) {
      id = lease.workspaceId();
      workspace = lease.workspacePath();
      assertThat(id).matches(UUID_V4);
      assertThat(workspace).isDirectory();
      assertThat(lease.relativeWorkspacePath()).isEqualTo(".dediren/mcp/workspaces/" + id);
      assertThat(lease.expiresAt()).isEqualTo(START.plus(Duration.ofHours(24)));
    }

    clock.advance(Duration.ofHours(3));
    try (WorkspaceLease resumed = manager.open(id)) {
      assertThat(resumed.workspacePath()).isEqualTo(workspace);
      assertThat(resumed.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
    }
    assertThat(Files.readString(root.resolve(".dediren/mcp/state/" + id + "/last_used")))
        .isEqualTo(clock.instant().toString());
  }

  @Test
  void rejectsMalformedUnknownAndExpiredHandles(@TempDir Path root) throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager manager = McpWorkspaceManager.writable(root, clock, false);

    assertThatThrownBy(() -> manager.acquire("not-a-uuid"))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).kind())
        .isEqualTo(WorkspaceException.Kind.INVALID);
    assertThatThrownBy(() -> manager.acquire("8f1de3c2-3552-4f31-91b3-5524ca3bb8c8"))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).kind())
        .isEqualTo(WorkspaceException.Kind.UNAVAILABLE);

    String id;
    try (WorkspaceLease created = manager.open(null)) {
      id = created.workspaceId();
    }
    clock.advance(Duration.ofHours(24));

    assertThatThrownBy(() -> manager.acquire(id))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).kind())
        .isEqualTo(WorkspaceException.Kind.UNAVAILABLE);
  }

  @Test
  void sameWorkspaceIsBusyWhileADifferentWorkspaceRemainsAvailable(@TempDir Path root)
      throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager first = McpWorkspaceManager.writable(root, clock, false);
    McpWorkspaceManager second = McpWorkspaceManager.writable(root, clock, false);
    String firstId;
    String secondId;
    try (WorkspaceLease created = first.open(null)) {
      firstId = created.workspaceId();
    }
    try (WorkspaceLease created = first.open(null)) {
      secondId = created.workspaceId();
    }

    try (WorkspaceLease held = first.acquire(firstId)) {
      assertThatThrownBy(() -> second.acquire(firstId))
          .isInstanceOf(WorkspaceException.class)
          .extracting(error -> ((WorkspaceException) error).kind())
          .isEqualTo(WorkspaceException.Kind.BUSY);
      try (WorkspaceLease independent = second.acquire(secondId)) {
        assertThat(independent.workspaceId()).isEqualTo(secondId);
      }
    }
  }

  @Test
  void sweepRetainsFreshDataAndRemovesExpiredUnlockedData(@TempDir Path root) throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager manager = McpWorkspaceManager.writable(root, clock, false);
    String id;
    Path artifact;
    try (WorkspaceLease created = manager.open(null)) {
      id = created.workspaceId();
      artifact = created.workspacePath().resolve("artifact.txt");
      Files.writeString(artifact, "keep me");
    }

    clock.advance(Duration.ofHours(23));
    manager.sweep();
    assertThat(artifact).exists();

    clock.advance(Duration.ofHours(1));
    manager.sweep();
    assertThat(root.resolve(".dediren/mcp/workspaces/" + id)).doesNotExist();
    assertThat(root.resolve(".dediren/mcp/state/" + id)).doesNotExist();
  }

  @Test
  void sweepSkipsAnActiveExpiredWorkspaceAndRetriesItLater(@TempDir Path root) throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager manager = McpWorkspaceManager.writable(root, clock, false);
    String id;
    try (WorkspaceLease created = manager.open(null)) {
      id = created.workspaceId();
    }
    WorkspaceLease held = manager.acquire(id);
    clock.advance(Duration.ofHours(24));

    manager.sweep();
    assertThat(held.workspacePath()).exists();

    held.close();
    manager.sweep();
    assertThat(root.resolve(".dediren/mcp/workspaces/" + id)).doesNotExist();
    assertThat(root.resolve(".dediren/mcp/state/" + id)).doesNotExist();
  }

  @Test
  void sweepRemovesAnUnlockedIncompleteCrashRemnant(@TempDir Path root) throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager manager = McpWorkspaceManager.writable(root, clock, false);
    String id = "8f1de3c2-3552-4f31-91b3-5524ca3bb8c8";
    Path remnant = Files.createDirectories(root.resolve(".dediren/mcp/workspaces/" + id));
    Files.writeString(remnant.resolve("partial.tmp"), "partial");

    manager.sweep();

    assertThat(remnant).doesNotExist();
    assertThat(root.resolve(".dediren/mcp/state/" + id)).doesNotExist();
  }

  @Test
  void sweepNeverFollowsSymlinksWhileDeletingAWorkspace(@TempDir Path root, @TempDir Path outside)
      throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager manager = McpWorkspaceManager.writable(root, clock, false);
    String id;
    Path workspace;
    try (WorkspaceLease created = manager.open(null)) {
      id = created.workspaceId();
      workspace = created.workspacePath();
    }
    Path outsideFile = outside.resolve("must-survive.txt");
    Files.writeString(outsideFile, "safe");
    try {
      Files.createSymbolicLink(workspace.resolve("outside-link"), outside);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return;
    }
    clock.advance(Duration.ofHours(24));

    manager.sweep();

    assertThat(root.resolve(".dediren/mcp/workspaces/" + id)).doesNotExist();
    assertThat(outsideFile).hasContent("safe");
  }

  @Test
  void readOnlyManagerCreatesNothingAndDoesNotRefreshAnExistingLease(@TempDir Path root)
      throws Exception {
    MutableClock clock = new MutableClock(START);
    McpWorkspaceManager readOnly = McpWorkspaceManager.readOnly(root, clock);
    readOnly.sweep();
    assertThat(root.resolve(".dediren")).doesNotExist();

    McpWorkspaceManager writable = McpWorkspaceManager.writable(root, clock, false);
    String id;
    try (WorkspaceLease created = writable.open(null)) {
      id = created.workspaceId();
    }
    Path lastUsed = root.resolve(".dediren/mcp/state/" + id + "/last_used");
    String original = Files.readString(lastUsed);
    clock.advance(Duration.ofHours(2));

    try (WorkspaceLease ignored = readOnly.acquire(id)) {
      assertThat(Files.readString(lastUsed)).isEqualTo(original);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

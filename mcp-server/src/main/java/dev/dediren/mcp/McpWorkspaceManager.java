package dev.dediren.mcp;

import dev.dediren.core.io.ConfinedPaths;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Owns MCP build-workspace identity, locking, activity state, expiry, and reaping. */
final class McpWorkspaceManager {
  static final Duration TTL = Duration.ofHours(24);
  private static final Duration REAPER_INTERVAL = Duration.ofHours(1);
  private static final String MCP_RELATIVE = ".dediren/mcp";
  private static final String WORKSPACES_RELATIVE = MCP_RELATIVE + "/workspaces";
  private static final String STATE_RELATIVE = MCP_RELATIVE + "/state";
  private static final String LAST_USED = "last_used";
  private static final String LOCK = "lock";
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
  private static final ScheduledExecutorService REAPER =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "dediren-mcp-workspace-reaper");
            thread.setDaemon(true);
            return thread;
          });

  private final Path root;
  private final boolean writable;
  private final Clock clock;

  private McpWorkspaceManager(Path root, boolean writable, Clock clock) {
    try {
      this.root = root.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException("MCP root must be an existing directory", error);
    }
    this.writable = writable;
    this.clock = clock;
  }

  static McpWorkspaceManager writable(Path root) {
    return writable(root, Clock.systemUTC(), true);
  }

  static McpWorkspaceManager writable(Path root, Clock clock, boolean schedule) {
    McpWorkspaceManager manager = new McpWorkspaceManager(root, true, clock);
    if (schedule) {
      manager.sweep();
      REAPER.scheduleAtFixedRate(
          manager::sweep, REAPER_INTERVAL.toHours(), REAPER_INTERVAL.toHours(), TimeUnit.HOURS);
    }
    return manager;
  }

  static McpWorkspaceManager readOnly(Path root) {
    return readOnly(root, Clock.systemUTC());
  }

  static McpWorkspaceManager readOnly(Path root, Clock clock) {
    return new McpWorkspaceManager(root, false, clock);
  }

  WorkspaceLease open(String requestedId) throws WorkspaceException {
    if (!writable) {
      throw new WorkspaceException(
          WorkspaceException.Kind.IO, "workspace creation is unavailable in read-only mode", null);
    }
    if (requestedId != null) {
      return acquire(requestedId);
    }
    ensureRoots();
    for (int attempt = 0; attempt < 100; attempt++) {
      String id = UUID.randomUUID().toString();
      Path state = stateRoot().resolve(id);
      Path workspace = workspaceRoot().resolve(id);
      boolean stateCreated = false;
      boolean workspaceCreated = false;
      FileChannel channel = null;
      FileLock lock = null;
      try {
        Files.createDirectory(state);
        stateCreated = true;
        Path lockPath = Files.createFile(state.resolve(LOCK));
        channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
        lock = tryExclusiveLock(channel, id);
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
          close(lock, channel);
          deleteTreeNoFollow(state);
          continue;
        }
        Files.createDirectory(workspace);
        workspaceCreated = true;
        Instant usedAt = clock.instant();
        writeLastUsed(state, usedAt);
        return new WorkspaceLease(
            id,
            workspace.toRealPath(),
            WORKSPACES_RELATIVE + "/" + id,
            usedAt.plus(TTL),
            channel,
            lock);
      } catch (WorkspaceException error) {
        close(lock, channel);
        cleanupCreateFailure(state, stateCreated, workspace, workspaceCreated);
        throw error;
      } catch (IOException error) {
        close(lock, channel);
        cleanupCreateFailure(state, stateCreated, workspace, workspaceCreated);
        if (!stateCreated && error instanceof FileAlreadyExistsException) {
          continue;
        }
        throw io("failed to create MCP workspace", error);
      }
    }
    throw new WorkspaceException(
        WorkspaceException.Kind.IO, "failed to allocate a unique MCP workspace", null);
  }

  WorkspaceLease acquire(String id) throws WorkspaceException {
    requireCanonicalId(id);
    Path state = existingManagedDirectory(STATE_RELATIVE + "/" + id);
    Path workspace = existingManagedDirectory(WORKSPACES_RELATIVE + "/" + id);
    Path lockPath = state.resolve(LOCK);
    Path lastUsedPath = state.resolve(LAST_USED);
    if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(lastUsedPath, LinkOption.NOFOLLOW_LINKS)) {
      throw unavailable(id);
    }

    FileChannel channel;
    FileLock lock;
    try {
      channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
      lock = tryExclusiveLock(channel, id);
    } catch (WorkspaceException error) {
      throw error;
    } catch (IOException error) {
      throw io("failed to acquire MCP workspace", error);
    }

    try {
      Instant lastUsed = readLastUsed(lastUsedPath, id);
      Instant now = clock.instant();
      if (!lastUsed.plus(TTL).isAfter(now)) {
        close(lock, channel);
        throw unavailable(id);
      }
      Instant effective = lastUsed;
      if (writable) {
        effective = now;
        writeLastUsed(state, effective);
      }
      return new WorkspaceLease(
          id, workspace, WORKSPACES_RELATIVE + "/" + id, effective.plus(TTL), channel, lock);
    } catch (WorkspaceException error) {
      close(lock, channel);
      throw error;
    }
  }

  /** Runs one best-effort expiry pass. Read-only managers deliberately do nothing. */
  void sweep() {
    if (!writable) {
      return;
    }
    try {
      ensureRoots();
      Path reaperLock = stateRoot().resolve("reaper.lock");
      try (FileChannel channel =
          FileChannel.open(reaperLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
        FileLock lock;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException busy) {
          return;
        }
        if (lock == null) {
          return;
        }
        try (lock) {
          for (String id : workspaceIds()) {
            reapOne(id);
          }
        }
      }
    } catch (IOException | WorkspaceException error) {
      System.err.println("dediren mcp: workspace reaper failed: " + error.getMessage());
    }
  }

  private void reapOne(String id) {
    Path state;
    Path workspace;
    try {
      state = confinedForWrite(STATE_RELATIVE + "/" + id);
      workspace = confinedForWrite(WORKSPACES_RELATIVE + "/" + id);
      if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(state);
      }
      if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      Path lockPath = state.resolve(LOCK);
      if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
        Files.createFile(lockPath);
      }
      if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
        FileLock lock;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException busy) {
          return;
        }
        if (lock == null) {
          return;
        }
        try (lock) {
          if (!shouldReap(state, workspace)) {
            return;
          }
          deleteManagedTree(workspace);
          deleteManagedTree(state);
        }
      }
    } catch (IOException | WorkspaceException error) {
      System.err.println(
          "dediren mcp: workspace reaper skipped '" + id + "': " + error.getMessage());
    }
  }

  private boolean shouldReap(Path state, Path workspace) {
    if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
      return true;
    }
    Path lastUsed = state.resolve(LAST_USED);
    if (!Files.isRegularFile(lastUsed, LinkOption.NOFOLLOW_LINKS)) {
      return true;
    }
    try {
      Instant timestamp = Instant.parse(Files.readString(lastUsed));
      return !timestamp.plus(TTL).isAfter(clock.instant());
    } catch (IOException | DateTimeParseException invalid) {
      return true;
    }
  }

  private Set<String> workspaceIds() throws IOException, WorkspaceException {
    Set<String> ids = new LinkedHashSet<>();
    collectWorkspaceIds(workspaceRoot(), ids);
    collectWorkspaceIds(stateRoot(), ids);
    return ids;
  }

  private static void collectWorkspaceIds(Path directory, Set<String> ids) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      for (Path entry : entries.toList()) {
        Path fileName = entry.getFileName();
        if (fileName != null && UUID_V4.matcher(fileName.toString()).matches()) {
          ids.add(fileName.toString());
        }
      }
    }
  }

  private void ensureRoots() throws WorkspaceException {
    try {
      Path mcp = confinedForWrite(MCP_RELATIVE);
      Files.createDirectories(mcp);
      Path workspaces = confinedForWrite(WORKSPACES_RELATIVE);
      Path state = confinedForWrite(STATE_RELATIVE);
      Files.createDirectories(workspaces);
      Files.createDirectories(state);
      if (!Files.isDirectory(workspaces, LinkOption.NOFOLLOW_LINKS)
          || !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("managed workspace roots are not directories");
      }
    } catch (IOException error) {
      throw io("failed to initialize MCP workspace storage", error);
    }
  }

  private Path workspaceRoot() throws WorkspaceException {
    return existingManagedDirectory(WORKSPACES_RELATIVE);
  }

  private Path stateRoot() throws WorkspaceException {
    return existingManagedDirectory(STATE_RELATIVE);
  }

  private Path existingManagedDirectory(String relative) throws WorkspaceException {
    Path lexical = root.resolve(relative);
    if (!Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS)) {
      throw unavailable(relative.substring(relative.lastIndexOf('/') + 1));
    }
    try {
      return ConfinedPaths.resolveExisting(root, lexical);
    } catch (ConfinedPaths.PathEscapeException error) {
      throw io("managed MCP workspace path failed confinement", error);
    }
  }

  private Path confinedForWrite(String relative) throws WorkspaceException {
    try {
      return ConfinedPaths.resolveAnchored(root, root.resolve(relative));
    } catch (ConfinedPaths.PathEscapeException error) {
      throw io("managed MCP workspace path failed confinement", error);
    }
  }

  private FileLock tryExclusiveLock(FileChannel channel, String id)
      throws IOException, WorkspaceException {
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        throw busy(id);
      }
      return lock;
    } catch (OverlappingFileLockException overlap) {
      throw busy(id);
    }
  }

  private Instant readLastUsed(Path path, String id) throws WorkspaceException {
    try {
      return Instant.parse(Files.readString(path));
    } catch (DateTimeParseException invalid) {
      throw unavailable(id);
    } catch (IOException error) {
      throw io("failed to read MCP workspace lifecycle state", error);
    }
  }

  private void writeLastUsed(Path state, Instant value) throws WorkspaceException {
    Path target = state.resolve(LAST_USED);
    Path temporary = state.resolve(LAST_USED + ".tmp-" + UUID.randomUUID());
    try {
      Files.writeString(temporary, value.toString(), StandardOpenOption.CREATE_NEW);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanup) {
        error.addSuppressed(cleanup);
      }
      throw io("failed to update MCP workspace lifecycle state", error);
    }
  }

  private void deleteManagedTree(Path path) throws IOException, WorkspaceException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Path confined;
    try {
      confined = ConfinedPaths.resolveExisting(root, path);
    } catch (ConfinedPaths.PathEscapeException error) {
      throw io("managed MCP workspace path failed confinement", error);
    }
    deleteTreeNoFollow(confined);
  }

  private static void deleteTreeNoFollow(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void cleanupCreateFailure(
      Path state, boolean stateCreated, Path workspace, boolean workspaceCreated) {
    try {
      if (workspaceCreated) {
        deleteTreeNoFollow(workspace);
      }
      if (stateCreated) {
        deleteTreeNoFollow(state);
      }
    } catch (IOException cleanup) {
      System.err.println("dediren mcp: failed to clean partial workspace: " + cleanup.getMessage());
    }
  }

  private static void close(FileLock lock, FileChannel channel) {
    try {
      if (lock != null) {
        lock.close();
      }
    } catch (IOException ignored) {
      // The operation is already failing; the channel close below is the final release attempt.
    }
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (IOException ignored) {
      // Nothing useful can be reported beyond the operation's primary failure.
    }
  }

  private static void requireCanonicalId(String id) throws WorkspaceException {
    if (id == null || !UUID_V4.matcher(id).matches()) {
      throw new WorkspaceException(
          WorkspaceException.Kind.INVALID, "'workspace_id' must be a canonical UUIDv4", id);
    }
  }

  private static WorkspaceException unavailable(String id) {
    return new WorkspaceException(
        WorkspaceException.Kind.UNAVAILABLE,
        "MCP workspace is unknown, incomplete, or expired",
        id);
  }

  private static WorkspaceException busy(String id) {
    return new WorkspaceException(
        WorkspaceException.Kind.BUSY, "MCP workspace is already in use", id);
  }

  private static WorkspaceException io(String message, Exception cause) {
    return new WorkspaceException(WorkspaceException.Kind.IO, message, null, cause);
  }

  static final class WorkspaceLease implements AutoCloseable {
    private final String workspaceId;
    private final Path workspacePath;
    private final String relativeWorkspacePath;
    private final Instant expiresAt;
    private final FileChannel channel;
    private final FileLock lock;

    private WorkspaceLease(
        String workspaceId,
        Path workspacePath,
        String relativeWorkspacePath,
        Instant expiresAt,
        FileChannel channel,
        FileLock lock) {
      this.workspaceId = workspaceId;
      this.workspacePath = workspacePath;
      this.relativeWorkspacePath = relativeWorkspacePath;
      this.expiresAt = expiresAt;
      this.channel = channel;
      this.lock = lock;
    }

    String workspaceId() {
      return workspaceId;
    }

    Path workspacePath() {
      return workspacePath;
    }

    String relativeWorkspacePath() {
      return relativeWorkspacePath;
    }

    Instant expiresAt() {
      return expiresAt;
    }

    @Override
    public void close() {
      McpWorkspaceManager.close(lock, channel);
    }
  }

  static final class WorkspaceException extends Exception {
    enum Kind {
      INVALID,
      UNAVAILABLE,
      BUSY,
      IO
    }

    private final Kind kind;
    private final String candidate;

    private WorkspaceException(Kind kind, String message, String candidate) {
      super(message);
      this.kind = kind;
      this.candidate = candidate;
    }

    private WorkspaceException(Kind kind, String message, String candidate, Throwable cause) {
      super(message, cause);
      this.kind = kind;
      this.candidate = candidate;
    }

    Kind kind() {
      return kind;
    }

    String candidate() {
      return candidate;
    }
  }
}

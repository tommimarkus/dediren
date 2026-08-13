package dev.dediren.mcp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional MCP-owned adapter from generated SVG bytes to a validated PNG attachment. */
final class ResvgRasterizer {
  private static final byte[] PNG_SIGNATURE = {
    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
  };
  private static final long MAX_ATTACHED_IMAGE_BYTES = 64L * 1024 * 1024;
  private static final int MAX_DIMENSION = 4096;
  private static final int MAX_STDERR_BYTES = 64 * 1024;
  private static final Pattern DIMENSION =
      Pattern.compile("\\b(width|height)\\s*=\\s*['\"]([0-9]+(?:\\.[0-9]+)?)(?:px)?['\"]");

  private final Path executable;
  private final Duration timeout;
  private final long outputCeiling;

  ResvgRasterizer(Path executable, Duration timeout, long outputCeiling) {
    this.executable = executable;
    this.timeout = timeout;
    this.outputCeiling = outputCeiling;
  }

  static ResvgRasterizer resolve(String command, Map<String, String> env) {
    Path resolved = null;
    try {
      if (command != null && !command.isBlank()) {
        Path candidate = Path.of(command);
        if (candidate.isAbsolute()) {
          resolved = executable(candidate);
        } else if (!containsSeparator(command)) {
          String path = env.get("PATH");
          if (path != null) {
            for (String directory : path.split(Pattern.quote(File.pathSeparator), -1)) {
              if (directory.isEmpty()) {
                continue;
              }
              Path found = executable(Path.of(directory).resolve(command).toAbsolutePath());
              if (found != null) {
                resolved = found;
                break;
              }
            }
          }
        }
      }
    } catch (RuntimeException invalidPath) {
      resolved = null;
    }
    return new ResvgRasterizer(resolved, Duration.ofSeconds(15), MAX_ATTACHED_IMAGE_BYTES);
  }

  Optional<byte[]> rasterize(byte[] svg, long remainingAttachmentBytes) {
    if (executable == null || remainingAttachmentBytes <= 0) {
      return Optional.empty();
    }
    long ceiling = Math.min(outputCeiling, remainingAttachmentBytes);
    if (ceiling < 24) {
      return Optional.empty();
    }

    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    scaleArgument(svg).ifPresent(command::addAll);
    command.addAll(List.of("--quiet", "-", "-c"));
    Process process = null;
    Thread stdin = null;
    Thread stdout = null;
    Thread stderr = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().clear();
      builder.environment().put("PATH", "");
      builder.environment().put("HOME", "");
      process = builder.start();
      Process child = process;
      BoundedCapture png = new BoundedCapture(ceiling);
      BoundedCapture errors = new BoundedCapture(MAX_STDERR_BYTES);
      stdout = Thread.startVirtualThread(() -> png.read(child.getInputStream()));
      stderr = Thread.startVirtualThread(() -> errors.read(child.getErrorStream()));
      stdin =
          Thread.startVirtualThread(
              () -> {
                try (OutputStream input = child.getOutputStream()) {
                  input.write(svg);
                } catch (IOException ignored) {
                  // A converter may close stdin as part of any ordinary failure path.
                }
              });
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        terminate(process);
        closeStreams(process);
        joinWithin(Duration.ofSeconds(1), stdin, stdout, stderr);
        return Optional.empty();
      }
      if (!joinWithin(Duration.ofSeconds(1), stdin, stdout, stderr)) {
        closeStreams(process);
        interrupt(stdin, stdout, stderr);
        return Optional.empty();
      }
      if (process.exitValue() != 0 || png.overflowed()) {
        return Optional.empty();
      }
      byte[] bytes = png.bytes();
      return validPng(bytes) ? Optional.of(bytes) : Optional.empty();
    } catch (InterruptedException interrupted) {
      if (process != null) {
        terminate(process);
        closeStreams(process);
      }
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (IOException | RuntimeException failure) {
      if (process != null) {
        terminate(process);
        closeStreams(process);
      }
      return Optional.empty();
    }
  }

  private static Path executable(Path path) {
    try {
      Path normalized = path.toAbsolutePath().normalize();
      return Files.isRegularFile(normalized) && Files.isExecutable(normalized) ? normalized : null;
    } catch (SecurityException failure) {
      return null;
    }
  }

  private static boolean containsSeparator(String command) {
    return command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
  }

  private static Optional<List<String>> scaleArgument(byte[] svg) {
    String prefix =
        new String(svg, 0, Math.min(svg.length, 4096), java.nio.charset.StandardCharsets.UTF_8);
    double width = -1;
    double height = -1;
    Matcher matcher = DIMENSION.matcher(prefix);
    while (matcher.find()) {
      double value = Double.parseDouble(matcher.group(2));
      if ("width".equals(matcher.group(1))) {
        width = value;
      } else {
        height = value;
      }
    }
    if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
      return Optional.empty();
    }
    return width >= height
        ? Optional.of(List.of("--width", Integer.toString(MAX_DIMENSION)))
        : Optional.of(List.of("--height", Integer.toString(MAX_DIMENSION)));
  }

  private static boolean validPng(byte[] png) {
    if (png.length < 24 || !Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(png, 8))) {
      return false;
    }
    if (readInt(png, 8) != 13
        || png[12] != 'I'
        || png[13] != 'H'
        || png[14] != 'D'
        || png[15] != 'R') {
      return false;
    }
    long width = Integer.toUnsignedLong(readInt(png, 16));
    long height = Integer.toUnsignedLong(readInt(png, 20));
    return width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION;
  }

  private static int readInt(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) << 24
        | (bytes[offset + 1] & 0xff) << 16
        | (bytes[offset + 2] & 0xff) << 8
        | bytes[offset + 3] & 0xff;
  }

  private static void terminate(Process process) {
    process.destroy();
    try {
      if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(250, TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private static boolean joinWithin(Duration timeout, Thread... threads)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    for (Thread thread : threads) {
      if (thread != null) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        thread.join(Duration.ofNanos(remaining));
        if (thread.isAlive()) {
          return false;
        }
      }
    }
    return true;
  }

  private static void closeStreams(Process process) {
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
      // Best effort while bounding converter cleanup.
    }
    try {
      process.getInputStream().close();
    } catch (IOException ignored) {
      // Best effort while bounding converter cleanup.
    }
    try {
      process.getErrorStream().close();
    } catch (IOException ignored) {
      // Best effort while bounding converter cleanup.
    }
  }

  private static void interrupt(Thread... threads) {
    for (Thread thread : threads) {
      if (thread != null) {
        thread.interrupt();
      }
    }
  }

  private static final class BoundedCapture {
    private final long ceiling;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile boolean overflowed;

    BoundedCapture(long ceiling) {
      this.ceiling = ceiling;
    }

    void read(InputStream input) {
      byte[] buffer = new byte[8192];
      try (input) {
        for (int count; (count = input.read(buffer)) != -1; ) {
          long remaining = ceiling - bytes.size();
          if (remaining > 0) {
            bytes.write(buffer, 0, (int) Math.min(remaining, count));
          }
          if (count > remaining) {
            overflowed = true;
          }
        }
      } catch (IOException failure) {
        overflowed = true;
      }
    }

    boolean overflowed() {
      return overflowed;
    }

    byte[] bytes() {
      return bytes.toByteArray();
    }
  }
}

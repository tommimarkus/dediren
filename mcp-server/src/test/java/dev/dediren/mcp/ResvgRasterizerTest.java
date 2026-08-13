package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResvgRasterizerTest {
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADElEQVR42mNk+M/wHwAF/gL+gq6uVQAAAABJRU5ErkJggg==");

  @Test
  void resolutionUsesOnlyAnAbsolutePathOrOneSuppliedPathLookup(@TempDir Path temp)
      throws Exception {
    Path executable = executable(temp, "resvg", pngScript());

    assertThat(
            ResvgRasterizer.resolve("missing-resvg", Map.of("PATH", temp.toString()))
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), 1024))
        .isEmpty();
    assertThat(
            ResvgRasterizer.resolve("resvg", Map.of("PATH", temp.toString()))
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), 1024))
        .contains(PNG);
    assertThat(
            ResvgRasterizer.resolve(executable.toString(), Map.of())
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), 1024))
        .contains(PNG);
    assertThat(
            ResvgRasterizer.resolve("tools/resvg", Map.of("PATH", temp.toString()))
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), 1024))
        .isEmpty();
    assertThat(
            ResvgRasterizer.resolve("\0", Map.of("PATH", temp.toString()))
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), 1024))
        .isEmpty();
  }

  @Test
  void invokesFixedNoShellArgumentsWithSvgOnStdinAndPngOnStdout(@TempDir Path temp)
      throws Exception {
    Path executable =
        executable(
            temp,
            "resvg",
            """
            #!/bin/sh
            [ "$#" = 3 ] && [ "$1" = --quiet ] && [ "$2" = - ] && [ "$3" = -c ] || exit 9
            IFS= read -r input || true
            [ "$input" = '<svg width="10" height="20"/>' ] || exit 8
            [ -z "$PATH" ] && [ -z "$HOME" ] || exit 7
            """
                + pngScriptBody());

    Optional<byte[]> raster =
        new ResvgRasterizer(executable, Duration.ofSeconds(1), PNG.length + 1)
            .rasterize(
                "<svg width=\"10\" height=\"20\"/>".getBytes(StandardCharsets.UTF_8), PNG.length);

    assertThat(raster).contains(PNG);
  }

  @Test
  void constrainsTheLongerSvgDimensionTo4096Pixels(@TempDir Path temp) throws Exception {
    Path executable =
        executable(
            temp,
            "resvg-scale",
            """
            #!/bin/sh
            [ "$#" = 5 ] && [ "$1" = --width ] && [ "$2" = 4096 ] || exit 9
            [ "$3" = --quiet ] && [ "$4" = - ] && [ "$5" = -c ] || exit 8
            """
                + pngScriptBody());

    assertThat(
            new ResvgRasterizer(executable, Duration.ofSeconds(1), PNG.length + 1)
                .rasterize(
                    "<svg width=\"5000\" height=\"2500\"/>".getBytes(StandardCharsets.UTF_8),
                    PNG.length))
        .contains(PNG);
  }

  @Test
  void omitsInvalidOrOverBudgetConverterOutputWithoutThrowing(@TempDir Path temp) throws Exception {
    for (String script : new String[] {"#!/bin/sh\nexit 3\n", "#!/bin/sh\nprintf not-a-png\n"}) {
      Path executable = executable(temp, "resvg-" + script.hashCode(), script);
      assertThat(
              new ResvgRasterizer(executable, Duration.ofSeconds(1), PNG.length + 1)
                  .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), PNG.length + 1))
          .isEmpty();
    }

    Path oversized = executable(temp, "resvg-oversized", pngScript());
    assertThat(
            new ResvgRasterizer(oversized, Duration.ofSeconds(1), PNG.length - 1)
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), PNG.length - 1))
        .isEmpty();
    assertThat(
            new ResvgRasterizer(oversized, Duration.ofSeconds(1), PNG.length + 1)
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), PNG.length - 1))
        .as("the caller's remaining cumulative attachment budget is a separate ceiling")
        .isEmpty();

    Path overdimensioned =
        executable(
            temp,
            "resvg-overdimensioned",
            "#!/bin/sh\nprintf '\\211PNG\\r\\n\\032\\n\\000\\000\\000\\rIHDR\\000\\000\\020\\001\\000\\000\\000\\001'\n");
    assertThat(
            new ResvgRasterizer(overdimensioned, Duration.ofSeconds(1), 1024)
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), 1024))
        .isEmpty();
  }

  @Test
  void timeoutTerminatesTheConverterAndLeavesNoAttachment(@TempDir Path temp) throws Exception {
    Path pidFile = temp.resolve("timeout.pid");
    Path executable =
        executable(
            temp,
            "resvg-hang",
            "#!/bin/sh\nprintf '%s' \"$$\" > '" + pidFile + "'\nwhile :; do :; done\n");

    assertThat(
            new ResvgRasterizer(executable, Duration.ofMillis(25), PNG.length + 1)
                .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), PNG.length))
        .isEmpty();
    assertProcessDead(pidFile);
  }

  @Test
  void interruptionCancelsTheConverterAndRestoresTheCallerInterrupt(@TempDir Path temp)
      throws Exception {
    Path pidFile = temp.resolve("interrupted.pid");
    Path executable =
        executable(
            temp,
            "resvg-cancel",
            "#!/bin/sh\nprintf '%s' \"$$\" > '" + pidFile + "'\nwhile :; do :; done\n");
    AtomicReference<Optional<byte[]>> result = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  result.set(
                      new ResvgRasterizer(executable, Duration.ofSeconds(10), PNG.length + 1)
                          .rasterize("<svg/>".getBytes(StandardCharsets.UTF_8), PNG.length + 1));
                  interrupted.set(Thread.currentThread().isInterrupted());
                });

    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(pidFile).exists();
    caller.interrupt();
    caller.join(Duration.ofSeconds(2));

    assertThat(caller.isAlive()).isFalse();
    assertThat(result.get()).isEmpty();
    assertThat(interrupted).isTrue();
    assertProcessDead(pidFile);
  }

  private static void assertProcessDead(Path pidFile) throws Exception {
    assertThat(pidFile).exists();
    long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  private static Path executable(Path directory, String name, String script) throws Exception {
    Path target = directory.resolve(name);
    Files.writeString(target, script, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        target,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    return target;
  }

  private static String pngScript() {
    return "#!/bin/sh\n" + pngScriptBody();
  }

  private static String pngScriptBody() {
    return "printf '"
        + "\\211\\120\\116\\107\\015\\012\\032\\012\\000\\000\\000\\015\\111\\110\\104\\122"
        + "\\000\\000\\000\\001\\000\\000\\000\\001\\010\\006\\000\\000\\000\\037\\025\\304\\211"
        + "\\000\\000\\000\\014\\111\\104\\101\\124\\170\\332\\143\\144\\370\\317\\360\\037\\000"
        + "\\005\\376\\002\\376\\202\\256\\256\\125\\000\\000\\000\\000\\111\\105\\116\\104\\256"
        + "\\102\\140\\202'\n";
  }
}

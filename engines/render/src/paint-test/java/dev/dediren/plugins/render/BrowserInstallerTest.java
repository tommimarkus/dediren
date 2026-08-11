package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "dediren.render.paint.enabled", matches = "true")
class BrowserInstallerTest {

  @Test
  @Timeout(600)
  void installsOnlyThePinnedChromiumHeadlessShellInTheRepositoryCache() throws Exception {
    Files.createDirectories(BrowserTestSupport.BROWSER_CACHE_PATH);
    Path revision =
        BrowserTestSupport.BROWSER_CACHE_PATH.resolve(
            "chromium_headless_shell-" + BrowserTestSupport.PINNED_CHROMIUM_REVISION);
    if (!isCompleteHeadlessShell(revision)) {
      installHeadlessShell();
    }
    removeUnusedFfmpeg();

    assertThat(revision).isDirectory();
    assertThat(isCompleteHeadlessShell(revision)).isTrue();
    try (var entries = Files.list(BrowserTestSupport.BROWSER_CACHE_PATH)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .filteredOn(name -> !name.equals(".links"))
          .containsExactly(
              "chromium_headless_shell-" + BrowserTestSupport.PINNED_CHROMIUM_REVISION);
    }
  }

  private static void installHeadlessShell() throws Exception {
    Path java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().startsWith("windows")
                ? "java.exe"
                : "java");
    ProcessBuilder builder =
        new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.microsoft.playwright.CLI",
                "install",
                "--only-shell",
                "chromium")
            .inheritIO();
    builder
        .environment()
        .put("PLAYWRIGHT_BROWSERS_PATH", BrowserTestSupport.BROWSER_CACHE_PATH.toString());
    builder.environment().put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "120000");

    Process process = builder.start();
    boolean finished = process.waitFor(Duration.ofMinutes(9).toMillis(), TimeUnit.MILLISECONDS);
    assertThat(finished).as("Playwright browser installer finished").isTrue();
    assertThat(process.exitValue()).as("Playwright browser installer exit code").isZero();
  }

  private static void removeUnusedFfmpeg() throws Exception {
    // Playwright's Chromium installer also fetches FFmpeg for video capture. Paint tests take only
    // PNG screenshots, so retain no unused native media binary in the repository cache.
    try (var entries = Files.list(BrowserTestSupport.BROWSER_CACHE_PATH)) {
      for (Path ffmpeg :
          entries.filter(path -> path.getFileName().toString().startsWith("ffmpeg-")).toList()) {
        deleteTree(ffmpeg);
      }
    }
  }

  private static boolean isCompleteHeadlessShell(Path revision) throws Exception {
    if (!Files.isRegularFile(revision.resolve("INSTALLATION_COMPLETE"))) {
      return false;
    }
    try (var files = Files.walk(revision, 4)) {
      return files.anyMatch(
          path -> {
            String name = path.getFileName().toString();
            return Files.isRegularFile(path)
                && Files.isExecutable(path)
                && (name.equals("chrome-headless-shell")
                    || name.equals("headless_shell")
                    || name.equals("headless_shell.exe"));
          });
    }
  }

  private static void deleteTree(Path root) throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}

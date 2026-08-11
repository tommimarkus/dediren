package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "dediren.render.paint.enabled", matches = "true")
class BrowserPaintSmokeTest {

  @Test
  void declaresTheMinimalDisposableOfflineChromiumPolicy() {
    assertThat(BrowserTestSupport.CHROMIUM_LAUNCH_ARGS)
        .containsExactlyElementsOf(
            List.of(
                "--disable-background-networking",
                "--disable-breakpad",
                "--disable-client-side-phishing-detection",
                "--disable-component-update",
                "--disable-crash-reporter",
                "--disable-default-apps",
                "--disable-domain-reliability",
                "--disable-extensions",
                "--disable-features=MediaRouter,OptimizationHints,Translate",
                "--disable-sync",
                "--disable-translate",
                "--no-default-browser-check",
                "--no-first-run"))
        .noneMatch(argument -> argument.contains("metrics"));
    assertThat(BrowserTestSupport.LIFECYCLE_POLICY).contains("non-persistent").contains("close");
    assertThat(BrowserTestSupport.NETWORK_POLICY)
        .contains("offline")
        .contains("catch-all route abort");
    assertThat(BrowserTestSupport.TELEMETRY_POLICY)
        .contains("background networking")
        .contains("crash reporting")
        .contains("optimization hints");
  }

  @Test
  void rejectsActiveOrExternallyLoadedSvgBeforeLaunchingTheBrowser() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BrowserTestSupport.validateStaticSvg(
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>"))
        .withMessageContaining("prohibited element: script");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BrowserTestSupport.validateStaticSvg(
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"><animate/></svg>"))
        .withMessageContaining("prohibited element: animate");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BrowserTestSupport.validateStaticSvg(
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect onclick=\"alert(1)\"/></svg>"))
        .withMessageContaining("event handler");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                BrowserTestSupport.validateStaticSvg(
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"https://example.test/a.png\"/></svg>"))
        .withMessageContaining("external URL");
  }

  @Test
  @Timeout(20)
  void rendersDecoratedStaticPaintWithPinnedBrowserAndBundledFont(@TempDir Path temporaryDirectory)
      throws Exception {
    assertThat(BrowserTestSupport.playwrightVersion())
        .isEqualTo(BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION);
    assertThat(BrowserTestSupport.canDisplay("Paint tree")).isTrue();
    assertThat(BrowserTestSupport.canDisplay("漢🙂")).isFalse();

    String svg =
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="240" height="120"
             viewBox="0 0 240 120">
          <defs>
            <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="5"
                    orient="auto">
              <path d="M 1 1 L 9 5 L 1 9 Z" fill="#000"/>
            </marker>
            <filter id="blur" x="-50%" y="-50%" width="200%" height="200%">
              <feGaussianBlur stdDeviation="3"/>
            </filter>
          </defs>
          <g transform="translate(20 10) scale(2)">
            <rect id="transformed" x="5" y="5" width="10" height="10"
                  fill="#fff" stroke="#000" stroke-width="2"/>
          </g>
          <text id="middle" x="80" y="60" text-anchor="middle"
                dominant-baseline="middle" font-family="Inter, Arial, sans-serif"
                font-size="20">Paint tree</text>
          <path id="edge" d="M 120 80 L 210 80" fill="none" stroke="#000"
                stroke-width="2" marker-end="url(#arrow)"/>
          <rect id="filtered" x="20" y="90" width="20" height="10" fill="#000"
                filter="url(#blur)"/>
        </svg>
        """;

    Path first = temporaryDirectory.resolve("first.png");
    Path second = temporaryDirectory.resolve("second.png");
    try (var built = BrowserTestSupport.build(svg)) {
      assertThat(built.browserVersion()).isEqualTo(BrowserTestSupport.PINNED_CHROMIUM_VERSION);
      assertThat(built.fontReady()).isTrue();
      assertThat(built.computedFontFamily("middle")).isEqualTo("\"Dediren Liberation Sans\"");

      var transformed = built.geometryBounds("transformed");
      assertThat(transformed.minX()).isEqualTo(30.0);
      assertThat(transformed.maxX()).isEqualTo(50.0);

      var edge = built.paintedBounds("edge");
      assertThat(edge.height()).isGreaterThan(6.0);

      var filtered = built.paintedBounds("filtered");
      assertThat(filtered.width()).isGreaterThan(20.0);
      assertThat(filtered.height()).isGreaterThan(10.0);

      BrowserTestSupport.rasterize(built, first);
      BrowserTestSupport.rasterize(built, second);

      assertThat(built.probeExternalRequestIsBlocked("https://example.test/paint-probe")).isTrue();
      assertThat(built.blockedNetworkRequests())
          .containsExactly("https://example.test/paint-probe");
      assertThat(built.escapedNetworkResponses()).isEmpty();
    }

    assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
  }
}

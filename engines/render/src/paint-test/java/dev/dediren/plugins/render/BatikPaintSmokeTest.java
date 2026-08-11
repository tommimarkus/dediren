package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "dediren.render.paint.enabled", matches = "true")
class BatikPaintSmokeTest {

  @Test
  @Timeout(10)
  void buildsDecoratedStaticPaintWithOnlyTheBundledFont(@TempDir Path temporaryDirectory)
      throws Exception {
    assertThat(BatikTestSupport.version()).isEqualTo(BatikTestSupport.PINNED_BATIK_VERSION);
    assertThat(BatikTestSupport.fontFamily()).isEqualTo("Liberation Sans");
    assertThat(BatikTestSupport.canDisplay("Paint tree")).isTrue();
    assertThat(BatikTestSupport.canDisplay("漢🙂")).isFalse();

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
    try (var built = BatikTestSupport.build(svg)) {
      var transformed = built.transformedNonTextPrimitiveBounds("transformed");
      assertThat(transformed.getMinX()).isEqualTo(28.0);
      assertThat(transformed.getMaxX()).isEqualTo(52.0);

      assertThatIllegalArgumentException()
          .isThrownBy(() -> built.transformedNonTextPrimitiveBounds("middle"))
          .withMessageContaining("text bounds belong to the JDK text oracle");

      var edge = built.transformedNonTextPrimitiveBounds("edge");
      assertThat(edge.getHeight()).isGreaterThan(6.0);

      var filtered = built.transformedNonTextPaintBounds("filtered");
      assertThat(filtered.getWidth()).isGreaterThan(20.0);
      assertThat(filtered.getHeight()).isGreaterThan(10.0);

      BatikTestSupport.rasterize(built, first);
      BatikTestSupport.rasterize(built, second);
    }

    assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
  }
}

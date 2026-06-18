package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.render.RasterPolicy;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SvgRasterizerTest {
    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"40\" viewBox=\"0 0 100 40\">"
            + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"40\" fill=\"#ffffff\"/></svg>\n";

    @Test
    void producesPngOfIntrinsicSizeAtScaleOne() throws Exception {
        byte[] png = Base64.getDecoder().decode(SvgRasterizer.toPngBase64(SVG, new RasterPolicy(null, null)));
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(100);
        assertThat(image.getHeight()).isEqualTo(40);
    }

    @Test
    void scalesDimensionsByScaleFactor() throws Exception {
        byte[] png = Base64.getDecoder().decode(SvgRasterizer.toPngBase64(SVG, new RasterPolicy(2.0, null)));
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(80);
    }
}

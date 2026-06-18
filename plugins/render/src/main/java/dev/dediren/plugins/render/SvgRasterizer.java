package dev.dediren.plugins.render;

import dev.dediren.contracts.render.RasterPolicy;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

final class SvgRasterizer {
    private static final Pattern WIDTH = Pattern.compile("<svg[^>]*\\bwidth=\"([0-9.]+)\"");
    private static final Pattern HEIGHT = Pattern.compile("<svg[^>]*\\bheight=\"([0-9.]+)\"");

    private SvgRasterizer() {
    }

    static String toPngBase64(String svg, RasterPolicy raster) {
        double scale = raster == null || raster.scale() == null ? 1.0 : raster.scale();
        float width = (float) (intrinsic(WIDTH, svg) * scale);
        float height = (float) (intrinsic(HEIGHT, svg) * scale);

        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height);
        if (raster != null && raster.background() != null) {
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, parseColor(raster.background()));
        }

        var output = new ByteArrayOutputStream();
        try {
            transcoder.transcode(
                    new TranscoderInput(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))),
                    new TranscoderOutput(output));
        } catch (org.apache.batik.transcoder.TranscoderException error) {
            throw new RasterizationException(error);
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static double intrinsic(Pattern pattern, String svg) {
        Matcher matcher = pattern.matcher(svg);
        if (!matcher.find()) {
            throw new RasterizationException("svg root is missing an intrinsic size attribute");
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static Color parseColor(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
            throw new RasterizationException("raster.background must be a #RRGGBB hex color: " + hex);
        }
        try {
            int rgb = Integer.parseInt(hex.substring(1), 16);
            return new Color(rgb, false);
        } catch (NumberFormatException e) {
            throw new RasterizationException("raster.background must be a #RRGGBB hex color: " + hex);
        }
    }

    static final class RasterizationException extends RuntimeException {
        RasterizationException(String message) {
            super(message);
        }

        RasterizationException(Throwable cause) {
            super(cause);
        }
    }
}

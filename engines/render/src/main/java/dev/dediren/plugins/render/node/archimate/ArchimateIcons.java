package dev.dediren.plugins.render.node.archimate;

import static dev.dediren.plugins.render.node.NodeShapeSupport.ARCHIMATE_ICON_SIZE;
import static dev.dediren.plugins.render.node.NodeShapeSupport.ARCHIMATE_ICON_TOP_INSET;
import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.svg.Svg.f1;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.Locale;

// lean-audit:dup-intentional per-icon SVG path builders are deliberately parallel declarative code
public final class ArchimateIcons {

  private ArchimateIcons() {}

  public static void archimateNodeDecorator(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
    String name = decoratorName(decorator);
    ArchimateIconKind kind = archimateIconKind(decorator);
    double size = ARCHIMATE_ICON_SIZE;
    double x = node.x() + node.width() - size - 6.0;
    double y = node.y() + ARCHIMATE_ICON_TOP_INSET;
    w.start("g")
        .attr("data-dediren-node-decorator", name)
        .attr("data-dediren-icon-kind", kind.value())
        .attr("data-dediren-icon-size", "22");
    archimateIconBody(w, decorator, kind, x, y, size, style);
    w.end();
  }

  public static void archimateIconBody(
      SvgWriter w,
      SvgNodeDecorator decorator,
      ArchimateIconKind kind,
      double x,
      double y,
      double size,
      ResolvedNodeStyle style) {
    String fill = style.fill();
    String stroke = style.stroke();
    String width = styleNumber(style.strokeWidth());
    switch (kind) {
      case ACTOR -> archimateActorIconBody(w, x, y - 3.0, size, fill, stroke, width);
      case INTERFACE -> {
        ellipse(
            w,
            x + size * 0.72,
            y + size * 0.28,
            size * 0.18,
            size * 0.18,
            fill,
            stroke,
            width,
            null);
        outline(
            w,
            null,
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f",
                x + size * 0.1,
                y + size * 0.28,
                x + size * 0.54,
                y + size * 0.28),
            stroke,
            width);
      }
      case COLLABORATION -> {
        collaborationCircle(w, x + size * 0.38, y + size * 0.38, size * 0.26, fill, "none", null);
        collaborationCircle(w, x + size * 0.62, y + size * 0.38, size * 0.26, fill, "none", null);
        collaborationCircle(
            w, x + size * 0.38, y + size * 0.38, size * 0.26, "none", stroke, width);
        collaborationCircle(
            w, x + size * 0.62, y + size * 0.38, size * 0.26, "none", stroke, width);
      }
      case ROLE, STAKEHOLDER -> {
        outline(
            w,
            "side-cylinder",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f"
                    + " %.1f",
                x + size * 0.1,
                y + size * 0.2,
                size * 0.16,
                size * 0.16,
                x + size * 0.1,
                y + size * 0.52,
                x + size * 0.1,
                y + size * 0.2,
                x + size * 0.7,
                y + size * 0.2,
                x + size * 0.1,
                y + size * 0.52,
                x + size * 0.7,
                y + size * 0.52),
            stroke,
            width);
        ellipse(
            w,
            x + size * 0.7,
            y + size * 0.36,
            size * 0.16,
            size * 0.16,
            fill,
            stroke,
            width,
            "side-cylinder-end");
      }
      case SERVICE -> archimateServiceIconBody(w, decorator, x, y, size, fill, stroke, width);
      case INTERACTION -> {
        outline(
            w,
            "interaction-half",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f L %.1f %.1f",
                x + size * 0.42,
                y + size * 0.12,
                size * 0.24,
                size * 0.24,
                x + size * 0.42,
                y + size * 0.6,
                x + size * 0.42,
                y + size * 0.12),
            stroke,
            width);
        outline(
            w,
            "interaction-half",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f A %.1f %.1f 0 0 1 %.1f %.1f L %.1f %.1f",
                x + size * 0.58,
                y + size * 0.12,
                size * 0.24,
                size * 0.24,
                x + size * 0.58,
                y + size * 0.6,
                x + size * 0.58,
                y + size * 0.12),
            stroke,
            width);
      }
      case FUNCTION ->
          filled(
              w,
              "function-bookmark",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                  x + size * 0.18,
                  y + size * 0.2,
                  x + size * 0.5,
                  y + size * 0.06,
                  x + size * 0.82,
                  y + size * 0.2,
                  x + size * 0.82,
                  y + size * 0.7,
                  x + size * 0.5,
                  y + size * 0.56,
                  x + size * 0.18,
                  y + size * 0.7),
              fill,
              stroke,
              width);
      case PROCESS ->
          filled(
              w,
              "process-arrow",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f"
                      + " Z",
                  x,
                  y + size * 0.24,
                  x + size * 0.62,
                  y + size * 0.24,
                  x + size * 0.62,
                  y,
                  x + size,
                  y + size * 0.36,
                  x + size * 0.62,
                  y + size * 0.72,
                  x + size * 0.62,
                  y + size * 0.48,
                  x,
                  y + size * 0.48),
              fill,
              stroke,
              width);
      case COURSE_OF_ACTION ->
          archimateTargetIconBody(w, x, y, size, fill, stroke, width, TargetIconStyle.HANDLE);
      case EVENT ->
          filled(
              w,
              "event-pill",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f,"
                      + " %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                  x + size * 0.18,
                  y + size * 0.04,
                  x + size * 0.74,
                  y + size * 0.04,
                  x + size * 0.94,
                  y + size * 0.04,
                  x + size,
                  y + size * 0.2,
                  x + size,
                  y + size * 0.36,
                  x + size,
                  y + size * 0.52,
                  x + size * 0.94,
                  y + size * 0.68,
                  x + size * 0.74,
                  y + size * 0.68,
                  x + size * 0.18,
                  y + size * 0.68,
                  x + size * 0.34,
                  y + size * 0.36,
                  x + size * 0.18,
                  y + size * 0.04),
              fill,
              stroke,
              width);
      case OBJECT -> archimateDocumentIconBody(w, x, y, size, fill, stroke, width, false);
      case COMPONENT -> archimateApplicationComponentIconBody(w, x, y, size, fill, stroke, width);
      case CONTRACT -> archimateContractIconBody(w, x, y, size, fill, stroke, width);
      case PRODUCT ->
          w.empty("path")
              .attr("data-dediren-icon-part", "product-tab")
              .attr(
                  "d",
                  String.format(
                      Locale.ROOT,
                      "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L"
                          + " %.1f %.1f L %.1f %.1f",
                      x,
                      y,
                      x + size,
                      y,
                      x + size,
                      y + size * 0.72,
                      x,
                      y + size * 0.72,
                      x,
                      y,
                      x,
                      y + size * 0.24,
                      x + size * 0.62,
                      y + size * 0.24,
                      x + size * 0.62,
                      y))
              .attr("fill", fill)
              .attr("stroke", stroke)
              .attr("stroke-width", width)
              .attr("stroke-linejoin", "round");
      case REPRESENTATION ->
          filled(
              w,
              "wavy-representation",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f L %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f,"
                      + " %.1f %.1f, %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f",
                  x,
                  y,
                  x + size * 0.92,
                  y,
                  x + size * 0.92,
                  y + size * 0.58,
                  x + size * 0.74,
                  y + size * 0.5,
                  x + size * 0.56,
                  y + size * 0.5,
                  x + size * 0.42,
                  y + size * 0.58,
                  x + size * 0.28,
                  y + size * 0.66,
                  x + size * 0.14,
                  y + size * 0.66,
                  x,
                  y + size * 0.58,
                  x,
                  y,
                  x,
                  y + size * 0.22,
                  x + size * 0.92,
                  y + size * 0.22),
              fill,
              stroke,
              width);
      case LOCATION -> {
        double markerY = y - 3.0;
        outlineNoPartFilled(
            w,
            String.format(
                Locale.ROOT,
                "M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f Z",
                x + size * 0.5,
                markerY + size,
                x + size * 0.04,
                markerY + size * 0.5,
                x + size * 0.14,
                markerY,
                x + size * 0.5,
                markerY,
                x + size * 0.86,
                markerY,
                x + size * 0.96,
                markerY + size * 0.5,
                x + size * 0.5,
                markerY + size),
            fill,
            stroke,
            width);
      }
      case GROUPING ->
          w.empty("rect")
              .attr("x", f1(x))
              .attr("y", f1(y))
              .attr("width", f1(size))
              .attr("height", f1(size * 0.72))
              .attr("rx", "1.2")
              .attr("fill", "none")
              .attr("stroke", stroke)
              .attr("stroke-width", width)
              .attr("stroke-dasharray", "3 2");
      case DRIVER -> {
        ellipse(
            w,
            x + size * 0.5,
            y + size * 0.36,
            size * 0.28,
            size * 0.28,
            "none",
            stroke,
            width,
            null);
        w.empty("path")
            .attr("data-dediren-icon-part", "driver-spokes")
            .attr(
                "d",
                String.format(
                    Locale.ROOT,
                    "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f"
                        + " %.1f L %.1f %.1f",
                    x + size * 0.5,
                    y,
                    x + size * 0.5,
                    y + size * 0.72,
                    x + size * 0.14,
                    y + size * 0.36,
                    x + size * 0.86,
                    y + size * 0.36,
                    x + size * 0.26,
                    y + size * 0.12,
                    x + size * 0.74,
                    y + size * 0.6,
                    x + size * 0.26,
                    y + size * 0.6,
                    x + size * 0.74,
                    y + size * 0.12))
            .attr("fill", "none")
            .attr("stroke", stroke)
            .attr("stroke-width", width)
            .attr("stroke-linecap", "round");
        ellipse(
            w,
            x + size * 0.5,
            y + size * 0.36,
            size * 0.12,
            size * 0.08,
            stroke,
            stroke,
            width,
            null);
      }
      case ASSESSMENT -> {
        ellipse(
            w,
            x + size * 0.5,
            y + size * 0.28,
            size * 0.22,
            size * 0.22,
            "none",
            stroke,
            width,
            null);
        w.empty("path")
            .attr("data-dediren-icon-part", "assessment-handle")
            .attr(
                "d",
                String.format(
                    Locale.ROOT,
                    "M %.1f %.1f L %.1f %.1f",
                    x + size * 0.36,
                    y + size * 0.44,
                    x + size * 0.16,
                    y + size * 0.64))
            .attr("fill", "none")
            .attr("stroke", stroke)
            .attr("stroke-width", width)
            .attr("stroke-linecap", "round");
      }
      case GOAL ->
          archimateTargetIconBody(w, x, y, size, fill, stroke, width, TargetIconStyle.BULLSEYE);
      case OUTCOME ->
          archimateTargetIconBody(w, x, y, size, fill, stroke, width, TargetIconStyle.ARROW);
      case VALUE ->
          ellipse(
              w,
              x + size * 0.5,
              y + size * 0.36,
              size * 0.44,
              size * 0.24,
              fill,
              stroke,
              width,
              null);
      case MEANING ->
          outlineNoPartFilled(
              w,
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C"
                      + " %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f Z",
                  x + size * 0.18,
                  y + size * 0.54,
                  x + size * 0.04,
                  y + size * 0.38,
                  x + size * 0.18,
                  y + size * 0.22,
                  x + size * 0.36,
                  y + size * 0.28,
                  x + size * 0.46,
                  y + size * 0.04,
                  x + size * 0.68,
                  y + size * 0.18,
                  x + size * 0.66,
                  y + size * 0.34,
                  x + size * 0.92,
                  y + size * 0.32,
                  x + size * 0.94,
                  y + size * 0.54,
                  x + size * 0.72,
                  y + size * 0.62,
                  x + size * 0.42,
                  y + size * 0.68,
                  x + size * 0.26,
                  y + size * 0.62,
                  x + size * 0.18,
                  y + size * 0.54),
              fill,
              stroke,
              width);
      case CONSTRAINT -> {
        filled(
            w,
            "constraint-parallelogram",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                x + size * 0.22,
                y,
                x + size,
                y,
                x + size * 0.78,
                y + size * 0.72,
                x,
                y + size * 0.72),
            fill,
            stroke,
            width);
        outline(
            w,
            "constraint-left-line",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f",
                x + size * 0.32,
                y,
                x + size * 0.1,
                y + size * 0.72),
            stroke,
            width);
      }
      case REQUIREMENT ->
          filled(
              w,
              "requirement-parallelogram",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                  x + size * 0.22,
                  y,
                  x + size,
                  y,
                  x + size * 0.78,
                  y + size * 0.72,
                  x,
                  y + size * 0.72),
              fill,
              stroke,
              width);
      case PRINCIPLE -> {
        w.empty("rect")
            .attr("x", f1(x))
            .attr("y", f1(y))
            .attr("width", f1(size))
            .attr("height", f1(size * 0.72))
            .attr("rx", "1.2")
            .attr("fill", fill)
            .attr("stroke", stroke)
            .attr("stroke-width", width);
        w.empty("path")
            .attr(
                "d",
                String.format(
                    Locale.ROOT,
                    "M %.1f %.1f L %.1f %.1f",
                    x + size * 0.5,
                    y + size * 0.12,
                    x + size * 0.5,
                    y + size * 0.44))
            .attr("stroke", stroke)
            .attr("stroke-width", width);
        w.empty("circle")
            .attr("cx", f1(x + size * 0.5))
            .attr("cy", f1(y + size * 0.58))
            .attr("r", "1.2")
            .attr("fill", stroke);
      }
      case RESOURCE -> {
        w.empty("rect")
            .attr("data-dediren-icon-part", "resource-capsule")
            .attr("x", f1(x + size * 0.04))
            .attr("y", f1(y + size * 0.18))
            .attr("width", f1(size * 0.78))
            .attr("height", f1(size * 0.4))
            .attr("rx", f1(size * 0.12))
            .attr("fill", fill)
            .attr("stroke", stroke)
            .attr("stroke-width", width);
        w.empty("rect")
            .attr("data-dediren-icon-part", "resource-tab")
            .attr("x", f1(x + size * 0.82))
            .attr("y", f1(y + size * 0.3))
            .attr("width", f1(size * 0.08))
            .attr("height", f1(size * 0.2))
            .attr("rx", "0.8")
            .attr("fill", fill)
            .attr("stroke", stroke)
            .attr("stroke-width", width);
        w.empty("path")
            .attr("data-dediren-icon-part", "resource-bars")
            .attr(
                "d",
                String.format(
                    Locale.ROOT,
                    "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f",
                    x + size * 0.2,
                    y + size * 0.28,
                    x + size * 0.2,
                    y + size * 0.52,
                    x + size * 0.34,
                    y + size * 0.28,
                    x + size * 0.34,
                    y + size * 0.52,
                    x + size * 0.48,
                    y + size * 0.28,
                    x + size * 0.48,
                    y + size * 0.52))
            .attr("stroke", stroke)
            .attr("stroke-width", width)
            .attr("stroke-linecap", "round");
      }
      case VALUE_STREAM ->
          filled(
              w,
              "value-stream-chevron",
              String.format(
                  Locale.ROOT,
                  "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                  x,
                  y + size * 0.08,
                  x + size * 0.68,
                  y + size * 0.08,
                  x + size,
                  y + size * 0.36,
                  x + size * 0.68,
                  y + size * 0.64,
                  x,
                  y + size * 0.64,
                  x + size * 0.24,
                  y + size * 0.36),
              fill,
              stroke,
              width);
      case CAPABILITY -> archimateCapabilityIconBody(w, x, y, size, fill, stroke, width);
      case PLATEAU ->
          w.empty("path")
              .attr(
                  "d",
                  String.format(
                      Locale.ROOT,
                      "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f",
                      x + size * 0.26,
                      y + size * 0.18,
                      x + size * 0.88,
                      y + size * 0.18,
                      x + size * 0.12,
                      y + size * 0.36,
                      x + size * 0.74,
                      y + size * 0.36,
                      x,
                      y + size * 0.54,
                      x + size * 0.62,
                      y + size * 0.54))
              .attr("fill", "none")
              .attr("stroke", stroke)
              .attr("stroke-width", width)
              .attr("stroke-linecap", "square");
      case WORK_PACKAGE -> archimateWorkPackageIconBody(w, x, y, size, stroke, width);
      case DELIVERABLE ->
          archimateWavyDocumentIconBody(w, "wavy-document", x, y, size, fill, stroke, width);
      case GAP -> {
        ellipse(
            w,
            x + size * 0.52,
            y + size * 0.34,
            size * 0.22,
            size * 0.22,
            "none",
            stroke,
            width,
            null);
        w.empty("path")
            .attr("data-dediren-icon-part", "gap-lines")
            .attr(
                "d",
                String.format(
                    Locale.ROOT,
                    "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f",
                    x + size * 0.1,
                    y + size * 0.26,
                    x + size * 0.94,
                    y + size * 0.26,
                    x + size * 0.1,
                    y + size * 0.42,
                    x + size * 0.94,
                    y + size * 0.42))
            .attr("fill", "none")
            .attr("stroke", stroke)
            .attr("stroke-width", width)
            .attr("stroke-linecap", "round");
      }
      case ARTIFACT ->
          archimateFoldedDocumentIconBody(
              w, "artifact-document", x, y - 1.0, size, fill, stroke, width);
      case SYSTEM_SOFTWARE -> {
        ellipse(
            w,
            x + size * 0.58,
            y + size * 0.36,
            size * 0.26,
            size * 0.26,
            "none",
            stroke,
            width,
            "system-software-disks");
        ellipse(
            w,
            x + size * 0.38,
            y + size * 0.5,
            size * 0.26,
            size * 0.26,
            fill,
            stroke,
            width,
            "system-software-disks");
      }
      case DEVICE -> {
        w.empty("rect")
            .attr("x", f1(x + size * 0.08))
            .attr("y", f1(y + size * 0.04))
            .attr("width", f1(size * 0.84))
            .attr("height", f1(size * 0.52))
            .attr("rx", "2.0")
            .attr("fill", fill)
            .attr("stroke", stroke)
            .attr("stroke-width", width);
        w.empty("path")
            .attr("data-dediren-icon-part", "device-stand")
            .attr(
                "d",
                String.format(
                    Locale.ROOT,
                    "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f",
                    x + size * 0.5,
                    y + size * 0.56,
                    x + size * 0.5,
                    y + size * 0.72,
                    x + size * 0.32,
                    y + size * 0.72,
                    x + size * 0.68,
                    y + size * 0.72))
            .attr("fill", "none")
            .attr("stroke", stroke)
            .attr("stroke-width", width)
            .attr("stroke-linecap", "round");
      }
      case FACILITY -> archimateFacilityIconBody(w, x, y, size, fill, stroke, width);
      case EQUIPMENT -> archimateEquipmentIconBody(w, x, y, size, fill, stroke, width);
      case NODE -> archimateTechnologyNodeIconBody(w, x, y, size, fill, stroke, width);
      case MATERIAL -> archimateMaterialIconBody(w, x, y, size, fill, stroke, width);
      case NETWORK -> archimateNetworkIconBody(w, x, y, size, stroke, width);
      case DISTRIBUTION_NETWORK ->
          archimateDistributionNetworkIconBody(w, x, y, size, fill, stroke, width);
      case PATH -> archimatePathIconBody(w, x, y, size, stroke, width);
      case JUNCTION -> {}
    }
  }

  // A filled <path> carrying a data-dediren-icon-part.
  private static void filled(
      SvgWriter w, String part, String d, String fill, String stroke, String width) {
    w.empty("path")
        .attr("data-dediren-icon-part", part)
        .attr("d", d)
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  // A stroked (fill="none") <path> carrying a data-dediren-icon-part.
  private static void outline(SvgWriter w, String part, String d, String stroke, String width) {
    w.empty("path")
        .attrIf("data-dediren-icon-part", part)
        .attr("d", d)
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  // A filled <path> with no data-part (icon silhouettes drawn as a single anonymous outline).
  private static void outlineNoPartFilled(
      SvgWriter w, String d, String fill, String stroke, String width) {
    w.empty("path")
        .attr("d", d)
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  private static void ellipse(
      SvgWriter w,
      double cx,
      double cy,
      double rx,
      double ry,
      String fill,
      String stroke,
      String width,
      String part) {
    w.empty("ellipse")
        .attrIf("data-dediren-icon-part", part)
        .attr("cx", f1(cx))
        .attr("cy", f1(cy))
        .attr("rx", f1(rx))
        .attr("ry", f1(ry))
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  private static void collaborationCircle(
      SvgWriter w, double cx, double cy, double r, String fill, String stroke, String width) {
    w.empty("circle")
        .attr("data-dediren-icon-part", "collaboration-circles")
        .attr("cx", f1(cx))
        .attr("cy", f1(cy))
        .attr("r", f1(r))
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attrIf("stroke-width", width);
  }

  public static void archimateServiceIconBody(
      SvgWriter w,
      SvgNodeDecorator decorator,
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width) {
    double serviceY =
        decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE ? y : y + size * 0.12;
    double serviceHeight =
        decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE ? size * 0.62 : size * 0.5;
    w.empty("rect")
        .attr("x", f1(x))
        .attr("y", f1(serviceY))
        .attr("width", f1(size))
        .attr("height", f1(serviceHeight))
        .attr("rx", f1(size * 0.18))
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  public static void archimateActorIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    double cx = x + size * 0.5;
    double headRx = size * 0.16;
    double headRy = size * 0.2;
    double headCy = y + headRy;
    double bodyTop = y + headRy * 2.0 + size * 0.08;
    double bodyBottom = y + size * 0.72;
    double armY = bodyTop + size * 0.12;
    ellipse(w, cx, headCy, headRx, headRy, fill, stroke, width, null);
    w.empty("path")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f"
                    + " M %.1f %.1f L %.1f %.1f",
                cx,
                bodyTop,
                cx,
                bodyBottom,
                cx - size * 0.28,
                armY,
                cx,
                bodyTop + size * 0.1,
                cx + size * 0.28,
                armY,
                cx,
                bodyBottom,
                cx - size * 0.24,
                y + size,
                cx,
                bodyBottom,
                cx + size * 0.24,
                y + size))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linecap", "round")
        .attr("stroke-linejoin", "round");
  }

  public static void archimateApplicationComponentIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    double tabWidth = size * 0.36;
    double tabHeight = size * 0.26;
    double bodyX = x + tabWidth / 2.0;
    double bodyWidth = size - tabWidth / 2.0;
    componentRect(w, bodyX, y, bodyWidth, size * 0.72, "1.5", fill, stroke, width);
    componentRect(w, x, y + size * 0.12, tabWidth, tabHeight, "1.2", fill, stroke, width);
    componentRect(w, x, y + size * 0.44, tabWidth, tabHeight, "1.2", fill, stroke, width);
  }

  private static void componentRect(
      SvgWriter w,
      double x,
      double y,
      double width,
      double height,
      String rx,
      String fill,
      String stroke,
      String strokeWidth) {
    w.empty("rect")
        .attr("x", f1(x))
        .attr("y", f1(y))
        .attr("width", f1(width))
        .attr("height", f1(height))
        .attr("rx", rx)
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", strokeWidth);
  }

  public static void archimateTargetIconBody(
      SvgWriter w,
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width,
      TargetIconStyle style) {
    double centerX = x + size * 0.5;
    double centerY = y + size * 0.36;
    double outer = size * 0.34;
    double inner = size * 0.16;
    ellipse(w, centerX, centerY, outer, outer, fill, stroke, width, null);
    ellipse(w, centerX, centerY, inner, inner, "none", stroke, width, null);
    ellipse(w, centerX, centerY, size * 0.05, size * 0.05, "none", stroke, width, null);
    switch (style) {
      case BULLSEYE -> {}
      case ARROW ->
          w.empty("path")
              .attr("data-dediren-icon-part", "target-arrow")
              .attr(
                  "d",
                  String.format(
                      Locale.ROOT,
                      "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f"
                          + " %.1f L %.1f %.1f",
                      centerX,
                      centerY,
                      x + size * 0.82,
                      y + size * 0.08,
                      x + size * 0.82,
                      y + size * 0.04,
                      x + size * 0.82,
                      y + size * 0.13,
                      x + size * 0.78,
                      y + size * 0.12,
                      x + size * 0.86,
                      y + size * 0.05,
                      x + size * 0.82,
                      y + size * 0.08,
                      x + size * 0.88,
                      y + size * 0.01))
              .attr("fill", "none")
              .attr("stroke", stroke)
              .attr("stroke-width", width)
              .attr("stroke-linecap", "round");
      case HANDLE ->
          w.empty("path")
              .attr("data-dediren-icon-part", "course-of-action-handle")
              .attr(
                  "d",
                  String.format(
                      Locale.ROOT,
                      "M %.1f %.1f L %.1f %.1f",
                      centerX - size * 0.22,
                      centerY + size * 0.2,
                      x + size * 0.06,
                      y + size * 0.72))
              .attr("fill", "none")
              .attr("stroke", stroke)
              .attr("stroke-width", width)
              .attr("stroke-linecap", "round");
    }
  }

  public static void archimateDocumentIconBody(
      SvgWriter w,
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width,
      boolean folded) {
    if (folded) {
      filled(
          w,
          "document-fold",
          String.format(
              Locale.ROOT,
              "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f"
                  + " L %.1f %.1f",
              x,
              y,
              x + size * 0.72,
              y,
              x + size * 0.92,
              y + size * 0.2,
              x + size * 0.92,
              y + size * 0.72,
              x,
              y + size * 0.72,
              x + size * 0.72,
              y,
              x + size * 0.72,
              y + size * 0.2,
              x + size * 0.92,
              y + size * 0.2),
          fill,
          stroke,
          width);
      outline(
          w,
          "document-header",
          String.format(
              Locale.ROOT,
              "M %.1f %.1f L %.1f %.1f",
              x,
              y + size * 0.22,
              x + size * 0.68,
              y + size * 0.22),
          stroke,
          width);
      return;
    }
    filled(
        w,
        "document-body",
        String.format(
            Locale.ROOT,
            "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
            x,
            y,
            x + size * 0.92,
            y,
            x + size * 0.92,
            y + size * 0.72,
            x,
            y + size * 0.72),
        fill,
        stroke,
        width);
    outline(
        w,
        "document-header",
        String.format(
            Locale.ROOT,
            "M %.1f %.1f L %.1f %.1f",
            x,
            y + size * 0.22,
            x + size * 0.92,
            y + size * 0.22),
        stroke,
        width);
  }

  public static void archimateContractIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    filled(
        w,
        "contract-document-body",
        String.format(
            Locale.ROOT,
            "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
            x,
            y,
            x + size * 0.92,
            y,
            x + size * 0.92,
            y + size * 0.72,
            x,
            y + size * 0.72),
        fill,
        stroke,
        width);
    outline(
        w,
        "contract-lines",
        String.format(
            Locale.ROOT,
            "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f",
            x,
            y + size * 0.24,
            x + size * 0.92,
            y + size * 0.24,
            x,
            y + size * 0.48,
            x + size * 0.92,
            y + size * 0.48),
        stroke,
        width);
  }

  public static void archimateCapabilityIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    double[][] steps = {
      {0.2, 0.52},
      {0.4, 0.52},
      {0.4, 0.32},
      {0.6, 0.52},
      {0.6, 0.32},
      {0.6, 0.12}
    };
    for (double[] step : steps) {
      w.empty("rect")
          .attr("data-dediren-icon-part", "capability-step")
          .attr("x", f1(x + size * step[0]))
          .attr("y", f1(y + size * step[1]))
          .attr("width", f1(size * 0.2))
          .attr("height", f1(size * 0.2))
          .attr("fill", fill)
          .attr("stroke", stroke)
          .attr("stroke-width", width);
    }
  }

  public static void archimateWorkPackageIconBody(
      SvgWriter w, double x, double y, double size, String stroke, String width) {
    double loopX = x - size * 0.14;
    double loopY = y - size * 0.3;
    w.empty("path")
        .attr("data-dediren-icon-part", "work-package-loop-arrow")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f A %.1f %.1f 0 1 0 %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f"
                    + " %.1f L %.1f %.1f",
                loopX + size * 0.58,
                loopY + size * 0.52,
                size * 0.26,
                size * 0.26,
                loopX + size * 0.54,
                loopY + size * 0.72,
                loopX + size * 0.86,
                loopY + size * 0.72,
                loopX + size * 0.86,
                loopY + size * 0.72,
                loopX + size * 0.74,
                loopY + size * 0.62,
                loopX + size * 0.86,
                loopY + size * 0.72,
                loopX + size * 0.74,
                loopY + size * 0.82))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linecap", "round")
        .attr("stroke-linejoin", "round");
  }

  public static void archimateWavyDocumentIconBody(
      SvgWriter w,
      String part,
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width) {
    w.empty("path")
        .attr("data-dediren-icon-part", part)
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f,"
                    + " %.1f %.1f, %.1f %.1f L %.1f %.1f Z",
                x,
                y,
                x + size,
                y,
                x + size,
                y + size * 0.58,
                x + size * 0.82,
                y + size * 0.5,
                x + size * 0.66,
                y + size * 0.5,
                x + size * 0.5,
                y + size * 0.58,
                x + size * 0.34,
                y + size * 0.66,
                x + size * 0.18,
                y + size * 0.66,
                x,
                y + size * 0.58,
                x,
                y))
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linejoin", "round");
  }

  public static void archimateFoldedDocumentIconBody(
      SvgWriter w,
      String part,
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width) {
    w.empty("path")
        .attr("data-dediren-icon-part", part)
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f"
                    + " L %.1f %.1f",
                x + size * 0.1,
                y,
                x + size * 0.58,
                y,
                x + size * 0.86,
                y + size * 0.28,
                x + size * 0.86,
                y + size * 0.9,
                x + size * 0.1,
                y + size * 0.9,
                x + size * 0.58,
                y,
                x + size * 0.58,
                y + size * 0.28,
                x + size * 0.86,
                y + size * 0.28))
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  public static void archimateFacilityIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    filled(
        w,
        "factory-silhouette",
        String.format(
            Locale.ROOT,
            "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L"
                + " %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
            x + size * 0.08,
            y + size * 0.72,
            x + size * 0.08,
            y + size * 0.08,
            x + size * 0.22,
            y + size * 0.08,
            x + size * 0.22,
            y + size * 0.48,
            x + size * 0.42,
            y + size * 0.34,
            x + size * 0.42,
            y + size * 0.48,
            x + size * 0.62,
            y + size * 0.34,
            x + size * 0.62,
            y + size * 0.48,
            x + size * 0.84,
            y + size * 0.34,
            x + size * 0.84,
            y + size * 0.72,
            x + size * 0.08,
            y + size * 0.72),
        fill,
        stroke,
        width);
  }

  public static void archimateEquipmentIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    archimateGearPath(
        w,
        "equipment-gear-large",
        x + size * 0.34,
        y + size * 0.52,
        size * 0.24,
        fill,
        stroke,
        width);
    w.empty("circle")
        .attr("data-dediren-icon-part", "equipment-gear-hole")
        .attr("cx", f1(x + size * 0.34))
        .attr("cy", f1(y + size * 0.52))
        .attr("r", f1(size * 0.06))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width);
    archimateGearPath(
        w,
        "equipment-gear-small",
        x + size * 0.68,
        y + size * 0.24,
        size * 0.16,
        fill,
        stroke,
        width);
    w.empty("circle")
        .attr("data-dediren-icon-part", "equipment-gear-hole")
        .attr("cx", f1(x + size * 0.68))
        .attr("cy", f1(y + size * 0.24))
        .attr("r", f1(size * 0.04))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  public static void archimateGearPath(
      SvgWriter w,
      String part,
      double cx,
      double cy,
      double radius,
      String fill,
      String stroke,
      String width) {
    double[][] points =
        new double[][] {
          {cx, cy - radius}, {cx + radius * 0.18, cy - radius * 0.72},
          {cx + radius * 0.42, cy - radius * 0.9}, {cx + radius * 0.52, cy - radius * 0.58},
          {cx + radius * 0.84, cy - radius * 0.54}, {cx + radius * 0.72, cy - radius * 0.18},
          {cx + radius, cy}, {cx + radius * 0.72, cy + radius * 0.18},
          {cx + radius * 0.84, cy + radius * 0.54}, {cx + radius * 0.52, cy + radius * 0.58},
          {cx + radius * 0.42, cy + radius * 0.9}, {cx + radius * 0.18, cy + radius * 0.72},
          {cx, cy + radius}, {cx - radius * 0.18, cy + radius * 0.72},
          {cx - radius * 0.42, cy + radius * 0.9}, {cx - radius * 0.52, cy + radius * 0.58},
          {cx - radius * 0.84, cy + radius * 0.54}, {cx - radius * 0.72, cy + radius * 0.18},
          {cx - radius, cy}, {cx - radius * 0.72, cy - radius * 0.18},
          {cx - radius * 0.84, cy - radius * 0.54}, {cx - radius * 0.52, cy - radius * 0.58},
          {cx - radius * 0.42, cy - radius * 0.9}, {cx - radius * 0.18, cy - radius * 0.72}
        };
    StringBuilder d = new StringBuilder();
    for (int index = 0; index < points.length; index++) {
      if (index > 0) {
        d.append(" ");
      }
      d.append(index == 0 ? "M" : "L")
          .append(" ")
          .append(styleNumber(points[index][0]))
          .append(" ")
          .append(styleNumber(points[index][1]));
    }
    w.empty("path")
        .attr("data-dediren-icon-part", part)
        .attr("d", d + " Z")
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  public static void archimateTechnologyNodeIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    double bodyY = y + size * 0.18;
    double depth = size * 0.18;
    w.empty("rect")
        .attr("x", f1(x))
        .attr("y", f1(bodyY))
        .attr("width", f1(size - depth))
        .attr("height", f1(size * 0.58))
        .attr("rx", "1.5")
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width);
    w.empty("path")
        .attr("data-dediren-icon-part", "node-3d-edges")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f"
                    + " L %.1f %.1f M %.1f %.1f L %.1f %.1f",
                x,
                bodyY,
                x + depth,
                bodyY - depth,
                x + size,
                bodyY - depth,
                x + size - depth,
                bodyY,
                x + size - depth,
                bodyY,
                x + size,
                bodyY - depth,
                x + size - depth,
                bodyY + size * 0.58,
                x + size,
                bodyY + size * 0.58 - depth,
                x + size,
                bodyY - depth,
                x + size,
                bodyY + size * 0.58 - depth))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linejoin", "round");
  }

  public static void archimateMaterialIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    filled(
        w,
        "material-hexagon",
        String.format(
            Locale.ROOT,
            "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
            x + size * 0.25,
            y,
            x + size * 0.75,
            y,
            x + size,
            y + size * 0.36,
            x + size * 0.75,
            y + size * 0.72,
            x + size * 0.25,
            y + size * 0.72,
            x,
            y + size * 0.36),
        fill,
        stroke,
        width);
    w.empty("path")
        .attr("data-dediren-icon-part", "material-lines")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f",
                x + size * 0.36,
                y + size * 0.16,
                x + size * 0.64,
                y + size * 0.16,
                x + size * 0.78,
                y + size * 0.28,
                x + size * 0.62,
                y + size * 0.52,
                x + size * 0.22,
                y + size * 0.28,
                x + size * 0.38,
                y + size * 0.52))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linecap", "round");
  }

  public static void archimateNetworkIconBody(
      SvgWriter w, double x, double y, double size, String stroke, String width) {
    networkNode(w, x + size * 0.32, y + size * 0.22, stroke);
    networkNode(w, x + size * 0.72, y + size * 0.22, stroke);
    networkNode(w, x + size * 0.22, y + size * 0.58, stroke);
    networkNode(w, x + size * 0.62, y + size * 0.58, stroke);
    w.empty("path")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                x + size * 0.32,
                y + size * 0.22,
                x + size * 0.72,
                y + size * 0.22,
                x + size * 0.62,
                y + size * 0.58,
                x + size * 0.22,
                y + size * 0.58))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width);
  }

  private static void networkNode(SvgWriter w, double cx, double cy, String fill) {
    w.empty("circle").attr("cx", f1(cx)).attr("cy", f1(cy)).attr("r", "2.3").attr("fill", fill);
  }

  public static void archimateDistributionNetworkIconBody(
      SvgWriter w, double x, double y, double size, String fill, String stroke, String width) {
    w.empty("path")
        .attr("data-dediren-icon-part", "distribution-network-arrows")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f"
                    + " L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                x + size * 0.12,
                y + size * 0.36,
                x + size * 0.3,
                y + size * 0.22,
                x + size * 0.3,
                y + size * 0.3,
                x + size * 0.7,
                y + size * 0.3,
                x + size * 0.7,
                y + size * 0.22,
                x + size * 0.88,
                y + size * 0.36,
                x + size * 0.7,
                y + size * 0.5,
                x + size * 0.7,
                y + size * 0.42,
                x + size * 0.3,
                y + size * 0.42,
                x + size * 0.3,
                y + size * 0.5))
        .attr("fill", fill)
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linejoin", "round");
  }

  public static void archimatePathIconBody(
      SvgWriter w, double x, double y, double size, String stroke, String width) {
    w.empty("path")
        .attr("data-dediren-icon-part", "path-line")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f",
                x + size * 0.08,
                y + size * 0.36,
                x + size * 0.92,
                y + size * 0.36))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-dasharray", "3 2")
        .attr("stroke-linecap", "round");
    w.empty("path")
        .attr("data-dediren-icon-part", "path-arrowheads")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f"
                    + " L %.1f %.1f",
                x + size * 0.25,
                y + size * 0.16,
                x + size * 0.08,
                y + size * 0.36,
                x + size * 0.75,
                y + size * 0.16,
                x + size * 0.92,
                y + size * 0.36,
                x + size * 0.25,
                y + size * 0.56,
                x + size * 0.08,
                y + size * 0.36,
                x + size * 0.75,
                y + size * 0.56,
                x + size * 0.92,
                y + size * 0.36))
        .attr("fill", "none")
        .attr("stroke", stroke)
        .attr("stroke-width", width)
        .attr("stroke-linecap", "round");
  }

  public static ArchimateIconKind archimateIconKind(SvgNodeDecorator decorator) {
    return switch (decorator) {
      case ARCHIMATE_BUSINESS_INTERFACE,
          ARCHIMATE_APPLICATION_INTERFACE,
          ARCHIMATE_TECHNOLOGY_INTERFACE ->
          ArchimateIconKind.INTERFACE;
      case ARCHIMATE_BUSINESS_COLLABORATION,
          ARCHIMATE_APPLICATION_COLLABORATION,
          ARCHIMATE_TECHNOLOGY_COLLABORATION ->
          ArchimateIconKind.COLLABORATION;
      case ARCHIMATE_BUSINESS_ACTOR -> ArchimateIconKind.ACTOR;
      case ARCHIMATE_BUSINESS_ROLE -> ArchimateIconKind.ROLE;
      case ARCHIMATE_BUSINESS_SERVICE,
          ARCHIMATE_APPLICATION_SERVICE,
          ARCHIMATE_TECHNOLOGY_SERVICE ->
          ArchimateIconKind.SERVICE;
      case ARCHIMATE_BUSINESS_INTERACTION,
          ARCHIMATE_APPLICATION_INTERACTION,
          ARCHIMATE_TECHNOLOGY_INTERACTION ->
          ArchimateIconKind.INTERACTION;
      case ARCHIMATE_BUSINESS_FUNCTION,
          ARCHIMATE_APPLICATION_FUNCTION,
          ARCHIMATE_TECHNOLOGY_FUNCTION ->
          ArchimateIconKind.FUNCTION;
      case ARCHIMATE_BUSINESS_PROCESS,
          ARCHIMATE_APPLICATION_PROCESS,
          ARCHIMATE_TECHNOLOGY_PROCESS ->
          ArchimateIconKind.PROCESS;
      case ARCHIMATE_BUSINESS_EVENT,
          ARCHIMATE_APPLICATION_EVENT,
          ARCHIMATE_TECHNOLOGY_EVENT,
          ARCHIMATE_IMPLEMENTATION_EVENT ->
          ArchimateIconKind.EVENT;
      case ARCHIMATE_BUSINESS_OBJECT, ARCHIMATE_DATA_OBJECT -> ArchimateIconKind.OBJECT;
      case ARCHIMATE_APPLICATION_COMPONENT -> ArchimateIconKind.COMPONENT;
      case ARCHIMATE_CONTRACT -> ArchimateIconKind.CONTRACT;
      case ARCHIMATE_PRODUCT -> ArchimateIconKind.PRODUCT;
      case ARCHIMATE_REPRESENTATION -> ArchimateIconKind.REPRESENTATION;
      case ARCHIMATE_LOCATION -> ArchimateIconKind.LOCATION;
      case ARCHIMATE_GROUPING -> ArchimateIconKind.GROUPING;
      case ARCHIMATE_AND_JUNCTION, ARCHIMATE_OR_JUNCTION -> ArchimateIconKind.JUNCTION;
      case ARCHIMATE_STAKEHOLDER -> ArchimateIconKind.STAKEHOLDER;
      case ARCHIMATE_DRIVER -> ArchimateIconKind.DRIVER;
      case ARCHIMATE_ASSESSMENT -> ArchimateIconKind.ASSESSMENT;
      case ARCHIMATE_GOAL -> ArchimateIconKind.GOAL;
      case ARCHIMATE_OUTCOME -> ArchimateIconKind.OUTCOME;
      case ARCHIMATE_VALUE -> ArchimateIconKind.VALUE;
      case ARCHIMATE_MEANING -> ArchimateIconKind.MEANING;
      case ARCHIMATE_CONSTRAINT -> ArchimateIconKind.CONSTRAINT;
      case ARCHIMATE_REQUIREMENT -> ArchimateIconKind.REQUIREMENT;
      case ARCHIMATE_PRINCIPLE -> ArchimateIconKind.PRINCIPLE;
      case ARCHIMATE_COURSE_OF_ACTION -> ArchimateIconKind.COURSE_OF_ACTION;
      case ARCHIMATE_RESOURCE -> ArchimateIconKind.RESOURCE;
      case ARCHIMATE_VALUE_STREAM -> ArchimateIconKind.VALUE_STREAM;
      case ARCHIMATE_CAPABILITY -> ArchimateIconKind.CAPABILITY;
      case ARCHIMATE_PLATEAU -> ArchimateIconKind.PLATEAU;
      case ARCHIMATE_WORK_PACKAGE -> ArchimateIconKind.WORK_PACKAGE;
      case ARCHIMATE_DELIVERABLE -> ArchimateIconKind.DELIVERABLE;
      case ARCHIMATE_GAP -> ArchimateIconKind.GAP;
      case ARCHIMATE_ARTIFACT -> ArchimateIconKind.ARTIFACT;
      case ARCHIMATE_SYSTEM_SOFTWARE -> ArchimateIconKind.SYSTEM_SOFTWARE;
      case ARCHIMATE_DEVICE -> ArchimateIconKind.DEVICE;
      case ARCHIMATE_FACILITY -> ArchimateIconKind.FACILITY;
      case ARCHIMATE_EQUIPMENT -> ArchimateIconKind.EQUIPMENT;
      case ARCHIMATE_TECHNOLOGY_NODE -> ArchimateIconKind.NODE;
      case ARCHIMATE_MATERIAL -> ArchimateIconKind.MATERIAL;
      case ARCHIMATE_COMMUNICATION_NETWORK -> ArchimateIconKind.NETWORK;
      case ARCHIMATE_DISTRIBUTION_NETWORK -> ArchimateIconKind.DISTRIBUTION_NETWORK;
      case ARCHIMATE_PATH -> ArchimateIconKind.PATH;
      default -> throw new IllegalArgumentException("not an ArchiMate decorator: " + decorator);
    };
  }
}

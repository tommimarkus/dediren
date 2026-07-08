package dev.dediren.plugins.render.node.archimate;

import static dev.dediren.plugins.render.node.NodeShapeSupport.ARCHIMATE_ICON_SIZE;
import static dev.dediren.plugins.render.node.NodeShapeSupport.ARCHIMATE_ICON_TOP_INSET;
import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import java.util.Locale;

// lean-audit:dup-intentional per-icon SVG path builders are deliberately parallel declarative code
public final class ArchimateIcons {

  private ArchimateIcons() {}

  public static String archimateNodeDecorator(
      LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
    String name = decoratorName(decorator);
    ArchimateIconKind kind = archimateIconKind(decorator);
    double size = ARCHIMATE_ICON_SIZE;
    double x = node.x() + node.width() - size - 6.0;
    double y = node.y() + ARCHIMATE_ICON_TOP_INSET;
    String body = archimateIconBody(decorator, kind, x, y, size, style);
    return "<g data-dediren-node-decorator=\""
        + attr(name)
        + "\" data-dediren-icon-kind=\""
        + kind.value()
        + "\" data-dediren-icon-size=\"22\">"
        + body
        + "</g>";
  }

  public static String archimateIconBody(
      SvgNodeDecorator decorator,
      ArchimateIconKind kind,
      double x,
      double y,
      double size,
      ResolvedNodeStyle style) {
    String fill = attr(style.fill());
    String stroke = attr(style.stroke());
    String width = styleNumber(style.strokeWidth());
    return switch (kind) {
      case ACTOR -> archimateActorIconBody(x, y - 3.0, size, fill, stroke, width);
      case INTERFACE ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.72,
              y + size * 0.28,
              size * 0.18,
              size * 0.18,
              fill,
              stroke,
              width,
              x + size * 0.1,
              y + size * 0.28,
              x + size * 0.54,
              y + size * 0.28,
              stroke,
              width);
      case COLLABORATION ->
          String.format(
              Locale.ROOT,
              "<circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"none\"/><circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"none\"/><circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.38,
              y + size * 0.38,
              size * 0.26,
              fill,
              x + size * 0.62,
              y + size * 0.38,
              size * 0.26,
              fill,
              x + size * 0.38,
              y + size * 0.38,
              size * 0.26,
              stroke,
              width,
              x + size * 0.62,
              y + size * 0.38,
              size * 0.26,
              stroke,
              width);
      case ROLE, STAKEHOLDER ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"side-cylinder\" d=\"M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse data-dediren-icon-part=\"side-cylinder-end\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.52,
              stroke,
              width,
              x + size * 0.7,
              y + size * 0.36,
              size * 0.16,
              size * 0.16,
              fill,
              stroke,
              width);
      case SERVICE -> archimateServiceIconBody(decorator, x, y, size, fill, stroke, width);
      case INTERACTION ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"interaction-half\" d=\"M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"interaction-half\" d=\"M %.1f %.1f A %.1f %.1f 0 0 1 %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.42,
              y + size * 0.12,
              size * 0.24,
              size * 0.24,
              x + size * 0.42,
              y + size * 0.6,
              x + size * 0.42,
              y + size * 0.12,
              stroke,
              width,
              x + size * 0.58,
              y + size * 0.12,
              size * 0.24,
              size * 0.24,
              x + size * 0.58,
              y + size * 0.6,
              x + size * 0.58,
              y + size * 0.12,
              stroke,
              width);
      case FUNCTION ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"function-bookmark\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.7,
              fill,
              stroke,
              width);
      case PROCESS ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"process-arrow\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.48,
              fill,
              stroke,
              width);
      case COURSE_OF_ACTION ->
          archimateTargetIconBody(x, y, size, fill, stroke, width, TargetIconStyle.HANDLE);
      case EVENT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"event-pill\" d=\"M %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.04,
              fill,
              stroke,
              width);
      case OBJECT -> archimateDocumentIconBody(x, y, size, fill, stroke, width, false);
      case COMPONENT -> archimateApplicationComponentIconBody(x, y, size, fill, stroke, width);
      case CONTRACT -> archimateContractIconBody(x, y, size, fill, stroke, width);
      case PRODUCT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"product-tab\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
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
              y,
              fill,
              stroke,
              width);
      case REPRESENTATION ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"wavy-representation\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.22,
              fill,
              stroke,
              width);
      case LOCATION -> {
        double markerY = y - 3.0;
        yield String.format(
            Locale.ROOT,
            "<path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
            markerY + size,
            fill,
            stroke,
            width);
      }
      case GROUPING ->
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-dasharray=\"3 2\"/>",
              x,
              y,
              size,
              size * 0.72,
              stroke,
              width);
      case DRIVER ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"driver-spokes\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/><ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.5,
              y + size * 0.36,
              size * 0.28,
              size * 0.28,
              stroke,
              width,
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
              y + size * 0.12,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.36,
              size * 0.12,
              size * 0.08,
              stroke,
              stroke,
              width);
      case ASSESSMENT ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"assessment-handle\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.5,
              y + size * 0.28,
              size * 0.22,
              size * 0.22,
              stroke,
              width,
              x + size * 0.36,
              y + size * 0.44,
              x + size * 0.16,
              y + size * 0.64,
              stroke,
              width);
      case GOAL ->
          archimateTargetIconBody(x, y, size, fill, stroke, width, TargetIconStyle.BULLSEYE);
      case OUTCOME ->
          archimateTargetIconBody(x, y, size, fill, stroke, width, TargetIconStyle.ARROW);
      case VALUE ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.5,
              y + size * 0.36,
              size * 0.44,
              size * 0.24,
              fill,
              stroke,
              width);
      case MEANING ->
          String.format(
              Locale.ROOT,
              "<path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.54,
              fill,
              stroke,
              width);
      case CONSTRAINT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"constraint-parallelogram\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"constraint-left-line\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.22,
              y,
              x + size,
              y,
              x + size * 0.78,
              y + size * 0.72,
              x,
              y + size * 0.72,
              fill,
              stroke,
              width,
              x + size * 0.32,
              y,
              x + size * 0.1,
              y + size * 0.72,
              stroke,
              width);
      case REQUIREMENT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"requirement-parallelogram\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.22,
              y,
              x + size,
              y,
              x + size * 0.78,
              y + size * 0.72,
              x,
              y + size * 0.72,
              fill,
              stroke,
              width);
      case PRINCIPLE ->
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f\" stroke=\"%s\" stroke-width=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"1.2\" fill=\"%s\"/>",
              x,
              y,
              size,
              size * 0.72,
              fill,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.12,
              x + size * 0.5,
              y + size * 0.44,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.58,
              stroke);
      case RESOURCE ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-icon-part=\"resource-capsule\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect data-dediren-icon-part=\"resource-tab\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"0.8\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"resource-bars\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.04,
              y + size * 0.18,
              size * 0.78,
              size * 0.4,
              size * 0.12,
              fill,
              stroke,
              width,
              x + size * 0.82,
              y + size * 0.3,
              size * 0.08,
              size * 0.2,
              fill,
              stroke,
              width,
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
              y + size * 0.52,
              stroke,
              width);
      case VALUE_STREAM ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"value-stream-chevron\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
              y + size * 0.36,
              fill,
              stroke,
              width);
      case CAPABILITY -> archimateCapabilityIconBody(x, y, size, fill, stroke, width);
      case PLATEAU ->
          String.format(
              Locale.ROOT,
              "<path d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"square\"/>",
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
              y + size * 0.54,
              stroke,
              width);
      case WORK_PACKAGE -> archimateWorkPackageIconBody(x, y, size, stroke, width);
      case DELIVERABLE ->
          archimateWavyDocumentIconBody("wavy-document", x, y, size, fill, stroke, width);
      case GAP ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"gap-lines\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.52,
              y + size * 0.34,
              size * 0.22,
              size * 0.22,
              stroke,
              width,
              x + size * 0.1,
              y + size * 0.26,
              x + size * 0.94,
              y + size * 0.26,
              x + size * 0.1,
              y + size * 0.42,
              x + size * 0.94,
              y + size * 0.42,
              stroke,
              width);
      case ARTIFACT ->
          archimateFoldedDocumentIconBody(
              "artifact-document", x, y - 1.0, size, fill, stroke, width);
      case SYSTEM_SOFTWARE ->
          String.format(
              Locale.ROOT,
              "<ellipse data-dediren-icon-part=\"system-software-disks\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse data-dediren-icon-part=\"system-software-disks\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.58,
              y + size * 0.36,
              size * 0.26,
              size * 0.26,
              stroke,
              width,
              x + size * 0.38,
              y + size * 0.5,
              size * 0.26,
              size * 0.26,
              fill,
              stroke,
              width);
      case DEVICE ->
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2.0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"device-stand\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.08,
              y + size * 0.04,
              size * 0.84,
              size * 0.52,
              fill,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.56,
              x + size * 0.5,
              y + size * 0.72,
              x + size * 0.32,
              y + size * 0.72,
              x + size * 0.68,
              y + size * 0.72,
              stroke,
              width);
      case FACILITY -> archimateFacilityIconBody(x, y, size, fill, stroke, width);
      case EQUIPMENT -> archimateEquipmentIconBody(x, y, size, fill, stroke, width);
      case NODE -> archimateTechnologyNodeIconBody(x, y, size, fill, stroke, width);
      case MATERIAL -> archimateMaterialIconBody(x, y, size, fill, stroke, width);
      case NETWORK -> archimateNetworkIconBody(x, y, size, stroke, width);
      case DISTRIBUTION_NETWORK ->
          archimateDistributionNetworkIconBody(x, y, size, fill, stroke, width);
      case PATH -> archimatePathIconBody(x, y, size, stroke, width);
      case JUNCTION -> "";
    };
  }

  public static String archimateServiceIconBody(
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
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        serviceY,
        size,
        serviceHeight,
        size * 0.18,
        fill,
        stroke,
        width);
  }

  public static String archimateActorIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    double cx = x + size * 0.5;
    double headRx = size * 0.16;
    double headRy = size * 0.2;
    double headCy = y + headRy;
    double bodyTop = y + headRy * 2.0 + size * 0.08;
    double bodyBottom = y + size * 0.72;
    double armY = bodyTop + size * 0.12;
    return String.format(
        Locale.ROOT,
        "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>",
        cx,
        headCy,
        headRx,
        headRy,
        fill,
        stroke,
        width,
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
        y + size,
        stroke,
        width);
  }

  public static String archimateApplicationComponentIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    double tabWidth = size * 0.36;
    double tabHeight = size * 0.26;
    double bodyX = x + tabWidth / 2.0;
    double bodyWidth = size - tabWidth / 2.0;
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.5\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        bodyX,
        y,
        bodyWidth,
        size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.12,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width,
        x,
        y + size * 0.44,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width);
  }

  public static String archimateTargetIconBody(
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
    String rings =
        String.format(
            Locale.ROOT,
            "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
            centerX,
            centerY,
            outer,
            outer,
            fill,
            stroke,
            width,
            centerX,
            centerY,
            inner,
            inner,
            stroke,
            width,
            centerX,
            centerY,
            size * 0.05,
            size * 0.05,
            stroke,
            width);
    return switch (style) {
      case BULLSEYE -> rings;
      case ARROW ->
          rings
              + String.format(
                  Locale.ROOT,
                  "<path data-dediren-icon-part=\"target-arrow\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
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
                  y + size * 0.01,
                  stroke,
                  width);
      case HANDLE ->
          rings
              + String.format(
                  Locale.ROOT,
                  "<path data-dediren-icon-part=\"course-of-action-handle\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
                  centerX - size * 0.22,
                  centerY + size * 0.2,
                  x + size * 0.06,
                  y + size * 0.72,
                  stroke,
                  width);
    };
  }

  public static String archimateDocumentIconBody(
      double x, double y, double size, String fill, String stroke, String width, boolean folded) {
    if (folded) {
      return String.format(
          Locale.ROOT,
          "<path data-dediren-icon-part=\"document-fold\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"document-header\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
          y + size * 0.2,
          fill,
          stroke,
          width,
          x,
          y + size * 0.22,
          x + size * 0.68,
          y + size * 0.22,
          stroke,
          width);
    }
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"document-body\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"document-header\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        y,
        x + size * 0.92,
        y,
        x + size * 0.92,
        y + size * 0.72,
        x,
        y + size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.22,
        x + size * 0.92,
        y + size * 0.22,
        stroke,
        width);
  }

  public static String archimateContractIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"contract-document-body\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"contract-lines\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        y,
        x + size * 0.92,
        y,
        x + size * 0.92,
        y + size * 0.72,
        x,
        y + size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.24,
        x + size * 0.92,
        y + size * 0.24,
        x,
        y + size * 0.48,
        x + size * 0.92,
        y + size * 0.48,
        stroke,
        width);
  }

  public static String archimateCapabilityIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    StringBuilder body = new StringBuilder();
    double[][] steps = {
      {0.2, 0.52},
      {0.4, 0.52},
      {0.4, 0.32},
      {0.6, 0.52},
      {0.6, 0.32},
      {0.6, 0.12}
    };
    for (double[] step : steps) {
      body.append(
          String.format(
              Locale.ROOT,
              "<rect data-dediren-icon-part=\"capability-step\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * step[0],
              y + size * step[1],
              size * 0.2,
              size * 0.2,
              fill,
              stroke,
              width));
    }
    return body.toString();
  }

  public static String archimateWorkPackageIconBody(
      double x, double y, double size, String stroke, String width) {
    double loopX = x - size * 0.14;
    double loopY = y - size * 0.3;
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"work-package-loop-arrow\" d=\"M %.1f %.1f A %.1f %.1f 0 1 0 %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>",
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
        loopY + size * 0.82,
        stroke,
        width);
  }

  public static String archimateWavyDocumentIconBody(
      String part, double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
        attr(part),
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
        y,
        fill,
        stroke,
        width);
  }

  public static String archimateFoldedDocumentIconBody(
      String part, double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        attr(part),
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
        y + size * 0.28,
        fill,
        stroke,
        width);
  }

  public static String archimateFacilityIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"factory-silhouette\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
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
        y + size * 0.72,
        fill,
        stroke,
        width);
  }

  public static String archimateEquipmentIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return archimateGearPath(
            "equipment-gear-large",
            x + size * 0.34,
            y + size * 0.52,
            size * 0.24,
            fill,
            stroke,
            width)
        + String.format(
            Locale.ROOT,
            "<circle data-dediren-icon-part=\"equipment-gear-hole\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
            x + size * 0.34,
            y + size * 0.52,
            size * 0.06,
            stroke,
            width)
        + archimateGearPath(
            "equipment-gear-small",
            x + size * 0.68,
            y + size * 0.24,
            size * 0.16,
            fill,
            stroke,
            width)
        + String.format(
            Locale.ROOT,
            "<circle data-dediren-icon-part=\"equipment-gear-hole\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
            x + size * 0.68,
            y + size * 0.24,
            size * 0.04,
            stroke,
            width);
  }

  public static String archimateGearPath(
      String part, double cx, double cy, double radius, String fill, String stroke, String width) {
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
    return "<path data-dediren-icon-part=\""
        + attr(part)
        + "\" d=\""
        + d
        + " Z\" fill=\""
        + fill
        + "\" stroke=\""
        + stroke
        + "\" stroke-width=\""
        + width
        + "\"/>";
  }

  public static String archimateTechnologyNodeIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    double bodyY = y + size * 0.18;
    double depth = size * 0.18;
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.5\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"node-3d-edges\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
        x,
        bodyY,
        size - depth,
        size * 0.58,
        fill,
        stroke,
        width,
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
        bodyY + size * 0.58 - depth,
        stroke,
        width);
  }

  public static String archimateMaterialIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"material-hexagon\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"material-lines\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
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
        y + size * 0.36,
        fill,
        stroke,
        width,
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
        y + size * 0.52,
        stroke,
        width);
  }

  public static String archimateNetworkIconBody(
      double x, double y, double size, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x + size * 0.32,
        y + size * 0.22,
        stroke,
        x + size * 0.72,
        y + size * 0.22,
        stroke,
        x + size * 0.22,
        y + size * 0.58,
        stroke,
        x + size * 0.62,
        y + size * 0.58,
        stroke,
        x + size * 0.32,
        y + size * 0.22,
        x + size * 0.72,
        y + size * 0.22,
        x + size * 0.62,
        y + size * 0.58,
        x + size * 0.22,
        y + size * 0.58,
        stroke,
        width);
  }

  public static String archimateDistributionNetworkIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"distribution-network-arrows\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
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
        y + size * 0.5,
        fill,
        stroke,
        width);
  }

  public static String archimatePathIconBody(
      double x, double y, double size, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"path-line\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-dasharray=\"3 2\" stroke-linecap=\"round\"/><path data-dediren-icon-part=\"path-arrowheads\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
        x + size * 0.08,
        y + size * 0.36,
        x + size * 0.92,
        y + size * 0.36,
        stroke,
        width,
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
        y + size * 0.36,
        stroke,
        width);
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

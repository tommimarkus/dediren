package dev.dediren.plugins.render.node.uml;

import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.node.NodeShapeSupport.umlDecoratorSuppliesNodeLabel;
import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;
import static dev.dediren.plugins.render.svg.Svg.text;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

public final class UmlDecorators {

  private UmlDecorators() {}

  public static String umlNodeDecorator(
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    String name = decoratorName(decorator);
    String body = "";
    // Actor is in umlDecoratorSuppliesNodeLabel (so the generic plain label is
    // suppressed) but supplies its own label below the figure, not classifier
    // notation — so it is excluded from this classifier branch and handled below.
    if (umlDecoratorSuppliesNodeLabel(decorator) && decorator != SvgNodeDecorator.UML_ACTOR) {
      body = umlClassifierNotation(node, style, decorator, selector);
    } else if (decorator == SvgNodeDecorator.UML_PACKAGE) {
      body =
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + 8.0,
              node.y() + 16.0,
              attr(style.labelFill()),
              text(node.label()));
    } else if (decorator == SvgNodeDecorator.UML_ACTOR) {
      body =
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + node.width() / 2.0,
              node.y() + node.height() - 8.0,
              attr(style.labelFill()),
              text(node.label()));
    } else if (decorator == SvgNodeDecorator.UML_COMPONENT) {
      body = umlComponentGlyph(node, style);
    } else if (decorator == SvgNodeDecorator.UML_DEVICE
        || decorator == SvgNodeDecorator.UML_EXECUTION_ENVIRONMENT
        || decorator == SvgNodeDecorator.UML_DEPLOYMENT_SPECIFICATION) {
      body = umlStereotypeLabel(node, style, decorator);
    }
    return "<g data-dediren-node-decorator=\"" + attr(name) + "\">" + body + "</g>";
  }

  public static String umlStereotypeLabel(
      LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
    String stereotype =
        switch (decorator) {
          case UML_DEVICE -> "&#171;device&#187;";
          case UML_EXECUTION_ENVIRONMENT -> "&#171;executionEnvironment&#187;";
          case UML_DEPLOYMENT_SPECIFICATION -> "&#171;deployment spec&#187;";
          default -> "";
        };
    return String.format(
        Locale.ROOT,
        "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"11\">%s</text>",
        node.x() + node.width() / 2.0,
        node.y() + 17.0,
        attr(style.labelFill()),
        stereotype);
  }

  public static String umlComponentGlyph(LaidOutNode node, ResolvedNodeStyle style) {
    double glyphWidth = Math.min(28.0, Math.max(18.0, node.width() * 0.18));
    double glyphHeight = Math.min(24.0, Math.max(16.0, node.height() * 0.22));
    double x = node.x() + node.width() - glyphWidth - 10.0;
    double y = node.y() + 10.0;
    double tabWidth = glyphWidth * 0.32;
    double tabHeight = glyphHeight * 0.28;
    String fill = attr(style.fill());
    String stroke = attr(style.stroke());
    String width = styleNumber(style.strokeWidth());
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        y,
        glyphWidth,
        glyphHeight,
        fill,
        stroke,
        width,
        x - tabWidth * 0.45,
        y + glyphHeight * 0.22,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width,
        x - tabWidth * 0.45,
        y + glyphHeight * 0.58,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width);
  }

  public static String umlClassifierNotation(
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    JsonNode properties = selector == null ? null : selector.properties();
    List<String> titleLines = new ArrayList<>();
    if (decorator == SvgNodeDecorator.UML_ENUMERATION) {
      titleLines.add("&#171;enumeration&#187;");
    } else if (decorator == SvgNodeDecorator.UML_INTERFACE) {
      titleLines.add("&#171;interface&#187;");
    } else if (decorator == SvgNodeDecorator.UML_DATA_TYPE) {
      titleLines.add("&#171;dataType&#187;");
    }
    titleLines.add(text(node.label()));

    List<String> attributeLines =
        decorator == SvgNodeDecorator.UML_ENUMERATION
            ? umlLiteralLines(properties)
            : umlAttributeLines(properties);
    List<String> operationLines =
        decorator == SvgNodeDecorator.UML_ENUMERATION ? List.of() : umlOperationLines(properties);
    double titleHeight = Math.max(28.0, titleLines.size() * 15.0 + 8.0);
    double attributeHeight = attributeLines.isEmpty() ? 0.0 : attributeLines.size() * 14.0 + 8.0;
    double firstSeparatorY = node.y() + titleHeight;
    double secondSeparatorY = firstSeparatorY + attributeHeight;

    StringBuilder svg = new StringBuilder();
    if (!attributeLines.isEmpty() || !operationLines.isEmpty()) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
              node.x(),
              firstSeparatorY,
              node.x() + node.width(),
              firstSeparatorY,
              attr(style.stroke()),
              styleNumber(style.strokeWidth())));
    }
    if (!operationLines.isEmpty()) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
              node.x(),
              secondSeparatorY,
              node.x() + node.width(),
              secondSeparatorY,
              attr(style.stroke()),
              styleNumber(style.strokeWidth())));
    }
    double y = node.y() + 15.0;
    for (String line : titleLines) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + node.width() / 2.0,
              y,
              attr(style.labelFill()),
              line));
      y += 15.0;
    }
    y = firstSeparatorY + 15.0;
    for (String line : attributeLines) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + 8.0,
              y,
              attr(style.labelFill()),
              text(line)));
      y += 14.0;
    }
    y = secondSeparatorY + 15.0;
    for (String line : operationLines) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + 8.0,
              y,
              attr(style.labelFill()),
              text(line)));
      y += 14.0;
    }
    return svg.toString();
  }

  public static List<String> umlAttributeLines(JsonNode properties) {
    JsonNode attributes = properties == null ? null : properties.get("attributes");
    if (attributes == null || !attributes.isArray()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode attribute : attributes) {
      String visibility = umlVisibilitySymbol(textField(attribute, "visibility", "public"));
      String name = textField(attribute, "name", "");
      String type = textField(attribute, "type", "");
      lines.add(type.isEmpty() ? visibility + " " + name : visibility + " " + name + " : " + type);
    }
    return lines;
  }

  public static List<String> umlOperationLines(JsonNode properties) {
    JsonNode operations = properties == null ? null : properties.get("operations");
    if (operations == null || !operations.isArray()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode operation : operations) {
      String visibility = umlVisibilitySymbol(textField(operation, "visibility", "public"));
      String name = textField(operation, "name", "");
      List<String> parameters = new ArrayList<>();
      JsonNode parameterValues = operation.get("parameters");
      if (parameterValues != null && parameterValues.isArray()) {
        for (JsonNode parameter : parameterValues) {
          String parameterName = textField(parameter, "name", "");
          String parameterType = textField(parameter, "type", "");
          if (parameterType.isEmpty()) {
            parameters.add(parameterName);
          } else if (parameterName.isEmpty()) {
            parameters.add(parameterType);
          } else {
            parameters.add(parameterName + " : " + parameterType);
          }
        }
      }
      String returnType = textField(operation, "return_type", "");
      String signature = visibility + " " + name + "(" + String.join(", ", parameters) + ")";
      lines.add(returnType.isEmpty() ? signature : signature + " : " + returnType);
    }
    return lines;
  }

  public static List<String> umlLiteralLines(JsonNode properties) {
    JsonNode literals = properties == null ? null : properties.get("literals");
    if (literals == null || !literals.isArray()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode literal : literals) {
      if (literal.isTextual()) {
        lines.add(literal.asText());
      }
    }
    return lines;
  }

  public static String umlVisibilitySymbol(String visibility) {
    return switch (visibility) {
      case "private" -> "-";
      case "protected" -> "#";
      case "package" -> "~";
      default -> "+";
    };
  }

  public static String textField(JsonNode value, String field, String fallback) {
    JsonNode fieldValue = value == null ? null : value.get(field);
    return fieldValue != null && fieldValue.isTextual() ? fieldValue.asText() : fallback;
  }
}

package dev.dediren.plugins.render.node.uml;

import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.node.NodeShapeSupport.umlDecoratorSuppliesNodeLabel;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;
import static dev.dediren.plugins.render.svg.Svg.text;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

public final class UmlDecorators {

  private UmlDecorators() {}

  public static void umlNodeDecorator(
      SvgWriter w,
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    w.start("g").attr("data-dediren-node-decorator", decoratorName(decorator));
    // Actor is in umlDecoratorSuppliesNodeLabel (so the generic plain label is
    // suppressed) but supplies its own label below the figure, not classifier
    // notation — so it is excluded from this classifier branch and handled below.
    if (umlDecoratorSuppliesNodeLabel(decorator) && decorator != SvgNodeDecorator.UML_ACTOR) {
      umlClassifierNotation(w, node, style, decorator, selector);
    } else if (decorator == SvgNodeDecorator.UML_PACKAGE) {
      w.start("text")
          .attr("x", f1(node.x() + 8.0))
          .attr("y", f1(node.y() + 16.0))
          .attr("fill", style.labelFill())
          .attr("font-size", "12")
          .text(node.label())
          .end();
    } else if (decorator == SvgNodeDecorator.UML_ACTOR) {
      w.start("text")
          .attr("x", f1(node.x() + node.width() / 2.0))
          .attr("y", f1(node.y() + node.height() - 8.0))
          .attr("text-anchor", "middle")
          .attr("fill", style.labelFill())
          .attr("font-size", "12")
          .text(node.label())
          .end();
    } else if (decorator == SvgNodeDecorator.UML_COMPONENT) {
      umlComponentGlyph(w, node, style);
    } else if (decorator == SvgNodeDecorator.UML_DEVICE
        || decorator == SvgNodeDecorator.UML_EXECUTION_ENVIRONMENT
        || decorator == SvgNodeDecorator.UML_DEPLOYMENT_SPECIFICATION) {
      umlStereotypeLabel(w, node, style, decorator);
    }
    w.end();
  }

  public static void umlStereotypeLabel(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
    String stereotype =
        switch (decorator) {
          case UML_DEVICE -> "&#171;device&#187;";
          case UML_EXECUTION_ENVIRONMENT -> "&#171;executionEnvironment&#187;";
          case UML_DEPLOYMENT_SPECIFICATION -> "&#171;deployment spec&#187;";
          default -> "";
        };
    w.start("text")
        .attr("x", f1(node.x() + node.width() / 2.0))
        .attr("y", f1(node.y() + 17.0))
        .attr("text-anchor", "middle")
        .attr("fill", style.labelFill())
        .attr("font-size", "11")
        .raw(stereotype)
        .end();
  }

  public static void umlComponentGlyph(SvgWriter w, LaidOutNode node, ResolvedNodeStyle style) {
    double glyphWidth = Math.min(28.0, Math.max(18.0, node.width() * 0.18));
    double glyphHeight = Math.min(24.0, Math.max(16.0, node.height() * 0.22));
    double x = node.x() + node.width() - glyphWidth - 10.0;
    double y = node.y() + 10.0;
    double tabWidth = glyphWidth * 0.32;
    double tabHeight = glyphHeight * 0.28;
    componentRect(w, x, y, glyphWidth, glyphHeight, style);
    componentRect(w, x - tabWidth * 0.45, y + glyphHeight * 0.22, tabWidth, tabHeight, style);
    componentRect(w, x - tabWidth * 0.45, y + glyphHeight * 0.58, tabWidth, tabHeight, style);
  }

  private static void componentRect(
      SvgWriter w, double x, double y, double width, double height, ResolvedNodeStyle style) {
    w.empty("rect")
        .attr("x", f1(x))
        .attr("y", f1(y))
        .attr("width", f1(width))
        .attr("height", f1(height))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  public static void umlClassifierNotation(
      SvgWriter w,
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

    if (!attributeLines.isEmpty() || !operationLines.isEmpty()) {
      separatorLine(w, node, firstSeparatorY, style);
    }
    if (!operationLines.isEmpty()) {
      separatorLine(w, node, secondSeparatorY, style);
    }
    double y = node.y() + 15.0;
    for (String line : titleLines) {
      w.start("text")
          .attr("x", f1(node.x() + node.width() / 2.0))
          .attr("y", f1(y))
          .attr("text-anchor", "middle")
          .attr("fill", style.labelFill())
          .attr("font-size", "12")
          .raw(line)
          .end();
      y += 15.0;
    }
    y = firstSeparatorY + 15.0;
    for (String line : attributeLines) {
      w.start("text")
          .attr("x", f1(node.x() + 8.0))
          .attr("y", f1(y))
          .attr("fill", style.labelFill())
          .attr("font-size", "12")
          .text(line)
          .end();
      y += 14.0;
    }
    y = secondSeparatorY + 15.0;
    for (String line : operationLines) {
      w.start("text")
          .attr("x", f1(node.x() + 8.0))
          .attr("y", f1(y))
          .attr("fill", style.labelFill())
          .attr("font-size", "12")
          .text(line)
          .end();
      y += 14.0;
    }
  }

  private static void separatorLine(
      SvgWriter w, LaidOutNode node, double lineY, ResolvedNodeStyle style) {
    w.empty("line")
        .attr("x1", f1(node.x()))
        .attr("y1", f1(lineY))
        .attr("x2", f1(node.x() + node.width()))
        .attr("y2", f1(lineY))
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
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

  private static String f1(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }
}

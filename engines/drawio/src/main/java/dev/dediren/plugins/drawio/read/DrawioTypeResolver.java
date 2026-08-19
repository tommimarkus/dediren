package dev.dediren.plugins.drawio.read;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recognizes a draw.io ArchiMate® stencil and, where the stencil says so unambiguously, names the
 * Dediren element type it looks like.
 *
 * <p><strong>This is a hint, never a resolution.</strong> Import always emits {@code
 * semantic_profile: generic-graph} with {@code generic.node}/{@code generic.link} types; what this
 * class produces is recorded under {@code properties.drawio.stencil} and summarized in one {@code
 * DEDIREN_DRAWIO_KIND_INFERRED} info diagnostic so a human can promote the model by hand after
 * reviewing it. Nothing here ever changes a type or a profile.
 *
 * <p><strong>Why the table is deliberately partial, and not the reverse of {@code
 * DrawioShapes}.</strong> A draw.io style string is not a unique key for an ArchiMate type: the
 * layer rides only in {@code fillColor}, so twenty-three of the element types {@code DrawioShapes}
 * exports collapse into eight
 * shared style strings. The eight collapsed {@code appType} tokens — {@code event}, {@code
 * interface}, {@code collab}, {@code proc}, {@code serv}, {@code func}, {@code interaction}, {@code
 * passive} — are therefore absent below and get no suggestion at all, because any suggestion for
 * them would be a coin flip between layers. Only {@code role} is disambiguated, and only because
 * its two types differ in {@code archiType} rather than in colour.
 *
 * <p>Being partial also means this table may drift from {@code DrawioShapes} without anything
 * breaking: a stencil missing here is simply recorded with no suggestion, which is the same
 * outcome an ambiguous one gets. That is the point of keeping it separate rather than inverting the
 * export table — an inverted index would silently claim a resolution it cannot make.
 */
final class DrawioTypeResolver {

  /** The draw.io ArchiMate 3 shape-library prefix; the only stencil family recognized here. */
  private static final String ARCHIMATE_PREFIX = "mxgraph.archimate3.";

  /** The generic ArchiMate 3 box, whose specific element rides in {@code appType}. */
  private static final String ARCHIMATE_BOX = ARCHIMATE_PREFIX + "application";

  private static final Map<String, String> UNAMBIGUOUS = buildTable();

  private DrawioTypeResolver() {}

  /**
   * One recognized stencil: the token that identified it, and the Dediren type it suggests — null
   * when the token cannot name one type without guessing.
   */
  record Stencil(String token, String suggestedType) {}

  /** The stencil this parsed style declares, or null when it declares none this class knows. */
  static Stencil recognize(Map<String, String> style) {
    String shape = style.get("shape");
    if (shape == null || !shape.startsWith(ARCHIMATE_PREFIX)) {
      return null;
    }
    if (!ARCHIMATE_BOX.equals(shape)) {
      // The direct form: mxgraph.archimate3.goal, .capability, .device, …
      return new Stencil(shape, suggestionFor(shape.substring(ARCHIMATE_PREFIX.length()), null));
    }
    String appType = style.get("appType");
    String archiType = style.get("archiType");
    if (appType == null) {
      return new Stencil(shape, null);
    }
    StringBuilder token = new StringBuilder(shape).append(";appType=").append(appType);
    if (archiType != null) {
      token.append(";archiType=").append(archiType);
    }
    return new Stencil(token.toString(), suggestionFor(appType, archiType));
  }

  private static String suggestionFor(String token, String archiType) {
    if ("role".equals(token)) {
      // The one collision the shape string itself settles: an octagonal role is a Stakeholder, a
      // square one is a BusinessRole. Without an archiType neither can be preferred.
      if ("oct".equals(archiType)) {
        return "Stakeholder";
      }
      return "square".equals(archiType) ? "BusinessRole" : null;
    }
    return UNAMBIGUOUS.get(token);
  }

  /**
   * Stencil token → the one Dediren element type it can only be. Grouped by ArchiMate layer in the
   * order {@code Archimate.ELEMENT_TYPES} uses, so a reader comparing the two files reads them the
   * same way round.
   */
  private static Map<String, String> buildTable() {
    var table = new LinkedHashMap<String, String>();

    // Implementation & migration.
    table.put("plateau", "Plateau");
    table.put("workPackage", "WorkPackage");
    table.put("deliverable", "Deliverable");
    table.put("gap", "Gap");

    // Other.
    table.put("grouping", "Grouping");
    table.put("location", "Location");

    // Motivation.
    table.put("driver", "Driver");
    table.put("assess", "Assessment");
    table.put("goal", "Goal");
    table.put("outcome", "Outcome");
    table.put("amValue", "Value");
    table.put("meaning", "Meaning");
    table.put("constraint", "Constraint");
    table.put("requirement", "Requirement");
    table.put("principle", "Principle");

    // Strategy.
    table.put("course", "CourseOfAction");
    table.put("resource", "Resource");
    table.put("valueStream", "ValueStream");
    table.put("capability", "Capability");

    // Business.
    table.put("actor", "BusinessActor");
    table.put("product", "Product");
    table.put("contract", "Contract");
    table.put("representation", "Representation");

    // Application.
    table.put("comp", "ApplicationComponent");

    // Technology & physical.
    table.put("sysSw", "SystemSoftware");
    table.put("node", "Node");
    table.put("device", "Device");
    table.put("facility", "Facility");
    table.put("equipment", "Equipment");
    table.put("path", "Path");
    table.put("artifact", "Artifact");
    table.put("material", "Material");
    table.put("netw", "CommunicationNetwork");
    table.put("distribution", "DistributionNetwork");

    return Collections.unmodifiableMap(table);
  }
}

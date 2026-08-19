package dev.dediren.plugins.drawio.style;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ArchiMate element type name (dediren's own vocabulary, {@code Archimate.elementTypes()}) →
 * draw.io {@code mxgraph.archimate3.application} shape.
 *
 * <p><strong>Ordering rule.</strong> The table below is built as a {@link LinkedHashMap} in {@code
 * Archimate.ELEMENT_TYPES} declaration order, and that order is load-bearing, not cosmetic:
 * {@code dist-tool}'s {@code DrawioShapeCoverageTest} reads {@code Archimate.java} as text and
 * asserts this table's key sequence matches it exactly, so the table is never reordered to match
 * draw.io's own shape-library layout or any other third-party palette. It is keyed and sequenced
 * by dediren's vocabulary alone.
 *
 * <p><strong>No reverse (style → type) index.</strong> The style string is not a unique key: layer
 * is carried only by fill colour (owned by {@link DrawioPalette}, not this table), so twenty-three
 * element types collapse into eight identical style strings here — all four {@code *Event} types
 * share one, and {@code interface}/{@code collab}/{@code proc}/{@code func}/{@code interaction}/
 * {@code serv} each repeat across the business, application, and technology layers, as do the two
 * passive-structure types ({@code BusinessObject}, {@code DataObject}). Building a style → type map
 * would silently mis-resolve on import. Import instead resolves the element type from the
 * {@code dedirenType} cell attribute, never from the style string.
 */
public final class DrawioShapes {

  /**
   * Every {@code mxgraph.archimate3.application} cell in draw.io carries this prefix ahead of
   * {@code shape=}/{@code appType=}/{@code archiType=}.
   */
  private static final String PREFIX = "html=1;outlineConnect=0;whiteSpace=wrap;";

  /** Default boxed size for every ArchiMate element except the 10×10 junction ellipses. */
  private static final int DEFAULT_WIDTH = 150;

  private static final int DEFAULT_HEIGHT = 75;

  /** Returned for an element type absent from the table below — never a hard failure. */
  private static final DrawioShape FALLBACK =
      new DrawioShape("rounded=1;whiteSpace=wrap;html=1;", DEFAULT_WIDTH, DEFAULT_HEIGHT);

  private static final Map<String, DrawioShape> TABLE = buildTable();

  private DrawioShapes() {}

  /** The draw.io shape for {@code elementType}, or {@link #FALLBACK} if it is not in the table. */
  public static DrawioShape shapeFor(String elementType) {
    return TABLE.getOrDefault(elementType, FALLBACK);
  }

  /**
   * Whether the table covers {@code elementType} at all.
   *
   * <p>Separate from {@link #shapeFor} because the fallback is a real, usable shape and not an
   * error signal: the exporter still draws the element, and only needs to know whether to say so.
   * Comparing a returned shape against the fallback would work today and stop working the moment a
   * table entry happened to be a plain rounded box.
   */
  public static boolean isMapped(String elementType) {
    return TABLE.containsKey(elementType);
  }

  private static Map<String, DrawioShape> buildTable() {
    var table = new LinkedHashMap<String, DrawioShape>();

    table.put("Plateau", boxed("plateau", null));
    table.put("WorkPackage", boxed("workPackage", "rounded"));
    table.put("Deliverable", boxed("deliverable", null));
    table.put("ImplementationEvent", boxed("event", "rounded"));
    table.put("Gap", boxed("gap", null));
    table.put("AndJunction", new DrawioShape(junction("fillColor=strokeColor"), 10, 10));
    table.put("OrJunction", new DrawioShape(junction("fillColor=#ffffff"), 10, 10));
    table.put("Grouping", boxed("grouping", "square", "dashed=1;fillColor=none;"));
    table.put("Location", boxed("location", "square"));
    table.put("Stakeholder", boxed("role", "oct"));
    table.put("Driver", boxed("driver", "oct"));
    table.put("Assessment", boxed("assess", "oct"));
    table.put("Goal", boxed("goal", "oct"));
    table.put("Outcome", boxed("outcome", "oct"));
    table.put("Value", boxed("amValue", "oct"));
    table.put("Meaning", boxed("meaning", "oct"));
    table.put("Constraint", boxed("constraint", "oct"));
    table.put("Requirement", boxed("requirement", "oct"));
    table.put("Principle", boxed("principle", "oct"));
    table.put("CourseOfAction", boxed("course", "rounded"));
    table.put("Resource", boxed("resource", "square"));
    table.put("ValueStream", boxed("valueStream", "rounded"));
    table.put("Capability", boxed("capability", "rounded"));
    table.put("BusinessInterface", boxed("interface", "square"));
    table.put("BusinessCollaboration", boxed("collab", "square"));
    table.put("BusinessActor", boxed("actor", "square"));
    table.put("BusinessRole", boxed("role", "square"));
    table.put("BusinessProcess", boxed("proc", "rounded"));
    table.put("BusinessService", boxed("serv", "rounded"));
    table.put("BusinessInteraction", boxed("interaction", "rounded"));
    table.put("BusinessFunction", boxed("func", "rounded"));
    table.put("BusinessEvent", boxed("event", "rounded"));
    table.put("Product", boxed("product", "square"));
    table.put("BusinessObject", boxed("passive", "square"));
    table.put("Contract", boxed("contract", "square"));
    table.put("Representation", boxed("representation", "square"));
    table.put("ApplicationInterface", boxed("interface", "square"));
    table.put("ApplicationCollaboration", boxed("collab", "square"));
    table.put("ApplicationComponent", boxed("comp", "square"));
    table.put("ApplicationService", boxed("serv", "rounded"));
    table.put("ApplicationInteraction", boxed("interaction", "rounded"));
    table.put("ApplicationFunction", boxed("func", "rounded"));
    table.put("ApplicationProcess", boxed("proc", "rounded"));
    table.put("ApplicationEvent", boxed("event", "rounded"));
    table.put("DataObject", boxed("passive", "square"));
    table.put("TechnologyInterface", boxed("interface", "square"));
    table.put("TechnologyCollaboration", boxed("collab", "square"));
    table.put("Node", boxed("node", "square"));
    table.put("SystemSoftware", boxed("sysSw", "square"));
    table.put("Device", boxed("device", null));
    table.put("Facility", boxed("facility", "square"));
    table.put("Equipment", boxed("equipment", "square"));
    table.put("Path", boxed("path", "square"));
    table.put("TechnologyService", boxed("serv", "rounded"));
    table.put("TechnologyInteraction", boxed("interaction", "rounded"));
    table.put("TechnologyFunction", boxed("func", "rounded"));
    table.put("TechnologyProcess", boxed("proc", "rounded"));
    table.put("TechnologyEvent", boxed("event", "rounded"));
    table.put("Artifact", boxed("artifact", "square"));
    table.put("Material", boxed("material", "square"));
    table.put("CommunicationNetwork", boxed("netw", "square"));
    table.put("DistributionNetwork", boxed("distribution", "square"));

    return Collections.unmodifiableMap(table);
  }

  /**
   * The {@code mxgraph.archimate3.application} style shared by every non-junction ArchiMate
   * element, at the default 150×75 box.
   */
  private static DrawioShape boxed(String appType, String archiType) {
    return boxed(appType, archiType, "");
  }

  private static DrawioShape boxed(String appType, String archiType, String extra) {
    var style = new StringBuilder(PREFIX);
    style.append("shape=mxgraph.archimate3.application;appType=").append(appType).append(";");
    if (archiType != null) {
      style.append("archiType=").append(archiType).append(";");
    }
    style.append(extra);
    return new DrawioShape(style.toString(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
  }

  /**
   * The two junction types are not {@code archimate3} shapes at all — a plain 10×10 ellipse
   * distinguished from its sibling only by {@code fillFragment}.
   */
  private static String junction(String fillFragment) {
    return "ellipse;html=1;verticalLabelPosition=bottom;labelBackgroundColor=#ffffff;"
        + "verticalAlign=top;"
        + fillFragment
        + ";";
  }
}

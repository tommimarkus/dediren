package dev.dediren.plugins.drawio.style;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ArchiMate element type name → fill/stroke/label colours, sourced from {@code
 * fixtures/render-policy/archimate-svg.json}'s {@code style.node_type_overrides} — the same
 * per-layer palette dediren's SVG renderer already uses, not draw.io's own shape-library fill.
 * Using our palette instead of draw.io's keeps a `.drawio` export visually consistent with the SVG
 * dediren renders for the same model, and sidesteps redistributing any third-party colour table.
 *
 * <p>Built in {@code Archimate.ELEMENT_TYPES} declaration order for the same auditability reason as
 * {@link DrawioShapes}; {@code dist-tool}'s {@code DrawioShapeCoverageTest} cross-checks this
 * table's keys against the shipped render policy's {@code node_type_overrides} map as text, so the
 * two colour tables cannot silently drift apart.
 *
 * <p>Two element types keep a colour that is semantic rather than palette-driven and must not be
 * looked up here: the two junctions (fill is the only thing distinguishing {@code AndJunction} from
 * {@code OrJunction}) and {@code Grouping} (always unfilled, {@code fillColor=none}). Both are
 * encoded directly in {@link DrawioShapes}, not through this palette.
 */
public final class DrawioPalette {

  /** One element type's resolved colours, mirroring the render-policy override fields. */
  public record Colors(String fill, String stroke, String labelFill) {}

  /** Returned for an element type absent from the table below — never a hard failure. */
  private static final Colors FALLBACK = new Colors("#f5f5f5", "#666666", "#333333");

  private static final Map<String, Colors> TABLE = buildTable();

  private DrawioPalette() {}

  /** The colours for {@code elementType}, or {@link #FALLBACK} if it is not in the table. */
  public static Colors colorsFor(String elementType) {
    return TABLE.getOrDefault(elementType, FALLBACK);
  }

  private static Map<String, Colors> buildTable() {
    var table = new LinkedHashMap<String, Colors>();

    var motivation = new Colors("#d9d2e9", "#674ea7", "#351c75");
    var strategy = new Colors("#ffe6cc", "#d79b00", "#7f6000");
    var business = new Colors("#fff2cc", "#d6b656", "#3f3000");
    var application = new Colors("#e0f2fe", "#0369a1", "#0c4a6e");
    var technology = new Colors("#d5e8d4", "#4d7c0f", "#365314");
    var otherLayer = new Colors("#f8cecc", "#b85450", "#5f1f1b");

    table.put("Plateau", otherLayer);
    table.put("WorkPackage", otherLayer);
    table.put("Deliverable", otherLayer);
    table.put("ImplementationEvent", otherLayer);
    table.put("Gap", otherLayer);
    table.put("AndJunction", new Colors("#ffffff", "#111827", "#111827"));
    table.put("OrJunction", new Colors("#ffffff", "#111827", "#111827"));
    table.put("Grouping", new Colors("#ffffff", "#64748b", "#334155"));
    table.put("Location", new Colors("#ead1dc", "#a64d79", "#741b47"));
    table.put("Stakeholder", motivation);
    table.put("Driver", motivation);
    table.put("Assessment", motivation);
    table.put("Goal", motivation);
    table.put("Outcome", motivation);
    table.put("Value", motivation);
    table.put("Meaning", motivation);
    table.put("Constraint", motivation);
    table.put("Requirement", motivation);
    table.put("Principle", motivation);
    table.put("CourseOfAction", strategy);
    table.put("Resource", strategy);
    table.put("ValueStream", strategy);
    table.put("Capability", strategy);
    table.put("BusinessInterface", business);
    table.put("BusinessCollaboration", business);
    table.put("BusinessActor", business);
    table.put("BusinessRole", business);
    table.put("BusinessProcess", business);
    table.put("BusinessService", business);
    table.put("BusinessInteraction", business);
    table.put("BusinessFunction", business);
    table.put("BusinessEvent", business);
    table.put("Product", business);
    table.put("BusinessObject", business);
    table.put("Contract", business);
    table.put("Representation", business);
    table.put("ApplicationInterface", application);
    table.put("ApplicationCollaboration", application);
    table.put("ApplicationComponent", application);
    table.put("ApplicationService", application);
    table.put("ApplicationInteraction", application);
    table.put("ApplicationFunction", application);
    table.put("ApplicationProcess", application);
    table.put("ApplicationEvent", application);
    table.put("DataObject", application);
    table.put("TechnologyInterface", technology);
    table.put("TechnologyCollaboration", technology);
    table.put("Node", technology);
    table.put("SystemSoftware", technology);
    table.put("Device", technology);
    table.put("Facility", technology);
    table.put("Equipment", technology);
    table.put("Path", technology);
    table.put("TechnologyService", technology);
    table.put("TechnologyInteraction", technology);
    table.put("TechnologyFunction", technology);
    table.put("TechnologyProcess", technology);
    table.put("TechnologyEvent", technology);
    table.put("Artifact", technology);
    table.put("Material", technology);
    table.put("CommunicationNetwork", technology);
    table.put("DistributionNetwork", technology);

    return Collections.unmodifiableMap(table);
  }
}

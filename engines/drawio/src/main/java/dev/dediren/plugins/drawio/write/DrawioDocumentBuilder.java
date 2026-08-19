package dev.dediren.plugins.drawio.write;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.DrawioExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutGroups;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutNodeRole;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.XmlIds;
import dev.dediren.plugins.drawio.mx.MxCell;
import dev.dediren.plugins.drawio.mx.MxDiagram;
import dev.dediren.plugins.drawio.mx.MxFile;
import dev.dediren.plugins.drawio.mx.MxGeometry;
import dev.dediren.plugins.drawio.mx.MxObject;
import dev.dediren.plugins.drawio.mx.MxPoint;
import dev.dediren.plugins.drawio.style.DrawioEdgeStyles;
import dev.dediren.plugins.drawio.style.DrawioEdgeStyles.Notation;
import dev.dediren.plugins.drawio.style.DrawioPalette;
import dev.dediren.plugins.drawio.style.DrawioShapes;
import dev.dediren.plugins.drawio.style.DrawioUmlShapes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Maps a source model and one laid-out view onto an {@link MxFile}.
 *
 * <h2>Geometry is taken, never computed</h2>
 *
 * <p>Every coordinate comes straight from the {@link LayoutResult}: there is no second layout pass
 * and no invented geometry anywhere below. The one arithmetic operation performed on a coordinate
 * is the container rebase described next, and it exists only because the two formats disagree about
 * what a child's coordinates are measured from.
 *
 * <p><strong>The layout result's coordinates are absolute; mxGraph's child coordinates are
 * relative.</strong> A laid-out node inside a group carries the same absolute origin as one at the
 * page root, but mxGraph reads a child cell's geometry against its parent's origin. Re-parenting a
 * member into a container therefore has to subtract the container's absolute origin, and groups
 * nest — a group's member list can name another group — so the subtraction uses each element's
 * immediate parent. Skipping it draws every grouped element displaced by exactly the container
 * origin, which is the single easiest thing here to get wrong.
 *
 * <p><strong>Edges always ride the layer, never a container.</strong> mxGraph interprets an edge's
 * waypoints relative to the edge's own parent, and the layout result's route points are absolute;
 * parenting an edge into a group would silently displace its whole route even though its endpoints
 * are correct.
 *
 * <h2>Types come from the source, not the layout</h2>
 *
 * <p>A {@link LaidOutNode} carries no type — only id, source id, label, and role — so the source
 * document is indexed by id and the type read from {@link SourceNode}/{@link SourceRelationship}
 * there, exactly as the OEF exporter does.
 *
 * <h2>It discloses rather than fails</h2>
 *
 * <p>Nothing here throws. An element type no draw.io shape covers, a layout reference that resolves
 * to nothing, and UML behaviour ornamentation this export does not draw are all reported as
 * diagnostics on an artifact that still opens. That is a deliberate difference from the OEF
 * exporter, whose equivalent reference check is a hard failure: a {@code .drawio} is an editable
 * picture, and a picture missing one box is more useful than no picture.
 *
 * <h2>Which notation a cell is drawn in</h2>
 *
 * <p>{@code Node}, {@code Device} and {@code Artifact} are declared by both the ArchiMate and the
 * UML vocabularies, so a type name alone does not identify a shape. The view's declared kind picks
 * the primary table — the eight {@code uml-*} kinds read UML, everything else reads ArchiMate — and
 * a type the primary table does not cover is looked up in the other one before falling back. That
 * ordering is what makes the three shared names resolve by declared kind rather than by table
 * order, while a view kind of {@code generic} still draws whatever it actually contains.
 *
 * <h2>What this export does not carry</h2>
 *
 * <p>Compartment content: a Class exports as its box, without the attribute and operation
 * compartments its source properties describe. Stereotype keywords likewise — the cell label is the
 * element's own. Both survive the round trip regardless, on {@link DrawioIdentity#TYPE} and in the
 * source model.
 */
public final class DrawioDocumentBuilder {

  /** The built document and everything the build has to disclose about it. */
  public record Document(MxFile file, List<Diagnostic> diagnostics) {}

  /** mxGraph's two structural cells: every page opens with a root and a default layer. */
  private static final String ROOT_CELL_ID = "0";

  private static final String LAYER_CELL_ID = "1";

  /** The mxCell id of the hidden metadata cell, claimed before any element can take it. */
  private static final String METADATA_CELL_ID = "dediren-view";

  /** The one model property path this export carries, spelled once so writer and disclosure agree. */
  private static final String UML_SEQUENCE_PATH = "uml.sequence";

  /**
   * Inert and unlabelled: the metadata cell is hidden, and if a curious user ever un-hides it, it
   * should read as a marker rather than a mystery box.
   */
  private static final String METADATA_STYLE =
      "text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=top;";

  /**
   * A container is drawn as an outline with its label at the top, so the members re-parented into
   * it stay legible.
   */
  private static final String GROUP_STYLE =
      "rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;verticalAlign=top;";

  /**
   * The three element types whose colour is semantic rather than palette-driven, per {@code
   * DrawioPalette}'s own contract: fill is the only thing distinguishing the two junctions, and a
   * Grouping is always unfilled. Both are already encoded in the shape table, and appending the
   * palette's fill would win mxGraph's last-key-wins style parse and destroy them.
   */
  private static final Set<String> PALETTE_EXEMPT_TYPES =
      Set.of("AndJunction", "OrJunction", "Grouping");

  /**
   * The UML behaviour roles this export places and labels but does not ornament.
   *
   * <p>{@link LayoutNodeRole#INTERACTION} is deliberately absent. It marks the interaction frame,
   * which this export now draws with draw.io's own {@code umlFrame} shape — and the entry it used
   * to carry named combined-fragment frames, which is a different thing altogether. Since a
   * sequence view always has an interaction frame, that entry fired on every sequence export
   * including the ones with no combined fragment anywhere in the model.
   */
  private static final Map<String, String> OMITTED_ORNAMENTS =
      Map.of(
          LayoutNodeRole.LIFELINE, "lifeline tails (dashed lifeline lines)",
          LayoutNodeRole.EXECUTION, "execution occurrences (activation bars)",
          LayoutNodeRole.DESTRUCTION, "destruction occurrences (the lifeline cross)");

  private final SourceDocument source;
  private final LayoutResult layout;
  private final DrawioExportPolicy policy;

  private final List<Diagnostic> diagnostics = new ArrayList<>();
  private final List<MxCell> cells = new ArrayList<>();

  /** Every mxCell id claimed so far, so {@link XmlIds#unique} cannot hand out a duplicate. */
  private final Set<String> claimedCellIds = new HashSet<>();

  /** dediren id → mxCell id, for the endpoint and containment lookups below. */
  private final Map<String, String> cellIdByDedirenId = new LinkedHashMap<>();

  /** dediren id of a group member → dediren id of the group claiming it. */
  private final Map<String, String> containerOf = new LinkedHashMap<>();

  private final Map<String, LaidOutGroup> groupsById = new LinkedHashMap<>();

  /** Resolved once on first use; see {@link #notation()}. */
  private Notation resolvedNotation;

  private DrawioDocumentBuilder(
      SourceDocument source, LayoutResult layout, DrawioExportPolicy policy) {
    this.source = source;
    this.layout = layout;
    this.policy = policy;
  }

  /** Builds the one-page draw.io document for {@code layout}'s view. */
  public static Document build(
      SourceDocument source, LayoutResult layout, DrawioExportPolicy policy) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(layout, "layout");
    Objects.requireNonNull(policy, "policy");
    return new DrawioDocumentBuilder(source, layout, policy).build();
  }

  private Document build() {
    claimedCellIds.add(ROOT_CELL_ID);
    claimedCellIds.add(LAYER_CELL_ID);
    claimedCellIds.add(METADATA_CELL_ID);

    indexContainment();
    claimCellIds();

    cells.add(structuralCell(ROOT_CELL_ID, null));
    cells.add(structuralCell(LAYER_CELL_ID, ROOT_CELL_ID));
    cells.add(metadataCell());

    // Groups first so a parent precedes its children in the file, which is what draw.io itself
    // writes; MxReader resolves the parent chain either way.
    for (LaidOutGroup group : layout.groups()) {
      cells.add(groupCell(group));
    }
    for (LaidOutNode node : layout.nodes()) {
      cells.add(nodeCell(node));
    }
    for (LaidOutEdge edge : layout.edges()) {
      cells.add(edgeCell(edge));
    }
    reportOmittedOrnaments();
    reportDroppedProperties();

    var page = new MxDiagram(pageId(), pageName(), false, List.copyOf(cells));
    return new Document(new MxFile(List.of(page)), List.copyOf(diagnostics));
  }

  // ---------------------------------------------------------------- containment

  /**
   * Resolves each group's member list into a single parent per element, refusing the two shapes
   * mxGraph cannot express: an element claimed by two containers, and a containment cycle. Both
   * would otherwise produce a file {@link dev.dediren.plugins.drawio.mx.MxReader} refuses outright,
   * so the link is dropped and declared instead.
   */
  private void indexContainment() {
    for (LaidOutGroup group : layout.groups()) {
      groupsById.put(group.id(), group);
    }
    Set<String> known = new HashSet<>(groupsById.keySet());
    for (LaidOutNode node : layout.nodes()) {
      known.add(node.id());
    }
    for (LaidOutGroup group : layout.groups()) {
      for (String member : group.members()) {
        if (!known.contains(member)) {
          warnMissingReference(
              "group '"
                  + group.id()
                  + "' names member '"
                  + member
                  + "', which this layout result does not lay out; the container is emitted"
                  + " without it",
              "layout_result.groups");
          continue;
        }
        String claimed = containerOf.putIfAbsent(member, group.id());
        if (claimed != null) {
          warnMissingReference(
              "'"
                  + member
                  + "' is claimed by both group '"
                  + claimed
                  + "' and group '"
                  + group.id()
                  + "'; mxGraph gives a cell one parent, so it is nested in the first",
              "layout_result.groups");
        }
      }
    }
    breakContainmentCycles();
  }

  /** Drops the link that closes a containment cycle, walking each group's ancestry once. */
  private void breakContainmentCycles() {
    for (String groupId : groupsById.keySet()) {
      Set<String> walked = new LinkedHashSet<>();
      String current = groupId;
      while (current != null && walked.add(current)) {
        current = containerOf.get(current);
      }
      if (current != null) {
        containerOf.remove(current);
        warnMissingReference(
            "group '"
                + current
                + "' is contained in itself through the layout result's group membership; the"
                + " containment is dropped and the group is emitted at the page root",
            "layout_result.groups");
      }
    }
  }

  /** The mxCell id this element hangs under: its container's cell, or the default layer. */
  private String parentCellId(String dedirenId) {
    String container = containerOf.get(dedirenId);
    return container == null
        ? LAYER_CELL_ID
        : cellIdByDedirenId.getOrDefault(container, LAYER_CELL_ID);
  }

  /**
   * The absolute origin of this element's container, or the page origin. Subtracting it is what
   * turns the layout result's absolute coordinates into the relative ones mxGraph expects.
   */
  private Point containerOrigin(String dedirenId) {
    LaidOutGroup container = groupsById.get(containerOf.get(dedirenId));
    return container == null ? new Point(0, 0) : new Point(container.x(), container.y());
  }

  // ---------------------------------------------------------------- cells

  private static MxCell structuralCell(String id, String parent) {
    return new MxCell(id, parent, null, null, false, false, null, null, true, null, null);
  }

  /**
   * The one hidden cell per page carrying the view's identity.
   *
   * <p>It has to be a cell rather than attributes on {@code <mxfile>} or {@code <diagram>}: the
   * editor rewrites those two elements on every save, while it round-trips unknown {@code <object>}
   * attributes verbatim — that round-trip is the mechanism behind draw.io's own Edit Data feature,
   * and it is the only place in the format where custom data reliably survives an edit.
   *
   * <p>It carries no {@link DrawioIdentity#ID}, which is how a consumer tells it apart from a cell
   * standing for a model element without having to special-case its style or its visibility.
   *
   * <p>Every value it carries is the <strong>effective</strong> one, not only the explicitly
   * declared one. A source view that leaves {@code kind} implicit still has a kind — the importer
   * materializes {@code generic} — so omitting it made the first export differ from the second: a
   * re-imported model states what the original left unsaid, and export stopped being idempotent
   * over its own output. Writing the effective value loses nothing.
   */
  private MxCell metadataCell() {
    var attributes = new LinkedHashMap<String, String>();
    attributes.put(DrawioIdentity.TYPE, DrawioIdentity.VIEW_TYPE);
    putIfPresent(attributes, DrawioIdentity.VIEW_ID, layout.viewId());
    GenericGraphPluginData pluginData = genericGraphData();
    putIfPresent(attributes, DrawioIdentity.VIEW_KIND, effectiveViewKind(pluginData));
    putIfPresent(
        attributes,
        DrawioIdentity.SEMANTIC_PROFILE,
        pluginData == null
            ? null
            : jsonName(
                pluginData.semanticProfile() == null
                    ? GenericGraphSemanticProfile.GENERIC_GRAPH
                    : pluginData.semanticProfile()));
    putIfPresent(attributes, DrawioIdentity.MODEL_SCHEMA_VERSION, source.modelSchemaVersion());
    putIfPresent(
        attributes, DrawioIdentity.LAYOUT_PREFERENCES, layoutPreferencesJson(pluginData));
    attributes.put("id", METADATA_CELL_ID);

    return new MxCell(
        METADATA_CELL_ID,
        LAYER_CELL_ID,
        null,
        METADATA_STYLE,
        true,
        false,
        null,
        null,
        false,
        new MxGeometry(0, 0, 0, 0, false, null, null, List.of()),
        new MxObject("object", attributes));
  }

  /**
   * The container cell for one laid-out group, plus the element it stands for.
   *
   * <p><strong>The reference alone is not enough.</strong> A semantic boundary's element need only
   * be a node of the <em>document</em> — {@code SemanticsRouterEngine} and {@code SceneProjection}
   * both resolve {@code semantic_source_id} against {@code source.nodes()}, never against the
   * view's own node list — so the standard shape of a UML package boundary is an element with no
   * box of its own. Writing only its id would name something the file does not contain, and
   * Dediren's own artifact would fail its own re-import. The element's type and label ride the
   * container instead, and the importer rebuilds the node from them.
   *
   * <p><strong>A group naming itself names nothing.</strong> {@code SceneProjection} gives a
   * semantic-boundary group that declares no {@code semantic_source_id} a provenance pointing at
   * the group's own id, and the layout result carries that fallback back verbatim. Emitting it
   * would manufacture a reference to an element the model does not have — a file that re-imports
   * green and fails the next command. The discriminator is the source document: an id no node
   * declares is not an element, whatever the provenance says.
   */
  private MxCell groupCell(LaidOutGroup group) {
    String cellId = cellIdFor(group.id());
    String semanticSourceId = LaidOutGroups.semanticSourceId(group);
    SourceNode backing =
        semanticSourceId == null ? null : sourceNodeById().get(semanticSourceId);
    if (semanticSourceId != null && backing == null && !semanticSourceId.equals(group.id())) {
      // Silent for the self-naming fallback above, which is ordinary; loud for a boundary that
      // names some other element, which is a stale layout result against a changed model.
      warnMissingReference(
          "group '"
              + group.id()
              + "' stands for source element '"
              + semanticSourceId
              + "', which the source model does not declare; the container is emitted without a "
              + DrawioIdentity.SEMANTIC_SOURCE_ID,
          "layout_result.groups");
    }

    var attributes = new LinkedHashMap<String, String>();
    putIfPresent(attributes, "label", htmlLabel(group.label()));
    attributes.put(DrawioIdentity.ID, group.id());
    attributes.put(DrawioIdentity.TYPE, DrawioIdentity.GROUP_TYPE);
    attributes.put(
        DrawioIdentity.GROUP_ROLE,
        semanticSourceId == null
            ? DrawioIdentity.GROUP_ROLE_VISUAL
            : DrawioIdentity.GROUP_ROLE_SEMANTIC);
    if (backing != null) {
      attributes.put(DrawioIdentity.SEMANTIC_SOURCE_ID, semanticSourceId);
      putIfPresent(attributes, DrawioIdentity.SEMANTIC_SOURCE_TYPE, backing.type());
      putIfPresent(attributes, DrawioIdentity.SEMANTIC_SOURCE_LABEL, htmlLabel(backing.label()));
    }
    attributes.put("id", cellId);

    Point origin = containerOrigin(group.id());
    return new MxCell(
        cellId,
        parentCellId(group.id()),
        htmlLabel(group.label()),
        GROUP_STYLE,
        true,
        false,
        null,
        null,
        true,
        new MxGeometry(
            group.x() - origin.x(),
            group.y() - origin.y(),
            group.width(),
            group.height(),
            false,
            null,
            null,
            List.of()),
        new MxObject("object", attributes));
  }

  private MxCell nodeCell(LaidOutNode node) {
    String cellId = cellIdFor(node.id());
    SourceNode sourceNode = sourceNodeById().get(node.sourceId());
    if (sourceNode == null) {
      warnMissingReference(
          "laid-out node '"
              + node.id()
              + "' references source node '"
              + node.sourceId()
              + "', which the source model does not declare; it is exported with the neutral"
              + " fallback shape and no "
              + DrawioIdentity.TYPE,
          "layout_result.nodes");
    }
    String type = sourceNode == null ? null : sourceNode.type();

    var attributes = new LinkedHashMap<String, String>();
    putIfPresent(attributes, "label", htmlLabel(node.label()));
    attributes.put(DrawioIdentity.ID, node.id());
    putIfPresent(attributes, DrawioIdentity.TYPE, type);
    putIfPresent(attributes, DrawioIdentity.SEMANTIC_SOURCE_ID, node.sourceId());
    attributes.put("id", cellId);

    Point origin = containerOrigin(node.id());
    return new MxCell(
        cellId,
        parentCellId(node.id()),
        htmlLabel(node.label()),
        nodeStyle(node, type),
        true,
        false,
        null,
        null,
        true,
        new MxGeometry(
            node.x() - origin.x(),
            node.y() - origin.y(),
            node.width(),
            node.height(),
            false,
            null,
            null,
            List.of()),
        new MxObject("object", attributes));
  }

  private MxCell edgeCell(LaidOutEdge edge) {
    String cellId = cellIdFor(edge.id());
    SourceRelationship relationship = sourceRelationshipById().get(edge.sourceId());
    if (relationship == null) {
      warnMissingReference(
          "laid-out edge '"
              + edge.id()
              + "' references source relationship '"
              + edge.sourceId()
              + "', which the source model does not declare; it is exported without a "
              + DrawioIdentity.TYPE,
          "layout_result.edges");
    }

    String sourceCellId = endpointCellId(edge, edge.source(), "source");
    String targetCellId = endpointCellId(edge, edge.target(), "target");

    var attributes = new LinkedHashMap<String, String>();
    putIfPresent(attributes, "label", htmlLabel(edge.label()));
    attributes.put(DrawioIdentity.ID, edge.id());
    putIfPresent(
        attributes, DrawioIdentity.TYPE, relationship == null ? null : relationship.type());
    putIfPresent(attributes, DrawioIdentity.SEMANTIC_SOURCE_ID, edge.sourceId());
    putIfPresent(attributes, DrawioIdentity.SOURCE, edge.source());
    putIfPresent(attributes, DrawioIdentity.TARGET, edge.target());
    putIfPresent(attributes, DrawioIdentity.UML_SEQUENCE, messageSequence(relationship));
    attributes.put("id", cellId);

    return new MxCell(
        cellId,
        // Always the layer: waypoints are read against the edge's own parent, and the route
        // points below are absolute.
        LAYER_CELL_ID,
        htmlLabel(edge.label()),
        edgeStyle(edge, relationship == null ? null : relationship.type()),
        false,
        true,
        sourceCellId,
        targetCellId,
        true,
        edgeGeometry(edge, sourceCellId, targetCellId),
        new MxObject("object", attributes));
  }

  /**
   * The edge's own geometry: its interior bends, plus a floating endpoint for each end that names
   * no cell.
   *
   * <p>The first and last route points sit on the two shapes' perimeters. draw.io recomputes those
   * from the attached cells, so re-emitting them as waypoints adds bends that go stale the instant
   * a user drags a node. They are kept only where there is no cell to attach to, as the floating
   * endpoint that end would otherwise lack.
   */
  private static MxGeometry edgeGeometry(
      LaidOutEdge edge, String sourceCellId, String targetCellId) {
    List<Point> route = edge.points();
    List<MxPoint> waypoints = new ArrayList<>();
    if (route.size() > 2) {
      for (Point point : route.subList(1, route.size() - 1)) {
        waypoints.add(new MxPoint(point.x(), point.y()));
      }
    } else if (route.size() == 1) {
      waypoints.add(new MxPoint(route.get(0).x(), route.get(0).y()));
    }
    MxPoint sourcePoint =
        sourceCellId == null && !route.isEmpty()
            ? new MxPoint(route.get(0).x(), route.get(0).y())
            : null;
    MxPoint targetPoint =
        targetCellId == null && !route.isEmpty()
            ? new MxPoint(route.get(route.size() - 1).x(), route.get(route.size() - 1).y())
            : null;
    return new MxGeometry(0, 0, 0, 0, true, sourcePoint, targetPoint, List.copyOf(waypoints));
  }

  private String endpointCellId(LaidOutEdge edge, String endpoint, String end) {
    if (endpoint == null) {
      return null;
    }
    String cellId = cellIdByDedirenId.get(endpoint);
    if (cellId == null) {
      warnMissingReference(
          "laid-out edge '"
              + edge.id()
              + "' names "
              + end
              + " '"
              + endpoint
              + "', which this view does not lay out; the edge is exported with a floating "
              + end
              + " endpoint",
          "layout_result.edges");
    }
    return cellId;
  }

  // ---------------------------------------------------------------- styles

  private String nodeStyle(LaidOutNode node, String type) {
    if (type == null) {
      // The missing source element was already declared; a second diagnostic about its shape adds
      // nothing a repair could act on.
      return DrawioShapes.shapeFor(null).style();
    }
    boolean archimate = DrawioShapes.isMapped(type);
    boolean uml = DrawioUmlShapes.isMapped(type);
    if (!archimate && !uml) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.DRAWIO_SHAPE_UNMAPPED.code(),
              DiagnosticSeverity.WARNING,
              "no draw.io shape covers element type '"
                  + type
                  + "' (node '"
                  + node.id()
                  + "'); it is exported as a neutral rectangle. "
                  + DrawioIdentity.TYPE
                  + " still records the exact type, so re-importing this file is lossless"
                  + " regardless of the shape it was drawn with.",
              "layout_result.nodes"));
      return DrawioShapes.shapeFor(type).style();
    }

    // The declared view kind decides the three names both vocabularies claim; anything only one
    // table covers is drawn from that table whatever the view kind says.
    boolean drawAsUml = uml && (notation() == Notation.UML || !archimate);
    if (drawAsUml) {
      // No palette append: DrawioPalette is the ArchiMate layer palette, and UML carries no layer
      // to colour by. The UML table supplies its own semantic fills where a fill means something.
      return DrawioUmlShapes.shapeFor(type).style();
    }

    String style = DrawioShapes.shapeFor(type).style();
    if (PALETTE_EXEMPT_TYPES.contains(type)) {
      return style;
    }
    DrawioPalette.Colors colors = DrawioPalette.colorsFor(type);
    return style
        + "fillColor="
        + colors.fill()
        + ";strokeColor="
        + colors.stroke()
        + ";fontColor="
        + colors.labelFill()
        + ";";
  }

  /**
   * The notation-specific edge style for one relationship, declaring the notation if it has none.
   *
   * <p>Reuses {@code DEDIREN_DRAWIO_SHAPE_UNMAPPED} rather than minting an edge-specific code: the
   * situation and the repair are the same one the node message describes, and a relationship type
   * absent from the table is exactly as re-importable as an element type absent from the shape
   * table.
   */
  private String edgeStyle(LaidOutEdge edge, String relationshipType) {
    if (relationshipType == null) {
      // The missing source relationship was already declared.
      return DrawioEdgeStyles.styleFor(notation(), null);
    }
    if (!DrawioEdgeStyles.isMapped(notation(), relationshipType)) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.DRAWIO_SHAPE_UNMAPPED.code(),
              DiagnosticSeverity.WARNING,
              "no draw.io relationship notation covers type '"
                  + relationshipType
                  + "' (edge '"
                  + edge.id()
                  + "'); it is exported as a plain directed line. "
                  + DrawioIdentity.TYPE
                  + " still records the exact type, so re-importing this file is lossless"
                  + " regardless of the notation it was drawn with.",
              "layout_result.edges"));
    }
    return DrawioEdgeStyles.styleFor(notation(), relationshipType);
  }

  /**
   * Which vocabulary this view's type names are read against, resolved once from the view's
   * declared kind.
   *
   * <p>The kind is the only place the distinction exists: a source model carries no notation marker
   * on an individual element, and the three names both vocabularies declare are otherwise
   * indistinguishable. A view with no declared kind, or a kind of {@code generic} or {@code
   * archimate}, reads ArchiMate — which is what this exporter did before it drew UML at all.
   */
  private Notation notation() {
    if (resolvedNotation == null) {
      GenericGraphViewKind kind = declaredViewKindEnum(genericGraphData());
      resolvedNotation =
          kind != null && kind.name().startsWith("UML_") ? Notation.UML : Notation.ARCHIMATE;
    }
    return resolvedNotation;
  }

  // ---------------------------------------------------------------- disclosure

  /**
   * Names the UML behaviour ornamentation this export places and labels but does not draw. One
   * diagnostic per view rather than one per node: the omission is a property of the export, and a
   * fifty-message envelope for a fifty-message sequence diagram would bury it.
   */
  private void reportOmittedOrnaments() {
    Set<String> omitted = new TreeSet<>();
    for (LaidOutNode node : layout.nodes()) {
      // Most nodes carry no role at all, and Map.of() refuses a null lookup key outright.
      if (node.role() == null) {
        continue;
      }
      String ornament = OMITTED_ORNAMENTS.get(node.role());
      if (ornament != null) {
        omitted.add(ornament);
      }
    }
    if (omitted.isEmpty()) {
      return;
    }
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.DRAWIO_ORNAMENT_OMITTED.code(),
            DiagnosticSeverity.INFO,
            "this export places and labels every element but does not draw "
                + String.join(", ", omitted)
                + "; the result is a correctly positioned set of boxes and lines, not a rendered"
                + " sequence diagram.",
            "layout_result.nodes"));
  }

  /**
   * Names every {@code properties} entry this export cannot carry.
   *
   * <p>mxGraph has one extension point — the {@code <object>} wrapper's attribute set — and putting
   * a model's whole property tree through it would show a user opaque JSON in draw.io's Edit Data
   * dialog. Exactly one property is carried instead ({@link DrawioIdentity#UML_SEQUENCE}), because
   * a Message without it is not a valid model at all; the rest are declared lost here. Silence was
   * the worse half of that defect: the artifact re-imported green and the next command rejected
   * the model with nothing to connect the two.
   *
   * <p>One diagnostic per view, counting property paths rather than elements, for the same reason
   * {@link #reportOmittedOrnaments()} aggregates: a fifty-message sequence diagram would otherwise
   * bury every other diagnostic in the envelope.
   */
  private void reportDroppedProperties() {
    var dropped = new TreeMap<String, Integer>();
    for (LaidOutNode node : layout.nodes()) {
      collectDroppedProperties(sourceNodeById().get(node.sourceId()), null, dropped);
    }
    for (LaidOutEdge edge : layout.edges()) {
      SourceRelationship relationship = sourceRelationshipById().get(edge.sourceId());
      collectDroppedProperties(
          relationship == null ? null : relationship.properties(),
          relationship != null && messageSequence(relationship) != null ? UML_SEQUENCE_PATH : null,
          dropped);
    }
    if (dropped.isEmpty()) {
      return;
    }
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.DRAWIO_PROPERTIES_DROPPED.code(),
            DiagnosticSeverity.WARNING,
            "draw.io has nowhere to keep element properties, so these are not in the exported"
                + " file and will not come back if it is re-imported: "
                + dropped.entrySet().stream()
                    .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                    .collect(Collectors.joining(", "))
                + "; keep the source model as the record of truth and re-import only to recover"
                + " structure, geometry and identity",
            "source"));
  }

  private void collectDroppedProperties(
      SourceNode node, String carriedPath, Map<String, Integer> dropped) {
    collectDroppedProperties(node == null ? null : node.properties(), carriedPath, dropped);
  }

  private static void collectDroppedProperties(
      Map<String, JsonNode> properties, String carriedPath, Map<String, Integer> dropped) {
    if (properties == null) {
      return;
    }
    properties.forEach(
        (namespace, value) -> {
          if (value == null || !value.isObject()) {
            return;
          }
          value
              .propertyNames()
              .forEach(
                  key -> {
                    String path = namespace + "." + key;
                    if (!path.equals(carriedPath)) {
                      dropped.merge(path, 1, Integer::sum);
                    }
                  });
        });
  }

  /**
   * A Message's {@code properties.uml.sequence} as the attribute value to write, or {@code null}
   * when this relationship declares none. Only an integral, positive ordering is carried, matching
   * what {@code UmlSequenceValidation.validateMessageProperties} will accept back.
   */
  private static String messageSequence(SourceRelationship relationship) {
    if (relationship == null) {
      return null;
    }
    JsonNode uml = relationship.properties().get("uml");
    if (uml == null || !uml.isObject()) {
      return null;
    }
    JsonNode sequence = uml.get("sequence");
    return sequence != null && sequence.isIntegralNumber() && sequence.bigIntegerValue().signum() > 0
        ? sequence.bigIntegerValue().toString()
        : null;
  }

  /**
   * A label as draw.io will actually render it: every cell here is styled {@code html=1}, and an
   * HTML label collapses whitespace, so a line break has to be {@code <br>}.
   *
   * <p>A raw newline does not even reach the renderer. XML attribute-value normalization (XML 1.0
   * §3.3.3) replaces a literal {@code #xA} in an attribute with a space before any parser hands the
   * value on, so writing the newline through lost the break from the model too — a re-import read
   * back {@code "Ingest Gateway"}. {@link
   * dev.dediren.plugins.drawio.read.DrawioSourceMapper} decodes the three {@code <br>} spellings on
   * the way in; this is the other half of that pair.
   */
  private static String htmlLabel(String label) {
    return label == null ? null : label.replace("\r\n", "<br>").replace("\n", "<br>");
  }

  private void warnMissingReference(String message, String path) {
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.DRAWIO_LAYOUT_REFERENCE_MISSING.code(),
            DiagnosticSeverity.WARNING,
            message,
            path));
  }

  // ---------------------------------------------------------------- indexes and identity

  private Map<String, SourceNode> sourceNodeIndex;
  private Map<String, SourceRelationship> sourceRelationshipIndex;

  private Map<String, SourceNode> sourceNodeById() {
    if (sourceNodeIndex == null) {
      sourceNodeIndex = new LinkedHashMap<>();
      for (SourceNode node : source.nodes()) {
        sourceNodeIndex.putIfAbsent(node.id(), node);
      }
    }
    return sourceNodeIndex;
  }

  private Map<String, SourceRelationship> sourceRelationshipById() {
    if (sourceRelationshipIndex == null) {
      sourceRelationshipIndex = new LinkedHashMap<>();
      for (SourceRelationship relationship : source.relationships()) {
        sourceRelationshipIndex.putIfAbsent(relationship.id(), relationship);
      }
    }
    return sourceRelationshipIndex;
  }

  /**
   * Claims a slugged, collision-free mxCell id for every element up front.
   *
   * <p>Up front rather than as each cell is built, because a group's container and an edge's
   * endpoints have to resolve to mxCell ids regardless of the order the layout result happens to
   * list them in. Resolving lazily works for a result that lists parents before children and fails
   * silently — flattening the nesting, not erroring — for one that does not.
   */
  private void claimCellIds() {
    for (LaidOutGroup group : layout.groups()) {
      claimCellId(group.id());
    }
    for (LaidOutNode node : layout.nodes()) {
      claimCellId(node.id());
    }
    for (LaidOutEdge edge : layout.edges()) {
      claimCellId(edge.id());
    }
  }

  /**
   * Claims one id and remembers the pairing.
   *
   * <p>The two ids are deliberately different things. mxCell ids are the file's internal wiring and
   * the editor may reassign them; the dediren id is the identity a re-import restores from, and it
   * rides the {@code <object>} wrapper untouched.
   */
  private String claimCellId(String dedirenId) {
    String cellId = XmlIds.unique(claimedCellIds, XmlIds.slug(dedirenId == null ? "" : dedirenId));
    if (dedirenId != null) {
      cellIdByDedirenId.put(dedirenId, cellId);
    }
    return cellId;
  }

  /** The already-claimed mxCell id for a dediren id. */
  private String cellIdFor(String dedirenId) {
    return cellIdByDedirenId.get(dedirenId);
  }

  // ---------------------------------------------------------------- page identity

  private String pageId() {
    return layout.viewId() == null ? "page-1" : XmlIds.slug(layout.viewId());
  }

  /** The per-view override where the policy carries one, otherwise the policy's page name. */
  private String pageName() {
    if (layout.viewId() == null) {
      return policy.diagramName();
    }
    DrawioExportPolicy.ViewIdentity override = policy.views().get(layout.viewId());
    if (override != null && override.diagramName() != null && !override.diagramName().isBlank()) {
      return override.diagramName();
    }
    return policy.diagramName();
  }

  // ---------------------------------------------------------------- source plugin data

  private GenericGraphPluginData genericGraphData() {
    JsonNode pluginData = source.plugins().get("generic-graph");
    if (pluginData == null || !pluginData.isObject()) {
      return null;
    }
    try {
      return JsonSupport.objectMapper().convertValue(pluginData, GenericGraphPluginData.class);
    } catch (JacksonException unreadable) {
      // Malformed plugin data is not this export's to reject; the pipeline validates source
      // structure upstream. Absent readable data, the metadata cell simply carries less.
      return null;
    }
  }

  /** The declared view kind, or the {@code generic} the importer would materialize in its place. */
  private String effectiveViewKind(GenericGraphPluginData pluginData) {
    GenericGraphView view = exportedView(pluginData);
    if (view == null) {
      return null;
    }
    return jsonName(view.kind() == null ? GenericGraphViewKind.GENERIC : view.kind());
  }

  /**
   * The exported view's {@code layout_preferences} as compact JSON, or {@code null} when it
   * declares none. Serialized with the model's own mapper, so what comes back out of a re-import is
   * the same block that went in rather than a re-spelling of it.
   */
  private String layoutPreferencesJson(GenericGraphPluginData pluginData) {
    GenericGraphView view = exportedView(pluginData);
    if (view == null || view.layoutPreferences() == null) {
      return null;
    }
    return JsonSupport.objectMapper().writeValueAsString(view.layoutPreferences());
  }

  /** The source view this layout result belongs to, or {@code null} when it names none. */
  private GenericGraphView exportedView(GenericGraphPluginData pluginData) {
    if (pluginData == null || layout.viewId() == null) {
      return null;
    }
    for (GenericGraphView view : pluginData.views()) {
      if (layout.viewId().equals(view.id())) {
        return view;
      }
    }
    return null;
  }

  /** The kind this layout's view declares, or {@code null} if it declares none. */
  private GenericGraphViewKind declaredViewKindEnum(GenericGraphPluginData pluginData) {
    GenericGraphView view = exportedView(pluginData);
    return view == null ? null : view.kind();
  }

  /**
   * The enum's wire spelling ({@code uml-sequence}), not its Java constant name. The metadata cell
   * is read back by the importer and by a human in Edit Data, and both expect the contract's own
   * vocabulary.
   */
  private static String jsonName(Enum<?> value) {
    String written = JsonSupport.writeValueAsString(value);
    return written.length() >= 2 ? written.substring(1, written.length() - 1) : written;
  }

  private static void putIfPresent(Map<String, String> attributes, String name, String value) {
    if (value != null && !value.isEmpty()) {
      attributes.put(name, value);
    }
  }
}

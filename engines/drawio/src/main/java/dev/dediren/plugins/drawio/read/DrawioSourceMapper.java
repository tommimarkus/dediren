package dev.dediren.plugins.drawio.read;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.plugins.drawio.DrawioLimits;
import dev.dediren.plugins.drawio.mx.MxCell;
import dev.dediren.plugins.drawio.mx.MxDiagram;
import dev.dediren.plugins.drawio.mx.MxFile;
import dev.dediren.plugins.drawio.write.DrawioIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a parsed {@link MxFile} onto the generic-graph source contract.
 *
 * <h2>Two identity paths</h2>
 *
 * <p><strong>Round trip.</strong> A cell wrapped in {@code <object dedirenId=…>} recovers its id,
 * type, group role, and — for an edge — its endpoints verbatim. Endpoints are keyed by {@code
 * dedirenId} rather than by {@code mxCell} id because draw.io reassigns cell ids freely as a user
 * edits, so cell ids are the one part of the file that does not survive a real editing session. One
 * hidden metadata cell per page ({@code dedirenType="dediren.view"}) carries the semantic profile,
 * view id, view kind, and model schema version; it is consumed as metadata and never emitted as a
 * node.
 *
 * <p><strong>Foreign.</strong> Hand-authored draw.io always produces {@code semantic_profile:
 * generic-graph} with {@code generic.node}/{@code generic.link}, exactly like the DOT and Mermaid
 * importers. A recognized stencil is recorded under {@code properties.drawio.stencil} with a
 * suggested type and summarized in one {@link DiagnosticCode#DRAWIO_KIND_INFERRED} info diagnostic,
 * but is <em>never</em> promoted: draw.io encodes relationship semantics only as arrowhead
 * decoration, which cannot be reversed; a wrongly promoted ArchiMate model would fail {@code
 * validate --profile} after a green import, which is the worst possible diagnostic ordering; and a
 * third importer that sometimes emits a different profile would make "what does import produce"
 * unanswerable.
 *
 * <h2>What is dropped, and what is refused</h2>
 *
 * <p>A dangling edge is dropped with a warning rather than failing the import: a floating connector
 * is the commonest condition in a hand-drawn file and carries nothing to recover, and {@code
 * DEDIREN_ELK_DANGLING_EDGE} is the in-repo precedent. Emitting it instead would pass import —
 * {@code SourceValidator.gateImportedDocument} checks schema and ceilings only — and then fail
 * {@code dediren validate} with {@code DEDIREN_DANGLING_ENDPOINT}, i.e. a green import producing an
 * unusable model. Constructs Dediren cannot represent at all fail the whole import atomically
 * instead, so no partial model is ever produced.
 *
 * <p>Geometry is discarded because {@code schemas/model.schema.json} {@code $defs.sourceNode} is
 * {@code additionalProperties: false} with no x/y/width/height. That is contractual, not a
 * preference: every import lane re-lays the page out with ELK.
 *
 * <p>Cells carry no line or column ({@code MxCell} deliberately has none, because a compressed
 * page's coordinates belong to a different document), so every diagnostic path names the page and
 * the cell id instead.
 */
public final class DrawioSourceMapper {

  /** The published source-model id pattern, shared by nodes, relationships, groups, and views. */
  private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

  /** The three {@code <br>} spellings, case-insensitive — identical to the Mermaid importer's. */
  private static final Pattern SAFE_BR = Pattern.compile("(?i)<br(?:/| /)?>");

  /** Any remaining markup tag, once the {@code <br>} family has been removed. */
  private static final Pattern HTML_TAG = Pattern.compile("<\\s*/?\\s*[A-Za-z][^>]*>");

  private static final String PLUGIN_KEY = "generic-graph";
  private static final String PROPERTY_KEY = "drawio";
  private static final String MAIN_VIEW = "main";

  /**
   * The identity vocabulary is {@link DrawioIdentity}, which the export half of this lane owns and
   * publishes precisely so both halves agree at compile time. Reading it here rather than
   * re-spelling the names is the whole point of that class: a one-character divergence would
   * produce a file that imports cleanly and silently loses every model identity in it.
   */
  private static final String ATTR_ID = DrawioIdentity.ID;

  private static final String ATTR_TYPE = DrawioIdentity.TYPE;
  private static final String ATTR_SOURCE = DrawioIdentity.SOURCE;
  private static final String ATTR_TARGET = DrawioIdentity.TARGET;
  private static final String ATTR_GROUP_ROLE = DrawioIdentity.GROUP_ROLE;
  private static final String ATTR_SEMANTIC_SOURCE = DrawioIdentity.SEMANTIC_SOURCE_ID;
  private static final String ATTR_SEMANTIC_SOURCE_TYPE = DrawioIdentity.SEMANTIC_SOURCE_TYPE;
  private static final String ATTR_SEMANTIC_SOURCE_LABEL = DrawioIdentity.SEMANTIC_SOURCE_LABEL;
  private static final String ATTR_UML_SEQUENCE = DrawioIdentity.UML_SEQUENCE;
  private static final String ATTR_VIEW_ID = DrawioIdentity.VIEW_ID;
  private static final String ATTR_VIEW_KIND = DrawioIdentity.VIEW_KIND;
  private static final String ATTR_PROFILE = DrawioIdentity.SEMANTIC_PROFILE;
  private static final String ATTR_MODEL_VERSION = DrawioIdentity.MODEL_SCHEMA_VERSION;
  private static final String ATTR_LAYOUT_PREFERENCES = DrawioIdentity.LAYOUT_PREFERENCES;
  private static final String ATTR_ELEMENT_PROPERTIES = DrawioIdentity.ELEMENT_PROPERTIES;

  /** Wrapper attributes the mapper consumes; anything else on a wrapper is a discarded hint. */
  private static final Set<String> CONSUMED_ATTRIBUTES =
      Set.of(
          "id",
          "label",
          ATTR_ID,
          ATTR_TYPE,
          ATTR_SOURCE,
          ATTR_TARGET,
          ATTR_GROUP_ROLE,
          ATTR_SEMANTIC_SOURCE,
          ATTR_SEMANTIC_SOURCE_TYPE,
          ATTR_SEMANTIC_SOURCE_LABEL,
          ATTR_UML_SEQUENCE,
          ATTR_VIEW_ID,
          ATTR_VIEW_KIND,
          ATTR_PROFILE,
          ATTR_MODEL_VERSION,
          ATTR_LAYOUT_PREFERENCES,
          ATTR_ELEMENT_PROPERTIES);

  /** Longest attacker-supplied fragment echoed into a published diagnostic. */
  private static final int ECHO_LIMIT = 80;

  /** Longest run of cell ids listed in one aggregated diagnostic. */
  private static final int LIST_LIMIT = 24;

  /** The mapper's product: the document, plus the diagnostics that ride the success envelope. */
  public record MappingResult(SourceDocument document, List<Diagnostic> diagnostics) {
    public MappingResult {
      diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
  }

  private final int maxElements;
  private final Map<String, SourceNode> nodes = new LinkedHashMap<>();
  private final Map<String, SourceRelationship> relationships = new LinkedHashMap<>();
  private final List<GenericGraphView> views = new ArrayList<>();
  private final Set<String> reservedElementIds = new LinkedHashSet<>();
  private final Set<String> reservedViewIds = new LinkedHashSet<>();
  private final Map<String, Endpoint> dedirenIndex = new LinkedHashMap<>();

  /**
   * The elements a semantic boundary declares but no cell draws, keyed by element id.
   *
   * <p>Separate from {@link #dedirenIndex} because these are not cells: nothing on the page can be
   * an edge endpoint or a container for them. They exist only to be rebuilt as document nodes, and
   * only a boundary that carried a type can produce one.
   */
  private final Map<String, CarriedElement> carriedSemanticSources = new LinkedHashMap<>();

  /**
   * The element {@code properties} the pages carry on their hidden metadata cells, keyed by element
   * id and merged across every page before any element is built.
   *
   * <p>Document-scoped rather than page-scoped because the element a semantic boundary stands for
   * is rebuilt after every page has been read, and because an element drawn on two pages is built
   * once. First page to declare an id wins, which is the rule {@link #addNode} already applies to
   * that element's type and label.
   */
  private final Map<String, Map<String, JsonNode>> carriedProperties = new LinkedHashMap<>();

  /** The dediren ids of cells that map to nodes — the only ids a semantic boundary may name. */
  private final Set<String> nodeDedirenIds = new LinkedHashSet<>();

  private final Map<String, Integer> discardedKeys = new LinkedHashMap<>();
  private final Map<String, Integer> stencilTokens = new LinkedHashMap<>();
  private final List<String> hiddenCells = new ArrayList<>();
  private final List<String> droppedEdges = new ArrayList<>();
  private final List<String> flattenedLayers = new ArrayList<>();
  private int groupCount;
  private int suggestedStencils;
  private GenericGraphSemanticProfile profile = GenericGraphSemanticProfile.GENERIC_GRAPH;
  private boolean profileDeclared;
  private boolean roundTripDocument;

  private DrawioSourceMapper(int maxElements) {
    this.maxElements = maxElements;
  }

  /** Maps one parsed draw.io document at the shipped {@link DrawioLimits#MAX_ELEMENTS} ceiling. */
  public static MappingResult map(MxFile file) throws EngineException {
    return map(file, DrawioLimits.MAX_ELEMENTS);
  }

  /**
   * Ceiling-injecting overload; production callers take {@link DrawioLimits#MAX_ELEMENTS} and tests
   * use tiny limits, mirroring {@code SourceValidator.gateImportedDocument}. Building a hundred
   * thousand cells to exercise one comparison would buy no extra confidence and would dominate the
   * module's test runtime.
   */
  static MappingResult map(MxFile file, int maxElements) throws EngineException {
    return new DrawioSourceMapper(maxElements).run(file);
  }

  // ---------------------------------------------------------------- pipeline

  private MappingResult run(MxFile file) throws EngineException {
    List<PageScan> scans = new ArrayList<>();
    for (int index = 0; index < file.diagrams().size(); index++) {
      scans.add(scan(file.diagrams().get(index), index + 1));
    }
    // One dediren.view metadata cell anywhere makes this a round-tripped document. Without one
    // there is no declared profile for a dedirenType to be valid under, so a stray hand-typed
    // dedirenType must not smuggle an ArchiMate-looking type into a generic-graph model; a
    // dedirenId still stands, because an id is profile-neutral.
    roundTripDocument = scans.stream().anyMatch(scan -> scan.metadata != null);
    for (PageScan scan : scans) {
      readMetadata(scan);
      refuseUnsupported(scan);
    }
    for (PageScan scan : scans) {
      reserveRoundTripIds(scan);
    }
    for (PageScan scan : scans) {
      checkSemanticSources(scan);
    }
    for (PageScan scan : scans) {
      buildPage(scan);
    }
    // After every page, because a later page may draw the very element an earlier page's boundary
    // stands for, and a drawn cell wins over the reconstruction.
    carriedSemanticSources.forEach(this::addCarriedSemanticSource);

    int produced = nodes.size() + relationships.size() + groupCount;
    if (produced > maxElements) {
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_ELEMENT_LIMIT_EXCEEDED,
          "the document maps to more than "
              + maxElements
              + " produced elements (nodes, relationships, and view groups)");
    }

    JsonNode plugin =
        JsonSupport.objectMapper().valueToTree(new GenericGraphPluginData(profile, views));
    SourceDocument document =
        new SourceDocument(
            ContractVersions.MODEL_SCHEMA_VERSION,
            List.of(),
            List.of(),
            List.copyOf(nodes.values()),
            List.copyOf(relationships.values()),
            Map.of(PLUGIN_KEY, plugin));
    return new MappingResult(document, diagnostics());
  }

  // ---------------------------------------------------------------- page scan

  /**
   * Everything about one page that the later passes need, computed once. Containment is resolved
   * here rather than per pass because a cell's effective parent (its nearest <em>vertex</em>
   * ancestor) is what decides whether a vertex is a node or a group, and both the id pass and the
   * build pass need that answer.
   */
  private static final class PageScan {
    private final int number;
    private final MxDiagram diagram;
    private final Map<String, MxCell> byId = new LinkedHashMap<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final List<MxCell> content = new ArrayList<>();
    private final Map<String, List<MxCell>> childVertices = new LinkedHashMap<>();
    private final Map<String, MxCell> edgeLabels = new LinkedHashMap<>();
    private final Set<String> edgeLabelIds = new LinkedHashSet<>();
    private MxCell metadata;
    private String viewId;
    private GenericGraphViewKind viewKind = GenericGraphViewKind.GENERIC;
    private LayoutPreferences layoutPreferences;

    private PageScan(int number, MxDiagram diagram) {
      this.number = number;
      this.diagram = diagram;
    }

    /** True when this vertex holds at least one visible vertex child, i.e. it is a container. */
    private boolean isGroup(MxCell cell) {
      return cell.vertex() && childVertices.containsKey(cell.id());
    }
  }

  private PageScan scan(MxDiagram diagram, int number) throws EngineException {
    PageScan scan = new PageScan(number, diagram);
    for (MxCell cell : diagram.cells()) {
      scan.byId.put(cell.id(), cell);
    }

    for (MxCell cell : diagram.cells()) {
      if (!DrawioIdentity.VIEW_TYPE.equals(attribute(cell, ATTR_TYPE))) {
        continue;
      }
      if (scan.metadata != null) {
        throw roundTripInvalid(
            "page "
                + number
                + " carries two "
                + DrawioIdentity.VIEW_TYPE
                + " metadata cells, so its view identity is ambiguous",
            scan,
            cell);
      }
      scan.metadata = cell;
    }

    for (MxCell cell : diagram.cells()) {
      if (isHidden(cell, scan)) {
        scan.hidden.add(cell.id());
      }
    }

    List<MxCell> layers = new ArrayList<>();
    for (MxCell cell : diagram.cells()) {
      if (isLayer(cell, scan)) {
        layers.add(cell);
      }
    }
    if (layers.size() > 1) {
      // A layer is z-order and visibility, not containment; turning one into a group would
      // fabricate nesting nobody drew. One default layer is every ordinary draw.io file and
      // flattens nothing, so only a real stack is worth reporting.
      layers.forEach(layer -> flattenedLayers.add(describe(scan, layer.id(), layer.value())));
    }

    for (MxCell cell : diagram.cells()) {
      if (cell == scan.metadata || (!cell.vertex() && !cell.edge())) {
        continue;
      }
      if (scan.hidden.contains(cell.id())) {
        hiddenCells.add(describe(scan, cell.id(), null));
        continue;
      }
      scan.content.add(cell);
    }

    for (MxCell cell : scan.content) {
      if (!cell.vertex()) {
        continue;
      }
      MxCell rawParent = cell.parent() == null ? null : scan.byId.get(cell.parent());
      if (rawParent != null && rawParent.edge()) {
        scan.edgeLabels.putIfAbsent(rawParent.id(), cell);
        scan.edgeLabelIds.add(cell.id());
        continue;
      }
      MxCell container = effectiveParent(cell, scan);
      if (container != null) {
        scan.childVertices.computeIfAbsent(container.id(), unused -> new ArrayList<>()).add(cell);
      }
    }
    return scan;
  }

  /** Visibility is inherited: what the author sees is what the model means. */
  private static boolean isHidden(MxCell cell, PageScan scan) {
    MxCell current = cell;
    while (current != null) {
      if (!current.visible()) {
        return true;
      }
      current = current.parent() == null ? null : scan.byId.get(current.parent());
    }
    return false;
  }

  /** A layer is a structural cell parented directly to the page's structural root. */
  private static boolean isLayer(MxCell cell, PageScan scan) {
    if (cell.vertex() || cell.edge() || cell.parent() == null) {
      return false;
    }
    MxCell parent = scan.byId.get(cell.parent());
    return parent != null && !parent.vertex() && !parent.edge() && parent.parent() == null;
  }

  /**
   * The nearest vertex ancestor: structural cells (the root and every layer) are walked through.
   */
  private static MxCell effectiveParent(MxCell cell, PageScan scan) {
    MxCell current = cell.parent() == null ? null : scan.byId.get(cell.parent());
    while (current != null) {
      if (current.vertex()) {
        return current;
      }
      if (current.edge()) {
        return null;
      }
      current = current.parent() == null ? null : scan.byId.get(current.parent());
    }
    return null;
  }

  // ---------------------------------------------------------------- round-trip metadata

  private void readMetadata(PageScan scan) throws EngineException {
    if (scan.metadata == null) {
      return;
    }
    Map<String, String> attributes = scan.metadata.object().attributes();

    String declaredProfile = attributes.get(ATTR_PROFILE);
    if (declaredProfile != null) {
      GenericGraphSemanticProfile parsed =
          constant(GenericGraphSemanticProfile.class, declaredProfile);
      if (parsed == null) {
        throw roundTripInvalid(
            "unknown semantic profile '" + echo(declaredProfile) + "'", scan, scan.metadata);
      }
      if (profileDeclared && parsed != profile) {
        throw roundTripInvalid(
            "page "
                + scan.number
                + " declares semantic profile '"
                + echo(declaredProfile)
                + "', which conflicts with an earlier page",
            scan,
            scan.metadata);
      }
      profile = parsed;
      profileDeclared = true;
    }

    String declaredKind = attributes.get(ATTR_VIEW_KIND);
    if (declaredKind != null) {
      GenericGraphViewKind parsed = constant(GenericGraphViewKind.class, declaredKind);
      if (parsed == null) {
        throw roundTripInvalid(
            "unknown view kind '" + echo(declaredKind) + "'", scan, scan.metadata);
      }
      scan.viewKind = parsed;
    }

    String declaredVersion = attributes.get(ATTR_MODEL_VERSION);
    if (declaredVersion != null && !ContractVersions.MODEL_SCHEMA_VERSION.equals(declaredVersion)) {
      throw roundTripInvalid(
          "the page declares model schema version '"
              + echo(declaredVersion)
              + "', but this build round-trips "
              + ContractVersions.MODEL_SCHEMA_VERSION,
          scan,
          scan.metadata);
    }

    String declaredViewId = attributes.get(ATTR_VIEW_ID);
    if (declaredViewId != null) {
      if (!valid(declaredViewId)) {
        throw roundTripInvalid(
            "view id '" + echo(declaredViewId) + "' is not a valid Dediren id",
            scan,
            scan.metadata);
      }
      if (!reservedViewIds.add(declaredViewId)) {
        throw roundTripInvalid(
            "view id '" + echo(declaredViewId) + "' is declared on two pages", scan, scan.metadata);
      }
      scan.viewId = declaredViewId;
    }

    readElementProperties(scan, attributes.get(ATTR_ELEMENT_PROPERTIES));

    String declaredPreferences = attributes.get(ATTR_LAYOUT_PREFERENCES);
    if (declaredPreferences != null && !declaredPreferences.isBlank()) {
      // MxReader already refused any attribute value over DrawioLimits.MAX_TOKEN_BYTES, so the
      // string handed to the mapper here is bounded before it is parsed.
      try {
        scan.layoutPreferences =
            JsonSupport.objectMapper().readValue(declaredPreferences, LayoutPreferences.class);
      } catch (JacksonException unreadable) {
        // Bounded and typed rather than best-effort: an unreadable block would otherwise become a
        // silently different picture, which is the failure mode this attribute exists to close.
        throw roundTripInvalid(
            ATTR_LAYOUT_PREFERENCES
                + " is not a readable layout_preferences object: "
                + echo(unreadable.getOriginalMessage()),
            scan,
            scan.metadata);
      }
    }
  }

  /**
   * Restores the element {@code properties} the page carries on its metadata cell.
   *
   * <p>Bounded and typed rather than best-effort, exactly as {@code layout_preferences} is: {@code
   * MxReader} has already refused any attribute value over {@link DrawioLimits#MAX_TOKEN_BYTES}, so
   * the string is bounded before it is parsed, and an unreadable one is refused rather than skipped
   * — silently importing a model with the properties missing is precisely the green-import,
   * rejected-next-command failure this channel exists to close.
   */
  private void readElementProperties(PageScan scan, String declared) throws EngineException {
    if (declared == null || declared.isBlank()) {
      return;
    }
    JsonNode parsed;
    try {
      parsed = JsonSupport.objectMapper().readTree(declared);
    } catch (JacksonException unreadable) {
      throw roundTripInvalid(
          ATTR_ELEMENT_PROPERTIES
              + " is not readable JSON: "
              + echo(unreadable.getOriginalMessage()),
          scan,
          scan.metadata);
    }
    if (!parsed.isObject()) {
      throw roundTripInvalid(
          ATTR_ELEMENT_PROPERTIES + " is not an object keyed by element id", scan, scan.metadata);
    }
    for (String elementId : parsed.propertyNames()) {
      JsonNode properties = parsed.get(elementId);
      if (!properties.isObject()) {
        throw roundTripInvalid(
            ATTR_ELEMENT_PROPERTIES + " entry '" + echo(elementId) + "' is not a properties object",
            scan,
            scan.metadata);
      }
      var namespaces = new LinkedHashMap<String, JsonNode>();
      for (String namespace : properties.propertyNames()) {
        namespaces.put(namespace, properties.get(namespace));
      }
      carriedProperties.putIfAbsent(elementId, namespaces);
    }
  }

  /**
   * The carried {@code properties} for one element, or an empty map. Copied per element because the
   * caller may add a {@code drawio} namespace of its own on top.
   */
  private Map<String, JsonNode> carriedPropertiesOf(String elementId) {
    Map<String, JsonNode> carried = carriedProperties.get(elementId);
    return carried == null ? new LinkedHashMap<>() : new LinkedHashMap<>(carried);
  }

  // ---------------------------------------------------------------- declined constructs

  private void refuseUnsupported(PageScan scan) throws EngineException {
    for (MxCell cell : scan.content) {
      Map<String, String> style = parseStyle(cell.style());
      Map<String, String> attributes =
          cell.object() == null ? Map.of() : cell.object().attributes();

      // A link is the format's one way of reaching outside the file, and every image key is a
      // resource load. Refusing both is what keeps the threat model's "no external resource load"
      // claim true rather than merely usually true.
      for (String interactive : List.of("link", "linkTarget")) {
        if (attributes.containsKey(interactive)) {
          throw unsupported(
              "the '"
                  + interactive
                  + "' attribute is an interactive draw.io feature Dediren does"
                  + " not import; remove it",
              scan,
              cell);
        }
      }
      String shape = style.get("shape");
      if ("image".equals(shape) || style.containsKey("image")) {
        throw unsupported(
            "an image shape or image URL is not part of the supported subset; replace it with a"
                + " plain shape",
            scan,
            cell);
      }
      if (style.containsKey("imageBackground")) {
        throw unsupported(
            "an imageBackground URL is not part of the supported subset; remove it", scan, cell);
      }
      if (shape != null && shape.startsWith("stencil(")) {
        throw unsupported(
            "an embedded shape=stencil(...) definition is a second nested compressed payload"
                + " Dediren refuses to decode; use a named draw.io shape",
            scan,
            cell);
      }
      if ("1".equals(style.get("html"))
          && cell.value() != null
          && HTML_TAG.matcher(SAFE_BR.matcher(cell.value()).replaceAll("\n")).find()) {
        throw unsupported(
            "label HTML beyond <br>, <br/>, and <br /> is not part of the supported subset; the"
                + " label carries markup Dediren cannot represent",
            scan,
            cell);
      }
      if (cell.vertex() && cell.parent() != null) {
        MxCell rawParent = scan.byId.get(cell.parent());
        if (rawParent != null && rawParent.edge() && !style.containsKey("edgeLabel")) {
          throw unsupported(
              "cell '"
                  + echo(cell.id())
                  + "' is a shape parented to a connector, which Dediren has no way to represent;"
                  + " only an edgeLabel may ride an edge",
              scan,
              cell);
        }
      }
    }
  }

  // ---------------------------------------------------------------- identity

  /** Where a {@code dedirenSource}/{@code dedirenTarget} lands once the group hop is applied. */
  private record Endpoint(String elementId, boolean group, String semanticSourceId) {}

  /** A boundary's element as the file carries it, for the node this mapper rebuilds from it. */
  private record CarriedElement(String type, String label) {}

  private void reserveRoundTripIds(PageScan scan) throws EngineException {
    Set<String> onThisPage = new LinkedHashSet<>();
    for (MxCell cell : scan.content) {
      String declared = attribute(cell, ATTR_ID);
      if (declared == null) {
        continue;
      }
      if (!valid(declared)) {
        throw roundTripInvalid(
            "dedirenId '" + echo(declared) + "' is not a valid Dediren id", scan, cell);
      }
      if (!onThisPage.add(declared)) {
        throw roundTripInvalid(
            "dedirenId '" + echo(declared) + "' appears twice on page " + scan.number, scan, cell);
      }
      reservedElementIds.add(declared);
      if (cell.vertex() && !scan.edgeLabelIds.contains(cell.id())) {
        boolean group = scan.isGroup(cell);
        dedirenIndex.put(
            declared, new Endpoint(declared, group, attribute(cell, ATTR_SEMANTIC_SOURCE)));
        if (group) {
          reserveCarriedSemanticSource(scan, cell);
        } else {
          nodeDedirenIds.add(declared);
        }
      }
    }
  }

  /**
   * Records the element a semantic boundary stands for when the boundary carries enough to rebuild
   * it.
   *
   * <p>A boundary's element is contract-legal without a box of its own — {@code
   * SemanticsRouterEngine} and {@code SceneProjection} both resolve {@code semantic_source_id}
   * against the document's nodes, not the view's — so the export writes the element's type and
   * label onto the container and this rebuilds the node. The type is honoured only on a
   * round-tripped document, for the same reason {@link #typeOrDefault} is: outside one there is no
   * declared profile for it to be valid under, and a hand-typed attribute must not smuggle a UML
   * type into a generic-graph model.
   */
  private void reserveCarriedSemanticSource(PageScan scan, MxCell cell) throws EngineException {
    if (!DrawioIdentity.GROUP_ROLE_SEMANTIC.equals(attribute(cell, ATTR_GROUP_ROLE))) {
      return;
    }
    String semanticSource = attribute(cell, ATTR_SEMANTIC_SOURCE);
    String type = attribute(cell, ATTR_SEMANTIC_SOURCE_TYPE);
    if (semanticSource == null || type == null || type.isBlank()) {
      return;
    }
    if (!valid(semanticSource)) {
      throw roundTripInvalid(
          ATTR_SEMANTIC_SOURCE + " '" + echo(semanticSource) + "' is not a valid Dediren id",
          scan,
          cell);
    }
    // An absent label attribute means an empty label, not "borrow the container's": the export
    // writes this attribute whenever the element has a label at all, and guessing would rename an
    // element that was deliberately unlabelled.
    String label = attribute(cell, ATTR_SEMANTIC_SOURCE_LABEL);
    CarriedElement carried =
        new CarriedElement(roundTripDocument ? type : "generic.node", label(label));
    CarriedElement existing = carriedSemanticSources.putIfAbsent(semanticSource, carried);
    if (existing != null && !existing.equals(carried)) {
      throw roundTripInvalid(
          ATTR_SEMANTIC_SOURCE
              + " '"
              + echo(semanticSource)
              + "' is described with a different type or label on page "
              + scan.number
              + " than on an earlier page",
          scan,
          cell);
    }
    reservedElementIds.add(semanticSource);
  }

  /**
   * A semantic-boundary group whose {@code semanticSourceId} names nothing would reach {@code
   * SceneProjection} and fail there as an untyped I/O error, so it is caught here where the cell
   * that caused it can still be named.
   */
  private void checkSemanticSources(PageScan scan) throws EngineException {
    for (MxCell cell : scan.content) {
      if (!scan.isGroup(cell)
          || !DrawioIdentity.GROUP_ROLE_SEMANTIC.equals(attribute(cell, ATTR_GROUP_ROLE))) {
        // The export writes dedirenSemanticSourceId on nodes and edges too, as layout provenance
        // the source contract has no field for. Only a semantic-boundary group's copy is
        // load-bearing, because that is the one SceneProjection resolves.
        continue;
      }
      String semanticSource = attribute(cell, ATTR_SEMANTIC_SOURCE);
      if (semanticSource != null
          && !nodeDedirenIds.contains(semanticSource)
          && !carriedSemanticSources.containsKey(semanticSource)) {
        throw roundTripInvalid(
            "dedirenSemanticSourceId '"
                + echo(semanticSource)
                + "' names no node in the document: a boundary stands for an element, so it must"
                + " name a cell that maps to one, or carry that element's "
                + ATTR_SEMANTIC_SOURCE_TYPE
                + " itself",
            scan,
            cell);
      }
    }
  }

  private String claim(String preferred) {
    String base = valid(preferred) ? preferred : normalize(preferred);
    String candidate = base;
    int suffix = 2;
    while (!reservedElementIds.add(candidate)) {
      candidate = base + "-" + suffix++;
    }
    return candidate;
  }

  // ---------------------------------------------------------------- build

  private void buildPage(PageScan scan) throws EngineException {
    Map<String, Endpoint> local = new LinkedHashMap<>();
    List<String> viewNodes = new ArrayList<>();
    List<GenericGraphViewGroup> viewGroups = new ArrayList<>();
    List<MxCell> groupCells = new ArrayList<>();

    for (MxCell cell : scan.content) {
      recordDiscarded(cell);
      if (!cell.vertex() || scan.edgeLabelIds.contains(cell.id())) {
        continue;
      }
      String declared = attribute(cell, ATTR_ID);
      String elementId = declared != null ? declared : claim(cell.id());
      boolean group = scan.isGroup(cell);
      local.put(cell.id(), new Endpoint(elementId, group, attribute(cell, ATTR_SEMANTIC_SOURCE)));
      if (group) {
        groupCells.add(cell);
        continue;
      }
      viewNodes.add(elementId);
      addNode(scan, cell, elementId, declared != null);
    }

    for (MxCell cell : groupCells) {
      Endpoint mapped = local.get(cell.id());
      List<String> members =
          scan.childVertices.get(cell.id()).stream()
              .map(child -> local.get(child.id()))
              .filter(java.util.Objects::nonNull)
              .map(Endpoint::elementId)
              .toList();
      GenericGraphViewGroupRole role =
          DrawioIdentity.GROUP_ROLE_SEMANTIC.equals(attribute(cell, ATTR_GROUP_ROLE))
              ? GenericGraphViewGroupRole.SEMANTIC_BOUNDARY
              : GenericGraphViewGroupRole.LAYOUT_ONLY;
      viewGroups.add(
          new GenericGraphViewGroup(
              mapped.elementId(),
              label(cell, null),
              members,
              role,
              role == GenericGraphViewGroupRole.SEMANTIC_BOUNDARY
                  ? mapped.semanticSourceId()
                  : null));
      groupCount++;
    }

    List<String> viewRelationships = new ArrayList<>();
    for (MxCell cell : scan.content) {
      if (!cell.edge()) {
        continue;
      }
      String source = resolveEndpoint(scan, cell, local, ATTR_SOURCE, cell.source());
      String target = resolveEndpoint(scan, cell, local, ATTR_TARGET, cell.target());
      if (source == null || target == null) {
        droppedEdges.add(describe(scan, cell.id(), null));
        continue;
      }
      String declared = attribute(cell, ATTR_ID);
      String elementId = declared != null ? declared : claim(cell.id());
      viewRelationships.add(elementId);
      addRelationship(scan, cell, elementId, source, target, declared != null);
    }

    views.add(
        new GenericGraphView(
            viewId(scan),
            viewLabel(scan),
            scan.viewKind,
            viewNodes,
            viewRelationships,
            scan.layoutPreferences,
            viewGroups));
  }

  /**
   * Rebuilds the element a semantic boundary stands for, when no cell on any page drew it.
   *
   * <p>A <em>document</em> node, deliberately never a view node: the view lays out the boundary,
   * not a second box for the same element, and adding it to {@code views[].nodes} would make the
   * re-imported view differ from the one that was exported. A cell that drew the element wins over
   * this reconstruction — a drawn element is the file's own statement about it.
   */
  private void addCarriedSemanticSource(String semanticSourceId, CarriedElement carried) {
    if (nodes.containsKey(semanticSourceId)) {
      return;
    }
    nodes.put(
        semanticSourceId,
        new SourceNode(
            semanticSourceId,
            carried.type(),
            carried.label(),
            carriedPropertiesOf(semanticSourceId)));
  }

  private void addNode(PageScan scan, MxCell cell, String elementId, boolean identified)
      throws EngineException {
    String type =
        identified && roundTripDocument ? typeOrDefault(cell, "generic.node") : "generic.node";
    String label = label(cell, null);
    SourceNode existing = nodes.get(elementId);
    if (existing != null) {
      if (!existing.type().equals(type) || !existing.label().equals(label)) {
        throw roundTripInvalid(
            "dedirenId '"
                + echo(elementId)
                + "' carries a different type or label on page "
                + scan.number
                + " than on an earlier page",
            scan,
            cell);
      }
      return;
    }
    nodes.put(
        elementId,
        new SourceNode(elementId, type, label, nodeProperties(cell, elementId, identified)));
  }

  private void addRelationship(
      PageScan scan,
      MxCell cell,
      String elementId,
      String source,
      String target,
      boolean identified)
      throws EngineException {
    String type =
        identified && roundTripDocument ? typeOrDefault(cell, "generic.link") : "generic.link";
    MxCell labelCell = scan.edgeLabels.get(cell.id());
    String label = label(cell, labelCell);
    SourceRelationship existing = relationships.get(elementId);
    if (existing != null) {
      if (!existing.type().equals(type)
          || !existing.label().equals(label)
          || !existing.source().equals(source)
          || !existing.target().equals(target)) {
        throw roundTripInvalid(
            "dedirenId '"
                + echo(elementId)
                + "' carries different relationship detail on page "
                + scan.number
                + " than on an earlier page",
            scan,
            cell);
      }
      return;
    }
    Map<String, JsonNode> properties = carriedPropertiesOf(elementId);
    if (!identified && !elementId.equals(cell.id())) {
      properties.put(PROPERTY_KEY, originalId(cell.id()));
    }
    JsonNode sequence = messageSequence(cell);
    if (sequence != null) {
      // The edge wrapper's dedirenUmlSequence outranks the hidden map's uml.sequence: it is the
      // one of the two a human can see and edit in draw.io's Edit Data dialog, and silently
      // overruling what someone typed there is the worst surprise this lane could hold. They
      // agree unless a human changed one.
      JsonNode existingUml = properties.get("uml");
      ObjectNode uml =
          existingUml != null && existingUml.isObject()
              ? ((ObjectNode) existingUml).deepCopy()
              : JsonSupport.objectMapper().createObjectNode();
      uml.set("sequence", sequence);
      properties.put("uml", uml);
    }
    relationships.put(
        elementId, new SourceRelationship(elementId, type, source, target, label, properties));
  }

  /**
   * Resolves one edge end. A round-tripped edge names a {@code dedirenId}, which must exist —
   * silently dropping it would lose a relationship the exporter knew was real. A foreign edge names
   * a cell, and every way that can fail (no attribute, no such cell, a hidden cell, an edge, a
   * container that became a group) drops the edge instead.
   */
  private String resolveEndpoint(
      PageScan scan,
      MxCell edge,
      Map<String, Endpoint> local,
      String attributeName,
      String cellReference)
      throws EngineException {
    String declared = attribute(edge, attributeName);
    if (declared != null) {
      Endpoint endpoint = dedirenIndex.get(declared);
      if (endpoint == null) {
        throw roundTripInvalid(
            "edge endpoint '" + echo(declared) + "' names no dedirenId in the document",
            scan,
            edge);
      }
      return endpoint.group() ? endpoint.semanticSourceId() : endpoint.elementId();
    }
    if (cellReference == null || cellReference.isEmpty()) {
      return null;
    }
    Endpoint endpoint = local.get(cellReference);
    if (endpoint == null) {
      return null;
    }
    // An edge into a semantic-boundary group means the element that group stands for; an edge
    // into a layout-only group means nothing Dediren can express.
    return endpoint.group() ? endpoint.semanticSourceId() : endpoint.elementId();
  }

  /**
   * The round-tripped type, or the generic fallback. {@link DrawioIdentity#GROUP_TYPE} is a marker
   * for a container cell rather than a model type, so a container that lost every visible child and
   * is therefore mapped as a plain node must not inherit it.
   */
  private String typeOrDefault(MxCell cell, String fallback) {
    String declared = attribute(cell, ATTR_TYPE);
    if (declared == null || declared.isBlank() || DrawioIdentity.GROUP_TYPE.equals(declared)) {
      return fallback;
    }
    return declared;
  }

  private Map<String, JsonNode> nodeProperties(MxCell cell, String elementId, boolean identified) {
    Map<String, JsonNode> properties = carriedPropertiesOf(elementId);
    ObjectNode drawio = JsonSupport.objectMapper().createObjectNode();
    if (!identified && !elementId.equals(cell.id())) {
      drawio.put("original_id", cell.id());
    }
    DrawioTypeResolver.Stencil stencil =
        identified && roundTripDocument
            ? null
            : DrawioTypeResolver.recognize(parseStyle(cell.style()));
    if (stencil != null) {
      stencilTokens.merge(stencil.token(), 1, Integer::sum);
      ObjectNode node = drawio.putObject("stencil");
      node.put("style", stencil.token());
      if (stencil.suggestedType() != null) {
        node.put("suggested_type", stencil.suggestedType());
        suggestedStencils++;
      }
    }
    if (!drawio.isEmpty()) {
      properties.put(PROPERTY_KEY, drawio);
    }
    // Insertion-ordered, never Map.copyOf: an immutable map's iteration order is unspecified and
    // JVM-salted, and this map is re-serialised into the model, so copying it here would make the
    // exported artifact differ between two runs over the same input.
    return properties;
  }

  /**
   * A Message's restored {@code properties.uml.sequence}, or {@code null}.
   *
   * <p>mxGraph carries no element properties, so the export writes the one a model is invalid
   * without onto the edge's wrapper: {@code UmlSequenceValidation.validateMessageProperties}
   * rejects a Message that declares no positive integral ordering, and an export that dropped it
   * produced a file that re-imported green and failed the very next command. Restored only under
   * the UML profile — a {@code uml} property namespace in a generic-graph or ArchiMate model would
   * be exactly the smuggling {@link #typeOrDefault} exists to prevent.
   */
  private JsonNode messageSequence(MxCell cell) {
    if (profile != GenericGraphSemanticProfile.UML) {
      return null;
    }
    String declared = attribute(cell, ATTR_UML_SEQUENCE);
    if (declared == null) {
      return null;
    }
    try {
      java.math.BigInteger sequence = new java.math.BigInteger(declared.trim());
      return sequence.signum() > 0
          ? JsonSupport.objectMapper().getNodeFactory().numberNode(sequence)
          : null;
    } catch (NumberFormatException notAnOrdering) {
      // A hand-typed value is a hint, not a contract: the model simply keeps no ordering, and the
      // UML validator names the missing property at the path an author can act on.
      return null;
    }
  }

  private static ObjectNode originalId(String original) {
    ObjectNode drawio = JsonSupport.objectMapper().createObjectNode();
    drawio.put("original_id", original);
    return drawio;
  }

  private String viewId(PageScan scan) {
    if (scan.viewId != null) {
      return scan.viewId;
    }
    if (scan.number == 1 && reservedViewIds.add(MAIN_VIEW)) {
      // CoreCommands.renderImportedMain projects view "main", so page one must supply it or MCP
      // dediren_import with output "svg" has nothing to render.
      return MAIN_VIEW;
    }
    String base =
        scan.diagram.name() == null || scan.diagram.name().isBlank()
            ? "page-" + scan.number
            : normalize(scan.diagram.name());
    String candidate = MAIN_VIEW.equals(base) ? base + "-" + scan.number : base;
    int suffix = 2;
    while (!reservedViewIds.add(candidate)) {
      candidate = base + "-" + suffix++;
    }
    return candidate;
  }

  private static String viewLabel(PageScan scan) {
    return scan.diagram.name() == null || scan.diagram.name().isBlank()
        ? "Imported draw.io page " + scan.number
        : scan.diagram.name();
  }

  // ---------------------------------------------------------------- discarded hints

  /**
   * Counts what this import throws away, for {@link DiagnosticCode#DRAWIO_HINT_IGNORED}.
   *
   * <p><strong>A Dediren-authored cell's own style is not a discarded hint.</strong> On a
   * round-tripped document the exporter computed that style from this very model and will compute
   * it again, so nothing is lost and there is nothing to reapply. Reporting it anyway made the
   * warning fire on every import including Dediren's own artifact, which is how a diagnostic stops
   * carrying signal and starts training its reader past the ones that matter. A hand-drawn cell on
   * the same page still loses its appearance, and still says so.
   */
  private void recordDiscarded(MxCell cell) {
    if (roundTripDocument && attribute(cell, ATTR_ID) != null) {
      recordUnconsumedAttributes(cell);
      return;
    }
    Map<String, String> style = parseStyle(cell.style());
    // A recognized stencil is never an ignored hint: on the foreign path it is recorded as a
    // suggestion, and on the round-trip path it agrees with the dedirenType that was used instead.
    boolean stencilRecognized = DrawioTypeResolver.recognize(style) != null;
    for (String key : style.keySet()) {
      if (key.equals("html") || key.equals("edgeLabel")) {
        continue;
      }
      if (stencilRecognized
          && (key.equals("shape") || key.equals("appType") || key.equals("archiType"))) {
        continue;
      }
      discardedKeys.merge(key, 1, Integer::sum);
    }
    if (cell.object() != null) {
      cell.object().attributes().keySet().stream()
          .filter(key -> !CONSUMED_ATTRIBUTES.contains(key))
          .forEach(key -> discardedKeys.merge(key, 1, Integer::sum));
    }
    if (cell.geometry() != null) {
      discardedKeys.merge("mxGeometry", 1, Integer::sum);
      if (!cell.geometry().points().isEmpty()) {
        discardedKeys.merge("waypoints", 1, Integer::sum);
      }
    }
  }

  /**
   * The one thing still worth reporting on a Dediren-authored cell: an attribute nothing here
   * consumes. Geometry and style are the exporter's own and are recomputed, but an extra key in
   * draw.io's Edit Data dialog is a user's own data, and this import does not keep it.
   */
  private void recordUnconsumedAttributes(MxCell cell) {
    if (cell.object() == null) {
      return;
    }
    cell.object().attributes().keySet().stream()
        .filter(key -> !CONSUMED_ATTRIBUTES.contains(key))
        .forEach(key -> discardedKeys.merge(key, 1, Integer::sum));
  }

  // ---------------------------------------------------------------- diagnostics

  private List<Diagnostic> diagnostics() {
    List<Diagnostic> diagnostics = new ArrayList<>();
    if (!flattenedLayers.isEmpty()) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.DRAWIO_LAYERS_FLATTENED.code(),
              DiagnosticSeverity.WARNING,
              "draw.io layers are z-order, not containment, so these layers' cells were flattened"
                  + " into their page: "
                  + list(flattenedLayers)
                  + "; recreate the layering as Dediren view groups if you need it as structure",
              "$"));
    }
    if (!hiddenCells.isEmpty() || !droppedEdges.isEmpty()) {
      StringBuilder message = new StringBuilder();
      if (!hiddenCells.isEmpty()) {
        message
            .append("skipped ")
            .append(hiddenCells.size())
            .append(" hidden cell(s): ")
            .append(list(hiddenCells));
      }
      if (!droppedEdges.isEmpty()) {
        if (!message.isEmpty()) {
          message.append("; ");
        }
        message
            .append("dropped ")
            .append(droppedEdges.size())
            .append(" edge(s) whose endpoints resolve to no imported node: ")
            .append(list(droppedEdges))
            .append(" — reconnect them in draw.io and re-export");
      }
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.DRAWIO_CELLS_SKIPPED.code(),
              DiagnosticSeverity.WARNING,
              message.toString(),
              "$"));
    }
    if (!discardedKeys.isEmpty()) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.DRAWIO_HINT_IGNORED.code(),
              DiagnosticSeverity.WARNING,
              "ignored draw.io geometry, presentation, or routing keys: "
                  + counted(discardedKeys)
                  + "; Dediren re-lays every imported page out with ELK and carries appearance in"
                  + " render policy instead",
              "$"));
    }
    if (!stencilTokens.isEmpty()) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.DRAWIO_KIND_INFERRED.code(),
              DiagnosticSeverity.INFO,
              "recognized "
                  + stencilTokens.values().stream().mapToInt(Integer::intValue).sum()
                  + " stencil(s) ("
                  + suggestedStencils
                  + " with a suggested type): "
                  + counted(stencilTokens)
                  + "; semantic_profile stays generic-graph because draw.io encodes relationship"
                  + " semantics only as arrowhead decoration. To promote by hand: set"
                  + " plugins.generic-graph.semantic_profile to archimate, copy each"
                  + " properties.drawio.stencil.suggested_type onto that node's type, give every"
                  + " relationship a real ArchiMate type, then rerun dediren validate --profile"
                  + " archimate",
              "$"));
    }
    return List.copyOf(diagnostics);
  }

  private EngineException unsupported(String message, PageScan scan, MxCell cell) {
    return EngineException.structuralFailure(
        DiagnosticCode.DRAWIO_UNSUPPORTED_CONSTRUCT.code(), message, path(scan, cell));
  }

  private EngineException roundTripInvalid(String message, PageScan scan, MxCell cell) {
    return EngineException.structuralFailure(
        DiagnosticCode.DRAWIO_ROUND_TRIP_INVALID.code(), message, path(scan, cell));
  }

  /** {@code "page 2 (Notes), cell 'svc.ledger'"} — cells carry no line or column to report. */
  private static String path(PageScan scan, MxCell cell) {
    return describe(scan, cell.id(), null);
  }

  private static String describe(PageScan scan, String cellId, String name) {
    StringBuilder path = new StringBuilder("page ").append(scan.number);
    if (scan.diagram.name() != null && !scan.diagram.name().isBlank()) {
      path.append(" (").append(echo(scan.diagram.name())).append(')');
    }
    path.append(", cell '").append(echo(cellId)).append('\'');
    if (name != null && !name.isBlank()) {
      path.append(" (").append(echo(name)).append(')');
    }
    return path.toString();
  }

  private static String list(List<String> items) {
    String joined = items.stream().limit(LIST_LIMIT).collect(Collectors.joining(", "));
    return items.size() > LIST_LIMIT ? joined + ", … (" + items.size() + " total)" : joined;
  }

  private static String counted(Map<String, Integer> counts) {
    return counts.entrySet().stream()
        .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
        .collect(Collectors.joining(", "));
  }

  // ---------------------------------------------------------------- small helpers

  private static String attribute(MxCell cell, String name) {
    return cell.object() == null ? null : cell.object().attributes().get(name);
  }

  /** The cell's own value wins; a child label cell supplies the label only when it has none. */
  private static String label(MxCell cell, MxCell labelCell) {
    String value = cell.value();
    if ((value == null || value.isEmpty()) && labelCell != null) {
      value = labelCell.value();
    }
    return label(value);
  }

  /** The same {@code <br>} decode for a label that arrives as a wrapper attribute, not a cell. */
  private static String label(String value) {
    return value == null ? "" : SAFE_BR.matcher(value).replaceAll("\n");
  }

  /**
   * Splits a draw.io style string into its {@code key=value} pairs. A bare token (a style name such
   * as {@code text} or {@code edgeLabel}) becomes a key with an empty value, which is how the
   * format itself reads it.
   */
  private static Map<String, String> parseStyle(String style) {
    Map<String, String> parsed = new LinkedHashMap<>();
    if (style == null || style.isEmpty()) {
      return parsed;
    }
    for (String entry : style.split(";", -1)) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int separator = trimmed.indexOf('=');
      if (separator < 0) {
        parsed.putIfAbsent(trimmed, "");
      } else {
        parsed.putIfAbsent(trimmed.substring(0, separator), trimmed.substring(separator + 1));
      }
    }
    return parsed;
  }

  private static <E extends Enum<E>> E constant(Class<E> type, String wireName) {
    try {
      return Enum.valueOf(type, wireName.replace('-', '_').toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  private static boolean valid(String id) {
    return VALID_ID.matcher(id).matches();
  }

  private static String echo(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= ECHO_LIMIT ? value : value.substring(0, ECHO_LIMIT) + "…";
  }

  /**
   * The DOT and Mermaid importers' id normalizer, character for character: an imported id that
   * cannot be published as-is has to land on the same value whichever notation it arrived in.
   */
  private static String normalize(String original) {
    StringBuilder normalized = new StringBuilder();
    boolean separator = false;
    for (int index = 0; index < original.length(); ) {
      int codePoint = original.codePointAt(index);
      if (codePoint < 128
          && (Character.isLetterOrDigit(codePoint)
              || codePoint == '.'
              || codePoint == '_'
              || codePoint == '-')) {
        normalized.appendCodePoint(codePoint);
        separator = false;
      } else if (codePoint < 128) {
        if (!separator
            && !normalized.isEmpty()
            && normalized.charAt(normalized.length() - 1) != '-') {
          normalized.append('-');
        }
        separator = true;
      } else {
        if (normalized.isEmpty() || normalized.charAt(normalized.length() - 1) != '-') {
          normalized.append('-');
        }
        normalized.append('u').append(String.format(Locale.ROOT, "%04x", codePoint));
        separator = false;
      }
      index += Character.charCount(codePoint);
    }
    while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '-') {
      normalized.setLength(normalized.length() - 1);
    }
    if (normalized.isEmpty()) {
      normalized.append("node");
    }
    if (!Character.isLetterOrDigit(normalized.charAt(0))) {
      normalized.insert(0, "node-");
    }
    return normalized.toString();
  }
}

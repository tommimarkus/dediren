package dev.dediren.plugins.drawio.write;

/**
 * The {@code dediren*} attribute names the export writes onto every {@code <object>} wrapper, and
 * the two reserved {@code dedirenType} values that name a cell dediren owns rather than a model
 * element.
 *
 * <p><strong>Why constants rather than literals at the use sites.</strong> These names are the
 * whole identity contract between the two halves of the draw.io lane: the importer has to read back
 * exactly what the exporter wrote, and a one-character divergence produces a file that imports
 * cleanly and silently loses every model identity in it. A shared constant makes that agreement a
 * compile-time fact instead of a convention two packages have to keep in step by hand.
 *
 * <p>The names are camel-cased rather than snake-cased because draw.io's Edit Data dialog shows
 * them to a human verbatim, and every other attribute in the format is camel-cased.
 *
 * <p>The two reserved type values carry a dot, which no ArchiMate or UML type name contains, so a
 * dediren-owned cell can never be confused with an element whose type happens to be spelled the
 * same way.
 */
public final class DrawioIdentity {

  private DrawioIdentity() {}

  /** The laid-out element's own id — the key every other {@code dediren*} reference uses. */
  public static final String ID = "dedirenId";

  /**
   * The element's exact source type ({@code ApplicationComponent}, {@code Serving}, {@code Class},
   * …), or one of the reserved values below. Written even when no draw.io shape covers the type,
   * which is what keeps a re-import lossless after a fallback shape.
   */
  public static final String TYPE = "dedirenType";

  /** The semantic model element behind this cell, where the layout result names one. */
  public static final String SEMANTIC_SOURCE_ID = "dedirenSemanticSourceId";

  /**
   * The {@link #SEMANTIC_SOURCE_ID} element's own type, written on a semantic-boundary container.
   *
   * <p>A boundary stands for an element that frequently has no box of its own — a UML package drawn
   * only as its boundary is the standard shape, and its {@code semantic_source_id} is
   * contract-legal precisely because {@code SemanticsRouterEngine} resolves it against the
   * document's nodes rather than the view's. Without this pair the export names an id nothing in
   * the file declares, and Dediren cannot re-import its own artifact.
   */
  public static final String SEMANTIC_SOURCE_TYPE = "dedirenSemanticSourceType";

  /** The {@link #SEMANTIC_SOURCE_ID} element's own label; the container's label may differ. */
  public static final String SEMANTIC_SOURCE_LABEL = "dedirenSemanticSourceLabel";

  /**
   * A UML Message's {@code properties.uml.sequence}, the one model property mxGraph has no place
   * for that a model is invalid without: {@code UmlSequenceValidation} rejects a Message that
   * declares no ordering. Everything else under {@code properties} is disclosed as dropped rather
   * than carried, because a general property channel would put opaque JSON in front of a user in
   * draw.io's Edit Data dialog.
   */
  public static final String UML_SEQUENCE = "dedirenUmlSequence";

  /**
   * An edge's endpoints, keyed by {@link #ID} rather than by mxCell id: the editor is free to
   * reassign cell ids, and identity has to survive that.
   */
  public static final String SOURCE = "dedirenSource";

  /** An edge's target endpoint, keyed the same way {@link #SOURCE} is. */
  public static final String TARGET = "dedirenTarget";

  /** {@link #GROUP_ROLE_VISUAL} or {@link #GROUP_ROLE_SEMANTIC}, on a container cell. */
  public static final String GROUP_ROLE = "dedirenGroupRole";

  /** On the per-page metadata cell: the view this page was exported from. */
  public static final String VIEW_ID = "dedirenViewId";

  /** On the per-page metadata cell: the declared view kind, when the source declares one. */
  public static final String VIEW_KIND = "dedirenViewKind";

  /** On the per-page metadata cell: the source document's semantic profile. */
  public static final String SEMANTIC_PROFILE = "dedirenSemanticProfile";

  /** On the per-page metadata cell: the schema version the source document was written against. */
  public static final String MODEL_SCHEMA_VERSION = "dedirenModelSchemaVersion";

  /**
   * On the per-page metadata cell: the view's {@code layout_preferences}, as the model's own JSON.
   *
   * <p>Preferences decide the geometry, so losing them means a re-imported view lays out
   * differently from the one that was exported — the file's structure survives and its picture does
   * not, which no structural comparison can see. The block is nested and open-ended (direction,
   * density, routing, layering, crossing, placement, …), so it rides as the model's own JSON rather
   * than as a dozen flat attributes that would have to be kept in step by hand. It is the one place
   * JSON is acceptable here: this cell is hidden dediren metadata, not something a user meets in
   * draw.io's Edit Data dialog.
   */
  public static final String LAYOUT_PREFERENCES = "dedirenLayoutPreferences";

  /**
   * On the per-page metadata cell: the {@code properties} of every element the page carries, as a
   * JSON object keyed by element id.
   *
   * <p><strong>Why here and not on the element's own wrapper.</strong> A general {@code
   * dedirenProperties} attribute beside {@link #ID} was the obvious shape and is the wrong one:
   * draw.io's Edit Data dialog shows a wrapper's attributes to whoever right-clicks the shape, so
   * it would put raw model JSON in front of an ordinary user. This cell is hidden and has no
   * editing surface, which is the same reason {@link #LAYOUT_PREFERENCES} rides it.
   *
   * <p><strong>Why it has to exist at all.</strong> mxGraph has nowhere else to keep element
   * properties, and losing them is not cosmetic: a required UML ownership property ({@code
   * Port.component}, {@code ExtensionPoint.use_case}, {@code Transition.region}, {@code
   * ExecutionSpecification.covered}) makes the re-imported model invalid, and {@code
   * uml.attributes}/{@code uml.operations} decide how large a Class is drawn, so a model that
   * survives without them still comes back as a different picture.
   *
   * <p>Keys are sorted, so the attribute is a function of the model's content and not of the order
   * a layout result happened to list its elements in — which is what makes {@code export → import →
   * export} byte-identical. Values keep the model's own key order, because they are the model's own
   * JSON round-tripped rather than re-spelled.
   *
   * <p>Element ids are unique across nodes <em>and</em> relationships ({@code
   * SourceValidator.validateSourceDocument} checks both against one set), so one flat map needs no
   * node/relationship split to stay unambiguous.
   */
  public static final String ELEMENT_PROPERTIES = "dedirenElementProperties";

  /** {@link #TYPE} of the one hidden metadata cell each page carries. */
  public static final String VIEW_TYPE = "dediren.view";

  /** {@link #TYPE} of a container cell; a group has no model type of its own. */
  public static final String GROUP_TYPE = "dediren.group";

  /** A grouping that exists only to shape the picture. */
  public static final String GROUP_ROLE_VISUAL = "visual";

  /** A grouping that stands for a real model element (a package, a region, a boundary). */
  public static final String GROUP_ROLE_SEMANTIC = "semantic";
}

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

  /** {@link #TYPE} of the one hidden metadata cell each page carries. */
  public static final String VIEW_TYPE = "dediren.view";

  /** {@link #TYPE} of a container cell; a group has no model type of its own. */
  public static final String GROUP_TYPE = "dediren.group";

  /** A grouping that exists only to shape the picture. */
  public static final String GROUP_ROLE_VISUAL = "visual";

  /** A grouping that stands for a real model element (a package, a region, a boundary). */
  public static final String GROUP_ROLE_SEMANTIC = "semantic";
}

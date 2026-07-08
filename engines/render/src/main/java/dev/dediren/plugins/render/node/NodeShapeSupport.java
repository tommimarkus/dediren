package dev.dediren.plugins.render.node;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import java.util.Locale;

public final class NodeShapeSupport {

  private NodeShapeSupport() {}

  // Reviewed (ARCH-V-002, won't-fix): these compact glyphs deliberately place their
  // label diagonally up-left (see nodeLabelPosition) to keep it clear of the in/out
  // flows that enter and leave initial/final/decision/merge nodes. This is intentional
  // and differs from ArchiMate junction labels, which center below the circle.
  public static boolean umlCompactControlNodeLabelOutside(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.UML_INITIAL_NODE
        || decorator == SvgNodeDecorator.UML_ACTIVITY_FINAL_NODE
        || decorator == SvgNodeDecorator.UML_DECISION_NODE
        || decorator == SvgNodeDecorator.UML_MERGE_NODE;
  }

  public static boolean archimateJunctionLabelOutside(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION;
  }

  // Reviewed (ARCH-L-004, won't-fix): final states and pseudostates are intentionally
  // unlabeled. Unnamed final/initial pseudostates are valid UML, so these glyph-only
  // shapes suppress the plain label rather than rendering an empty or placeholder name.
  public static boolean shouldRenderPlainNodeLabel(LaidOutNode node, SvgNodeDecorator decorator) {
    return node.label() != null
        && !node.label().isEmpty()
        && !umlDecoratorSuppliesNodeLabel(decorator)
        && decorator != SvgNodeDecorator.UML_FINAL_STATE
        && decorator != SvgNodeDecorator.UML_PSEUDOSTATE;
  }

  public static boolean umlDecoratorSuppliesNodeLabel(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.UML_CLASS
        || decorator == SvgNodeDecorator.UML_INTERFACE
        || decorator == SvgNodeDecorator.UML_DATA_TYPE
        || decorator == SvgNodeDecorator.UML_ENUMERATION
        || decorator == SvgNodeDecorator.UML_ACTOR;
  }

  public static boolean isUmlDecorator(SvgNodeDecorator decorator) {
    return decorator != null && decorator.name().startsWith("UML_");
  }

  public static boolean hasArchimateCornerIcon(SvgNodeDecorator decorator) {
    return decorator != null
        && !isUmlDecorator(decorator)
        && decorator != SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        && decorator != SvgNodeDecorator.ARCHIMATE_OR_JUNCTION;
  }

  public static boolean isArchimateCutCornerRectangle(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.ARCHIMATE_STAKEHOLDER
        || decorator == SvgNodeDecorator.ARCHIMATE_DRIVER
        || decorator == SvgNodeDecorator.ARCHIMATE_ASSESSMENT
        || decorator == SvgNodeDecorator.ARCHIMATE_GOAL
        || decorator == SvgNodeDecorator.ARCHIMATE_OUTCOME
        || decorator == SvgNodeDecorator.ARCHIMATE_VALUE
        || decorator == SvgNodeDecorator.ARCHIMATE_MEANING
        || decorator == SvgNodeDecorator.ARCHIMATE_CONSTRAINT
        || decorator == SvgNodeDecorator.ARCHIMATE_REQUIREMENT
        || decorator == SvgNodeDecorator.ARCHIMATE_PRINCIPLE;
  }

  public static boolean isArchimateRoundedRectangle(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.ARCHIMATE_WORK_PACKAGE
        || decorator == SvgNodeDecorator.ARCHIMATE_IMPLEMENTATION_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_COURSE_OF_ACTION
        || decorator == SvgNodeDecorator.ARCHIMATE_VALUE_STREAM
        || decorator == SvgNodeDecorator.ARCHIMATE_CAPABILITY
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_SERVICE
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_FUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_PROCESS
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_FUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_PROCESS
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_SERVICE
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_FUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_PROCESS
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_INTERACTION
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_INTERACTION
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_INTERACTION;
  }

  public static final double ARCHIMATE_ICON_SIZE = 22.0;
  // Top inset of the corner type decorator from the node box top. Must match the
  // y origin used in archimateNodeDecorator; it feeds the label's vertical reserve.
  public static final double ARCHIMATE_ICON_TOP_INSET = 9.0;

  public static double archimateJunctionRadius(LaidOutNode node, ResolvedNodeStyle style) {
    return Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
  }

  public static String decoratorName(SvgNodeDecorator decorator) {
    return decorator.name().toLowerCase(Locale.ROOT);
  }
}

package dev.dediren.archimate;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Dediren-owned ArchiMate relationship-endpoint legality.
 *
 * <p>This is an original implementation of the ArchiMate language's functional relationship rules,
 * expressed over the element categories of the language's <em>generic metamodel</em> (ArchiMate 3.2
 * &sect;4, Figure 5) and the relationship semantics stated in &sect;5.1&ndash;5.4. It is not a
 * reproduction of the normative relationship tables in Appendix B.5: it copies none of that table's
 * selection, row/column ordering, grouping, or letter-code notation. It encodes the necessary
 * endpoint-category conditions each relationship carries &mdash; for example Access targets only
 * passive structure (&sect;5.2.2/5.2.5), Influence targets only motivation (&sect;5.2.3/5.2.5), the
 * dynamic relationships connect only behavior/active-structure elements (&sect;5.3), Specialization
 * holds between the same concept (&sect;5.4.2), and assignment/realization are directional over the
 * generic active-structure &rarr; behavior / concrete &rarr; abstract structure (&sect;5.1).
 *
 * <p>The check is deliberately a sound under-approximation: it does not compute the full derivation
 * closure of Appendix B, so a minority of invalid endpoint combinations are accepted rather than
 * rejected. It never rejects a valid combination. The {@code Association} relationship &mdash; the
 * language's "unspecified relationship" (&sect;5.2.4) &mdash; is always accepted, so any intended
 * but unusual link always has a legal expression. Grouping and Location, the generic composite
 * connectors, may attach to anything (&sect;5.5, Appendix B.6).
 */
final class RelationshipLegality {
  private RelationshipLegality() {}

  private enum Category {
    AS_INT, // internal active structure (actors, roles, components, nodes, resources, networks)
    AS_IFACE, // external active structure (interfaces)
    BEH, // internal behavior (processes, functions, interactions, capability, work package)
    SVC, // external behavior (services)
    EVT, // events
    PAS, // passive structure (objects, artifacts, data, deliverables)
    MOT, // motivation
    COMP // composite containers (grouping, location, plateau, product)
  }

  /**
   * Behavior/active-structure categories: the endpoints dependency/dynamic relationships accept.
   */
  private static final Set<Category> DYNAMIC =
      EnumSet.of(
          Category.AS_INT,
          Category.AS_IFACE,
          Category.BEH,
          Category.SVC,
          Category.EVT,
          Category.COMP);

  /** Grouping/Location connect to anything, so they short-circuit the category rules. */
  private static final Set<String> UNIVERSAL = Set.of("Grouping", "Location");

  private static final Map<String, Category> CATEGORY = buildCategories();

  private static final Map<Category, Set<Category>> ASSIGNMENT_TARGETS =
      new EnumMap<>(
          Map.of(
              Category.AS_INT,
                  EnumSet.of(
                      Category.AS_INT,
                      Category.AS_IFACE,
                      Category.BEH,
                      Category.SVC,
                      Category.EVT,
                      Category.PAS,
                      Category.MOT),
              Category.AS_IFACE, EnumSet.of(Category.AS_IFACE, Category.SVC),
              Category.BEH, EnumSet.of(Category.BEH),
              Category.COMP, EnumSet.of(Category.SVC, Category.PAS, Category.MOT, Category.COMP)));

  private static final Map<Category, Set<Category>> REALIZATION_TARGETS =
      new EnumMap<>(
          Map.of(
              Category.AS_INT,
                  EnumSet.of(
                      Category.AS_INT,
                      Category.AS_IFACE,
                      Category.BEH,
                      Category.SVC,
                      Category.EVT,
                      Category.MOT),
              Category.AS_IFACE,
                  EnumSet.of(
                      Category.AS_INT, Category.AS_IFACE, Category.BEH, Category.SVC, Category.MOT),
              Category.BEH,
                  EnumSet.of(
                      Category.AS_INT,
                      Category.AS_IFACE,
                      Category.BEH,
                      Category.SVC,
                      Category.EVT,
                      Category.PAS,
                      Category.MOT,
                      Category.COMP),
              Category.SVC, EnumSet.of(Category.BEH, Category.SVC, Category.MOT),
              Category.EVT, EnumSet.of(Category.EVT, Category.MOT),
              Category.PAS,
                  EnumSet.of(
                      Category.AS_INT,
                      Category.AS_IFACE,
                      Category.BEH,
                      Category.SVC,
                      Category.EVT,
                      Category.PAS,
                      Category.MOT,
                      Category.COMP),
              Category.MOT, EnumSet.of(Category.MOT),
              Category.COMP,
                  EnumSet.of(
                      Category.AS_INT,
                      Category.AS_IFACE,
                      Category.BEH,
                      Category.SVC,
                      Category.EVT,
                      Category.PAS,
                      Category.MOT,
                      Category.COMP)));

  /**
   * Returns whether {@code sourceType -[relationshipType]-> targetType} is a legal ArchiMate
   * endpoint combination. Callers validate that the three type names are supported before calling;
   * relationship-connector (junction) endpoints are handled by the caller and never reach here.
   */
  static boolean isAllowedEndpoint(String relationshipType, String sourceType, String targetType) {
    if (UNIVERSAL.contains(sourceType) || UNIVERSAL.contains(targetType)) {
      return true;
    }
    Category s = CATEGORY.get(sourceType);
    Category t = CATEGORY.get(targetType);
    if (s == null || t == null) {
      // A junction or otherwise unclassified type: connector and element-type handling upstream own
      // these cases, so do not reject here.
      return true;
    }
    return switch (relationshipType) {
      case "Association" -> true;
      case "Specialization" ->
          sourceType.equals(targetType) || isDefinedSpecialization(sourceType, targetType);
      case "Composition", "Aggregation" ->
          s == t || s == Category.COMP || (s == Category.AS_INT && t == Category.AS_IFACE);
      case "Assignment" -> ASSIGNMENT_TARGETS.getOrDefault(s, Set.of()).contains(t);
      case "Realization" -> REALIZATION_TARGETS.getOrDefault(s, Set.of()).contains(t);
      case "Serving" ->
          (DYNAMIC.contains(s) && DYNAMIC.contains(t)) || (s == Category.MOT && t == Category.MOT);
      case "Access" -> t == Category.PAS && DYNAMIC.contains(s);
      case "Influence" -> t == Category.MOT;
      case "Triggering", "Flow" -> DYNAMIC.contains(s) && DYNAMIC.contains(t);
      default -> true;
    };
  }

  /**
   * The two cross-type specializations the specification itself defines: a Contract is a
   * specialization of a Business Object (&sect;8) and a Constraint is a specialization of a
   * Requirement (&sect;6). Accepted in either direction.
   */
  private static boolean isDefinedSpecialization(String a, String b) {
    return (a.equals("Contract") && b.equals("BusinessObject"))
        || (a.equals("BusinessObject") && b.equals("Contract"))
        || (a.equals("Constraint") && b.equals("Requirement"))
        || (a.equals("Requirement") && b.equals("Constraint"));
  }

  private static Map<String, Category> buildCategories() {
    Map<String, Category> map = new java.util.HashMap<>();
    put(
        map,
        Category.AS_INT,
        "BusinessActor",
        "BusinessRole",
        "BusinessCollaboration",
        "ApplicationComponent",
        "ApplicationCollaboration",
        "Node",
        "Device",
        "SystemSoftware",
        "TechnologyCollaboration",
        "Equipment",
        "Facility",
        "Path",
        "CommunicationNetwork",
        "DistributionNetwork",
        "Resource");
    put(map, Category.AS_IFACE, "BusinessInterface", "ApplicationInterface", "TechnologyInterface");
    put(
        map,
        Category.BEH,
        "BusinessProcess",
        "BusinessFunction",
        "BusinessInteraction",
        "ApplicationFunction",
        "ApplicationInteraction",
        "ApplicationProcess",
        "TechnologyFunction",
        "TechnologyProcess",
        "TechnologyInteraction",
        "Capability",
        "CourseOfAction",
        "ValueStream",
        "WorkPackage");
    put(map, Category.SVC, "BusinessService", "ApplicationService", "TechnologyService");
    put(
        map,
        Category.EVT,
        "BusinessEvent",
        "ApplicationEvent",
        "TechnologyEvent",
        "ImplementationEvent");
    put(
        map,
        Category.PAS,
        "BusinessObject",
        "Contract",
        "Representation",
        "DataObject",
        "Artifact",
        "Material",
        "Deliverable",
        "Gap");
    put(
        map,
        Category.MOT,
        "Stakeholder",
        "Driver",
        "Assessment",
        "Goal",
        "Outcome",
        "Value",
        "Meaning",
        "Constraint",
        "Requirement",
        "Principle");
    put(map, Category.COMP, "Grouping", "Location", "Plateau", "Product");
    return Map.copyOf(map);
  }

  private static void put(Map<String, Category> map, Category category, String... types) {
    for (String type : types) {
      map.put(type, category);
    }
  }
}

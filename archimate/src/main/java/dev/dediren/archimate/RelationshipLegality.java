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
 * rejected. It never rejects a valid combination, with one documented exception: the small set of
 * combinations the Appendix-B tables derive as allowed but &sect;5 semantics contradict &mdash;
 * dynamic relationships (Triggering/Flow, &sect;5.3) touching a motivation or passive element, and
 * Assignment (&sect;5.1.3) from a passive, motivation, event, or service source &mdash; is
 * deliberately rejected. The conformance gate ({@code
 * ArchimateRelationshipLegalityConformanceTest}) asserts zero false negatives against a local
 * Appendix-B oracle except exactly that carve-out ({@code isSpecContradictedByFive}), which is the
 * authoritative list. The {@code Association} relationship &mdash; the language's "unspecified
 * relationship" (&sect;5.2.4) &mdash; is always accepted, so any intended but unusual link always
 * has a legal expression.
 *
 * <p>Grouping and Location, the generic composite connectors, are universal only in the conditional
 * sense Appendix B.6 states: they take part in a relationship when the other endpoint can itself
 * take part in it. Treating them as attaching to <em>anything</em> would bypass every rule below,
 * including the &sect;5-contradicted carve-out this class deliberately rejects.
 *
 * <p>Known residue, recorded rather than fixed: composition and aggregation are decided on category
 * rather than element type, and {@code Product} and {@code Plateau} share the composite category,
 * so some cross-layer containment passes. Narrowing either needs the &sect;B.4 domain dimension the
 * model does not carry, and narrowing without the Appendix-B oracle risks the false rejections this
 * class promises never to make.
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

  /**
   * The generic composite connectors (&sect;5.5, Appendix B.6). Their universality is
   * <em>conditional</em>: they take part in a relationship whenever the other endpoint can itself
   * take part in it, which is not the same as attaching to anything.
   */
  private static final Set<String> GENERIC_COMPOSITE = Set.of("Grouping", "Location");

  /**
   * The three §B.6 names whose aggregation or composition reaches any concept: grouping and
   * location by that section's first bullet, plateau by the same section's scope. Wider than {@link
   * #GENERIC_COMPOSITE}, which is about taking part in <em>any</em> relationship conditionally.
   */
  private static final Set<String> CONTAINMENT_COMPOSITE =
      Set.of("Grouping", "Location", "Plateau");

  private static final Map<String, Category> CATEGORY = buildCategories();

  /**
   * The four domains Appendix B.4 names, which cut across the element categories above.
   *
   * <p>Category answers "what kind of thing is this" (active structure, behavior, passive
   * structure…); domain answers "which part of the language does it belong to". B.4 restricts which
   * relationship types may cross between them, and those restrictions are invisible to a
   * category-only model &mdash; which is why a business process could serve a capability here.
   */
  private enum Domain {
    MOTIVATION,
    STRATEGY,
    /** Business, Application, Technology and Physical, plus location and grouping (&sect;B.4). */
    CORE,
    IMPLEMENTATION_MIGRATION
  }

  private static final Map<String, Domain> DOMAIN = buildDomains();

  /**
   * Assignment's allowed target categories per source category.
   *
   * <p>&sect;5.1.3 defines assignment as the allocation of responsibility, performance of behavior,
   * storage, or execution, and &sect;B.1 places it on the generic metamodel's active-structure
   * &rarr; behavior axis. The behavior-source row is deliberately narrower than &sect;5.1.3's
   * framework sentence, which also sanctions a behavior element assigned to passive structure; that
   * narrowing is recorded as known residue rather than claimed as the section's own rule.
   */
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
    boolean compositeSource = GENERIC_COMPOSITE.contains(sourceType);
    boolean compositeTarget = GENERIC_COMPOSITE.contains(targetType);
    if (compositeSource && compositeTarget) {
      return true;
    }
    if (compositeSource || compositeTarget) {
      return someElementCouldStandInPlaceOfTheComposite(
          relationshipType, sourceType, targetType, compositeSource);
    }
    return matchesCategoryRule(relationshipType, sourceType, targetType);
  }

  /**
   * Appendix B.6's condition, evaluated directly: a generic composite takes part in a relationship
   * when the <em>other</em> endpoint can itself take part in it.
   *
   * <p>Asking whether any element could stand in the composite's place is the same question, and
   * makes the rule a safe narrowing rather than a new source of rejections &mdash; the edge is
   * refused only when the relationship is impossible in that direction for every element there is,
   * which no substitution of a grouping or location could rescue. Access reaching a behavior
   * element, or a Flow leaving a motivation element, fail that way; a grouping accessing a data
   * object or composing an actor do not.
   */
  private static boolean someElementCouldStandInPlaceOfTheComposite(
      String relationshipType, String sourceType, String targetType, boolean compositeSource) {
    for (String candidate : CATEGORY.keySet()) {
      if (GENERIC_COMPOSITE.contains(candidate)) {
        continue;
      }
      boolean legal =
          compositeSource
              ? matchesCategoryRule(relationshipType, candidate, targetType)
              : matchesCategoryRule(relationshipType, sourceType, candidate);
      if (legal) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesCategoryRule(
      String relationshipType, String sourceType, String targetType) {
    Category s = CATEGORY.get(sourceType);
    Category t = CATEGORY.get(targetType);
    if (s == null || t == null) {
      // A junction or otherwise unclassified type: connector and element-type handling upstream own
      // these cases, so do not reject here. `categorizedTypes()` is pinned by the conformance test,
      // so an element type added without a category fails the build rather than arriving here.
      return true;
    }
    if (!allowsDomainCrossing(
        relationshipType, sourceType, DOMAIN.get(sourceType), DOMAIN.get(targetType))) {
      return false;
    }
    return switch (relationshipType) {
      case "Association" -> true;
      case "Specialization" ->
          sourceType.equals(targetType) || isDefinedSpecialization(sourceType, targetType);
      case "Composition", "Aggregation" -> allowsContainment(sourceType, targetType, s, t);
      case "Assignment" -> ASSIGNMENT_TARGETS.getOrDefault(s, Set.of()).contains(t);
      case "Realization" -> REALIZATION_TARGETS.getOrDefault(s, Set.of()).contains(t);
      case "Serving" ->
          (DYNAMIC.contains(s) && DYNAMIC.contains(t)) || (s == Category.MOT && t == Category.MOT);
      case "Access" -> t == Category.PAS && DYNAMIC.contains(s);
      case "Influence" -> t == Category.MOT;
      case "Triggering", "Flow" -> DYNAMIC.contains(s) && DYNAMIC.contains(t);
      // Fail closed: a relationship type added without an arm here is rejected everywhere, which is
      // loud, rather than made universally legal, which is silent. Callers validate the type name
      // before calling, so this arm is unreachable for the eleven the language defines.
      default -> false;
    };
  }

  /**
   * The two cross-type specializations the specification itself defines: a Contract is a
   * specialization of a Business Object (&sect;8.4.2) and a Constraint is a specialization of a
   * Requirement (&sect;6). Accepted in either direction.
   *
   * <p>Either direction looks wrong and is not. Those are <em>metamodel</em> statements &mdash;
   * Contract is a subtype of BusinessObject, and "the relationships that apply to a business object
   * also apply to a contract" (&sect;8.4.2) &mdash; not a restriction on which way a modeller may
   * draw the arrow. Appendix B.5 derives the pair through that inheritance and allows both
   * directions, because a specialization between a BusinessObject and a Contract <em>is</em>
   * &sect;5.4.2's same-type rule: a Contract counts as a Business Object.
   *
   * <p>Narrowed to one direction on 2026-08-12 and reverted the same day, when running the
   * Appendix-B oracle produced exactly two false negatives &mdash; both of them here. Reading the
   * arrow's direction off the prose was the mistake: legality asks which type pairs may be
   * connected, which metamodel inheritance makes symmetric, and that is a different question from
   * what the drawn relationship then asserts.
   */
  private static boolean isDefinedSpecialization(String a, String b) {
    return (a.equals("Contract") && b.equals("BusinessObject"))
        || (a.equals("BusinessObject") && b.equals("Contract"))
        || (a.equals("Constraint") && b.equals("Requirement"))
        || (a.equals("Requirement") && b.equals("Constraint"));
  }

  /**
   * The element types {@link #isAllowedEndpoint} can reason about. Exposed so the conformance test
   * can pin it against the language's element list: an element type added without a category would
   * otherwise make every relationship on it legal, silently and greenly.
   */
  static Set<String> categorizedTypes() {
    return CATEGORY.keySet();
  }

  /**
   * &sect;B.4's domain crossing restrictions, stated as the section states them: a crossing is
   * refused unless the relationship type is one the section names for that direction.
   *
   * <p>The section frames these as restrictions on <em>derivation</em>, which is why they are
   * applied here only to crossings and not to its passive-structure clauses &mdash; those would
   * reject directly-modelled edges §5.1 permits, such as a business object composed of business
   * objects. The crossings are safe to enforce directly because the relationship tables they
   * produce agree: a core element serving a strategy element is absent from them.
   */
  private static boolean allowsDomainCrossing(
      String relationshipType, String sourceType, Domain a, Domain b) {
    if (a == null || b == null || a == b) {
      return true;
    }
    // §B.6: grouping, location and plateau may aggregate or compose any concept, which is a
    // containment statement rather than a relationship between domains — a plateau collects the
    // goals and outcomes a state of the architecture reaches, across every domain there is.
    if (CONTAINMENT_COMPOSITE.contains(sourceType)
        && ("Composition".equals(relationshipType) || "Aggregation".equals(relationshipType))) {
      return true;
    }
    if (b == Domain.MOTIVATION) {
      return switch (relationshipType) {
        case "Assignment", "Realization", "Influence", "Association" -> true;
        default -> false;
      };
    }
    if (a == Domain.MOTIVATION) {
      return "Association".equals(relationshipType);
    }
    if (b == Domain.STRATEGY) {
      return "Realization".equals(relationshipType) || "Association".equals(relationshipType);
    }
    if (a == Domain.STRATEGY) {
      return "Association".equals(relationshipType);
    }
    if (a == Domain.IMPLEMENTATION_MIGRATION) {
      return "Realization".equals(relationshipType) || "Association".equals(relationshipType);
    }
    return "Assignment".equals(relationshipType) || "Association".equals(relationshipType);
  }

  /**
   * Which containments &sect;5.1.1, &sect;8.5.1 and &sect;B.6 allow.
   *
   * <p>Three different rules wear the same relationship. A grouping, location or plateau contains
   * any concept (&sect;B.6). A product has a <em>closed</em> list &mdash; business, application and
   * technology services, business and data objects, artifacts and material, and a contract
   * (&sect;8.5.1) &mdash; which in category terms is services and passive structure, and is why it
   * cannot be treated as a generic container. Everything else follows &sect;5.1.1: between
   * instances of the same element type, modelled here at category granularity because the tables
   * are wider than the sentence (a business process composed of business functions is legal).
   */
  private static boolean allowsContainment(
      String sourceType, String targetType, Category s, Category t) {
    if (CONTAINMENT_COMPOSITE.contains(sourceType)) {
      return true;
    }
    if ("Product".equals(sourceType)) {
      // §8.5.1's list is in addition to §5.1.1's same-type rule, not instead of it.
      return t == Category.SVC || t == Category.PAS || "Product".equals(targetType);
    }
    return s == t || (s == Category.AS_INT && t == Category.AS_IFACE);
  }

  private static Map<String, Domain> buildDomains() {
    Map<String, Domain> map = new java.util.HashMap<>();
    // Chapter 6.
    put(
        map,
        Domain.MOTIVATION,
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
    // Chapter 7.
    put(map, Domain.STRATEGY, "Resource", "Capability", "CourseOfAction", "ValueStream");
    // Chapter 12.
    put(
        map,
        Domain.IMPLEMENTATION_MIGRATION,
        "WorkPackage",
        "Deliverable",
        "ImplementationEvent",
        "Plateau",
        "Gap");
    // Everything else is Core: chapters 8-11 plus location and grouping, named by §B.4 itself.
    for (String type : CATEGORY.keySet()) {
      map.putIfAbsent(type, Domain.CORE);
    }
    return Map.copyOf(map);
  }

  private static void put(Map<String, Domain> map, Domain domain, String... types) {
    for (String type : types) {
      map.put(type, domain);
    }
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

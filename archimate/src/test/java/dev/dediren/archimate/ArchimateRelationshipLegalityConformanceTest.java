package dev.dediren.archimate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Spec-conformance guard for {@link Archimate#validateRelationshipEndpointTypes}. Unlike the
 * example-based cases in {@link ArchimateRelationshipRulesTest}, this class enforces ArchiMate 3.2
 * §5 relationship semantics as invariants over <em>every</em> element pair, so a future edit to the
 * legality rules that violates a §5 necessary condition fails the build.
 *
 * <p>The element categories below are declared here independently from the spec's layer summaries
 * (§8–§10, §6–§7) — they are not read from the (private) implementation — so the two must agree.
 * The invariants assert only spec-derived <em>necessary</em> conditions (e.g. Access targets
 * passive structure); over-restrictive regressions are caught instead by the example cases and the
 * opt-in oracle check ({@link #modelMatchesRelationshipTableOracle}).
 */
class ArchimateRelationshipLegalityConformanceTest {

  // Element categories from the ArchiMate 3.2 generic metamodel (§4) and layer summaries.
  private static final Set<String> ACTIVE_STRUCTURE =
      Set.of(
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
  private static final Set<String> INTERFACE =
      Set.of("BusinessInterface", "ApplicationInterface", "TechnologyInterface");
  private static final Set<String> BEHAVIOR =
      Set.of(
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
  private static final Set<String> SERVICE =
      Set.of("BusinessService", "ApplicationService", "TechnologyService");
  private static final Set<String> EVENT =
      Set.of("BusinessEvent", "ApplicationEvent", "TechnologyEvent", "ImplementationEvent");
  private static final Set<String> PASSIVE =
      Set.of(
          "BusinessObject",
          "Contract",
          "Representation",
          "DataObject",
          "Artifact",
          "Material",
          "Deliverable",
          "Gap");
  private static final Set<String> MOTIVATION =
      Set.of(
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
  private static final Set<String> UNIVERSAL = Set.of("Grouping", "Location");

  private static final List<String> RELATIONSHIPS =
      List.of(
          "Composition",
          "Aggregation",
          "Assignment",
          "Realization",
          "Specialization",
          "Serving",
          "Access",
          "Influence",
          "Flow",
          "Triggering",
          "Association");

  /** All ArchiMate element types except the relationship connectors (junctions). */
  private static List<String> elements() {
    List<String> elements = new ArrayList<>();
    for (String type : Archimate.elementTypes()) {
      if (!Archimate.isRelationshipConnectorType(type)) {
        elements.add(type);
      }
    }
    return elements;
  }

  private static boolean allowed(String relationship, String source, String target) {
    try {
      Archimate.validateRelationshipEndpointTypes(relationship, source, target, "$");
      return true;
    } catch (ArchimateTypeValidationException rejected) {
      return false;
    }
  }

  /** The category invariants only bind when neither endpoint is a universal connector. */
  private static boolean nonUniversalPair(String s, String t) {
    return !UNIVERSAL.contains(s) && !UNIVERSAL.contains(t);
  }

  // --- §5 necessary-condition invariants over every element pair -------------------------------

  @Test
  void everyCategorizedElementIsCoveredExactlyOnce() {
    // Guards the test's own categorization: the sets partition the 60 non-connector elements.
    List<String> elements = elements();
    for (String e : elements) {
      long hits =
          List.of(ACTIVE_STRUCTURE, INTERFACE, BEHAVIOR, SERVICE, EVENT, PASSIVE, MOTIVATION)
                  .stream()
                  .filter(set -> set.contains(e))
                  .count()
              + (UNIVERSAL.contains(e) || e.equals("Plateau") || e.equals("Product") ? 1 : 0);
      assertThat(hits).as("element %s must belong to exactly one spec category", e).isEqualTo(1);
    }
    assertThat(elements).hasSize(60);
  }

  @Test
  void associationConnectsEveryPair() {
    // §5.2.4: Association is the unspecified relationship — always legal.
    for (String s : elements()) {
      for (String t : elements()) {
        assertThat(allowed("Association", s, t))
            .as("Association %s -> %s must be legal", s, t)
            .isTrue();
      }
    }
  }

  @Test
  void accessAllowedImpliesPassiveTarget() {
    // §5.2.2 / §5.2.5: Access lets a behavior/active element act upon a PASSIVE structure element.
    forEachNonUniversalPair(
        (s, t) -> {
          if (allowed("Access", s, t)) {
            assertThat(PASSIVE)
                .as("Access %s -> %s allowed, so target must be passive structure", s, t)
                .contains(t);
          }
        });
  }

  @Test
  void influenceAllowedImpliesMotivationTarget() {
    // §5.2.3 / §5.2.5: Influence affects a MOTIVATION element.
    forEachNonUniversalPair(
        (s, t) -> {
          if (allowed("Influence", s, t)) {
            assertThat(MOTIVATION)
                .as("Influence %s -> %s allowed, so target must be a motivation element", s, t)
                .contains(t);
          }
        });
  }

  @Test
  void servingAllowedImpliesNonPassiveTarget() {
    // §5.2.1 / §5.2.5: an element serves another by providing functionality; a passive object is
    // accessed, never served.
    forEachNonUniversalPair(
        (s, t) -> {
          if (allowed("Serving", s, t)) {
            assertThat(PASSIVE)
                .as("Serving %s -> %s allowed, so target must not be passive structure", s, t)
                .doesNotContain(t);
          }
        });
  }

  @Test
  void dynamicRelationshipsConnectBehaviorOrActiveOnly() {
    // §5.3: Triggering and Flow are dynamic relationships between behavior/active-structure
    // elements; passive and motivation elements are never endpoints.
    forEachNonUniversalPair(
        (s, t) -> {
          for (String rel : List.of("Triggering", "Flow")) {
            if (allowed(rel, s, t)) {
              assertThat(PASSIVE)
                  .as("%s %s -> %s allowed, so neither endpoint may be passive", rel, s, t)
                  .doesNotContain(s)
                  .doesNotContain(t);
              assertThat(MOTIVATION)
                  .as("%s %s -> %s allowed, so neither endpoint may be motivation", rel, s, t)
                  .doesNotContain(s)
                  .doesNotContain(t);
            }
          }
        });
  }

  @Test
  void assignmentAllowedImpliesActiveOrBehaviorSource() {
    // §5.1.3: assignment allocates responsibility/behavior; its source is active structure or a
    // behavior collaboration/interaction — never passive, motivation, event, or service.
    forEachNonUniversalPair(
        (s, t) -> {
          if (allowed("Assignment", s, t)) {
            assertThat(PASSIVE).as("Assignment source %s must not be passive", s).doesNotContain(s);
            assertThat(MOTIVATION)
                .as("Assignment source %s must not be motivation", s)
                .doesNotContain(s);
            assertThat(EVENT).as("Assignment source %s must not be an event", s).doesNotContain(s);
          }
        });
  }

  @Test
  void specializationAllowedImpliesSameConceptOrDefinedPair() {
    // §5.4.2: specialization holds between the same concept; the spec also defines Contract as a
    // specialization of BusinessObject (§8) and Constraint of Requirement (§6).
    forEachNonUniversalPair(
        (s, t) -> {
          if (allowed("Specialization", s, t)) {
            boolean sameType = s.equals(t);
            boolean definedPair =
                !sameType
                    && (Set.of(s, t).equals(Set.of("Contract", "BusinessObject"))
                        || Set.of(s, t).equals(Set.of("Constraint", "Requirement")));
            assertThat(sameType || definedPair)
                .as("Specialization %s -> %s must be same-type or a spec-defined pair", s, t)
                .isTrue();
          }
        });
  }

  @Test
  void groupingAndLocationConnectToAnything() {
    // §5.5 / Appendix B.6: the generic composite connectors attach to any element.
    for (String other : elements()) {
      for (String rel : RELATIONSHIPS) {
        for (String universal : UNIVERSAL) {
          assertThat(allowed(rel, universal, other))
              .as("%s %s -> %s must be legal", rel, universal, other)
              .isTrue();
          assertThat(allowed(rel, other, universal))
              .as("%s %s -> %s must be legal", rel, other, universal)
              .isTrue();
        }
      }
    }
  }

  // --- Tier 2: opt-in check against a local ArchiMate relationship-table oracle -----------------

  /**
   * Verifies the model against a local, authoritative relationship-table oracle (derived from
   * ArchiMate 3.2 Appendix B.5), which is copyrighted and therefore never committed. Provide it
   * with {@code -Ddediren.archimate-oracle=<path>}, a UTF-8 file of allowed triples, one {@code
   * Source|Relationship|Target} per line. Asserts the two contract properties the metamodel model
   * must uphold: (1) it never rejects a triple the oracle allows, except the small set of
   * combinations §5 contradicts (motivation/passive dynamic edges; passive/motivation/event/service
   * assignment); (2) it still rejects at least {@link #CATCH_RATE_FLOOR} of the combinations the
   * oracle forbids. Skipped when the property is unset.
   */
  private static final double CATCH_RATE_FLOOR = 0.75;

  @Test
  void modelMatchesRelationshipTableOracle() throws IOException {
    String oraclePath = System.getProperty("dediren.archimate-oracle");
    assumeTrue(
        oraclePath != null && !oraclePath.isBlank(),
        "set -Ddediren.archimate-oracle=<allowed-triples file> to run the B.5 conformance check");

    Set<String> oracleAllowed =
        Set.copyOf(
            Files.readAllLines(Path.of(oraclePath)).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList());

    List<String> elements = elements();
    List<String> falseNegatives = new ArrayList<>();
    int caught = 0;
    int oracleForbidden = 0;
    for (String s : elements) {
      for (String t : elements) {
        for (String rel : RELATIONSHIPS) {
          boolean oracleOk = oracleAllowed.contains(s + "|" + rel + "|" + t);
          boolean modelOk = allowed(rel, s, t);
          if (oracleOk && !modelOk && !isSpecContradictedByFive(rel, s, t)) {
            falseNegatives.add(s + " -[" + rel + "]-> " + t);
          }
          if (!oracleOk) {
            oracleForbidden++;
            if (!modelOk) {
              caught++;
            }
          }
        }
      }
    }

    assertThat(falseNegatives)
        .as("model must never reject a spec-legal endpoint the oracle allows")
        .isEmpty();
    double catchRate = oracleForbidden == 0 ? 1.0 : (double) caught / oracleForbidden;
    assertThat(catchRate)
        .as(
            "model must reject at least %.0f%% of oracle-forbidden combinations",
            CATCH_RATE_FLOOR * 100)
        .isGreaterThanOrEqualTo(CATCH_RATE_FLOOR);
  }

  /**
   * The combinations the extracted oracle lists as allowed but ArchiMate 3.2 §5 semantics
   * contradict, which the model deliberately rejects: dynamic relationships (§5.3) touching a
   * motivation or passive element, and assignment (§5.1.3) from a passive, motivation, event, or
   * service element.
   */
  private static boolean isSpecContradictedByFive(String rel, String s, String t) {
    if (rel.equals("Triggering") || rel.equals("Flow")) {
      return MOTIVATION.contains(s)
          || MOTIVATION.contains(t)
          || PASSIVE.contains(s)
          || PASSIVE.contains(t);
    }
    if (rel.equals("Assignment")) {
      return PASSIVE.contains(s)
          || MOTIVATION.contains(s)
          || EVENT.contains(s)
          || SERVICE.contains(s);
    }
    return false;
  }

  private interface PairCheck {
    void check(String source, String target);
  }

  private static void forEachNonUniversalPair(PairCheck check) {
    List<String> elements = elements();
    for (String s : elements) {
      for (String t : elements) {
        if (nonUniversalPair(s, t)) {
          check.check(s, t);
        }
      }
    }
  }
}

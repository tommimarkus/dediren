package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.attr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves class-attribute type names to in-document {@code xmi:id} references so that no attribute
 * ships as a dangling type-name string (issue #33 defect 1).
 *
 * <p>Resolution is deterministic and offline: a type name is resolved to the {@code xmi:id} of a
 * classifier already emitted in this document when one carries that name; otherwise a resolvable
 * target is synthesized once and referenced by id. UML 2.5.1 standard primitives (with the common
 * source aliases {@code int} and {@code boolean}) become {@code uml:PrimitiveType}
 * packagedElements; every other unresolved name becomes a {@code uml:DataType} packagedElement.
 * Keeping the targets local (rather than {@code href}s into the OMG PrimitiveTypes library) keeps
 * the emitted document self-contained and validatable without network access.
 */
public final class TypeResolver {

  /** UML 2.5.1 PrimitiveTypes library names. */
  private static final Set<String> UML_PRIMITIVE_TYPES =
      Set.of("String", "Integer", "Boolean", "Real", "UnlimitedNatural");

  /** Common source spellings that denote a UML standard primitive. */
  private static final Map<String, String> PRIMITIVE_ALIASES =
      Map.of("int", "Integer", "boolean", "Boolean");

  private final IdentifierMap ids;
  private final Map<String, String> classifierIdByName;
  private final Map<String, SynthesizedType> synthesizedByKey = new LinkedHashMap<>();

  public TypeResolver(IdentifierMap ids, Map<String, String> classifierIdByName) {
    this.ids = ids;
    this.classifierIdByName = classifierIdByName;
  }

  /**
   * Returns an {@code xmi:id} that resolves the given attribute type name to an element in this
   * document, synthesizing a primitive or data type target on first use when needed.
   */
  public String resolve(String typeName) {
    String classifierId = classifierIdByName.get(typeName);
    if (classifierId != null) {
      return classifierId;
    }
    String canonical = PRIMITIVE_ALIASES.getOrDefault(typeName, typeName);
    if (UML_PRIMITIVE_TYPES.contains(canonical)) {
      return synthesize(
          "uml:PrimitiveType", "primitive:" + canonical, "primitive-" + canonical, canonical);
    }
    return synthesize("uml:DataType", "datatype:" + typeName, "datatype-" + typeName, typeName);
  }

  private String synthesize(String umlType, String key, String idSeed, String name) {
    SynthesizedType existing = synthesizedByKey.get(key);
    if (existing != null) {
      return existing.id();
    }
    String id = ids.xmiId(idSeed);
    synthesizedByKey.put(key, new SynthesizedType(umlType, id, name));
    return id;
  }

  /**
   * Appends the synthesized primitive and data type targets as {@code packagedElement}s in
   * first-use order. Callers must invoke this after every classifier attribute has been written so
   * that all referenced types are present.
   */
  public void writeSynthesizedTypes(StringBuilder xml) {
    for (SynthesizedType type : synthesizedByKey.values()) {
      xml.append("<packagedElement xmi:type=\"")
          .append(type.umlType())
          .append("\" xmi:id=\"")
          .append(attr(type.id()))
          .append("\" name=\"")
          .append(attr(type.name()))
          .append("\"/>");
    }
  }

  private record SynthesizedType(String umlType, String id, String name) {}
}

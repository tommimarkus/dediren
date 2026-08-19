package dev.dediren.engine;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

/**
 * Hardened XML reader construction for every engine that parses untrusted input. One
 * implementation, referenced from any engine that needs it, because engines may not depend on each
 * other and duplicated hardening rules drift — and a drifted XML reader is an XXE, not a cosmetic
 * inconsistency. The draw.io importer is the first consumer: {@code .drawio} files are
 * attacker-supplied XML.
 *
 * <p><strong>Every call returns a fresh factory.</strong> {@link XMLInputFactory} is not documented
 * as thread-safe and is mutable, so a shared instance could be reconfigured — including
 * un-hardened — by any holder. Callers create one per parse; the cost is negligible next to the
 * parse itself.
 *
 * <h2>What the configuration actually does</h2>
 *
 * <p>{@code SUPPORT_DTD = false} does <em>not</em> reject a document that carries a {@code
 * DOCTYPE}. On the JDK's StAX implementation the declaration is parsed and reported as a {@code
 * DTD} event, but its content is inert: nothing external is fetched and no entity is declared. The
 * refusal happens one step later, when the document <em>references</em> an entity the DTD would
 * have declared — the reader then fails the parse with "the entity was referenced, but not
 * declared". That covers internal-subset {@code SYSTEM} entities (classic XXE), external DTD
 * subsets, external parameter entities, and nested-entity expansion, and it refuses each of them
 * outright rather than bounding it the way the JDK's 64000-expansion limit does. The consequence
 * for callers: a DOCTYPE is not by itself an error, so an engine that wants to reject one must
 * check for the {@code DTD} event itself.
 *
 * <p>The built-in references — {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;}, {@code
 * &apos;} and numeric character references — keep working; {@code IS_REPLACING_ENTITY_REFERENCES =
 * false} suppresses only DTD-declared ones. draw.io labels and style strings depend on that.
 *
 * <p>{@code IS_COALESCING = true} is a functional requirement, not a defensive one: a compressed
 * draw.io {@code <diagram>} body is a single base64 blob that the decoder must receive as one
 * {@code CHARACTERS} event rather than reassemble across a CDATA or buffer boundary.
 *
 * <p>{@link XMLConstants#FEATURE_SECURE_PROCESSING} is <em>not supported</em> by the JDK's own
 * {@code XMLInputFactoryImpl}, which throws {@link IllegalArgumentException} for it, so on the
 * shipped runtime setting it is a no-op. It is applied only where the provider reports support, so
 * that a third-party StAX provider arriving through {@link java.util.ServiceLoader} is hardened
 * too. It is not load-bearing here, and the properties above are what actually close the holes.
 *
 * <p>The {@link javax.xml.stream.XMLResolver} is the second line, and mutation testing shows it is
 * a real one rather than decoration: in the configuration below the JDK reader never consults it,
 * but with {@code SUPPORT_DTD} re-enabled it is what refuses the external DTD subset and the
 * external parameter entity. {@code IS_SUPPORTING_EXTERNAL_ENTITIES} and {@code
 * IS_REPLACING_ENTITY_REFERENCES} are the weakest members of the set: {@code SUPPORT_DTD = false}
 * masks both, so no payload can tell whether they are set. They are kept as layered defence, not
 * because anything here depends on them.
 *
 * <p>No hardened {@code DocumentBuilderFactory} lives here: nothing on a production path needs DOM
 * parsing of untrusted input, and an unused factory would be an untested attack surface.
 */
public final class SecureXml {
  private SecureXml() {}

  /**
   * A fresh StAX reader factory that resolves nothing external, declares no entities, and delivers
   * each text run as a single event.
   */
  public static XMLInputFactory inputFactory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    if (factory.isPropertySupported(XMLConstants.FEATURE_SECURE_PROCESSING)) {
      factory.setProperty(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    }
    factory.setXMLResolver(
        (publicId, systemId, baseUri, namespace) -> {
          // The identifiers are attacker-controlled; they are deliberately not echoed.
          throw new XMLStreamException("external XML entity resolution is disabled");
        });
    return factory;
  }
}

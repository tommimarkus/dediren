package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial coverage for {@link SecureXml#inputFactory()}. Every hardening test also runs its
 * payload through a stock {@link XMLInputFactory#newFactory()} and asserts the attack succeeds
 * there, because a hardening test that would pass unhardened proves nothing.
 *
 * <p>Nothing here touches the network. The one payload that names an {@code http} system id routes
 * through a recording {@link XMLResolver} that stands in for the fetch, so the "an unhardened
 * reader really would go out for this" half is proved without a socket.
 */
class SecureXmlTest {
  private static final String CANARY = "XXE-CANARY-9137";

  /**
   * Ten references to a nine-character entity: 100 expansions, far under the JDK's 64000-expansion
   * ceiling, so a stock factory expands it happily. That is the point — it separates "refused
   * outright" from "bounded by the JDK limit".
   */
  private static final String MODEST_EXPANSION =
      "<?xml version=\"1.0\"?><!DOCTYPE lolz ["
          + "<!ENTITY lol \"lolololol\">"
          + "<!ENTITY lol1 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
          + "]><lolz>&lol1;</lolz>";

  @Test
  void refusesXxeLocalFileReadThatAStockFactoryPerforms(@TempDir Path directory)
      throws IOException, XMLStreamException {
    Path secret = Files.writeString(directory.resolve("secret.txt"), CANARY);
    String payload =
        "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \""
            + secret.toUri()
            + "\">]><foo>&xxe;</foo>";

    assertThat(textOf(XMLInputFactory.newFactory(), payload))
        .as("payload must be a real attack: a stock factory leaks the file")
        .contains(CANARY);

    assertThatThrownBy(() -> textOf(SecureXml.inputFactory(), payload))
        .isInstanceOf(XMLStreamException.class)
        .hasMessageNotContaining(CANARY);
  }

  @Test
  void neverFetchesAnExternalDtdSubset(@TempDir Path directory)
      throws IOException, XMLStreamException {
    Path dtd = Files.writeString(directory.resolve("evil.dtd"), "<!ENTITY xxe \"" + CANARY + "\">");
    String payload =
        "<?xml version=\"1.0\"?><!DOCTYPE foo SYSTEM \"" + dtd.toUri() + "\"><foo>&xxe;</foo>";

    assertThat(textOf(XMLInputFactory.newFactory(), payload))
        .as("a stock factory pulls in the external subset and expands what it declares")
        .contains(CANARY);

    assertThatThrownBy(() -> textOf(SecureXml.inputFactory(), payload))
        .isInstanceOf(XMLStreamException.class)
        .hasMessageNotContaining(CANARY);
  }

  @Test
  void refusesExternalParameterEntity(@TempDir Path directory)
      throws IOException, XMLStreamException {
    Path dtd = Files.writeString(directory.resolve("pe.dtd"), "<!ENTITY xxe \"" + CANARY + "\">");
    String payload =
        "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY % pe SYSTEM \""
            + dtd.toUri()
            + "\">%pe;]><foo>&xxe;</foo>";

    assertThat(textOf(XMLInputFactory.newFactory(), payload))
        .as("a stock factory loads the parameter entity and expands what it declares")
        .contains(CANARY);

    assertThatThrownBy(() -> textOf(SecureXml.inputFactory(), payload))
        .isInstanceOf(XMLStreamException.class)
        .hasMessageNotContaining(CANARY);
  }

  @Test
  void doesNotReachOutForAnHttpExternalDtdEvenWithAPermissiveResolver() throws XMLStreamException {
    // A recording resolver stands in for the network, so the test proves the reach-out without
    // making one. The stock arm shows an unhardened reader really does go out for this URL; the
    // hardened arm installs the same permissive resolver *over* SecureXml's refusing one, so a
    // pass proves the hardening does not depend on the resolver at all.
    String payload =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE mxfile SYSTEM \"http://127.0.0.1:1/evil.dtd\"><mxfile>&xxe;</mxfile>";

    List<String> stockLookups = new ArrayList<>();
    XMLInputFactory stock = XMLInputFactory.newFactory();
    stock.setXMLResolver(recordingResolver(stockLookups));
    assertThat(textOf(stock, payload)).contains(CANARY);
    assertThat(stockLookups).containsExactly("http://127.0.0.1:1/evil.dtd");

    List<String> hardenedLookups = new ArrayList<>();
    XMLInputFactory hardened = SecureXml.inputFactory();
    hardened.setXMLResolver(recordingResolver(hardenedLookups));
    assertThatThrownBy(() -> textOf(hardened, payload))
        .isInstanceOf(XMLStreamException.class)
        .hasMessageNotContaining(CANARY);
    assertThat(hardenedLookups).isEmpty();
  }

  @Test
  void refusesEntityExpansionOutrightRatherThanBoundingIt() throws XMLStreamException {
    assertThat(textOf(XMLInputFactory.newFactory(), MODEST_EXPANSION))
        .as("100 expansions sit under the JDK ceiling, so a stock factory expands them")
        .hasSize(90);

    assertThatThrownBy(() -> textOf(SecureXml.inputFactory(), MODEST_EXPANSION))
        .isInstanceOf(XMLStreamException.class);
  }

  @Test
  void refusesDeeplyNestedEntityExpansionQuickly() {
    StringBuilder payload =
        new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lolololol\">");
    for (int level = 1; level <= 9; level++) {
      payload.append("<!ENTITY lol").append(level).append(" \"");
      for (int reference = 0; reference < 10; reference++) {
        payload.append("&lol").append(level == 1 ? "" : String.valueOf(level - 1)).append(";");
      }
      payload.append("\">");
    }
    payload.append("]><lolz>&lol9;</lolz>");

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThatThrownBy(() -> textOf(SecureXml.inputFactory(), payload.toString()))
                .isInstanceOf(XMLStreamException.class));
  }

  @Test
  void coalescesTextSplitByACdataBoundaryIntoOneCharactersEvent() throws XMLStreamException {
    // Functional, not defensive: a compressed draw.io <diagram> body is one base64 blob the
    // decoder must receive whole.
    String payload = "<mxfile><diagram>AAA<![CDATA[BBB]]>CCC</diagram></mxfile>";

    assertThat(characterEventsOf(XMLInputFactory.newFactory(), payload))
        .as("a stock factory splits the body across events")
        .hasSizeGreaterThan(1);

    assertThat(characterEventsOf(SecureXml.inputFactory(), payload)).containsExactly("AAABBBCCC");
  }

  @Test
  void coalescesALargeBodySplitIntoManyChunks() throws XMLStreamException {
    StringBuilder payload = new StringBuilder("<mxfile><diagram>");
    StringBuilder expected = new StringBuilder();
    for (int chunk = 0; chunk < 50; chunk++) {
      payload.append("x".repeat(100)).append("<![CDATA[y]]>");
      expected.append("x".repeat(100)).append("y");
    }
    payload.append("</diagram></mxfile>");

    assertThat(characterEventsOf(SecureXml.inputFactory(), payload.toString()))
        .containsExactly(expected.toString());
  }

  @Test
  void parsesOrdinaryWellFormedXml() throws XMLStreamException {
    String document =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<mxfile host=\"app.diagrams.net\"><diagram id=\"a1\" name=\"Page-1\">"
            + "ZGF0YQ==</diagram></mxfile>";

    assertThat(textOf(SecureXml.inputFactory(), document)).isEqualTo("ZGF0YQ==");
  }

  @Test
  void stillResolvesPredefinedAndNumericCharacterReferences() throws XMLStreamException {
    // Turning off IS_REPLACING_ENTITY_REFERENCES must not cost the built-in references: draw.io
    // labels and style strings are full of &amp; and &lt;.
    assertThat(characterEventsOf(SecureXml.inputFactory(), "<r>a &amp; b &lt;c&gt; &#65;</r>"))
        .containsExactly("a & b <c> A");
  }

  @Test
  void carriesAnUnconditionallyRefusingResolverAsBeltAndBraces() {
    // With SUPPORT_DTD=false the JDK reader never consults the resolver, so this asserts the
    // resolver directly rather than pretending a payload reaches it.
    var resolver = SecureXml.inputFactory().getXMLResolver();

    assertThat(resolver).isNotNull();
    assertThatThrownBy(() -> resolver.resolveEntity(null, "http://evil.example/x.dtd", null, null))
        .isInstanceOf(XMLStreamException.class);
  }

  @Test
  void appliesSecureProcessingOnlyWhereTheStaxProviderSupportsIt() throws XMLStreamException {
    // The JDK's own XMLInputFactoryImpl rejects FEATURE_SECURE_PROCESSING outright; a third-party
    // provider picked up by ServiceLoader may accept it. Either way the factory must be usable.
    XMLInputFactory factory = SecureXml.inputFactory();

    if (factory.isPropertySupported(XMLConstants.FEATURE_SECURE_PROCESSING)) {
      assertThat(factory.getProperty(XMLConstants.FEATURE_SECURE_PROCESSING)).isEqualTo(true);
    }
    assertThat(textOf(factory, "<r>ok</r>")).isEqualTo("ok");
  }

  @Test
  void pinsTheDefenceInDepthPropertiesBehaviourCannotObserve() {
    // SUPPORT_DTD=false already masks these two: flipping either alone changes nothing any
    // payload can detect, so no behavioural test can pin them. They still matter as the second
    // line if DTD support is ever re-enabled, and this keeps them from being deleted as dead
    // configuration. The properties above them are covered behaviourally and are not repeated
    // here.
    XMLInputFactory factory = SecureXml.inputFactory();

    assertThat(factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES))
        .isEqualTo(false);
    assertThat(factory.getProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES))
        .isEqualTo(false);
  }

  @Test
  void returnsAFreshFactoryPerCall() {
    assertThat(SecureXml.inputFactory()).isNotSameAs(SecureXml.inputFactory());
  }

  private static XMLResolver recordingResolver(List<String> lookups) {
    return (publicId, systemId, baseUri, namespace) -> {
      lookups.add(systemId);
      return new ByteArrayInputStream(
          ("<!ENTITY xxe \"" + CANARY + "\">").getBytes(StandardCharsets.UTF_8));
    };
  }

  private static String textOf(XMLInputFactory factory, String xml) throws XMLStreamException {
    return String.join("", characterEventsOf(factory, xml));
  }

  private static List<String> characterEventsOf(XMLInputFactory factory, String xml)
      throws XMLStreamException {
    XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
    List<String> events = new ArrayList<>();
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
        events.add(reader.getText());
      }
    }
    reader.close();
    return events;
  }
}

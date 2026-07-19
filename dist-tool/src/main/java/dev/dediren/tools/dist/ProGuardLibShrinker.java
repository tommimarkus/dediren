package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

/**
 * Shrink ProGuard pass (no optimization, no renaming; the obfuscation phase runs only to strip
 * debugger-local attributes, with every name pinned — see bundle-shrink.pro) over the staged
 * launcher classpath, merged into one output jar. Keep rules live in the checked-in {@code
 * bundle-shrink.pro} resource next to this class; the rule-by-rule rationale is documented there.
 * {@link MergedJarPostProcessor} then unions ServiceLoader registrations, relocates embedded
 * licence files, and repacks entries STORED — all things a first-wins deflated merge would silently
 * get wrong.
 */
final class ProGuardLibShrinker implements LibShrinker {

  /**
   * Strips content that is dead outside Eclipse/OSGi hosts (UI icons, {@code .ecore}/{@code .xsd}
   * model sources, xtext {@code ._trace}, {@code .melk}, OSGi manifest extras and {@code OSGI-INF}
   * descriptors, Eclipse branding files, javadoc {@code package.html}, ELK doc pages), metadata for
   * platforms the plain-Java bundle never runs on (GraalVM {@code native-image}, Android {@code
   * META-INF/proguard}), multi-release variants, manifests and signature files that cannot survive
   * a merge, embedded licence files including {@code about*.html} (re-added collision-free by
   * {@link MergedJarPostProcessor}), and {@code META-INF/services} files (rebuilt as cross-jar
   * unions by the same post-processor — letting them through would only produce first-wins
   * duplicates ProGuard warns about).
   *
   * <p>Also drops runtime-reachable-but-provably-unused validator data: networknt's non-English
   * {@code jsv-messages_*.properties} (the base bundle stays and is the ResourceBundle fallback),
   * {@code ucd/**} Unicode data (read lazily by the idn-* format validators — no dediren schema or
   * MCP tool schema uses the {@code format} keyword, and schema-cache feeds only XML XSDs to
   * xmllint), and the draft-04/06/07/2019-09 meta-schemas (every dediren family is 2020-12). Adding
   * a {@code format} keyword to any packaged schema means putting {@code ucd/**} and the message
   * bundles back.
   */
  static final String INJAR_FILTER =
      "(!META-INF/MANIFEST.MF,!META-INF/*.SF,!META-INF/*.RSA,!META-INF/*.DSA,"
          + "!META-INF/LICENSE*,!META-INF/NOTICE*,!META-INF/services/**,!META-INF/eclipse.inf,"
          + "!module-info.class,!META-INF/versions/**,!META-INF/maven/**,!**.melk,!**._trace,"
          + "!images/**,!model/**,!schema/**,!**.png,!plugin.xml,!plugin.properties,!about.html,"
          + "!about_files/**,!about.ini,!about.properties,!about.mappings,"
          + "!.api_description,!.options,!profile.list,!systembundle.properties,"
          + "!jsv-messages_*.properties,!ucd/**,"
          + "!draft-04/**,!draft-06/**,!draft-07/**,!draft/2019-09/**,"
          + "!**/package.html,!OSGI-INF/**,!META-INF/native-image/**,"
          + "!META-INF/proguard/**,!about_*.html,!docs/**)";

  private static final String KEEP_RULES_RESOURCE = "bundle-shrink.pro";

  @Override
  public void shrink(List<Path> stagedJars, Path mergedJar) throws IOException {
    Path keepRules = Files.createTempFile("bundle-shrink", ".pro");
    try {
      try (var in = ProGuardLibShrinker.class.getResourceAsStream(KEEP_RULES_RESOURCE)) {
        if (in == null) {
          throw new IllegalStateException("missing keep-rules resource: " + KEEP_RULES_RESOURCE);
        }
        Files.copy(in, keepRules, StandardCopyOption.REPLACE_EXISTING);
      }
      String[] args = proGuardArgs(stagedJars, mergedJar, keepRules).toArray(String[]::new);
      Configuration configuration = new Configuration();
      try (ConfigurationParser parser = new ConfigurationParser(args, System.getProperties())) {
        parser.parse(configuration);
      }
      new ProGuard(configuration).execute();
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException("ProGuard shrink failed for " + mergedJar, error);
    } finally {
      Files.deleteIfExists(keepRules);
    }
    if (!Files.isRegularFile(mergedJar)) {
      throw new IOException("ProGuard reported success but wrote no jar at " + mergedJar);
    }
    MergedJarPostProcessor.apply(mergedJar, stagedJars);
  }

  /** Package-private for tests: full argument list, injars in launcher classpath order. */
  static List<String> proGuardArgs(List<Path> stagedJars, Path mergedJar, Path keepRules) {
    List<String> args = new ArrayList<>();
    for (Path jar : stagedJars) {
      args.add("-injars");
      args.add(jar + INJAR_FILTER);
    }
    args.add("-outjars");
    args.add(mergedJar.toString());
    args.add("-libraryjars");
    args.add("<java.home>/jmods(!**.jar;!module-info.class)");
    args.add("@" + keepRules);
    return args;
  }
}

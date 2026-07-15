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
 * Shrink-only ProGuard pass (no optimization, no obfuscation) over the staged launcher classpath,
 * merged into one output jar. Keep rules live in the checked-in {@code bundle-shrink.pro} resource
 * next to this class; the rule-by-rule rationale is documented there. {@link
 * MergedJarPostProcessor} then unions ServiceLoader registrations, relocates embedded licence
 * files, and repacks entries STORED — all things a first-wins deflated merge would silently get
 * wrong.
 */
final class ProGuardLibShrinker implements LibShrinker {

  /**
   * Strips content that is dead outside Eclipse/OSGi hosts (UI icons under {@code images/}, {@code
   * .ecore}/{@code .xsd} model sources, xtext {@code ._trace}, {@code .melk}, OSGi manifest
   * extras), multi-release variants, signature files that cannot survive a merge, and embedded
   * licence files (re-added collision-free by {@link MergedJarPostProcessor}).
   */
  static final String INJAR_FILTER =
      "(!META-INF/*.SF,!META-INF/*.RSA,!META-INF/*.DSA,!META-INF/LICENSE*,!META-INF/NOTICE*,"
          + "!module-info.class,!META-INF/versions/**,!META-INF/maven/**,!**.melk,!**._trace,"
          + "!images/**,!model/**,!schema/**,!plugin.xml,!plugin.properties,!about.html,"
          + "!about_files/**,!.api_description,!.options,!profile.list,!systembundle.properties)";

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

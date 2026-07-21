package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-artifact licence report that license-maven-plugin's {@code add-third-party} execution
 * (cli module, {@code resolved-licence-report}) generates from every runtime dependency's effective
 * pom, normalized through {@code licenseMerges}. Report entries look like
 *
 * <pre>  (EPL-2.0) EMF Common (org.eclipse.emf:org.eclipse.emf.common:2.45.0 - https://...)</pre>
 *
 * and are the resolution-side ground truth that {@link DistTool#writeThirdPartyNotices} diffs the
 * hand-curated attribution map against, so a dependency bump that changes an upstream licence fails
 * the dist lane instead of silently shipping a stale label.
 */
final class ResolvedLicenceReport {
  /** {@code (group:artifact:version - url)} tail of a report entry line. */
  private static final Pattern ENTRY_TAIL =
      Pattern.compile("\\(([^\\s:()]+):([^\\s:()]+):([^\\s()]+) - [^)]*\\)\\s*$");

  /** One leading {@code (Licence-Id)} group; canonical ids never contain parentheses. */
  private static final Pattern LEADING_LICENCE = Pattern.compile("^\\s*\\(([^()]+)\\)\\s*");

  record Entry(String groupId, String version, Set<String> licences) {}

  private final Map<String, Entry> byArtifactId;
  private final Path source;

  private ResolvedLicenceReport(Map<String, Entry> byArtifactId, Path source) {
    this.byArtifactId = byArtifactId;
    this.source = source;
  }

  static ResolvedLicenceReport parse(Path report) throws IOException {
    if (!Files.isRegularFile(report)) {
      throw new IllegalStateException(
          "resolved licence report missing at "
              + report
              + "; run the cli package phase (license-maven-plugin resolved-licence-report) first");
    }
    Map<String, Entry> entries = new LinkedHashMap<>();
    for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
      Matcher tail = ENTRY_TAIL.matcher(line);
      if (!tail.find()) {
        continue; // header / blank line
      }
      String groupId = tail.group(1);
      String artifactId = tail.group(2);
      String version = tail.group(3);
      Set<String> licences = new LinkedHashSet<>();
      String remainder = line.substring(0, tail.start());
      Matcher licence = LEADING_LICENCE.matcher(remainder);
      while (licence.find()) {
        licences.add(licence.group(1));
        remainder = remainder.substring(licence.end());
        licence = LEADING_LICENCE.matcher(remainder);
      }
      if (licences.isEmpty()) {
        throw new IllegalStateException(
            "resolved licence report entry without licences: '" + line.trim() + "' in " + report);
      }
      Entry previous = entries.put(artifactId, new Entry(groupId, version, licences));
      if (previous != null) {
        throw new IllegalStateException(
            "resolved licence report lists artifactId '"
                + artifactId
                + "' twice ("
                + previous.groupId()
                + " and "
                + groupId
                + "); jar-name keyed attribution cannot disambiguate — resolve the collision");
      }
    }
    if (entries.isEmpty()) {
      throw new IllegalStateException("resolved licence report has no entries: " + report);
    }
    return new ResolvedLicenceReport(entries, report);
  }

  /** The report entry for a staged jar's artifactId, or null when the report does not know it. */
  Entry entryFor(String artifactId) {
    return byArtifactId.get(artifactId);
  }

  Path source() {
    return source;
  }
}

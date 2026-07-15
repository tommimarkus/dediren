package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Merges the staged launcher classpath jars into the single bundle lib jar. Production uses {@link
 * ProGuardLibShrinker}; hermeticity tests inject a fake because their staged "jars" are text
 * fixtures that must never reach the real shrinker.
 */
interface LibShrinker {
  void shrink(List<Path> stagedJars, Path mergedJar) throws IOException;
}

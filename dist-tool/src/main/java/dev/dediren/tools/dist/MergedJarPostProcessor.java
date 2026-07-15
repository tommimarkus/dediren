package dev.dediren.tools.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Repairs what a first-wins jar merge cannot preserve. ServiceLoader registrations are unioned
 * across the original jars (filtered to classes that survived shrinking): ELK's layered algorithm
 * and the MCP SDK's JSON mapper/schema validator all register through {@code META-INF/services},
 * and losing a single line fails them at runtime, not at build time. Embedded licence texts are
 * copied from every original jar to {@code META-INF/third-party/<jar-name>/} so no jar's licence
 * file collides with another's (the injar filter keeps the originals out of the ProGuard output).
 * Every entry is rewritten STORED (uncompressed): the bundle archive's outer gzip then compresses
 * raw class bytes instead of already-deflated ones, which is worth ~1.7 MB on the shipped tarball
 * and measurably speeds classloading.
 */
final class MergedJarPostProcessor {
  private MergedJarPostProcessor() {}

  static void apply(Path mergedJar, List<Path> originalJars) throws IOException {
    Set<String> keptClasses = new HashSet<>();
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(mergedJar.toFile())) {
      var names = zip.entries();
      while (names.hasMoreElements()) {
        ZipEntry entry = names.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        byte[] data;
        try (var in = zip.getInputStream(entry)) {
          data = in.readAllBytes();
        }
        entries.put(entry.getName(), data);
        if (entry.getName().endsWith(".class")) {
          String className =
              entry
                  .getName()
                  .substring(0, entry.getName().length() - ".class".length())
                  .replace('/', '.');
          keptClasses.add(className);
        }
      }
    }

    Map<String, LinkedHashSet<String>> services = new TreeMap<>();
    Map<String, byte[]> licences = new TreeMap<>();
    for (Path original : originalJars) {
      String jarBase = original.getFileName().toString().replaceFirst("\\.jar$", "");
      try (ZipFile zip = new ZipFile(original.toFile())) {
        var names = zip.entries();
        while (names.hasMoreElements()) {
          ZipEntry entry = names.nextElement();
          if (entry.isDirectory()) {
            continue;
          }
          String name = entry.getName();
          if (name.startsWith("META-INF/services/")) {
            String content;
            try (var in = zip.getInputStream(entry)) {
              content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String line : content.split("\\R")) {
              String impl = line.split("#", 2)[0].strip();
              if (!impl.isEmpty() && keptClasses.contains(impl)) {
                services.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(impl);
              }
            }
          } else if (isLicenceFile(name)) {
            byte[] data;
            try (var in = zip.getInputStream(entry)) {
              data = in.readAllBytes();
            }
            String file = name.substring(name.lastIndexOf('/') + 1);
            licences.put("META-INF/third-party/" + jarBase + "/" + file, data);
          }
        }
      }
    }

    Path repacked = Files.createTempFile(mergedJar.getParent(), "merged-post", ".jar");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(repacked))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        if (entry.getKey().startsWith("META-INF/services/")) {
          continue; // replaced by the union below
        }
        writeEntry(out, entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, LinkedHashSet<String>> service : services.entrySet()) {
        writeEntry(
            out,
            service.getKey(),
            (String.join("\n", service.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
      }
      for (Map.Entry<String, byte[]> licence : licences.entrySet()) {
        writeEntry(out, licence.getKey(), licence.getValue());
      }
    }
    Files.move(repacked, mergedJar, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Root-level or META-INF-root licence artifacts; deeper paths are content, not licences. */
  private static boolean isLicenceFile(String name) {
    String file = name.substring(name.lastIndexOf('/') + 1);
    boolean metaInfRoot =
        name.startsWith("META-INF/") && name.indexOf('/', "META-INF/".length()) < 0;
    boolean atRoot = name.indexOf('/') < 0;
    return (metaInfRoot || atRoot)
        && (file.startsWith("LICENSE") || file.startsWith("NOTICE") || file.equals("about.html"));
  }

  /** STORED entries must carry size and CRC up front; java.util.zip enforces it. */
  private static void writeEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(data.length);
    CRC32 crc = new CRC32();
    crc.update(data);
    entry.setCrc(crc.getValue());
    out.putNextEntry(entry);
    out.write(data);
    out.closeEntry();
  }
}

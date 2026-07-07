package dev.dediren.tools.dist;

import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class DistTool {
  private static final String BUNDLE_METADATA_TARGET = "java";

  /**
   * Matches {@code "$REPO"/some-artifact-1.2.3.jar} segments of the CLASSPATH line that
   * appassembler generates into each launcher script (flat repository layout). That line is the
   * authoritative resolved runtime dependency set for the launcher, so the packaged {@code lib/} is
   * verified against it (SEED-1 hermeticity guard).
   */
  private static final Pattern CLASSPATH_LIB_JAR = Pattern.compile("\\$REPO\"?/([^:/\"]+\\.jar)");

  private static final List<String> EXPECTED_LAUNCHER_FLAGS =
      List.of(
          "-XX:TieredStopAtLevel=1",
          "-XX:+UseSerialGC",
          // UTF-8 stream encoding: core spawns plugin children with a stripped environment, so the
          // launcher itself must force UTF-8 or non-ASCII output is mangled to '?' (issue #47).
          "-Dstdout.encoding=UTF-8",
          "-Dstderr.encoding=UTF-8",
          "-Dfile.encoding=UTF-8");
  private static final List<Launcher> LAUNCHERS =
      List.of(
          new Launcher("cli/target/appassembler", "cli", "dediren", null),
          new Launcher(
              "plugins/generic-graph/target/appassembler",
              "generic-graph",
              "dediren-plugin-generic-graph",
              "generic-graph"),
          new Launcher(
              "plugins/elk-layout/target/appassembler",
              "elk-layout",
              "dediren-plugin-elk-layout",
              "elk-layout"),
          new Launcher(
              "plugins/render/target/appassembler", "render", "dediren-plugin-render", "render"),
          new Launcher(
              "plugins/archimate-oef-export/target/appassembler",
              "archimate-oef-export",
              "dediren-plugin-archimate-oef-export",
              "archimate-oef"),
          new Launcher(
              "plugins/uml-xmi-export/target/appassembler",
              "uml-xmi-export",
              "dediren-plugin-uml-xmi-export",
              "uml-xmi"));
  private static final List<String> CLEAN_ENV =
      List.of(
          "DEDIREN_CDS_DIR",
          "DEDIREN_TRUST_MANIFEST_CAPABILITIES",
          "DEDIREN_PLUGIN_DIRS",
          "DEDIREN_PLUGIN_GENERIC_GRAPH",
          "DEDIREN_PLUGIN_ELK_LAYOUT",
          "DEDIREN_PLUGIN_RENDER",
          "DEDIREN_PLUGIN_ARCHIMATE_OEF",
          "DEDIREN_PLUGIN_UML_XMI");

  /** Licence attribution for one redistributed third-party artifact (jar name minus version). */
  private record ThirdPartyAttribution(String project, List<String> licenseIds) {}

  private static ThirdPartyAttribution attribution(String project, String... licenseIds) {
    return new ThirdPartyAttribution(project, List.of(licenseIds));
  }

  private static final Set<String> FIRST_PARTY_ARTIFACTS =
      Set.of(
          "archimate",
          "archimate-oef-export",
          "cli",
          "contracts",
          "core",
          "elk-layout",
          "generic-graph",
          "render",
          "schema-cache",
          "uml",
          "uml-xmi-export");

  private static final Map<String, ThirdPartyAttribution> THIRD_PARTY_ATTRIBUTIONS =
      Map.ofEntries(
          Map.entry("batik-anim", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-awt-util", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-bridge", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-codec", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-constants", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-css", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-dom", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-ext", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-gvt", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-i18n", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-parser", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-script", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-shared-resources", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-svg-dom", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-svggen", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-transcoder", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-util", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("batik-xml", attribution("Apache Batik", "Apache-2.0")),
          Map.entry("commons-io", attribution("Apache Commons IO", "Apache-2.0")),
          Map.entry("commons-logging", attribution("Apache Commons Logging", "Apache-2.0")),
          Map.entry(
              "error_prone_annotations",
              attribution("Google Error Prone annotations", "Apache-2.0")),
          Map.entry("failureaccess", attribution("Google Guava failureaccess", "Apache-2.0")),
          Map.entry("guava", attribution("Google Guava", "Apache-2.0")),
          Map.entry("itu", attribution("Ethlo Internet Time Utility", "Apache-2.0")),
          Map.entry("j2objc-annotations", attribution("Google J2ObjC annotations", "Apache-2.0")),
          Map.entry("jackson-annotations", attribution("FasterXML Jackson", "Apache-2.0")),
          Map.entry("jackson-core", attribution("FasterXML Jackson", "Apache-2.0")),
          Map.entry("jackson-databind", attribution("FasterXML Jackson", "Apache-2.0")),
          Map.entry("jackson-dataformat-yaml", attribution("FasterXML Jackson", "Apache-2.0")),
          Map.entry(
              "json-schema-validator",
              attribution("NetworkNT JSON Schema Validator", "Apache-2.0")),
          Map.entry("jspecify", attribution("JSpecify", "Apache-2.0")),
          Map.entry("listenablefuture", attribution("Google Guava listenablefuture", "Apache-2.0")),
          Map.entry(
              "org.eclipse.elk.alg.common", attribution("Eclipse Layout Kernel (ELK)", "EPL-2.0")),
          Map.entry(
              "org.eclipse.elk.alg.layered", attribution("Eclipse Layout Kernel (ELK)", "EPL-2.0")),
          Map.entry(
              "org.eclipse.elk.alg.rectpacking",
              attribution("Eclipse Layout Kernel (ELK)", "EPL-2.0")),
          Map.entry("org.eclipse.elk.core", attribution("Eclipse Layout Kernel (ELK)", "EPL-2.0")),
          Map.entry("org.eclipse.elk.graph", attribution("Eclipse Layout Kernel (ELK)", "EPL-2.0")),
          Map.entry(
              "org.eclipse.emf.common", attribution("Eclipse Modeling Framework (EMF)", "EPL-1.0")),
          Map.entry(
              "org.eclipse.emf.ecore", attribution("Eclipse Modeling Framework (EMF)", "EPL-1.0")),
          Map.entry(
              "org.eclipse.emf.ecore.xmi",
              attribution("Eclipse Modeling Framework (EMF)", "EPL-1.0")),
          Map.entry("org.eclipse.xtext.xbase.lib", attribution("Eclipse Xtext", "EPL-2.0")),
          Map.entry("picocli", attribution("picocli", "Apache-2.0")),
          Map.entry("slf4j-api", attribution("SLF4J", "MIT (SLF4J)")),
          Map.entry("snakeyaml-engine", attribution("SnakeYAML Engine", "Apache-2.0")),
          Map.entry(
              "xml-apis",
              attribution(
                  "Apache XML Commons xml-apis",
                  "Apache-2.0",
                  "SAX (public domain)",
                  "W3C Software License")),
          Map.entry(
              "xml-apis-ext",
              attribution(
                  "Apache XML Commons xml-apis-ext",
                  "Apache-2.0",
                  "SAX (public domain)",
                  "W3C Software License")),
          Map.entry(
              "xmlgraphics-commons", attribution("Apache XML Graphics Commons", "Apache-2.0")));

  /**
   * Bundled verbatim licence texts appended to the generated notices. Every licence id used in
   * THIRD_PARTY_ATTRIBUTIONS must have a text here; a new dependency under a new licence must add
   * its canonical text before it can ship.
   */
  private static final Map<String, String> LICENSE_TEXT_RESOURCES =
      Map.of(
          "Apache-2.0", "licenses/apache-2.0.txt",
          "EPL-2.0", "licenses/epl-2.0.txt",
          "EPL-1.0", "licenses/epl-1.0.txt",
          "MIT (SLF4J)", "licenses/mit-slf4j.txt",
          "SAX (public domain)", "licenses/sax.txt",
          "W3C Software License", "licenses/w3c-software.txt");

  private DistTool() {}

  public static void main(String[] args) throws Exception {
    int exitCode = run(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      return 2;
    }
    Map<String, String> options = parseOptions(args);
    Path root = Path.of(required(options, "root")).toAbsolutePath().normalize();
    return switch (args[0]) {
      case "notices" -> {
        Path output = Path.of(required(options, "output")).toAbsolutePath().normalize();
        writeThirdPartyNotices(root, output);
        yield 0;
      }
      case "build" -> {
        String version = required(options, "version");
        rejectRetiredTargetOption(options);
        Path notices = Path.of(required(options, "notices")).toAbsolutePath().normalize();
        build(root, version, notices);
        yield 0;
      }
      case "smoke" -> {
        String version = required(options, "version");
        rejectRetiredTargetOption(options);
        Path archive =
            options.containsKey("archive")
                ? Path.of(options.get("archive"))
                : root.resolve("dist").resolve(bundleName(version) + ".tar.gz");
        smoke(root, archive.toAbsolutePath().normalize(), version);
        yield 0;
      }
      case "bench" -> {
        String version = required(options, "version");
        int runs = options.containsKey("runs") ? Integer.parseInt(options.get("runs")) : 5;
        Path archive =
            options.containsKey("archive")
                ? Path.of(options.get("archive"))
                : root.resolve("dist").resolve(bundleName(version) + ".tar.gz");
        bench(root, archive.toAbsolutePath().normalize(), runs);
        yield 0;
      }
      default -> {
        usage();
        yield 2;
      }
    };
  }

  private static void build(Path root, String version, Path notices) throws Exception {
    build(root, version, notices, staged -> {});
  }

  /**
   * Staging seam (package-private for tests): {@code afterStage} runs against the fully staged
   * bundle directory immediately before it is archived. It lets a test inject the runtime-generated
   * residue a launcher writes at first run — {@code cds/*.jsa} under {@code
   * $DEDIREN_BUNDLE_ROOT/cds}, a sibling of {@code lib/} — so the archive's {@code --exclude=cds}
   * and {@code --exclude=*.jsa} hermeticity filters are actually exercised. Production callers pass
   * a no-op; the seam runs after all staging and metadata writes, so it cannot perturb the packaged
   * {@code lib/} verification.
   */
  static void build(
      Path root, String version, Path notices, java.util.function.Consumer<Path> afterStage)
      throws Exception {
    Path dist = root.resolve("dist");
    Path bundle = dist.resolve(bundleName(version));
    Path archive = dist.resolve(bundle.getFileName() + ".tar.gz");

    deleteIfExists(bundle);
    Files.deleteIfExists(archive);
    Files.createDirectories(bundle.resolve("bin"));
    Files.createDirectories(bundle.resolve("lib"));
    Files.createDirectories(bundle.resolve("plugins"));
    Files.createDirectories(bundle.resolve("docs"));

    Set<String> declaredLibJars = new TreeSet<>();
    for (Launcher launcher : LAUNCHERS) {
      declaredLibJars.addAll(installLauncher(root, bundle, launcher));
    }
    verifyPackagedLib(bundle.resolve("lib"), declaredLibJars);
    copyManifestFiles(root.resolve("fixtures/plugins"), bundle.resolve("plugins"));
    copyDirectory(root.resolve("schemas"), bundle.resolve("schemas"));
    copyFixtures(root.resolve("fixtures"), bundle.resolve("fixtures"));
    Files.copy(
        root.resolve("docs/agent-usage.md"),
        bundle.resolve("docs/agent-usage.md"),
        StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        root.resolve("docs/plugin-authoring.md"),
        bundle.resolve("docs/plugin-authoring.md"),
        StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        root.resolve("LICENSE"), bundle.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        notices, bundle.resolve("THIRD-PARTY-NOTICES.md"), StandardCopyOption.REPLACE_EXISTING);
    writeBundleMetadata(bundle, version);
    // Test seam: inject runtime-generated CDS residue into the staged tree just before archiving.
    afterStage.accept(bundle);

    runCommand(
        root,
        List.of(
            "tar",
            // Runtime-generated CDS content must never ship in the archive
            // (SEED-1): launchers auto-create cds/*.jsa next to the bundle at
            // first run, and stale copies must not ride into a rebuild.
            "--exclude=cds",
            "--exclude=*.jsa",
            "-C",
            dist.toString(),
            "-czf",
            archive.toString(),
            bundle.getFileName().toString()),
        null);
    pruneStaleArtifacts(dist, bundle.getFileName().toString());
    System.out.println(archive);
  }

  private static void writeThirdPartyNotices(Path root, Path output) throws IOException {
    Set<String> jars = new java.util.TreeSet<>();
    for (Launcher launcher : LAUNCHERS) {
      Path lib = root.resolve(launcher.installDir()).resolve("lib");
      if (!Files.isDirectory(lib)) {
        continue;
      }
      try (var entries = Files.list(lib)) {
        entries
            .filter(path -> path.getFileName().toString().endsWith(".jar"))
            .map(path -> path.getFileName().toString())
            .forEach(jars::add);
      }
    }
    Set<String> firstParty = new TreeSet<>();
    Map<String, ThirdPartyAttribution> thirdParty = new TreeMap<>();
    List<String> unattributed = new ArrayList<>();
    for (String jar : jars) {
      String artifact = jarArtifactId(jar);
      if (FIRST_PARTY_ARTIFACTS.contains(artifact)) {
        firstParty.add(jar);
      } else {
        ThirdPartyAttribution attribution = THIRD_PARTY_ATTRIBUTIONS.get(artifact);
        if (attribution == null) {
          unattributed.add(jar);
        } else {
          thirdParty.put(jar, attribution);
        }
      }
    }
    if (!unattributed.isEmpty()) {
      throw new IllegalStateException(
          "redistributed jars without licence attribution: "
              + unattributed
              + "; add DistTool.THIRD_PARTY_ATTRIBUTIONS (or FIRST_PARTY_ARTIFACTS) entries");
    }
    Files.createDirectories(output.getParent());
    StringBuilder notice = new StringBuilder();
    notice.append("# Third-Party Notices\n\n");
    notice.append("Dediren's own modules and launchers are covered by the bundle root\n");
    notice.append("LICENSE file (MIT License).\n\n");
    notice.append("## First-Party Jars\n\n");
    for (String jar : firstParty) {
      notice.append("- ").append(jar).append('\n');
    }
    notice.append('\n');
    notice.append("## Redistributed Third-Party Libraries\n\n");
    notice.append("Each launcher `lib/` directory redistributes the libraries below in\n");
    notice.append("unmodified object form. Every library remains covered by its upstream\n");
    notice.append("licence, identified per jar as `(project, licence)`. Licence and notice\n");
    notice.append("files embedded inside the jars (`META-INF/LICENSE`, `META-INF/NOTICE`,\n");
    notice.append("`about.html`) are redistributed unchanged and remain authoritative for\n");
    notice.append("their jar.\n\n");
    Set<String> licenseIds = new TreeSet<>();
    for (Map.Entry<String, ThirdPartyAttribution> entry : thirdParty.entrySet()) {
      ThirdPartyAttribution attribution = entry.getValue();
      licenseIds.addAll(attribution.licenseIds());
      notice
          .append("- ")
          .append(entry.getKey())
          .append(" (")
          .append(attribution.project())
          .append(", ")
          .append(String.join(" / ", attribution.licenseIds()))
          .append(")\n");
    }
    notice.append('\n');
    if (licenseIds.contains("EPL-2.0") || licenseIds.contains("EPL-1.0")) {
      notice.append("## Source Code Availability\n\n");
      notice.append("Source code for the Eclipse Public License libraries listed above is\n");
      notice.append("available from their Eclipse Foundation project repositories\n");
      notice.append("(https://projects.eclipse.org/) and as source artifacts on Maven\n");
      notice.append("Central under the same coordinates as the redistributed jars.\n\n");
    }
    notice.append("## Licence Texts\n");
    for (String licenseId : licenseIds) {
      notice.append("\n### ").append(licenseId).append("\n\n");
      String text = licenseText(licenseId);
      notice.append(text);
      if (!text.endsWith("\n")) {
        notice.append('\n');
      }
    }
    Files.writeString(output, notice.toString(), StandardCharsets.UTF_8);
  }

  private static String jarArtifactId(String jarName) {
    return jarName.replaceFirst("-\\d.*\\.jar$", "");
  }

  private static String licenseText(String licenseId) throws IOException {
    String resource = LICENSE_TEXT_RESOURCES.get(licenseId);
    if (resource == null) {
      throw new IllegalStateException("no bundled licence text for " + licenseId);
    }
    try (var in = DistTool.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing licence text resource: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void smoke(Path root, Path archive, String version) throws Exception {
    if (!Files.isRegularFile(archive)) {
      throw new IllegalStateException("archive not found: " + archive);
    }
    ensureJavaRuntime();
    Path temp = Files.createTempDirectory("dediren-dist-smoke-");
    try {
      runCommand(root, List.of("tar", "-xzf", archive.toString(), "-C", temp.toString()), null);
      Path bundle = findBundleDir(temp);
      assertLauncherJvmFlags(bundle);
      assertCdsConfigured(bundle);
      assertFirstLaunchStdoutClean(bundle, temp, version);
      if (Files.exists(bundle.resolve("fixtures/plugins"))) {
        throw new IllegalStateException("archive must not include source fixture plugin manifests");
      }
      Path dediren = bundle.resolve("bin/dediren");
      runBundleCommand(dediren, bundle, List.of("--help"), null);
      assertContains(
          runBundleCommand(dediren, bundle, List.of("--version"), null),
          "dediren " + version,
          "version output");
      for (Launcher launcher : LAUNCHERS) {
        if (launcher.pluginId() == null) {
          continue;
        }
        String capabilities =
            runBundleCommand(
                bundle.resolve("bin").resolve(launcher.bundleScript()),
                bundle,
                List.of("capabilities"),
                null);
        assertRuntimeCapabilities(capabilities, launcher.pluginId());
      }

      Path request = temp.resolve("request.json");
      Path layout = temp.resolve("layout.json");
      Path render = temp.resolve("render.json");
      Map<String, String> dirtyPluginEnv =
          Map.of(
              "DEDIREN_PLUGIN_GENERIC_GRAPH", temp.resolve("missing-generic-graph").toString(),
              "DEDIREN_PLUGIN_ELK_LAYOUT", temp.resolve("missing-elk-layout").toString(),
              "DEDIREN_PLUGIN_RENDER", temp.resolve("missing-render").toString());
      String projectOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of(
                  "project",
                  "--target",
                  "layout-request",
                  "--plugin",
                  "generic-graph",
                  "--view",
                  "main",
                  "--input",
                  bundle.resolve("fixtures/source/valid-pipeline-rich.json").toString()),
              null,
              Map.of(),
              dirtyPluginEnv);
      Files.writeString(request, projectOutput, StandardCharsets.UTF_8);

      String layoutOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of("layout", "--plugin", "elk-layout", "--input", request.toString()),
              null);
      Files.writeString(layout, layoutOutput, StandardCharsets.UTF_8);
      assertCdsArchiveCreated(bundle, "elk-layout");
      assertQualityOutput(
          runBundleCommand(
              dediren, bundle, List.of("validate-layout", "--input", layout.toString()), null));

      String renderOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of(
                  "render",
                  "--plugin",
                  "render",
                  "--policy",
                  bundle.resolve("fixtures/render-policy/rich-svg.json").toString(),
                  "--input",
                  layout.toString()),
              null);
      Files.writeString(render, renderOutput, StandardCharsets.UTF_8);
      assertSvgRenderOutput(renderOutput);

      // Second render: raster-enabled policy (PNG / Batik path)
      JsonNode svgPolicyJson =
          JsonSupport.objectMapper()
              .readTree(
                  Files.readString(
                      bundle.resolve("fixtures/render-policy/rich-svg.json"),
                      StandardCharsets.UTF_8));
      ObjectNode rasterPolicyJson = ((ObjectNode) svgPolicyJson).deepCopy();
      rasterPolicyJson.putObject("raster").put("scale", 2);
      Path rasterPolicyFile = temp.resolve("raster-policy.json");
      Files.writeString(
          rasterPolicyFile,
          JsonSupport.objectMapper().writeValueAsString(rasterPolicyJson),
          StandardCharsets.UTF_8);
      String pngRenderOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of(
                  "render",
                  "--plugin",
                  "render",
                  "--policy",
                  rasterPolicyFile.toString(),
                  "--input",
                  layout.toString()),
              null);
      assertPngRenderOutput(pngRenderOutput);

      Path oefSchemas = writeOefSchemas(temp.resolve("oef-schemas"));
      String oefOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of(
                  "export",
                  "--plugin",
                  "archimate-oef",
                  "--policy",
                  bundle.resolve("fixtures/export-policy/default-oef.json").toString(),
                  "--source",
                  bundle.resolve("fixtures/source/valid-archimate-oef.json").toString(),
                  "--layout",
                  bundle.resolve("fixtures/layout-result/archimate-oef-basic.json").toString()),
              null,
              Map.of("DEDIREN_OEF_SCHEMA_DIR", oefSchemas.toString()),
              Map.of());
      assertArtifactKind(oefOutput, "archimate-oef+xml");
      Path xmiSchema = writeXmiSchema(temp.resolve("XMI.xsd"));
      String xmiOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of(
                  "export",
                  "--plugin",
                  "uml-xmi",
                  "--policy",
                  bundle.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                  "--source",
                  bundle.resolve("fixtures/source/valid-uml-basic.json").toString(),
                  "--layout",
                  bundle.resolve("fixtures/layout-result/uml-basic.json").toString()),
              null,
              Map.of("DEDIREN_XMI_SCHEMA_PATH", xmiSchema.toString()),
              Map.of());
      assertArtifactKind(xmiOutput, "uml-xmi+xml");
      System.out.println("distribution smoke test passed: " + archive);
    } finally {
      deleteIfExists(temp);
    }
  }

  private static void bench(Path root, Path archive, int runs) throws Exception {
    if (!Files.isRegularFile(archive)) {
      throw new IllegalStateException("archive not found: " + archive);
    }
    ensureJavaRuntime();
    Path temp = Files.createTempDirectory("dediren-dist-bench-");
    try {
      runCommand(root, List.of("tar", "-xzf", archive.toString(), "-C", temp.toString()), null);
      Path bundle = findBundleDir(temp);
      Path dediren = bundle.resolve("bin/dediren");

      // Prepare a layout request once so the layout bench has real input.
      String projectOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of(
                  "project",
                  "--target",
                  "layout-request",
                  "--plugin",
                  "generic-graph",
                  "--view",
                  "main",
                  "--input",
                  bundle.resolve("fixtures/source/valid-pipeline-rich.json").toString()),
              null);
      Path request = temp.resolve("request.json");
      Files.writeString(request, projectOutput, StandardCharsets.UTF_8);

      List<Bench.Stat> stats = new ArrayList<>();
      stats.add(
          timeCommand(
              "cli --version",
              runs,
              () -> {
                runBundleCommand(dediren, bundle, List.of("--version"), null);
              }));
      stats.add(
          timeCommand(
              "elk-layout capabilities",
              runs,
              () -> {
                runBundleCommand(
                    bundle.resolve("bin/dediren-plugin-elk-layout"),
                    bundle,
                    List.of("capabilities"),
                    null);
              }));
      stats.add(
          timeCommand(
              "elk-layout layout (probe+work)",
              runs,
              () -> {
                runBundleCommand(
                    dediren,
                    bundle,
                    List.of("layout", "--plugin", "elk-layout", "--input", request.toString()),
                    null);
              }));
      stats.add(
          timeCommand(
              "generic-graph capabilities",
              runs,
              () -> {
                runBundleCommand(
                    bundle.resolve("bin/dediren-plugin-generic-graph"),
                    bundle,
                    List.of("capabilities"),
                    null);
              }));

      System.out.println(Bench.renderReport(stats));
    } finally {
      deleteIfExists(temp);
    }
  }

  private static Bench.Stat timeCommand(String label, int runs, BenchInvocation invocation)
      throws Exception {
    List<Long> millis = new ArrayList<>();
    for (int index = 0; index < runs; index++) {
      long start = System.nanoTime();
      invocation.run();
      millis.add((System.nanoTime() - start) / 1_000_000L);
    }
    return Bench.summarize(label, millis);
  }

  @FunctionalInterface
  private interface BenchInvocation {
    void run() throws Exception;
  }

  private static Set<String> installLauncher(Path root, Path bundle, Launcher launcher)
      throws IOException {
    Path install = root.resolve(launcher.installDir());
    Path sourceBin = install.resolve("bin").resolve(launcher.sourceScript());
    if (!Files.isRegularFile(sourceBin)) {
      throw new IOException("missing installed launcher: " + sourceBin);
    }
    String sourceScript = Files.readString(sourceBin, StandardCharsets.UTF_8);
    Set<String> declaredJars = declaredClasspathJars(sourceScript);
    verifyStagedLib(install.resolve("lib"), declaredJars, launcher);
    Path targetBin = bundle.resolve("bin").resolve(launcher.bundleScript());
    String script = withBundleRootExport(sourceScript);
    script = withCdsArchive(script, launcher.sourceScript());
    Files.writeString(targetBin, script, StandardCharsets.UTF_8);
    makeExecutable(targetBin);
    copyDeclaredJars(install.resolve("lib"), bundle.resolve("lib"), declaredJars);
    return declaredJars;
  }

  /** Extracts the {@code lib/} jar file names declared on the launcher script CLASSPATH line. */
  static Set<String> declaredClasspathJars(String script) {
    Set<String> jars = new LinkedHashSet<>();
    for (String line : script.split("\\R")) {
      if (!line.startsWith("CLASSPATH=")) {
        continue;
      }
      Matcher matcher = CLASSPATH_LIB_JAR.matcher(line);
      while (matcher.find()) {
        jars.add(matcher.group(1));
      }
    }
    return jars;
  }

  /**
   * Fails the build when the staged appassembler {@code lib/} diverges from the launcher's declared
   * classpath: a stale jar (for example a prior product version left in {@code target/}) would
   * otherwise silently ship, and a missing jar would ship a broken launcher.
   */
  private static void verifyStagedLib(Path lib, Set<String> declaredJars, Launcher launcher)
      throws IOException {
    Set<String> staged = new TreeSet<>();
    if (Files.isDirectory(lib)) {
      try (var entries = Files.list(lib)) {
        entries
            .filter(Files::isRegularFile)
            .map(path -> path.getFileName().toString())
            .filter(name -> name.endsWith(".jar"))
            .forEach(staged::add);
      }
    }
    Set<String> foreign = new TreeSet<>(staged);
    foreign.removeAll(declaredJars);
    if (!foreign.isEmpty()) {
      throw new IllegalStateException(
          "dist hermeticity check failed: "
              + launcher.installDir()
              + "/lib contains jars that are not on the "
              + launcher.sourceScript()
              + " launcher classpath: "
              + foreign
              + " (stale build output; rebuild so target/appassembler is regenerated)");
    }
    Set<String> missing = new TreeSet<>(declaredJars);
    missing.removeAll(staged);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "dist hermeticity check failed: "
              + launcher.installDir()
              + "/lib is missing jars declared on the "
              + launcher.sourceScript()
              + " launcher classpath: "
              + missing);
    }
  }

  private static void copyDeclaredJars(Path sourceLib, Path targetLib, Set<String> declaredJars)
      throws IOException {
    Files.createDirectories(targetLib);
    for (String jar : declaredJars) {
      Files.copy(
          sourceLib.resolve(jar), targetLib.resolve(jar), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Exact-match guard: the packaged {@code lib/} holds the declared jar set and nothing else. */
  private static void verifyPackagedLib(Path lib, Set<String> declaredJars) throws IOException {
    Set<String> packaged = new TreeSet<>();
    try (var entries = Files.list(lib)) {
      entries.map(path -> path.getFileName().toString()).forEach(packaged::add);
    }
    if (!packaged.equals(declaredJars)) {
      Set<String> extra = new TreeSet<>(packaged);
      extra.removeAll(declaredJars);
      Set<String> missing = new TreeSet<>(declaredJars);
      missing.removeAll(packaged);
      throw new IllegalStateException(
          "dist hermeticity check failed: packaged lib/ diverges from the launcher classpaths"
              + (extra.isEmpty() ? "" : "; unexpected entries: " + extra)
              + (missing.isEmpty() ? "" : "; missing jars: " + missing));
    }
  }

  static String withCdsArchive(String script, String cdsName) {
    if (script.contains("DEDIREN_CDS_DIR=")) {
      return script;
    }
    String marker = "export DEDIREN_BUNDLE_ROOT";
    int markerIndex = script.indexOf(marker);
    if (markerIndex < 0) {
      throw new IllegalArgumentException(
          "launcher script must contain the DEDIREN_BUNDLE_ROOT export before CDS injection");
    }
    int lineEnd = script.indexOf('\n', markerIndex);
    int insertionPoint = lineEnd < 0 ? script.length() : lineEnd + 1;
    String nl = script.contains("\r\n") ? "\r\n" : "\n";
    String block =
        ""
            + "DEDIREN_CDS_DIR=\"${DEDIREN_CDS_DIR:-$DEDIREN_BUNDLE_ROOT/cds}\""
            + nl
            + "if ! mkdir -p \"$DEDIREN_CDS_DIR\" 2>/dev/null || [ ! -w \"$DEDIREN_CDS_DIR\" ]; then"
            + nl
            + "  DEDIREN_CDS_DIR=\"${XDG_CACHE_HOME:-$HOME/.cache}/dediren/cds\""
            + nl
            + "  mkdir -p \"$DEDIREN_CDS_DIR\" 2>/dev/null || true"
            + nl
            + "fi"
            + nl
            + "JAVA_OPTS=\"$JAVA_OPTS -XX:+AutoCreateSharedArchive"
            + " -XX:SharedArchiveFile=$DEDIREN_CDS_DIR/"
            + cdsName
            // Route first-launch AutoCreateSharedArchive warnings off stdout and onto stderr (the
            // human debug channel) so a freshly unpacked bundle's first command keeps stdout
            // JSON-pure. cds=warning:stderr alone only ADDS a stderr sink; the default stdout sink
            // still emits them, so cds=off:stdout is required to actually clear stdout while
            // preserving the warnings on stderr and leaving all other JVM logging untouched.
            + ".jsa -Xlog:cds=off:stdout -Xlog:cds=warning:stderr\""
            + nl
            + "export JAVA_OPTS"
            + nl;
    return script.substring(0, insertionPoint) + block + script.substring(insertionPoint);
  }

  static String withBundleRootExport(String script) {
    if (script.contains("DEDIREN_BUNDLE_ROOT=")) {
      return script;
    }
    InsertionPoint marker = findBundleRootInsertionPoint(script);
    int lineEnd = script.indexOf('\n', marker.index());
    int insertionPoint = lineEnd < 0 ? script.length() : lineEnd + 1;
    String lineSeparator = script.contains("\r\n") ? "\r\n" : "\n";
    String export =
        "DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$"
            + marker.variable()
            + "}\""
            + lineSeparator
            + "export DEDIREN_BUNDLE_ROOT"
            + lineSeparator;
    return script.substring(0, insertionPoint) + export + script.substring(insertionPoint);
  }

  private static InsertionPoint findBundleRootInsertionPoint(String script) {
    int appHome = script.indexOf("APP_HOME=$( cd -P ");
    if (appHome >= 0) {
      return new InsertionPoint(appHome, "APP_HOME");
    }
    int basedir = script.indexOf("BASEDIR=");
    if (basedir >= 0) {
      return new InsertionPoint(basedir, "BASEDIR");
    }
    int baseDir = script.indexOf("BASE_DIR=");
    if (baseDir >= 0) {
      return new InsertionPoint(baseDir, "BASE_DIR");
    }
    throw new IllegalArgumentException(
        "launcher script does not contain a recognized application home assignment");
  }

  private static void copyManifestFiles(Path source, Path target) throws IOException {
    Files.createDirectories(target);
    try (var entries = Files.list(source)) {
      for (Path path :
          entries.filter(p -> p.getFileName().toString().endsWith(".manifest.json")).toList()) {
        Files.copy(path, target.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static void copyFixtures(Path source, Path target) throws IOException {
    Files.createDirectories(target);
    try (var entries = Files.list(source)) {
      for (Path path : entries.toList()) {
        if (path.getFileName().toString().equals("plugins")) {
          continue;
        }
        Path destination = target.resolve(path.getFileName());
        if (Files.isDirectory(path)) {
          copyDirectory(path, destination);
        } else {
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void writeBundleMetadata(Path bundle, String version) throws IOException {
    List<Map<String, String>> plugins = new ArrayList<>();
    for (String plugin : bundledPluginIds()) {
      plugins.add(Map.of("id", plugin, "version", version));
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("bundle_schema_version", "dediren-bundle.schema.v1");
    metadata.put("product", "dediren");
    metadata.put("version", version);
    metadata.put("target", bundleMetadataTarget());
    metadata.put("built_at_utc", Instant.now().toString());
    metadata.put("plugins", plugins);
    metadata.put("schemas_dir", "schemas");
    metadata.put("fixtures_dir", "fixtures");
    metadata.put("docs_dir", "docs");
    metadata.put("elk_helper", "bin/dediren-plugin-elk-layout");
    Files.writeString(
        bundle.resolve("bundle.json"),
        JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
        StandardCharsets.UTF_8);
  }

  static List<String> bundledPluginIds() {
    return LAUNCHERS.stream().map(Launcher::pluginId).filter(Objects::nonNull).toList();
  }

  static List<String> launcherInstallDirs() {
    return LAUNCHERS.stream().map(Launcher::installDir).toList();
  }

  private static String runBundleCommand(
      Path executable, Path bundle, List<String> args, Path stdin) throws Exception {
    return runBundleCommand(executable, bundle, args, stdin, Map.of(), Map.of());
  }

  private static String runBundleCommand(
      Path executable,
      Path bundle,
      List<String> args,
      Path stdin,
      Map<String, String> extraEnv,
      Map<String, String> dirtyEnv)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command(executable.toString(), args));
    builder.directory(bundle.toFile());
    builder.environment().putAll(dirtyEnv);
    for (String name : CLEAN_ENV) {
      builder.environment().remove(name);
    }
    builder.environment().putAll(extraEnv);
    if (stdin != null) {
      builder.redirectInput(stdin.toFile());
    }
    Process process = builder.start();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(
          "bundled command failed with status "
              + exit
              + "\nstdout:\n"
              + stdout
              + "\nstderr:\n"
              + stderr);
    }
    return stdout;
  }

  private static void assertSvgRenderOutput(String stdout) throws IOException {
    JsonNode data = okData(stdout);
    JsonNode artifact = data.path("artifacts").path(0);
    if (!"svg".equals(artifact.path("artifact_kind").asText())) {
      throw new IllegalStateException("render smoke output artifact_kind should be svg");
    }
    if (!artifact.path("content").asText().contains("<svg")) {
      throw new IllegalStateException("render smoke output should contain SVG content");
    }
  }

  private static void assertPngRenderOutput(String stdout) throws IOException {
    JsonNode data = okData(stdout);
    JsonNode artifacts = data.path("artifacts");
    JsonNode pngArtifact = null;
    for (JsonNode artifact : artifacts) {
      if ("png".equals(artifact.path("artifact_kind").asText())) {
        pngArtifact = artifact;
        break;
      }
    }
    if (pngArtifact == null) {
      throw new IllegalStateException(
          "raster render smoke output should contain a png artifact: " + stdout);
    }
    if (!"base64".equals(pngArtifact.path("encoding").asText())) {
      throw new IllegalStateException("png artifact encoding should be base64: " + stdout);
    }
    byte[] pngBytes = Base64.getDecoder().decode(pngArtifact.path("content").asText());
    // PNG magic: 0x89 'P' 'N' 'G'
    if (pngBytes.length < 4
        || (pngBytes[0] & 0xFF) != 0x89
        || pngBytes[1] != 'P'
        || pngBytes[2] != 'N'
        || pngBytes[3] != 'G') {
      throw new IllegalStateException("png artifact content does not start with PNG magic bytes");
    }
  }

  private static void assertArtifactKind(String stdout, String expected) throws IOException {
    JsonNode data = okData(stdout);
    if (!expected.equals(data.path("artifact_kind").asText())) {
      throw new IllegalStateException("artifact_kind should be " + expected + ": " + stdout);
    }
  }

  private static void assertQualityOutput(String stdout) throws IOException {
    JsonNode data = okData(stdout);
    if (!"ok".equals(data.path("status").asText())) {
      throw new IllegalStateException("validate-layout status should be ok: " + stdout);
    }
  }

  private static void assertRuntimeCapabilities(String stdout, String expectedPluginId)
      throws IOException {
    JsonNode value = JsonSupport.objectMapper().readTree(stdout);
    if (!expectedPluginId.equals(value.path("id").asText())) {
      throw new IllegalStateException(
          "capability id should be " + expectedPluginId + ": " + stdout);
    }
    if (!value.path("capabilities").isArray() || value.path("capabilities").isEmpty()) {
      throw new IllegalStateException("capability output should list capabilities: " + stdout);
    }
  }

  private static JsonNode okData(String stdout) throws IOException {
    JsonNode value = JsonSupport.objectMapper().readTree(stdout);
    if (!"ok".equals(value.path("status").asText())) {
      throw new IllegalStateException("command output status should be ok: " + stdout);
    }
    return value.get("data");
  }

  private static void assertContains(String text, String expected, String label) {
    if (!text.contains(expected)) {
      throw new IllegalStateException(label + " should contain " + expected + ": " + text);
    }
  }

  private static void assertLauncherJvmFlags(Path bundle) throws IOException {
    for (Launcher launcher : LAUNCHERS) {
      Path script = bundle.resolve("bin").resolve(launcher.bundleScript());
      String text = Files.readString(script, StandardCharsets.UTF_8);
      for (String flag : EXPECTED_LAUNCHER_FLAGS) {
        if (!text.contains(flag)) {
          throw new IllegalStateException(
              "launcher " + launcher.bundleScript() + " is missing JVM flag " + flag);
        }
      }
    }
  }

  /**
   * First-launch stdout purity guard. On the very first launch against a not-yet-seeded CDS
   * directory, {@code -XX:+AutoCreateSharedArchive} emits ~150 {@code [warning][cds]} lines. Those
   * must land on stderr (the human debug channel), never stdout: an agent whose first command
   * against a freshly unpacked bundle pipes stdout to {@code jq} must still see only the version
   * line / a single JSON envelope. Each probe uses its own fresh, empty CDS dir so it is a genuine
   * cold start with the archive absent.
   */
  private static void assertFirstLaunchStdoutClean(Path bundle, Path temp, String version)
      throws Exception {
    Path dediren = bundle.resolve("bin/dediren");
    String versionStdout =
        runBundleCommand(
            dediren,
            bundle,
            List.of("--version"),
            null,
            Map.of("DEDIREN_CDS_DIR", temp.resolve("fresh-cds-version").toString()),
            Map.of());
    assertNoCdsLines(versionStdout, "first-launch --version");
    if (!versionStdout.strip().equals("dediren " + version)) {
      throw new IllegalStateException(
          "first-launch --version stdout must be exactly the version line: " + versionStdout);
    }
    String validateStdout =
        runBundleCommand(
            dediren,
            bundle,
            List.of(
                "validate",
                "--input",
                bundle.resolve("fixtures/source/valid-basic.json").toString()),
            null,
            Map.of("DEDIREN_CDS_DIR", temp.resolve("fresh-cds-validate").toString()),
            Map.of());
    assertNoCdsLines(validateStdout, "first-launch validate");
    // Must parse as a single ok JSON envelope, not JSON interleaved with CDS log noise.
    okData(validateStdout);
  }

  private static void assertNoCdsLines(String stdout, String label) {
    for (String line : stdout.split("\n", -1)) {
      if (line.contains("[cds]")) {
        throw new IllegalStateException(
            label + " stdout must not contain CDS log lines (they belong on stderr): " + line);
      }
    }
  }

  private static void assertCdsConfigured(Path bundle) throws IOException {
    for (Launcher launcher : LAUNCHERS) {
      String text =
          Files.readString(
              bundle.resolve("bin").resolve(launcher.bundleScript()), StandardCharsets.UTF_8);
      if (!text.contains("-XX:+AutoCreateSharedArchive")
          || !text.contains(launcher.sourceScript() + ".jsa")
          || !text.contains("-Xlog:cds=off:stdout -Xlog:cds=warning:stderr")) {
        throw new IllegalStateException(
            "launcher " + launcher.bundleScript() + " is missing its CDS configuration");
      }
    }
  }

  private static void assertCdsArchiveCreated(Path bundle, String cdsName) {
    Path archive = bundle.resolve("cds").resolve(cdsName + ".jsa");
    if (!Files.isRegularFile(archive)) {
      throw new IllegalStateException("expected CDS archive was not auto-created: " + archive);
    }
  }

  private static Path writeOefSchemas(Path schemaDir) throws IOException {
    Files.createDirectories(schemaDir);
    String schema =
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
              targetNamespace="http://www.opengroup.org/xsd/archimate/3.0/"
              xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
              elementFormDefault="qualified"
              attributeFormDefault="unqualified">
              <xs:element name="model">
                <xs:complexType>
                  <xs:sequence>
                    <xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/>
                  </xs:sequence>
                  <xs:attribute name="identifier" type="xs:ID" use="required"/>
                  <xs:anyAttribute namespace="##any" processContents="lax"/>
                </xs:complexType>
              </xs:element>
              <xs:complexType name="ApplicationComponent" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="ApplicationService" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Grouping" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="AndJunction" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Realization" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Flow" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Composition" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Element" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Relationship" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
              <xs:complexType name="Diagram" mixed="true">
                <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                <xs:anyAttribute namespace="##any" processContents="lax"/>
              </xs:complexType>
            </xs:schema>
            """;
    for (String fileName :
        List.of("archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd")) {
      Files.writeString(schemaDir.resolve(fileName), schema, StandardCharsets.UTF_8);
    }
    return schemaDir;
  }

  private static Path writeXmiSchema(Path schemaPath) throws IOException {
    Files.writeString(
        schemaPath,
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                        targetNamespace="http://www.omg.org/spec/XMI/20131001"
                        xmlns="http://www.omg.org/spec/XMI/20131001"
                        elementFormDefault="qualified">
              <xsd:element name="XMI">
                <xsd:complexType>
                  <xsd:choice minOccurs="0" maxOccurs="unbounded">
                    <xsd:any processContents="lax"/>
                  </xsd:choice>
                  <xsd:anyAttribute processContents="lax"/>
                </xsd:complexType>
              </xsd:element>
            </xsd:schema>
            """,
        StandardCharsets.UTF_8);
    return schemaPath;
  }

  private static void ensureJavaRuntime() throws Exception {
    Process process = new ProcessBuilder("java", "-version").start();
    String text =
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
            + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException("java -version failed");
    }
    int major = parseJavaMajor(text);
    if (major < 21) {
      throw new IllegalStateException("Java 21 or newer is required for distribution smoke tests");
    }
  }

  private static int parseJavaMajor(String text) {
    int quote = text.indexOf('"');
    String version = quote >= 0 ? text.substring(quote + 1, text.indexOf('"', quote + 1)) : text;
    String first = version.split("[.\\s]")[0].replaceAll("[^0-9]", "");
    int major = Integer.parseInt(first);
    if (major == 1) {
      return Integer.parseInt(version.split("\\.")[1]);
    }
    return major;
  }

  static String bundleMetadataTarget() {
    return BUNDLE_METADATA_TARGET;
  }

  static String bundleName(String version) {
    return "dediren-agent-bundle-" + version;
  }

  private static void rejectRetiredTargetOption(Map<String, String> options) {
    if (options.containsKey("target")) {
      throw new IllegalArgumentException(
          "--target is no longer supported; Java distribution archives are platform-neutral");
    }
  }

  private static void pruneStaleArtifacts(Path dist, String currentBundle) throws IOException {
    if (!Files.isDirectory(dist)) {
      return;
    }
    try (var entries = Files.list(dist)) {
      for (Path path : entries.toList()) {
        String name = path.getFileName().toString();
        if (!name.startsWith("dediren-agent-bundle-")) {
          continue;
        }
        if (name.equals(currentBundle) || name.equals(currentBundle + ".tar.gz")) {
          continue;
        }
        deleteIfExists(path);
      }
    }
  }

  private static Path findBundleDir(Path temp) throws IOException {
    try (var entries = Files.list(temp)) {
      return entries
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith("dediren-agent-bundle-"))
          .sorted(Comparator.comparing(Path::toString))
          .reduce((first, second) -> second)
          .orElseThrow(
              () -> new IOException("archive did not contain a dediren-agent-bundle directory"));
    }
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    if (!Files.exists(source)) {
      throw new IOException("missing source directory: " + source);
    }
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteIfExists(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void makeExecutable(Path path) throws IOException {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
      permissions.add(PosixFilePermission.OWNER_EXECUTE);
      permissions.add(PosixFilePermission.GROUP_EXECUTE);
      permissions.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      path.toFile().setExecutable(true, false);
    }
  }

  private static void runCommand(Path directory, List<String> command, Path stdin)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    if (stdin != null) {
      builder.redirectInput(stdin.toFile());
    }
    Process process = builder.start();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(
          "command failed with status "
              + exit
              + ": "
              + command
              + "\nstdout:\n"
              + stdout
              + "\nstderr:\n"
              + stderr);
    }
  }

  private static List<String> command(String executable, List<String> args) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.addAll(args);
    return command;
  }

  private static Map<String, String> parseOptions(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int index = 1; index < args.length; index++) {
      String key = args[index];
      if (!key.startsWith("--") || index + 1 >= args.length) {
        throw new IllegalArgumentException("expected --key value option, found " + key);
      }
      options.put(key.substring(2), args[++index]);
    }
    return options;
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing --" + name);
    }
    return value;
  }

  private static void usage() {
    System.err.println(
        "usage: DistTool notices|build|smoke|bench --root PATH [--version VERSION] [--runs N]");
  }

  private record InsertionPoint(int index, String variable) {}

  private record Launcher(
      String installDir, String sourceScript, String bundleScript, String pluginId) {}
}

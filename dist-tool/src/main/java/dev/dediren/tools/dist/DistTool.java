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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

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
          // UTF-8 stream encoding: the launcher itself must force UTF-8 or non-ASCII output is
          // mangled to '?' regardless of the invoking shell's locale (issue #47).
          "-Dstdout.encoding=UTF-8",
          "-Dstderr.encoding=UTF-8",
          "-Dfile.encoding=UTF-8");
  // Single-launcher distribution (Cutover B): the five per-plugin appassembler launchers are gone.
  // The cli launcher hosts the five first-party engines in-process, so its classpath carries the
  // engine jars (+ transitives) and the packaged lib/ is verified against that one classpath.
  private static final List<Launcher> LAUNCHERS =
      List.of(new Launcher("cli/target/appassembler", "cli", "dediren"));
  // Hermeticity scrub for smoke/bench child processes: the dediren environment knobs a caller's
  // shell could otherwise leak into the packaged-bundle probes. DEDIREN_LOG_LEVEL belongs here or a
  // developer who happens to export it would turn on debug logging inside the probes and break the
  // quiet-stderr assertions with a failure that reproduces on their machine only.
  private static final List<String> CLEAN_ENV = List.of("DEDIREN_CDS_DIR", "DEDIREN_LOG_LEVEL");

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
          "engine-api",
          "ir",
          "mcp",
          "render",
          "schema-cache",
          "semantics-archimate",
          "semantics-graph",
          "semantics-uml",
          "uml",
          "uml-xmi-export");

  private static final Map<String, ThirdPartyAttribution> THIRD_PARTY_ATTRIBUTIONS =
      Map.ofEntries(
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
              "mcp-core", attribution("Model Context Protocol Java SDK", "MIT (MCP Java SDK)")),
          Map.entry(
              "mcp-json-jackson3",
              attribution("Model Context Protocol Java SDK", "MIT (MCP Java SDK)")),
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
          Map.entry(
              "reactive-streams", attribution("Reactive Streams", "MIT-0 (Reactive Streams)")),
          Map.entry("reactor-core", attribution("Project Reactor", "Apache-2.0")),
          Map.entry("slf4j-api", attribution("SLF4J", "MIT (SLF4J)")),
          Map.entry("slf4j-simple", attribution("SLF4J", "MIT (SLF4J)")),
          Map.entry("snakeyaml-engine", attribution("SnakeYAML Engine", "Apache-2.0")),
          Map.entry(
              "xml-apis",
              attribution(
                  "Apache XML Commons xml-apis",
                  "Apache-2.0",
                  "SAX (public domain)",
                  "W3C Software License")));

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
          "MIT (MCP Java SDK)", "licenses/mit-mcp-java-sdk.txt",
          "MIT-0 (Reactive Streams)", "licenses/mit0-reactive-streams.txt",
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
    Files.createDirectories(bundle.resolve("docs"));

    Set<String> declaredLibJars = new TreeSet<>();
    for (Launcher launcher : LAUNCHERS) {
      declaredLibJars.addAll(installLauncher(root, bundle, launcher));
    }
    verifyPackagedLib(bundle.resolve("lib"), declaredLibJars);
    copyDirectory(root.resolve("schemas"), bundle.resolve("schemas"));
    copyFixtures(root.resolve("fixtures"), bundle.resolve("fixtures"));
    Files.copy(
        root.resolve("docs/agent-usage.md"),
        bundle.resolve("docs/agent-usage.md"),
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

  /**
   * The dediren {@code mcp} module and the third-party {@code io.modelcontextprotocol.sdk:mcp}
   * artifact both produce a jar literally named {@code mcp-<version>.jar}: {@link #jarArtifactId}
   * strips only the version suffix, so artifact-id matching alone cannot tell the two apart. The
   * third-party jar's full filename is therefore checked explicitly, ahead of the artifact-id
   * lookup below, before "mcp" is allowed to resolve to the first-party module.
   *
   * <p>This literal must track {@code mcp.sdk.version} in the root POM, and nothing in the build
   * makes it do so on its own — which is the whole danger. Bump the SDK to 2.1.0 and this no longer
   * matches: {@code mcp-2.1.0.jar} falls through to the artifact-id lookup, resolves to {@code
   * "mcp"}, is classified as first-party, and the SDK's MIT attribution silently vanishes from
   * THIRD-PARTY-NOTICES.md — a licence-compliance regression shipped by a green build. {@code
   * McpSdkJarPinTest} is what makes that impossible: it reads {@code mcp.sdk.version} out of the
   * root POM and fails if this constant has drifted from it.
   */
  static final String MCP_JAVA_SDK_CORE_JAR = "mcp-2.0.0.jar";

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
      if (jar.equals(MCP_JAVA_SDK_CORE_JAR)) {
        thirdParty.put(jar, attribution("Model Context Protocol Java SDK", "MIT (MCP Java SDK)"));
      } else if (FIRST_PARTY_ARTIFACTS.contains(artifact)) {
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
      assertStaleCdsStdoutClean(bundle, temp);
      assertLoggingIsQuietAndSwitchable(bundle, temp);
      if (Files.exists(bundle.resolve("fixtures/plugins"))) {
        throw new IllegalStateException("archive must not include source fixture plugin manifests");
      }
      if (Files.exists(bundle.resolve("plugins"))) {
        throw new IllegalStateException(
            "single-launcher bundle must not stage a plugins/ manifest directory");
      }
      Path dediren = bundle.resolve("bin/dediren");
      runBundleCommand(dediren, bundle, List.of("--help"), null);
      assertContains(
          runBundleCommand(dediren, bundle, List.of("--version"), null),
          "dediren " + version,
          "version output");

      Path oefSchemas = writeOefSchemas(temp.resolve("oef-schemas"));
      // Full one-shot pipeline through the packaged launcher: render + OEF export in one build.
      assertBuildRendersAndExports(dediren, bundle, temp, oefSchemas);

      assertMcpServesToolsOverStdio(bundle, temp);

      Path request = temp.resolve("request.json");
      Path layout = temp.resolve("layout.json");
      Path render = temp.resolve("render.json");
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
      Files.writeString(request, projectOutput, StandardCharsets.UTF_8);

      String layoutOutput =
          runBundleCommand(
              dediren,
              bundle,
              List.of("layout", "--plugin", "elk-layout", "--input", request.toString()),
              null);
      Files.writeString(layout, layoutOutput, StandardCharsets.UTF_8);
      // One CDS archive per launcher: the single cli launcher seeds cds/cli.jsa.
      assertCdsArchiveCreated(bundle, "cli");
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

      // Issue #47 regression (rehomed from the retired plugin-runtime testbed): a non-ASCII model
      // rendered through the packaged launcher under a C locale must round-trip intact, proving the
      // launcher's -Dstdout.encoding/-Dfile.encoding=UTF-8 flags hold when the ambient locale would
      // otherwise drive the JVM to US-ASCII and mangle the labels to '?'.
      assertNonAsciiRoundTrip(dediren, bundle, temp, version);

      System.out.println("distribution smoke test passed: " + archive);
    } finally {
      deleteIfExists(temp);
    }
  }

  /**
   * Full one-shot {@code dediren build} exercising a render lane and an OEF export lane in one call
   * against bundle fixtures, writing both artifacts under {@code --out} and asserting the packaged
   * build succeeds end to end.
   */
  private static void assertBuildRendersAndExports(
      Path dediren, Path bundle, Path temp, Path oefSchemas) throws Exception {
    Path buildOut = temp.resolve("build-out");
    String stdout =
        runBundleCommand(
            dediren,
            bundle,
            List.of(
                "build",
                "--input",
                bundle.resolve("fixtures/source/valid-archimate-oef.json").toString(),
                "--out",
                buildOut.toString(),
                "--render-policy",
                bundle.resolve("fixtures/render-policy/archimate-svg.json").toString(),
                "--oef-policy",
                bundle.resolve("fixtures/export-policy/default-oef.json").toString()),
            null,
            Map.of("DEDIREN_OEF_SCHEMA_DIR", oefSchemas.toString()),
            Map.of());
    JsonNode buildResult = JsonSupport.objectMapper().readTree(stdout);
    if (!"ok".equals(buildResult.path("status").asText())) {
      throw new IllegalStateException("dediren build did not succeed: " + stdout);
    }
    Path svg = buildOut.resolve("main/diagram.svg");
    Path oef = buildOut.resolve("main/oef.xml");
    if (!Files.isRegularFile(svg) || !Files.isRegularFile(oef)) {
      throw new IllegalStateException(
          "dediren build must write both the render and OEF artifacts: " + buildResult);
    }
  }

  /**
   * Renders a sentinel-labelled model through the packaged launcher under {@code LC_ALL=C} and
   * asserts the non-ASCII label survives the stdout round-trip.
   */
  private static void assertNonAsciiRoundTrip(Path dediren, Path bundle, Path temp, String version)
      throws Exception {
    String sentinel = "Sähkö öäå 测试";
    // Literal placeholder substitution (not String.format) so the multi-line JSON template does not
    // trip the format-string-newline check and the sentinel is inserted verbatim.
    String source =
        """
        {
          "model_schema_version": "model.schema.v1",
          "required_plugins": [ { "id": "generic-graph", "version": "__VERSION__" } ],
          "nodes": [
            { "id": "client", "type": "generic.actor", "label": "__SENTINEL__", "properties": {} },
            { "id": "api", "type": "generic.component", "label": "API", "properties": {} }
          ],
          "relationships": [
            { "id": "client-calls-api", "type": "generic.calls", "source": "client",
              "target": "api", "label": "calls", "properties": {} }
          ],
          "plugins": {
            "generic-graph": {
              "views": [
                { "id": "main", "label": "Main", "nodes": ["client", "api"],
                  "relationships": ["client-calls-api"] }
              ]
            }
          }
        }
        """
            .replace("__VERSION__", version)
            .replace("__SENTINEL__", sentinel);
    Path sourceFile = temp.resolve("sentinel-source.json");
    Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
    // Force a non-UTF-8 ambient locale so the launcher's own encoding flags are what keep stdout
    // and
    // the written files UTF-8 clean; without them the JVM would derive US-ASCII and emit '?'.
    Map<String, String> asciiLocale = Map.of("LC_ALL", "C", "LANG", "C");

    String requestOutput =
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
                sourceFile.toString()),
            null,
            Map.of(),
            asciiLocale);
    Path request = temp.resolve("sentinel-request.json");
    Files.writeString(request, requestOutput, StandardCharsets.UTF_8);
    // Verbatim stream-encoding check: the projected node label must survive the stdout round-trip
    // byte-for-byte (a mangling launcher would have replaced the non-ASCII glyphs with '?'). Assert
    // on the structured label rather than the rendered SVG, which may wrap the label across tspans.
    JsonNode projectedLabel = clientNodeLabel(okData(requestOutput));
    if (projectedLabel == null || !sentinel.equals(projectedLabel.asText())) {
      throw new IllegalStateException(
          "non-ASCII label was mangled through the packaged launcher (issue #47 regression); "
              + "expected the projected node label to be \""
              + sentinel
              + "\" but got: "
              + projectedLabel);
    }

    // The full pipeline must also run render clean (non-zero exit throws); this proves the
    // non-ASCII payload survives layout and render through the packaged launcher end to end.
    String layoutOutput =
        runBundleCommand(
            dediren,
            bundle,
            List.of("layout", "--plugin", "elk-layout", "--input", request.toString()),
            null,
            Map.of(),
            asciiLocale);
    Path layout = temp.resolve("sentinel-layout.json");
    Files.writeString(layout, layoutOutput, StandardCharsets.UTF_8);
    runBundleCommand(
        dediren,
        bundle,
        List.of(
            "render",
            "--plugin",
            "render",
            "--policy",
            bundle.resolve("fixtures/render-policy/default-svg.json").toString(),
            "--input",
            layout.toString()),
        null,
        Map.of(),
        asciiLocale);
  }

  /** Returns the {@code label} node of the {@code client} node in a layout-request payload. */
  private static JsonNode clientNodeLabel(JsonNode layoutRequestData) {
    for (JsonNode node : layoutRequestData.path("nodes")) {
      if ("client".equals(node.path("id").asText())) {
        return node.path("label");
      }
    }
    return null;
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

      Path buildOut = temp.resolve("bench-build-out");
      List<Bench.Stat> stats = new ArrayList<>();
      // Surviving single-launcher surface (W1): the per-plugin capabilities launchers are gone, so
      // bench the version banner, a per-stage layout, and a one-shot render build — all through the
      // one packaged dediren launcher.
      stats.add(
          timeCommand(
              "dediren --version",
              runs,
              () -> {
                runBundleCommand(dediren, bundle, List.of("--version"), null);
              }));
      stats.add(
          timeCommand(
              "dediren layout (per-stage)",
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
              "dediren build (render)",
              runs,
              () -> {
                runBundleCommand(
                    dediren,
                    bundle,
                    List.of(
                        "build",
                        "--input",
                        bundle.resolve("fixtures/source/valid-pipeline-rich.json").toString(),
                        "--out",
                        buildOut.toString(),
                        "--render-policy",
                        bundle.resolve("fixtures/render-policy/rich-svg.json").toString()),
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
            // Keep stdout JSON-pure: the JVM's DEFAULT unified-logging sink is stdout, so any
            // warning the VM emits lands on top of the command envelope and breaks the agent
            // contract. A per-tag selector cannot hold that line -- `cds` matches the tag set
            // `cds` EXACTLY, so sibling sets like `cds,dynamic` ("Unable to use shared archive",
            // emitted whenever the archive is stale, e.g. after any classpath change) fall
            // through to stdout. Other tags can warn too (`os,container` under cgroups, for one).
            // So clear the stdout sink for ALL tags, then re-add warnings on stderr (the human
            // debug channel) to preserve the AutoCreateSharedArchive diagnostics.
            // all=off:stdout, not -Xlog:disable: disable would also wipe a user's own JAVA_OPTS
            // -Xlog file sink, whereas this only reconfigures the stdout and stderr outputs.
            + ".jsa -Xlog:all=off:stdout -Xlog:all=warning:stderr:uptime,level,tags\""
            + nl
            // DEDIREN_LOG_LEVEL is the only switch for first-party logging. Mapping it here, before
            // the JVM starts, sidesteps slf4j-simple's static-initializer ordering hazard: it reads
            // its level once, on the first LoggerFactory call, so a post-launch flag could be
            // clobbered by whichever class happened to load first.
            //
            // The case allowlist is load-bearing, not cosmetic. This value is interpolated into
            // JAVA_OPTS, so an unvalidated env var is arbitrary JVM-argument injection: a caller
            // could smuggle in -XX flags, an agent lib, or a debugger port through what looks like
            // a log setting. Only these six literals reach the JVM; anything else is dropped with
            // a note on stderr (never stdout -- the envelope stays JSON-pure even on misuse).
            + "case \"${DEDIREN_LOG_LEVEL:-}\" in"
            + nl
            + "  trace|debug|info|warn|error|off)"
            + nl
            + "    JAVA_OPTS=\"$JAVA_OPTS"
            + " -Dorg.slf4j.simpleLogger.defaultLogLevel=$DEDIREN_LOG_LEVEL\" ;;"
            + nl
            + "  \"\") ;;"
            + nl
            + "  *)"
            + nl
            + "    echo \"dediren: ignoring invalid DEDIREN_LOG_LEVEL"
            + " (want trace|debug|info|warn|error|off)\" >&2 ;;"
            + nl
            + "esac"
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

  private static void copyFixtures(Path source, Path target) throws IOException {
    Files.createDirectories(target);
    try (var entries = Files.list(source)) {
      for (Path path : entries.toList()) {
        // The retired source plugin-manifest fixtures never ship in the bundle.
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
    // dediren-bundle.schema.v2 (Cutover B): the plugins[] array and elk_helper pointer described a
    // process-plugin surface that no longer exists; an honest descriptor drops them.
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("bundle_schema_version", "dediren-bundle.schema.v2");
    metadata.put("product", "dediren");
    metadata.put("version", version);
    metadata.put("target", bundleMetadataTarget());
    metadata.put("built_at_utc", Instant.now().toString());
    metadata.put("schemas_dir", "schemas");
    metadata.put("fixtures_dir", "fixtures");
    metadata.put("docs_dir", "docs");
    Files.writeString(
        bundle.resolve("bundle.json"),
        JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
        StandardCharsets.UTF_8);
  }

  static List<String> launcherInstallDirs() {
    return LAUNCHERS.stream().map(Launcher::installDir).toList();
  }

  private static String runBundleCommand(
      Path executable, Path bundle, List<String> args, Path stdin) throws Exception {
    return runBundleCommand(executable, bundle, args, stdin, Map.of(), Map.of());
  }

  /** Both streams of one bundled run. Only stderr-sensitive probes need this. */
  private record BundleOutput(String stdout, String stderr) {}

  private static BundleOutput runBundleCommandCapturingBoth(
      Path executable, Path bundle, List<String> args, Map<String, String> extraEnv)
      throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command(executable.toString(), args));
    builder.directory(bundle.toFile());
    for (String name : CLEAN_ENV) {
      builder.environment().remove(name);
    }
    builder.environment().putAll(extraEnv);
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
    return new BundleOutput(stdout, stderr);
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
    assertNoJvmLogLines(versionStdout, "first-launch --version");
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
    assertNoJvmLogLines(validateStdout, "first-launch validate");
    // Must parse as a single ok JSON envelope, not JSON interleaved with CDS log noise.
    okData(validateStdout);
  }

  /**
   * Drives the packaged MCP server over real stdio with real JSON-RPC.
   *
   * <p>Three things are only observable here. First, the protocol actually working through the
   * bundled classpath. Second — and this is the gate for the stdout-integrity control — that the
   * server's stdout carries protocol frames and <em>nothing else</em>: the JVM's CDS notices and
   * SLF4J's provider warning share this process, and if any of them (or a stray print from an
   * engine) reached stdout, the frame stream would be corrupt and a real client would silently go
   * dark.
   *
   * <p>Third, that a tool which does <em>real work</em> gets its response written at all. The batch
   * therefore ends in a genuine {@code dediren_build} — the flagship tool, a full compile through
   * ELK and the SVG renderer, seconds rather than milliseconds — and its response frame is required
   * to be present, by id, carrying an ok build envelope. This is the assertion that matters: an
   * earlier drain implementation waited a fixed idle window after the last outbound byte, which is
   * long enough for {@code dediren_guide} (a string constant, answered in milliseconds) and not
   * remotely long enough for a build. Every response slower than that window was silently discarded
   * by the SDK's EOF-triggered close, and a smoke test that called only the guide stayed green
   * while the product's headline tool lost every reply it ever made.
   */
  private static void assertMcpServesToolsOverStdio(Path bundle, Path temp) throws Exception {
    Path dediren = bundle.resolve("bin/dediren");
    Path requests = temp.resolve("mcp-requests.jsonl");
    // The build tool confines every path inside --root, so the source, the policy and the output
    // directory are all named relative to the bundle it is rooted at.
    Files.writeString(
        requests,
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"dist-smoke","version":"1"}}}
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dediren_guide","arguments":{"topic":"source-json"}}}
        {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"dediren_build","arguments":{"source":"fixtures/source/valid-archimate-oef.json","out":"mcp-build-out","render_policy":"fixtures/render-policy/archimate-svg.json"}}}
        """,
        StandardCharsets.UTF_8);

    String stdout =
        runBundleCommand(dediren, bundle, List.of("mcp", "--root", bundle.toString()), requests);

    // Every non-blank stdout line must be a JSON-RPC frame. Nothing else may share this channel.
    Map<String, JsonNode> responses = new LinkedHashMap<>();
    for (String line : stdout.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode frame;
      try {
        frame = JsonSupport.objectMapper().readTree(line);
      } catch (RuntimeException notJson) {
        throw new IllegalStateException(
            "mcp stdout must carry JSON-RPC frames only; found a non-JSON line: " + line);
      }
      if (!"2.0".equals(frame.path("jsonrpc").asText())) {
        throw new IllegalStateException("mcp stdout line is not a JSON-RPC frame: " + line);
      }
      if (frame.has("id")) {
        responses.put(frame.path("id").asText(), frame);
      }
    }

    assertContains(stdout, "\"serverInfo\"", "mcp initialize response");
    assertContains(stdout, "dediren_validate", "mcp tools/list response");
    assertContains(stdout, "dediren_build", "mcp tools/list response");
    assertContains(stdout, "dediren_guide", "mcp tools/list response");
    assertContains(stdout, "Minimal Source JSON", "mcp guide tool call response");

    assertMcpBuildAnswered(bundle, responses, stdout);
    System.out.println(
        "mcp stdio smoke passed: 3 tools, real build answered, protocol-only stdout");
  }

  /**
   * The build response must be <em>present</em>, not merely unerrored. A dropped response is not an
   * error the client can see: the frame is simply absent, the batch exits 0, and the call hangs on
   * the far end. So assert on the id, then on the envelope it carries, then on the artifact it
   * claims to have written.
   */
  private static void assertMcpBuildAnswered(
      Path bundle, Map<String, JsonNode> responses, String stdout) throws IOException {
    JsonNode build = responses.get("4");
    if (build == null) {
      throw new IllegalStateException(
          "mcp dediren_build response (id 4) is ABSENT from stdout: the server dropped it on"
              + " stdin's EOF instead of writing it. Frames seen: "
              + responses.keySet()
              + "\nstdout:\n"
              + stdout);
    }
    if (build.path("result").path("isError").asBoolean()) {
      throw new IllegalStateException("mcp dediren_build reported a tool error: " + build);
    }
    String envelopeText = build.path("result").path("content").path(0).path("text").asText();
    JsonNode envelope = JsonSupport.objectMapper().readTree(envelopeText);
    if (!"ok".equals(envelope.path("status").asText())) {
      throw new IllegalStateException(
          "mcp dediren_build envelope status should be ok: " + envelopeText);
    }
    // The build-result envelope the CLI publishes, verbatim: the MCP tool adds no second result
    // format, so the artifact it claims to have written is named under views[].artifacts[].
    JsonNode artifact = envelope.path("views").path(0).path("artifacts").path(0);
    if (!"svg".equals(artifact.path("artifact_kind").asText())) {
      throw new IllegalStateException(
          "mcp dediren_build envelope should name the SVG it rendered: " + envelopeText);
    }
    Path svg = bundle.resolve("mcp-build-out").resolve(artifact.path("path").asText());
    if (!Files.isRegularFile(svg)) {
      throw new IllegalStateException("mcp dediren_build must actually write its render: " + svg);
    }
  }

  /**
   * A FRESH archive (covered above) only exercises CDS creation. The stdout-corrupting case is a
   * STALE one: a valid archive whose recorded classpath no longer matches, which makes the JVM warn
   * "Unable to use shared archive" under the {@code cds,dynamic} tag set. That is reachable in the
   * field because DEDIREN_CDS_DIR falls back to a version-independent ~/.cache dir when the bundle
   * is not writable, so ANY classpath change -- an upgrade, a dependency added -- staleness-breaks
   * the archive on first run. Seed an archive, then shift the classpath under it and prove the
   * envelope still stands alone on stdout.
   */
  private static void assertStaleCdsStdoutClean(Path bundle, Path temp) throws Exception {
    Path dediren = bundle.resolve("bin/dediren");
    Path cds = temp.resolve("stale-cds");
    List<String> validate =
        List.of(
            "validate", "--input", bundle.resolve("fixtures/source/valid-basic.json").toString());
    // 1. Seed a valid archive for the bundle's real classpath.
    runBundleCommand(
        dediren, bundle, validate, null, Map.of("DEDIREN_CDS_DIR", cds.toString()), Map.of());
    // 2. Shift the classpath out from under it. The probe must be a REAL jar on disk: the JVM
    //    validates actual classpath entries, so a bogus path does not invalidate the archive.
    Path probe = temp.resolve("stale-cds-probe.jar");
    Files.copy(findAnyLibJar(bundle), probe, StandardCopyOption.REPLACE_EXISTING);
    BundleOutput stale =
        runBundleCommandCapturingBoth(
            dediren,
            bundle,
            validate,
            Map.of("DEDIREN_CDS_DIR", cds.toString(), "CLASSPATH_PREFIX", probe.toString()));

    // 3. Prove the probe actually PROVOKED the condition before trusting the clean-stdout result.
    //    Without this the test is a trap: if the archive ever stopped going stale (the jar copy
    //    silently fails, a launcher change drops CLASSPATH_PREFIX, a JVM revision stops warning),
    //    stdout would be clean because nothing warned at all, and this would keep passing while
    //    testing nothing. The warning MUST appear -- on stderr, which is where it belongs.
    if (!stale.stderr().contains("[cds")) {
      throw new IllegalStateException(
          "the stale-CDS probe did not make the archive stale, so its stdout-purity assertion"
              + " proves nothing. Expected a [cds...] warning on stderr.\nstderr:\n"
              + stale.stderr());
    }
    assertNoJvmLogLines(stale.stdout(), "stale-CDS validate");
    okData(stale.stdout());
  }

  /**
   * The bundled SLF4J behaviour, end to end. These must be subprocess probes: an in-process CLI
   * test cannot see any of it, because Main swaps picocli's writers and never touches the JVM's
   * real System.err — which is exactly where SLF4J writes. An in-process assertion here would be
   * vacuously green.
   */
  private static void assertLoggingIsQuietAndSwitchable(Path bundle, Path temp) throws Exception {
    Path dediren = bundle.resolve("bin/dediren");
    Map<String, String> cds = Map.of("DEDIREN_CDS_DIR", temp.resolve("log-cds").toString());

    // 1. The banner probe runs `validate`, and only asserts on the banner. `validate` initialises
    //    SLF4J -- json-schema-validator calls LoggerFactory, which is what printed the banner on
    //    every command before a provider was bound -- but it dispatches no engine, so it reaches
    //    none of OUR debug statements. A default-off assertion here would therefore be vacuous:
    //    silent whether the level were off or debug. That check belongs on `layout` below.
    List<String> validate =
        List.of(
            "validate", "--input", bundle.resolve("fixtures/source/valid-basic.json").toString());
    BundleOutput banner = runBundleCommandCapturingBoth(dediren, bundle, validate, cds);
    if (banner.stderr().contains("SLF4J(")) {
      throw new IllegalStateException(
          "a default run must not print an SLF4J banner; the cli must bind a provider.\nstderr:\n"
              + banner.stderr());
    }
    okData(banner.stdout());

    // 2. Every remaining probe runs `layout`, which goes through EngineDispatch.requireEngine and
    //    the ELK engine -- the seams where the debug lines actually live. Asserting on a command
    //    that reaches no seam is the trap this whole method is shaped to avoid.
    Path request = temp.resolve("log-request.json");
    Files.writeString(
        request,
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
            null),
        StandardCharsets.UTF_8);
    List<String> layout =
        List.of("layout", "--plugin", "elk-layout", "--input", request.toString());

    // 3. Default-off, asserted where it can actually FAIL. `layout` emits debug lines the moment
    //    the level is anything but off, so if simplelogger.properties ever regressed from off to
    //    debug -- or the launcher's empty-DEDIREN_LOG_LEVEL branch started setting a level -- this
    //    catches it. The same assertion against `validate` would be unfalsifiable.
    BundleOutput quiet = runBundleCommandCapturingBoth(dediren, bundle, layout, cds);
    if (quiet.stderr().contains("DEBUG")) {
      throw new IllegalStateException(
          "logging must default to off; a run with no DEDIREN_LOG_LEVEL emitted debug lines"
              + " on a command that reaches a logging seam.\nstderr:\n"
              + quiet.stderr());
    }
    okData(quiet.stdout());

    // 4. Every literal in the launcher's allowlist must actually be honoured. Only exercising
    //    `debug` would let a typo that dropped one of the six from the case pattern ship unnoticed:
    //    the dropped level would fall through to the reject branch and silently stop working.
    for (String level : List.of("trace", "debug", "info", "warn", "error", "off")) {
      Map<String, String> env = new LinkedHashMap<>(cds);
      env.put("DEDIREN_LOG_LEVEL", level);
      BundleOutput accepted = runBundleCommandCapturingBoth(dediren, bundle, layout, env);
      if (accepted.stderr().contains("ignoring invalid DEDIREN_LOG_LEVEL")) {
        throw new IllegalStateException(
            "DEDIREN_LOG_LEVEL=" + level + " is a valid level but the launcher rejected it");
      }
      assertNoJvmLogLines(accepted.stdout(), "DEDIREN_LOG_LEVEL=" + level + " layout");
      okData(accepted.stdout());
    }

    // 5. DEDIREN_LOG_LEVEL=debug opens the channel -- on stderr, with stdout still a clean
    // envelope.
    // Both halves matter: a switch that also polluted stdout would trade one contract break for
    // another.
    Map<String, String> debugEnv = new LinkedHashMap<>(cds);
    debugEnv.put("DEDIREN_LOG_LEVEL", "debug");
    BundleOutput debug = runBundleCommandCapturingBoth(dediren, bundle, layout, debugEnv);
    if (!debug.stderr().contains("DEBUG")) {
      throw new IllegalStateException(
          "DEDIREN_LOG_LEVEL=debug must emit debug lines on stderr.\nstderr:\n" + debug.stderr());
    }
    assertNoJvmLogLines(debug.stdout(), "DEDIREN_LOG_LEVEL=debug layout");
    okData(debug.stdout());

    // 3. The allowlist. This value is interpolated into JAVA_OPTS, so an unvalidated one is
    //    arbitrary JVM-argument injection. -XshowSettings:properties would dump the JVM's whole
    //    property table if it ever reached the java command; assert it does not, that the smuggled
    //    level is dropped rather than honoured, and that the run still yields a clean envelope.
    Map<String, String> injected = new LinkedHashMap<>(cds);
    injected.put("DEDIREN_LOG_LEVEL", "debug -XshowSettings:properties");
    BundleOutput rejected = runBundleCommandCapturingBoth(dediren, bundle, layout, injected);
    if (rejected.stderr().contains("java.runtime.name")
        || rejected.stdout().contains("java.runtime.name")) {
      throw new IllegalStateException(
          "DEDIREN_LOG_LEVEL was interpolated into JAVA_OPTS unvalidated -- a caller can inject"
              + " arbitrary JVM arguments.\nstderr:\n"
              + rejected.stderr());
    }
    if (!rejected.stderr().contains("ignoring invalid DEDIREN_LOG_LEVEL")) {
      throw new IllegalStateException(
          "an invalid DEDIREN_LOG_LEVEL must be reported and dropped.\nstderr:\n"
              + rejected.stderr());
    }
    if (rejected.stderr().contains("DEBUG")) {
      throw new IllegalStateException(
          "a rejected DEDIREN_LOG_LEVEL must not still switch logging on.\nstderr:\n"
              + rejected.stderr());
    }
    okData(rejected.stdout());
  }

  private static Path findAnyLibJar(Path bundle) throws IOException {
    try (Stream<Path> jars = Files.list(bundle.resolve("lib"))) {
      return jars.filter(jar -> jar.getFileName().toString().endsWith(".jar"))
          .sorted()
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("bundle lib/ contains no jars"));
    }
  }

  /**
   * Matches a JVM unified-logging line with the launcher's {@code uptime,level,tags} decorations,
   * e.g. {@code [0.007s][warning][cds,dynamic] Unable to use shared archive}. Deliberately broader
   * than any single tag: the JVM's default log sink is stdout, so ANY tag that warns (cds,
   * cds+dynamic, os+container under cgroups, ...) would land on top of the command envelope. An
   * earlier {@code [cds]}-only check missed {@code [cds,dynamic]} and let exactly that through.
   */
  private static final Pattern JVM_LOG_LINE = Pattern.compile("^\\[[0-9]+[.,][0-9]+s\\]\\[");

  private static void assertNoJvmLogLines(String stdout, String label) {
    for (String line : stdout.split("\n", -1)) {
      if (JVM_LOG_LINE.matcher(line).find()) {
        throw new IllegalStateException(
            label + " stdout must not contain JVM log lines (they belong on stderr): " + line);
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
          || !text.contains("-Xlog:all=off:stdout -Xlog:all=warning:stderr:uptime,level,tags")) {
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

  private record Launcher(String installDir, String sourceScript, String bundleScript) {}
}

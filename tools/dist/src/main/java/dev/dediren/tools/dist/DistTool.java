package dev.dediren.tools.dist;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DistTool {
    private static final String DEFAULT_TARGET = "x86_64-unknown-linux-gnu";
    private static final List<DistTarget> TARGETS = List.of(
        new DistTarget("x86_64-unknown-linux-gnu", "linux", "x86_64"),
        new DistTarget("aarch64-unknown-linux-gnu", "linux", "aarch64"),
        new DistTarget("aarch64-apple-darwin", "macos", "aarch64"));
    private static final List<Launcher> LAUNCHERS = List.of(
        new Launcher("apps/cli/build/install/cli", "cli", "dediren", null),
        new Launcher("modules/plugins/generic-graph/build/install/generic-graph", "generic-graph",
            "dediren-plugin-generic-graph", "generic-graph"),
        new Launcher("modules/plugins/elk-layout/build/install/elk-layout", "elk-layout",
            "dediren-plugin-elk-layout", "elk-layout"),
        new Launcher("modules/plugins/svg-render/build/install/svg-render", "svg-render",
            "dediren-plugin-svg-render", "svg-render"),
        new Launcher("modules/plugins/archimate-oef-export/build/install/archimate-oef-export",
            "archimate-oef-export", "dediren-plugin-archimate-oef-export", "archimate-oef"),
        new Launcher("modules/plugins/uml-xmi-export/build/install/uml-xmi-export", "uml-xmi-export",
            "dediren-plugin-uml-xmi-export", "uml-xmi"));
    private static final List<String> CLEAN_ENV = List.of(
        "DEDIREN_PLUGIN_DIRS",
        "DEDIREN_PLUGIN_GENERIC_GRAPH",
        "DEDIREN_PLUGIN_ELK_LAYOUT",
        "DEDIREN_PLUGIN_SVG_RENDER",
        "DEDIREN_PLUGIN_ARCHIMATE_OEF",
        "DEDIREN_PLUGIN_UML_XMI");

    private DistTool() {
    }

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
                DistTarget target = resolveTarget(options.get("target"));
                Path notices = Path.of(required(options, "notices")).toAbsolutePath().normalize();
                build(root, version, target, notices);
                yield 0;
            }
            case "smoke" -> {
                String version = required(options, "version");
                DistTarget target = resolveTarget(options.get("target"));
                Path archive = options.containsKey("archive")
                    ? Path.of(options.get("archive"))
                    : root.resolve("dist").resolve(bundleName(version, target.triple()) + ".tar.gz");
                smoke(root, archive.toAbsolutePath().normalize(), version);
                yield 0;
            }
            default -> {
                usage();
                yield 2;
            }
        };
    }

    private static void build(Path root, String version, DistTarget target, Path notices) throws Exception {
        ensureHostCanBuild(target);
        Path dist = root.resolve("dist");
        Path bundle = dist.resolve(bundleName(version, target.triple()));
        Path archive = dist.resolve(bundle.getFileName() + ".tar.gz");

        deleteIfExists(bundle);
        Files.deleteIfExists(archive);
        Files.createDirectories(bundle.resolve("bin"));
        Files.createDirectories(bundle.resolve("lib"));
        Files.createDirectories(bundle.resolve("plugins"));
        Files.createDirectories(bundle.resolve("docs"));

        for (Launcher launcher : LAUNCHERS) {
            installLauncher(root, bundle, launcher);
        }
        copyManifestFiles(root.resolve("fixtures/plugins"), bundle.resolve("plugins"));
        copyDirectory(root.resolve("schemas"), bundle.resolve("schemas"));
        copyFixtures(root.resolve("fixtures"), bundle.resolve("fixtures"));
        Files.copy(root.resolve("docs/agent-usage.md"), bundle.resolve("docs/agent-usage.md"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.copy(root.resolve("LICENSE"), bundle.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(notices, bundle.resolve("THIRD-PARTY-NOTICES.md"), StandardCopyOption.REPLACE_EXISTING);
        writeBundleMetadata(bundle, version, target.triple());

        runCommand(root, List.of("tar", "-C", dist.toString(), "-czf", archive.toString(),
            bundle.getFileName().toString()), null);
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
        Files.createDirectories(output.getParent());
        StringBuilder notice = new StringBuilder();
        notice.append("# Third-Party Notices\n\n");
        notice.append("Dediren's own source and launchers are covered by the root LICENSE file.\n");
        notice.append("The Gradle runtime dependency graph below covers redistributed Java libraries.\n\n");
        notice.append("## Java Runtime Dependencies\n\n");
        for (String jar : jars) {
            notice.append("- ").append(jar).append('\n');
        }
        Files.writeString(output, notice.toString(), StandardCharsets.UTF_8);
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
                String capabilities = runBundleCommand(
                    bundle.resolve("bin").resolve(launcher.bundleScript()),
                    bundle,
                    List.of("capabilities"),
                    null);
                assertRuntimeCapabilities(capabilities, launcher.pluginId());
            }

            Path request = temp.resolve("request.json");
            Path layout = temp.resolve("layout.json");
            Path render = temp.resolve("render.json");
            Map<String, String> dirtyPluginEnv = Map.of(
                "DEDIREN_PLUGIN_GENERIC_GRAPH", temp.resolve("missing-generic-graph").toString(),
                "DEDIREN_PLUGIN_ELK_LAYOUT", temp.resolve("missing-elk-layout").toString(),
                "DEDIREN_PLUGIN_SVG_RENDER", temp.resolve("missing-svg-render").toString());
            String projectOutput = runBundleCommand(dediren, bundle, List.of(
                "project", "--target", "layout-request", "--plugin", "generic-graph", "--view", "main",
                "--input", bundle.resolve("fixtures/source/valid-pipeline-rich.json").toString()), null,
                Map.of(), dirtyPluginEnv);
            Files.writeString(request, projectOutput, StandardCharsets.UTF_8);

            String layoutOutput = runBundleCommand(dediren, bundle, List.of(
                "layout", "--plugin", "elk-layout", "--input", request.toString()), null);
            Files.writeString(layout, layoutOutput, StandardCharsets.UTF_8);
            assertQualityOutput(runBundleCommand(dediren, bundle, List.of(
                "validate-layout", "--input", layout.toString()), null));

            String renderOutput = runBundleCommand(dediren, bundle, List.of(
                "render", "--plugin", "svg-render",
                "--policy", bundle.resolve("fixtures/render-policy/rich-svg.json").toString(),
                "--input", layout.toString()), null);
            Files.writeString(render, renderOutput, StandardCharsets.UTF_8);
            assertSvgRenderOutput(renderOutput);
            Path oefSchemas = writeOefSchemas(temp.resolve("oef-schemas"));
            String oefOutput = runBundleCommand(dediren, bundle, List.of(
                "export", "--plugin", "archimate-oef",
                "--policy", bundle.resolve("fixtures/export-policy/default-oef.json").toString(),
                "--source", bundle.resolve("fixtures/source/valid-archimate-oef.json").toString(),
                "--layout", bundle.resolve("fixtures/layout-result/archimate-oef-basic.json").toString()), null,
                Map.of("DEDIREN_OEF_SCHEMA_DIR", oefSchemas.toString()), Map.of());
            assertArtifactKind(oefOutput, "archimate-oef+xml");
            Path xmiSchema = writeXmiSchema(temp.resolve("XMI.xsd"));
            String xmiOutput = runBundleCommand(dediren, bundle, List.of(
                "export", "--plugin", "uml-xmi",
                "--policy", bundle.resolve("fixtures/export-policy/default-uml-xmi.json").toString(),
                "--source", bundle.resolve("fixtures/source/valid-uml-basic.json").toString(),
                "--layout", bundle.resolve("fixtures/layout-result/uml-basic.json").toString()), null,
                Map.of("DEDIREN_XMI_SCHEMA_PATH", xmiSchema.toString()), Map.of());
            assertArtifactKind(xmiOutput, "uml-xmi+xml");
            System.out.println("distribution smoke test passed: " + archive);
        } finally {
            deleteIfExists(temp);
        }
    }

    private static void installLauncher(Path root, Path bundle, Launcher launcher) throws IOException {
        Path install = root.resolve(launcher.installDir());
        Path sourceBin = install.resolve("bin").resolve(launcher.sourceScript());
        if (!Files.isRegularFile(sourceBin)) {
            throw new IOException("missing installed launcher: " + sourceBin);
        }
        Path targetBin = bundle.resolve("bin").resolve(launcher.bundleScript());
        Files.copy(sourceBin, targetBin, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
            targetBin,
            withBundleRootExport(Files.readString(targetBin, StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);
        makeExecutable(targetBin);
        copyDirectoryContents(install.resolve("lib"), bundle.resolve("lib"));
    }

    static String withBundleRootExport(String script) {
        if (script.contains("DEDIREN_BUNDLE_ROOT=")) {
            return script;
        }
        int appHome = script.indexOf("APP_HOME=$( cd -P ");
        if (appHome < 0) {
            throw new IllegalArgumentException("launcher script does not contain the APP_HOME assignment");
        }
        int lineEnd = script.indexOf('\n', appHome);
        int insertionPoint = lineEnd < 0 ? script.length() : lineEnd + 1;
        String lineSeparator = script.contains("\r\n") ? "\r\n" : "\n";
        String export = "DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$APP_HOME}\"" + lineSeparator
            + "export DEDIREN_BUNDLE_ROOT" + lineSeparator;
        return script.substring(0, insertionPoint) + export + script.substring(insertionPoint);
    }

    private static void copyManifestFiles(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var entries = Files.list(source)) {
            for (Path path : entries.filter(p -> p.getFileName().toString().endsWith(".manifest.json")).toList()) {
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

    private static void writeBundleMetadata(Path bundle, String version, String target) throws IOException {
        List<Map<String, String>> plugins = new ArrayList<>();
        for (String plugin : bundledPluginIds()) {
            plugins.add(Map.of("id", plugin, "version", version));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bundle_schema_version", "dediren-bundle.schema.v1");
        metadata.put("product", "dediren");
        metadata.put("version", version);
        metadata.put("target", target);
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
        return LAUNCHERS.stream()
            .map(Launcher::pluginId)
            .filter(Objects::nonNull)
            .toList();
    }

    private static String runBundleCommand(Path executable, Path bundle, List<String> args, Path stdin)
        throws Exception {
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
            throw new IllegalStateException("bundled command failed with status " + exit
                + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
        }
        return stdout;
    }

    private static void assertSvgRenderOutput(String stdout) throws IOException {
        JsonNode data = okData(stdout);
        if (!"svg".equals(data.path("artifact_kind").asText())) {
            throw new IllegalStateException("render smoke output artifact_kind should be svg");
        }
        if (!data.path("content").asText().contains("<svg")) {
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

    private static void assertRuntimeCapabilities(String stdout, String expectedPluginId) throws IOException {
        JsonNode value = JsonSupport.objectMapper().readTree(stdout);
        if (!expectedPluginId.equals(value.path("id").asText())) {
            throw new IllegalStateException("capability id should be " + expectedPluginId + ": " + stdout);
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

    private static Path writeOefSchemas(Path schemaDir) throws IOException {
        Files.createDirectories(schemaDir);
        String schema = """
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
        for (String fileName : List.of("archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd")) {
            Files.writeString(schemaDir.resolve(fileName), schema, StandardCharsets.UTF_8);
        }
        return schemaDir;
    }

    private static Path writeXmiSchema(Path schemaPath) throws IOException {
        Files.writeString(schemaPath, """
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
            """, StandardCharsets.UTF_8);
        return schemaPath;
    }

    private static void ensureJavaRuntime() throws Exception {
        Process process = new ProcessBuilder("java", "-version").start();
        String text = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
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

    private static DistTarget resolveTarget(String requested) {
        String value = requested;
        if (value == null || value.isBlank()) {
            value = System.getenv("DEDIREN_DIST_TARGET");
        }
        if (value == null || value.isBlank()) {
            DistTarget current = currentHostTarget();
            value = current == null ? DEFAULT_TARGET : current.triple();
        }
        String selected = value;
        return TARGETS.stream()
            .filter(target -> target.triple().equals(selected))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unsupported distribution target: " + selected));
    }

    private static void ensureHostCanBuild(DistTarget target) {
        String os = normalizedOs();
        String arch = normalizedArch();
        if (!target.hostOs().equals(os) || !target.hostArch().equals(arch)) {
            throw new IllegalStateException("distribution target " + target.triple() + " must be built on "
                + target.hostOs() + " " + target.hostArch() + "; current host is " + os + " " + arch);
        }
    }

    private static DistTarget currentHostTarget() {
        String os = normalizedOs();
        String arch = normalizedArch();
        return TARGETS.stream()
            .filter(target -> target.hostOs().equals(os) && target.hostArch().equals(arch))
            .findFirst()
            .orElse(null);
    }

    private static String normalizedOs() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os;
    }

    private static String normalizedArch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    private static String bundleName(String version, String target) {
        return "dediren-agent-bundle-" + version + "-" + target;
    }

    private static void pruneStaleArtifacts(Path dist, String currentBundle) throws IOException {
        if (!Files.isDirectory(dist)) {
            return;
        }
        try (var entries = Files.list(dist)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith("dediren-agent-bundle-") || name.startsWith(currentBundle)) {
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
                .orElseThrow(() -> new IOException("archive did not contain a dediren-agent-bundle directory"));
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("missing source directory: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyDirectoryContents(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var entries = Files.list(source)) {
            for (Path path : entries.toList()) {
                Path destination = target.resolve(path.getFileName());
                if (Files.isDirectory(path)) {
                    copyDirectory(path, destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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

    private static void runCommand(Path directory, List<String> command, Path stdin) throws Exception {
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
            throw new IllegalStateException("command failed with status " + exit + ": " + command
                + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
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
        System.err.println("usage: DistTool notices|build|smoke --root PATH [--version VERSION] [--target TRIPLE]");
    }

    private record DistTarget(String triple, String hostOs, String hostArch) {
    }

    private record Launcher(String installDir, String sourceScript, String bundleScript, String pluginId) {
    }
}

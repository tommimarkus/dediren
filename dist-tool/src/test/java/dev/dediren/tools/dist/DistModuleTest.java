package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DistModuleTest {
    @Test
    void moduleLoads() {
        assertThat(DistModule.moduleName()).isEqualTo("dist");
    }

    @Test
    void launcherScriptExportsBundleRootFromAppHome() {
        String script = """
            #!/bin/sh
            APP_HOME=$( cd -P "${APP_HOME:-./}.." > /dev/null && printf '%s\\n' "$PWD" ) || exit

            DEFAULT_JVM_OPTS='"-Ddediren.version=0.22.0"'
            """;

        String rewritten = DistTool.withBundleRootExport(script);

        assertThat(rewritten)
            .contains("DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$APP_HOME}\"")
            .contains("export DEDIREN_BUNDLE_ROOT")
            .containsSubsequence("APP_HOME=$(", "DEDIREN_BUNDLE_ROOT=");
    }

    @Test
    void mavenLauncherScriptExportsBundleRootFromBasedir() {
        String script = """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            exec "$JAVACMD" "$@"
            """;

        String rewritten = DistTool.withBundleRootExport(script);

        assertThat(rewritten)
            .contains("DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$BASEDIR}\"")
            .contains("export DEDIREN_BUNDLE_ROOT")
            .containsSubsequence("BASEDIR=$(", "DEDIREN_BUNDLE_ROOT=");
    }

    @Test
    void bundledPluginIdsAreDerivedFromPluginLaunchers() {
        assertThat(DistTool.bundledPluginIds())
            .containsExactly(
                "generic-graph",
                "elk-layout",
                "svg-render",
                "archimate-oef",
                "uml-xmi");
    }

    @Test
    void launcherInstallDirsUseMavenReactorModulePaths() {
        assertThat(DistTool.launcherInstallDirs())
            .containsExactly(
                "cli/target/appassembler",
                "plugins/generic-graph/target/appassembler",
                "plugins/elk-layout/target/appassembler",
                "plugins/svg-render/target/appassembler",
                "plugins/archimate-oef-export/target/appassembler",
                "plugins/uml-xmi-export/target/appassembler");
    }
}

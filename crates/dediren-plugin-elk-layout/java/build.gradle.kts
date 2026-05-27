import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.SpdxLicenseBundleNormalizer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer

plugins {
    application
    java
    id("com.github.jk1.dependency-license-report") version "3.1.2"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("dev.dediren.elk.Main")
    applicationDefaultJvmArgs = listOf("-XX:-UsePerfData")
}

dependencyLocking {
    lockAllConfigurations()
}

licenseReport {
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.path
    configurations = arrayOf("runtimeClasspath")
    excludeBoms = true
    renderers = arrayOf<ReportRenderer>(
        TextReportRenderer("THIRD-PARTY-NOTICES.md")
    )
    filters = arrayOf<DependencyFilter>(SpdxLicenseBundleNormalizer())
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.3")
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.graph:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0")
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.32.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

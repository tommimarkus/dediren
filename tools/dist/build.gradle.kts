plugins {
    application
}

dependencies {
    implementation(project(":modules:contracts"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.dediren.tools.dist.DistTool")
}

val redistributedProjects = listOf(
    ":apps:cli",
    ":modules:plugins:generic-graph",
    ":modules:plugins:elk-layout",
    ":modules:plugins:svg-render",
    ":modules:plugins:archimate-oef-export",
    ":modules:plugins:uml-xmi-export"
)

val thirdPartyNoticeFile = layout.buildDirectory.file("reports/third-party/THIRD-PARTY-NOTICES.md")

tasks.register<JavaExec>("thirdPartyNotices") {
    dependsOn("classes")
    dependsOn(redistributedProjects.map { "$it:installDist" })
    outputs.file(thirdPartyNoticeFile)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.dediren.tools.dist.DistTool")
    args(
        "notices",
        "--root", rootProject.layout.projectDirectory.asFile.absolutePath,
        "--output", thirdPartyNoticeFile.get().asFile.absolutePath
    )
}

tasks.register<JavaExec>("distBuild") {
    dependsOn("classes", "thirdPartyNotices")
    dependsOn(redistributedProjects.map { "$it:installDist" })
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.dediren.tools.dist.DistTool")
    args(
        "build",
        "--root", rootProject.layout.projectDirectory.asFile.absolutePath,
        "--version", project.version.toString(),
        "--notices", thirdPartyNoticeFile.get().asFile.absolutePath
    )
    project.findProperty("target")?.toString()?.takeIf { it.isNotBlank() }?.let {
        args("--target", it)
    }
}

tasks.register<JavaExec>("distSmoke") {
    dependsOn("distBuild")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.dediren.tools.dist.DistTool")
    args(
        "smoke",
        "--root", rootProject.layout.projectDirectory.asFile.absolutePath,
        "--version", project.version.toString()
    )
    project.findProperty("archive")?.toString()?.takeIf { it.isNotBlank() }?.let {
        args("--archive", it)
    }
    project.findProperty("target")?.toString()?.takeIf { it.isNotBlank() }?.let {
        args("--target", it)
    }
}

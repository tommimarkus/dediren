plugins {
    application
}

val dedirenVersion = project.version.toString()

dependencies {
    implementation(project(":modules:contracts"))
    implementation(project(":modules:core"))
    implementation(libs.picocli)
    testImplementation(project(":modules:plugins:archimate-oef-export"))
    testImplementation(project(":modules:plugins:svg-render"))
    testImplementation(project(":modules:plugins:uml-xmi-export"))
    testImplementation(project(":modules:contracts"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.dediren.cli.Main")
    applicationDefaultJvmArgs = listOf("-Ddediren.version=$dedirenVersion")
}

tasks.test {
    systemProperty("dediren.version", dedirenVersion)
}

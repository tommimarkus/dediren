plugins {
    application
}

dependencies {
    implementation(project(":modules:contracts"))
    implementation(project(":modules:core"))
    implementation(libs.picocli)
    testImplementation(project(":modules:contracts"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.dediren.cli.Main")
}

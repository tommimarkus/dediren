plugins {
    application
}

dependencies {
    implementation(project(":modules:archimate"))
    implementation(project(":modules:contracts"))
    implementation(project(":modules:uml"))
    testImplementation(project(":test-support"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.dediren.plugins.svgrender.Main")
}

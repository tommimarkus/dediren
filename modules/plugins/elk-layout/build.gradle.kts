plugins {
    application
}

dependencies {
    implementation(project(":modules:contracts"))
    implementation(libs.elk.core)
    implementation(libs.elk.graph)
    implementation(libs.elk.layered)
    testImplementation(project(":test-support"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.dediren.plugins.elklayout.Main")
}

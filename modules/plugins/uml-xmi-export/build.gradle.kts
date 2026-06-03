plugins {
    application
}

dependencies {
    implementation(project(":modules:contracts"))
    implementation(project(":modules:schema-cache"))
    implementation(project(":modules:uml"))
    testImplementation(project(":test-support"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.xmlunit.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.dediren.plugins.umlxmi.Main")
}

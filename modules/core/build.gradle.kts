plugins {
    `java-library`
}

dependencies {
    api(project(":modules:contracts"))
    implementation(libs.json.schema.validator)
    testImplementation(project(":test-support"))
    testImplementation(project(":testbeds:plugin-runtime"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

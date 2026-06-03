plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    testImplementation(project(":test-support"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

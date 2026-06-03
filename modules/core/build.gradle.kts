plugins {
    `java-library`
}

dependencies {
    api(project(":modules:contracts"))
    testImplementation(project(":test-support"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

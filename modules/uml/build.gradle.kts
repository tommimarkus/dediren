plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:contracts"))
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

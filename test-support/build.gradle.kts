plugins {
    `java-library`
}

dependencies {
    api(libs.assertj.core)
    api(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

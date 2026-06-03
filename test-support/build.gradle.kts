plugins {
    `java-library`
}

dependencies {
    api(libs.assertj.core)
    api(libs.jackson.databind)
    api(libs.json.schema.validator)
    api(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

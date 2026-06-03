plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

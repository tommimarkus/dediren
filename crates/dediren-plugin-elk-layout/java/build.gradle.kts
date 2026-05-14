plugins {
    application
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("dev.dediren.elk.Main")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.3")
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.graph:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.libavoid:0.11.0")
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.32.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:canonical"))
    implementation(project(":core:da"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnit()
}

tasks.register<JavaExec>("exportParityFixture") {
    group = "verification"
    description = "Export the deterministic 50-event parity fixture bundle."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.takopi.percept.core.trace.ParityFixtureExporterKt")
    args(layout.buildDirectory.dir("parity-fixture-bundle").get().asFile.absolutePath)
}

tasks.register<JavaExec>("exportSyntheticM6Bundle") {
    group = "verification"
    description = "Export a deterministic 5-minute multimodal M6 synthetic bundle."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.takopi.percept.core.trace.SyntheticM6BundleExporterKt")
    args(layout.buildDirectory.dir("synthetic-m6-bundle").get().asFile.absolutePath)
}

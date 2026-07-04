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

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:canonical"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

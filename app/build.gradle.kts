import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// §5: models are fetched at build time with pinned sha256 (never committed);
// hashes must stay in sync with ExtractionRuns in :core:trace.
abstract class DownloadModelsTask : DefaultTask() {
    @get:Input
    abstract val specs: MapProperty<String, Pair<String, String>>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val directory = outputDir.get().asFile.apply { mkdirs() }
        for ((fileName, spec) in specs.get()) {
            val (url, sha256) = spec
            val target = File(directory, fileName)
            if (!target.exists() || sha256Of(target) != sha256) {
                URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val actual = sha256Of(target)
            check(actual == sha256) {
                "sha256 mismatch for $fileName: expected $sha256, got $actual"
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val downloadModels = tasks.register<DownloadModelsTask>("downloadModels") {
    group = "build setup"
    description = "Download model assets with pinned sha256 into generated assets."
    specs.set(
        mapOf(
            "efficientdet_lite0_int8.tflite" to Pair(
                "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
                "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb",
            ),
            "yamnet.tflite" to Pair(
                "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite",
                "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf",
            ),
            "ggml-tiny-q8_0.bin" to Pair(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin",
                "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca",
            ),
            "yamnet_class_map.csv" to Pair(
                "https://raw.githubusercontent.com/tensorflow/models/dfffd623b6be8d1d9744b8e261fbac370d17c46d/research/audioset/yamnet/yamnet_class_map.csv",
                "cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2",
            ),
        ),
    )
    outputDir.set(layout.buildDirectory.dir("generated/percept-assets/models"))
}

android {
    namespace = "org.takopi.percept.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.takopi.percept"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/percept-assets"))

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Only asset merging (packaging) pulls the models; unit tests stay offline-safe.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(downloadModels)
}

dependencies {
    implementation(project(":core:canonical"))
    implementation(project(":core:da"))
    implementation(project(":core:trace"))
    implementation(project(":core:index"))
    implementation(project(":perception:video"))
    implementation(project(":perception:audio"))
    implementation(project(":dispatch"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.room.ktx)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.coroutines.core)
    implementation(libs.guava.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

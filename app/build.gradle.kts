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

// sherpa-onnx streaming zipformer: AAR for runtime, model files extracted
// from the pinned release tarball into generated assets.
abstract class DownloadSherpaAssetsTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    /** Staging location of the tarball, outside any asset root. */
    @get:OutputFile
    abstract val tarballFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:javax.inject.Inject
    abstract val archives: ArchiveOperations

    @get:javax.inject.Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun downloadAndExtract() {
        val tarball = tarballFile.get().asFile.apply { parentFile.mkdirs() }
        if (!tarball.exists() || sha256Of(tarball) != sha256.get()) {
            URI(url.get()).toURL().openStream().use { input ->
                tarball.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val actual = sha256Of(tarball)
        check(actual == sha256.get()) {
            "sha256 mismatch for ${tarball.name}: expected ${sha256.get()}, got $actual"
        }
        fs.copy {
            from(archives.tarTree(tarball)) {
                include("**/encoder-epoch-99-avg-1.int8.onnx")
                include("**/decoder-epoch-99-avg-1.onnx")
                include("**/joiner-epoch-99-avg-1.int8.onnx")
                include("**/tokens.txt")
                eachFile { path = name }
                includeEmptyDirs = false
            }
            into(outputDir.get().asFile)
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

abstract class DownloadPinnedFileTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = outputFile.get().asFile.apply { parentFile.mkdirs() }
        if (!target.exists() || sha256Of(target) != sha256.get()) {
            URI(url.get()).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val actual = sha256Of(target)
        check(actual == sha256.get()) {
            "sha256 mismatch for ${target.name}: expected ${sha256.get()}, got $actual"
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

val sherpaAar = layout.buildDirectory.file("sherpa/sherpa-onnx-1.13.3.aar")
val downloadSherpaAar = tasks.register<DownloadPinnedFileTask>("downloadSherpaAar") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-1.13.3.aar")
    sha256.set("243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6")
    outputFile.set(sherpaAar)
}

val downloadSherpaModels = tasks.register<DownloadSherpaAssetsTask>("downloadSherpaModels") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2")
    sha256.set("9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9")
    tarballFile.set(layout.buildDirectory.file("sherpa/zipformer-en-20m.tar.bz2"))
    outputDir.set(layout.buildDirectory.dir("generated/percept-sherpa-assets/models/zipformer20m"))
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
        versionCode = 44
        versionName = "0.4.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/percept-assets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/percept-sherpa-assets"))

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Only asset merging (packaging) pulls the models; unit tests stay offline-safe.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(downloadModels, downloadSherpaModels)
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
    implementation(files(sherpaAar).builtBy(downloadSherpaAar))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// whisper.cpp JNI is opt-in (-PwhisperNative) so host tests and CI never need
// the NDK; device builds enable it to bundle libwhisper_percept.so (M5).
val whisperNative = providers.gradleProperty("whisperNative").isPresent

// sherpa-onnx ships AARs via GitHub releases only; fetched with a pinned
// sha256. Library modules may only compile against a local AAR (compileOnly);
// :app carries the same AAR as a runtime dependency.
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

android {
    namespace = "org.takopi.percept.perception.audio"
    compileSdk = 34

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (whisperNative) {
            ndk {
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    arguments("-DANDROID_STL=c++_static")
                    cppFlags("-O3")
                }
            }
        }
    }

    if (whisperNative) {
        ndkVersion = "26.1.10909125"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:trace"))
    implementation(libs.tensorflow.lite)
    compileOnly(files(sherpaAar).builtBy(downloadSherpaAar))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

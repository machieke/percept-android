plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// whisper.cpp JNI is opt-in (-PwhisperNative) so host tests and CI never need
// the NDK; device builds enable it to bundle libwhisper_percept.so (M5).
val whisperNative = providers.gradleProperty("whisperNative").isPresent

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
    implementation(libs.mediapipe.tasks.audio)
    testImplementation(libs.junit)
}

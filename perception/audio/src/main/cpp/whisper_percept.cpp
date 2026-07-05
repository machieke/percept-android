// JNI bridge for org.takopi.percept.perception.audio.NativeWhisper.
// Built from the whisper.cpp submodule (third_party/whisper.cpp) for
// arm64-v8a; see perception/audio/build.gradle.kts (-PwhisperNative).

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

struct PerceptWhisper {
    whisper_context *ctx;
    int threads;
};

PerceptWhisper *from_handle(jlong handle) {
    return reinterpret_cast<PerceptWhisper *>(handle);
}

// Mean token log-probability of one segment, in integer micro-units.
jlong segment_avg_logprob_micro(whisper_context *ctx, int segment) {
    const int n_tokens = whisper_full_n_tokens(ctx, segment);
    if (n_tokens <= 0) {
        return 0;
    }
    double sum = 0.0;
    for (int token = 0; token < n_tokens; ++token) {
        sum += whisper_full_get_token_data(ctx, segment, token).plog;
    }
    return static_cast<jlong>(std::llround(sum / n_tokens * 1e6));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_takopi_percept_perception_audio_NativeWhisper_initContext(
    JNIEnv *env, jobject /*thiz*/, jstring model_path, jint threads) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) {
        return 0;
    }
    auto *wrapper = new PerceptWhisper{ctx, threads > 0 ? threads : 4};
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_takopi_percept_perception_audio_NativeWhisper_transcribeNative(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray pcm, jstring language) {
    PerceptWhisper *wrapper = from_handle(handle);
    if (wrapper == nullptr || pcm == nullptr) {
        return nullptr;
    }
    const jsize n_samples = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n_samples));
    env->GetFloatArrayRegion(pcm, 0, n_samples, samples.data());

    std::string lang = "en";
    if (language != nullptr) {
        const char *lang_chars = env->GetStringUTFChars(language, nullptr);
        lang = lang_chars;
        env->ReleaseStringUTFChars(language, lang_chars);
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = wrapper->threads;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    // Pinning the language skips a per-window detection pass; a Moto G84
    // session measured ~31 s per 5 s window with autodetect in the loop.
    params.language = lang.c_str();
    params.detect_language = false;

    if (whisper_full(wrapper->ctx, params, samples.data(), n_samples) != 0) {
        return nullptr;
    }

    const int n_segments = whisper_full_n_segments(wrapper->ctx);
    std::vector<jlong> flat(static_cast<size_t>(n_segments) * 3);
    for (int i = 0; i < n_segments; ++i) {
        // Segment timestamps are in centiseconds.
        flat[i * 3 + 0] = whisper_full_get_segment_t0(wrapper->ctx, i) * 10;
        flat[i * 3 + 1] = whisper_full_get_segment_t1(wrapper->ctx, i) * 10;
        flat[i * 3 + 2] = segment_avg_logprob_micro(wrapper->ctx, i);
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(flat.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_takopi_percept_perception_audio_NativeWhisper_lastSegmentTexts(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    PerceptWhisper *wrapper = from_handle(handle);
    if (wrapper == nullptr) {
        return nullptr;
    }
    const int n_segments = whisper_full_n_segments(wrapper->ctx);
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(n_segments, string_class, nullptr);
    if (result == nullptr) {
        return nullptr;
    }
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(wrapper->ctx, i);
        jstring jtext = env->NewStringUTF(text != nullptr ? text : "");
        env->SetObjectArrayElement(result, i, jtext);
        env->DeleteLocalRef(jtext);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_takopi_percept_perception_audio_NativeWhisper_freeContext(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    PerceptWhisper *wrapper = from_handle(handle);
    if (wrapper != nullptr) {
        whisper_free(wrapper->ctx);
        delete wrapper;
    }
}

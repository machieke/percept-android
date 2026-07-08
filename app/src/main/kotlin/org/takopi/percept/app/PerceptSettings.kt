package org.takopi.percept.app

import android.content.Context
import android.os.Build

class PerceptSettings(context: Context) {
    private val prefs = context.getSharedPreferences("percept-settings", Context.MODE_PRIVATE)

    var endpointUrl: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()

    var bearerToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Remote ASR endpoint (server/asr); blank = on-device ASR only. */
    var asrEndpointUrl: String
        get() = prefs.getString(KEY_ASR_ENDPOINT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ASR_ENDPOINT, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: defaultDeviceId()
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    // --- capture toggles (applied at the next session start) ---

    var captureVideo: Boolean by BoolPref("captureVideo")
    var captureAsr: Boolean by BoolPref("captureAsr")
    var captureAudioTags: Boolean by BoolPref("captureAudioTags")
    var captureAudioChunks: Boolean by BoolPref("captureAudioChunks")
    var captureLocation: Boolean by BoolPref("captureLocation")
    var captureMotion: Boolean by BoolPref("captureMotion")
    var capturePose: Boolean by BoolPref("capturePose")
    var captureEnvironment: Boolean by BoolPref("captureEnvironment")
    var captureNetwork: Boolean by BoolPref("captureNetwork")
    var capturePower: Boolean by BoolPref("capturePower")

    /** True when any audio-consuming feature is on (mic opens at all). */
    val captureMicrophone: Boolean
        get() = captureAsr || captureAudioTags || captureAudioChunks

    // --- quality/cooldown knobs (dashcam storage levers) ---

    var sceneCooldownSeconds: Int
        get() = prefs.getInt("sceneCooldownSeconds", DEFAULT_SCENE_COOLDOWN_S)
        set(value) = prefs.edit().putInt("sceneCooldownSeconds", value.coerceIn(0, 300)).apply()

    var keyframeQuality: Int
        get() = prefs.getInt("keyframeQuality", DEFAULT_KEYFRAME_QUALITY)
        set(value) = prefs.edit().putInt("keyframeQuality", value.coerceIn(10, 100)).apply()

    var minTrackDurationMs: Int
        get() = prefs.getInt("minTrackDurationMs", DEFAULT_MIN_TRACK_MS)
        set(value) = prefs.edit().putInt("minTrackDurationMs", value.coerceIn(0, 60_000)).apply()

    /**
     * Camera analysis resolution (long edge, px). Keyframes are encoded from
     * this frame, so higher values make on-screen text legible to server-side
     * VLM reads at the cost of more per-frame CPU. Detector input is always
     * letterboxed to 320 regardless.
     */
    var videoResolution: Int
        get() = prefs.getInt("videoResolution", DEFAULT_VIDEO_RESOLUTION)
        set(value) = prefs.edit().putInt("videoResolution", value.coerceIn(480, 2160)).apply()

    private inner class BoolPref(private val key: String) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean =
            prefs.getBoolean(key, true)

        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    companion object {
        private const val KEY_ENDPOINT = "endpointUrl"
        private const val KEY_TOKEN = "bearerToken"
        private const val KEY_ASR_ENDPOINT = "asrEndpointUrl"
        private const val KEY_DEVICE_ID = "deviceId"
        const val DEFAULT_SCENE_COOLDOWN_S = 2
        const val DEFAULT_KEYFRAME_QUALITY = 70
        const val DEFAULT_MIN_TRACK_MS = 0
        const val DEFAULT_VIDEO_RESOLUTION = 720

        fun defaultDeviceId(model: String = Build.MODEL ?: "android-device"): String =
            model.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "android-device" }
    }
}

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

    companion object {
        private const val KEY_ENDPOINT = "endpointUrl"
        private const val KEY_TOKEN = "bearerToken"
        private const val KEY_ASR_ENDPOINT = "asrEndpointUrl"
        private const val KEY_DEVICE_ID = "deviceId"

        fun defaultDeviceId(model: String = Build.MODEL ?: "android-device"): String =
            model.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "android-device" }
    }
}

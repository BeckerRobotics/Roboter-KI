package de.beckerrobotics.serviceroboter.app

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("robot_settings", Context.MODE_PRIVATE)
    var onlineEnabled: Boolean
        get() = preferences.getBoolean("online_enabled", true)
        set(value) { preferences.edit().putBoolean("online_enabled", value).apply() }
    /** Optional HTTPS SearXNG endpoint. Empty selects the public German Wikipedia API. */
    var searchEndpoint: String
        get() = preferences.getString("search_endpoint", "").orEmpty()
        set(value) { preferences.edit().putString("search_endpoint", value.trim()).apply() }
    var speechRate: Float
        get() = preferences.getFloat("speech_rate", 0.9f)
        set(value) { preferences.edit().putFloat("speech_rate", value.coerceIn(0.7f, 1.15f)).apply() }
}

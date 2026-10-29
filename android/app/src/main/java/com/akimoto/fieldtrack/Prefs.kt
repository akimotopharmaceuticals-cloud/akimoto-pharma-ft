package com.akimoto.fieldtrack

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Small wrapper around SharedPreferences for settings and day state. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("fieldtrack", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v).apply()

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "akimoto-demo-key") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var intervalMin: Int
        get() = sp.getInt("intervalMin", 5)
        set(v) = sp.edit().putInt("intervalMin", v).apply()

    var consentAt: Long
        get() = sp.getLong("consentAt", 0L)
        set(v) = sp.edit().putLong("consentAt", v).apply()

    var dayActive: Boolean
        get() = sp.getBoolean("dayActive", false)
        set(v) = sp.edit().putBoolean("dayActive", v).apply()

    var dayId: Long
        get() = sp.getLong("dayId", -1L)
        set(v) = sp.edit().putLong("dayId", v).apply()

    var dayStart: Long
        get() = sp.getLong("dayStart", 0L)
        set(v) = sp.edit().putLong("dayStart", v).apply()

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) = sp.edit().putLong("lastSync", v).apply()

    var batteryAsked: Boolean
        get() = sp.getBoolean("batteryAsked", false)
        set(v) = sp.edit().putBoolean("batteryAsked", v).apply()

    // Expense rules (₹). Defaults until the server sends the company's rates.
    var daHq: Float
        get() = sp.getFloat("daHq", 250f)
        set(v) = sp.edit().putFloat("daHq", v).apply()

    var daExHq: Float
        get() = sp.getFloat("daExHq", 400f)
        set(v) = sp.edit().putFloat("daExHq", v).apply()

    var daOut: Float
        get() = sp.getFloat("daOut", 800f)
        set(v) = sp.edit().putFloat("daOut", v).apply()

    var taPerKm: Float
        get() = sp.getFloat("taPerKm", 3.5f)
        set(v) = sp.edit().putFloat("taPerKm", v).apply()

    /** The open work day ends automatically at this hour (24 h clock) if the MR forgets. */
    var dayEndHour: Int
        get() = sp.getInt("dayEndHour", 21)
        set(v) = sp.edit().putInt("dayEndHour", v.coerceIn(12, 23)).apply()

    var locationOffSince: Long
        get() = sp.getLong("locationOffSince", 0L)
        set(v) = sp.edit().putLong("locationOffSince", v).apply()

    val deviceId: String
        get() {
            var id = sp.getString("deviceId", "") ?: ""
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString().substring(0, 8)
                sp.edit().putString("deviceId", id).apply()
            }
            return id
        }
}

package com.diegouc3m.whatsappblock

import android.content.Context
import android.content.SharedPreferences

/** Consent is versioned so an upgrade can never inherit an undisclosed purpose. */
object ConsentStore {
    private const val PREFS_NAME = "privacy_consent"
    private const val CONSENT_REVISION = 1
    private const val KEY_REVISION = "accepted_revision"
    private const val KEY_PAUSED = "blocking_paused"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasConsent(context: Context): Boolean =
        prefs(context).getInt(KEY_REVISION, 0) == CONSENT_REVISION

    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, true)

    fun grant(context: Context) {
        prefs(context).edit()
            .putInt(KEY_REVISION, CONSENT_REVISION)
            .putBoolean(KEY_PAUSED, false)
            .commit()
    }

    fun revoke(context: Context) {
        prefs(context).edit()
            .putInt(KEY_REVISION, 0)
            .putBoolean(KEY_PAUSED, true)
            .commit()
    }

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).commit()
    }

    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}

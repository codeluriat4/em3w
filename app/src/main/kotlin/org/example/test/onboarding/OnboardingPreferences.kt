package org.example.test.onboarding

import android.content.Context

/**
 * Thin wrapper around [android.content.SharedPreferences] that tracks whether the
 * one-time onboarding screen has been completed on this install.
 *
 * The backing preferences file is cleared on uninstall, so onboarding naturally
 * reappears on a fresh install and never again on subsequent opens of the same install.
 */
class OnboardingPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("has_completed_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_completed_onboarding", value).apply()
}

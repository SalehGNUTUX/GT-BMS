// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * The user's app-language choice ("" = follow the system, "ar", "en").
 *
 * Activities use AndroidX per-app locales (AppCompatDelegate.setApplicationLocales). On
 * Android 13+ the system applies that to the whole app; below 13 AppCompat only localises
 * activities, so services (notifications, alarm texts) wrap their context with [wrap].
 */
object AppLanguage {
    private const val PREFS = "gt_bms_prefs"
    private const val KEY = "app_lang"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(ctx: Context, tag: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()

    /** Context in the chosen language for services on Android < 13 (no-op otherwise). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = get(base).ifBlank { return base }
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}

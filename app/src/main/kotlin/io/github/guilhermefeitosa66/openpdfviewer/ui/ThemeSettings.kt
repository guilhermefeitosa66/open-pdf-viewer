package io.github.guilhermefeitosa66.openpdfviewer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Theme choice and the background switch, in SharedPreferences (read once at start). */
class ThemeSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeId by mutableStateOf(prefs.getString(KEY_THEME, AppThemes.DEFAULT_ID) ?: AppThemes.DEFAULT_ID)
        private set

    private val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /**
     * The chosen extra theme, or null for the brand theme. Read from a cached copy of its colors
     * so the first frame never waits for themes.json; the cache is refreshed when the app updates.
     */
    var current by mutableStateOf(restore(context))
        private set

    private fun restore(context: Context): AppTheme? {
        val id = prefs.getString(KEY_THEME, AppThemes.DEFAULT_ID) ?: AppThemes.DEFAULT_ID
        if (id == AppThemes.DEFAULT_ID) return null
        val cachedVersion = prefs.getLong(KEY_CACHE_VERSION, -1L)
        if (cachedVersion == version) {
            prefs.getString(KEY_CACHE, null)?.let(AppThemes::decode)?.takeIf { it.id == id }?.let { return it }
        }
        val theme = AppThemes.find(context, id)
        prefs.edit().apply {
            if (theme != null) putString(KEY_CACHE, AppThemes.encode(theme)) else remove(KEY_CACHE)
            putLong(KEY_CACHE_VERSION, version)
        }.apply()
        return theme
    }

    var backgroundEnabled by mutableStateOf(prefs.getBoolean(KEY_BACKGROUND, true))
        private set

    fun setTheme(theme: AppTheme?) {
        themeId = theme?.id ?: AppThemes.DEFAULT_ID
        current = theme
        prefs.edit().apply {
            putString(KEY_THEME, themeId)
            if (theme != null) putString(KEY_CACHE, AppThemes.encode(theme)) else remove(KEY_CACHE)
            putLong(KEY_CACHE_VERSION, version)
        }.apply()
    }

    fun setBackground(enabled: Boolean) {
        backgroundEnabled = enabled
        prefs.edit().putBoolean(KEY_BACKGROUND, enabled).apply()
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_CACHE = "theme_cache"
        private const val KEY_CACHE_VERSION = "theme_cache_version"

        @Volatile
        private var instance: ThemeSettings? = null

        fun get(context: Context): ThemeSettings =
            instance ?: synchronized(this) {
                instance ?: ThemeSettings(context.applicationContext).also { instance = it }
            }
    }
}

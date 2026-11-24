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

    /** Page color for reading (normal, night, sepia). Global, like the theme: one choice to make. */
    var pageMode by mutableStateOf(io.github.guilhermefeitosa66.openpdfviewer.viewer.PageMode.fromName(prefs.getString(KEY_PAGE_MODE, null)))
        private set

    fun choosePageMode(mode: io.github.guilhermefeitosa66.openpdfviewer.viewer.PageMode) {
        pageMode = mode
        prefs.edit().putString(KEY_PAGE_MODE, mode.name).apply()
    }

    /** Reading options from the viewer menu, global. */
    var keepScreenOn by mutableStateOf(prefs.getBoolean(KEY_KEEP_ON, false))
        private set
    var lockRotation by mutableStateOf(prefs.getBoolean(KEY_LOCK_ROTATION, false))
        private set
    var fullScreen by mutableStateOf(prefs.getBoolean(KEY_FULL_SCREEN, true))
        private set

    /** Page layout: continuous scroll or page by page, two-page spreads, cover alone, right to left. */
    var pagedLayout by mutableStateOf(prefs.getBoolean(KEY_PAGED, false))
        private set
    var twoPages by mutableStateOf(prefs.getBoolean(KEY_TWO_PAGES, true))
        private set
    var coverAlone by mutableStateOf(prefs.getBoolean(KEY_COVER_ALONE, true))
        private set
    var rightToLeft by mutableStateOf(prefs.getBoolean(KEY_RTL, false))
        private set

    fun setLayout(paged: Boolean, twoPages: Boolean, coverAlone: Boolean, rightToLeft: Boolean) {
        pagedLayout = paged
        this.twoPages = twoPages
        this.coverAlone = coverAlone
        this.rightToLeft = rightToLeft
        prefs.edit()
            .putBoolean(KEY_PAGED, paged)
            .putBoolean(KEY_TWO_PAGES, twoPages)
            .putBoolean(KEY_COVER_ALONE, coverAlone)
            .putBoolean(KEY_RTL, rightToLeft)
            .apply()
    }

    fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        prefs.edit().putBoolean(KEY_KEEP_ON, keepScreenOn).apply()
    }

    fun toggleLockRotation() {
        lockRotation = !lockRotation
        prefs.edit().putBoolean(KEY_LOCK_ROTATION, lockRotation).apply()
    }

    fun toggleFullScreen() {
        fullScreen = !fullScreen
        prefs.edit().putBoolean(KEY_FULL_SCREEN, fullScreen).apply()
    }

    fun setBackground(enabled: Boolean) {
        backgroundEnabled = enabled
        prefs.edit().putBoolean(KEY_BACKGROUND, enabled).apply()
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_PAGE_MODE = "page_mode"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val KEY_LOCK_ROTATION = "lock_rotation"
        private const val KEY_FULL_SCREEN = "full_screen"
        private const val KEY_PAGED = "paged"
        private const val KEY_TWO_PAGES = "two_pages"
        private const val KEY_COVER_ALONE = "cover_alone"
        private const val KEY_RTL = "rtl"
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

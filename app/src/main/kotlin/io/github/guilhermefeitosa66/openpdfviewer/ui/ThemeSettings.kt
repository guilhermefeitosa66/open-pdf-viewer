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

    /** Color of the last text markup used (ARGB), and the author name written in annotations. */
    var markupColor by mutableStateOf(prefs.getInt(KEY_MARKUP_COLOR, DEFAULT_MARKUP_COLOR))
        private set
    var authorName by mutableStateOf(prefs.getString(KEY_AUTHOR, "") ?: "")
        private set

    /** Pen for free-hand drawing: color (ARGB with opacity) and width in points. */
    var inkColor by mutableStateOf(prefs.getInt(KEY_INK_COLOR, 0xFF1F3A93.toInt()))
        private set
    var inkWidth by mutableStateOf(prefs.getFloat(KEY_INK_WIDTH, 2f))
        private set

    fun choosePen(argb: Int, width: Float) {
        inkColor = argb
        inkWidth = width
        prefs.edit().putInt(KEY_INK_COLOR, argb).putFloat(KEY_INK_WIDTH, width).apply()
    }

    /** Custom stamp texts the user created (most recent first, at most 6). */
    var customStamps by mutableStateOf(prefs.getString(KEY_STAMPS, "")!!.split('\n').filter { it.isNotBlank() })
        private set

    fun addCustomStamp(text: String) {
        val t = text.trim().replace('\n', ' ')
        if (t.isEmpty()) return
        customStamps = (listOf(t) + customStamps.filterNot { it == t }).take(6)
        prefs.edit().putString(KEY_STAMPS, customStamps.joinToString("\n")).apply()
    }

    fun removeCustomStamp(text: String) {
        customStamps = customStamps.filterNot { it == text }
        prefs.edit().putString(KEY_STAMPS, customStamps.joinToString("\n")).apply()
    }

    fun chooseMarkupColor(argb: Int) {
        markupColor = argb
        prefs.edit().putInt(KEY_MARKUP_COLOR, argb).apply()
    }

    fun chooseAuthorName(name: String) {
        authorName = name.trim()
        prefs.edit().putString(KEY_AUTHOR, authorName).apply()
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
        private const val KEY_MARKUP_COLOR = "markup_color"
        private const val KEY_AUTHOR = "author"
        private const val KEY_INK_COLOR = "ink_color"
        private const val KEY_INK_WIDTH = "ink_width"
        private const val KEY_STAMPS = "custom_stamps"
        const val DEFAULT_MARKUP_COLOR = 0xFFFFE066.toInt()
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

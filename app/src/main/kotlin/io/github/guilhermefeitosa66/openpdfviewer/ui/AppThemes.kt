package io.github.guilhermefeitosa66.openpdfviewer.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

/**
 * The libre-apps shared themes (copied from libre-apps-themes into assets/themes/). The brand
 * theme, which follows the system light/dark setting, is built in and stays the default.
 */
data class ThemeUi(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accent2: Color,
    val selection: Color,
    val searchHit: Color,
    val link: Color,
    val error: Color,
)

data class AppTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val ui: ThemeUi,
    /** Asset path of the portrait background, or null when the theme has none. */
    val background: String?,
    /** Small preview of the background for the settings grid. */
    val thumbnail: String?,
)

object AppThemes {
    const val DEFAULT_ID = "libre-apps"

    @Volatile
    private var cache: List<AppTheme>? = null

    /** Extra themes from assets/themes/themes.json, parsed once (a few kB). */
    fun extras(context: Context): List<AppTheme> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    fun find(context: Context, id: String): AppTheme? =
        if (id == DEFAULT_ID) null else extras(context).firstOrNull { it.id == id }

    private fun load(context: Context): List<AppTheme> = runCatching {
        val json = context.assets.open("themes/themes.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("themes")
        List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            val id = t.getString("id")
            val u = t.getJSONObject("ui")
            fun c(key: String) = parse(u.getString(key))
            // Every shared theme ships a background; no file checks here, this runs before the
            // first frame. A missing file only means no image (the loader returns null).
            val hasBg = !t.isNull("background")
            val bgAsset = "themes/backgrounds/$id-portrait.webp"
            AppTheme(
                id = id,
                name = t.getString("name"),
                dark = t.optString("scheme") == "dark",
                ui = ThemeUi(
                    bg = c("bg"), surface = c("surface"), surfaceAlt = c("surfaceAlt"),
                    border = c("border"), text = c("text"), textMuted = c("textMuted"),
                    accent = c("accent"), onAccent = c("onAccent"), accent2 = c("accent2"),
                    selection = c("selection"), searchHit = c("searchHit"), link = c("link"),
                    error = c("error"),
                ),
                background = bgAsset.takeIf { hasBg },
                thumbnail = "themes/backgrounds/$id-thumb.webp".takeIf { hasBg },
            )
        }
    }.getOrDefault(emptyList())

    private fun parse(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

    /**
     * Compact form of a theme for SharedPreferences, so start-up never reads themes.json:
     * id|name|dark|background|thumbnail|13 ARGB colors.
     */
    fun encode(t: AppTheme): String {
        val u = t.ui
        val colors = listOf(u.bg, u.surface, u.surfaceAlt, u.border, u.text, u.textMuted, u.accent,
            u.onAccent, u.accent2, u.selection, u.searchHit, u.link, u.error)
        return (listOf(t.id, t.name, t.dark.toString(), t.background.orEmpty(), t.thumbnail.orEmpty()) +
            colors.map { it.toArgb().toString() }).joinToString("|")
    }

    fun decode(s: String): AppTheme? = runCatching {
        val p = s.split('|')
        val c = p.drop(5).map { Color(it.toInt()) }
        AppTheme(
            id = p[0], name = p[1], dark = p[2].toBoolean(),
            background = p[3].ifEmpty { null }, thumbnail = p[4].ifEmpty { null },
            ui = ThemeUi(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12]),
        )
    }.getOrNull()
}

/** WCAG contrast ratio between two opaque colors. */
fun contrast(a: Color, b: Color): Float {
    fun lum(c: Color): Float {
        fun ch(x: Float) = if (x <= 0.03928f) x / 12.92f else Math.pow(((x + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }
    val la = lum(a)
    val lb = lum(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

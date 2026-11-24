package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A page the reader marked, with a title they can edit. Kept by the app; the PDF never changes. */
data class Bookmark(val page: Int, val title: String, val createdAt: Long)

/** One small JSON file per document under files/bookmarks/, named by the document key. */
class Bookmarks(context: Context) {
    private val dir = File(context.filesDir, "bookmarks")

    private fun fileFor(uri: Uri) = File(dir, RecentFile.keyOf(uri.toString()) + ".json")

    suspend fun load(uri: Uri): List<Bookmark> = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(uri)
            if (!f.exists()) return@withContext emptyList()
            decode(f.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun save(uri: Uri, list: List<Bookmark>) = withContext(Dispatchers.IO) {
        val f = fileFor(uri)
        if (list.isEmpty()) {
            f.delete()
            return@withContext
        }
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(encode(list))
        tmp.renameTo(f)
    }

    companion object {
        fun encode(list: List<Bookmark>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("page", it.page).put("title", it.title).put("createdAt", it.createdAt)) }
        }.toString()

        fun decode(json: String): List<Bookmark> {
            val arr = JSONArray(json)
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.getInt("page"), o.optString("title"), o.optLong("createdAt"))
            }.sortedBy { it.page }
        }
    }
}

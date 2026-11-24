package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RecentFile(
    val uri: String,
    val name: String,
    val pageCount: Int,
    val lastPage: Int,
    val openedAt: Long,
    /** Where the reader was: offset into [lastPage] (0..1), zoom, horizontal position (0..1). */
    val offset: Float = 0f,
    val zoom: Float = 1f,
    val scrollX: Float = 0.5f,
) {
    val key: String get() = keyOf(uri)

    companion object {
        fun keyOf(uri: String): String =
            MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Recently opened files: a small JSON file in app storage plus one thumbnail per file in the
 * cache. No database, so nothing extra to initialize on cold start.
 */
class RecentFiles(context: Context) {
    private val file = File(context.filesDir, "recent.json")
    val thumbDir = File(context.cacheDir, "thumbs")
    private val receivedDir = File(context.filesDir, "received")
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<RecentFile>?>(null)
    val items: StateFlow<List<RecentFile>?> = _items.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (_items.value != null) return@withLock
        _items.value = withContext(Dispatchers.IO) {
            read().also { list ->
                // Private copies of files no longer in the list go away at the next start
                // (not on removal, so "Undo" keeps working).
                val keep = list.mapTo(HashSet()) { it.key + ".pdf" }
                receivedDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            }
        }
    }

    fun find(uri: Uri): RecentFile? = _items.value?.firstOrNull { it.uri == uri.toString() }

    suspend fun upsert(entry: RecentFile) = update { list ->
        listOf(entry) + list.filterNot { it.uri == entry.uri }
    }

    suspend fun setPosition(uri: Uri, page: Int, offset: Float, zoom: Float, scrollX: Float) = update { list ->
        list.map {
            if (it.uri == uri.toString()) it.copy(lastPage = page, offset = offset, zoom = zoom, scrollX = scrollX) else it
        }
    }

    suspend fun remove(entry: RecentFile) {
        update { list -> list.filterNot { it.uri == entry.uri } }
    }

    suspend fun restore(entry: RecentFile) = update { list ->
        (list.filterNot { it.uri == entry.uri } + entry).sortedByDescending { it.openedAt }
    }

    fun thumbFile(entry: RecentFile): File = File(thumbDir, entry.key + ".png")
    fun thumbFile(uri: Uri): File = File(thumbDir, RecentFile.keyOf(uri.toString()) + ".png")

    private suspend fun update(transform: (List<RecentFile>) -> List<RecentFile>) {
        load()
        mutex.withLock {
            val next = transform(_items.value.orEmpty()).take(MAX_ITEMS)
            _items.value = next
            withContext(Dispatchers.IO) { write(next) }
        }
    }

    private fun read(): List<RecentFile> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RecentFile(
                uri = o.getString("uri"),
                name = o.getString("name"),
                pageCount = o.optInt("pageCount", 0),
                lastPage = o.optInt("lastPage", 0),
                openedAt = o.optLong("openedAt", 0L),
                offset = o.optDouble("offset", 0.0).toFloat(),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                scrollX = o.optDouble("scrollX", 0.5).toFloat(),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("name", it.name)
                    .put("pageCount", it.pageCount)
                    .put("lastPage", it.lastPage)
                    .put("openedAt", it.openedAt)
                    .put("offset", it.offset.toDouble())
                    .put("zoom", it.zoom.toDouble())
                    .put("scrollX", it.scrollX.toDouble())
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    companion object {
        const val MAX_ITEMS = 50

        @Volatile
        private var instance: RecentFiles? = null

        fun get(context: Context): RecentFiles =
            instance ?: synchronized(this) {
                instance ?: RecentFiles(context.applicationContext).also { instance = it }
            }
    }
}

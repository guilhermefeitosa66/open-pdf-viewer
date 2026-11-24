package io.github.guilhermefeitosa66.openpdfviewer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** A file shown by the library (favorite or found in a folder). */
data class LibraryFile(val uri: String, val name: String, val size: Long = -1, val modified: Long = 0)

/** A folder the user granted through the system picker. */
data class LibraryFolder(val uri: String, val name: String)

/**
 * The local library: starred files and folders the user opened to the app with
 * ACTION_OPEN_DOCUMENT_TREE (persisted grants, no broad storage permission). Two small JSON
 * files in app storage, loaded when the home screen asks.
 */
class Library private constructor(context: Context) {
    private val app = context.applicationContext
    private val favFile = File(app.filesDir, "favorites.json")
    private val folderFile = File(app.filesDir, "folders.json")
    private val _favorites = MutableStateFlow<List<LibraryFile>>(emptyList())
    private val _folders = MutableStateFlow<List<LibraryFolder>>(emptyList())
    val favorites: StateFlow<List<LibraryFile>> = _favorites.asStateFlow()
    val folders: StateFlow<List<LibraryFolder>> = _folders.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        _favorites.value = runCatching { decodeFiles(favFile.readText()) }.getOrDefault(emptyList())
        _folders.value = runCatching {
            val arr = JSONArray(folderFile.readText())
            List(arr.length()) { i -> arr.getJSONObject(i).let { LibraryFolder(it.getString("uri"), it.optString("name")) } }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(uri: String) = _favorites.value.any { it.uri == uri }

    suspend fun toggleFavorite(file: LibraryFile) {
        load()
        _favorites.value = if (isFavorite(file.uri)) _favorites.value.filterNot { it.uri == file.uri }
        else (_favorites.value + file).sortedBy { it.name.lowercase() }
        withContext(Dispatchers.IO) { favFile.writeText(encodeFiles(_favorites.value)) }
    }

    /** Keeps a granted folder (the grant itself is persisted by the caller's picker result). */
    suspend fun addFolder(tree: Uri) {
        load()
        runCatching {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = folderName(tree)
        if (_folders.value.none { it.uri == tree.toString() }) {
            _folders.value = _folders.value + LibraryFolder(tree.toString(), name)
            saveFolders()
        }
    }

    suspend fun removeFolder(folder: LibraryFolder) {
        _folders.value = _folders.value.filterNot { it.uri == folder.uri }
        runCatching {
            app.contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        saveFolders()
    }

    private suspend fun saveFolders() = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        _folders.value.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        folderFile.writeText(arr.toString())
    }

    private fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':') ?: "Folder"

    /**
     * The PDFs inside a granted folder and its subfolders (up to [maxDepth] levels and
     * [maxFiles] files, so a huge tree never stalls the screen).
     */
    suspend fun scan(folder: LibraryFolder, maxDepth: Int = 4, maxFiles: Int = 5000): List<LibraryFile> =
        withContext(Dispatchers.IO) {
            val tree = Uri.parse(folder.uri)
            val out = ArrayList<LibraryFile>()
            val queue = ArrayDeque<Pair<String, Int>>()
            runCatching { queue.add(DocumentsContract.getTreeDocumentId(tree) to 0) }
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            while (queue.isNotEmpty() && out.size < maxFiles) {
                coroutineContext.ensureActive()
                val (id, depth) = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                runCatching {
                    app.contentResolver.query(children, cols, null, null, null)?.use { c ->
                        while (c.moveToNext() && out.size < maxFiles) {
                            val docId = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            val mime = c.getString(2) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < maxDepth) queue.add(docId to depth + 1)
                            } else if (mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                                out += LibraryFile(uri.toString(), name, if (c.isNull(3)) -1 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                            }
                        }
                    }
                }
            }
            out
        }

    companion object {
        @Volatile
        private var instance: Library? = null

        fun get(context: Context): Library =
            instance ?: synchronized(this) { instance ?: Library(context).also { instance = it } }

        fun encodeFiles(list: List<LibraryFile>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name).put("size", it.size).put("modified", it.modified)) }
        }.toString()

        fun decodeFiles(json: String): List<LibraryFile> {
            val arr = JSONArray(json)
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                LibraryFile(o.getString("uri"), o.optString("name"), o.optLong("size", -1), o.optLong("modified", 0))
            }
        }

        enum class Sort { NAME, DATE, SIZE }

        /** Filters by a case-insensitive name fragment, then sorts (newest and largest first). */
        fun filterAndSort(files: List<LibraryFile>, query: String, sort: Sort): List<LibraryFile> {
            val q = query.trim()
            val filtered = if (q.isEmpty()) files else files.filter { it.name.contains(q, ignoreCase = true) }
            return when (sort) {
                Sort.NAME -> filtered.sortedBy { it.name.lowercase() }
                Sort.DATE -> filtered.sortedByDescending { it.modified }
                Sort.SIZE -> filtered.sortedByDescending { it.size }
            }
        }
    }
}

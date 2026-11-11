package app.showcase.screensaver.data

import app.showcase.screensaver.ShowcaseApp
import app.showcase.screensaver.plex.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class NotConfiguredException : IOException("Not set up yet - open Showcase to sign in to Plex")

/**
 * The list of artwork to show. Cached in memory and on disk, so the screensaver can start
 * instantly (even before Plex answers) and keeps working from the last list if Plex is down.
 */
class LibraryRepository(private val app: ShowcaseApp) {

    private class Cached(val key: String, val at: Long, val items: List<Item>)

    private val file = File(app.filesDir, "library.json")
    private val mutex = Mutex()
    private var memory: Cached? = null

    /** Changing server or excluded libraries invalidates the cache. */
    private fun cacheKey(p: Prefs) = listOf(p.serverUrl, p.serverId, p.excluded.sorted().joinToString(",")).joinToString("|")

    /**
     * Items no older than [maxAgeMs]. Long.MAX_VALUE = "anything cached is fine" (instant start).
     * If Plex can't be reached, falls back to the cached list (however old) rather than failing.
     */
    suspend fun items(maxAgeMs: Long): List<Item> = mutex.withLock {
        val p = app.prefs
        if (!p.isConfigured) throw NotConfiguredException()
        val key = cacheKey(p)
        val cached = memory?.takeIf { it.key == key } ?: readFile()?.takeIf { it.key == key }?.also { memory = it }
        if (cached != null && System.currentTimeMillis() - cached.at < maxAgeMs) return@withLock cached.items
        try {
            val fresh = app.plex.items(p.serverUrl, p.token, p.excluded)
            Cached(key, System.currentTimeMillis(), fresh).also { memory = it; writeFile(it) }.items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.items ?: throw e
        }
    }

    suspend fun clear() = mutex.withLock {
        memory = null
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun readFile(): Cached? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map {
                val i = arr.getJSONObject(it)
                Item(i.getString("k"), i.optString("t"), i.optBoolean("p"))
            }
            Cached(o.getString("key"), o.getLong("at"), items)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writeFile(c: Cached) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        c.items.forEach { arr.put(JSONObject().put("k", it.ratingKey).put("t", it.title).put("p", it.hasThumb)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("key", c.key).put("at", c.at).put("items", arr).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

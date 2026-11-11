package app.showcase.screensaver.plex

import android.os.Build
import android.os.SystemClock
import app.showcase.screensaver.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlexException(message: String, val httpCode: Int = 0) : IOException(message)

/** Sign-in PIN: the user enters [code] at plex.tv/link. */
data class Pin(val id: Long, val code: String)

enum class Kind(val rank: Int, val label: String) {
    LOCAL(0, "on your network"),
    REMOTE(1, "over the internet"),
    RELAY(2, "through Plex Relay (slow)"),
}

data class Connection(val uri: String, val kind: Kind)

data class ServerResource(
    val id: String,
    val name: String,
    val owned: Boolean,
    val owner: String,
    val online: Boolean,
    val accessToken: String,
    val connections: List<Connection>,
)

data class AddressTest(val uri: String, val kind: Kind, val ok: Boolean, val ms: Long, val error: String?, val name: String?)

data class Library(val key: String, val title: String, val type: String) {
    val supported: Boolean get() = type == "movie" || type == "show"
}

data class Item(val ratingKey: String, val title: String, val hasThumb: Boolean)

data class ItemInfo(val year: Int?, val genres: List<String>, val rating: String, val logoPath: String?) {
    /** "2021  ·  Action / Thriller  ·  12A" */
    val detail: String get() = listOfNotNull(year?.toString(), genres.joinToString(" / ").ifEmpty { null }, rating.ifEmpty { null })
        .joinToString("  ·  ")
}

/**
 * Talks to plex.tv (sign-in, server discovery) and to your Plex Media Server.
 * A port of the web version's plexapi.py - same flow, same address-picking rules.
 */
class PlexClient(private val clientId: String, private val http: OkHttpClient) {

    private val testHttp = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ headers
    fun headers(token: String? = null, accept: String = "application/json"): Headers =
        Headers.Builder().apply {
            add("Accept", accept)
            add("X-Plex-Product", PRODUCT)
            add("X-Plex-Version", BuildConfig.VERSION_NAME)
            add("X-Plex-Client-Identifier", clientId)
            add("X-Plex-Platform", "Android")
            add("X-Plex-Platform-Version", Build.VERSION.RELEASE.ascii())
            add("X-Plex-Device", Build.MODEL.ascii())
            add("X-Plex-Device-Name", "$PRODUCT (${Build.MODEL.ascii()})")
            if (!token.isNullOrEmpty()) add("X-Plex-Token", token)   // header, never in URLs
        }.build()

    private fun String?.ascii(): String =
        (this ?: "").filter { it in ' '..'~' }.ifBlank { "Android" }

    private suspend fun call(url: String, token: String? = null, post: Boolean = false,
                             client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).headers(headers(token))
        if (post) builder.post(ByteArray(0).toRequestBody(null))
        client.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) throw PlexException("Plex answered with error ${r.code}", r.code)
            r.body?.string() ?: ""
        }
    }

    // ------------------------------------------------------------------ plex.tv sign-in
    /** A short code for plex.tv/link - the usual way to sign in on a TV. */
    suspend fun createPin(): Pin {
        val o = JSONObject(call("$PLEX_TV/api/v2/pins", post = true))
        return Pin(o.getLong("id"), o.getString("code"))
    }

    /** The account token once the user has entered the code, otherwise null. */
    suspend fun checkPin(pin: Pin): String? {
        val o = JSONObject(call("$PLEX_TV/api/v2/pins/${pin.id}"))
        return if (o.isNull("authToken")) null else o.optString("authToken").ifEmpty { null }
    }

    suspend fun accountName(token: String): String {
        val o = JSONObject(call("$PLEX_TV/api/v2/user", token))
        return listOf("username", "title", "email").map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: "Plex user"
    }

    suspend fun servers(token: String): List<ServerResource> {
        val arr = JSONArray(call("$CLIENTS/api/v2/resources?includeHttps=1&includeRelay=1", token))
        val out = mutableListOf<ServerResource>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if ("server" !in r.optString("provides").split(",")) continue
            out += ServerResource(
                id = r.optString("clientIdentifier"),
                name = r.optString("name").ifBlank { "Plex server" },
                owned = r.optBoolean("owned"),
                owner = r.optString("sourceTitle"),
                online = r.optBoolean("presence", true),
                accessToken = r.optString("accessToken").ifEmpty { token },
                connections = candidates(r.optJSONArray("connections") ?: JSONArray()),
            )
        }
        return out.sortedWith(compareByDescending<ServerResource> { it.owned }.thenByDescending { it.online })
    }

    /** Every address worth trying, best kind first. */
    private fun candidates(conns: JSONArray): List<Connection> {
        val out = LinkedHashMap<String, Connection>()
        for (i in 0 until conns.length()) {
            val c = conns.getJSONObject(i)
            val kind = when {
                c.optBoolean("relay") -> Kind.RELAY
                c.optBoolean("local") -> Kind.LOCAL
                else -> Kind.REMOTE
            }
            c.optString("uri").trimEnd('/').takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, Connection(it, kind)) }
            // Plain http://ip:port too - some routers block the *.plex.direct names used by the https address.
            val address = c.optString("address")
            val port = c.optInt("port")
            if (kind == Kind.LOCAL && address.isNotEmpty() && port > 0) {
                val host = if (':' in address) "[$address]" else address
                val uri = "http://$host:$port"
                out.putIfAbsent(uri, Connection(uri, kind))
            }
        }
        return out.values.sortedBy { it.kind.rank }
    }

    // ------------------------------------------------------------------ choosing an address
    /** Does this address answer, accept the token, and belong to the expected server? */
    suspend fun testAddress(uri: String, token: String, machineId: String? = null, kind: Kind = Kind.LOCAL): AddressTest {
        val started = SystemClock.elapsedRealtime()
        return try {
            val mc = JSONObject(call("$uri/", token, client = testHttp)).getJSONObject("MediaContainer")
            val id = mc.optString("machineIdentifier")
            val ok = machineId.isNullOrEmpty() || id.isEmpty() || id == machineId
            AddressTest(uri, kind, ok, SystemClock.elapsedRealtime() - started,
                if (ok) null else "a different server answered", mc.optString("friendlyName").ifEmpty { null })
        } catch (e: Exception) {
            val why = when {
                e is PlexException && e.httpCode == 401 -> "token rejected"
                e is PlexException -> "error ${e.httpCode}"
                else -> e.javaClass.simpleName
            }
            AddressTest(uri, kind, false, SystemClock.elapsedRealtime() - started, why, null)
        }
    }

    /** Tests every address at once; returns the best working one (LAN > internet > relay, https preferred) and all results. */
    suspend fun pickConnection(server: ServerResource): Pair<AddressTest?, List<AddressTest>> = coroutineScope {
        val results = server.connections
            .map { c -> async { testAddress(c.uri, server.accessToken, server.id, c.kind) } }
            .awaitAll()
        val best = results.filter { it.ok }.minWithOrNull(
            compareBy<AddressTest> { it.kind.rank }
                .thenBy { if (it.uri.startsWith("https")) 0 else 1 }
                .thenBy { it.ms })
        best to results
    }

    // ------------------------------------------------------------------ your Plex server
    private suspend fun container(base: String, token: String, path: String): JSONObject =
        JSONObject(call(base.trimEnd('/') + path, token)).getJSONObject("MediaContainer")

    suspend fun libraries(base: String, token: String): List<Library> {
        val dirs = container(base, token, "/library/sections").optJSONArray("Directory") ?: JSONArray()
        return (0 until dirs.length()).map { i ->
            val d = dirs.getJSONObject(i)
            Library(d.optString("key"), d.optString("title"), d.optString("type"))
        }
    }

    /** Every movie/show with background art, from the libraries not excluded. One bad library doesn't stop the rest. */
    suspend fun items(base: String, token: String, excluded: Set<String>): List<Item> {
        val out = mutableListOf<Item>()
        var lastError: Exception? = null
        for (lib in libraries(base, token)) {
            if (!lib.supported || lib.title in excluded) continue
            try {
                val md = container(base, token,
                    "/library/sections/${lib.key}/all?includeFields=ratingKey,title,art,thumb")
                    .optJSONArray("Metadata") ?: continue
                for (i in 0 until md.length()) {
                    val m = md.getJSONObject(i)
                    val key = m.optString("ratingKey")
                    if (key.isNotEmpty() && m.optString("art").isNotEmpty()) {
                        out += Item(key, m.optString("title"), m.optString("thumb").isNotEmpty())
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (out.isEmpty() && lastError != null) throw lastError
        return out
    }

    /** Year, genres, rating and the clearLogo path for one item (one small request per slide). */
    suspend fun info(base: String, token: String, ratingKey: String): ItemInfo {
        val md = container(base, token, "/library/metadata/$ratingKey").optJSONArray("Metadata")
        val m = md?.optJSONObject(0) ?: return ItemInfo(null, emptyList(), "", null)
        val genres = m.optJSONArray("Genre")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("tag")?.ifEmpty { null } } }
            ?: emptyList()
        var logo: String? = null
        m.optJSONArray("Image")?.let { imgs ->
            for (i in 0 until imgs.length()) {
                val img = imgs.optJSONObject(i) ?: continue
                val url = img.optString("url")
                if (img.optString("type") == "clearLogo" && url.startsWith("/")) { logo = url; break }
            }
        }
        return ItemInfo(
            year = m.optInt("year").takeIf { it > 0 },
            genres = genres.take(2),
            rating = m.optString("contentRating").substringAfterLast('/').trim(),   // "gb/12A" -> "12A"
            logoPath = logo,
        )
    }

    fun logoUrl(base: String, path: String): String = base.trimEnd('/') + path

    /** Background art: the original, or (quality "screen") resized by Plex to fit [w]x[h]. */
    fun artUrl(base: String, ratingKey: String, quality: String, w: Int, h: Int): String =
        imageUrl(base, "/library/metadata/$ratingKey/art", if (quality == "screen") w else 0, h)

    fun thumbUrl(base: String, ratingKey: String): String =
        imageUrl(base, "/library/metadata/$ratingKey/thumb", 600, 900)

    private fun imageUrl(base: String, source: String, w: Int, h: Int): String {
        val root = base.trimEnd('/')
        if (w <= 0) return root + source
        return "$root/photo/:/transcode".toHttpUrl().newBuilder()
            .addQueryParameter("width", w.toString())
            .addQueryParameter("height", h.toString())
            .addQueryParameter("minSize", "1")
            .addQueryParameter("upscale", "0")
            .addQueryParameter("url", source)
            .build().toString()
    }

    companion object {
        const val PRODUCT = "Showcase"
        const val PLEX_TV = "https://plex.tv"
        const val CLIENTS = "https://clients.plex.tv"
    }
}

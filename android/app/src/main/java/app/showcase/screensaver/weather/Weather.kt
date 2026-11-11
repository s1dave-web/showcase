package app.showcase.screensaver.weather

import app.showcase.screensaver.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/** A city from the search, e.g. "Manchester" / "England, United Kingdom". */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double) {
    val label: String get() = if (detail.isEmpty()) name else "$name, ${detail.substringBefore(",")}"
}

/** Current conditions. [code] is the WMO weather code Open-Meteo uses. */
data class Conditions(val tempC: Double, val code: Int, val isDay: Boolean, val at: Long) {

    fun temperature(fahrenheit: Boolean): String =
        (if (fahrenheit) tempC * 9 / 5 + 32 else tempC).roundToInt().toString() + "°"

    val description: String
        get() = when (code) {
            0 -> if (isDay) "Sunny" else "Clear"
            1 -> if (isDay) "Mostly sunny" else "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66, 80, 81 -> "Rain"
            65, 67, 82 -> "Heavy rain"
            71, 73, 75, 77, 85, 86 -> "Snow"
            95, 96, 99 -> "Thunderstorms"
            else -> ""
        }

    val icon: Int
        get() = when (code) {
            0, 1 -> if (isDay) R.drawable.wx_clear_day else R.drawable.wx_clear_night
            2 -> if (isDay) R.drawable.wx_partly_day else R.drawable.wx_partly_night
            3 -> R.drawable.wx_cloudy
            45, 48 -> R.drawable.wx_fog
            71, 73, 75, 77, 85, 86 -> R.drawable.wx_snow
            95, 96, 99 -> R.drawable.wx_thunder
            in 51..67, in 80..82 -> R.drawable.wx_rain
            else -> R.drawable.wx_cloudy
        }

    /** "12.3|2|1|1731300000000" - stored in Prefs so the screen saver can show it instantly. */
    fun encode() = "$tempC|$code|${if (isDay) 1 else 0}|$at"

    companion object {
        fun decode(s: String): Conditions? = s.split("|").takeIf { it.size == 4 }?.let {
            runCatching { Conditions(it[0].toDouble(), it[1].toInt(), it[2] == "1", it[3].toLong()) }.getOrNull()
        }
    }
}

/**
 * Weather from Open-Meteo (open-meteo.com): free, no account or API key. You pick a city once,
 * so the app never needs your location. Data licensed CC BY 4.0 - attribution is shown in settings.
 */
class WeatherClient(private val http: OkHttpClient) {

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Weather service answered with error ${r.code}")
            JSONObject(r.body?.string() ?: "{}")
        }
    }

    suspend fun search(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build().toString()
        val results = get(url).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(
                name = r.optString("name"),
                detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                lat = r.getDouble("latitude"),
                lon = r.getDouble("longitude"),
            )
        }
    }

    suspend fun current(lat: String, lon: String): Conditions {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat)
            .addQueryParameter("longitude", lon)
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("timezone", "auto")
            .build().toString()
        val c = get(url).getJSONObject("current")
        return Conditions(c.getDouble("temperature_2m"), c.getInt("weather_code"), c.optInt("is_day", 1) == 1,
            System.currentTimeMillis())
    }
}

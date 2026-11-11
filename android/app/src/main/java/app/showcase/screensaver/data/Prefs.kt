package app.showcase.screensaver.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Every setting, stored in the app's private SharedPreferences (not readable by other apps,
 * excluded from backups via allowBackup=false). Mirrors the web version's .env options.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("showcase", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ identity & connection
    /** Stable per-install ID that Plex shows under "Authorized Devices". */
    val clientId: String
        get() = sp.getString("client_id", null)
            ?: UUID.randomUUID().toString().also { sp.edit().putString("client_id", it).apply() }

    var token by string("token")
    var serverUrl by string("server_url")
    var serverName by string("server_name")
    var serverId by string("server_id")
    var username by string("username")

    val isConfigured: Boolean get() = token.isNotEmpty() && serverUrl.isNotEmpty()

    fun saveConnection(token: String, url: String, name: String, id: String, user: String) {
        sp.edit()
            .putString("token", token).putString("server_url", url).putString("server_name", name)
            .putString("server_id", id).putString("username", user)
            .apply()
    }

    fun signOut() {
        sp.edit().remove("token").remove("server_url").remove("server_name")
            .remove("server_id").remove("username").remove("excluded").apply()
    }

    // ------------------------------------------------------------------ library
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet())?.toSet() ?: emptySet()
        set(value) { sp.edit().putStringSet("excluded", value.toSet()).apply() }

    var refreshMinutes by int("refresh_minutes", 30)

    // ------------------------------------------------------------------ display
    var intervalSeconds by int("interval_seconds", 10)
    var transitionMs by int("transition_ms", 1500)
    /** "original" = artwork as stored in Plex; "screen" = Plex shrinks it to the screen first. */
    var artQuality by string("art_quality", "original")
    var animations: Set<String>
        get() = sp.getStringSet("animations", null)?.toSet() ?: ALL_ANIMATIONS.toSet()
        set(value) { sp.edit().putStringSet("animations", value.toSet()).apply() }
    var showTitle by bool("show_title", true)
    var showClock by bool("show_clock", true)
    var clock24h by bool("clock_24h", true)
    var showPoster by bool("show_poster", true)
    var pixelShift by bool("pixel_shift", true)
    var showDate by bool("show_date", true)
    var titleLogos by bool("title_logos", true)          // the film's clearLogo instead of title text
    var showDetails by bool("show_details", true)        // "2021 · Action · 12A"

    // ------------------------------------------------------------------ night dimming (minutes after midnight, -1 = off)
    var dimStart by int("dim_start", -1)
    var dimEnd by int("dim_end", -1)
    var dimLevel by int("dim_level", 60)                 // percent darker
    val dimEnabled: Boolean get() = dimStart >= 0 && dimEnd >= 0 && dimStart != dimEnd

    /** Is [minuteOfDay] inside the dimming window? Handles windows that cross midnight (22:00-07:00). */
    fun isNight(minuteOfDay: Int): Boolean = when {
        !dimEnabled -> false
        dimStart < dimEnd -> minuteOfDay in dimStart until dimEnd
        else -> minuteOfDay >= dimStart || minuteOfDay < dimEnd
    }

    // ------------------------------------------------------------------ weather (off until a city is chosen)
    var weatherPlace by string("weather_place")          // "Manchester, England" - empty = weather off
    var weatherLat by string("weather_lat")
    var weatherLon by string("weather_lon")
    var fahrenheit by bool("fahrenheit", false)
    /** Last reading, so the screen saver can show it instantly: "tempC|code|isDay|epochMillis". */
    var weatherCache by string("weather_cache")

    val weatherEnabled: Boolean get() = weatherPlace.isNotEmpty() && weatherLat.isNotEmpty() && weatherLon.isNotEmpty()

    fun setWeatherPlace(name: String, lat: Double, lon: Double) {
        sp.edit().putString("weather_place", name).putString("weather_lat", lat.toString())
            .putString("weather_lon", lon.toString()).remove("weather_cache").apply()
    }

    fun clearWeather() {
        sp.edit().remove("weather_place").remove("weather_lat").remove("weather_lon").remove("weather_cache").apply()
    }

    // ------------------------------------------------------------------ helpers
    private fun string(key: String, default: String = "") = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    private fun int(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    companion object {
        val ZOOM = listOf("zoomIn", "zoomOut")
        val PAN = listOf("moveLeft", "moveRight", "moveUp", "moveDown")
        val COLOUR = listOf("goGray", "goColor")
        val ALL_ANIMATIONS = ZOOM + PAN + COLOUR
    }
}

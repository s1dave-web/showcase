package app.showcase.screensaver.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.showcase.screensaver.ShowcaseApp
import app.showcase.screensaver.plex.Kind
import app.showcase.screensaver.plex.Library
import app.showcase.screensaver.plex.PlexException
import app.showcase.screensaver.plex.ServerResource
import app.showcase.screensaver.weather.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

enum class Screen { Welcome, SignIn, PickServer, Home, Libraries, Display, Weather, Screensaver }

/** The setup flow's state and actions. Survives screen rotation on phones. */
class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ShowcaseApp
    val prefs = app.prefs

    var screen by mutableStateOf(if (prefs.isConfigured) Screen.Home else Screen.Welcome)
        private set
    var busy by mutableStateOf<String?>(null)          // "Testing addresses…" etc.
        private set
    var error by mutableStateOf<String?>(null)
    var pinCode by mutableStateOf<String?>(null)
        private set
    var accountName by mutableStateOf("")
        private set
    var servers by mutableStateOf<List<ServerResource>>(emptyList())
        private set
    var libraries by mutableStateOf<List<Library>?>(null)
        private set
    var connectionStatus by mutableStateOf<String?>(null)
        private set
    var weatherResults by mutableStateOf<List<Place>?>(null)
        private set
    /** Bumped whenever a setting changes, so screens re-read prefs. */
    var settingsVersion by mutableIntStateOf(0)
        private set

    private var accountToken: String? = null
    private var signInJob: Job? = null

    fun go(to: Screen) {
        error = null
        screen = to
        when (to) {
            Screen.Home -> checkConnection()
            Screen.Libraries -> loadLibraries()
            Screen.Weather -> weatherResults = null
            else -> Unit
        }
    }

    fun back() {
        when (screen) {
            Screen.SignIn -> { cancelSignIn(); go(if (prefs.isConfigured) Screen.Home else Screen.Welcome) }
            Screen.PickServer -> go(if (prefs.isConfigured) Screen.Home else Screen.Welcome)
            Screen.Libraries, Screen.Display, Screen.Screensaver -> go(Screen.Home)
            Screen.Weather -> go(Screen.Display)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ sign in with Plex
    fun startSignIn() {
        cancelSignIn()
        go(Screen.SignIn)
        signInJob = viewModelScope.launch {
            try {
                val pin = app.plex.createPin()
                pinCode = pin.code
                val deadline = System.currentTimeMillis() + 15 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    val token = try { app.plex.checkPin(pin) } catch (e: IOException) { null } ?: continue
                    accountToken = token
                    busy = "Signed in - finding your servers…"
                    accountName = app.plex.accountName(token)
                    servers = app.plex.servers(token)
                    busy = null
                    pinCode = null
                    screen = Screen.PickServer
                    return@launch
                }
                pinCode = null
                error = "The code expired. Choose Sign in to get a new one."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = null
                pinCode = null
                error = friendly(e, "Couldn't reach plex.tv - check the internet connection")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        pinCode = null
        busy = null
    }

    fun chooseServer(server: ServerResource) {
        if (busy != null) return
        viewModelScope.launch {
            error = null
            busy = "Testing ${server.name}'s addresses…"
            try {
                val (best, results) = app.plex.pickConnection(server)
                if (best == null) {
                    error = "None of ${server.name}'s addresses answered from this device:\n" +
                        results.joinToString("\n") { "• ${it.uri} (${it.kind.label}): ${it.error ?: "failed"}" }
                } else {
                    prefs.saveConnection(server.accessToken, best.uri, server.name, server.id, accountName)
                    prefs.excluded = emptySet()
                    app.library.clear()
                    accountToken = null
                    connectionStatus = "Connected to ${server.name} ${best.kind.label}" +
                        if (best.kind == Kind.RELAY) ". Relay is slow, so images may load slowly." else ""
                    settingsVersion++
                    screen = Screen.Home
                }
            } finally {
                busy = null
            }
        }
    }

    fun signOut() {
        cancelSignIn()
        prefs.signOut()
        viewModelScope.launch { app.library.clear() }
        connectionStatus = null
        settingsVersion++
        go(Screen.Welcome)
    }

    fun checkConnection() {
        if (!prefs.isConfigured) return
        viewModelScope.launch {
            val r = app.plex.testAddress(prefs.serverUrl, prefs.token, prefs.serverId)
            connectionStatus = if (r.ok) "Connected to ${prefs.serverName.ifEmpty { r.name ?: "Plex" }}"
            else "Can't reach ${prefs.serverName.ifEmpty { "your server" }} (${r.error}). Try Switch server."
        }
    }

    // ------------------------------------------------------------------ libraries
    private fun loadLibraries() {
        libraries = null
        viewModelScope.launch {
            try {
                libraries = app.plex.libraries(prefs.serverUrl, prefs.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libraries = emptyList()
                error = friendly(e, "Couldn't load libraries from Plex")
            }
        }
    }

    fun toggleLibrary(title: String) {
        val ex = prefs.excluded.toMutableSet()
        if (!ex.remove(title)) ex.add(title)
        prefs.excluded = ex
        settingsVersion++
        viewModelScope.launch { app.library.clear() }
    }

    // ------------------------------------------------------------------ display settings
    fun update(change: () -> Unit) {
        change()
        settingsVersion++
    }

    // ------------------------------------------------------------------ weather
    fun searchWeather(query: String) {
        if (query.isBlank() || busy != null) return
        viewModelScope.launch {
            error = null
            busy = "Searching…"
            try {
                val found = app.weather.search(query)
                weatherResults = found
                if (found.isEmpty()) error = "No places called \"${query.trim()}\". Try another spelling or a nearby city."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendly(e, "Couldn't reach the weather service - check the internet connection")
            } finally {
                busy = null
            }
        }
    }

    fun chooseWeather(place: Place) {
        prefs.setWeatherPlace(place.label, place.lat, place.lon)
        weatherResults = null
        settingsVersion++
        // Fetch a first reading now, so the screen saver has weather to show straight away.
        viewModelScope.launch {
            try {
                prefs.weatherCache = app.weather.current(prefs.weatherLat, prefs.weatherLon).encode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the screen saver will try again
            }
        }
    }

    fun weatherOff() {
        prefs.clearWeather()
        weatherResults = null
        settingsVersion++
    }

    private fun friendly(e: Exception, fallback: String): String = when {
        e is PlexException && e.httpCode == 401 -> "Plex rejected the sign-in. Please sign in again."
        e is IOException -> fallback
        else -> "$fallback (${e.javaClass.simpleName})"
    }
}

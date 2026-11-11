package app.showcase.screensaver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.showcase.screensaver.data.Prefs
import app.showcase.screensaver.slideshow.SlideshowActivity
import kotlinx.coroutines.delay

private val Amber = Color(0xFFE5A00D)
private val Background = Color(0xFF121316)
private val Card = Color(0xFF1C1E22)
private val TextMain = Color(0xFFECEEF1)
private val TextMuted = Color(0xFFA8AFBA)
private val OnAmber = Color(0xFF1B1D21)
private val ErrorText = Color(0xFFF2B8B5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Background, surface = Card)) {
                ShowcaseRoot(viewModel())
            }
        }
    }
}

// ====================================================================== navigation
@Composable
private fun ShowcaseRoot(vm: SetupViewModel) {
    BackHandler(enabled = vm.screen != Screen.Home && vm.screen != Screen.Welcome) { vm.back() }
    LaunchedEffect(Unit) { if (vm.screen == Screen.Home) vm.checkConnection() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 48.dp, vertical = 27.dp),   // TV overscan-safe margins
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = 860.dp)) {
            when (vm.screen) {
                Screen.Welcome -> WelcomeScreen(vm)
                Screen.SignIn -> SignInScreen(vm)
                Screen.PickServer -> PickServerScreen(vm)
                Screen.Home -> HomeScreen(vm)
                Screen.Libraries -> LibrariesScreen(vm)
                Screen.Display -> DisplayScreen(vm)
                Screen.Weather -> WeatherScreen(vm)
                Screen.Screensaver -> ScreensaverScreen(vm)
            }
        }
    }
}

// ====================================================================== building blocks
/** A page: heading, optional subtitle, status lines, then a focusable list. Focus starts on the first row. */
@Composable
private fun Page(
    title: String,
    subtitle: String? = null,
    vm: SetupViewModel,
    content: LazyListScope.(first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(vm.screen) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = TextMuted, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
        vm.busy?.let { Text(it, color = Amber, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp)) }
        vm.error?.let { Text(it, color = ErrorText, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // Read the settings version here, so the rows are rebuilt (with their new values) the moment a setting changes.
            vm.settingsVersion
            content(first)
        }
    }
}

/** One focusable row: highlighted amber when the remote is on it. Works with touch too. */
@Composable
private fun OptionRow(
    label: String,
    value: String? = null,
    detail: String? = null,
    enabled: Boolean = true,
    focus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fg = when {
        focused -> OnAmber
        enabled -> TextMain
        else -> TextMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Amber else Card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) {
                Text(detail, color = if (focused) OnAmber.copy(alpha = 0.8f) else TextMuted, fontSize = 15.sp)
            }
        }
        if (value != null) Text(value, color = fg, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

private fun LazyListScope.note(text: String, color: Color = TextMuted, size: Int = 17) {
    item { Text(text, color = color, fontSize = size.sp, modifier = Modifier.padding(vertical = 4.dp)) }
}

private fun Context.startSlideshow() = startActivity(Intent(this, SlideshowActivity::class.java))

// ====================================================================== screens
@Composable
private fun WelcomeScreen(vm: SetupViewModel) {
    Page("Showcase", "Your Plex movie and TV artwork as a screen saver.", vm) { first ->
        item {
            OptionRow("Sign in with Plex", detail = "You'll enter a short code at plex.tv/link on your phone or computer",
                focus = first) { vm.startSignIn() }
        }
    }
}

@Composable
private fun SignInScreen(vm: SetupViewModel) {
    Page("Sign in with Plex", vm = vm) { first ->
        val code = vm.pinCode
        if (code != null) {
            note("1. On your phone or computer, go to")
            item { Text("plex.tv/link", color = Amber, fontSize = 36.sp, fontWeight = FontWeight.Bold) }
            note("2. Enter this code:")
            item {
                Text(code, color = TextMain, fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, letterSpacing = 12.sp)
            }
            note("This screen continues by itself once you've entered the code.")
        } else if (vm.busy == null && vm.error == null) {
            note("Getting a code from Plex…")
        }
        if (vm.error != null) {
            item { OptionRow("Try again", focus = first) { vm.startSignIn() } }
            item { OptionRow("Cancel") { vm.back() } }
        } else {
            item { OptionRow("Cancel", focus = first) { vm.back() } }
        }
    }
}

@Composable
private fun PickServerScreen(vm: SetupViewModel) {
    Page("Choose a server", "Signed in as ${vm.accountName}", vm) { first ->
        if (vm.servers.isEmpty()) note("This Plex account doesn't have access to any servers.")
        vm.servers.forEachIndexed { i, s ->
            item {
                val facts = buildList {
                    add(if (s.owned) "Your server" else "Shared by ${s.owner.ifEmpty { "someone else" }}")
                    if (!s.online) add("currently offline")
                    val kinds = s.connections.map { it.kind }.distinct().sortedBy { it.rank }
                    if (kinds.isNotEmpty()) add("reachable " + kinds.joinToString(", ") { it.label })
                }
                OptionRow(s.name, detail = facts.joinToString(" · "), focus = if (i == 0) first else null,
                    enabled = vm.busy == null) { vm.chooseServer(s) }
            }
        }
        item { OptionRow("Cancel", focus = if (vm.servers.isEmpty()) first else null) { vm.back() } }
    }
}

@Composable
private fun HomeScreen(vm: SetupViewModel) {
    val context = LocalContext.current
    vm.settingsVersion   // re-read prefs when settings change
    val p = vm.prefs
    Page("Showcase", vm.connectionStatus ?: "Checking connection…", vm) { first ->
        item {
            OptionRow("Start slideshow now", detail = "Preview it, or use it where screen savers can't be changed",
                focus = first) { context.startSlideshow() }
        }
        item {
            OptionRow("Set as screen saver", detail = "Make the TV show your artwork when it's idle") {
                vm.go(Screen.Screensaver)
            }
        }
        item {
            val hidden = p.excluded.size
            OptionRow("Libraries", value = if (hidden == 0) "All shown" else "$hidden hidden") { vm.go(Screen.Libraries) }
        }
        item {
            OptionRow("Display", detail = "${p.intervalSeconds} s per image · ${motionName(p.animations)} · " +
                if (p.artQuality == "screen") "fit to screen" else "original quality") { vm.go(Screen.Display) }
        }
        item {
            OptionRow("Switch server", value = p.serverName.ifEmpty { null },
                detail = p.username.ifEmpty { null }?.let { "Signed in as $it" }) { vm.startSignIn() }
        }
        item { OptionRow("Sign out", detail = "Forget this Plex account on this device") { vm.signOut() } }
    }
}

@Composable
private fun LibrariesScreen(vm: SetupViewModel) {
    vm.settingsVersion
    val excluded = vm.prefs.excluded
    Page("Libraries", "Choose which libraries the slideshow uses.", vm) { first ->
        val libs = vm.libraries
        when {
            libs == null -> note("Loading libraries…")
            libs.isEmpty() && vm.error == null -> note("This server has no libraries.")
        }
        libs?.forEachIndexed { i, lib ->
            item {
                if (lib.supported) {
                    OptionRow(lib.title, value = if (lib.title in excluded) "Hidden" else "Shown",
                        focus = if (i == 0) first else null) { vm.toggleLibrary(lib.title) }
                } else {
                    OptionRow(lib.title, value = "Not used", detail = "${lib.type} libraries have no background art",
                        enabled = false, focus = if (i == 0) first else null) {}
                }
            }
        }
        item { OptionRow("Done", focus = if (libs.isNullOrEmpty()) first else null) { vm.back() } }
    }
}

private val INTERVALS = listOf(5, 8, 10, 15, 20, 30, 45, 60, 120)
private val TRANSITIONS = listOf(0, 500, 1000, 1500, 2000, 3000)
private val REFRESH = listOf(15, 30, 60, 180, 720)
/** Night dimming windows, as minutes after midnight. (-1, -1) = off. */
private val DIM_WINDOWS = listOf(-1 to -1, 21 * 60 to 6 * 60, 22 * 60 to 7 * 60, 23 * 60 to 7 * 60, 0 to 7 * 60)
private val DIM_LEVELS = listOf(30, 50, 60, 70, 85)

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
private val MOTION_PRESETS = linkedMapOf(
    "All effects" to Prefs.ALL_ANIMATIONS.toSet(),
    "Zoom only" to Prefs.ZOOM.toSet(),
    "Pan only" to Prefs.PAN.toSet(),
    "Zoom and pan" to (Prefs.ZOOM + Prefs.PAN).toSet(),
    "Colour fades only" to Prefs.COLOUR.toSet(),
    "None" to emptySet(),
)

private fun motionName(set: Set<String>) = MOTION_PRESETS.entries.firstOrNull { it.value == set }?.key ?: "Custom"

private fun <T> cycle(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

private fun onOff(v: Boolean) = if (v) "On" else "Off"

@Composable
private fun DisplayScreen(vm: SetupViewModel) {
    val context = LocalContext.current
    vm.settingsVersion
    val p = vm.prefs
    Page("Display", "Select a row to change it. Changes apply the next time the slideshow starts.", vm) { first ->
        item {
            OptionRow("Seconds per image", value = "${p.intervalSeconds} s", focus = first) {
                vm.update { p.intervalSeconds = cycle(INTERVALS, p.intervalSeconds) }
            }
        }
        item {
            OptionRow("Crossfade", value = if (p.transitionMs == 0) "Off" else "${p.transitionMs / 1000.0} s") {
                vm.update { p.transitionMs = cycle(TRANSITIONS, p.transitionMs) }
            }
        }
        item {
            OptionRow("Image quality",
                value = if (p.artQuality == "screen") "Fit to screen" else "Original",
                detail = if (p.artQuality == "screen") "Plex shrinks images first: faster on slow Wi-Fi"
                else "Full quality as stored in Plex") {
                vm.update { p.artQuality = if (p.artQuality == "screen") "original" else "screen" }
            }
        }
        item {
            OptionRow("Motion effects", value = motionName(p.animations)) {
                vm.update {
                    val names = MOTION_PRESETS.keys.toList()
                    p.animations = MOTION_PRESETS.getValue(cycle(names, motionName(p.animations).takeIf { it in names } ?: names.last()))
                }
            }
        }
        item { OptionRow("Title", value = onOff(p.showTitle)) { vm.update { p.showTitle = !p.showTitle } } }
        item {
            OptionRow("Title logos", value = onOff(p.titleLogos),
                detail = "Show the film's logo instead of its name, when Plex has one") {
                vm.update { p.titleLogos = !p.titleLogos }
            }
        }
        item {
            OptionRow("Year, genre and rating", value = onOff(p.showDetails), detail = "A small line under the title") {
                vm.update { p.showDetails = !p.showDetails }
            }
        }
        item { OptionRow("Clock", value = onOff(p.showClock)) { vm.update { p.showClock = !p.showClock } } }
        item {
            OptionRow("Clock format", value = if (p.clock24h) "24-hour" else "12-hour") {
                vm.update { p.clock24h = !p.clock24h }
            }
        }
        item { OptionRow("Date", value = onOff(p.showDate)) { vm.update { p.showDate = !p.showDate } } }
        item {
            OptionRow("Weather", value = if (p.weatherEnabled) p.weatherPlace else "Off",
                detail = "Current weather under the clock") { vm.go(Screen.Weather) }
        }
        item {
            OptionRow("Poster in the corner", value = onOff(p.showPoster)) { vm.update { p.showPoster = !p.showPoster } }
        }
        item {
            OptionRow("Burn-in protection", value = onOff(p.pixelShift),
                detail = "Moves the text a few pixels with each image. Recommended for OLED TVs.") {
                vm.update { p.pixelShift = !p.pixelShift }
            }
        }
        item {
            OptionRow("Night dimming", value = if (p.dimEnabled) "${hhmm(p.dimStart)} – ${hhmm(p.dimEnd)}" else "Off",
                detail = "Darkens the screen at night, for bedrooms") {
                vm.update {
                    val (start, end) = cycle(DIM_WINDOWS, p.dimStart to p.dimEnd)
                    p.dimStart = start
                    p.dimEnd = end
                }
            }
        }
        if (p.dimEnabled) item {
            OptionRow("Dimming amount", value = "${p.dimLevel}% darker") {
                vm.update { p.dimLevel = cycle(DIM_LEVELS, p.dimLevel) }
            }
        }
        item {
            OptionRow("Check for new Plex items", value = "every ${p.refreshMinutes} min") {
                vm.update { p.refreshMinutes = cycle(REFRESH, p.refreshMinutes) }
            }
        }
        item { OptionRow("Preview", detail = "Start the slideshow with these settings") { context.startSlideshow() } }
        item { OptionRow("Done") { vm.back() } }
    }
}

@Composable
private fun WeatherScreen(vm: SetupViewModel) {
    vm.settingsVersion
    val p = vm.prefs
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val firstResult = remember { FocusRequester() }
    // When results arrive, close the keyboard and put the remote on the first one.
    LaunchedEffect(vm.weatherResults) {
        if (!vm.weatherResults.isNullOrEmpty()) {
            keyboard?.hide()
            delay(100)
            runCatching { firstResult.requestFocus() }
        }
    }
    val subtitle = if (p.weatherEnabled) "Showing the weather for ${p.weatherPlace}." else "Choose a town or city to show its weather."
    Page("Weather", subtitle, vm) { first ->
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Town or city") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchWeather(query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextMain, unfocusedTextColor = TextMain,
                    focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
                    focusedLabelColor = Amber, unfocusedLabelColor = TextMuted, cursorColor = Amber),
                textStyle = TextStyle(fontSize = 20.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(first)
                    // A text box keeps the remote's up/down for itself; send them on so you can leave it.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    },
            )
        }
        item { OptionRow("Search", enabled = vm.busy == null) { vm.searchWeather(query) } }
        vm.weatherResults?.forEachIndexed { i, place ->
            item {
                OptionRow(place.name, detail = place.detail, focus = if (i == 0) firstResult else null) {
                    vm.chooseWeather(place)
                }
            }
        }
        item {
            OptionRow("Temperature", value = if (p.fahrenheit) "°F" else "°C") { vm.update { p.fahrenheit = !p.fahrenheit } }
        }
        if (p.weatherEnabled) item { OptionRow("Turn weather off") { vm.weatherOff() } }
        item { OptionRow("Done") { vm.back() } }
        note("Weather data by Open-Meteo.com. Only the town you choose is sent, never your location.", size = 15)
    }
}

@Composable
private fun ScreensaverScreen(vm: SetupViewModel) {
    val context = LocalContext.current
    Page("Set as screen saver", vm = vm) { first ->
        note("1. Choose Open Settings below.", TextMain)
        note("2. Go to Device Preferences → Screen saver → Screen saver, and pick Showcase.", TextMain)
        note("3. Optional: under When to start, choose how long the TV waits first.", TextMain)
        note("Android TV doesn't let apps jump straight to the screen saver page, so it opens Settings for you.")
        item {
            OptionRow("Open Settings", focus = first) {
                vm.error = null
                // The screen saver page if the device offers it, otherwise the main Settings screen.
                val opened = listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_SETTINGS).any { action ->
                    try {
                        context.startActivity(Intent(action))
                        true
                    } catch (e: ActivityNotFoundException) {
                        false
                    } catch (e: SecurityException) {
                        false
                    }
                }
                if (!opened) {
                    vm.error = "This device doesn't let apps open its settings. " +
                        "Use the adb command below, or Start slideshow now."
                }
            }
        }
        item { OptionRow("Preview the slideshow") { context.startSlideshow() } }
        note("Google TV and Fire TV hide other screen savers. To set it there, run this once from a computer:")
        item {
            Text("adb shell settings put secure screensaver_components app.showcase.screensaver/.slideshow.ShowcaseDream",
                color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Card)
                    .padding(12.dp))
        }
        item { OptionRow("Done") { vm.back() } }
    }
}

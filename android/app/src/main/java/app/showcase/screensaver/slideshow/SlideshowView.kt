package app.showcase.screensaver.slideshow

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.showcase.screensaver.showcase
import app.showcase.screensaver.data.NotConfiguredException
import app.showcase.screensaver.plex.Item
import app.showcase.screensaver.plex.ItemInfo
import app.showcase.screensaver.weather.Conditions
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import java.util.Locale
import kotlin.random.Random

/**
 * The slideshow: two stacked slides that crossfade, a slow pan/zoom on each image,
 * and an overlay with title, clock and poster. Used by the screensaver and by "Play now".
 * A port of the web version's showcase.js.
 */
@SuppressLint("ViewConstructor")
class SlideshowView(context: Context) : FrameLayout(context) {

    private val app = context.showcase
    private val prefs = app.prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    private class Slide(context: Context) : FrameLayout(context) {
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        var colourAnim: ValueAnimator? = null

        init {
            addView(image, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            alpha = 0f
        }
    }

    private class Prepared(val item: Item, val poster: Drawable?, val logo: Drawable?, val detail: String)

    private val slides = arrayOf(Slide(context), Slide(context))
    private var active = 0
    private var items: List<Item> = emptyList()
    private val bag = ArrayDeque<Item>()

    // Soft dark fades at the top and bottom so the clock and title stay readable on bright artwork.
    // Kept apart from the overlay so burn-in shifting never exposes their edges.
    private val shade = FrameLayout(context)
    private val topShade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x80000000.toInt(), 0))
    }
    private val bottomShade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0))
    }

    private val overlay = FrameLayout(context)

    // Top right: time, date and weather in one right-aligned block.
    private val infoBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val clock = TextClock(context)
    private val date = TextClock(context)
    private val weatherRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val weatherIcon = ImageView(context)
    private val weatherText = TextView(context)

    // Bottom right: the film's logo (or its title as text) with year · genre · rating underneath.
    private val caption = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val logo = ImageView(context)
    private val title = TextView(context)
    private val details = TextView(context)
    private val infoCache = object : LinkedHashMap<String, ItemInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ItemInfo>?) = size > 300
    }

    // Night dimming: a black layer over everything.
    private val dim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    private var dimJob: Job? = null
    private val poster = ImageView(context)
    private val message = TextView(context)
    private var weatherJob: Job? = null

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        slides.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

        // One typeface (Roboto, Android TV's own) throughout: Light for the big text, small caps for details.
        val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val regular = Typeface.create("sans-serif", Typeface.NORMAL)
        fun TextView.style(sizeSp: Float, face: Typeface, caps: Boolean = false, alpha: Float = 1f) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.WHITE)
            typeface = face
            this.alpha = alpha
            isAllCaps = caps
            if (caps) letterSpacing = 0.14f
            setShadowLayer(14f, 0f, 2f, 0xCC000000.toInt())
            includeFontPadding = false
        }
        clock.style(40f, light)
        date.style(13f, regular, caps = true, alpha = 0.92f)
        weatherText.style(13f, regular, caps = true, alpha = 0.92f)
        title.style(22f, light)
        title.maxLines = 2
        title.gravity = Gravity.END
        details.style(13f, regular, caps = true, alpha = 0.92f)
        details.gravity = Gravity.END
        details.visibility = View.GONE
        logo.apply {
            scaleType = ImageView.ScaleType.FIT_END
            adjustViewBounds = true
            visibility = View.GONE
        }
        fun captionLp(top: Int = 0) = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            topMargin = top
        }
        caption.addView(logo, captionLp())
        caption.addView(title, captionLp())
        caption.addView(details, captionLp(dp(8)))

        weatherIcon.alpha = 0.92f
        weatherRow.addView(weatherIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        weatherRow.addView(weatherText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        weatherRow.visibility = View.GONE

        fun lp(top: Int) = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            topMargin = top
        }
        infoBlock.addView(clock, lp(0))
        infoBlock.addView(date, lp(dp(6)))
        infoBlock.addView(weatherRow, lp(dp(6)))

        poster.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            elevation = 8f
        }
        message.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            val pad = dp(12)
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
        }
        overlay.addView(infoBlock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        overlay.addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        overlay.addView(poster, LayoutParams(dp(160), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        overlay.addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        shade.addView(topShade, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        shade.addView(bottomShade, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------ lifecycle
    fun start() {
        if (loop?.isActive == true) return
        applyPrefs()
        loop = scope.launch { slideshowLoop() }
        weatherJob = scope.launch { weatherLoop() }
        dimJob = scope.launch { dimLoop() }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        loop = null
        weatherJob = null
        dimJob = null
        slides.forEach { stopEffect(it) }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 6% margins, like the web version; poster ~16% of the width.
        val mx = (w * 0.06f).toInt()
        val my = (h * 0.06f).toInt()
        listOf(infoBlock, caption, poster, message).forEach { (it.layoutParams as LayoutParams).setMargins(mx, my, mx, my) }
        (poster.layoutParams as LayoutParams).width = (w * POSTER_WIDTH).toInt()
        (caption.layoutParams as LayoutParams).marginStart = mx + (w * (POSTER_WIDTH + 0.04f)).toInt()   // never under the poster
        logo.maxWidth = (w * LOGO_WIDTH).toInt()
        logo.maxHeight = (h * LOGO_HEIGHT).toInt()
        topShade.layoutParams.height = (h * 0.32f).toInt()
        bottomShade.layoutParams.height = (h * 0.42f).toInt()
        post { overlay.requestLayout(); shade.requestLayout() }
    }

    private fun applyPrefs() {
        val pattern = if (prefs.clock24h) "HH:mm" else "h:mm"
        clock.format24Hour = pattern
        clock.format12Hour = pattern
        // "Tuesday 11 November" / "Tuesday, November 11" - whichever order the TV's language uses.
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        date.format24Hour = datePattern
        date.format12Hour = datePattern
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        date.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        if (!prefs.weatherEnabled) weatherRow.visibility = View.GONE
        caption.visibility = if (prefs.showTitle) View.VISIBLE else View.GONE
        if (!prefs.showPoster) poster.visibility = View.GONE
        topShade.visibility = if (prefs.showClock || prefs.showDate || prefs.weatherEnabled) View.VISIBLE else View.GONE
        bottomShade.visibility = if (prefs.showTitle || prefs.showPoster) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ weather
    /** Shows the last reading straight away, then refreshes every 30 minutes. Hides itself if data gets old. */
    private suspend fun weatherLoop() {
        if (!prefs.weatherEnabled) return
        while (true) {
            val cached = Conditions.decode(prefs.weatherCache)
            val age = cached?.let { System.currentTimeMillis() - it.at } ?: Long.MAX_VALUE
            if (cached != null && age < WEATHER_MAX_AGE) showWeather(cached)
            if (age > WEATHER_REFRESH) {
                try {
                    val fresh = app.weather.current(prefs.weatherLat, prefs.weatherLon)
                    prefs.weatherCache = fresh.encode()
                    showWeather(fresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (age >= WEATHER_MAX_AGE) weatherRow.visibility = View.GONE
                }
            }
            delay(WEATHER_REFRESH)
        }
    }

    private fun showWeather(c: Conditions) {
        weatherIcon.setImageResource(c.icon)
        weatherText.text = listOf(c.temperature(prefs.fahrenheit), c.description).filter { it.isNotEmpty() }.joinToString("  ")
        weatherRow.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ night dimming
    /** Checks the time every 30 s and eases the dimming layer in or out over a minute. */
    private suspend fun dimLoop() {
        var current = -1f
        while (true) {
            val now = Calendar.getInstance()
            val night = prefs.isNight(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
            val target = if (night) prefs.dimLevel.coerceIn(10, 95) / 100f else 0f
            if (target != current) {
                dim.bringToFront()
                if (current < 0f) dim.alpha = target                 // starting at night: already dim, no fade
                else dim.animate().alpha(target).setDuration(60_000L).setInterpolator(LinearInterpolator()).start()
                current = target
            }
            delay(30_000L)
        }
    }

    // ------------------------------------------------------------------ main loop
    private suspend fun slideshowLoop() {
        loadWithRetry(Long.MAX_VALUE)                    // anything cached: start instantly
        var lastRefresh = 0L                             // ...then refresh from Plex after the first image
        prepare(slides[1 - active])?.let { show(it) }
        while (true) {
            val t0 = SystemClock.elapsedRealtime()
            delay(prefs.transitionMs + 100L)              // let the old slide finish fading out
            val refreshMs = prefs.refreshMinutes * 60_000L
            if (SystemClock.elapsedRealtime() - lastRefresh > refreshMs) {
                try {
                    items = app.library.items(refreshMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // keep showing what we have
                }
                lastRefresh = SystemClock.elapsedRealtime()
            }
            val next = prepare(slides[1 - active])        // preload during the wait
            delay(max(0L, prefs.intervalSeconds * 1000L - (SystemClock.elapsedRealtime() - t0)))
            next?.let { show(it) }
        }
    }

    private suspend fun loadWithRetry(maxAgeMs: Long) {
        var wait = 5_000L
        while (true) {
            try {
                items = app.library.items(maxAgeMs)
                if (items.isNotEmpty()) {
                    showMessage(null)
                    return
                }
                showMessage("No artwork found - check which libraries are switched on in Showcase")
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotConfiguredException) {
                showMessage(e.message)
                wait = 30_000L
            } catch (e: Exception) {
                showMessage("Can't reach your Plex server (${e.message ?: e.javaClass.simpleName}). Retrying…")
            }
            delay(wait)
            wait = min(wait * 2, 5 * 60_000L)
        }
    }

    private fun nextItem(): Item {
        if (bag.isEmpty()) bag.addAll(items.shuffled())   // every item once before any repeats
        return bag.removeFirst()
    }

    // ------------------------------------------------------------------ images
    private suspend fun load(url: String, w: Int, h: Int, scale: Scale = Scale.FILL): Drawable? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .headers(app.plex.headers(prefs.token, accept = "image/*"))
            .size(w, h)
            .scale(scale)
            .build()
        return (app.imageLoader.execute(request) as? SuccessResult)?.drawable
    }

    /** Loads the next working image into [slide] (skips broken ones). */
    private suspend fun prepare(slide: Slide): Prepared? {
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        val h = if (height > 0) height else resources.displayMetrics.heightPixels
        repeat(5) {
            if (items.isEmpty()) return null
            val item = nextItem()
            val art = load(app.plex.artUrl(prefs.serverUrl, item.ratingKey, prefs.artQuality, w, h), w, h)
                ?: return@repeat
            val thumb = if (prefs.showPoster && item.hasThumb) {
                load(app.plex.thumbUrl(prefs.serverUrl, item.ratingKey), (w * POSTER_WIDTH).toInt().coerceAtLeast(64), h / 2)
            } else null
            var logoImage: Drawable? = null
            var detail = ""
            if (prefs.showTitle && (prefs.titleLogos || prefs.showDetails)) {
                // Never hold the slideshow up for long: no details is fine.
                withTimeoutOrNull(5_000L) {
                    val info = infoCache[item.ratingKey] ?: runCatching {
                        app.plex.info(prefs.serverUrl, prefs.token, item.ratingKey)
                    }.getOrNull()?.also { infoCache[item.ratingKey] = it }
                    detail = info?.detail.orEmpty()
                    val path = info?.logoPath
                    if (prefs.titleLogos && path != null) {
                        logoImage = load(app.plex.logoUrl(prefs.serverUrl, path),
                            (w * LOGO_WIDTH).toInt(), (h * LOGO_HEIGHT).toInt(), Scale.FIT)
                    }
                }
            }
            stopEffect(slide)
            slide.image.setImageDrawable(art)
            return Prepared(item, thumb, logoImage, detail)
        }
        return null
    }

    private fun show(p: Prepared) {
        val next = slides[1 - active]
        val prev = slides[active]
        startEffect(next)
        next.alpha = 0f
        next.bringToFront()
        shade.bringToFront()
        overlay.bringToFront()
        dim.bringToFront()                                 // night dimming stays on top
        next.animate().alpha(1f).setDuration(prefs.transitionMs.toLong()).withEndAction {
            prev.alpha = 0f
            stopEffect(prev)
            prev.image.setImageDrawable(null)              // free memory; it gets the next image later
        }.start()
        active = 1 - active
        updateOverlay(p)
    }

    private fun updateOverlay(p: Prepared) {
        val swap = {
            title.text = p.item.title
            logo.setImageDrawable(p.logo)
            logo.contentDescription = p.item.title
            logo.visibility = if (p.logo != null) View.VISIBLE else View.GONE
            title.visibility = if (p.logo != null) View.GONE else View.VISIBLE   // the logo replaces the title text
            details.text = if (prefs.showDetails) p.detail else ""
            details.visibility = if (details.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (prefs.showPoster && p.poster != null) {
                poster.setImageDrawable(p.poster)
                poster.visibility = View.VISIBLE
            } else {
                poster.visibility = View.GONE
            }
            if (prefs.pixelShift) {                       // burn-in protection
                overlay.translationX = dp(Random.nextInt(-8, 9)).toFloat()
                overlay.translationY = dp(Random.nextInt(-8, 9)).toFloat()
            }
        }
        val fade = (prefs.transitionMs / 2L).coerceAtLeast(1L)
        caption.animate().alpha(0f).setDuration(fade).withEndAction {
            swap()
            caption.animate().alpha(1f).setDuration(fade).start()
            poster.alpha = 0f
            poster.animate().alpha(1f).setDuration(fade).start()
        }.start()
        poster.animate().alpha(0f).setDuration(fade).start()
    }

    private fun showMessage(text: String?) {
        message.text = text ?: ""
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ effects
    private fun startEffect(slide: Slide) {
        stopEffect(slide)
        val img = slide.image
        val duration = prefs.intervalSeconds * 1000L + prefs.transitionMs
        val w = max(width, 1).toFloat()
        val h = max(height, 1).toFloat()

        fun kenBurns(s0: Float, s1: Float, x0: Float = 0f, x1: Float = 0f, y0: Float = 0f, y1: Float = 0f) {
            img.scaleX = s0; img.scaleY = s0; img.translationX = x0; img.translationY = y0
            img.animate().scaleX(s1).scaleY(s1).translationX(x1).translationY(y1)
                .setDuration(duration).setInterpolator(LinearInterpolator()).start()
        }

        fun colour(from: Float, to: Float) {
            val matrix = ColorMatrix()
            slide.colourAnim = ValueAnimator.ofFloat(from, to).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener {
                    matrix.setSaturation(it.animatedValue as Float)
                    img.colorFilter = ColorMatrixColorFilter(matrix)
                }
                start()
            }
        }

        // Same effects as the web version (pans at 110% zoom so the edges never show).
        when (prefs.animations.toList().randomOrNull()) {
            "zoomIn" -> kenBurns(1f, 1.1f)
            "zoomOut" -> kenBurns(1.1f, 1f)
            "moveRight" -> kenBurns(1.1f, 1.1f, x0 = w * 0.033f, x1 = -w * 0.033f)
            "moveLeft" -> kenBurns(1.1f, 1.1f, x0 = -w * 0.033f, x1 = w * 0.033f)
            "moveUp" -> kenBurns(1.1f, 1.1f, y0 = h * 0.044f, y1 = -h * 0.044f)
            "moveDown" -> kenBurns(1.1f, 1.1f, y0 = -h * 0.044f, y1 = h * 0.044f)
            "goGray" -> colour(1f, 0f)
            "goColor" -> colour(0f, 1f)
        }
    }

    private fun stopEffect(slide: Slide) {
        slide.image.animate().cancel()
        slide.colourAnim?.cancel()
        slide.colourAnim = null
        slide.image.apply { scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f; clearColorFilter() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val POSTER_WIDTH = 0.16f                 // share of the screen width
        const val LOGO_WIDTH = 0.30f                   // title logos fit inside 30% x 15% of the screen
        const val LOGO_HEIGHT = 0.15f
        const val WEATHER_REFRESH = 30 * 60_000L
        const val WEATHER_MAX_AGE = 3 * 60 * 60_000L    // older than this isn't worth showing
    }
}

package app.showcase.screensaver

import android.app.Application
import android.content.Context
import app.showcase.screensaver.data.LibraryRepository
import app.showcase.screensaver.data.Prefs
import app.showcase.screensaver.plex.PlexClient
import app.showcase.screensaver.weather.WeatherClient
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** App-wide singletons, shared by the setup screens, the screensaver and "Play now". */
class ShowcaseApp : Application() {

    val prefs: Prefs by lazy { Prefs(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val plex: PlexClient by lazy { PlexClient(prefs.clientId, http) }

    val library: LibraryRepository by lazy { LibraryRepository(this) }

    val weather: WeatherClient by lazy { WeatherClient(http) }

    /** Artwork loader: decodes to screen size, keeps recent images on disk so repeats don't hit Plex again. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}

val Context.showcase: ShowcaseApp
    get() = applicationContext as ShowcaseApp

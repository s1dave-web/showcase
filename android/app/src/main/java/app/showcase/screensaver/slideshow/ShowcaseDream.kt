package app.showcase.screensaver.slideshow

import android.service.dreams.DreamService

/**
 * The screensaver. Android starts this when the TV goes idle (once Showcase is chosen as
 * the screen saver), and stops it when you press any button.
 */
class ShowcaseDream : DreamService() {

    private var view: SlideshowView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false          // any key press wakes the TV
        isFullscreen = true
        isScreenBright = true
        view = SlideshowView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}

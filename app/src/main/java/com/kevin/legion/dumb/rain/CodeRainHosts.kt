package com.kevin.legion.dumb.rain

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View

/**
 * Drives a [CodeRainScene] on a timer: one frame every [RainTuning.FRAME_MS] while [running], with
 * the elapsed real time passed to the scene so the speed does not depend on the frame rate.
 * [drawFrame] is where a host paints. Start and stop are the host's visibility, which is what
 * keeps the rain from burning battery while nobody can see it.
 */
internal class RainLoop(private val scene: CodeRainScene, private val drawFrame: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            scene.advance(((now - lastMs).coerceAtMost(MAX_STEP_MS)) / MS_PER_SECOND)
            lastMs = now
            drawFrame()
            handler.postDelayed(this, RainTuning.FRAME_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastMs = System.currentTimeMillis()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private companion object {
        const val MS_PER_SECOND = 1000f

        /** After a long pause (a stopped loop) the rain resumes where it was instead of leaping ahead. */
        const val MAX_STEP_MS = 100L
    }
}

/**
 * The rain as a live wallpaper (ADR 0062). The engine draws only while the wallpaper is visible, so
 * a locked or switched-off screen costs nothing. Whether it can be set on the LOCK screen is up to the
 * phone's maker (Samsung may refuse); the setup row says so and nothing here claims otherwise.
 */
class MatrixWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = RainEngine()

    private inner class RainEngine : Engine() {
        private val scene = CodeRainScene()
        private val loop = RainLoop(scene) { paint() }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            scene.resize(width, height, resources.displayMetrics.density)
            paint()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible && !animationsOff(applicationContext)) loop.start() else loop.stop()
            if (visible) paint()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            loop.stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            loop.stop()
            super.onDestroy()
        }

        private fun paint() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let {
                    it.drawColor(Color.BLACK)
                    scene.draw(it)
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }
    }
}

/** A plain View that paints the rain; the screensaver's content. */
internal class CodeRainView(context: Context) : View(context) {
    private val scene = CodeRainScene()
    private val loop = RainLoop(scene) { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animationsOff(context)) loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scene.resize(w, h, resources.displayMetrics.density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        scene.draw(canvas)
    }
}

/** The rain as the charging screensaver (ADR 0062). Not interactive: a touch ends it, as a screensaver should. */
class MatrixDreamService : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(CodeRainView(this))
    }
}

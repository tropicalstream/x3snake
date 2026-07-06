package com.tropicalstream.x3snake

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.tropicalstream.x3snake.game.Particles
import com.tropicalstream.x3snake.game.SnakeGame
import com.tropicalstream.x3snake.input.TrackpadGestureEngine
import com.tropicalstream.x3snake.render.SnakeView
import com.tropicalstream.x3snake.ui.BinocularSbsLayout

class MainActivity : Activity() {

    private val game = SnakeGame()
    private val particles = Particles()
    private val gestures = TrackpadGestureEngine()
    private lateinit var view: SnakeView

    private var running = false
    private var lastFrameMs = 0L

    /** 1dp == 1px on the 640×480-per-eye canvas — idempotent density set. */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()

        view = SnakeView(this, game, particles)
        val root = BinocularSbsLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        }
        setContentView(root)

        gestures.setScreenSize(640, 480)
        gestures.onSwipeVertical = { dir -> game.setDirection(0, dir) }    // -1 up, +1 down
        gestures.onSwipeHorizontal = { dir -> game.setDirection(dir, 0) }  // -1 left, +1 right
        gestures.onTap = { game.onTap(SystemClock.uptimeMillis()) }
        gestures.onDoubleTap = { game.onTap(SystemClock.uptimeMillis()) }
    }

    private fun configureImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    // ~30 fps loop (thermal-friendly); interpolation keeps motion smooth.
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.05f)
            lastFrameMs = now
            game.update(now)
            particles.update(dt)
            view.setFrameTime(now)
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        running = true
        lastFrameMs = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
    }

    override fun onDestroy() {
        super.onDestroy()
        gestures.release()
    }

    // Temple FIRM-click arrives as a KEY — check first so nothing swallows it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gestures.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}

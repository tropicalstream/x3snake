package com.tropicalstream.x3snake.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.tropicalstream.x3snake.game.Particles
import com.tropicalstream.x3snake.game.SnakeGame
import kotlin.math.min
import kotlin.math.sin

/**
 * All rendering for X3 Snake: a synthwave backdrop (sliced sun + perspective floor),
 * a neon board grid, a glowing interpolated snake, a pulsing food orb, particles,
 * and neon HUD/overlays — all on a pure-black (waveguide-transparent) canvas.
 *
 * Glow is done with layered translucent draws (no BlurMaskFilter) so it stays on
 * the hardware-accelerated path that BinocularSbsLayout's dual-draw needs.
 */
class SnakeView(
    context: Context,
    private val game: SnakeGame,
    private val particles: Particles
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val snakePath = Path()
    private val rect = RectF()

    private var vw = 640
    private var vh = 480
    private var cell = 20f
    private var boardX = 0f
    private var boardY = 0f
    private var boardW = 0f
    private var boardH = 0f
    private var frameTime = 0L

    init {
        game.onEat = { c -> particles.burst(cellCx(c.x), cellCy(c.y), FOOD, 26, 240f) }
        game.onDeath = { c -> particles.burst(cellCx(c.x), cellCy(c.y), MAGENTA, 64, 340f) }
    }

    fun setFrameTime(now: Long) {
        frameTime = now
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vw = w; vh = h
        val marginX = w * 0.06f
        val marginTop = h * 0.15f
        val marginBottom = h * 0.06f
        val availW = w - marginX * 2
        val availH = h - marginTop - marginBottom
        cell = min(availW / game.cols, availH / game.rows)
        boardW = cell * game.cols
        boardH = cell * game.rows
        boardX = (w - boardW) / 2f
        boardY = marginTop + (availH - boardH) / 2f
    }

    private fun cellCx(gx: Int) = boardX + (gx + 0.5f) * cell
    private fun cellCy(gy: Int) = boardY + (gy + 0.5f) * cell

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackdrop(canvas)
        drawBoard(canvas)
        if (game.state != SnakeGame.State.READY) {
            drawFood(canvas)
            drawSnake(canvas)
        }
        particles.draw(canvas)
        if (game.state != SnakeGame.State.READY) drawHud(canvas)
        drawOverlay(canvas)
    }

    // ---- backdrop: sliced synth sun + perspective floor ----

    private fun drawBackdrop(canvas: Canvas) {
        val cx = vw / 2f
        val sunCy = boardY * 0.52f
        val r = min(vw, vh) * 0.15f
        canvas.save()
        val clip = Path().apply { addCircle(cx, sunCy, r, Path.Direction.CW) }
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx, sunCy - r, cx, sunCy + r,
            intArrayOf(MAGENTA, PINK, CYAN), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        paint.alpha = 210
        canvas.drawRect(cx - r, sunCy - r, cx + r, sunCy + r, paint)
        paint.shader = null
        // Horizontal slice gaps, thicker toward the bottom (classic outrun sun).
        paint.color = Color.BLACK
        paint.alpha = 255
        var y = sunCy
        var gap = 2f
        while (y < sunCy + r) {
            canvas.drawRect(cx - r, y, cx + r, y + gap, paint)
            y += r * 0.13f + gap
            gap += 1.1f
        }
        canvas.restore()

        // Perspective floor lines fanning from a vanishing point at the horizon.
        val horizonY = boardY + boardH + vh * 0.02f
        val vpX = vw / 2f
        glow.shader = null
        glow.style = Paint.Style.STROKE
        glow.strokeWidth = 1.5f
        glow.color = CYAN
        glow.alpha = 40
        var i = -6
        while (i <= 6) {
            val bx = vpX + i * (vw * 0.14f)
            canvas.drawLine(vpX, horizonY, bx, vh.toFloat(), glow)
            i++
        }
        var hy = horizonY
        var d = vh * 0.04f
        while (hy < vh) {
            canvas.drawLine(0f, hy, vw.toFloat(), hy, glow)
            hy += d
            d *= 1.35f
        }
        glow.alpha = 255
    }

    // ---- neon board ----

    private fun drawBoard(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = CYAN
        paint.alpha = 22
        for (c in 0..game.cols) {
            val x = boardX + c * cell
            canvas.drawLine(x, boardY, x, boardY + boardH, paint)
        }
        for (rr in 0..game.rows) {
            val y = boardY + rr * cell
            canvas.drawLine(boardX, y, boardX + boardW, y, paint)
        }
        paint.alpha = 255
        rect.set(boardX, boardY, boardX + boardW, boardY + boardH)
        strokeGlowRoundRect(canvas, rect, CYAN, 3f)
    }

    // ---- food ----

    private fun drawFood(canvas: Canvas) {
        if (game.food.x < 0) return
        val pulse = 0.5f + 0.5f * sin(frameTime / 180f).toFloat()
        val fx = cellCx(game.food.x)
        val fy = cellCy(game.food.y)
        val baseR = cell * 0.30f * (0.82f + 0.18f * pulse)
        fillGlowCircle(canvas, fx, fy, baseR, FOOD)
    }

    // ---- snake ----

    private fun drawSnake(canvas: Canvas) {
        val body = game.bodyCells
        if (body.isEmpty()) return
        val prev = game.prevBodyCells
        val t = game.progress(frameTime)

        snakePath.reset()
        var headX = 0f
        var headY = 0f
        var tailX = 0f
        var tailY = 0f
        for (i in body.indices) {
            val to = body[i]
            val from = if (i < prev.size) prev[i] else to
            val px = boardX + (lerp(from.x.toFloat(), to.x.toFloat(), t) + 0.5f) * cell
            val py = boardY + (lerp(from.y.toFloat(), to.y.toFloat(), t) + 0.5f) * cell
            if (i == 0) {
                snakePath.moveTo(px, py); headX = px; headY = py
            } else {
                snakePath.lineTo(px, py)
            }
            tailX = px; tailY = py
        }

        val w = cell * 0.72f
        val grad = LinearGradient(headX, headY, tailX, tailY, HEAD, TAIL, Shader.TileMode.CLAMP)
        // Outer glow layers (faint, wide) → crisp core.
        glow.style = Paint.Style.STROKE
        for (i in 4 downTo 1) {
            glow.shader = grad
            glow.alpha = 12 + (4 - i) * 6
            glow.strokeWidth = w + i * 7f
            canvas.drawPath(snakePath, glow)
        }
        glow.shader = grad
        glow.alpha = 255
        glow.strokeWidth = w
        canvas.drawPath(snakePath, glow)
        glow.shader = null

        drawHead(canvas, headX, headY)
    }

    private fun drawHead(canvas: Canvas, hx: Float, hy: Float) {
        // Bright head knob.
        paint.style = Paint.Style.FILL
        paint.color = HEAD
        paint.alpha = 255
        canvas.drawCircle(hx, hy, cell * 0.42f, paint)
        paint.color = Color.WHITE
        paint.alpha = 200
        canvas.drawCircle(hx, hy, cell * 0.18f, paint)
        // Eyes offset perpendicular to travel.
        val (dx, dy) = game.dirVec()
        val ex = -dy.toFloat()
        val ey = dx.toFloat()
        val off = cell * 0.20f
        val fwd = cell * 0.12f
        paint.color = Color.BLACK
        paint.alpha = 255
        canvas.drawCircle(hx + ex * off + dx * fwd, hy + ey * off + dy * fwd, cell * 0.07f, paint)
        canvas.drawCircle(hx - ex * off + dx * fwd, hy - ey * off + dy * fwd, cell * 0.07f, paint)
    }

    // ---- HUD + overlays ----

    private fun drawHud(canvas: Canvas) {
        text.textSize = vh * 0.075f
        glowText(canvas, "SCORE ${game.score}", boardX + 4f, vh * 0.11f, CYAN, Paint.Align.LEFT)
        glowText(canvas, "HI ${game.high}", boardX + boardW - 4f, vh * 0.11f, MAGENTA, Paint.Align.RIGHT)
    }

    private fun drawOverlay(canvas: Canvas) {
        when (game.state) {
            SnakeGame.State.READY -> {
                text.textSize = vh * 0.15f
                glowText(canvas, "X3 SNAKE", vw / 2f, vh * 0.42f, CYAN, Paint.Align.CENTER)
                text.textSize = vh * 0.055f
                glowText(canvas, "swipe to turn  •  tap to start", vw / 2f, vh * 0.56f, PINK, Paint.Align.CENTER)
            }
            SnakeGame.State.DEAD -> {
                text.textSize = vh * 0.13f
                glowText(canvas, "GAME OVER", vw / 2f, vh * 0.42f, MAGENTA, Paint.Align.CENTER)
                text.textSize = vh * 0.06f
                glowText(canvas, "score ${game.score}   •   hi ${game.high}", vw / 2f, vh * 0.55f, CYAN, Paint.Align.CENTER)
                text.textSize = vh * 0.05f
                glowText(canvas, "tap to play again", vw / 2f, vh * 0.66f, PINK, Paint.Align.CENTER)
            }
            SnakeGame.State.RUNNING -> {}
        }
    }

    // ---- glow helpers (layered, hardware-friendly) ----

    private fun strokeGlowRoundRect(canvas: Canvas, r: RectF, color: Int, coreWidth: Float) {
        glow.shader = null
        glow.style = Paint.Style.STROKE
        for (i in 4 downTo 1) {
            glow.color = color
            glow.alpha = 14 + (4 - i) * 8
            glow.strokeWidth = coreWidth + i * 3f
            canvas.drawRoundRect(r, 8f, 8f, glow)
        }
        glow.color = color
        glow.alpha = 255
        glow.strokeWidth = coreWidth
        canvas.drawRoundRect(r, 8f, 8f, glow)
    }

    private fun fillGlowCircle(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        for (i in 5 downTo 1) {
            paint.color = color
            paint.alpha = 26 + (5 - i) * 8
            canvas.drawCircle(x, y, radius * (1f + i * 0.5f), paint)
        }
        paint.color = color
        paint.alpha = 255
        canvas.drawCircle(x, y, radius, paint)
        paint.color = Color.WHITE
        paint.alpha = 220
        canvas.drawCircle(x, y, radius * 0.45f, paint)
    }

    private fun glowText(canvas: Canvas, s: String, x: Float, y: Float, color: Int, align: Paint.Align) {
        text.textAlign = align
        text.color = color
        text.setShadowLayer(text.textSize * 0.35f, 0f, 0f, color)
        canvas.drawText(s, x, y, text)
        text.clearShadowLayer()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private val HEAD = 0xFF00F0FF.toInt()   // cyan
        private val TAIL = 0xFFFF2D95.toInt()    // hot pink
        private val FOOD = 0xFF39FF14.toInt()    // neon green
        private val CYAN = 0xFF00E5FF.toInt()
        private val PINK = 0xFFFF6EC7.toInt()
        private val MAGENTA = 0xFFFF2D95.toInt()
    }
}

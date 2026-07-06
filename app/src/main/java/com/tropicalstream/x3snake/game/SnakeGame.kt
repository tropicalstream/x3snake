package com.tropicalstream.x3snake.game

/**
 * Pure grid-based Snake logic — no rendering, no Android deps. The renderer reads
 * [bodyCells] + [prevBodyCells] + [progress] to interpolate smooth motion between
 * discrete grid steps.
 */
class SnakeGame(val cols: Int = 24, val rows: Int = 18) {

    enum class State { READY, RUNNING, DEAD }

    data class Cell(val x: Int, val y: Int)

    var state = State.READY
        private set
    var score = 0
        private set
    var high = 0
        private set
    var food = Cell(-1, -1)
        private set

    private val body = ArrayDeque<Cell>()
    private var prevBody: List<Cell> = emptyList()
    private var dirX = 1
    private var dirY = 0
    private var pendingX = 1
    private var pendingY = 0
    private var grow = 0
    private var stepMs = START_STEP_MS
    private var lastStepMs = 0L

    /** Fired (with the eaten/killing cell) so the renderer can spawn particles. */
    var onEat: ((Cell) -> Unit)? = null
    var onDeath: ((Cell) -> Unit)? = null

    val bodyCells: List<Cell> get() = body
    val prevBodyCells: List<Cell> get() = prevBody
    fun dirVec(): Pair<Int, Int> = dirX to dirY

    fun reset(now: Long) {
        body.clear()
        val cx = cols / 2
        val cy = rows / 2
        body.addLast(Cell(cx, cy))
        body.addLast(Cell(cx - 1, cy))
        body.addLast(Cell(cx - 2, cy))
        prevBody = body.toList()
        dirX = 1; dirY = 0; pendingX = 1; pendingY = 0
        grow = 0
        score = 0
        stepMs = START_STEP_MS
        lastStepMs = now
        placeFood()
        state = State.RUNNING
    }

    /** Tap starts a fresh game from READY or DEAD; ignored while RUNNING. */
    fun onTap(now: Long) {
        if (state != State.RUNNING) reset(now)
    }

    fun setDirection(dx: Int, dy: Int) {
        if (state != State.RUNNING) return
        if (dx != 0 && dy != 0) return            // no diagonals
        if (dx == -dirX && dy == -dirY) return    // no 180° reversal
        pendingX = dx; pendingY = dy
    }

    fun update(now: Long) {
        if (state != State.RUNNING) return
        if (now - lastStepMs >= stepMs) {
            step()
            lastStepMs = now
        }
    }

    /** Fraction [0,1) through the current step, for render interpolation. */
    fun progress(now: Long): Float {
        if (state != State.RUNNING) return 1f
        return ((now - lastStepMs).toFloat() / stepMs).coerceIn(0f, 1f)
    }

    private fun step() {
        dirX = pendingX; dirY = pendingY
        val head = body.first()
        val nx = head.x + dirX
        val ny = head.y + dirY
        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows || hitsBody(nx, ny)) {
            state = State.DEAD
            high = maxOf(high, score)
            onDeath?.invoke(head)
            return
        }
        prevBody = body.toList()
        body.addFirst(Cell(nx, ny))
        if (nx == food.x && ny == food.y) {
            score += 1
            grow += 1
            stepMs = maxOf(MIN_STEP_MS, START_STEP_MS - score * 4L)
            onEat?.invoke(food)
            placeFood()
        }
        if (grow > 0) grow -= 1 else body.removeLast()
    }

    private fun hitsBody(x: Int, y: Int): Boolean {
        // The tail moves away this step (unless growing), so it's not a collision.
        val ignoreTail = grow == 0
        val last = body.size - 1
        body.forEachIndexed { i, c ->
            if (!(ignoreTail && i == last) && c.x == x && c.y == y) return true
        }
        return false
    }

    private fun placeFood() {
        var c: Cell
        var tries = 0
        do {
            c = Cell((0 until cols).random(), (0 until rows).random())
            tries++
        } while (occupied(c) && tries < 300)
        food = c
    }

    private fun occupied(c: Cell) = body.any { it.x == c.x && it.y == c.y }

    companion object {
        const val START_STEP_MS = 150L
        const val MIN_STEP_MS = 70L
    }
}

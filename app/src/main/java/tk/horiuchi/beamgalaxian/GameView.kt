package tk.horiuchi.beamgalaxian

import android.content.Context
import android.graphics.*
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.random.Random

class GameView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint()
    private var score = 0
    private var playerLives = 4
    private var bullet: Bullet? = null
    private val enemies = mutableListOf<Enemy>()
    private val enemyBullets = mutableListOf<Bullet>()
    private var playerX = 1
    private var canShoot = true
    private var gameOver = false
    private val hitFlashDuration = 5
    private var moveRight = true
    private var enemyMoveCounter = 0
    private var playerFlash = 0
    private var attackingEnemyToggle = true
    private var topInset = 0
    //private val stars = List(100) { PointF(Random.nextFloat(), Random.nextFloat()) }
    private val stars = List(100) {
        Star(Random.nextFloat(), Random.nextFloat(), true)
    }

    private val enemyWait0 = BitmapFactory.decodeResource(resources, R.drawable.galaxian00)
    private val enemyWait1 = BitmapFactory.decodeResource(resources, R.drawable.galaxian01)
    private val enemyAtk0 = BitmapFactory.decodeResource(resources, R.drawable.galaxian10)
    private val enemyAtk1 = BitmapFactory.decodeResource(resources, R.drawable.galaxian11)
    private val fighter1 = BitmapFactory.decodeResource(resources, R.drawable.fighter1)
    private val fighter0 = BitmapFactory.decodeResource(resources, R.drawable.fighter0)
    private val fighterIcon = BitmapFactory.decodeResource(resources, R.drawable.fighter)

    private val seg7Digits = (0..9).associateWith {
        BitmapFactory.decodeResource(resources, resources.getIdentifier("seg7_$it", "drawable", context.packageName))
    }
    private val seg7Blank = BitmapFactory.decodeResource(resources, R.drawable.seg7_blank)

    // BGM
    private var gameStartPlayer: MediaPlayer? = null
    private var gameOverPlayer: MediaPlayer? = null

    // 効果音用 SoundPool
    private val soundPool = SoundPool.Builder().setMaxStreams(5).build()
    private var seEnemyMove = 0
    private var seBullet = 0
    private var seEnemyDown = 0
    private var sePlayerHit = 0

    private var gameOverCooldown = 0
    private var isPaused = false
    private var enemyWaitMoveToggle = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        // BGM
        //gameStartPlayer = MediaPlayer.create(context, R.raw.bgm_gamestart)
        //gameOverPlayer = MediaPlayer.create(context, R.raw.bgm_gameover)

        // SE
        //seEnemyMove = soundPool.load(context, R.raw.se_enemy_move, 1)
        seBullet = soundPool.load(context, R.raw.se_bullet, 1)
        seEnemyDown = soundPool.load(context, R.raw.se_enemy_down, 1)
        sePlayerHit = soundPool.load(context, R.raw.se_player_hit, 1)

        setOnApplyWindowInsetsListener { _, insets ->
            topInset = insets.systemGestureInsets.top
            insets
        }

        startGame()
        postDelayed(object : Runnable {
            override fun run() {
                updateGame()
                invalidate()
                postDelayed(this, 150)
            }
        }, 150)

    }

    private fun startGame() {
        gameOverPlayer?.release()
        gameOverPlayer = null
        gameStartPlayer = MediaPlayer.create(context, R.raw.bgm_gamestart)
        gameStartPlayer?.start()

        score = 0
        playerLives = 4
        bullet = null
        enemies.clear()
        enemyBullets.clear()
        playerX = 1
        canShoot = true
        gameOver = false
        moveRight = true
        enemyMoveCounter = 0
        playerFlash = 0
        attackingEnemyToggle = true
        spawnEnemies()
    }

    private fun updateGame() {
        if (isPaused) return

        if (gameOverCooldown > 0) {
            gameOverCooldown--
        }
        if (gameOver || playerFlash > 0) {
            if (playerFlash > 0) playerFlash--
            return
        }
        enemyMoveCounter += 150
        val shouldMoveEnemies = enemyMoveCounter >= 400
        if (shouldMoveEnemies) {
            enemyMoveCounter = 0
            enemyWaitMoveToggle = !enemyWaitMoveToggle  // 1回ごとにトグル
        }

        bullet?.let {
            it.y--
            val hitEnemy = enemies.find { e -> e.state == EnemyState.ATTACKING && e.x == it.x && (e.y == 8 || e.y == it.y) }
            if (hitEnemy != null) {
                hitEnemy.flash = hitFlashDuration
                hitEnemy.state = EnemyState.REMOVED
                bullet = null
                soundPool.play(sePlayerHit, 1f, 1f, 0, 0, 1f)
                //score += (50 - (hitEnemy.y - 4) * 10).coerceAtLeast(10)
                val point = (50 - (hitEnemy.y - 4) * 10).coerceAtLeast(10)
                score += point
                if (score >= 2000) score -= 2000
            } else if (it.y < 4) {
                bullet = null
            }
        }

        enemyBullets.forEach { it.y++ }
        enemyBullets.removeAll { it.y > 9 }
        if (enemyBullets.any { it.x == playerX && it.y == 9 }) {
            playerLives--
            playerFlash = hitFlashDuration * 2
            enemyBullets.clear()
            bullet = null // ← 自機のビームを消す
            soundPool.play(seEnemyDown, 1f, 1f, 0, 0, 1f)
            if (playerLives <= 0) {
                gameOver = true
                // ★ ゲームオーバー時のBGMを再生
                gameOverPlayer = MediaPlayer.create(context, R.raw.bgm_gameover)
                gameOverPlayer?.start()
                gameOverCooldown = 15;
            }
            return
        }

        enemies.forEach { if (it.flash > 0) it.flash-- }

        if (shouldMoveEnemies) {
            val dx = if (moveRight) 1 else -1
            if (enemyWaitMoveToggle) {
                val canMove =
                    enemies.filter { it.state == EnemyState.WAITING }.all { (it.x + dx) in 0..3 }
                if (canMove) {
                    enemies.filter { it.state == EnemyState.WAITING }.forEach { it.x += dx }
                } else {
                    moveRight = !moveRight
                }
            }

            enemies.filter { it.state == EnemyState.ATTACKING }.forEach {
                if (attackingEnemyToggle) {
                    //it.x += it.dir
                    //if (it.x !in 0..3) {
                    //    it.dir *= -1
                    //    it.x += it.dir * 2
                    //    it.y++
                    //}
                    // ★ ランダムで左右どちらかへ動かす
                    //val dx = if (Random.nextBoolean()) 1 else -1
                    // ★ 左右の端なら強制反転、それ以外ならランダム
                    val dx = when (it.x) {
                        0 -> 1  // 左端 → 右へ
                        3 -> -1 // 右端 → 左へ
                        else -> if (Random.nextBoolean()) 1 else -1  // 中央 → ランダム
                    }
                    val newX = it.x + dx
                    if (newX in 0..3) {
                        it.x = newX
                    }
                    // ★ ランダムで1段下がる
                    if (Random.nextBoolean()) {
                        it.y++
                    }

                    if (it.y < 8 && Random.nextInt(100) < 70) {
                        enemyBullets.add(Bullet(it.x, it.y + 1))
                    }
                    if (it.y > 8) {
                        it.y = 4
                    }
                }
            }
            attackingEnemyToggle = !attackingEnemyToggle
        }

        //if (enemies.none { it.state == EnemyState.ATTACKING }) {
        //    enemies.find { it.state == EnemyState.WAITING }?.let {
        //        it.state = EnemyState.ATTACKING
        //        it.y = 4
        //    }
        //}
        val flashingEnemyExists = enemies.any { it.state == EnemyState.REMOVED && it.flash > 0 }

        if (!flashingEnemyExists && enemies.none { it.state == EnemyState.ATTACKING }) {
            enemies.find { it.state == EnemyState.WAITING }?.let {
                it.state = EnemyState.ATTACKING
                it.y = 4
            }
        }

        enemies.removeAll { it.state == EnemyState.REMOVED && it.flash <= 0 }

        if (enemies.isEmpty()) spawnEnemies()

        // 星の点滅状態を更新
        stars.forEach { star ->
            if (Random.nextInt(100) < 20) {
                star.visible = !star.visible
            }
        }
    }

    private fun spawnEnemies() {
        enemies.clear()
        for (i in 0 until 6) {
            val x = i % 3
            val y = 2 + (i / 3)
            enemies.add(Enemy(x, y, EnemyState.WAITING, 1, 0, Random.nextLong(0, 600)))
        }
    }

    data class Bullet(var x: Int, var y: Int)
    data class Enemy(var x: Int, var y: Int, var state: EnemyState, var dir: Int, var flash: Int, val animOffset: Long = Random.nextLong(0, 600))
    data class Star(val x: Float, val y: Float, var visible: Boolean)
    enum class EnemyState { WAITING, ATTACKING, REMOVED }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        paint.color = Color.WHITE
        paint.strokeWidth = 3f
        stars.forEach { star ->
            if (star.visible) {
                val x = star.x * width
                val y = star.y * height
                canvas.drawPoint(x, y, paint)
            }
        }

        val gridWidth = 4
        val gridHeight = 10
        val cellSize = Math.min(width / gridWidth, height / (gridHeight + 2))
        val offsetX = (width - gridWidth * cellSize) / 2
        val offsetY = topInset + 20

        paint.color = Color.WHITE
        paint.textSize = 40f
        //canvas.drawText("SCORE: $score", offsetX.toFloat(), (offsetY - 10).toFloat(), paint)

        val scoreStr = String.format("%04d", score.coerceAtMost(9999))
        val digitSize = cellSize
        val scoreLeft = offsetX + (gridWidth * cellSize - 4 * digitSize) / 2
        val scoreTop = offsetY
        for (i in 0 until 4) {
            val digitChar = scoreStr[i]
            //val bmp = if (digitChar == '0' && i == 0 && score < 100) {
            //    seg7Blank
            //} else {
            //    seg7Digits[digitChar.toString().toInt()] ?: seg7Blank
            //}
            //val bmp = when {
            //    score == 0 && i < 2 -> seg7Blank
            //    digitChar == '0' && i == 0 && score < 100 -> seg7Blank
            //    digitChar == '0' && i == 1 && score < 10 -> seg7Blank
            //    else -> seg7Digits[digitChar.toString().toInt()] ?: seg7Blank
            //}
            val showBlank = when {
                score == 0 && i < 3 -> true
                score < 10 && i < 3 -> true
                score < 100 && i < 2 -> true
                score < 1000 && i < 1 -> true
                else -> false
            }
            val bmp = if (showBlank) seg7Blank else seg7Digits[digitChar.digitToInt()] ?: seg7Blank

            val left = scoreLeft + i * digitSize
            val top = scoreTop
            val dest = Rect(left, top, left + digitSize, top + digitSize)
            canvas.drawBitmap(bmp, null, dest, paint)
        }

        val lifeTop = scoreTop + digitSize + 10
        val lifeLeft = offsetX + (gridWidth * cellSize - playerLives * cellSize)
        for (i in 0 until playerLives) {
            val left = lifeLeft + i * cellSize
            val dest = Rect(left, lifeTop, left + cellSize, lifeTop + cellSize)
            canvas.drawBitmap(fighterIcon, null, dest, paint)
        }

        enemies.forEach { enemy ->
            if (enemy.flash % 2 == 1) return@forEach
            val bmp = when (enemy.state) {
                //EnemyState.WAITING -> if ((System.currentTimeMillis() / 300) % 2 == 0L) enemyWait0 else enemyWait1
                EnemyState.WAITING -> {
                    val phase = ((System.currentTimeMillis() + enemy.animOffset) / 300) % 2
                    if (phase == 0L) enemyWait0 else enemyWait1
                }
                EnemyState.ATTACKING -> if ((System.currentTimeMillis() / 300) % 2 == 0L) enemyAtk0 else enemyAtk1
                EnemyState.REMOVED -> if ((System.currentTimeMillis() / 300) % 2 == 0L) enemyAtk0 else enemyAtk1
            }
            val dest = Rect(
                offsetX + enemy.x * cellSize,
                offsetY + enemy.y * cellSize,
                offsetX + (enemy.x + 1) * cellSize,
                offsetY + (enemy.y + 1) * cellSize
            )
            canvas.drawBitmap(bmp, null, dest, paint)
        }

        enemyBullets.forEach {
            paint.color = Color.RED
            val left = offsetX + it.x * cellSize + cellSize / 2 - 9
            val top = offsetY + it.y * cellSize
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + 18).toFloat(), (top + cellSize / 2).toFloat(), paint)
        }

        if (playerFlash == 0 && bullet != null) {
            bullet?.let {
                paint.color = Color.CYAN
                val left = offsetX + it.x * cellSize + cellSize / 2 - 9
                val top = offsetY + it.y * cellSize
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + 18).toFloat(),
                    (top + cellSize / 2).toFloat(),
                    paint
                )
            }
        }

        if (playerFlash % 2 == 0) {
            val bmp = if (canShoot) fighter1 else fighter0
            val dest = Rect(
                offsetX + playerX * cellSize,
                offsetY + 9 * cellSize,
                offsetX + (playerX + 1) * cellSize,
                offsetY + (9 + 1) * cellSize
            )
            canvas.drawBitmap(bmp, null, dest, paint)
        }

        if (gameOver) {
            paint.color = Color.WHITE
            paint.textSize = 120f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("GAME OVER", (width / 2).toFloat(), (height / 2).toFloat(), paint)
        }

        //val buttonY = offsetY + (gridHeight + 1) * cellSize
        //val btnW = width / 3
        //val btnH = cellSize
        //paint.color = Color.WHITE
        //paint.textAlign = Paint.Align.CENTER
        //paint.textSize = 48f
        //canvas.drawText("←", (btnW / 2).toFloat(), (buttonY + btnH / 1.5).toFloat(), paint)
        //canvas.drawText("⚪︎", (width / 2).toFloat(), (buttonY + btnH / 1.5).toFloat(), paint)
        //canvas.drawText("→", (width - btnW / 2).toFloat(), (buttonY + btnH / 1.5).toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (gameOver) {
                if (gameOverCooldown == 0) {
                    startGame()
                }
                //Log.w("CoolDown", "downcount="+gameOverCooldown)
                return true
            }

            //val third = width / 3
            //when {
            //    event.y > height * 0.85 && event.x < third -> if (playerX > 0) playerX--
            //    event.y > height * 0.85 && event.x > third * 2 -> if (playerX < 3) playerX++
            //    event.y > height * 0.85 -> {
            //        if (canShoot && bullet == null && playerFlash == 0) {
            //            bullet = Bullet(playerX, 8)
            //            canShoot = false
            //            soundPool.play(seBullet, 1f, 1f, 0, 0, 1f) // ← 追加
            //        }
            //    }
            //}
            //invalidate()
        }
        if (event.action == MotionEvent.ACTION_UP) {
            canShoot = true
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        isPaused = !hasWindowFocus
        if (!hasWindowFocus) {
            gameStartPlayer?.pause()
            gameOverPlayer?.pause()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (playerX > 0) playerX--
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (playerX < 3) playerX++
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (gameOver) {
                    if (gameOverCooldown == 0) {
                        startGame()
                    }
                }
                if (!gameOver && canShoot && bullet == null && playerFlash == 0) {
                    bullet = Bullet(playerX, 8)
                    canShoot = false
                    soundPool.play(seBullet, 1f, 1f, 0, 0, 1f)
                    invalidate()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            canShoot = true
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun moveLeft() {
        if (playerX > 0) playerX--
        invalidate()
    }

    fun moveRight() {
        if (playerX < 3) playerX++
        invalidate()
    }

    fun shootBullet() {
        if (canShoot && bullet == null && playerFlash == 0 && !gameOver) {
            bullet = Bullet(playerX, 8)
            canShoot = false
            soundPool.play(seBullet, 1f, 1f, 0, 0, 1f)
            invalidate()
        }
        if (gameOver) {
            if (gameOverCooldown == 0) {
                startGame()
            }
        }
    }

    fun releaseFire() {
        canShoot = true
    }
}

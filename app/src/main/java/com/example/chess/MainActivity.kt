package com.example.chess

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity(), ChessRenderer.Callbacks {

    private lateinit var glView: ChessGLSurfaceView
    private lateinit var sound: SoundFx

    // HUD (visible during a game)
    private lateinit var status: TextView
    private lateinit var material: TextView
    private lateinit var exitBtn: Button
    private lateinit var viewBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var newGameBtn: Button
    private lateinit var hintBtn: Button

    // Full-screen overlays
    private lateinit var homeOverlay: FrameLayout
    private lateinit var modeOverlay: FrameLayout
    private lateinit var difficultyOverlay: FrameLayout
    private lateinit var colorOverlay: FrameLayout
    private lateinit var victoryOverlay: FrameLayout
    private lateinit var victoryTitle: TextView
    private lateinit var victorySubtitle: TextView

    // Chosen AI search depth (set on the difficulty screen).
    private var chosenDepth = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: GL surface renders behind system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // Light status-bar icons on our dark background.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        requestHighRefreshRate()
        sound = SoundFx()

        val root = FrameLayout(this)
        glView = ChessGLSurfaceView(this, this)
        root.addView(glView, FrameLayout.LayoutParams(MATCH, MATCH))

        buildHud(root)
        homeOverlay = buildHome()
        modeOverlay = buildModeSelect()
        difficultyOverlay = buildDifficultySelect()
        colorOverlay = buildColorSelect()
        victoryOverlay = buildVictory()
        root.addView(homeOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(modeOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(difficultyOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(colorOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(victoryOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        modeOverlay.visibility = View.GONE
        difficultyOverlay.visibility = View.GONE
        colorOverlay.visibility = View.GONE
        victoryOverlay.visibility = View.GONE

        // Shift HUD controls out from under the system bars, using the real
        // device insets instead of guessing a fixed status-bar / nav-bar height.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val topBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottomBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            (status.layoutParams as FrameLayout.LayoutParams).topMargin = topBar + dp(2)
            (material.layoutParams as FrameLayout.LayoutParams).topMargin = topBar + dp(34)
            (exitBtn.layoutParams as FrameLayout.LayoutParams).topMargin = topBar

            (undoBtn.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomBar + dp(10)
            (viewBtn.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomBar + dp(68)
            (hintBtn.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomBar + dp(126)
            (newGameBtn.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomBar + dp(10)

            WindowInsetsCompat.CONSUMED
        }

        setContentView(root)
    }

    // ----------------------------------------------------------- HUD building

    private fun buildHud(root: FrameLayout) {
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(26)
        })

        material = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FFD54F"))
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
        }
        root.addView(material, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(58)
        })

        exitBtn = pillButton("✕ 退出", "#EF5350", "#C62828").apply {
            textSize = 14f
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                glView.postExitToHome()
            }
        }
        root.addView(exitBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(20); marginEnd = dp(16)
        })

        undoBtn = pillButton("↶ 悔棋", "#7E57C2", "#4527A0").apply {
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            isEnabled = false
            alpha = 0.4f
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                glView.postUndo()
            }
        }
        root.addView(undoBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(34); marginStart = dp(20)
        })

        viewBtn = pillButton("切换视角", "#26A69A", "#00695C").apply {
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                glView.postToggleView()
            }
        }
        root.addView(viewBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(92); marginStart = dp(20)
        })

        hintBtn = pillButton("💡 提示", "#FFCA28", "#F9A825").apply {
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                glView.postHint()
            }
        }
        root.addView(hintBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = dp(150); marginStart = dp(20)
        })

        newGameBtn = pillButton("重新开始", "#FFA726", "#EF6C00").apply {
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                glView.postNewGame()
            }
        }
        root.addView(newGameBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(34); marginEnd = dp(20)
        })
    }

    private fun setHudVisible(visible: Boolean) {
        for (v in listOf(status, material, exitBtn, viewBtn, undoBtn, newGameBtn, hintBtn)) {
            if (visible) fadeIn(v) else v.visibility = View.GONE
        }
        if (visible) {
            undoBtn.isEnabled = false
            undoBtn.alpha = 0.4f
        }
    }

    // -------------------------------------------------------- overlay building

    private fun buildHome(): FrameLayout {
        val f = FrameLayout(this)

        val title = TextView(this).apply {
            text = "立体国际象棋"
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 4f, Color.BLACK)
        }
        f.addView(title, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(84)
        })

        val subtitle = TextView(this).apply {
            text = "3D · 实时光影 · 双人 / 人机"
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        f.addView(subtitle, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(140)
        })

        val start = pillButton("开 始", "#66BB6A", "#2E7D32").apply {
            textSize = 22f
            setPadding(dp(56), dp(16), dp(56), dp(16))
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                fadeOut(homeOverlay) { fadeIn(modeOverlay) }
            }
        }
        f.addView(start, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(96)
        })

        return f
    }

    private fun buildModeSelect(): FrameLayout {
        val f = FrameLayout(this)
        val panel = panel()

        panel.addView(heading("选择对战模式"))
        panel.addView(spacer(dp(22)))
        panel.addView(bigChoice("双人对战", "同一台手机轮流走子", "#42A5F5", "#1565C0") {
            sound.play(SoundFx.Type.CLICK)
            startMatch(ChessRenderer.Mode.TWO_PLAYER, PieceColor.BLACK)
        })
        panel.addView(spacer(dp(14)))
        panel.addView(bigChoice("人机对战", "挑战内置 AI 对手", "#66BB6A", "#2E7D32") {
            sound.play(SoundFx.Type.CLICK)
            fadeOut(modeOverlay) { fadeIn(difficultyOverlay) }
        })
        panel.addView(spacer(dp(18)))
        panel.addView(backLink("← 返回") {
            sound.play(SoundFx.Type.CLICK)
            fadeOut(modeOverlay) { fadeIn(homeOverlay) }
        })

        f.addView(panel, centerPanelParams())
        return f
    }

    private fun buildDifficultySelect(): FrameLayout {
        val f = FrameLayout(this)
        val panel = panel()

        panel.addView(heading("选择 AI 难度"))
        panel.addView(spacer(dp(22)))
        panel.addView(bigChoice("简单", "AI 只看一步，会送子", "#66BB6A", "#2E7D32") {
            sound.play(SoundFx.Type.CLICK)
            chosenDepth = 1
            fadeOut(difficultyOverlay) { fadeIn(colorOverlay) }
        })
        panel.addView(spacer(dp(14)))
        panel.addView(bigChoice("普通", "基础战术，均衡对局", "#42A5F5", "#1565C0") {
            sound.play(SoundFx.Type.CLICK)
            chosenDepth = 2
            fadeOut(difficultyOverlay) { fadeIn(colorOverlay) }
        })
        panel.addView(spacer(dp(14)))
        panel.addView(bigChoice("困难", "深算三步，有挑战性", "#EF5350", "#C62828") {
            sound.play(SoundFx.Type.CLICK)
            chosenDepth = 3
            fadeOut(difficultyOverlay) { fadeIn(colorOverlay) }
        })
        panel.addView(spacer(dp(18)))
        panel.addView(backLink("← 返回") {
            sound.play(SoundFx.Type.CLICK)
            fadeOut(difficultyOverlay) { fadeIn(modeOverlay) }
        })

        f.addView(panel, centerPanelParams())
        return f
    }

    private fun buildColorSelect(): FrameLayout {
        val f = FrameLayout(this)
        val panel = panel()

        panel.addView(heading("选择你的棋色"))
        panel.addView(spacer(dp(22)))
        panel.addView(bigChoice("执白 · 先手", "你先走，AI 执黑", "#FAFAFA", "#BDBDBD", Color.parseColor("#212121")) {
            sound.play(SoundFx.Type.CLICK)
            startMatch(ChessRenderer.Mode.VS_AI, PieceColor.BLACK)
        })
        panel.addView(spacer(dp(14)))
        panel.addView(bigChoice("执黑 · 后手", "AI 先走，你执黑", "#455A64", "#263238") {
            sound.play(SoundFx.Type.CLICK)
            startMatch(ChessRenderer.Mode.VS_AI, PieceColor.WHITE)
        })
        panel.addView(spacer(dp(18)))
        panel.addView(backLink("← 返回") {
            sound.play(SoundFx.Type.CLICK)
            fadeOut(colorOverlay) { fadeIn(difficultyOverlay) }
        })

        f.addView(panel, centerPanelParams())
        return f
    }

    /** Full-screen end-of-game banner: who won (or a draw), with replay / home. */
    private fun buildVictory(): FrameLayout {
        val f = FrameLayout(this).apply {
            background = ColorDrawable(Color.parseColor("#B8000000"))
            isClickable = true   // swallow taps so the board behind can't be touched
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(30), dp(30), dp(26))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                colors = intArrayOf(Color.parseColor("#2A2F3A"), Color.parseColor("#12151D"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }

        panel.addView(TextView(this).apply {
            text = "👑"
            textSize = 54f
            gravity = Gravity.CENTER
        })
        panel.addView(spacer(dp(4)))
        victoryTitle = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 3f, Color.BLACK)
        }
        panel.addView(victoryTitle)
        panel.addView(spacer(dp(8)))
        victorySubtitle = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#FFD54F"))
            gravity = Gravity.CENTER
        }
        panel.addView(victorySubtitle)
        panel.addView(spacer(dp(26)))

        panel.addView(pillButton("再来一局", "#66BB6A", "#2E7D32").apply {
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                hideVictory()
                glView.postNewGame()
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(spacer(dp(12)))
        panel.addView(pillButton("返回主页", "#78909C", "#455A64").apply {
            setOnClickListener {
                sound.play(SoundFx.Type.CLICK)
                hideVictory()
                glView.postExitToHome()
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        f.addView(panel, FrameLayout.LayoutParams(dp(300), WRAP).apply {
            gravity = Gravity.CENTER
        })
        return f
    }

    private fun showVictory(s: GameStatus, turn: PieceColor) {
        when (s) {
            GameStatus.CHECKMATE -> {
                val winner = if (turn == PieceColor.WHITE) "黑方" else "白方"
                victoryTitle.text = "$winner 获胜"
                victoryTitle.setTextColor(Color.WHITE)
                victorySubtitle.text = "将死对方的王！"
            }
            GameStatus.STALEMATE -> {
                victoryTitle.text = "和棋"
                victoryTitle.setTextColor(Color.parseColor("#E0E0E0"))
                victorySubtitle.text = "逼和 · 无子可动"
            }
            else -> return
        }
        if (victoryOverlay.visibility == View.VISIBLE) return
        val panel = victoryOverlay.getChildAt(0)
        victoryOverlay.alpha = 0f
        victoryOverlay.visibility = View.VISIBLE
        panel.scaleX = 0.7f; panel.scaleY = 0.7f
        victoryOverlay.animate().alpha(1f).setDuration(280).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(OvershootInterpolator()).start()
    }

    private fun hideVictory() {
        if (victoryOverlay.visibility != View.VISIBLE) return
        victoryOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            victoryOverlay.visibility = View.GONE
        }.start()
    }

    private fun startMatch(mode: ChessRenderer.Mode, aiColor: PieceColor) {
        val current = when {
            modeOverlay.visibility == View.VISIBLE -> modeOverlay
            colorOverlay.visibility == View.VISIBLE -> colorOverlay
            else -> difficultyOverlay
        }
        fadeOut(current)
        val depth = if (mode == ChessRenderer.Mode.TWO_PLAYER) 3 else chosenDepth
        glView.postBeginGame(mode, aiColor, depth)
    }

    // ----------------------------------------------------- renderer callbacks

    override fun onStatus(s: GameStatus, turn: PieceColor) = runOnUiThread { updateStatus(s, turn) }

    override fun onGameStarted() = runOnUiThread { setHudVisible(true) }

    override fun onReturnedHome() = runOnUiThread {
        setHudVisible(false)
        modeOverlay.visibility = View.GONE
        difficultyOverlay.visibility = View.GONE
        colorOverlay.visibility = View.GONE
        victoryOverlay.visibility = View.GONE
        fadeIn(homeOverlay)
    }

    override fun onSound(type: SoundFx.Type) {
        sound.play(type)
    }

    override fun onMaterial(whiteMinusBlack: Int) = runOnUiThread {
        material.text = when {
            whiteMinusBlack > 0 -> "白方领先 +$whiteMinusBlack"
            whiteMinusBlack < 0 -> "黑方领先 +${-whiteMinusBlack}"
            else -> "子力均势"
        }
    }

    override fun onCanUndo(canUndo: Boolean) = runOnUiThread {
        undoBtn.isEnabled = canUndo
        undoBtn.animate().alpha(if (canUndo) 1f else 0.4f).setDuration(160).start()
    }

    override fun onNeedPromotion(apply: (PieceType) -> Unit) = runOnUiThread {
        val labels = arrayOf("后 ♛", "车 ♜", "象 ♝", "马 ♞")
        val types = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
        AlertDialog.Builder(this)
            .setTitle("升变为")
            .setCancelable(false)
            .setItems(labels) { _, which -> apply(types[which]) }
            .show()
    }

    private fun updateStatus(s: GameStatus, turn: PieceColor) {
        val side = if (turn == PieceColor.WHITE) "白方" else "黑方"
        status.text = when (s) {
            GameStatus.ONGOING -> "$side 走棋"
            GameStatus.CHECK -> "$side 被将军！"
            GameStatus.CHECKMATE -> {
                val winner = if (turn == PieceColor.WHITE) "黑方" else "白方"
                "将死！$winner 获胜"
            }
            GameStatus.STALEMATE -> "和棋（逼和）"
        }
        when (s) {
            GameStatus.CHECKMATE, GameStatus.STALEMATE -> showVictory(s, turn)
            else -> hideVictory()
        }
    }

    // ----------------------------------------------------- lifecycle / helpers

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { super.onPause(); glView.onPause() }

    private fun fadeIn(v: View) {
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(320).start()
    }

    private fun fadeOut(v: View, then: (() -> Unit)? = null) {
        v.animate().alpha(0f).setDuration(240).withEndAction {
            v.visibility = View.GONE
            then?.invoke()
        }.start()
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val params = window.attributes
            params.preferredRefreshRate = 120f
            window.attributes = params
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(26), dp(26), dp(26), dp(22))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#E61C1F26"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
    }

    private fun centerPanelParams() = FrameLayout.LayoutParams(dp(300), WRAP).apply {
        gravity = Gravity.CENTER
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, h)
    }

    private fun backLink(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor("#B0BEC5"))
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { onClick() }
    }

    /** A large two-line choice button used on the selection screens. */
    private fun bigChoice(
        title: String, subtitle: String, top: String, bottom: String,
        textColor: Int = Color.WHITE, onClick: () -> Unit
    ): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                colors = intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        ll.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
        })
        ll.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(textColor)
            alpha = 0.85f
            gravity = Gravity.CENTER
        })
        ll.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        return ll
    }

    private fun pillButton(text: String, top: String, bottom: String): Button {
        return Button(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(dp(36), dp(14), dp(36), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                colors = intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            stateListAnimator = null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

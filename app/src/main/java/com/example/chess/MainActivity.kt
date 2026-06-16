package com.example.chess

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // In-game character avatar and name
    private lateinit var characterAvatar: ImageView
    private lateinit var characterNameText: TextView

    // Full-screen overlays
    private lateinit var homeOverlay: FrameLayout
    private lateinit var modeOverlay: FrameLayout
    private lateinit var characterOverlay: FrameLayout
    private lateinit var colorOverlay: FrameLayout
    private lateinit var victoryOverlay: FrameLayout
    private lateinit var victoryTitle: TextView
    private lateinit var victorySubtitle: TextView
    private lateinit var stalemateUndoBtn: Button
    private lateinit var curveOverlay: FrameLayout
    private lateinit var detailOverlay: FrameLayout

    // Win-rate display
    private lateinit var winRateText: TextView
    private var evalForCurve: List<Float> = emptyList()

    // Chosen AI character
    private var chosenCharacter: CharacterDef = Characters.ALL[2] // default: 佐藤

    // Track current game mode so callbacks can adapt (e.g. hide avatar in 2P).
    private var currentMode: ChessRenderer.Mode? = null
    // Track whether the human player is black (for flipping the win-rate curve).
    private var playerIsBlack: Boolean = false

    // Dialog bubble for AI character speech
    private lateinit var dialogBubble: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        requestHighRefreshRate()
        sound = SoundFx()

        val root = FrameLayout(this)
        glView = ChessGLSurfaceView(this, this)
        root.addView(glView, FrameLayout.LayoutParams(MATCH, MATCH))

        buildHud(root)
        homeOverlay = buildHome()
        modeOverlay = buildModeSelect()
        characterOverlay = buildCharacterSelect()
        colorOverlay = buildColorSelect()
        victoryOverlay = buildVictory()
        curveOverlay = buildCurveOverlay()
        detailOverlay = buildDetailOverlay()
        root.addView(homeOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(modeOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(characterOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(colorOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(victoryOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(curveOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(detailOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        modeOverlay.visibility = View.GONE
        characterOverlay.visibility = View.GONE
        colorOverlay.visibility = View.GONE
        victoryOverlay.visibility = View.GONE
        curveOverlay.visibility = View.GONE
        detailOverlay.visibility = View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val topBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottomBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            (status.layoutParams as FrameLayout.LayoutParams).topMargin = topBar + dp(2)
            (material.layoutParams as FrameLayout.LayoutParams).topMargin = topBar + dp(34)
            (winRateText.layoutParams as FrameLayout.LayoutParams).topMargin = topBar + dp(6)
            (exitBtn.layoutParams as FrameLayout.LayoutParams).topMargin = topBar
            (characterAvatar.layoutParams as FrameLayout.LayoutParams).topMargin = topBar + dp(18)

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
            textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK); visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(26)
        })

        material = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#FFD54F")); gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK); visibility = View.GONE
        }
        root.addView(material, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(58)
        })

        winRateText = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 1f, Color.BLACK); visibility = View.GONE
        }
        root.addView(winRateText, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = dp(26); marginStart = dp(16)
        })

        // In-game character avatar (left side, visible during AI games)
        characterAvatar = ImageView(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#44FFFFFF"))
            }
        }
        root.addView(characterAvatar, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = dp(58); marginStart = dp(42)
        })

        characterNameText = TextView(this).apply {
            textSize = 10f; setTextColor(Color.parseColor("#CCFFFFFF"))
            gravity = Gravity.CENTER; visibility = View.GONE
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        root.addView(characterNameText, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = dp(108); marginStart = dp(36)
        })

        exitBtn = pillButton("✕ 退出", "#EF5350", "#C62828").apply {
            textSize = 14f; setPadding(dp(20), dp(10), dp(20), dp(10)); visibility = View.GONE
            setOnClickListener { sound.play(SoundFx.Type.CLICK); glView.postExitToHome() }
        }
        root.addView(exitBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(20); marginEnd = dp(16)
        })

        undoBtn = pillButton("↶ 悔棋", "#7E57C2", "#4527A0").apply {
            textSize = 14f; setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE; isEnabled = false; alpha = 0.4f
            setOnClickListener { sound.play(SoundFx.Type.CLICK); glView.postUndo() }
        }
        root.addView(undoBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = dp(34); marginStart = dp(20)
        })

        viewBtn = pillButton("切换视角", "#26A69A", "#00695C").apply {
            textSize = 14f; setPadding(dp(20), dp(12), dp(20), dp(12)); visibility = View.GONE
            setOnClickListener { sound.play(SoundFx.Type.CLICK); glView.postToggleView() }
        }
        root.addView(viewBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = dp(92); marginStart = dp(20)
        })

        hintBtn = pillButton("💡 提示", "#FFCA28", "#F9A825").apply {
            textSize = 14f; setPadding(dp(20), dp(12), dp(20), dp(12)); visibility = View.GONE
            setOnClickListener { sound.play(SoundFx.Type.CLICK); glView.postHint() }
        }
        root.addView(hintBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = dp(150); marginStart = dp(20)
        })

        // Dialog bubble — positioned near avatar on left side
        dialogBubble = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                colors = intArrayOf(Color.parseColor("#E61C1F26"), Color.parseColor("#E60D1018"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            visibility = View.GONE
        }
        root.addView(dialogBubble, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = dp(62); marginStart = dp(100)
        })

        newGameBtn = pillButton("重新开始", "#FFA726", "#EF6C00").apply {
            textSize = 14f; setPadding(dp(20), dp(12), dp(20), dp(12)); visibility = View.GONE
            setOnClickListener { sound.play(SoundFx.Type.CLICK); glView.postNewGame() }
        }
        root.addView(newGameBtn, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = dp(34); marginEnd = dp(20)
        })
    }

    private fun setHudVisible(visible: Boolean) {
        for (v in listOf(status, material, winRateText, exitBtn, viewBtn, undoBtn, newGameBtn, hintBtn)) {
            if (visible) fadeIn(v) else v.visibility = View.GONE
        }
        if (visible) {
            undoBtn.isEnabled = false; undoBtn.alpha = 0.4f
        } else {
            if (::dialogBubble.isInitialized) {
                dialogBubble.removeCallbacks(dismissBubbleRunnable)
                dialogBubble.visibility = View.GONE
            }
            if (::characterAvatar.isInitialized) characterAvatar.visibility = View.GONE
            if (::characterNameText.isInitialized) characterNameText.visibility = View.GONE
        }
    }

    private fun showInGameAvatar(char: CharacterDef) {
        characterAvatar.setImageResource(char.avatarResId)
        (characterAvatar.background as GradientDrawable).setStroke(dp(2), char.themeColor)
        characterNameText.text = char.name
        characterAvatar.visibility = View.VISIBLE
        characterNameText.visibility = View.VISIBLE

        // Smooth entrance animation
        characterAvatar.scaleX = 0f; characterAvatar.scaleY = 0f
        characterAvatar.alpha = 0f
        characterAvatar.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(700).setInterpolator(OvershootInterpolator()).start()
        characterNameText.alpha = 0f
        characterNameText.animate().alpha(1f).setDuration(300).start()
    }

    // -------------------------------------------------------- overlay building

    private fun buildHome(): FrameLayout {
        val f = FrameLayout(this)
        val title = TextView(this).apply {
            text = "立体国际象棋"; textSize = 36f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setShadowLayer(14f, 0f, 4f, Color.BLACK)
        }
        f.addView(title, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(84)
        })
        val subtitle = TextView(this).apply {
            text = "3D · 实时光影 · 12 位对手等你挑战"
            textSize = 15f; setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        f.addView(subtitle, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(140)
        })
        val start = pillButton("开 始", "#66BB6A", "#2E7D32").apply {
            textSize = 22f; setPadding(dp(56), dp(16), dp(56), dp(16))
            setOnClickListener { sound.play(SoundFx.Type.CLICK); fadeOut(homeOverlay) { fadeIn(modeOverlay) } }
        }
        f.addView(start, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(96)
        })
        return f
    }

    private fun buildModeSelect(): FrameLayout {
        val f = FrameLayout(this); val panel = panel()
        panel.addView(heading("选择对战模式")); panel.addView(spacer(dp(22)))
        panel.addView(bigChoice("双人对战", "同一台手机轮流走子", "#42A5F5", "#1565C0") {
            sound.play(SoundFx.Type.CLICK); startMatch(ChessRenderer.Mode.TWO_PLAYER, PieceColor.BLACK)
        })
        panel.addView(spacer(dp(14)))
        panel.addView(bigChoice("人机对战", "挑战 12 位 AI 对手", "#66BB6A", "#2E7D32") {
            sound.play(SoundFx.Type.CLICK); fadeOut(modeOverlay) { fadeIn(characterOverlay) }
        })
        panel.addView(spacer(dp(18)))
        panel.addView(backLink("← 返回") { sound.play(SoundFx.Type.CLICK); fadeOut(modeOverlay) { fadeIn(homeOverlay) } })
        f.addView(panel, centerPanelParams())
        return f
    }

    // ======================== Character selection list ========================

    private fun buildCharacterSelect(): FrameLayout {
        val f = FrameLayout(this)
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val panel = panel()
        panel.addView(heading("选择对手")); panel.addView(spacer(dp(16)))

        for (char in Characters.ALL) {
            panel.addView(characterRow(char) { showCharacterDetail(char) })
            panel.addView(spacer(dp(8)))
        }
        panel.addView(spacer(dp(6)))
        panel.addView(backLink("← 返回") { sound.play(SoundFx.Type.CLICK); fadeOut(characterOverlay) { fadeIn(modeOverlay) } })
        scrollView.addView(panel)
        f.addView(scrollView, FrameLayout.LayoutParams(dp(340), MATCH).apply {
            gravity = Gravity.CENTER; topMargin = dp(56); bottomMargin = dp(40)
        })
        return f
    }

    private fun characterRow(char: CharacterDef, onClick: () -> Unit): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#CC2A2F3A")); setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            isClickable = true; setOnClickListener { onClick() }
        }
        val avatar = ImageView(this).apply {
            setImageResource(char.avatarResId)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        ll.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
        textCol.addView(TextView(this).apply {
            text = char.name; textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(this).apply {
            text = char.title; textSize = 12f; setTextColor(Color.parseColor("#B0BEC5"))
        })
        textCol.addView(TextView(this).apply {
            text = "棋力 ${char.strength}  ${"★".repeat(char.depth)}${"☆".repeat(maxOf(0, 7 - char.depth))}"
            textSize = 11f; setTextColor(Color.parseColor("#FFD54F"))
        })
        ll.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        ll.addView(TextView(this).apply {
            text = "›"; textSize = 26f; setTextColor(Color.parseColor("#55FFFFFF"))
        })
        return ll
    }

    // ======================== Character detail popup ========================

    private var detailChar: CharacterDef? = null

    private fun buildDetailOverlay(): FrameLayout {
        val f = FrameLayout(this).apply {
            background = ColorDrawable(Color.parseColor("#00000000"))
            isClickable = true; visibility = View.GONE
        }
        // Will be populated dynamically in showCharacterDetail
        return f
    }

    private fun showCharacterDetail(char: CharacterDef) {
        detailChar = char
        val f = detailOverlay
        f.animate().cancel(); f.alpha = 1f; f.isClickable = true
        f.removeAllViews()

        // Dim background
        val dimBg = View(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000")); alpha = 0f
            isClickable = true; setOnClickListener { hideCharacterDetail() }
        }
        f.addView(dimBg, FrameLayout.LayoutParams(MATCH, MATCH))
        dimBg.animate().alpha(1f).setDuration(300).start()

        // Large avatar image (120dp = ~1/3 screen width)
        val largeAvatar = ImageView(this).apply {
            setImageResource(char.avatarResId)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), char.themeColor)
            }
            scaleX = 0.3f; scaleY = 0.3f; alpha = 0f
        }
        f.addView(largeAvatar, FrameLayout.LayoutParams(dp(120), dp(120)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(70)
        })
        largeAvatar.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(700).setInterpolator(OvershootInterpolator(0.8f)).start()

        // Detail card (slides up from bottom)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(22))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                colors = intArrayOf(Color.parseColor("#F02A2F3A"), Color.parseColor("#F012151D"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
            translationY = dp(400).toFloat()
        }

        card.addView(TextView(this).apply {
            text = char.name; textSize = 26f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        card.addView(spacer(dp(4)))
        card.addView(TextView(this).apply {
            text = char.title; textSize = 14f; setTextColor(Color.parseColor("#B0BEC5")); gravity = Gravity.CENTER
        })
        card.addView(spacer(dp(8)))
        card.addView(TextView(this).apply {
            text = "棋力 ${char.strength}  ${"★".repeat(char.depth)}${"☆".repeat(maxOf(0, 7 - char.depth))}"
            textSize = 16f; setTextColor(Color.parseColor("#FFD54F")); gravity = Gravity.CENTER
        })
        card.addView(spacer(dp(16)))
        // Divider
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })
        card.addView(spacer(dp(14)))
        // Signature quote
        card.addView(TextView(this).apply {
            text = "\"${char.signatureQuote}\""
            textSize = 15f; setTextColor(Color.parseColor("#E0E0E0"))
            gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            setPadding(dp(8), 0, dp(8), 0)
        })
        card.addView(spacer(dp(22)))

        // Buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        btnRow.addView(pillButton("✕ 取消", "#78909C", "#455A64").apply {
            textSize = 16f; setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { hideCharacterDetail() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(10) })
        btnRow.addView(pillButton("✓ 确定", "#66BB6A", "#2E7D32").apply {
            textSize = 16f; setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener { confirmCharacter(largeAvatar, dimBg, card) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))

        f.addView(card, FrameLayout.LayoutParams(dp(310), WRAP).apply {
            gravity = Gravity.CENTER; topMargin = dp(220)
        })
        f.visibility = View.VISIBLE

        // Slide card up
        card.animate().translationY(0f).setDuration(400)
            .setInterpolator(OvershootInterpolator(0.6f)).start()
    }

    private fun hideCharacterDetail() {
        detailChar = null
        val f = detailOverlay
        // Cancel all running animations on children and self
        for (i in 0 until f.childCount) f.getChildAt(i)?.animate()?.cancel()
        f.animate().cancel()
        f.isClickable = false
        f.animate().alpha(0f).setDuration(200).withEndAction {
            if (detailChar == null) { f.visibility = View.GONE; f.removeAllViews() }
            f.isClickable = true
        }.start()
    }

    private fun confirmCharacter(avatarView: ImageView, dimBg: View, card: LinearLayout) {
        val char = detailChar ?: return
        chosenCharacter = char
        sound.play(SoundFx.Type.CLICK)

        // Randomly assign player color
        val playerColor = if (Math.random() < 0.5) PieceColor.WHITE else PieceColor.BLACK
        val aiColor = if (playerColor == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
        val colorEmoji = if (playerColor == PieceColor.WHITE) "⚪" else "⚫"
        val moveOrder = if (playerColor == PieceColor.WHITE) "你先走" else "AI 先走"

        // Animate avatar flying to the left-side in-game position
        val targetX = dp(42 + 24) - (avatarView.left + dp(60))
        val targetY = dp(58 + 24) - (avatarView.top + dp(60))
        val targetScale = 48f / 120f

        avatarView.animate()
            .translationX(targetX.toFloat())
            .translationY(targetY.toFloat())
            .scaleX(targetScale).scaleY(targetScale)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(0.4f))
            .withEndAction {
                detailOverlay.visibility = View.GONE
                detailOverlay.removeAllViews()
                detailOverlay.isClickable = true
                detailChar = null
                fadeOut(characterOverlay) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("$colorEmoji 执${if (playerColor == PieceColor.WHITE) "白" else "黑"}方")
                        .setMessage("$moveOrder\n对手：${char.name} · ${char.title}")
                        .setCancelable(false)
                        .setPositiveButton("开始对局") { _, _ ->
                            startMatch(ChessRenderer.Mode.VS_AI, aiColor)
                        }
                        .show()
                }
            }.start()

        // Fade out card and dim
        card.animate().alpha(0f).translationY(dp(100).toFloat()).setDuration(300).start()
        dimBg.animate().alpha(0f).setDuration(350).start()
    }

    // ======================== Color selection ========================

    private fun buildColorSelect(): FrameLayout {
        val f = FrameLayout(this); val panel = panel()
        panel.addView(heading("选择你的棋色")); panel.addView(spacer(dp(22)))
        panel.addView(bigChoice("执白 · 先手", "你先走，AI 执黑", "#FAFAFA", "#BDBDBD", Color.parseColor("#212121")) {
            sound.play(SoundFx.Type.CLICK); startMatch(ChessRenderer.Mode.VS_AI, PieceColor.BLACK)
        })
        panel.addView(spacer(dp(14)))
        panel.addView(bigChoice("执黑 · 后手", "AI 先走，你执黑", "#455A64", "#263238") {
            sound.play(SoundFx.Type.CLICK); startMatch(ChessRenderer.Mode.VS_AI, PieceColor.WHITE)
        })
        panel.addView(spacer(dp(18)))
        panel.addView(backLink("← 返回") { sound.play(SoundFx.Type.CLICK); fadeOut(colorOverlay) { fadeIn(characterOverlay) } })
        f.addView(panel, centerPanelParams())
        return f
    }

    // ======================== Victory overlay ========================

    private fun buildVictory(): FrameLayout {
        val f = FrameLayout(this).apply {
            background = ColorDrawable(Color.parseColor("#B8000000")); isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(30), dp(30), dp(26))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                colors = intArrayOf(Color.parseColor("#2A2F3A"), Color.parseColor("#12151D"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }
        panel.addView(TextView(this).apply { text = "👑"; textSize = 54f; gravity = Gravity.CENTER })
        panel.addView(spacer(dp(4)))
        victoryTitle = TextView(this).apply {
            textSize = 34f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setShadowLayer(12f, 0f, 3f, Color.BLACK)
        }; panel.addView(victoryTitle); panel.addView(spacer(dp(8)))
        victorySubtitle = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#FFD54F")); gravity = Gravity.CENTER
        }; panel.addView(victorySubtitle); panel.addView(spacer(dp(26)))

        panel.addView(pillButton("📈 胜率曲线", "#FFA726", "#E65100").apply {
            setOnClickListener { sound.play(SoundFx.Type.CLICK); showCurve() }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(spacer(dp(10)))
        stalemateUndoBtn = pillButton("↩ 回退一步", "#26A69A", "#00695C").apply {
            visibility = View.GONE
            setOnClickListener { sound.play(SoundFx.Type.CLICK); hideVictory(); glView.postUndo() }
        }
        panel.addView(stalemateUndoBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(spacer(dp(10)))
        panel.addView(pillButton("再来一局", "#66BB6A", "#2E7D32").apply {
            setOnClickListener { sound.play(SoundFx.Type.CLICK); hideVictory(); glView.postNewGame() }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(spacer(dp(12)))
        panel.addView(pillButton("返回主页", "#78909C", "#455A64").apply {
            setOnClickListener { sound.play(SoundFx.Type.CLICK); hideVictory(); glView.postExitToHome() }
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        f.addView(panel, FrameLayout.LayoutParams(dp(300), WRAP).apply { gravity = Gravity.CENTER })
        return f
    }

    private fun showVictory(s: GameStatus, turn: PieceColor) {
        when (s) {
            GameStatus.CHECKMATE -> {
                val winner = if (turn == PieceColor.WHITE) "黑方" else "白方"
                victoryTitle.text = "$winner 获胜"; victoryTitle.setTextColor(Color.WHITE)
                victorySubtitle.text = "将死对方的王！"
                stalemateUndoBtn.visibility = View.GONE
            }
            GameStatus.STALEMATE -> {
                victoryTitle.text = "和棋"; victoryTitle.setTextColor(Color.parseColor("#E0E0E0"))
                victorySubtitle.text = "逼和 · 无子可动"
                stalemateUndoBtn.visibility = View.VISIBLE
            }
            else -> return
        }
        if (victoryOverlay.visibility == View.VISIBLE) return
        val panel = victoryOverlay.getChildAt(0)
        victoryOverlay.alpha = 0f; victoryOverlay.visibility = View.VISIBLE
        panel.scaleX = 0.7f; panel.scaleY = 0.7f
        victoryOverlay.animate().alpha(1f).setDuration(280).start()
        panel.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator()).start()
    }

    private fun hideVictory() {
        if (victoryOverlay.visibility != View.VISIBLE) return
        victoryOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            victoryOverlay.visibility = View.GONE
        }.start()
    }

    // ======================== Match start ========================

    private fun startMatch(mode: ChessRenderer.Mode, aiColor: PieceColor) {
        currentMode = mode
        playerIsBlack = mode == ChessRenderer.Mode.VS_AI && aiColor == PieceColor.WHITE
        val current = when {
            modeOverlay.visibility == View.VISIBLE -> modeOverlay
            colorOverlay.visibility == View.VISIBLE -> colorOverlay
            else -> characterOverlay
        }
        fadeOut(current)
        val depth = if (mode == ChessRenderer.Mode.TWO_PLAYER) 3 else chosenCharacter.depth
        // Reset dialogue tracking for the chosen character (AI only).
        if (mode == ChessRenderer.Mode.VS_AI) {
            DialogManager.resetUsed(chosenCharacter.id)
        }
        glView.postBeginGame(mode, aiColor, depth, chosenCharacter.id)
    }

    // ----------------------------------------------------- renderer callbacks

    override fun onStatus(s: GameStatus, turn: PieceColor) = runOnUiThread { updateStatus(s, turn) }

    override fun onGameStarted() = runOnUiThread {
        evalForCurve = emptyList()
        setHudVisible(true)
        if (currentMode == ChessRenderer.Mode.VS_AI) {
            showInGameAvatar(chosenCharacter)
        } else {
            characterAvatar.visibility = View.GONE
            characterNameText.visibility = View.GONE
        }
    }

    override fun onReturnedHome() = runOnUiThread {
        currentMode = null
        evalForCurve = emptyList()
        setHudVisible(false)
        modeOverlay.visibility = View.GONE
        characterOverlay.visibility = View.GONE
        colorOverlay.visibility = View.GONE
        victoryOverlay.visibility = View.GONE
        fadeIn(homeOverlay)
    }

    override fun onSound(type: SoundFx.Type) { sound.play(type) }

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

    override fun onEvalUpdate(blackWinRate: Float) = runOnUiThread {
        val black = blackWinRate.coerceIn(0f, 100f)
        if (black >= 50.5f) {
            winRateText.text = "黑 ${black.toInt()}%"; winRateText.setTextColor(Color.parseColor("#90CAF9"))
        } else if (black <= 49.5f) {
            winRateText.text = "白 ${(100 - black).toInt()}%"; winRateText.setTextColor(Color.parseColor("#FFCC80"))
        } else {
            winRateText.text = "均势"; winRateText.setTextColor(Color.WHITE)
        }
    }

    override fun onEvalHistory(history: List<Float>) = runOnUiThread { evalForCurve = history }

    override fun onAIDialog(text: String, durationMs: Long) = runOnUiThread {
        showDialogBubble(text, durationMs)
    }

    override fun onAIThinking(isThinking: Boolean) = runOnUiThread {}

    override fun onNeedPromotion(apply: (PieceType) -> Unit) = runOnUiThread {
        val labels = arrayOf("后 ♛", "车 ♜", "象 ♝", "马 ♞")
        val types = arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
        AlertDialog.Builder(this).setTitle("升变为").setCancelable(false)
            .setItems(labels) { _, which -> apply(types[which]) }.show()
    }

    private fun updateStatus(s: GameStatus, turn: PieceColor) {
        val side = if (turn == PieceColor.WHITE) "白方" else "黑方"
        status.text = when (s) {
            GameStatus.ONGOING -> "$side 走棋"
            GameStatus.CHECK -> "$side 被将军！"
            GameStatus.CHECKMATE -> { val w = if (turn == PieceColor.WHITE) "黑方" else "白方"; "将死！$w 获胜" }
            GameStatus.STALEMATE -> "和棋（逼和）"
        }
        when (s) { GameStatus.CHECKMATE, GameStatus.STALEMATE -> showVictory(s, turn); else -> hideVictory() }
    }

    // ----------------------------------------------------- lifecycle / helpers

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() {
        super.onPause()
        if (::dialogBubble.isInitialized) dialogBubble.removeCallbacks(dismissBubbleRunnable)
        if (::glView.isInitialized) glView.onPause()
    }

    private fun fadeIn(v: View) {
        v.alpha = 0f; v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(320).start()
    }
    private fun fadeOut(v: View, then: (() -> Unit)? = null) {
        v.animate().alpha(0f).setDuration(240).withEndAction { v.visibility = View.GONE; then?.invoke() }.start()
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(26), dp(26), dp(26), dp(22))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat(); setColor(Color.parseColor("#E61C1F26"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
    }

    private fun centerPanelParams() = FrameLayout.LayoutParams(dp(300), WRAP).apply { gravity = Gravity.CENTER }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text; textSize = 22f; setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, h) }

    private fun backLink(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.parseColor("#B0BEC5"))
        gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { onClick() }
    }

    private fun bigChoice(title: String, subtitle: String, top: String, bottom: String,
                          textColor: Int = Color.WHITE, onClick: () -> Unit): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                colors = intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            isClickable = true; setOnClickListener { onClick() }
        }
        ll.addView(TextView(this).apply {
            text = title; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor); gravity = Gravity.CENTER
        })
        ll.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(textColor); alpha = 0.85f; gravity = Gravity.CENTER
        })
        ll.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        return ll
    }

    private fun pillButton(text: String, top: String, bottom: String): Button {
        return Button(this).apply {
            this.text = text; textSize = 18f; setTextColor(Color.WHITE); isAllCaps = false
            setPadding(dp(36), dp(14), dp(36), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                colors = intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            stateListAnimator = null
        }
    }

    // ----------------------------------------------------- curve chart

    private fun buildCurveOverlay(): FrameLayout {
        val f = FrameLayout(this).apply {
            background = ColorDrawable(Color.parseColor("#CC000000")); isClickable = true
        }
        val curveView = CurveView(this)
        f.addView(curveView, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            setMargins(dp(24), dp(80), dp(24), dp(80))
        })
        val title = TextView(this).apply {
            text = "胜率曲线"; textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        f.addView(title, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(36)
        })
        f.addView(pillButton("✕ 关闭", "#78909C", "#455A64").apply {
            textSize = 14f; setPadding(dp(24), dp(10), dp(24), dp(10)); setOnClickListener { hideCurve() }
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(32)
        })
        return f
    }

    private fun showCurve() {
        if (evalForCurve.size < 2) return
        val cv = curveOverlay.getChildAt(0) as? CurveView ?: return
        cv.setData(evalForCurve, playerIsBlack)
        curveOverlay.alpha = 0f; curveOverlay.visibility = View.VISIBLE
        curveOverlay.animate().alpha(1f).setDuration(260).start()
    }

    private fun hideCurve() {
        if (curveOverlay.visibility != View.VISIBLE) return
        curveOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            curveOverlay.visibility = View.GONE
        }.start()
    }

    /** Custom View that draws the win-rate curve. Player's advantage = top. */
    private class CurveView(context: android.content.Context) : View(context) {
        private var data: List<Float> = emptyList()
        private var flipY: Boolean = false
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1C24"); style = Paint.Style.FILL
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD54F"); style = Paint.Style.STROKE
            strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF"); style = Paint.Style.STROKE
            strokeWidth = 1.5f; pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFFFFF"); textSize = 28f; textAlign = Paint.Align.CENTER
        }
        private val topLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFCC80"); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        }
        private val botLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#90CAF9"); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        }

        fun setData(d: List<Float>, playerBlack: Boolean) {
            data = d; flipY = playerBlack; invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (data.size < 2) return
            val w = width.toFloat(); val h = height.toFloat()
            val padLeft = 60f; val padRight = 30f; val padTop = 50f; val padBot = 50f
            val chartW = w - padLeft - padRight; val chartH = h - padTop - padBot
            canvas.drawRoundRect(8f, 8f, w - 8f, h - 8f, 16f, 16f, bgPaint)
            val midY = padTop + chartH * 0.5f
            canvas.drawLine(padLeft, midY, padLeft + chartW, midY, midPaint)

            // Axis labels: show player's color at the top.
            val topLabel = if (flipY) "黑 100%" else "白 100%"
            val botLabel = if (flipY) "白 100%" else "黑 100%"
            canvas.drawText(topLabel, padLeft - 10f, padTop + 22f, labelPaint)
            canvas.drawText(botLabel, padLeft - 10f, padTop + chartH - 10f, labelPaint)
            canvas.drawText("50%", padLeft - 10f, midY + 8f, labelPaint)

            val path = Path(); val n = data.size
            for (i in 0 until n) {
                val x = padLeft + (i.toFloat() / (n - 1)) * chartW
                val v = if (flipY) 100f - data[i] else data[i]
                val y = padTop + (v / 100f) * chartH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fillPath = Path(path)
            fillPath.lineTo(padLeft + chartW, padTop + chartH)
            fillPath.lineTo(padLeft, padTop + chartH); fillPath.close()
            val gradTop = if (flipY) Color.parseColor("#554242F5") else Color.parseColor("#55B0BEC5")
            val gradMid = Color.parseColor("#5542A5F5")
            val gradBot = if (flipY) Color.parseColor("#55B0BEC5") else Color.parseColor("#554242F5")
            fillPaint.shader = LinearGradient(0f, padTop, 0f, padTop + chartH,
                intArrayOf(gradTop, gradMid, gradBot),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(fillPath, fillPaint); canvas.drawPath(path, linePaint)

            // Advantage labels: show player's advantage at the top.
            val topAdv = if (flipY) "← 黑优" else "← 白优"
            val botAdv = if (flipY) "← 白优" else "← 黑优"
            canvas.drawText(topAdv, padLeft + chartW / 2, padTop - 16f, topLabelPaint)
            canvas.drawText(botAdv, padLeft + chartW / 2, padTop + chartH + 38f, botLabelPaint)

            val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#30FFFFFF"); textSize = 22f; textAlign = Paint.Align.CENTER
            }
            val step = if (n <= 20) 1 else n / 10
            for (i in 0 until n step step) {
                val x = padLeft + (i.toFloat() / (n - 1)) * chartW
                canvas.drawText("${i + 1}", x, padTop + chartH + 34f, stepPaint)
            }
        }
    }

    // ----------------------------------------------------- dialog bubble

    private val dismissBubbleRunnable = Runnable {
        if (!::dialogBubble.isInitialized) return@Runnable
        dialogBubble.animate()
            .alpha(0f).setDuration(500)
            .withEndAction { dialogBubble.visibility = View.GONE }
            .start()
    }

    private fun showDialogBubble(text: String, durationMs: Long) {
        dialogBubble.removeCallbacks(dismissBubbleRunnable)
        dialogBubble.animate().cancel()
        dialogBubble.text = text
        dialogBubble.translationX = -20f
        dialogBubble.alpha = 0f; dialogBubble.visibility = View.VISIBLE
        dialogBubble.animate()
            .translationX(0f).alpha(1f)
            .setDuration(350).setInterpolator(OvershootInterpolator())
            .start()
        dialogBubble.postDelayed(dismissBubbleRunnable, durationMs + 350)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

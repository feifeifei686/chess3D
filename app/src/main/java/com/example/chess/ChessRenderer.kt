package com.example.chess

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 2.0 renderer: 3D pieces & board with Blinn-Phong lighting, an
 * animated camera (home angle <-> top-down, plus a view toggle), soft contact
 * shadows, tap-to-move with smooth move animations, captured pieces that tumble
 * off the board into a messy graveyard, undo, a "pieces return home" animation
 * on exit, per-type identification rings and a pulsing red check warning.
 *
 * All public mutators must be called on the GL thread (via queueEvent).
 */
class ChessRenderer(private val callbacks: Callbacks) : GLSurfaceView.Renderer {

    interface Callbacks {
        fun onStatus(status: GameStatus, turn: PieceColor)
        fun onNeedPromotion(apply: (PieceType) -> Unit)
        fun onGameStarted()
        fun onReturnedHome()
        fun onSound(type: SoundFx.Type)
        fun onMaterial(whiteMinusBlack: Int)
        fun onCanUndo(canUndo: Boolean)
        fun onEvalUpdate(blackWinRate: Float)
        fun onEvalHistory(history: List<Float>)
        fun onAIDialog(text: String, durationMs: Long)
        fun onAIThinking(isThinking: Boolean)
    }

    enum class Mode { TWO_PLAYER, VS_AI }

    var glView: GLSurfaceView? = null

    private enum class Phase { HOME, TO_GAME, PLAYING, TO_HOME }

    private val game = ChessGame()
    private var phase = Phase.HOME

    private var mode = Mode.TWO_PLAYER
    private var aiColor = PieceColor.BLACK
    private var aiDepth = 3
    private var aiCharacterId = "sato"
    private var aiThinking = false

    // --- meshes ---
    private val pieceMeshes = HashMap<PieceType, Mesh>()
    private lateinit var boardBase: Mesh
    private lateinit var tilesLight: Mesh
    private lateinit var tilesDark: Mesh
    private lateinit var table: Mesh
    private lateinit var unitQuad: Mesh
    private lateinit var hand: Mesh

    // --- programs ---
    private var litProgram = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMVP = 0
    private var uModel = 0
    private var uColor = 0
    private var uLightDir = 0
    private var uCamPos = 0
    private var uShininess = 0
    private var uSpec = 0
    private var uAmbient = 0
    private var uAlpha = 0

    private var ovProgram = 0
    private var ovPos = 0
    private var ovMVP = 0
    private var ovColor = 0
    private var ovFeather = 0
    private var ovInner = 0

    // --- matrices ---
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val invVP = FloatArray(16)

    private var width = 1
    private var height = 1

    // --- camera ---
    private val target = floatArrayOf(0f, 0f, 0f)
    private val welcomeEye = floatArrayOf(0f, 9f, 12f)
    private val welcomeUp = floatArrayOf(0f, 1f, 0f)
    private val gameUp = floatArrayOf(0f, 0f, -1f)
    private val gameOverheadEye = floatArrayOf(0f, 22f, 7f)
    private val gameAngledEye = floatArrayOf(0f, 14f, 16f)
    private var gameViewIndex = 0

    private val camEye = floatArrayOf(0f, 9f, 12f)
    private val camUp = floatArrayOf(0f, 1f, 0f)
    private val cFromE = FloatArray(3)
    private val cFromU = FloatArray(3)
    private val cToE = FloatArray(3)
    private val cToU = FloatArray(3)
    private var camActive = false
    private var camStartNs = 0L
    private var camDurNs = 0L
    private var camOnComplete: (() -> Unit)? = null

    private val lightDir = normalize(floatArrayOf(0.45f, 1.0f, 0.35f))

    // --- selection / animation ---
    private var selR = -1
    private var selC = -1
    private var legalMoves: List<Move> = emptyList()
    private var busy = false

    private class Mover(
        val type: PieceType, val color: PieceColor,
        val fromX: Float, val fromZ: Float,
        val toX: Float, val toZ: Float,
        val skipR: Int, val skipC: Int,
        val hop: Float
    )

    private class MoveAnim(
        val movers: List<Mover>,
        val startNs: Long, val durNs: Long
    )

    /** A hand that swoops down, grips the moving piece, carries it, then lifts away. */
    private class HandAnim(
        val fromX: Float, val fromZ: Float,
        val toX: Float, val toZ: Float,
        val gripY: Float, val hop: Float,
        val startNs: Long, val durNs: Long
    )

    /** A captured (or homing) piece in flight: travels along an arc while tumbling. */
    private class Flyer(
        val type: PieceType, val color: PieceColor,
        val fromX: Float, val fromY: Float, val fromZ: Float,
        val fromRotY: Float, val fromRotZ: Float,
        val toX: Float, val toY: Float, val toZ: Float,
        val toRotY: Float, val toRotZ: Float,
        val hop: Float,
        val startNs: Long, val durNs: Long,
        val skipR: Int, val skipC: Int,
        val reveal: RestingPiece?,   // becomes visible on arrival (capture out)
        val remove: RestingPiece?    // removed from graveyard on arrival (undo back)
    )

    /** A captured piece lying messily off the board. */
    private class RestingPiece(
        val type: PieceType, val color: PieceColor,
        val x: Float, val y: Float, val z: Float,
        val rotY: Float, val rotZ: Float,
        var visible: Boolean = false
    )

    /** Enough of an applied move to animate its reversal. */
    private class LoggedMove(
        val move: Move,
        val captured: RestingPiece?,
        val capR: Int, val capC: Int
    )

    private var anim: MoveAnim? = null
    private var handAnim: HandAnim? = null
    private val flyers = ArrayList<Flyer>()
    private val graveyard = ArrayList<RestingPiece>()
    private val moveLog = ArrayList<LoggedMove>()
    private val animQueue = ArrayDeque<() -> Unit>()

    private var homing = false
    private val homers = ArrayList<Flyer>()

    // Win-rate history (Black's perspective) for the post-game curve.
    private val evalHistory = ArrayList<Float>()
    private var evalPending = false

    // --- "who is checking you" shake reminder ---
    private val shakeSquares = ArrayList<Pair<Int, Int>>()
    private var shakeStartNs = 0L

    // --- check-escape hint: after several failed attempts, flash the pieces
    //     that actually have a legal (check-resolving) move ---
    private var checkTapCount = 0
    private var saviorSquares: List<Pair<Int, Int>> = emptyList()

    // --- best-move hint (the bottom-left "提示" button) ---
    private var hintThinking = false
    private var hintFromR = -1; private var hintFromC = -1
    private var hintToR = -1;   private var hintToC = -1
    private var hintStartNs = 0L
    private var hintComputeStartNs = 0L

    // --- dialogue system ---
    private var lastDialogTimeNs = 0L
    private var idleDialogCount = 0
    private var idleCheckReady = false
    private val IDLE_DIALOG_FIRST_NS = 10_000_000_000L   // first idle at 10 s
    private val IDLE_DIALOG_REPEAT_NS = 15_000_000_000L  // subsequent idle at 15 s
    private val MIN_DIALOG_GAP_NS = 1_500_000_000L       // min gap between any two bubbles

    // --- phase tracking ---
    private var moveCount = 0
    private var lastPhaseTrigger: DialogTrigger? = null

    private var nowNs = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.06f, 0.08f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        litProgram = buildProgram(LIT_VS, LIT_FS)
        aPos = GLES20.glGetAttribLocation(litProgram, "aPos")
        aNormal = GLES20.glGetAttribLocation(litProgram, "aNormal")
        uMVP = GLES20.glGetUniformLocation(litProgram, "uMVP")
        uModel = GLES20.glGetUniformLocation(litProgram, "uModel")
        uColor = GLES20.glGetUniformLocation(litProgram, "uColor")
        uLightDir = GLES20.glGetUniformLocation(litProgram, "uLightDir")
        uCamPos = GLES20.glGetUniformLocation(litProgram, "uCamPos")
        uShininess = GLES20.glGetUniformLocation(litProgram, "uShininess")
        uSpec = GLES20.glGetUniformLocation(litProgram, "uSpec")
        uAmbient = GLES20.glGetUniformLocation(litProgram, "uAmbient")
        uAlpha = GLES20.glGetUniformLocation(litProgram, "uAlpha")

        ovProgram = buildProgram(OV_VS, OV_FS)
        ovPos = GLES20.glGetAttribLocation(ovProgram, "aPos")
        ovMVP = GLES20.glGetUniformLocation(ovProgram, "uMVP")
        ovColor = GLES20.glGetUniformLocation(ovProgram, "uColor")
        ovFeather = GLES20.glGetUniformLocation(ovProgram, "uFeather")
        ovInner = GLES20.glGetUniformLocation(ovProgram, "uInner")

        for (t in PieceType.values()) pieceMeshes[t] = ChessModels.buildPiece(t)
        boardBase = ChessModels.buildBoardBase()
        tilesLight = ChessModels.buildTiles(true)
        tilesDark = ChessModels.buildTiles(false)
        table = ChessModels.buildTable()
        unitQuad = ChessModels.buildUnitQuad()
        hand = ChessModels.buildHand()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        val aspect = w.toFloat() / h.toFloat()
        val fovy = 50f
        Matrix.perspectiveM(proj, 0, fovy, aspect, 0.5f, 200f)

        val tanHalf = Math.tan(Math.toRadians(fovy / 2.0)).toFloat()
        val fitHalf = 5.1f
        val h2 = (fitHalf / (aspect * tanHalf)).coerceAtLeast(12f)

        // Near-overhead (slight tilt) and a lower 3/4 angle for the view toggle.
        gameOverheadEye[1] = h2; gameOverheadEye[2] = h2 * 0.32f
        gameAngledEye[1] = h2 * 0.62f; gameAngledEye[2] = h2 * 0.74f
        // Home: board on a table, dramatic 3/4 angle.
        welcomeEye[1] = h2 * 0.42f; welcomeEye[2] = h2 * 0.54f

        if (phase == Phase.HOME && !camActive) { copy(camEye, welcomeEye); copy(camUp, welcomeUp) }
    }

    override fun onDrawFrame(gl: GL10?) = safely {
        nowNs = System.nanoTime()
        // Safety: if a hint computation hangs, reset the flag after 4 seconds.
        if (hintThinking && hintComputeStartNs > 0 && (nowNs - hintComputeStartNs) > 4_000_000_000L) {
            hintThinking = false; hintComputeStartNs = 0L
        }
        updateCamera()
        updateAnim()
        updateFlyers()
        checkIdleDialog()

        Matrix.setLookAtM(view, 0, camEye[0], camEye[1], camEye[2], target[0], target[1], target[2], camUp[0], camUp[1], camUp[2])
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(litProgram)
        GLES20.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2])
        GLES20.glUniform3f(uCamPos, camEye[0], camEye[1], camEye[2])

        drawLit(table, identityAt(), 0.16f, 0.13f, 0.11f, 4f, 0.05f, 0.45f, 1f)
        drawLit(boardBase, identityAt(), 0.32f, 0.20f, 0.11f, 8f, 0.10f, 0.40f, 1f)
        drawLit(tilesLight, identityAt(), 0.85f, 0.72f, 0.52f, 6f, 0.08f, 0.42f, 1f)
        drawLit(tilesDark, identityAt(), 0.43f, 0.29f, 0.18f, 6f, 0.08f, 0.40f, 1f)

        drawPieces()
        drawHand()

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        drawShadows()
        drawHighlights()
        drawHints()
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---------------------------------------------------------------- drawing

    private fun drawPieces() {
        if (homing) {
            for (f in homers) drawFlyer(f)
            return
        }

        val a = anim
        val checkSq = checkKingSquare()
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (a != null && a.movers.any { it.skipR == r && it.skipC == c }) continue
                if (flyers.any { it.skipR == r && it.skipC == c }) continue
                val p = game.board[r][c] ?: continue
                val red = if (checkSq != null && checkSq.first == r && checkSq.second == c) checkPulse() else 0f
                val glow = pieceGlow(r, c)
                val sx = shakeAmount(r, c)
                if (sx != 0f) {
                    drawPieceAtFull(
                        p.type, p.color,
                        ChessModels.squareCenterX(c) + sx, 0f, ChessModels.squareCenterZ(r),
                        0f, sx * 80f, 1f, red, glow
                    )
                } else {
                    drawPieceAt(p.type, p.color, ChessModels.squareCenterX(c), ChessModels.squareCenterZ(r), red, glow)
                }
            }
        }

        // Captured pieces resting off-board.
        for (rp in graveyard) if (rp.visible) drawRestingPiece(rp)

        // The sliding piece(s) of the current move.
        if (a != null) {
            val t = ((nowNs - a.startNs).toFloat() / a.durNs).coerceIn(0f, 1f)
            val s = smooth(t)
            for (m in a.movers) {
                val x = m.fromX + (m.toX - m.fromX) * s
                val z = m.fromZ + (m.toZ - m.fromZ) * s
                val y = sin(PI * t).toFloat() * m.hop
                drawPieceAtFull(m.type, m.color, x, y, z, 0f, 0f, 1f, 0f)
            }
        }

        // Pieces tumbling to / from the graveyard.
        for (f in flyers) drawFlyer(f)
    }

    /** The grabbing hand: swoops down onto the piece, carries it, lifts and fades. */
    private fun drawHand() {
        val h = handAnim ?: return
        if (homing || phase != Phase.PLAYING) { handAnim = null; return }
        val t = (nowNs - h.startNs).toFloat() / h.durNs
        if (t >= 1f) { handAnim = null; return }
        val tc = t.coerceIn(0f, 1f)
        val s = smooth(tc)
        val x = h.fromX + (h.toX - h.fromX) * s
        val z = h.fromZ + (h.toZ - h.fromZ) * s
        val hopY = sin(PI * tc).toFloat() * h.hop
        // Reach down over the first stretch, lift away over the last.
        val lift = when {
            tc < 0.22f -> (1f - smooth(tc / 0.22f)) * 1.5f
            tc > 0.80f -> smooth((tc - 0.80f) / 0.20f) * 1.3f
            else -> 0f
        }
        val alpha = when {
            tc < 0.10f -> tc / 0.10f
            tc > 0.88f -> (1f - tc) / 0.12f
            else -> 1f
        }.coerceIn(0f, 1f)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, h.gripY + hopY + lift, z)
        Matrix.rotateM(model, 0, 15f, 1f, 0f, 0f)   // forearm leans toward the near player

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawLit(hand, model, 0.86f, 0.66f, 0.52f, 12f, 0.18f, 0.42f, alpha)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawFlyer(f: Flyer) {
        val t = ((nowNs - f.startNs).toFloat() / f.durNs).coerceIn(0f, 1f)
        val s = smooth(t)
        val x = f.fromX + (f.toX - f.fromX) * s
        val z = f.fromZ + (f.toZ - f.fromZ) * s
        val y = f.fromY + (f.toY - f.fromY) * s + sin(PI * t).toFloat() * f.hop
        val rotY = f.fromRotY + (f.toRotY - f.fromRotY) * s
        val rotZ = f.fromRotZ + (f.toRotZ - f.fromRotZ) * s
        drawPieceAtFull(f.type, f.color, x, y, z, rotY, rotZ, 1f, 0f)
    }

    private fun drawRestingPiece(rp: RestingPiece) {
        drawPieceAtFull(rp.type, rp.color, rp.x, rp.y, rp.z, rp.rotY, rp.rotZ, 1f, 0f)
    }

    private fun drawPieceAt(type: PieceType, color: PieceColor, x: Float, z: Float, red: Float, glow: Float = 0f) {
        drawPieceAtFull(type, color, x, 0f, z, 0f, 0f, 1f, red, glow)
    }

    /** Full piece draw with translation, Y/Z rotation, alpha, an optional red check tint and a cyan hint glow. */
    private fun drawPieceAtFull(
        type: PieceType, color: PieceColor,
        x: Float, y: Float, z: Float,
        rotYDeg: Float, rotZDeg: Float,
        alpha: Float, red: Float, glow: Float = 0f
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        // Knights' horse heads face the enemy: white looks toward -z, black +z.
        val baseYaw = if (type == PieceType.KNIGHT && color == PieceColor.BLACK) 180f else 0f
        val yaw = rotYDeg + baseYaw
        if (yaw != 0f) Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        if (rotZDeg != 0f) Matrix.rotateM(model, 0, rotZDeg, 0f, 0f, 1f)

        var br: Float; var bg: Float; var bb: Float
        val shin: Float; val spec: Float; val amb: Float
        if (color == PieceColor.WHITE) {
            br = 0.91f; bg = 0.89f; bb = 0.83f; shin = 26f; spec = 0.45f; amb = 0.34f
        } else {
            br = 0.10f; bg = 0.11f; bb = 0.13f; shin = 44f; spec = 0.55f; amb = 0.30f
        }
        if (red > 0f) {
            br = br + (1.0f - br) * red
            bg = bg * (1f - red)
            bb = bb * (1f - red)
        }
        if (glow > 0f) {
            br = br + (0.45f - br) * glow
            bg = bg + (1.00f - bg) * glow
            bb = bb + (1.00f - bb) * glow
        }
        drawLit(pieceMeshes[type]!!, model, br, bg, bb, shin, spec, amb, alpha)
    }

    private fun drawLit(
        mesh: Mesh, m: FloatArray,
        r: Float, g: Float, b: Float,
        shininess: Float, spec: Float, ambient: Float, alpha: Float
    ) {
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glUniform3f(uColor, r, g, b)
        GLES20.glUniform1f(uShininess, shininess)
        GLES20.glUniform1f(uSpec, spec)
        GLES20.glUniform1f(uAmbient, ambient)
        GLES20.glUniform1f(uAlpha, alpha)
        mesh.draw(aPos, aNormal)
    }

    private fun drawShadows() {
        GLES20.glUseProgram(ovProgram)
        GLES20.glUniform4f(ovColor, 0f, 0f, 0f, 0.34f)
        GLES20.glUniform1f(ovFeather, 0.15f)
        GLES20.glUniform1f(ovInner, 0f)
        val ox = -lightDir[0] / lightDir[1] * 0.18f
        val oz = -lightDir[2] / lightDir[1] * 0.18f

        if (homing) {
            for (f in homers) shadowForFlyer(f, ox, oz)
            return
        }

        val a = anim
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (a != null && a.movers.any { it.skipR == r && it.skipC == c }) continue
                if (flyers.any { it.skipR == r && it.skipC == c }) continue
                val p = game.board[r][c] ?: continue
                val rad = 0.30f + ChessModels.pieceHeight(p.type) * 0.06f
                drawOverlay(ChessModels.squareCenterX(c) + ox, ChessModels.squareCenterZ(r) + oz, rad)
            }
        }
        for (rp in graveyard) if (rp.visible) {
            drawOverlay(rp.x + ox, rp.z + oz, 0.34f)
        }
        if (a != null) {
            val t = ((nowNs - a.startNs).toFloat() / a.durNs).coerceIn(0f, 1f)
            val s = smooth(t)
            for (m in a.movers) {
                val x = m.fromX + (m.toX - m.fromX) * s
                val z = m.fromZ + (m.toZ - m.fromZ) * s
                val rad = 0.30f + ChessModels.pieceHeight(m.type) * 0.06f
                drawOverlay(x + ox, z + oz, rad)
            }
        }
        for (f in flyers) shadowForFlyer(f, ox, oz)
    }

    private fun shadowForFlyer(f: Flyer, ox: Float, oz: Float) {
        val t = ((nowNs - f.startNs).toFloat() / f.durNs).coerceIn(0f, 1f)
        val s = smooth(t)
        val x = f.fromX + (f.toX - f.fromX) * s
        val z = f.fromZ + (f.toZ - f.fromZ) * s
        drawOverlay(x + ox, z + oz, 0.30f)
    }

    /** Lateral wobble (board units) for a checker square that's shaking; 0 when idle. */
    private fun shakeAmount(r: Int, c: Int): Float {
        if (shakeSquares.isEmpty()) return 0f
        if (shakeSquares.none { it.first == r && it.second == c }) return 0f
        val t = (nowNs - shakeStartNs).toFloat() / SHAKE_DUR_NS
        if (t < 0f || t >= 1f) return 0f
        val env = 1f - t                       // decay to rest
        return (sin(PI * 4.0 * t) * 0.14 * env).toFloat()   // two full wobbles
    }

    private fun startCheckShake() {
        val checkers = game.checkingPieces()
        if (checkers.isEmpty()) return
        shakeSquares.clear()
        shakeSquares.addAll(checkers)
        shakeStartNs = System.nanoTime()
        callbacks.onSound(SoundFx.Type.CHECK)
    }

    private fun drawHighlights() {
        if (phase != Phase.PLAYING || anim != null || selR < 0) return
        GLES20.glUseProgram(ovProgram)
        GLES20.glUniform1f(ovInner, 0f)
        GLES20.glUniform4f(ovColor, 0.30f, 0.85f, 0.40f, 0.40f)
        GLES20.glUniform1f(ovFeather, 0.55f)
        drawOverlay(ChessModels.squareCenterX(selC), ChessModels.squareCenterZ(selR), 0.5f)
        GLES20.glUniform4f(ovColor, 0.25f, 0.80f, 0.38f, 0.55f)
        GLES20.glUniform1f(ovFeather, 0.0f)
        for (mv in legalMoves) {
            val capture = game.board[mv.toR][mv.toC] != null || mv.isEnPassant
            drawOverlay(
                ChessModels.squareCenterX(mv.toC),
                ChessModels.squareCenterZ(mv.toR),
                if (capture) 0.45f else 0.18f
            )
        }
    }

    /** Blinking rings for the check-escape savior pieces and the best-move hint. */
    private fun drawHints() {
        if (phase != Phase.PLAYING) return
        // Expire the best-move hint once its time is up.
        if (hintFromR >= 0 && nowNs - hintStartNs > HINT_DUR_NS) { hintFromR = -1; hintToR = -1 }
        if (saviorSquares.isEmpty() && hintFromR < 0) return

        GLES20.glUseProgram(ovProgram)
        val pulse = (0.5 + 0.5 * sin(nowNs * 1e-9 * 6.0)).toFloat()

        // Pieces that can get the king out of check — cyan.
        for ((r, c) in saviorSquares) {
            drawPulseRing(ChessModels.squareCenterX(c), ChessModels.squareCenterZ(r),
                0.52f, 0.20f, 0.95f, 1.00f, pulse)
        }

        // Best-move suggestion — gold ring on the piece, gold dot on its target.
        if (hintFromR >= 0) {
            drawPulseRing(ChessModels.squareCenterX(hintFromC), ChessModels.squareCenterZ(hintFromR),
                0.52f, 1.00f, 0.84f, 0.16f, pulse)
            GLES20.glUniform4f(ovColor, 1.00f, 0.84f, 0.16f, 0.30f + 0.50f * pulse)
            GLES20.glUniform1f(ovFeather, 0.45f)
            GLES20.glUniform1f(ovInner, 0f)
            drawOverlay(ChessModels.squareCenterX(hintToC), ChessModels.squareCenterZ(hintToR), 0.34f)
        }
        GLES20.glUniform1f(ovInner, 0f)
    }

    private fun drawPulseRing(x: Float, z: Float, rad: Float, r: Float, g: Float, b: Float, pulse: Float) {
        GLES20.glUniform4f(ovColor, r, g, b, 0.25f + 0.65f * pulse)
        GLES20.glUniform1f(ovFeather, 0.04f)
        GLES20.glUniform1f(ovInner, 0.58f)
        drawOverlay(x, z, rad)
    }

    private fun drawOverlay(x: Float, z: Float, radius: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, 0.02f, z)
        Matrix.scaleM(model, 0, radius, 1f, radius)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(ovMVP, 1, false, mvp, 0)
        unitQuad.draw(ovPos, -1)
    }

    private fun identityAt(): FloatArray {
        Matrix.setIdentityM(model, 0)
        return model
    }

    private fun checkPulse(): Float {
        val s = (0.5 + 0.5 * sin(nowNs * 1e-9 * 7.0)).toFloat()
        return 0.25f + 0.75f * s
    }

    /** Pulsing cyan glow for a hinted piece (check-escape savior or best-move source). */
    private fun pieceGlow(r: Int, c: Int): Float {
        val active = saviorSquares.any { it.first == r && it.second == c } ||
            (hintFromR == r && hintFromC == c && nowNs - hintStartNs <= HINT_DUR_NS)
        if (!active) return 0f
        return 0.60f * (0.5 + 0.5 * sin(nowNs * 1e-9 * 6.0)).toFloat()
    }

    private fun checkKingSquare(): Pair<Int, Int>? {
        if (game.status != GameStatus.CHECK && game.status != GameStatus.CHECKMATE) return null
        val color = game.turn
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = game.board[r][c]
            if (p != null && p.color == color && p.type == PieceType.KING) return Pair(r, c)
        }
        return null
    }

    // ---------------------------------------------------------------- camera

    private fun updateCamera() {
        if (camActive) {
            val t = ((nowNs - camStartNs).toFloat() / camDurNs).coerceIn(0f, 1f)
            val s = smoother(t)
            for (i in 0 until 3) {
                camEye[i] = cFromE[i] + (cToE[i] - cFromE[i]) * s
                camUp[i] = cFromU[i] + (cToU[i] - cFromU[i]) * s
            }
            normalizeInto(camUp)
            if (t >= 1f) {
                camActive = false
                copy(camEye, cToE); copy(camUp, cToU)
                val cb = camOnComplete
                camOnComplete = null
                cb?.invoke()
            }
        } else if (phase == Phase.HOME) {
            copy(camEye, welcomeEye); copy(camUp, welcomeUp)
        }
    }

    private fun startCam(toE: FloatArray, toU: FloatArray, durNs: Long, onComplete: (() -> Unit)?) {
        copy(cFromE, camEye); copy(cFromU, camUp)
        copy(cToE, toE); copy(cToU, toU)
        camStartNs = System.nanoTime(); camDurNs = durNs
        camActive = true; camOnComplete = onComplete
    }

    private fun updateAnim() {
        val a = anim ?: return
        val t = (nowNs - a.startNs).toFloat() / a.durNs
        if (t >= 1f) {
            anim = null
            if (animQueue.isNotEmpty()) {
                animQueue.removeFirst().invoke()
                return
            }
            when (game.status) {
                GameStatus.CHECK -> callbacks.onSound(SoundFx.Type.CHECK)
                GameStatus.CHECKMATE, GameStatus.STALEMATE -> {
                    callbacks.onSound(SoundFx.Type.GAMEOVER)
                    callbacks.onEvalHistory(evalHistory.toList())
                }
                else -> {}
            }
            callbacks.onStatus(game.status, game.turn)
            emitMeta()

            // --- dialogue after a move completes ---
            val lastLog = moveLog.lastOrNull()
            val char = if (mode == Mode.VS_AI) Characters.byId(aiCharacterId) else null

            if (char != null && lastLog != null && canShowDialog()) {
                if (game.turn != aiColor) {
                    // AI just moved — show move-triggered dialogue
                    val trigger = triggerFromLog(lastLog)
                    val desc = DialogManager.moveDesc(lastLog.move, game)
                    showDialog(DialogManager.generate(char, trigger, desc))
                } else if (animQueue.isEmpty()) {
                    // Player just moved — react to capture / check
                    val trigger = triggerFromLog(lastLog)
                    if (trigger == DialogTrigger.MOVE_CAPTURE || trigger == DialogTrigger.MOVE_CHECK) {
                        val react = if (trigger == DialogTrigger.MOVE_CAPTURE)
                            DialogTrigger.REACT_CAPTURE else DialogTrigger.REACT_CHECK
                        val desc = DialogManager.moveDesc(lastLog.move, game)
                        showDialog(DialogManager.generate(char, react, desc))
                    }
                }
            }

            // Phase-transition dialogue
            if (char != null && canShowDialog()) checkPhaseDialog(char)

            maybeTriggerAI()
        }
    }

    private fun updateFlyers() {
        if (flyers.isEmpty()) return
        val it = flyers.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (nowNs - f.startNs).toFloat() / f.durNs
            if (t >= 1f) {
                f.reveal?.visible = true
                f.remove?.let { rp -> graveyard.remove(rp) }
                it.remove()
            }
        }
    }

    // ---------------------------------------------------------------- control

    fun beginGame(newMode: Mode, aiSide: PieceColor, depth: Int, characterId: String = "sato") {
        if (phase != Phase.HOME) return
        mode = newMode
        aiColor = aiSide
        aiDepth = depth
        aiCharacterId = characterId
        game.reset()
        clearTransient()
        graveyard.clear()
        moveLog.clear()
        evalHistory.clear()
        gameViewIndex = 0
        phase = Phase.TO_GAME
        startCam(gameOverheadEye, gameUp, 1_700_000_000L) {
            phase = Phase.PLAYING
            moveCount = 0
            lastPhaseTrigger = null
            callbacks.onGameStarted()
            callbacks.onStatus(game.status, game.turn)
            emitMeta()
            // Greeting dialog from the AI character
            if (mode == Mode.VS_AI) {
                val char = Characters.byId(aiCharacterId)
                if (char != null) {
                    callbacks.onAIDialog(DialogManager.generate(char, DialogTrigger.GREETING, null), 3000)
                    lastDialogTimeNs = nowNs
                }
            }
            maybeTriggerAI()
        }
    }

    fun exitToHome() {
        if (phase != Phase.PLAYING || homing) return
        phase = Phase.TO_HOME
        clearTransient()
        startHomingAnimation()
        callbacks.onCanUndo(false)
        startCam(welcomeEye, welcomeUp, 1_500_000_000L) {
            phase = Phase.HOME
            game.reset()
            graveyard.clear()
            moveLog.clear()
            homing = false
            homers.clear()
            aiThinking = false
            evalHistory.clear()
            callbacks.onReturnedHome()
        }
    }

    fun toggleView() {
        if (phase != Phase.PLAYING || camActive) return
        gameViewIndex = 1 - gameViewIndex
        val tgt = if (gameViewIndex == 0) gameOverheadEye else gameAngledEye
        startCam(tgt, gameUp, 600_000_000L, null)
    }

    fun newGame() {
        if (phase != Phase.PLAYING && phase != Phase.TO_GAME) return
        if (homing) return
        clearTransient()
        startHomingAnimation()
        callbacks.onCanUndo(false)
        // Use a still camera as a timer for the homing animation;
        // when it fires the game resets and a fresh match begins.
        startCam(camEye.clone(), camUp.clone(), 1_200_000_000L) {
            game.reset()
            graveyard.clear()
            moveLog.clear()
            homing = false
            homers.clear()
            aiThinking = false
            evalHistory.clear()
            moveCount = 0
            lastPhaseTrigger = null
            callbacks.onStatus(game.status, game.turn)
            emitMeta()
            maybeTriggerAI()
        }
    }

    fun handleTap(sx: Float, sy: Float) = safely {
        if (phase != Phase.PLAYING || anim != null || busy || aiThinking || flyers.isNotEmpty() || homing) return@safely
        if (mode == Mode.VS_AI && game.turn == aiColor) return@safely
        val inCheck = game.status == GameStatus.CHECK
        val sq = pick(sx, sy)
        if (sq == null) {
            selR = -1; selC = -1; legalMoves = emptyList()
            if (inCheck) registerCheckAttempt()
            return@safely
        }
        val (r, c) = sq

        if (selR >= 0) {
            val cands = legalMoves.filter { it.toR == r && it.toC == c }
            if (cands.isNotEmpty()) { beginMove(cands); return@safely }
        }
        val p = game.board[r][c]
        if (p != null && p.color == game.turn) {
            selR = r; selC = c
            legalMoves = game.legalMovesFrom(r, c)
            // Still in check and fiddling with a piece? Remind them who's attacking.
            if (inCheck) { startCheckShake(); registerCheckAttempt() }
        } else {
            selR = -1; selC = -1
            legalMoves = emptyList()
            if (inCheck) registerCheckAttempt()
        }
    }

    /**
     * Each tap that fails to escape check counts as an attempt; after more than
     * five, light up every friendly piece that actually has a legal move (any
     * legal move while in check necessarily resolves the check).
     */
    private fun registerCheckAttempt() {
        checkTapCount++
        if (checkTapCount > 5 && saviorSquares.isEmpty()) {
            val res = ArrayList<Pair<Int, Int>>()
            for (r in 0 until 8) for (c in 0 until 8) {
                val pc = game.board[r][c] ?: continue
                if (pc.color != game.turn) continue
                if (game.legalMovesFrom(r, c).isNotEmpty()) res.add(Pair(r, c))
            }
            saviorSquares = res
        }
    }

    private fun beginMove(cands: List<Move>) {
        val needsPromotion = cands.size > 1 && cands.any { it.promotion != null }
        if (needsPromotion) {
            busy = true
            callbacks.onNeedPromotion { type ->
                glView?.queueEvent {
                    val chosen = cands.firstOrNull { it.promotion == type }
                        ?: cands.first { it.promotion == PieceType.QUEEN }
                    applyMove(chosen)
                }
            }
            return
        }
        applyMove(cands.first())
    }

    private fun applyMove(move: Move) = safely {
        busy = false
        shakeSquares.clear()
        clearHints()
        val capR: Int; val capC: Int
        if (move.isEnPassant) { capR = move.fromR; capC = move.toC }
        else { capR = move.toR; capC = move.toC }
        val captured = game.board[capR][capC]

        val moving = game.board[move.fromR][move.fromC] ?: return@safely
        val movers = ArrayList<Mover>()
        val hop = if (moving.type == PieceType.KNIGHT) 0.55f else 0.22f
        movers.add(
            Mover(
                moving.type, moving.color,
                ChessModels.squareCenterX(move.fromC), ChessModels.squareCenterZ(move.fromR),
                ChessModels.squareCenterX(move.toC), ChessModels.squareCenterZ(move.toR),
                move.toR, move.toC, hop
            )
        )
        if (move.isCastle) {
            val rookFromC = if (move.toC > move.fromC) 7 else 0
            val rookToC = if (move.toC > move.fromC) 5 else 3
            movers.add(
                Mover(
                    PieceType.ROOK, moving.color,
                    ChessModels.squareCenterX(rookFromC), ChessModels.squareCenterZ(move.fromR),
                    ChessModels.squareCenterX(rookToC), ChessModels.squareCenterZ(move.fromR),
                    move.fromR, rookToC, 0.22f
                )
            )
        }

        callbacks.onSound(if (captured != null) SoundFx.Type.CAPTURE else SoundFx.Type.MOVE)
        game.makeMove(move)

        // Compute win rate in the background after the board changes.
        if (!evalPending) {
            evalPending = true
            val snap = game.snapshot()
            Thread {
                val cp = ChessAI.quickEvalBlack(snap)
                val wr = ChessAI.evalToWinRate(cp)
                glView?.queueEvent {
                    evalPending = false
                    if (phase == Phase.PLAYING) {
                        evalHistory.add(wr)
                        callbacks.onEvalUpdate(wr)
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        // Send the captured piece tumbling off the board into the graveyard.
        var rest: RestingPiece? = null
        if (captured != null) {
            rest = newGraveyardSlot(captured.type, captured.color)
            graveyard.add(rest)
            flyers.add(
                Flyer(
                    captured.type, captured.color,
                    ChessModels.squareCenterX(capC), 0f, ChessModels.squareCenterZ(capR),
                    0f, 0f,
                    rest.x, rest.y, rest.z, rest.rotY, rest.rotZ,
                    1.7f, System.nanoTime(), 640_000_000L,
                    -1, -1, rest, null
                )
            )
        }

        moveLog.add(LoggedMove(move, rest, capR, capC))
        val startNs = System.nanoTime()
        val durNs = 520_000_000L
        anim = MoveAnim(movers, startNs, durNs)
        startHand(movers.first(), startNs, durNs)
        selR = -1; selC = -1
        legalMoves = emptyList()
        // Reset idle dialog timer for the opponent's (AI's) next turn.
        lastDialogTimeNs = System.nanoTime()
        idleDialogCount = 0
        idleCheckReady = true
        moveCount++
    }

    /** Park a grabbing hand over the primary mover for the duration of its slide. */
    private fun startHand(m: Mover, startNs: Long, durNs: Long) {
        handAnim = HandAnim(
            m.fromX, m.fromZ, m.toX, m.toZ,
            ChessModels.pieceHeight(m.type) * 0.80f, m.hop,
            startNs, durNs
        )
    }

    fun requestUndo() = safely {
        if (phase != Phase.PLAYING || anim != null || busy || aiThinking || flyers.isNotEmpty() || homing) return@safely
        if (animQueue.isNotEmpty()) return@safely
        val plies = if (mode == Mode.VS_AI) 2 else 1
        if (moveLog.size < plies) return@safely
        if (mode == Mode.VS_AI && game.turn == aiColor) return@safely

        selR = -1; selC = -1; legalMoves = emptyList()
        startUndoPly()
        if (plies == 2) animQueue.add { startUndoPly() }
    }

    private fun startUndoPly() {
        val log = moveLog.removeLastOrNull() ?: return
        val mv = log.move
        game.undo()

        val movers = ArrayList<Mover>()
        val movedType = game.board[mv.fromR][mv.fromC]?.type ?: PieceType.PAWN
        val movedColor = game.board[mv.fromR][mv.fromC]?.color ?: PieceColor.WHITE
        val hop = if (movedType == PieceType.KNIGHT) 0.55f else 0.22f
        movers.add(
            Mover(
                movedType, movedColor,
                ChessModels.squareCenterX(mv.toC), ChessModels.squareCenterZ(mv.toR),
                ChessModels.squareCenterX(mv.fromC), ChessModels.squareCenterZ(mv.fromR),
                mv.fromR, mv.fromC, hop
            )
        )
        if (mv.isCastle) {
            val rookFromC = if (mv.toC > mv.fromC) 7 else 0
            val rookToC = if (mv.toC > mv.fromC) 5 else 3
            movers.add(
                Mover(
                    PieceType.ROOK, movedColor,
                    ChessModels.squareCenterX(rookToC), ChessModels.squareCenterZ(mv.fromR),
                    ChessModels.squareCenterX(rookFromC), ChessModels.squareCenterZ(mv.fromR),
                    mv.fromR, rookFromC, 0.22f
                )
            )
        }

        // Fly the captured piece back from the graveyard onto its square.
        val rest = log.captured
        if (rest != null) {
            rest.visible = false   // hide the static copy; the flyer draws it in transit
            flyers.add(
                Flyer(
                    rest.type, rest.color,
                    rest.x, rest.y, rest.z, rest.rotY, rest.rotZ,
                    ChessModels.squareCenterX(log.capC), 0f, ChessModels.squareCenterZ(log.capR), 0f, 0f,
                    1.4f, System.nanoTime(), 520_000_000L,
                    log.capR, log.capC, null, rest
                )
            )
        }

        clearHints()
        callbacks.onSound(SoundFx.Type.MOVE)
        val startNs = System.nanoTime()
        val durNs = 480_000_000L
        anim = MoveAnim(movers, startNs, durNs)
        startHand(movers.first(), startNs, durNs)
    }

    /** Fire an idle dialog when the player is thinking for too long. Repeats. */
    private fun checkIdleDialog() {
        if (mode != Mode.VS_AI || phase != Phase.PLAYING || anim != null || homing) return
        if (game.turn == aiColor) return
        if (!idleCheckReady) return
        if (game.status != GameStatus.ONGOING && game.status != GameStatus.CHECK) return

        val interval = if (idleDialogCount == 0) IDLE_DIALOG_FIRST_NS else IDLE_DIALOG_REPEAT_NS
        if (nowNs - lastDialogTimeNs < interval) return
        if (!canShowDialog()) return

        idleDialogCount++
        val char = Characters.byId(aiCharacterId) ?: return
        showDialog(DialogManager.generate(char, DialogTrigger.IDLE, null))
    }

    /** True when enough time has passed since the last dialogue bubble. */
    private fun canShowDialog(): Boolean = nowNs - lastDialogTimeNs >= MIN_DIALOG_GAP_NS

    /** Show a dialogue bubble and reset timing state. */
    private fun showDialog(text: String) {
        callbacks.onAIDialog(text, 3000)
        lastDialogTimeNs = nowNs
        idleDialogCount = 0
    }

    /** Determine what kind of move was just made (using move-log data so we
     *  avoid the post-move board-state ambiguity). */
    private fun triggerFromLog(log: LoggedMove): DialogTrigger = when {
        log.move.isCastle -> DialogTrigger.MOVE_CASTLE
        log.move.isEnPassant || log.captured != null -> DialogTrigger.MOVE_CAPTURE
        log.move.promotion != null -> DialogTrigger.MOVE_PROMOTE
        game.status == GameStatus.CHECK || game.status == GameStatus.CHECKMATE -> DialogTrigger.MOVE_CHECK
        else -> DialogTrigger.MOVE_GENERIC
    }

    /** Fire a phase-transition dialogue once when the move count crosses a threshold. */
    private fun checkPhaseDialog(char: CharacterDef) {
        val phaseTrigger = when {
            moveCount in 4..7 && lastPhaseTrigger != DialogTrigger.PHASE_OPENING ->
                DialogTrigger.PHASE_OPENING
            moveCount in 14..18 && lastPhaseTrigger != DialogTrigger.PHASE_MIDDLEGAME ->
                DialogTrigger.PHASE_MIDDLEGAME
            moveCount in 35..45 && lastPhaseTrigger != DialogTrigger.PHASE_ENDGAME ->
                DialogTrigger.PHASE_ENDGAME
            else -> null
        }
        if (phaseTrigger != null) {
            lastPhaseTrigger = phaseTrigger
            showDialog(DialogManager.generate(char, phaseTrigger, null))
        }
    }

    /** Drop both kinds of on-board hint (check-escape and best-move). */
    private fun clearHints() {
        checkTapCount = 0
        saviorSquares = emptyList()
        hintFromR = -1; hintFromC = -1
        hintToR = -1; hintToC = -1
    }

    /** Compute the strongest move in the background and flash it on the board. */
    fun requestHint() = safely {
        if (phase != Phase.PLAYING || anim != null || busy || aiThinking || hintThinking || homing) return@safely
        if (mode == Mode.VS_AI && game.turn == aiColor) return@safely
        if (game.status != GameStatus.ONGOING && game.status != GameStatus.CHECK) return@safely
        hintThinking = true
        hintComputeStartNs = System.nanoTime()
        // Quick hint search: cap depth at 6 and budget at 2 s so the player
        // isn't left waiting.  The search still sees tactics but returns fast.
        val searchDepth = minOf(maxOf(aiDepth, 4), 6)
        val snapshot = game.snapshot()
        Thread {
            try {
                val mv = ChessAI.bestMove(snapshot, searchDepth, 2000)
                glView?.queueEvent {
                    hintThinking = false
                    hintComputeStartNs = 0L
                    if (mv != null && phase == Phase.PLAYING &&
                        game.board[mv.fromR][mv.fromC]?.color == game.turn
                    ) {
                        hintFromR = mv.fromR; hintFromC = mv.fromC
                        hintToR = mv.toR;     hintToC = mv.toC
                        hintStartNs = System.nanoTime()
                        callbacks.onSound(SoundFx.Type.CLICK)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "hint computation failed", t)
                glView?.queueEvent { hintThinking = false; hintComputeStartNs = 0L }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun maybeTriggerAI() {
        if (mode != Mode.VS_AI || phase != Phase.PLAYING || anim != null || aiThinking || homing) return
        if (game.turn != aiColor) return
        if (game.status != GameStatus.ONGOING && game.status != GameStatus.CHECK) return
        aiThinking = true
        callbacks.onCanUndo(false)
        callbacks.onAIThinking(true)
        // Show a "thinking" dialog bubble.
        val char = Characters.byId(aiCharacterId)
        if (char != null) {
            callbacks.onAIDialog(DialogManager.generate(char, DialogTrigger.THINKING, null), 3000)
        }
        val snapshot = game.snapshot()
        val depth = aiDepth
        Thread {
            val mv = try { ChessAI.bestMove(snapshot, depth, 5000) } catch (t: Throwable) { null }
            glView?.queueEvent {
                aiThinking = false
                callbacks.onAIThinking(false)
                if (mv != null && phase == Phase.PLAYING && anim == null && game.turn == aiColor) {
                    applyMove(mv)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Material balance and whether undo is currently offerable, pushed to the HUD. */
    private fun emitMeta() {
        var bal = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = game.board[r][c] ?: continue
            val v = pieceValue(p.type)
            bal += if (p.color == PieceColor.WHITE) v else -v
        }
        callbacks.onMaterial(bal)
        val plies = if (mode == Mode.VS_AI) 2 else 1
        val canUndo = phase == Phase.PLAYING && moveLog.size >= plies &&
            !(mode == Mode.VS_AI && (game.turn == aiColor || aiThinking))
        callbacks.onCanUndo(canUndo)
    }

    private fun clearTransient() {
        anim = null
        handAnim = null
        hintThinking = false
        clearHints()
        flyers.clear()
        animQueue.clear()
        selR = -1; selC = -1
        legalMoves = emptyList()
        busy = false
        aiThinking = false
        shakeSquares.clear()
    }

    // ----- graveyard placement -----

    private fun newGraveyardSlot(type: PieceType, color: PieceColor): RestingPiece {
        // White losses pile on +x, black losses on -x; rows of eight, columns step outward.
        val side = if (color == PieceColor.WHITE) 1f else -1f
        val n = graveyard.count { it.color == color }
        val row = n % 8
        val col = n / 8
        val jx = (Math.random().toFloat() - 0.5f) * 0.34f
        val jz = (Math.random().toFloat() - 0.5f) * 0.30f
        val x = side * (5.25f + col * 0.95f) + jx
        val z = -3.3f + row * 0.95f + jz
        val rotY = (Math.random().toFloat() * 360f)
        val rotZ = 90f + (Math.random().toFloat() - 0.5f) * 26f   // lying on its side, askew
        val y = 0.17f
        return RestingPiece(type, color, x, y, z, rotY, rotZ)
    }

    // ----- return-home animation -----

    private fun startHomingAnimation() {
        homers.clear()
        // Pool of current visible pieces (board + graveyard) we can fly back home.
        data class Src(val type: PieceType, val color: PieceColor,
                       val x: Float, val y: Float, val z: Float,
                       val rotY: Float, val rotZ: Float, var used: Boolean = false)
        val pool = ArrayList<Src>()
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = game.board[r][c] ?: continue
            pool.add(Src(p.type, p.color, ChessModels.squareCenterX(c), 0f, ChessModels.squareCenterZ(r), 0f, 0f))
        }
        for (rp in graveyard) if (rp.visible) {
            pool.add(Src(rp.type, rp.color, rp.x, rp.y, rp.z, rp.rotY, rp.rotZ))
        }

        val now = System.nanoTime()
        val dur = 1_150_000_000L
        for ((color, rows) in listOf(PieceColor.BLACK to intArrayOf(0, 1), PieceColor.WHITE to intArrayOf(7, 6))) {
            for (rr in rows) {
                for (cc in 0 until 8) {
                    val type = initialPieceAt(rr, cc)
                    val homeX = ChessModels.squareCenterX(cc)
                    val homeZ = ChessModels.squareCenterZ(rr)
                    val src = pool.firstOrNull { !it.used && it.type == type && it.color == color }
                        ?: pool.firstOrNull { !it.used && it.color == color }
                    if (src != null) {
                        src.used = true
                        homers.add(
                            Flyer(
                                type, color,
                                src.x, src.y, src.z, src.rotY, src.rotZ,
                                homeX, 0f, homeZ, 0f, 0f,
                                0.45f, now, dur, -1, -1, null, null
                            )
                        )
                    } else {
                        // Nothing left to map: rise up from beneath the square.
                        homers.add(
                            Flyer(
                                type, color,
                                homeX, -2.2f, homeZ, 0f, 0f,
                                homeX, 0f, homeZ, 0f, 0f,
                                0f, now, dur, -1, -1, null, null
                            )
                        )
                    }
                }
            }
        }
        homing = true
    }

    private fun initialPieceAt(r: Int, c: Int): PieceType {
        if (r == 1 || r == 6) return PieceType.PAWN
        return when (c) {
            0, 7 -> PieceType.ROOK
            1, 6 -> PieceType.KNIGHT
            2, 5 -> PieceType.BISHOP
            3 -> PieceType.QUEEN
            else -> PieceType.KING
        }
    }

    private fun pick(sx: Float, sy: Float): Pair<Int, Int>? {
        if (!Matrix.invertM(invVP, 0, vp, 0)) return null
        val ndcX = 2f * sx / width - 1f
        val ndcY = 1f - 2f * sy / height
        val near = unproject(ndcX, ndcY, -1f)
        val far = unproject(ndcX, ndcY, 1f)
        val dy = far[1] - near[1]
        if (kotlin.math.abs(dy) < 1e-6f) return null
        val t = (0f - near[1]) / dy
        if (t < 0f) return null
        val hx = near[0] + (far[0] - near[0]) * t
        val hz = near[2] + (far[2] - near[2]) * t
        val c = floor(hx + 4f).toInt()
        val r = floor(hz + 4f).toInt()
        return if (r in 0 until 8 && c in 0 until 8) Pair(r, c) else null
    }

    private val tmp4 = FloatArray(4)
    private val out4 = FloatArray(4)
    private fun unproject(x: Float, y: Float, z: Float): FloatArray {
        tmp4[0] = x; tmp4[1] = y; tmp4[2] = z; tmp4[3] = 1f
        Matrix.multiplyMV(out4, 0, invVP, 0, tmp4, 0)
        val w = out4[3]
        return floatArrayOf(out4[0] / w, out4[1] / w, out4[2] / w)
    }

    // ---------------------------------------------------------------- helpers

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "renderer error", t) }
    }

    private fun pieceValue(t: PieceType): Int = when (t) {
        PieceType.PAWN -> 1
        PieceType.KNIGHT -> 3
        PieceType.BISHOP -> 3
        PieceType.ROOK -> 5
        PieceType.QUEEN -> 9
        PieceType.KING -> 0
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
    private fun smoother(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun copy(dst: FloatArray, src: FloatArray) {
        dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]
    }

    private fun normalizeInto(v: FloatArray) {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len > 1e-6f) { v[0] /= len; v[1] /= len; v[2] /= len }
    }

    companion object {
        private const val TAG = "ChessRenderer"

        private const val SHAKE_DUR_NS = 620_000_000f

        // How long the best-move hint flash lingers.
        private const val HINT_DUR_NS = 3_400_000_000L

        private fun normalize(v: FloatArray): FloatArray {
            val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) Log.e(TAG, "Link error: " + GLES20.glGetProgramInfoLog(p))
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) Log.e(TAG, "Shader error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        private const val LIT_VS = """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec3 vWorld;
            varying vec3 vNormal;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vNormal = mat3(uModel) * aNormal;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val LIT_FS = """
            precision mediump float;
            varying vec3 vWorld;
            varying vec3 vNormal;
            uniform vec3 uColor;
            uniform vec3 uLightDir;
            uniform vec3 uCamPos;
            uniform float uShininess;
            uniform float uSpec;
            uniform float uAmbient;
            uniform float uAlpha;
            void main() {
                vec3 N = normalize(vNormal);
                vec3 L = normalize(uLightDir);
                vec3 V = normalize(uCamPos - vWorld);
                vec3 H = normalize(L + V);
                float diff = max(dot(N, L), 0.0);
                float spec = pow(max(dot(N, H), 0.0), uShininess) * uSpec;
                vec3 col = uColor * (uAmbient + diff * 0.85) + vec3(spec);
                col = pow(clamp(col, 0.0, 1.0), vec3(1.0 / 2.2));
                gl_FragColor = vec4(col, uAlpha);
            }
        """

        private const val OV_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            varying vec2 vUV;
            void main() {
                vUV = aPos.xz;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val OV_FS = """
            precision mediump float;
            varying vec2 vUV;
            uniform vec4 uColor;
            uniform float uFeather;
            uniform float uInner;
            void main() {
                float d = length(vUV);
                float a = uColor.a * (1.0 - smoothstep(uFeather, 1.0, d));
                if (uInner > 0.0) a *= smoothstep(uInner - 0.06, uInner + 0.02, d);
                gl_FragColor = vec4(uColor.rgb, a);
            }
        """
    }
}

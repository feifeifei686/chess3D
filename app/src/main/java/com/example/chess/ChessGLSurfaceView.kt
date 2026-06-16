package com.example.chess

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.MotionEvent

/** Hosts the [ChessRenderer] and forwards taps to it on the GL thread. */
class ChessGLSurfaceView(context: Context, callbacks: ChessRenderer.Callbacks) : GLSurfaceView(context) {

    val renderer = ChessRenderer(callbacks)

    init {
        setEGLContextClientVersion(2)
        // RGBA8 + 16-bit depth.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer.glView = this
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            queueEvent { renderer.handleTap(x, y) }
            return true
        }
        return super.onTouchEvent(event)
    }

    fun postBeginGame(mode: ChessRenderer.Mode, aiColor: PieceColor, depth: Int, characterId: String = "sato") =
        queueEvent { renderer.beginGame(mode, aiColor, depth, characterId) }

    fun postNewGame() = queueEvent { renderer.newGame() }
    fun postExitToHome() = queueEvent { renderer.exitToHome() }
    fun postToggleView() = queueEvent { renderer.toggleView() }
    fun postUndo() = queueEvent { renderer.requestUndo() }
    fun postHint() = queueEvent { renderer.requestHint() }

    // ---- screenshot ----

    /** Capture the current GL framebuffer as a Bitmap. Callback fires on the GL thread. */
    fun postScreenshot(callback: (Bitmap) -> Unit) =
        queueEvent { renderer.requestScreenshot(callback) }

    // ---- replay ----

    fun postBeginReplay(moves: List<MoveRecord>) =
        queueEvent { renderer.beginReplay(moves.map { it.toMove() }) }

    fun postReplayNext() = queueEvent { renderer.replayNext() }
    fun postReplayPrev() = queueEvent { renderer.replayPrev() }
    fun postReplayToggleAutoPlay() = queueEvent { renderer.replayToggleAutoPlay() }
    fun postEndReplay() = queueEvent { renderer.endReplay() }
}

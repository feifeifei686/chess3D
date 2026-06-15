package com.example.chess

/**
 * A small negamax + alpha-beta chess AI with material and piece-square
 * evaluation. Pure function of a [ChessGame] snapshot, so it is safe to run
 * on a background thread.
 */
object ChessAI {

    private const val INF = 1_000_000
    private const val MATE = 100_000

    fun bestMove(root: ChessGame, depth: Int): Move? {
        val moves = ordered(root)
        if (moves.isEmpty()) return null
        var best: Move? = null
        var bestScore = -INF
        var alpha = -INF
        for (m in moves) {
            val g = root.snapshot()
            g.makeMove(m)
            // A tiny jitter so equal-valued openings vary between games.
            val score = -negamax(g, depth - 1, -INF, -alpha) + ((Math.random() * 9).toInt() - 4)
            if (score > bestScore) { bestScore = score; best = m }
            if (score > alpha) alpha = score
        }
        return best
    }

    private fun negamax(g: ChessGame, depth: Int, alpha: Int, beta: Int): Int {
        if (depth == 0) return evaluate(g)
        val moves = ordered(g)
        if (moves.isEmpty()) return if (g.inCheck()) -MATE - depth else 0
        var a = alpha
        var best = -INF
        for (m in moves) {
            val g2 = g.snapshot()
            g2.makeMove(m)
            val s = -negamax(g2, depth - 1, -beta, -a)
            if (s > best) best = s
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }

    /** Captures and promotions first, to make alpha-beta cut earlier. */
    private fun ordered(g: ChessGame): List<Move> {
        val moves = g.allLegalMoves()
        return moves.sortedByDescending { m ->
            var s = 0
            val victim = g.board[m.toR][m.toC]
            if (victim != null) s += 10 * value(victim.type)
            if (m.isEnPassant) s += 10 * value(PieceType.PAWN)
            if (m.promotion != null) s += value(m.promotion)
            s
        }
    }

    /** Score from the perspective of the side to move in [g]. */
    private fun evaluate(g: ChessGame): Int {
        var score = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = g.board[r][c] ?: continue
            val v = value(p.type) + pst(p.type, p.color, r, c)
            score += if (p.color == g.turn) v else -v
        }
        return score
    }

    private fun value(t: PieceType): Int = when (t) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT -> 320
        PieceType.BISHOP -> 330
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        PieceType.KING -> 20000
    }

    // Piece-square tables from White's view (row 7 = White's first rank).
    private fun pst(t: PieceType, color: PieceColor, r: Int, c: Int): Int {
        val row = if (color == PieceColor.WHITE) r else 7 - r
        val table = when (t) {
            PieceType.PAWN -> PAWN
            PieceType.KNIGHT -> KNIGHT
            PieceType.BISHOP -> BISHOP
            PieceType.ROOK -> ROOK
            PieceType.QUEEN -> QUEEN
            PieceType.KING -> KING
        }
        return table[row * 8 + c]
    }

    private val PAWN = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )
    private val KNIGHT = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )
    private val BISHOP = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20
    )
    private val ROOK = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0
    )
    private val QUEEN = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20
    )
    private val KING = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20
    )
}

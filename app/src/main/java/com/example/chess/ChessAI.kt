package com.example.chess

/**
 * Enhanced negamax + alpha-beta chess AI with quiescence search,
 * iterative deepening, killer moves, history heuristic, and
 * check extensions. Pure function of a [ChessGame] snapshot,
 * so it is safe to run on a background thread.
 */
object ChessAI {

    private const val INF = 1_000_000
    private const val MATE = 100_000
    private const val MAX_QS_PLY = 6

    // --- killer moves: two slots per search depth ---
    private val killerMoves = Array(64) { arrayOfNulls<Move>(2) }

    // --- history heuristic: [pieceType.ordinal][toSquare] ---
    private val historyTable = Array(6) { IntArray(64) }

    // --- principal variation from the last completed iteration ---
    private var pvMove: Move? = null

    /** Static evaluation from Black's point of view (used for the win-rate curve). */
    fun evaluateFromBlackPerspective(g: ChessGame): Int {
        val score = evaluate(g) // from g.turn's perspective
        return if (g.turn == PieceColor.BLACK) score else -score
    }

    /** Map centipawn evaluation to a 0-100 win-rate percentage via a logistic curve. */
    fun evalToWinRate(cp: Int): Float {
        return (100.0 / (1.0 + Math.exp(-cp / 400.0))).toFloat()
    }

    /**
     * Best move via iterative deepening from depth 1..[maxDepth].
     * Returns the best move found at the deepest completed iteration.
     */
    fun bestMove(root: ChessGame, maxDepth: Int, timeBudgetMs: Long = 10_000): Move? {
        val moves = ordered(root, 0)
        if (moves.isEmpty()) return null

        // Decay history table so recent cutoffs dominate.
        for (t in 0 until 6) for (s in 0 until 64) {
            historyTable[t][s] = historyTable[t][s] / 2
        }
        pvMove = null

        val startNano = System.nanoTime()
        var best: Move? = null

        for (d in 1..maxDepth) {
            val elapsed = (System.nanoTime() - startNano) / 1_000_000
            if (elapsed > timeBudgetMs * 0.7) break

            var bestScore = -INF
            var alpha = -INF
            var currentBest: Move? = null

            for (m in moves) {
                val g = root.snapshot()
                g.makeMove(m)
                val actualDepth = if (g.inCheck()) d else d - 1
                val score = -negamax(g, actualDepth, -INF, -alpha)
                // Tiny jitter at the root only, so equal-valued openings vary.
                val jittered = score + ((Math.random() * 9).toInt() - 4)
                if (jittered > bestScore) {
                    bestScore = jittered
                    currentBest = m
                }
                if (score > alpha) alpha = score
            }

            if (currentBest != null) {
                best = currentBest
                pvMove = currentBest
                // If we found a forced mate, no need to search deeper.
                if (bestScore > MATE - 100) break
            }
        }
        return best
    }

    // ===================================================================
    //  Negamax with check extension
    // ===================================================================

    private fun negamax(g: ChessGame, depth: Int, alpha: Int, beta: Int): Int {
        if (depth <= 0) return quiescence(g, alpha, beta, 0)

        val moves = ordered(g, depth)
        if (moves.isEmpty()) return if (g.inCheck()) -MATE - depth else 0

        var a = alpha
        var best = -INF
        val inCheck = g.inCheck()

        for (m in moves) {
            val g2 = g.snapshot()
            g2.makeMove(m)
            // Check extension: search one ply deeper when the opponent is in check.
            val ext = if (!inCheck && g2.inCheck()) 1 else 0
            val s = -negamax(g2, depth - 1 + ext, -beta, -a)
            if (s > best) best = s
            if (best > a) a = best
            if (a >= beta) {
                // Store killer move if it's a quiet move.
                if (g.board[m.toR][m.toC] == null && m.promotion == null) {
                    storeKiller(depth, m)
                }
                // Update history heuristic.
                val piece = g.board[m.fromR][m.fromC]
                if (piece != null) {
                    val idx = m.toR * 8 + m.toC
                    historyTable[piece.type.ordinal][idx] += depth * depth
                }
                break
            }
        }
        return best
    }

    // ===================================================================
    //  Quiescence search — only captures to quiet the position
    // ===================================================================

    private fun quiescence(g: ChessGame, alpha: Int, beta: Int, ply: Int): Int {
        if (ply >= MAX_QS_PLY) return evaluate(g)

        val standPat = evaluate(g)
        if (standPat >= beta) return beta
        var a = alpha
        if (standPat > a) a = standPat

        // If in check, search ALL legal moves (must escape check).
        val captureOnly = !g.inCheck()
        val moves = if (captureOnly) captureMoves(g) else ordered(g, 0)
        // Sort captures by MVV-LVA.
        val sorted = if (captureOnly) moves.sortedByDescending { mvvLva(it, g) } else moves

        for (m in sorted) {
            val g2 = g.snapshot()
            g2.makeMove(m)
            val s = -quiescence(g2, -beta, -a, ply + 1)
            if (s >= beta) return beta
            if (s > a) a = s
        }
        return a
    }

    /** Generate only capture moves (including en passant and promotions). */
    private fun captureMoves(g: ChessGame): List<Move> {
        val all = g.allLegalMoves()
        return all.filter { m ->
            g.board[m.toR][m.toC] != null || m.isEnPassant || m.promotion != null
        }
    }

    /** MVV-LVA score for capture ordering in quiescence. */
    private fun mvvLva(m: Move, g: ChessGame): Int {
        val victim = g.board[m.toR][m.toC]
        val attacker = g.board[m.fromR][m.fromC]
        val victimVal = if (victim != null) value(victim.type) else
            if (m.isEnPassant) value(PieceType.PAWN) else 0
        val attackerVal = if (attacker != null) value(attacker.type) else 0
        val promoBonus = if (m.promotion != null) value(m.promotion) else 0
        return victimVal * 10 - attackerVal + promoBonus
    }

    // ===================================================================
    //  Move ordering with killer moves and history heuristic
    // ===================================================================

    /** Captures and promotions first, then killer moves, then history. */
    private fun ordered(g: ChessGame, depth: Int): List<Move> {
        val moves = g.allLegalMoves()
        return moves.sortedByDescending { m ->
            var s = 0
            val victim = g.board[m.toR][m.toC]
            if (victim != null) s += 10 * value(victim.type)
            if (m.isEnPassant) s += 10 * value(PieceType.PAWN)
            if (m.promotion != null) s += value(m.promotion)

            // PV move from previous iteration gets top priority among quiet moves.
            if (pvMove != null && m.fromR == pvMove!!.fromR && m.fromC == pvMove!!.fromC
                && m.toR == pvMove!!.toR && m.toC == pvMove!!.toC) {
                s += 10_000
            }

            // Killer move bonus.
            if (killerMoves[depth][0] != null &&
                m.fromR == killerMoves[depth][0]!!.fromR && m.fromC == killerMoves[depth][0]!!.fromC
                && m.toR == killerMoves[depth][0]!!.toR && m.toC == killerMoves[depth][0]!!.toC) {
                s += 9000
            }
            if (killerMoves[depth][1] != null &&
                m.fromR == killerMoves[depth][1]!!.fromR && m.fromC == killerMoves[depth][1]!!.fromC
                && m.toR == killerMoves[depth][1]!!.toR && m.toC == killerMoves[depth][1]!!.toC) {
                s += 8000
            }

            // History heuristic bonus.
            val piece = g.board[m.fromR][m.fromC]
            if (piece != null) {
                val idx = m.toR * 8 + m.toC
                s += historyTable[piece.type.ordinal][idx] / 16
            }
            s
        }
    }

    private fun storeKiller(depth: Int, m: Move) {
        val slot0 = killerMoves[depth][0]
        if (slot0 == null || slot0.fromR != m.fromR || slot0.fromC != m.fromC
            || slot0.toR != m.toR || slot0.toC != m.toC) {
            killerMoves[depth][1] = slot0
            killerMoves[depth][0] = m
        }
    }

    // ===================================================================
    //  Static evaluation
    // ===================================================================

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

    fun value(t: PieceType): Int = when (t) {
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

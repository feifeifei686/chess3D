package com.example.chess

import kotlin.random.Random
import kotlin.math.ln
import kotlin.math.max

/**
 * Chess engine — Principal Variation Search (PVS) with:
 * - Tapered evaluation (PeSTO PSTs + king safety + pawn structure)
 * - Iterative deepening with aspiration windows
 * - Transposition table (Zobrist hashing, 16 MB)
 * - Late-move reductions (logarithmic formula)
 * - Null-move pruning (adaptive R)
 * - Futility pruning (shallow-depth quiet-move skipping)
 * - Razoring (very-shallow drop-to-qsearch)
 * - Lazy eval cutoff (material-only stand-pat)
 * - SEE pruning (skip losing captures in search and qsearch)
 * - Check extensions
 * - Piece-to + continuation history for move ordering
 * - Killer moves (2 slots per ply)
 * - Multi-level difficulty via root-score noise + suboptimal selection
 */
object ChessAI {

    private const val INF  = 1_000_000
    private const val MATE = 100_000
    private const val MAX_QS_PLY = 8
    private const val MAX_PLY = 64

    // ---- killer moves: 2 slots per ply ----
    private val killerMoves = Array(MAX_PLY) { arrayOfNulls<Move>(2) }

    // ---- history tables (piece-to + continuation) ----
    private val history = History()

    // ---- transposition table (16 MB, finally used!) ----
    private val tt = TranspositionTable(sizeMb = 16)

    // ---- RNG for difficulty noise ----
    private val noiseRng = Random(0xCAFE_BABE)

    // ---- time management ----
    private var startNano = 0L
    private var timeBudgetMs = 0L
    private var searchAborted = false

    // ---- current difficulty level (controls root noise) ----
    private var difficultyDepth = 3

    // ===================================================================
    //  Public API
    // ===================================================================

    /** Static evaluation from Black's point of view (for win-rate curve). */
    fun evaluateFromBlackPerspective(g: ChessGame): Int {
        val score = Evaluation.evaluate(g)  // full eval with mobility
        return if (g.turn == PieceColor.BLACK) score else -score
    }

    /** Map centipawn evaluation to a 0-100 win-rate percentage via a logistic curve. */
    fun evalToWinRate(cp: Int): Float {
        return (100.0 / (1.0 + Math.exp(-cp / 400.0))).toFloat()
    }

    /** Piece material value (for capture ordering). */
    fun value(t: PieceType): Int = Evaluation.materialValue(t)

    /**
     * Best move via iterative deepening from depth 1..[maxDepth].
     * [maxDepth] doubles as difficulty: lower depths get root-score noise
     * and occasionally pick suboptimal moves.
     * [timeBudgetMs] defaults to 5000 ms (5 seconds hard limit).
     */
    fun bestMove(root: ChessGame, maxDepth: Int, timeBudgetMs: Long = 5_000): Move? {
        val moves = root.allLegalMoves()
        if (moves.isEmpty()) return null

        startNano = System.nanoTime()
        this.timeBudgetMs = timeBudgetMs
        this.difficultyDepth = maxDepth
        searchAborted = false

        // Age history tables so recent cutoffs dominate
        history.age()

        // Clear per-search state
        for (i in 0 until MAX_PLY) { killerMoves[i][0] = null; killerMoves[i][1] = null }

        // Root hash for TT lookups
        val rootHash = Zobrist.computeHash(root)

        var best: Move? = null
        var bestScore = -INF

        for (d in 1..maxDepth) {
            if (elapsed() > timeBudgetMs * 0.6 || searchAborted) break  // soft stop

            val prevScore = bestScore
            var alpha = if (d <= 2) -INF else prevScore - 50
            var beta  = if (d <= 2)  INF else prevScore + 50
            var research = true
            var bestScoreIter = -INF
            var currentBest: Move? = null

            while (research) {
                research = false
                bestScoreIter = -INF
                currentBest = null

                for ((idx, m) in moves.withIndex()) {
                    if (elapsed() > timeBudgetMs) { searchAborted = true; break }  // hard stop

                    val newHash = Zobrist.applyMoveToHash(root, m, rootHash)
                    val g = root.snapshot()
                    g.makeMove(m)

                    val score: Int
                    if (idx == 0) {
                        // First move: full window (PV node)
                        score = -negamax(g, d - 1, -beta, -alpha, 1, newHash,
                            isPv = true, prevPiece = -1, prevToSq = -1)
                    } else {
                        // PVS: zero-window scout
                        var sc = -negamax(g, d - 1, -alpha - 1, -alpha, 1, newHash,
                            isPv = false, prevPiece = -1, prevToSq = -1)

                        // If scout beats alpha, re-search with full window
                        if (sc > alpha && sc < beta) {
                            sc = -negamax(g, d - 1, -beta, -alpha, 1, newHash,
                                isPv = true, prevPiece = -1, prevToSq = -1)
                        }
                        score = sc
                    }

                    if (score > bestScoreIter) {
                        bestScoreIter = score
                        currentBest = m
                    }
                    if (score > alpha) alpha = score
                }

                // All moves failed low or search aborted
                if (currentBest == null) {
                    if (best != null) { currentBest = best; bestScoreIter = bestScore }
                    else break
                }

                // Score outside aspiration window? Re-search with full window
                if (d > 2 && !searchAborted &&
                    (bestScoreIter <= prevScore - 50 || bestScoreIter >= prevScore + 50)) {
                    alpha = -INF; beta = INF; research = true
                }
            }

            if (searchAborted) break
            if (currentBest != null) {
                best = currentBest
                bestScore = bestScoreIter
                if (bestScore > MATE - 100) break  // forced mate found
            }
        }

        // Apply difficulty noise to root scores and select
        return maybeSuboptimal(best, moves, bestScore, maxDepth, root)
    }

    /**
     * Quick position evaluation via shallow iterative-deepening search.
     * Returns a centipawn score from **Black's perspective** — positive means
     * Black is better, negative means White is better.
     *
     * Used by the win-rate display / curve.  Unlike the static [evaluateFromBlackPerspective],
     * this actually searches tactics and captures, so the displayed percentages
     * reflect what the engine truly sees rather than just material count.
     *
     * @param root          position snapshot
     * @param timeBudgetMs  soft time limit (default 1500 ms — fast enough for UI, deep enough for tactics)
     */
    fun quickEvalBlack(root: ChessGame, timeBudgetMs: Long = 1500): Int {
        val moves = root.allLegalMoves()
        if (moves.isEmpty()) {
            // Checkmate or stalemate
            return if (root.inCheck()) -MATE else 0
        }

        startNano = System.nanoTime()
        this.timeBudgetMs = timeBudgetMs
        searchAborted = false

        history.age()
        for (i in 0 until MAX_PLY) { killerMoves[i][0] = null; killerMoves[i][1] = null }

        val rootHash = Zobrist.computeHash(root)
        var bestScore = -INF

        for (d in 1..5) {
            if (elapsed() > timeBudgetMs * 0.6 || searchAborted) break

            val prevScore = bestScore
            var alpha = if (d <= 2) -INF else prevScore - 50
            var beta  = if (d <= 2)  INF else prevScore + 50

            for (m in moves) {
                if (elapsed() > timeBudgetMs) { searchAborted = true; break }

                val newHash = Zobrist.applyMoveToHash(root, m, rootHash)
                val g = root.snapshot()
                g.makeMove(m)

                val score = -negamax(g, d - 1, -beta, -alpha, 1, newHash,
                    isPv = true, prevPiece = -1, prevToSq = -1)

                if (score > bestScore) bestScore = score
                if (score > alpha) alpha = score
            }
        }

        // bestScore is from root.turn's perspective; convert to Black's
        return if (root.turn == PieceColor.BLACK) bestScore else -bestScore
    }

    private fun elapsed(): Long = (System.nanoTime() - startNano) / 1_000_000

    // ===================================================================
    //  Negamax with PVS, TT, pruning suite
    // ===================================================================

    /**
     * Core search function — PVS negamax with all pruning integrated.
     *
     * @param g          position snapshot
     * @param depth      remaining search depth
     * @param alpha      lower bound
     * @param beta       upper bound
     * @param ply        distance from root
     * @param hash       Zobrist hash of [g]
     * @param isPv       true for PV nodes (first child of a PV parent)
     * @param prevPiece  piece type ordinal of the previous move, or -1
     * @param prevToSq   destination square of the previous move, or -1
     */
    private fun negamax(
        g: ChessGame, depth: Int, alpha: Int, beta: Int, ply: Int,
        hash: Long, isPv: Boolean, prevPiece: Int, prevToSq: Int
    ): Int {
        // ---- TT probe (non-PV only; PV uses TT only for move ordering) ----
        if (!isPv) {
            val ttScore = tt.probe(hash, depth, alpha, beta)
            if (ttScore != null) return adjustTtScore(ttScore, ply)
        }
        val ttMove = tt.bestMove(hash)

        // ---- Leaf: enter quiescence ----
        if (depth <= 0) return quiescence(g, alpha, beta, 0, hash)

        // ---- Safety: max ply ----
        if (ply >= MAX_PLY) return Evaluation.evaluateFast(g)

        val inCheck = g.inCheck()

        // ---- Static evaluation (for pruning heuristics) ----
        val staticEval = if (!inCheck) Evaluation.evaluateFast(g) else -INF

        // ---- Null-move pruning ----
        if (!isPv && !inCheck && depth >= 3 && !g.isLikelyZugzwang()) {
            val g2 = g.snapshot()
            g2.makeNullMove()
            val R = 3 + depth / 6
            val nullHash = Zobrist.computeHash(g2)
            val score = -negamax(g2, depth - 1 - R, -beta, -beta + 1, ply + 1,
                nullHash, false, -1, -1)
            if (score >= beta) return beta
        }

        // ---- Razoring: at depth 1, drop to qsearch if way below alpha ----
        if (!isPv && !inCheck && depth == 1) {
            val mat = Evaluation.evaluateMaterialOnly(g)
            if (mat + 300 <= alpha) {
                return quiescence(g, alpha, beta, 0, hash)
            }
        }

        // ---- Lazy eval: stand-pat cutoff at shallow depth ----
        if (!isPv && !inCheck && depth <= 2) {
            val mat = Evaluation.evaluateMaterialOnly(g)
            if (mat - 500 >= beta) return beta
        }

        // ---- Generate and order moves ----
        val moves = ordered(g, ply, ttMove)
        if (moves.isEmpty()) return if (inCheck) -MATE - ply else 0

        // ---- Futility margin for shallow depths ----
        val futilityMargin: Int? = if (!isPv && !inCheck && depth <= 3) {
            // margins by depth: 0:0, 1:0, 2:100, 3:200
            when (depth) {
                1 -> 100
                2 -> 200
                else -> 400  // depth 3
            }
        } else null

        var a = alpha
        var bestScore = -INF
        var bestMove: Move? = null
        var moveCount = 0
        val improving = !inCheck && staticEval != -INF

        for (m in moves) {
            // Apply move
            val newHash = Zobrist.applyMoveToHash(g, m, hash)
            val g2 = g.snapshot()
            g2.makeMove(m)

            moveCount++
            val givesCheck = g2.inCheck()

            // Move classification
            val isCapture = g.board[m.toR][m.toC] != null || m.isEnPassant
            val isPromotion = m.promotion != null
            val isQuiet = !isCapture && !isPromotion
            val isKiller = isQuiet && (
                (killerMoves[ply][0] != null && moveEquals(m, killerMoves[ply][0]!!)) ||
                (killerMoves[ply][1] != null && moveEquals(m, killerMoves[ply][1]!!))
            )

            // ---- Futility pruning: skip quiet moves at shallow depth ----
            if (futilityMargin != null && isQuiet && !givesCheck && !isKiller) {
                if (staticEval + futilityMargin <= a) continue
            }

            // ---- SEE pruning: skip losing captures at shallow depth ----
            if (isCapture && !isPromotion && depth <= 3 && !givesCheck) {
                if (!SEE.see_ge(g, m, -50 * depth)) continue
            }

            // Check extension
            val ext = if (givesCheck) 1 else 0

            // ---- LMR reduction ----
            var reduction = 0
            if (depth >= 3 && moveCount >= 4 && isQuiet && ext == 0) {
                reduction = lmrReduction(depth, moveCount)
                if (isPv)      reduction = maxOf(0, reduction - 1)
                if (isKiller)  reduction = maxOf(0, reduction - 1)
                if (!improving) reduction += 1
            }
            val searchDepth = maxOf(0, depth - 1 + ext - reduction)

            // ---- PVS: first move full window, rest zero-window ----
            val score: Int
            if (moveCount == 1) {
                // Track context for continuation history
                val currPiece = g.board[m.fromR][m.fromC]
                val currIdx = currPiece?.type?.ordinal ?: -1
                val currSq = m.toR * 8 + m.toC

                score = -negamax(g2, searchDepth, -beta, -a, ply + 1, newHash,
                    isPv, currIdx, currSq)
            } else {
                // Zero-window scout
                val currIdx = g.board[m.fromR][m.fromC]?.type?.ordinal ?: -1
                val currSq = m.toR * 8 + m.toC

                var sc = -negamax(g2, searchDepth, -a - 1, -a, ply + 1, newHash,
                    false, currIdx, currSq)

                // LMR re-search: if reduced scout beat alpha, re-search without reduction
                if (reduction > 0 && sc > a) {
                    sc = -negamax(g2, depth - 1 + ext, -a - 1, -a, ply + 1, newHash,
                        false, currIdx, currSq)
                }

                // Scout beat alpha: full-window re-search
                if (sc > a && sc < beta) {
                    sc = -negamax(g2, depth - 1 + ext, -beta, -a, ply + 1, newHash,
                        true, currIdx, currSq)
                }
                score = sc
            }

            // Update best score
            if (score > bestScore) {
                bestScore = score
                bestMove = m
                if (score > a) a = score
            }

            // Beta cutoff
            if (a >= beta) {
                if (isQuiet) storeKiller(ply, m)
                val piece = g.board[m.fromR][m.fromC]
                if (piece != null) {
                    history.update(piece.type, m.toR * 8 + m.toC, depth, prevPiece, prevToSq)
                }
                tt.store(hash, depth, TranspositionTable.LOWER_BOUND,
                    storeTtScore(bestScore, ply), m)
                return bestScore
            }
        }

        // ---- Store result in TT ----
        val flag = if (bestScore <= alpha) TranspositionTable.UPPER_BOUND
                   else TranspositionTable.EXACT
        tt.store(hash, depth, flag, storeTtScore(bestScore, ply), bestMove)

        return bestScore
    }

    // ===================================================================
    //  Quiescence search with SEE and delta pruning
    // ===================================================================

    private fun quiescence(
        g: ChessGame, alpha: Int, beta: Int, qsPly: Int, hash: Long
    ): Int {
        if (qsPly >= MAX_QS_PLY) return Evaluation.evaluateFast(g)

        val standPat = Evaluation.evaluateFast(g)
        if (standPat >= beta) return beta
        var a = max(alpha, standPat)

        val inCheck = g.inCheck()
        val moves = if (inCheck) {
            // Must consider all legal moves when in check
            ordered(g, 0, null)
        } else {
            // Only captures and promotions
            g.allLegalMoves().filter { m ->
                g.board[m.toR][m.toC] != null || m.isEnPassant || m.promotion != null
            }.sortedByDescending { captureSortScore(it, g) }
        }

        for (m in moves) {
            // SEE pruning: skip losing captures (non-check state only)
            if (!inCheck && !SEE.see_ge(g, m, -50)) continue

            // Delta pruning
            if (!inCheck) {
                val victimVal = capturedValue(g, m)
                if (standPat + victimVal + 200 < a) continue
            }

            val newHash = Zobrist.applyMoveToHash(g, m, hash)
            val g2 = g.snapshot()
            g2.makeMove(m)
            val score = -quiescence(g2, -beta, -a, qsPly + 1, newHash)
            if (score >= beta) return beta
            if (score > a) a = score
        }
        return a
    }

    /** Sort score for captures in qsearch: MVV-LVA based. */
    private fun captureSortScore(m: Move, g: ChessGame): Int {
        var s = 0
        val victim = g.board[m.toR][m.toC]
        if (victim != null) s += 10 * value(victim.type)
        if (m.isEnPassant) s += 10 * value(PieceType.PAWN)
        if (m.promotion != null) s += value(m.promotion)
        val attacker = g.board[m.fromR][m.fromC]
        if (attacker != null) s -= value(attacker.type) / 10
        return s
    }

    private fun capturedValue(g: ChessGame, m: Move): Int {
        val victim = g.board[m.toR][m.toC]
        if (victim != null) return value(victim.type)
        if (m.isEnPassant) return value(PieceType.PAWN)
        if (m.promotion != null) return value(m.promotion) - value(PieceType.PAWN)
        return 0
    }

    // ===================================================================
    //  LMR reduction
    // ===================================================================

    /**
     * Logarithmic LMR reduction.
     * Formula: 0.5 + ln(depth) * ln(moveCount) / 2.2
     * Floor at 0. Typical range [0, 4].
     */
    private fun lmrReduction(depth: Int, moveCount: Int): Int {
        val r = 0.5 + ln(depth.toDouble()) * ln(moveCount.toDouble()) / 2.2
        return maxOf(0, r.toInt())
    }

    // ===================================================================
    //  Move ordering
    // ===================================================================

    /**
     * Order moves for best alpha-beta efficiency.
     * Priority (highest first):
     *   1. TT best move      (+20,000)
     *   2. Captures MVV-LVA  (+10 * victim - attacker + 10,000)
     *   3. Promotions        (+promoValue + 10,000)
     *   4. Killer moves      (+9,000 / +8,000)
     *   5. Piece-to history  (+history / 16)
     */
    private fun ordered(
        g: ChessGame, ply: Int, hashMove: Move?
    ): List<Move> {
        val moves = g.allLegalMoves()
        return moves.sortedByDescending { m ->
            var s = 0

            // TT / hash move
            if (hashMove != null && moveEquals(m, hashMove)) {
                s += 20_000
            }

            // Captures (MVV-LVA) and promotions
            val victim = g.board[m.toR][m.toC]
            if (victim != null) {
                val attacker = g.board[m.fromR][m.fromC]
                val attVal = if (attacker != null) value(attacker.type) else 0
                s += 10 * value(victim.type) - attVal + 10_000
            }
            if (m.isEnPassant) s += 10 * value(PieceType.PAWN) + 10_000
            if (m.promotion != null) s += value(m.promotion) + 10_000

            // Killer moves
            val k0 = killerMoves[ply][0]
            val k1 = killerMoves[ply][1]
            if (k0 != null && moveEquals(m, k0)) s += 9_000
            else if (k1 != null && moveEquals(m, k1)) s += 8_000

            // History heuristic (piece-to)
            val piece = g.board[m.fromR][m.fromC]
            if (piece != null) {
                s += history.score(piece.type, m.toR * 8 + m.toC) / 16
            }

            s
        }
    }

    // ===================================================================
    //  Difficulty scaling
    // ===================================================================

    /**
     * Apply difficulty-appropriate noise to root scores and potentially
     * select a suboptimal move at low depths.
     */
    private fun maybeSuboptimal(
        best: Move?, moves: List<Move>, bestScore: Int, maxDepth: Int, root: ChessGame
    ): Move? {
        if (best == null || moves.size <= 1) return best

        // At high depth, no suboptimal selection — trust the search
        if (maxDepth > 3) return best

        // Evaluate all root moves (with noise) to build a ranked pool
        val scored = moves.map { m ->
            val g = root.snapshot()
            g.makeMove(m)
            val base = -negamax(g, 0, -INF, INF, 1,
                Zobrist.computeHash(g), true, -1, -1)
            val stdDev = noiseStdDev(maxDepth)
            var noise = 0
            if (stdDev > 0) { repeat(4) { noise += noiseRng.nextInt(-stdDev, stdDev + 1) } }
            Pair(m, base + noise / 2)
        }.sortedByDescending { it.second }

        if (maxDepth <= 2) {
            // Pick randomly from top 3 (or fewer)
            val pool = scored.take(minOf(3, scored.size))
            return pool[noiseRng.nextInt(pool.size)].first
        }

        // Depth 3: 30% chance to pick 2nd best, but not when crushing
        if (scored.size >= 2 && noiseRng.nextInt(100) < 30 && bestScore < 300) {
            return scored[1].first
        }
        return best
    }

    /** Evaluation noise standard deviation for a given difficulty depth. */
    private fun noiseStdDev(depth: Int): Int = when {
        depth <= 2 -> 65
        depth == 3 -> 28
        depth == 4 -> 12
        depth == 5 -> 5
        depth == 6 -> 2
        else       -> 0
    }

    // ===================================================================
    //  TT score adjustment for mate distance
    // ===================================================================

    /** Convert a TT-stored score back to the current ply's perspective. */
    private fun adjustTtScore(stored: Int, ply: Int): Int {
        if (stored > MATE - MAX_PLY) return stored - ply
        if (stored < -MATE + MAX_PLY) return stored + ply
        return stored
    }

    /** Convert a search score to a TT-storable score (compensate for ply). */
    private fun storeTtScore(score: Int, ply: Int): Int {
        if (score > MATE - MAX_PLY) return score + ply
        if (score < -MATE + MAX_PLY) return score - ply
        return score
    }

    // ===================================================================
    //  Utilities
    // ===================================================================

    private fun moveEquals(a: Move, b: Move): Boolean =
        a.fromR == b.fromR && a.fromC == b.fromC &&
        a.toR == b.toR && a.toC == b.toC &&
        a.promotion == b.promotion

    private fun storeKiller(ply: Int, m: Move) {
        val slot0 = killerMoves[ply][0]
        if (slot0 == null || !moveEquals(m, slot0)) {
            killerMoves[ply][1] = slot0
            killerMoves[ply][0] = m
        }
    }
}

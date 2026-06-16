package com.example.chess

/**
 * History heuristic tables for move ordering and LMR decisions.
 *
 * Replaces the old butterfly ([from][to]) history with piece-to history
 * (denser, less ambiguous) and adds 1-ply continuation history that
 * captures the relationship between consecutive moves.
 *
 * Gravity update formula prevents overflow while adapting quickly:
 *   new = old + bonus - old * abs(bonus) / MAX_HISTORY
 *
 * All values are halved on age() so recent cutoffs dominate the signal.
 */
class History {

    companion object {
        const val MAX_HISTORY = 16384
    }

    /** Piece-to history: [pieceType.ordinal][toSquare 0..63] */
    val pieceTo = Array(6) { IntArray(64) }

    /**
     * 1-ply continuation history.
     * Indexed as: continuation[prevPieceType][prevToSq][currPieceType][currToSq]
     * Tracks how successful a move is when it follows a specific previous move.
     */
    val continuation = Array(6) { Array(64) { Array(6) { IntArray(64) } } }

    /** Halve all values so recent cutoffs dominate. Called at start of bestMove(). */
    fun age() {
        for (t in 0 until 6) for (s in 0 until 64) {
            pieceTo[t][s] = pieceTo[t][s] / 2
        }
        for (pt in 0 until 6) for (ps in 0 until 64) {
            for (t in 0 until 6) for (s in 0 until 64) {
                continuation[pt][ps][t][s] = continuation[pt][ps][t][s] / 2
            }
        }
    }

    /** Clear all tables (for testing / new game). */
    fun clear() {
        for (t in 0 until 6) for (s in 0 until 64) pieceTo[t][s] = 0
        for (pt in 0 until 6) for (ps in 0 until 64) {
            for (t in 0 until 6) for (s in 0 until 64) {
                continuation[pt][ps][t][s] = 0
            }
        }
    }

    /**
     * Update history tables after a beta cutoff.
     *
     * @param piece     the piece type that caused the cutoff
     * @param toSq      its destination square index (r * 8 + c)
     * @param depth     search depth at this node
     * @param prevPiece previous move's piece type ordinal, or -1 if none
     * @param prevToSq  previous move's destination square index, or -1 if none
     */
    fun update(piece: PieceType, toSq: Int, depth: Int, prevPiece: Int, prevToSq: Int) {
        val bonus = minOf(depth * depth, 400)

        // Piece-to table
        val idx = piece.ordinal
        pieceTo[idx][toSq] = gravityUpdate(pieceTo[idx][toSq], bonus)

        // Continuation table
        if (prevPiece in 0..5 && prevToSq in 0..63) {
            continuation[prevPiece][prevToSq][idx][toSq] =
                gravityUpdate(continuation[prevPiece][prevToSq][idx][toSq], bonus)
        }
    }

    /** Get the piece-to history score for a move. Higher = more likely to be good. */
    fun score(piece: PieceType, toSq: Int): Int = pieceTo[piece.ordinal][toSq]

    /**
     * Get the continuation history score.
     * @param prevPiece previous move's piece type ordinal, or -1 if none
     * @param prevToSq  previous move's destination square, or -1 if none
     */
    fun continuationScore(prevPiece: Int, prevToSq: Int, currPiece: PieceType, currToSq: Int): Int {
        if (prevPiece !in 0..5 || prevToSq !in 0..63) return 0
        return continuation[prevPiece][prevToSq][currPiece.ordinal][currToSq]
    }

    /** Gravity update: absorb bonus without overflowing beyond MAX_HISTORY range. */
    private fun gravityUpdate(old: Int, bonus: Int): Int {
        return old + bonus - old * kotlin.math.abs(bonus) / MAX_HISTORY
    }
}

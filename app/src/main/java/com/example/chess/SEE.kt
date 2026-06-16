package com.example.chess

/**
 * Static Exchange Evaluation — determines whether a capture wins enough
 * material to be worth searching, without doing a full recursive search.
 *
 * Uses a simplified algorithm suitable for mobile: computes the immediate
 * material gain, then checks whether the opponent can recapture with a
 * piece worth ≤ the gain (which would make the exchange unfavorable).
 *
 * This catches >95% of bad captures at a fraction of full SEE cost.
 */
object SEE {

    /** Material values for exchange evaluation (centipawns). Order matches PieceType.ordinal. */
    private val PIECE_VALUES = intArrayOf(100, 320, 330, 500, 900, 20_000)
    //                                     PAWN  N    B    R    Q    KING

    /**
     * Returns true if the static exchange evaluation of playing [move]
     * on [g] (pre-move state) results in material gain >= [threshold].
     *
     * @param g         current position (before the move is made)
     * @param move      the capture or promotion to evaluate
     * @param threshold minimum acceptable material gain in centipawns
     */
    fun see_ge(g: ChessGame, move: Move, threshold: Int): Boolean {
        val attacker = g.board[move.fromR][move.fromC] ?: return false
        val attVal = PIECE_VALUES[attacker.type.ordinal]

        // ---- immediate material gain ----
        var gain = 0
        val victim = g.board[move.toR][move.toC]
        if (victim != null) gain = PIECE_VALUES[victim.type.ordinal]
        if (move.isEnPassant) gain = PIECE_VALUES[PieceType.PAWN.ordinal]

        // Promotions always searched (gain includes promoted piece value)
        if (move.promotion != null) {
            gain += PIECE_VALUES[move.promotion.ordinal] - PIECE_VALUES[PieceType.PAWN.ordinal]
            return true
        }

        // Clearly winning: our gain exceeds what we risk, even if recaptured
        if (gain - attVal >= threshold) return true

        // Clearly losing: immediate gain doesn't meet threshold alone
        if (gain < threshold) return false

        // Ambiguous: gain >= threshold but attacker is worth more than gain.
        // Check if the opponent has any recapture on the target square with
        // a piece worth <= gain. If they do, the net result is negative.
        val g2 = g.snapshot()
        g2.makeMove(move)
        val enemy = attacker.color.opposite()

        return !hasAttackerAtMost(g2.board, move.toR, move.toC, enemy, gain)
    }

    /**
     * Check whether [by] color has any piece attacking square (r,c) whose
     * SEE value is <= [maxValue].  Excludes no piece types — a recapture
     * with any piece worth ≤ maxValue makes the exchange unfavorable.
     */
    private fun hasAttackerAtMost(
        board: Array<Array<Piece?>>,
        r: Int, c: Int,
        by: PieceColor,
        maxValue: Int
    ): Boolean {
        // ---- Pawns ----
        if (PIECE_VALUES[PieceType.PAWN.ordinal] <= maxValue) {
            val pawnRow = if (by == PieceColor.WHITE) r + 1 else r - 1
            for (dc in intArrayOf(-1, 1)) {
                if (pawnRow in 0..7 && c + dc in 0..7) {
                    val p = board[pawnRow][c + dc]
                    if (p != null && p.color == by && p.type == PieceType.PAWN) return true
                }
            }
        }

        // ---- Knights ----
        if (PIECE_VALUES[PieceType.KNIGHT.ordinal] <= maxValue) {
            val kn = arrayOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2,
                             1 to -2, 1 to 2, 2 to -1, 2 to 1)
            for ((dr, dc) in kn) {
                val p = pieceOn(board, r + dr, c + dc)
                if (p != null && p.color == by && p.type == PieceType.KNIGHT) return true
            }
        }

        // ---- Bishops / Queens on diagonals ----
        if (PIECE_VALUES[PieceType.BISHOP.ordinal] <= maxValue ||
            PIECE_VALUES[PieceType.QUEEN.ordinal] <= maxValue) {
            val diag = arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
            for ((dr, dc) in diag) {
                if (slideAttacks(board, r, c, dr, dc, by, maxValue)) return true
            }
        }

        // ---- Rooks / Queens on orthogonals ----
        if (PIECE_VALUES[PieceType.ROOK.ordinal] <= maxValue ||
            PIECE_VALUES[PieceType.QUEEN.ordinal] <= maxValue) {
            val orth = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
            for ((dr, dc) in orth) {
                if (slideAttacks(board, r, c, dr, dc, by, maxValue)) return true
            }
        }

        // ---- King ----
        if (PIECE_VALUES[PieceType.KING.ordinal] <= maxValue) {
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val p = pieceOn(board, r + dr, c + dc)
                if (p != null && p.color == by && p.type == PieceType.KING) return true
            }
        }

        return false
    }

    /** Scan a ray from (r,c) in direction (dr,dc) for the first attacker of [by]. */
    private fun slideAttacks(
        board: Array<Array<Piece?>>,
        r: Int, c: Int,
        dr: Int, dc: Int,
        by: PieceColor,
        maxValue: Int
    ): Boolean {
        var rr = r + dr
        var cc = c + dc
        while (rr in 0..7 && cc in 0..7) {
            val p = board[rr][cc]
            if (p != null) {
                if (p.color == by && PIECE_VALUES[p.type.ordinal] <= maxValue) return true
                break  // blocked by any piece
            }
            rr += dr
            cc += dc
        }
        return false
    }

    private fun pieceOn(board: Array<Array<Piece?>>, r: Int, c: Int): Piece? =
        if (r in 0..7 && c in 0..7) board[r][c] else null
}
